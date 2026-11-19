import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Dict, Tuple

import cv2
import numpy as np
import torch

from models.media_utils import (
    atomic_download_file,
    emit_progress,
    encode_preview_b64,
    finalize_video_export,
    infer_tiled_frame,
    is_image_file,
    make_even_dims,
    open_video_capture,
    open_video_writer,
    write_frame_fit,
)

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
FP16 = torch.cuda.is_available()

UPSCALER_MODELS = {
    "2x-sudo-UltraCompact": {
        "name": "2x-sudo-UltraCompact (Ultra-Fast Anime 2X)",
        "url": "https://objectstorage.us-phoenix-1.oraclecloud.com/n/ax6ygfvpvzka/b/open-modeldb-files/o/2x-sudo-UltraCompact.pth",
        "scale": 2,
    },
    "Real-ESRGAN Anime": {
        "name": "Real-ESRGAN Anime v3 2X Compact",
        "url": "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-animevideov3.pth",
        "scale": 2,
    },
    "Real-ESRGAN General": {
        "name": "Real-ESRGAN Movie Model 2X",
        "url": "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth",
        "scale": 2,
    },
}

VALID_TARGET_QUALITIES = frozenset({
    "2×",
    "2x",
    "4×",
    "4x",
    "Auto (Native)",
    "Auto (2× Native)",
    "1080p (FHD)",
    "2K (1440p)",
    "4K (2160p)",
})

_upscale_lock = threading.Lock()
_loaded_models: Dict[str, Any] = {}


def compute_target_dimensions(
    src_w: int,
    src_h: int,
    target_quality: str,
    model_key: str,
) -> Tuple[int, int]:
    """
    Orientation-aware, aspect-preserving target resolution policy.
    Targets short-edge heights (1080/1440/2160) while clamping long edge <= 4320
    and enforcing encoder-compatible even dimensions.
    """
    if src_w <= 0 or src_h <= 0:
        raise ValueError(f"Invalid source dimensions: {src_w}x{src_h}")
    if model_key not in UPSCALER_MODELS:
        raise ValueError(f"Unsupported upscaler model: {model_key}")

    native_scale = int(UPSCALER_MODELS[model_key]["scale"])
    q = (target_quality or "Auto (Native)").lower()

    if "4×" in q or "4x" in q:
        # 4× = two 2× passes (multipass).
        raw_w = src_w * 4
        raw_h = src_h * 4
    elif "2×" in q or "2x" in q:
        # Plain 2× always doubles the source, regardless of model native scale.
        raw_w = src_w * 2
        raw_h = src_h * 2
    elif "auto" in q or "native" in q:
        raw_w = src_w * native_scale
        raw_h = src_h * native_scale
    else:
        if "1080" in q or "fhd" in q:
            target_short = 1080
        elif "2k" in q or "1440" in q:
            target_short = 1440
        elif "4k" in q or "2160" in q:
            target_short = 2160
        else:
            target_short = 0

        if target_short <= 0:
            raw_w = src_w * native_scale
            raw_h = src_h * native_scale
        else:
            short_side = min(src_w, src_h)
            long_side = max(src_w, src_h)
            scale = target_short / float(short_side)
            max_long = 4320.0
            if long_side * scale > max_long:
                scale = max_long / float(long_side)
            raw_w = int(round(src_w * scale))
            raw_h = int(round(src_h * scale))

    return make_even_dims(raw_w, raw_h)


def get_upscaler_model(model_key: str = "2x-sudo-UltraCompact"):
    if model_key not in UPSCALER_MODELS:
        raise ValueError(f"Unsupported upscaler model '{model_key}'.")

    with _upscale_lock:
        if model_key in _loaded_models:
            return _loaded_models[model_key], int(UPSCALER_MODELS[model_key]["scale"])

        meta = UPSCALER_MODELS[model_key]
        weights_dir = Path(tempfile.gettempdir()) / "upscaler_models"
        weights_dir.mkdir(parents=True, exist_ok=True)

        weights_path = weights_dir / f"{model_key.replace(' ', '_')}.pth"
        atomic_download_file([meta["url"]], weights_path, min_bytes=50_000)

        try:
            from spandrel import ModelLoader

            loader = ModelLoader()
            model = loader.load_from_file(str(weights_path))
            model.to(DEVICE)
            if FP16:
                model.half()
            model.eval()
        except Exception as e:
            raise RuntimeError(f"Failed to load upscaler model '{model_key}': {e}") from e

        _loaded_models[model_key] = model
        return model, int(meta["scale"])


@torch.inference_mode()
def _upscale_tile(model, tile_bgr: np.ndarray, model_scale: int = 2) -> np.ndarray:
    img_rgb = cv2.cvtColor(tile_bgr, cv2.COLOR_BGR2RGB)
    img_t = torch.from_numpy(img_rgb).permute(2, 0, 1).float().unsqueeze(0) / 255.0
    img_t = img_t.to(DEVICE)
    if FP16:
        img_t = img_t.half()

    out_t = model(img_t)

    # When using CompactArch (e.g. realesr-animevideov3 with internal scale=4)
    # for a 2X target scale, downscale 0.5x via bicubic for maximum sharpness and 6 FPS
    if getattr(model, "scale", None) == 4 and model_scale == 2:
        out_t = torch.nn.functional.interpolate(
            out_t.float(),
            scale_factor=0.5,
            mode="bicubic",
            align_corners=False,
            antialias=True
        )

    out_np = out_t.squeeze(0).permute(1, 2, 0).float().clamp(0, 1).cpu().numpy()
    return cv2.cvtColor((out_np * 255.0).astype(np.uint8), cv2.COLOR_RGB2BGR)


def apply_crisp_outline(frame_bgr: np.ndarray, strength: float = 0.85) -> np.ndarray:
    """Deepen line art and outlines with an edge-masked unsharp mask on luminance.

    Sharpening only the L channel and weighting it by local edge energy keeps flat
    areas clean, so lines get deeper without the white halos a full RGB unsharp
    mask leaves behind.
    """
    lab = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2LAB)
    l_chan = lab[:, :, 0].astype(np.float32)

    blur = cv2.GaussianBlur(l_chan, (0, 0), 1.4)
    detail = l_chan - blur
    edges = (
        np.abs(cv2.Sobel(blur, cv2.CV_32F, 1, 0, ksize=3))
        + np.abs(cv2.Sobel(blur, cv2.CV_32F, 0, 1, ksize=3))
    )
    mask = np.clip(cv2.GaussianBlur(edges, (0, 0), 1.2) / 60.0, 0.0, 1.0)

    l_chan = l_chan + detail * (0.6 + strength * mask)
    # Deepen already-dark line pixels a touch further, but never crush highlights.
    l_chan = np.where(l_chan < 96.0, l_chan - (96.0 - l_chan) * 0.18 * mask, l_chan)

    lab[:, :, 0] = np.clip(l_chan, 0, 255).astype(np.uint8)
    return cv2.cvtColor(lab, cv2.COLOR_LAB2BGR)


def upscale_frame(
    model,
    native_scale: int,
    frame_bgr: np.ndarray,
    target_w: int,
    target_h: int,
    crisp_outline: bool = False,
) -> np.ndarray:
    if crisp_outline:
        frame_bgr = apply_crisp_outline(frame_bgr)

    src_h, src_w = frame_bgr.shape[:2]
    target_scale = max(target_w / max(src_w, 1), target_h / max(src_h, 1))

    # Each model pass multiplies by native scale (2x). For 4×, run the 2× model twice (multipass).
    passes = 0
    if native_scale > 1 and target_scale > 1.0:
        covered = 1.0
        while covered < target_scale - 1e-3:
            covered *= native_scale
            passes += 1
        passes = min(passes, 2)

    out_bgr = frame_bgr
    for _ in range(passes):
        out_bgr = infer_tiled_frame(
            out_bgr,
            lambda tile: _upscale_tile(model, tile, model_scale=native_scale),
            scale=native_scale,
            tile_size=512,
            tile_pad=16,
        )

    h, w = out_bgr.shape[:2]
    if w != target_w or h != target_h:
        interp = cv2.INTER_AREA if (target_w < w or target_h < h) else cv2.INTER_LANCZOS4
        out_bgr = cv2.resize(out_bgr, (target_w, target_h), interpolation=interp)
    return out_bgr


def process_upscale_job(job, params: Dict[str, Any]) -> None:
    input_path = job.input_path
    model_choice = params.get("model", "2x-sudo-UltraCompact")
    target_quality = params.get("target_quality", "Auto (Native)")
    crisp_outline = bool(params.get("crisp_outline", False))

    output_dir = Path(tempfile.gettempdir()) / "upscaler_outputs"
    output_dir.mkdir(parents=True, exist_ok=True)

    try:
        if not job.start_processing():
            job.mark_cancelled()
            return
        model, native_scale = get_upscaler_model(model_choice)
        if hasattr(model, "reset"):
            model.reset()

        if is_image_file(input_path):
            img = cv2.imread(input_path)
            if img is None:
                job.mark_failed("Unable to read input image.")
                return
            src_h, src_w = img.shape[:2]
            out_w, out_h = compute_target_dimensions(src_w, src_h, target_quality, model_choice)
            out_bgr = upscale_frame(model, native_scale, img, out_w, out_h, crisp_outline)
            out_path = output_dir / f"upscaled_{job.job_id}.png"
            if not cv2.imwrite(str(out_path), out_bgr) or not out_path.exists():
                job.mark_failed("Failed to write output image.")
                return
            job.update_progress(100.0, 1, 1, 1.0, 0.0, encode_preview_b64(out_bgr))
            job.mark_completed(str(out_path))
            return

        _process_video_upscale(
            job, input_path, output_dir, model, native_scale, model_choice, target_quality, crisp_outline
        )
    except Exception as e:
        if job.is_cancelled:
            job.mark_cancelled()
        else:
            job.mark_failed(str(e))


def _process_video_upscale(
    job,
    input_path: str,
    output_dir: Path,
    model,
    native_scale: int,
    model_choice: str,
    target_quality: str,
    crisp_outline: bool = False,
) -> None:
    cap = None
    writer = None
    raw_out = output_dir / f"raw_{job.job_id}.mp4"
    final_out = output_dir / f"upscaled_{job.job_id}.mp4"
    job.raw_intermediate_path = str(raw_out)

    try:
        cap, fps, total_frames, src_w, src_h = open_video_capture(input_path)
        target_w, target_h = compute_target_dimensions(src_w, src_h, target_quality, model_choice)
        writer, target_w, target_h = open_video_writer(raw_out, fps, target_w, target_h)

        start_time = time.time()
        frame_idx = 0

        while True:
            if job.wait_if_paused():
                job.mark_cancelled()
                return

            ret, frame = cap.read()
            if not ret:
                break

            out_bgr = upscale_frame(model, native_scale, frame, target_w, target_h, crisp_outline)
            write_frame_fit(writer, out_bgr, target_w, target_h)
            frame_idx += 1

            if frame_idx == 1 or frame_idx % 5 == 0 or (total_frames > 0 and frame_idx == total_frames):
                emit_progress(job, frame_idx, total_frames, start_time, out_bgr)

        if frame_idx == 0:
            job.mark_failed("Input video contained zero readable frames.")
            return
    finally:
        if cap is not None:
            cap.release()
        if writer is not None:
            writer.release()

    if job.is_cancelled:
        try:
            if raw_out.exists():
                raw_out.unlink()
        except OSError:
            pass
        job.mark_cancelled()
        return

    try:
        finalize_video_export(raw_out, input_path, final_out, preserve_audio=True, job=job)
        job.mark_completed(str(final_out))
    except Exception as e:
        if job.is_cancelled:
            try:
                if raw_out.exists():
                    raw_out.unlink()
            except OSError:
                pass
            job.mark_cancelled()
        else:
            job.mark_failed(str(e))
