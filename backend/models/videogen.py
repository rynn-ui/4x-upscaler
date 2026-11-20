"""
Video Generation Studio backend engine for Upscaler Studio.

Supports five open-source video generation modalities:
  1. t2v                — Text-to-Video (Wan 2.1 1.3B, Wan 2.2 TI2V-5B, Wan 2.2 T2V-A14B, LTX-2.5, HunyuanVideo 1.5)
  2. i2v                — Image-to-Video (Wan 2.2 TI2V-5B, Wan 2.2 I2V-A14B, HunyuanVideo 1.5, LTX-2.5)
  3. v2v                — Video-to-Video (Wan 2.1 VACE-1.3B / 14B, optional Depth Anything V2 conditioning)
  4. motion_copy        — Motion Copy / Transfer (Wan 2.2 Animate-2-14B)
  5. character_exchange — Character Replacement / Swap (Wan 2.1 VACE-1.3B, Wan 2.2 Animate-2-14B)

Uses a singleton VRAM manager so only one diffusion pipeline occupies GPU memory at a time.
"""

from __future__ import annotations

import gc
import inspect
import os
import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import cv2
import numpy as np
import torch
from PIL import Image

from models.base import TaskJob
from models.media_utils import encode_preview_b64, finalize_video_export, make_even_dims

# ---------------------------------------------------------------------------
# Catalog & Validation Constants
# ---------------------------------------------------------------------------

VIDEOGEN_MODES: Dict[str, Dict[str, Any]] = {
    "t2v": {
        "title": "Text to Video",
        "requires_primary_file": False,
        "primary_file_kind": None,
        "requires_reference_image": False,
        "requires_prompt": True,
    },
    "i2v": {
        "title": "Image to Video",
        "requires_primary_file": True,
        "primary_file_kind": "image",
        "requires_reference_image": False,
        "requires_prompt": False,
    },
    "v2v": {
        "title": "Video to Video",
        "requires_primary_file": True,
        "primary_file_kind": "video",
        "requires_reference_image": False,
        "requires_prompt": True,
    },
    "motion_copy": {
        "title": "Motion Copy",
        "requires_primary_file": True,
        "primary_file_kind": "video",
        "requires_reference_image": True,
        "requires_prompt": False,
    },
    "character_exchange": {
        "title": "Character Exchange",
        "requires_primary_file": True,
        "primary_file_kind": "video",
        "requires_reference_image": True,
        "requires_prompt": False,
    },
}

VIDEOGEN_MODELS: Dict[str, Dict[str, Any]] = {
    "wan21_t2v_1_3b": {
        "name": "Wan 2.1 T2V 1.3B (Fast · T4 Ready)",
        "repo_id": "Wan-AI/Wan2.1-T2V-1.3B-Diffusers",
        "modes": ["t2v"],
        "min_vram_gb": 8.2,
        "recommended_vram_gb": 12.0,
        "tier": "t4_ready",
        "license": "Apache 2.0",
        "native_audio": False,
        "default_fps": 16,
        "pipeline_kind": "wan_t2v",
    },
    "wan22_ti2v_5b": {
        "name": "Wan 2.2 TI2V 5B (Best All-Rounder)",
        "repo_id": "Wan-AI/Wan2.2-TI2V-5B-Diffusers",
        "modes": ["t2v", "i2v"],
        "min_vram_gb": 10.0,
        "recommended_vram_gb": 24.0,
        "tier": "t4_ready",
        "license": "Apache 2.0",
        "native_audio": False,
        "default_fps": 24,
        "pipeline_kind": "wan_ti2v",
    },
    "wan22_t2v_14b": {
        "name": "Wan 2.2 T2V A14B (Flagship MoE · 24GB+)",
        "repo_id": "Wan-AI/Wan2.2-T2V-A14B-Diffusers",
        "modes": ["t2v"],
        "min_vram_gb": 22.0,
        "recommended_vram_gb": 48.0,
        "tier": "pro_24gb",
        "license": "Apache 2.0",
        "native_audio": False,
        "default_fps": 24,
        "pipeline_kind": "wan_t2v",
    },
    "wan22_i2v_14b": {
        "name": "Wan 2.2 I2V A14B (Flagship I2V · 24GB+)",
        "repo_id": "Wan-AI/Wan2.2-I2V-A14B-Diffusers",
        "modes": ["i2v"],
        "min_vram_gb": 22.0,
        "recommended_vram_gb": 48.0,
        "tier": "pro_24gb",
        "license": "Apache 2.0",
        "native_audio": False,
        "default_fps": 24,
        "pipeline_kind": "wan_i2v",
    },
    "hunyuan_video_1_5": {
        "name": "HunyuanVideo 1.5 (8.3B Cinematic)",
        "repo_id": "tencent/HunyuanVideo-1.5",
        "modes": ["t2v", "i2v"],
        "min_vram_gb": 14.0,
        "recommended_vram_gb": 24.0,
        "tier": "t4_ready",
        "license": "Hunyuan Community",
        "native_audio": False,
        "default_fps": 24,
        "pipeline_kind": "hunyuan",
    },
    "ltx25_av": {
        "name": "LTX-2.5 (Fast + Native Audio · 24GB+)",
        "repo_id": "Lightricks/LTX-2.5",
        "modes": ["t2v", "i2v"],
        "min_vram_gb": 16.0,
        "recommended_vram_gb": 32.0,
        "tier": "pro_24gb",
        "license": "LTX-2 Community",
        "native_audio": True,
        "default_fps": 24,
        "pipeline_kind": "ltx",
    },
    "wan21_vace_1_3b": {
        "name": "Wan 2.1 VACE 1.3B (V2V & Swap · T4 Ready)",
        "repo_id": "Wan-AI/Wan2.1-VACE-1.3B-diffusers",
        "modes": ["v2v", "character_exchange"],
        "min_vram_gb": 9.0,
        "recommended_vram_gb": 16.0,
        "tier": "t4_ready",
        "license": "Apache 2.0",
        "native_audio": False,
        "default_fps": 16,
        "pipeline_kind": "wan_vace",
    },
    "wan21_vace_14b": {
        "name": "Wan 2.1 VACE 14B (Pro V2V & Swap · 24GB+)",
        "repo_id": "Wan-AI/Wan2.1-VACE-14B-diffusers",
        "modes": ["v2v", "character_exchange"],
        "min_vram_gb": 22.0,
        "recommended_vram_gb": 48.0,
        "tier": "pro_24gb",
        "license": "Apache 2.0",
        "native_audio": False,
        "default_fps": 16,
        "pipeline_kind": "wan_vace",
    },
    "wan22_animate_14b": {
        "name": "Wan 2.2 Animate-2 14B (Full-Body Motion & Swap · 24GB+)",
        "repo_id": "Wan-AI/Wan2.2-Animate-2-14B",
        "modes": ["motion_copy", "character_exchange"],
        "min_vram_gb": 16.0,
        "recommended_vram_gb": 24.0,
        "tier": "pro_24gb",
        "license": "Apache 2.0",
        "native_audio": False,
        "default_fps": 24,
        "pipeline_kind": "wan_animate",
    },
}

VALID_ASPECT_RATIOS: Dict[str, Tuple[int, int]] = {
    "16:9": (832, 480),
    "9:16": (480, 832),
    "1:1": (640, 640),
    "4:3": (720, 544),
}

VALID_DURATIONS_SEC = (3, 5, 8)
DEFAULT_NEGATIVE_PROMPT = (
    "blurry, distorted, low quality, flickering, deformed hands, extra limbs, watermark, text artifacts"
)


def default_model_for_mode(mode: str, vram_gb: float = 15.0) -> str:
    """Select the highest-quality compatible default model for a mode given available VRAM."""
    preferred_order = {
        "t2v": ["wan22_ti2v_5b", "wan21_t2v_1_3b", "wan22_t2v_14b"],
        "i2v": ["wan22_ti2v_5b", "hunyuan_video_1_5", "wan22_i2v_14b"],
        "v2v": ["wan21_vace_14b", "wan21_vace_1_3b"] if vram_gb >= 22.0 else ["wan21_vace_1_3b"],
        "motion_copy": ["wan22_animate_14b"],
        "character_exchange": ["wan22_animate_14b", "wan21_vace_1_3b"] if vram_gb >= 16.0 else ["wan21_vace_1_3b"],
    }
    candidates = preferred_order.get(mode, ["wan22_ti2v_5b"])
    for key in candidates:
        meta = VIDEOGEN_MODELS.get(key)
        if meta and (vram_gb <= 0 or vram_gb >= meta["min_vram_gb"]):
            return key
    return candidates[-1]


def get_videogen_capabilities(vram_total_mb: float) -> Dict[str, Any]:
    """Return capabilities metadata annotated with live GPU VRAM compatibility."""
    vram_gb = round(vram_total_mb / 1024.0, 1) if vram_total_mb > 0 else 0.0
    models_out: Dict[str, Any] = {}
    for key, meta in VIDEOGEN_MODELS.items():
        compatible = (vram_gb == 0.0) or (vram_gb >= meta["min_vram_gb"] - 0.5)
        models_out[key] = {
            "name": meta["name"],
            "repo_id": meta["repo_id"],
            "modes": meta["modes"],
            "min_vram_gb": meta["min_vram_gb"],
            "recommended_vram_gb": meta["recommended_vram_gb"],
            "tier": meta["tier"],
            "license": meta["license"],
            "native_audio": meta["native_audio"],
            "compatible": compatible,
        }
    return {
        "title": "AI Video Generation Studio",
        "enabled": True,
        "modes": list(VIDEOGEN_MODES.keys()),
        "models": models_out,
        "aspect_ratios": list(VALID_ASPECT_RATIOS.keys()),
        "durations_sec": list(VALID_DURATIONS_SEC),
        "detected_vram_gb": vram_gb,
    }


def compute_generation_dimensions(aspect_ratio: str, resolution_preset: str = "480p") -> Tuple[int, int]:
    """Compute even-multiple-of-16 dimensions for diffusion models."""
    base_w, base_h = VALID_ASPECT_RATIOS.get(aspect_ratio, (832, 480))
    scale = 1.5 if resolution_preset == "720p" else 1.0
    w = max(256, int(round((base_w * scale) / 16.0)) * 16)
    h = max(256, int(round((base_h * scale) / 16.0)) * 16)
    return make_even_dims(w, h)


# ---------------------------------------------------------------------------
# Singleton VRAM-Safe Pipeline Manager
# ---------------------------------------------------------------------------

class VideoGenModelManager:
    """Holds at most one active video diffusion pipeline in VRAM and evicts before switching."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._active_key: Optional[str] = None
        self._active_pipeline: Any = None

    def evict(self) -> None:
        with self._lock:
            if self._active_pipeline is not None:
                try:
                    if hasattr(self._active_pipeline, "to"):
                        self._active_pipeline.to("cpu")
                except Exception:
                    pass
                self._active_pipeline = None
                self._active_key = None
            gc.collect()
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
                if hasattr(torch.cuda, "ipc_collect"):
                    torch.cuda.ipc_collect()

    def load_pipeline(self, model_key: str, job: TaskJob) -> Any:
        with self._lock:
            if self._active_key == model_key and self._active_pipeline is not None:
                return self._active_pipeline

        self.evict()
        meta = VIDEOGEN_MODELS.get(model_key)
        if not meta:
            raise ValueError(f"Unknown video generation model: '{model_key}'")

        repo_id = meta["repo_id"]
        kind = meta["pipeline_kind"]
        cache_dir = os.environ.get("UPSCALER_HF_CACHE") or None

        job.update_progress(progress=5.0, current_frame=0, total_frames=100, fps=0.0, eta=0.0)

        try:
            import diffusers  # noqa: F401
        except ImportError as exc:
            raise RuntimeError(
                "Video generation requires 'diffusers>=0.33.0' and 'accelerate'. "
                "Install them via: pip install -U diffusers accelerate transformers safetensors sentencepiece ftfy imageio[ffmpeg]"
            ) from exc

        dtype = torch.bfloat16 if (torch.cuda.is_available() and torch.cuda.is_bf16_supported()) else torch.float16
        device_vram_gb = 0.0
        if torch.cuda.is_available():
            _, total_mem = torch.cuda.mem_get_info(0)
            device_vram_gb = total_mem / (1024 ** 3)

        pipe = self._instantiate_diffusers_pipeline(kind, repo_id, dtype, cache_dir)

        if torch.cuda.is_available():
            if device_vram_gb < meta["recommended_vram_gb"] and hasattr(pipe, "enable_sequential_cpu_offload"):
                pipe.enable_sequential_cpu_offload()
            elif hasattr(pipe, "enable_model_cpu_offload"):
                pipe.enable_model_cpu_offload()
            else:
                pipe = pipe.to("cuda")
            if hasattr(pipe, "vae") and hasattr(pipe.vae, "enable_tiling"):
                pipe.vae.enable_tiling()

        with self._lock:
            self._active_key = model_key
            self._active_pipeline = pipe
        return pipe

    @staticmethod
    def _instantiate_diffusers_pipeline(kind: str, repo_id: str, dtype: torch.dtype, cache_dir: Optional[str]) -> Any:
        import diffusers

        common_kwargs: Dict[str, Any] = {
            "torch_dtype": dtype,
            "low_cpu_mem_usage": True,
        }
        if cache_dir:
            common_kwargs["cache_dir"] = cache_dir

        if kind == "wan_t2v":
            cls = getattr(diffusers, "WanPipeline", diffusers.DiffusionPipeline)
            return cls.from_pretrained(repo_id, **common_kwargs)
        if kind in ("wan_i2v", "wan_ti2v"):
            cls = getattr(diffusers, "WanImageToVideoPipeline", getattr(diffusers, "WanPipeline", diffusers.DiffusionPipeline))
            return cls.from_pretrained(repo_id, **common_kwargs)
        if kind == "wan_vace":
            cls = getattr(diffusers, "WanVACEPipeline", diffusers.DiffusionPipeline)
            return cls.from_pretrained(repo_id, **common_kwargs)
        if kind == "wan_animate":
            cls = getattr(diffusers, "WanAnimatePipeline", diffusers.DiffusionPipeline)
            return cls.from_pretrained(repo_id, **common_kwargs)
        if kind == "hunyuan":
            cls = getattr(diffusers, "HunyuanVideoPipeline", diffusers.DiffusionPipeline)
            return cls.from_pretrained(repo_id, **common_kwargs)
        if kind == "ltx":
            cls = getattr(diffusers, "LTXPipeline", diffusers.DiffusionPipeline)
            return cls.from_pretrained(repo_id, **common_kwargs)

        return diffusers.DiffusionPipeline.from_pretrained(repo_id, **common_kwargs)


videogen_manager = VideoGenModelManager()


# ---------------------------------------------------------------------------
# Helpers: Frame Extraction, Masking & Depth Conditioning
# ---------------------------------------------------------------------------

def _read_video_frames(video_path: str, max_frames: int, target_size: Tuple[int, int]) -> Tuple[List[Image.Image], float]:
    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise RuntimeError(f"Could not open input video: {video_path}")
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 24.0
    frames: List[Image.Image] = []
    w, h = target_size
    try:
        while len(frames) < max_frames:
            ok, bgr = cap.read()
            if not ok:
                break
            rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
            pil = Image.fromarray(rgb).resize((w, h), Image.Resampling.LANCZOS)
            frames.append(pil)
    finally:
        cap.release()
    if not frames:
        raise RuntimeError("Input video contained zero readable frames.")
    return frames, float(src_fps)


def _build_first_frame_mask(
    first_frame: Image.Image,
    mask_path: Optional[str],
    point_x: Optional[float],
    point_y: Optional[float],
) -> Image.Image:
    """Load an uploaded mask or synthesize a clean GrabCut region mask around a tap point."""
    w, h = first_frame.size
    if mask_path and os.path.exists(mask_path):
        return Image.open(mask_path).convert("L").resize((w, h), Image.Resampling.NEAREST)

    cx = int(np.clip(point_x if point_x is not None else 0.5, 0.05, 0.95) * w)
    cy = int(np.clip(point_y if point_y is not None else 0.5, 0.05, 0.95) * h)
    box_w = max(32, int(w * 0.36))
    box_h = max(32, int(h * 0.62))
    x1 = max(0, cx - box_w // 2)
    y1 = max(0, cy - box_h // 2)
    rect = (x1, y1, min(w - x1 - 1, box_w), min(h - y1 - 1, box_h))

    rgb = np.array(first_frame)
    bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
    gc_mask = np.zeros((h, w), np.uint8)
    bgd_model = np.zeros((1, 65), np.float64)
    fgd_model = np.zeros((1, 65), np.float64)
    try:
        cv2.grabCut(bgr, gc_mask, rect, bgd_model, fgd_model, 3, cv2.GC_INIT_WITH_RECT)
        bin_mask = np.where((gc_mask == 2) | (gc_mask == 0), 0, 255).astype(np.uint8)
    except Exception:
        bin_mask = np.zeros((h, w), dtype=np.uint8)
        cv2.rectangle(bin_mask, (x1, y1), (x1 + rect[2], y1 + rect[3]), 255, -1)
    return Image.fromarray(bin_mask, mode="L")


def _build_depth_conditioning_frames(frames: List[Image.Image], job: TaskJob) -> List[Image.Image]:
    """Extract structural depth conditioning for V2V using Upscaler Studio's Depth Anything V2 model."""
    from models.depth import get_depth_model, infer_depth_map

    job.update_progress(progress=12.0, current_frame=0, total_frames=len(frames), fps=0.0, eta=0.0)
    depth_model = get_depth_model("depth_anything_v2_small")
    depth_frames: List[Image.Image] = []

    for idx, pil_img in enumerate(frames):
        if job.wait_if_paused():
            raise InterruptedError("Cancelled during depth extraction.")
        bgr = cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)
        depth_bgr = infer_depth_map(depth_model, bgr, colormap="grayscale")
        depth_rgb = cv2.cvtColor(depth_bgr, cv2.COLOR_BGR2RGB)
        depth_frames.append(Image.fromarray(depth_rgb))
        if idx % 8 == 0:
            pct = 12.0 + (idx / max(1, len(frames))) * 8.0
            job.update_progress(progress=pct, current_frame=idx, total_frames=len(frames), fps=0.0, eta=0.0)

    return depth_frames


# ---------------------------------------------------------------------------
# Main Job Entry Point
# ---------------------------------------------------------------------------

def process_videogen_job(job: TaskJob, params: Dict[str, Any]) -> None:
    """Execute a video generation job with step-level progress, pause/cancel, and MP4 export."""
    if not job.start_processing():
        return

    mode = str(params.get("mode", "t2v"))
    model_key = str(params.get("model", "wan22_ti2v_5b"))
    prompt = str(params.get("prompt", "")).strip()
    negative_prompt = str(params.get("negative_prompt", DEFAULT_NEGATIVE_PROMPT)).strip()
    aspect_ratio = str(params.get("aspect_ratio", "16:9"))
    resolution = str(params.get("resolution", "480p"))
    duration_sec = int(params.get("duration_sec", 5))
    num_steps = int(params.get("num_inference_steps", 28))
    guidance_scale = float(params.get("guidance_scale", 5.0))
    seed = int(params.get("seed", -1))
    use_depth_guidance = bool(params.get("use_depth_guidance", False))
    preserve_audio = bool(params.get("preserve_audio", True))
    reference_image_path = params.get("reference_image_path")
    mask_path = params.get("mask_path")
    mask_point_x = params.get("mask_point_x")
    mask_point_y = params.get("mask_point_y")

    meta = VIDEOGEN_MODELS.get(model_key, VIDEOGEN_MODELS["wan22_ti2v_5b"])
    width, height = compute_generation_dimensions(aspect_ratio, resolution)
    fps = int(meta.get("default_fps", 16))
    raw_frames = max(17, duration_sec * fps + 1)
    num_frames = ((raw_frames - 1) // 4) * 4 + 1

    work_base = Path(os.environ.get("UPSCALER_WORK_DIR", tempfile.gettempdir()))
    out_dir = work_base / "upscaler_outputs"
    out_dir.mkdir(parents=True, exist_ok=True)
    raw_out = out_dir / f"videogen_{mode}_{job.job_id}_raw.mp4"
    final_out = out_dir / f"videogen_{mode}_{job.job_id}.mp4"
    job.raw_intermediate_path = str(raw_out)

    try:
        pipe = videogen_manager.load_pipeline(model_key, job)
        if job.wait_if_paused():
            job.mark_cancelled()
            return

        job.update_progress(progress=10.0, current_frame=0, total_frames=num_steps, fps=0.0, eta=0.0)

        call_kwargs: Dict[str, Any] = {
            "prompt": prompt or "high quality cinematic video, smooth natural motion",
            "negative_prompt": negative_prompt,
            "width": width,
            "height": height,
            "num_frames": num_frames,
            "num_inference_steps": num_steps,
            "guidance_scale": guidance_scale,
        }

        if seed >= 0:
            gen_device = "cuda" if torch.cuda.is_available() else "cpu"
            call_kwargs["generator"] = torch.Generator(device=gen_device).manual_seed(seed)

        # Mode-specific conditioning
        audio_source_path: Optional[str] = None
        if mode == "i2v":
            if not job.input_path or not os.path.exists(job.input_path):
                raise ValueError("Image-to-Video requires an uploaded input image.")
            img = Image.open(job.input_path).convert("RGB").resize((width, height), Image.Resampling.LANCZOS)
            call_kwargs["image"] = img
        elif mode == "v2v":
            if not job.input_path or not os.path.exists(job.input_path):
                raise ValueError("Video-to-Video requires an uploaded source video.")
            audio_source_path = job.input_path if preserve_audio else None
            src_frames, _ = _read_video_frames(job.input_path, num_frames, (width, height))
            if use_depth_guidance:
                src_frames = _build_depth_conditioning_frames(src_frames, job)
            call_kwargs["video"] = src_frames
        elif mode == "motion_copy":
            if not job.input_path or not os.path.exists(job.input_path):
                raise ValueError("Motion Copy requires a driving video.")
            if not reference_image_path or not os.path.exists(reference_image_path):
                raise ValueError("Motion Copy requires a character reference image.")
            audio_source_path = job.input_path if preserve_audio else None
            driving_frames, _ = _read_video_frames(job.input_path, num_frames, (width, height))
            ref_img = Image.open(reference_image_path).convert("RGB").resize((width, height), Image.Resampling.LANCZOS)
            call_kwargs["video"] = driving_frames
            call_kwargs["image"] = ref_img
            if meta["pipeline_kind"] == "wan_animate":
                call_kwargs["mode"] = "animation"
        elif mode == "character_exchange":
            if not job.input_path or not os.path.exists(job.input_path):
                raise ValueError("Character Exchange requires a source video.")
            if not reference_image_path or not os.path.exists(reference_image_path):
                raise ValueError("Character Exchange requires a replacement character image.")
            audio_source_path = job.input_path if preserve_audio else None
            src_frames, _ = _read_video_frames(job.input_path, num_frames, (width, height))
            ref_img = Image.open(reference_image_path).convert("RGB").resize((width, height), Image.Resampling.LANCZOS)
            first_mask = _build_first_frame_mask(
                src_frames[0],
                mask_path,
                float(mask_point_x) if mask_point_x is not None else None,
                float(mask_point_y) if mask_point_y is not None else None,
            )
            call_kwargs["video"] = src_frames
            call_kwargs["reference_images"] = [ref_img]
            call_kwargs["mask"] = [first_mask] * len(src_frames)
            if meta["pipeline_kind"] == "wan_animate":
                call_kwargs["image"] = ref_img
                call_kwargs["mode"] = "replacement"

        # Step-level diffusion progress callback with pause/cancel checks
        denoise_start = time.time()

        def _on_step_end(_pipe: Any, step_idx: int, _timestep: Any, callback_kwargs: Dict[str, Any]) -> Dict[str, Any]:
            if job.wait_if_paused():
                raise InterruptedError("Cancelled during denoising.")
            completed = step_idx + 1
            elapsed = max(0.001, time.time() - denoise_start)
            steps_per_sec = completed / elapsed
            remaining = max(0, num_steps - completed)
            eta = remaining / max(0.001, steps_per_sec)
            pct = 20.0 + (completed / max(1, num_steps)) * 70.0
            job.update_progress(
                progress=pct,
                current_frame=completed,
                total_frames=num_steps,
                fps=steps_per_sec,
                eta=eta,
            )
            return callback_kwargs

        call_kwargs["callback_on_step_end"] = _on_step_end

        job.update_progress(progress=20.0, current_frame=0, total_frames=num_steps, fps=0.0, eta=0.0)

        # Filter kwargs to match the specific pipeline's __call__ signature
        sig = inspect.signature(pipe.__call__)
        if not any(p.kind == inspect.Parameter.VAR_KEYWORD for p in sig.parameters.values()):
            call_kwargs = {k: v for k, v in call_kwargs.items() if k in sig.parameters}

        with torch.inference_mode():
            result = pipe(**call_kwargs)

        if job.wait_if_paused():
            job.mark_cancelled()
            return

        job.update_progress(progress=92.0, current_frame=num_steps, total_frames=num_steps, fps=0.0, eta=1.0)
        frames_out = result.frames[0] if hasattr(result, "frames") else result[0]

        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(str(raw_out), fourcc, float(fps), (width, height))
        if not writer.isOpened():
            raise RuntimeError("Failed to initialize OpenCV VideoWriter for generated clip.")

        preview_b64: Optional[str] = None
        try:
            for idx, frame in enumerate(frames_out):
                arr = np.array(frame)
                if arr.dtype != np.uint8:
                    arr = np.clip(arr * 255.0 if arr.max() <= 1.0 else arr, 0, 255).astype(np.uint8)
                if arr.shape[1] != width or arr.shape[0] != height:
                    arr = cv2.resize(arr, (width, height))
                bgr = cv2.cvtColor(arr, cv2.COLOR_RGB2BGR)
                if idx == len(frames_out) // 2:
                    preview_b64 = encode_preview_b64(bgr)
                writer.write(bgr)
        finally:
            writer.release()

        if preview_b64:
            job.update_progress(
                progress=96.0,
                current_frame=num_steps,
                total_frames=num_steps,
                fps=0.0,
                eta=0.5,
                preview_b64=preview_b64,
            )

        # Finalize export via ffmpeg H.264 muxing
        input_audio_src = audio_source_path if (audio_source_path and preserve_audio) else ""
        finalize_video_export(
            raw_out=raw_out,
            input_path=input_audio_src,
            final_out=final_out,
            preserve_audio=bool(audio_source_path and preserve_audio),
            job=job,
        )

        job.mark_completed(str(final_out))

    except InterruptedError:
        job.mark_cancelled()
    except Exception as exc:
        if job.is_cancelled:
            job.mark_cancelled()
        else:
            job.mark_failed(str(exc))
