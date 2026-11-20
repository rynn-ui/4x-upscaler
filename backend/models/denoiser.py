import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Dict

import cv2
import numpy as np
import torch
import torch.nn as nn

from models.media_utils import (
    atomic_download_file,
    emit_progress,
    encode_preview_b64,
    finalize_video_export,
    infer_tiled_frame,
    is_image_file,
    open_video_capture,
    open_video_writer,
    write_frame_fit,
)

DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
FP16 = torch.cuda.is_available()

MIRRORS = [
    "https://huggingface.co/nuriyoo/openmodeldb-mirror/resolve/main/models/1x-Archiver-AntiLines/1x-Archivist_AntiLines.pth",
    "https://github.com/Loganavter/Archivist-Project-Denoiser/releases/download/v1.0/1x-Archivist_AntiLines.pth",
]

VALID_DENOISE_MODELS = frozenset({"1x_archiver_antilines"})


class SRVGGNetCompact(nn.Module):
    def __init__(self, num_in_ch=3, num_out_ch=3, num_feat=48, num_conv=16, upscale=1, act_type="prelu"):
        super().__init__()
        self.num_conv = num_conv
        self.body = nn.ModuleList()
        self.body.append(nn.Conv2d(num_in_ch, num_feat, 3, 1, 1))
        self.body.append(nn.PReLU(num_parameters=num_feat) if act_type == "prelu" else nn.ReLU(inplace=True))
        for _ in range(num_conv - 2):
            self.body.append(nn.Conv2d(num_feat, num_feat, 3, 1, 1))
            self.body.append(nn.PReLU(num_parameters=num_feat) if act_type == "prelu" else nn.ReLU(inplace=True))
        self.body.append(nn.Conv2d(num_feat, num_out_ch, 3, 1, 1))

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        return out


_denoise_lock = threading.Lock()
_denoise_model = None


def get_denoise_model(model_key: str = "1x_archiver_antilines"):
    global _denoise_model
    if model_key not in VALID_DENOISE_MODELS:
        raise ValueError(f"Unsupported denoiser model '{model_key}'.")

    with _denoise_lock:
        if _denoise_model is not None:
            return _denoise_model

        model_dir = Path(tempfile.gettempdir()) / "denoise_models"
        weights_path = model_dir / "1x-Archivist_AntiLines.pth"
        atomic_download_file(MIRRORS, weights_path, min_bytes=10_000)

        try:
            import spandrel

            model = spandrel.ModelLoader().load_from_file(str(weights_path))
        except Exception:
            sd = torch.load(str(weights_path), map_location="cpu", weights_only=False)
            sd = sd.get("params_ema", sd.get("params", sd))
            compact = SRVGGNetCompact(num_in_ch=3, num_out_ch=3, num_feat=48, num_conv=16, upscale=1)
            compact.load_state_dict(sd)
            model = compact

        model = model.to(DEVICE).eval()
        if FP16:
            model = model.half()

        _denoise_model = model
        return _denoise_model


@torch.inference_mode()
def _denoise_tile(model, tile_bgr: np.ndarray) -> np.ndarray:
    img_rgb = cv2.cvtColor(tile_bgr, cv2.COLOR_BGR2RGB)
    t = torch.from_numpy(img_rgb).permute(2, 0, 1).float().unsqueeze(0) / 255.0
    t = t.to(DEVICE)
    if FP16:
        t = t.half()
    out = model(t)
    out_np = out.squeeze(0).permute(1, 2, 0).float().clamp(0, 1).cpu().numpy()
    return cv2.cvtColor((out_np * 255.0).astype(np.uint8), cv2.COLOR_RGB2BGR)


def denoise_frame(model, frame_bgr: np.ndarray) -> np.ndarray:
    can_cuda = torch.cuda.is_available()
    h, w = frame_bgr.shape[:2]
    if can_cuda and (h * w <= 1920 * 1088 or (min(h, w) <= 1088 and max(h, w) <= 1920)):
        try:
            return _denoise_tile(model, frame_bgr)
        except (torch.cuda.OutOfMemoryError, RuntimeError) as e:
            if "out of memory" not in str(e).lower() and not isinstance(e, torch.cuda.OutOfMemoryError):
                raise
            if torch.cuda.is_available():
                torch.cuda.empty_cache()

    tile_sz = 1024 if can_cuda else 512
    return infer_tiled_frame(
        frame_bgr,
        lambda tile: _denoise_tile(model, tile),
        scale=1,
        tile_size=tile_sz,
        tile_pad=16,
    )


def process_denoise_job(job, params: Dict[str, Any]) -> None:
    input_path = job.input_path
    model_key = str(params.get("model", "1x_archiver_antilines"))
    output_dir = Path(tempfile.gettempdir()) / "denoise_outputs"
    output_dir.mkdir(parents=True, exist_ok=True)

    cap = None
    writer = None
    raw_out = output_dir / f"raw_denoise_{job.job_id}.mp4"
    final_out = output_dir / f"denoised_{job.job_id}.mp4"
    job.raw_intermediate_path = str(raw_out)

    try:
        if not job.start_processing():
            job.mark_cancelled()
            return
        model = get_denoise_model(model_key)

        if is_image_file(input_path):
            img = cv2.imread(input_path)
            if img is None:
                job.mark_failed("Unable to read input image.")
                return
            out_bgr = denoise_frame(model, img)
            img_out = output_dir / f"denoised_{job.job_id}.png"
            if not cv2.imwrite(str(img_out), out_bgr) or not img_out.exists():
                job.mark_failed("Failed to write denoised image.")
                return
            job.update_progress(100.0, 1, 1, 1.0, 0.0, encode_preview_b64(out_bgr))
            job.mark_completed(str(img_out))
            return

        cap, fps, total_frames, width, height = open_video_capture(input_path)
        writer, out_w, out_h = open_video_writer(raw_out, fps, width, height)

        start_time = time.time()
        frame_idx = 0
        last_preview_time = 0.0

        while True:
            if job.wait_if_paused():
                job.mark_cancelled()
                return

            ret, frame = cap.read()
            if not ret:
                break

            out_bgr = denoise_frame(model, frame)
            write_frame_fit(writer, out_bgr, out_w, out_h)
            frame_idx += 1

            if frame_idx == 1 or frame_idx % 4 == 0 or (total_frames > 0 and frame_idx == total_frames):
                last_preview_time = emit_progress(
                    job, frame_idx, total_frames, start_time, out_bgr, last_preview_time=last_preview_time
                )

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
