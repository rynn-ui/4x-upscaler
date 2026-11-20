import importlib.util
import inspect
import os
import sys
import tempfile
import threading
import time
import types
import zipfile
from pathlib import Path
from typing import Any, Callable, Dict, List, Tuple

import cv2
import numpy as np
import torch
import torch.nn.functional as F

from models.media_utils import (
    atomic_download_file,
    emit_progress,
    finalize_video_export,
    open_video_capture,
    open_video_writer,
    write_frame_fit,
)

DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
FP16 = torch.cuda.is_available()

# ===================================================================
# Backward Warp Optical Flow for RIFE Model Architecture
# ===================================================================
_backwarp_lock = threading.Lock()
_backwarp_tenGrid: Dict[Tuple[str, str, str], torch.Tensor] = {}


def warp(tenInput: torch.Tensor, tenFlow: torch.Tensor) -> torch.Tensor:
    """Backward warp an image/feature tensor using optical flow."""
    k = (str(tenFlow.device), str(tenFlow.size()), str(tenFlow.dtype))
    with _backwarp_lock:
        if k not in _backwarp_tenGrid:
            tenHorizontal = (
                torch.linspace(-1.0, 1.0, tenFlow.shape[3], device=tenFlow.device, dtype=tenFlow.dtype)
                .view(1, 1, 1, tenFlow.shape[3])
                .expand(tenFlow.shape[0], -1, tenFlow.shape[2], -1)
            )
            tenVertical = (
                torch.linspace(-1.0, 1.0, tenFlow.shape[2], device=tenFlow.device, dtype=tenFlow.dtype)
                .view(1, 1, tenFlow.shape[2], 1)
                .expand(tenFlow.shape[0], -1, -1, tenFlow.shape[3])
            )
            _backwarp_tenGrid[k] = torch.cat([tenHorizontal, tenVertical], 1)
        grid_base = _backwarp_tenGrid[k]

    tenFlow = torch.cat([
        tenFlow[:, 0:1, :, :] / ((tenInput.shape[3] - 1.0) / 2.0),
        tenFlow[:, 1:2, :, :] / ((tenInput.shape[2] - 1.0) / 2.0)
    ], 1)

    grid = (grid_base + tenFlow).permute(0, 2, 3, 1)
    return F.grid_sample(
        input=tenInput, grid=grid,
        mode="bilinear", padding_mode="border", align_corners=True
    )


# Provide virtual 'model.warplayer' and 'warplayer' modules for RIFE model scripts
_warplayer = types.ModuleType("model.warplayer")
_warplayer.warp = warp
sys.modules["model.warplayer"] = _warplayer
sys.modules["warplayer"] = _warplayer
if "model" not in sys.modules:
    sys.modules["model"] = types.ModuleType("model")
sys.modules["model"].warplayer = _warplayer


RIFE_MODELS_DIR = Path(tempfile.gettempdir()) / "rife_models"
RIFE_OUTPUTS_DIR = Path(tempfile.gettempdir()) / "rife_outputs"

RIFE_MODEL_REGISTRY = {
    "rife_v4.6": {
        "name": "RIFE v4.6 — Anime & AMV (Recommended)",
        "url": "https://huggingface.co/Bash2X/RIFE-Models/resolve/main/RIFE_v4.6.zip",
    },
    "rife_v4.25": {
        "name": "RIFE v4.25 — Motion & Fast Action",
        "url": "https://huggingface.co/Bash2X/RIFE-Models/resolve/main/RIFE_v4.25.zip",
    },
    "rife_v4.22_lite": {
        "name": "RIFE v4.22 Lite — Ultra Fast",
        "url": "https://huggingface.co/Bash2X/RIFE-Models/resolve/main/RIFE_v4.22.lite.zip",
    },
    "rife_v4.22": {
        "name": "RIFE v4.22 — Realistic Quality",
        "url": "https://huggingface.co/Bash2X/RIFE-Models/resolve/main/RIFE_v4.22.zip",
    },
    "rife_v4.26": {
        "name": "RIFE v4.26 — Heavy Ultra-Quality",
        "url": "https://huggingface.co/Bash2X/RIFE-Models/resolve/main/RIFE_v4.26.zip",
    },
}

VALID_RIFE_MULTIPLIERS = frozenset({2, 4, 8})
VALID_RIFE_MODES = frozenset({"interpolate", "slow_motion"})

_rife_lock = threading.Lock()
_loaded_rife_adapters: Dict[str, Callable[[torch.Tensor, torch.Tensor, float], torch.Tensor]] = {}


def download_and_extract_rife(model_key: str = "rife_v4.6") -> str:
    if model_key not in RIFE_MODEL_REGISTRY:
        raise ValueError(f"Unsupported RIFE model '{model_key}'.")

    meta = RIFE_MODEL_REGISTRY[model_key]
    dest_dir = RIFE_MODELS_DIR / model_key
    if dest_dir.exists():
        for root, _, files in os.walk(dest_dir):
            if "flownet.pkl" in files:
                return root

    dest_dir.mkdir(parents=True, exist_ok=True)
    zip_path = RIFE_MODELS_DIR / f"{model_key}.zip"
    atomic_download_file([meta["url"]], zip_path, min_bytes=50_000)

    try:
        with zipfile.ZipFile(str(zip_path)) as zf:
            zf.extractall(str(dest_dir))
    finally:
        if zip_path.exists():
            try:
                zip_path.unlink()
            except OSError:
                pass

    for root, _, files in os.walk(dest_dir):
        if "flownet.pkl" in files:
            return root

    raise RuntimeError(f"Downloaded archive for '{model_key}' did not contain flownet.pkl.")


def _extract_frame_tensor(raw_output: Any) -> torch.Tensor:
    """Explicitly unwrap verified 4D (B, 3, H, W) output from IFNet variants."""
    if torch.is_tensor(raw_output) and raw_output.ndim == 4 and raw_output.shape[1] == 3:
        return raw_output
    if isinstance(raw_output, (tuple, list)):
        merged = raw_output[2] if len(raw_output) >= 3 else raw_output[-1]
        if isinstance(merged, (list, tuple)) and len(merged) > 0:
            candidate = merged[-1]
            if isinstance(candidate, (list, tuple)) and len(candidate) == 2:
                candidate = (candidate[0] + candidate[1]) / 2.0
            if torch.is_tensor(candidate) and candidate.ndim == 4 and candidate.shape[1] == 3:
                return candidate
        if torch.is_tensor(merged) and merged.ndim == 4 and merged.shape[1] == 3:
            return merged
    raise RuntimeError(f"Unexpected RIFE model output structure: {type(raw_output).__name__}")


def get_optimal_rife_scale(width: int, height: int) -> float:
    """Auto-detect processing scale based on resolution to prevent CUDA OOM on high-res media."""
    pixels = width * height
    if pixels > 3840 * 2160:
        return 0.25
    elif pixels > 2560 * 1440:
        return 0.5
    return 1.0


def build_rife_call_adapter(model) -> Callable[..., torch.Tensor]:
    """Inspect model signature explicitly and support dynamic resolution scaling."""
    sig = inspect.signature(model.forward)
    params = list(sig.parameters.values())
    positional = [
        p for p in params
        if p.kind in (inspect.Parameter.POSITIONAL_ONLY, inspect.Parameter.POSITIONAL_OR_KEYWORD)
    ]
    param_names = {p.name for p in params}

    num_blocks = 5 if hasattr(model, "block4") else 4

    def _get_scale_list(scale: float) -> List[float]:
        base = [8.0, 4.0, 2.0, 1.0, 1.0] if num_blocks == 5 else [8.0, 4.0, 2.0, 1.0]
        return [b / max(0.1, scale) for b in base]

    if len(positional) >= 2 and positional[0].name in ("img0", "x0", "t0", "I0", "frame0"):
        def _two_arg(t0: torch.Tensor, t1: torch.Tensor, timestep: float = 0.5, scale: float = 1.0) -> torch.Tensor:
            kwargs: Dict[str, Any] = {}
            if "timestep" in param_names:
                kwargs["timestep"] = timestep
            if "scale_list" in param_names:
                kwargs["scale_list"] = _get_scale_list(scale)
            if "scale" in param_names:
                kwargs["scale"] = scale
            return _extract_frame_tensor(model(t0, t1, **kwargs))
        return _two_arg

    def _concat_arg(t0: torch.Tensor, t1: torch.Tensor, timestep: float = 0.5, scale: float = 1.0) -> torch.Tensor:
        x = torch.cat((t0, t1), dim=1)
        kwargs: Dict[str, Any] = {}
        if "timestep" in param_names:
            kwargs["timestep"] = timestep
        if "scale_list" in param_names:
            kwargs["scale_list"] = _get_scale_list(scale)
        if "scale" in param_names:
            kwargs["scale"] = scale
        if "training" in param_names:
            kwargs["training"] = False
        return _extract_frame_tensor(model(x, **kwargs))

    return _concat_arg


def get_rife_model(model_key: str = "rife_v4.6") -> Callable[[torch.Tensor, torch.Tensor, float], torch.Tensor]:
    if model_key not in RIFE_MODEL_REGISTRY:
        raise ValueError(f"Unsupported RIFE model '{model_key}'.")

    with _rife_lock:
        if model_key in _loaded_rife_adapters:
            return _loaded_rife_adapters[model_key]

        model_dir = download_and_extract_rife(model_key)
        all_files = os.listdir(model_dir)
        py_files = [f for f in all_files if f.endswith(".py") and ("IFNet" in f or "flownet" in f or "RIFE" in f)]
        if "IFNet_HDv3.py" in all_files:
            if_file = "IFNet_HDv3.py"
        elif py_files:
            if_file = py_files[0]
        else:
            raise RuntimeError(f"No RIFE architecture module found in {model_dir}.")

        spec = importlib.util.spec_from_file_location(
            f"rife_arch_{model_key.replace('.', '_')}",
            os.path.join(model_dir, if_file),
        )
        if spec is None or spec.loader is None:
            raise RuntimeError(f"Unable to load architecture file '{if_file}'.")
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)

        IFNetClass = getattr(mod, "IFNet", None)
        if IFNetClass is None:
            raise RuntimeError(f"IFNet class not found in '{if_file}'.")

        model = IFNetClass()
        weight_file = os.path.join(model_dir, "flownet.pkl")
        state_dict = torch.load(weight_file, map_location="cpu", weights_only=False)
        cleaned = {k.replace("module.", ""): v for k, v in state_dict.items()}
        load_res = model.load_state_dict(cleaned, strict=False)
        if len(load_res.missing_keys) > len(cleaned) // 2:
            raise RuntimeError(
                f"RIFE weights mismatch for '{model_key}': too many missing keys ({len(load_res.missing_keys)})."
            )
        model.eval().to(DEVICE)
        if FP16:
            model.half()

        adapter = build_rife_call_adapter(model)
        _loaded_rife_adapters[model_key] = adapter
        return adapter


@torch.inference_mode()
def _np_pair_to_padded_tensors(
    img0_bgr: np.ndarray, img1_bgr: np.ndarray
) -> Tuple[torch.Tensor, torch.Tensor, Tuple[int, int, int, int], int, int]:
    h, w = img0_bgr.shape[:2]
    ph = ((h - 1) // 64 + 1) * 64
    pw = ((w - 1) // 64 + 1) * 64
    padding = (0, pw - w, 0, ph - h)

    rgb0 = cv2.cvtColor(img0_bgr, cv2.COLOR_BGR2RGB)
    rgb1 = cv2.cvtColor(img1_bgr, cv2.COLOR_BGR2RGB)
    t0 = torch.from_numpy(rgb0.transpose(2, 0, 1)).to(DEVICE).unsqueeze(0).float() / 255.0
    t1 = torch.from_numpy(rgb1.transpose(2, 0, 1)).to(DEVICE).unsqueeze(0).float() / 255.0

    if padding[1] > 0 or padding[3] > 0:
        t0 = torch.nn.functional.pad(t0, padding, mode="replicate")
        t1 = torch.nn.functional.pad(t1, padding, mode="replicate")
    if FP16:
        t0 = t0.half()
        t1 = t1.half()
    return t0, t1, padding, h, w


def _tensor_to_bgr(t: torch.Tensor, orig_h: int, orig_w: int) -> np.ndarray:
    cropped = t[:, :, :orig_h, :orig_w]
    rgb_np = (cropped.squeeze(0).float().clamp(0, 1) * 255.0).byte().cpu().numpy().transpose(1, 2, 0)
    return cv2.cvtColor(rgb_np, cv2.COLOR_RGB2BGR)


@torch.inference_mode()
def generate_intermediate_frames(
    rife_fn: Callable[..., torch.Tensor],
    img0_bgr: np.ndarray,
    img1_bgr: np.ndarray,
    multiplier: int,
    scale: float = 1.0,
) -> List[np.ndarray]:
    """
    Generate exactly (multiplier - 1) intermediate frames in strict temporal order
    using recursive dyadic subdivision with automatic CUDA OOM fallback.
    """
    if multiplier not in VALID_RIFE_MULTIPLIERS:
        raise ValueError(f"Unsupported RIFE multiplier: {multiplier}")

    t0, t1, _, orig_h, orig_w = _np_pair_to_padded_tensors(img0_bgr, img1_bgr)
    depth = {2: 1, 4: 2, 8: 3}[multiplier]

    try:
        sig = inspect.signature(rife_fn)
        num_params = len(sig.parameters)
        has_varargs = any(
            p.kind in (inspect.Parameter.VAR_POSITIONAL, inspect.Parameter.VAR_KEYWORD)
            for p in sig.parameters.values()
        )
    except (ValueError, TypeError):
        num_params = 4
        has_varargs = True

    def _call_single(a: torch.Tensor, b: torch.Tensor, ts: float, s: float) -> torch.Tensor:
        if not has_varargs and num_params == 2:
            return rife_fn(a, b)
        if not has_varargs and num_params == 3:
            return rife_fn(a, b, ts)
        try:
            return rife_fn(a, b, ts, s)
        except TypeError:
            return rife_fn(a, b, ts)

    def _call_with_fallback(a: torch.Tensor, b: torch.Tensor, ts: float, cur_scale: float) -> torch.Tensor:
        try:
            return _call_single(a, b, ts, cur_scale)
        except torch.cuda.OutOfMemoryError:
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            reduced = max(0.25, cur_scale * 0.5)
            return _call_single(a, b, ts, reduced)

    def _recurse(a: torch.Tensor, b: torch.Tensor, level: int, cur_scale: float) -> List[torch.Tensor]:
        mid = _call_with_fallback(a, b, 0.5, cur_scale)
        if level == 1:
            return [mid]
        return (
            _recurse(a, mid, level - 1, cur_scale)
            + [mid]
            + _recurse(mid, b, level - 1, cur_scale)
        )

    mids = _recurse(t0, t1, depth, scale)
    return [_tensor_to_bgr(m, orig_h, orig_w) for m in mids]


def compute_rife_output_spec(
    in_fps: float, multiplier: int, mode: str
) -> Tuple[float, bool]:
    """
    Separate normal-speed high-FPS interpolation from slow-motion playback.
    Returns (out_fps, preserve_audio).
    """
    if multiplier not in VALID_RIFE_MULTIPLIERS:
        raise ValueError(f"Unsupported RIFE multiplier: {multiplier}")
    if mode not in VALID_RIFE_MODES:
        raise ValueError(f"Unsupported RIFE mode: {mode}")

    if mode == "slow_motion":
        # True slow motion: keeps source cadence so duration increases by multiplier; mutes mismatched audio cleanly
        return float(in_fps), False
    # Normal-speed interpolation: multiplies FPS and keeps exact duration & audio
    return float(in_fps * multiplier), True


def process_rife_job(job, params: Dict[str, Any]) -> None:
    input_path = job.input_path
    multiplier = int(params.get("multiplier", 2))
    model_key = str(params.get("model_key", "rife_v4.6"))
    mode = str(params.get("mode", "interpolate"))

    if multiplier not in VALID_RIFE_MULTIPLIERS:
        job.mark_failed(f"Unsupported RIFE multiplier: {multiplier}")
        return
    if model_key not in RIFE_MODEL_REGISTRY:
        job.mark_failed(f"Unsupported RIFE model: {model_key}")
        return
    if mode not in VALID_RIFE_MODES:
        job.mark_failed(f"Unsupported RIFE mode: {mode}")
        return

    output_dir = RIFE_OUTPUTS_DIR
    output_dir.mkdir(parents=True, exist_ok=True)
    raw_out = output_dir / f"raw_rife_{job.job_id}.mp4"
    final_out = output_dir / f"rife_{job.job_id}.mp4"
    job.raw_intermediate_path = str(raw_out)

    cap = None
    writer = None

    try:
        if not job.start_processing():
            job.mark_cancelled()
            return

        rife_fn = get_rife_model(model_key)
        cap, in_fps, total_frames, width, height = open_video_capture(input_path)
        optimal_scale = get_optimal_rife_scale(width, height)
        out_fps, preserve_audio = compute_rife_output_spec(in_fps, multiplier, mode)
        writer, out_w, out_h = open_video_writer(raw_out, out_fps, width, height)

        start_time = time.time()
        ret, prev_frame = cap.read()
        if not ret:
            job.mark_failed("Input video contained zero readable frames.")
            return

        write_frame_fit(writer, prev_frame, out_w, out_h)
        frame_idx = 1
        last_preview_time = 0.0

        while True:
            if job.wait_if_paused():
                job.mark_cancelled()
                return

            ret, curr_frame = cap.read()
            if not ret:
                break

            mids = generate_intermediate_frames(rife_fn, prev_frame, curr_frame, multiplier, scale=optimal_scale)
            for m in mids:
                write_frame_fit(writer, m, out_w, out_h)
            write_frame_fit(writer, curr_frame, out_w, out_h)

            prev_frame = curr_frame
            frame_idx += 1

            if frame_idx == 2 or frame_idx % 4 == 0 or (total_frames > 0 and frame_idx == total_frames):
                last_preview_time = emit_progress(
                    job, frame_idx, total_frames, start_time, curr_frame, last_preview_time=last_preview_time
                )
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
        if torch.cuda.is_available():
            torch.cuda.empty_cache()

    if job.is_cancelled:
        try:
            if raw_out.exists():
                raw_out.unlink()
        except OSError:
            pass
        job.mark_cancelled()
        return

    try:
        finalize_video_export(raw_out, input_path, final_out, preserve_audio=preserve_audio, job=job)
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
