import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Dict

import cv2
import numpy as np
import torch

from models.media_utils import (
    emit_progress,
    encode_preview_b64,
    finalize_video_export,
    is_image_file,
    open_video_capture,
    open_video_writer,
    write_frame_fit,
)

DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
FP16 = torch.cuda.is_available()

DEPTH_MODELS = {
    "depth_anything_v2_small": "depth-anything/Depth-Anything-V2-Small-hf",
    "depth_anything_v2_base": "depth-anything/Depth-Anything-V2-Base-hf",
}

VALID_DEPTH_COLORMAPS = frozenset({"inferno", "turbo", "grayscale", "viridis"})

_depth_lock = threading.Lock()
_loaded_depth_models: Dict[str, Dict[str, Any]] = {}


def get_depth_model(model_key: str = "depth_anything_v2_small") -> Dict[str, Any]:
    if model_key not in DEPTH_MODELS:
        raise ValueError(f"Unsupported depth model '{model_key}'.")

    with _depth_lock:
        if model_key in _loaded_depth_models:
            return _loaded_depth_models[model_key]

        hf_id = DEPTH_MODELS[model_key]
        try:
            from transformers import AutoImageProcessor, AutoModelForDepthEstimation
        except ImportError as e:
            raise RuntimeError(
                "Depth estimation dependency 'transformers' is not installed on the backend."
            ) from e

        try:
            processor = AutoImageProcessor.from_pretrained(hf_id)
            model = AutoModelForDepthEstimation.from_pretrained(hf_id)
            model.to(DEVICE).eval()
            if FP16:
                model.half()
        except Exception as e:
            raise RuntimeError(f"Failed to load AI depth model '{model_key}' ({hf_id}): {e}") from e

        loaded = {"processor": processor, "model": model, "key": model_key}
        _loaded_depth_models[model_key] = loaded
        return loaded


def apply_depth_colormap(depth_u8: np.ndarray, colormap: str = "inferno") -> np.ndarray:
    if colormap not in VALID_DEPTH_COLORMAPS:
        raise ValueError(f"Unsupported depth colormap '{colormap}'.")

    if colormap == "grayscale":
        return cv2.cvtColor(depth_u8, cv2.COLOR_GRAY2BGR)
    cmap_map = {
        "inferno": cv2.COLORMAP_INFERNO,
        "turbo": cv2.COLORMAP_TURBO,
        "viridis": cv2.COLORMAP_VIRIDIS,
    }
    return cv2.applyColorMap(depth_u8, cmap_map[colormap])


@torch.inference_mode()
def infer_depth_map(model_dict: Dict[str, Any], frame_bgr: np.ndarray, colormap: str = "inferno") -> np.ndarray:
    if model_dict is None or "processor" not in model_dict or "model" not in model_dict:
        raise RuntimeError("Depth model is not loaded; refusing silent fallback.")
    if colormap not in VALID_DEPTH_COLORMAPS:
        raise ValueError(f"Unsupported depth colormap '{colormap}'.")

    processor = model_dict["processor"]
    model = model_dict["model"]

    img_rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
    inputs = processor(images=img_rgb, return_tensors="pt")
    inputs = {k: v.to(DEVICE) for k, v in inputs.items()}
    if FP16:
        inputs = {k: (v.half() if v.dtype == torch.float32 else v) for k, v in inputs.items()}

    outputs = model(**inputs)
    predicted_depth = outputs.predicted_depth
    if predicted_depth is None:
        raise RuntimeError("Depth model returned empty predicted_depth tensor.")

    h, w = frame_bgr.shape[:2]
    prediction = torch.nn.functional.interpolate(
        predicted_depth.unsqueeze(1),
        size=(h, w),
        mode="bicubic",
        align_corners=False,
    ).squeeze().float().cpu().numpy()

    depth_min = float(np.min(prediction))
    depth_max = float(np.max(prediction))
    span = depth_max - depth_min
    if not np.isfinite(depth_min) or not np.isfinite(depth_max):
        raise RuntimeError("Depth model produced non-finite output values.")
    if span <= 1e-6:
        depth_u8 = np.zeros((h, w), dtype=np.uint8)
    else:
        depth_norm = np.clip((prediction - depth_min) / span, 0.0, 1.0)
        depth_u8 = (depth_norm * 255.0).astype(np.uint8)

    return apply_depth_colormap(depth_u8, colormap)


def process_depth_job(job, params: Dict[str, Any]) -> None:
    input_path = job.input_path
    colormap = str(params.get("colormap", "inferno"))
    model_key = str(params.get("model", "depth_anything_v2_small"))

    output_dir = Path(tempfile.gettempdir()) / "depth_outputs"
    output_dir.mkdir(parents=True, exist_ok=True)

    cap = None
    writer = None
    raw_out = output_dir / f"raw_depth_{job.job_id}.mp4"
    final_out = output_dir / f"depth_{job.job_id}.mp4"
    job.raw_intermediate_path = str(raw_out)

    try:
        if not job.start_processing():
            job.mark_cancelled()
            return
        model_dict = get_depth_model(model_key)

        if is_image_file(input_path):
            img = cv2.imread(input_path)
            if img is None:
                job.mark_failed("Unable to read input image.")
                return
            depth_bgr = infer_depth_map(model_dict, img, colormap)
            img_out = output_dir / f"depth_{job.job_id}.png"
            if not cv2.imwrite(str(img_out), depth_bgr) or not img_out.exists():
                job.mark_failed("Failed to write depth map image.")
                return
            job.update_progress(100.0, 1, 1, 1.0, 0.0, encode_preview_b64(depth_bgr))
            job.mark_completed(str(img_out))
            return

        cap, fps, total_frames, width, height = open_video_capture(input_path)
        writer, out_w, out_h = open_video_writer(raw_out, fps, width, height)

        start_time = time.time()
        frame_idx = 0

        while True:
            if job.wait_if_paused():
                job.mark_cancelled()
                return

            ret, frame = cap.read()
            if not ret:
                break

            depth_bgr = infer_depth_map(model_dict, frame, colormap)
            write_frame_fit(writer, depth_bgr, out_w, out_h)
            frame_idx += 1

            if frame_idx == 1 or frame_idx % 5 == 0 or (total_frames > 0 and frame_idx == total_frames):
                emit_progress(job, frame_idx, total_frames, start_time, depth_bgr)

        if frame_idx == 0:
            job.mark_failed("Input video contained zero readable frames.")
            return
    except Exception as e:
        if job.is_cancelled:
            job.mark_cancelled()
        else:
            job.mark_failed(str(e))
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
