#!/usr/bin/env python3
"""Benchmark the upscaler model catalog and (optionally) compare quality.

Run from anywhere:  python backend/bench_upscaler.py
The script inserts the backend directory on sys.path, so it always measures the
same code path the FastAPI worker uses (spandrel load -> _upscale_tile ->
infer_tiled_frame).

Examples
--------
    # FPS sweep on the current machine
    python backend/bench_upscaler.py --resolutions 720p,1080p --frames 20

    # Tiled vs whole-frame, all models, machine-readable output
    python backend/bench_upscaler.py --tile 512 --json bench.json

    # Visual A/B: 5 frames of your own clip, 3x 256px crops per model.
    # --weights lets you test a candidate .pth without editing the catalog:
    python backend/bench_upscaler.py --models 2x-sudo-UltraCompact,Real-ESRGAN Anime \
        --video D:/clips/episode01.mp4 --crops bench_crops

    python backend/bench_upscaler.py --weights "candidate=C:/models/2x_AnimeJaNai_HD_V3_UltraCompact.pth" \
        --models 2x-sudo-UltraCompact,candidate --resolutions 1080p

    # Objective quality: treat a 4K master as ground truth
    python backend/bench_upscaler.py --video D:/clips/episode01.mp4 \
        --reference D:/clips/episode01_4k.mp4 --crops bench_crops

Weights are downloaded on first use into %TEMP%/upscaler_models (same cache the
backend uses), so the first run needs network access.
"""

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import cv2
import numpy as np
import torch

BACKEND_DIR = Path(__file__).resolve().parent
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from models.media_utils import infer_tiled_frame  # noqa: E402
from models.upscaler import (  # noqa: E402
    DEVICE,
    FP16,
    UPSCALER_MODELS,
    _upscale_tile,
    get_upscaler_model,
)

RESOLUTIONS: Dict[str, Tuple[int, int]] = {
    "480p": (854, 480),
    "720p": (1280, 720),
    "1080p": (1920, 1080),
    "1440p": (2560, 1440),
}

CROP_SIZE = 256
CROP_LABEL_HEIGHT = 28

# Filled from --weights KEY=PATH: benchmark candidates without editing the catalog.
_EXTRA_MODELS: Dict[str, Dict[str, object]] = {}


# --------------------------------------------------------------------------- utils


def cuda_sync() -> None:
    if DEVICE == "cuda":
        torch.cuda.synchronize()


def header(text: str) -> None:
    print()
    print("=" * 78)
    print(text)
    print("=" * 78)


def synthetic_frame(width: int, height: int, seed: int = 1234) -> np.ndarray:
    rng = np.random.default_rng(seed)
    ramp = np.linspace(0, 255, width, dtype=np.float32)[None, :, None]
    gradient = np.broadcast_to(ramp, (height, width, 3)).copy()
    noise = rng.integers(0, 32, (height, width, 3), dtype=np.uint8).astype(np.float32)
    return np.clip(gradient + noise, 0, 255).astype(np.uint8)


def psnr(a: np.ndarray, b: np.ndarray) -> float:
    diff = a.astype(np.float64) - b.astype(np.float64)
    mse = float(np.mean(diff * diff))
    return float("inf") if mse == 0.0 else float(10.0 * np.log10((255.0**2) / mse))


def ssim(a: np.ndarray, b: np.ndarray) -> float:
    """Gaussian-windowed SSIM on luminance (11x11, sigma 1.5)."""
    ga = cv2.cvtColor(a, cv2.COLOR_BGR2GRAY).astype(np.float64)
    gb = cv2.cvtColor(b, cv2.COLOR_BGR2GRAY).astype(np.float64)
    c1 = (0.01 * 255.0) ** 2
    c2 = (0.03 * 255.0) ** 2
    kernel = cv2.getGaussianKernel(11, 1.5)
    window = np.outer(kernel, kernel)
    mu_a = cv2.filter2D(ga, -1, window)
    mu_b = cv2.filter2D(gb, -1, window)
    var_a = cv2.filter2D(ga * ga, -1, window) - mu_a * mu_a
    var_b = cv2.filter2D(gb * gb, -1, window) - mu_b * mu_b
    cov = cv2.filter2D(ga * gb, -1, window) - mu_a * mu_b
    num = (2 * mu_a * mu_b + c1) * (2 * cov + c2)
    den = (mu_a * mu_a + mu_b * mu_b + c1) * (var_a + var_b + c2)
    return float(np.mean(num / den))


class LpipsScorer:
    """Optional LPIPS scorer; silently disabled when the package is missing."""

    def __init__(self) -> None:
        self.fn = None
        try:
            import lpips  # type: ignore

            self.fn = lpips.LPIPS(net="alex", verbose=False).to(DEVICE).eval()
        except Exception as exc:  # pragma: no cover - optional dependency
            print(f"[bench] LPIPS disabled ({exc}). Install with: pip install lpips")

    @property
    def available(self) -> bool:
        return self.fn is not None

    @torch.inference_mode()
    def __call__(self, a: np.ndarray, b: np.ndarray) -> float:
        def to_tensor(img: np.ndarray) -> torch.Tensor:
            rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32) / 127.5 - 1.0
            tensor = torch.from_numpy(rgb).permute(2, 0, 1).unsqueeze(0).to(DEVICE)
            return tensor.half() if FP16 else tensor.float()

        return float(self.fn(to_tensor(a), to_tensor(b)).item())


# ------------------------------------------------------------------- inference


def model_meta(key: str) -> Dict[str, object]:
    if key in _EXTRA_MODELS:
        return _EXTRA_MODELS[key]
    return UPSCALER_MODELS[key]


def load_model(key: str):
    """Load a catalog model (production path) or an ad-hoc --weights file."""
    if key in _EXTRA_MODELS:
        from spandrel import ModelLoader

        model = ModelLoader().load_from_file(_EXTRA_MODELS[key]["path"])
        model.to(DEVICE)
        if FP16:
            model.half()
        model.eval()
        return model
    model, _ = get_upscaler_model(key)
    return model


def native_scale(model_key: str) -> int:
    return int(model_meta(model_key)["scale"])


def run_whole_frame(model, key: str, frame: np.ndarray) -> np.ndarray:
    return _upscale_tile(model, frame, model_scale=native_scale(key))


def run_tiled(model, key: str, frame: np.ndarray, tile: int, pad: int = 16) -> np.ndarray:
    scale = native_scale(key)
    return infer_tiled_frame(
        frame,
        lambda tile_img: _upscale_tile(model, tile_img, model_scale=scale),
        scale=scale,
        tile_size=tile,
        tile_pad=pad,
    )


def time_call(fn: Callable[[], object], warmup: int, frames: int) -> Tuple[float, float, float]:
    for _ in range(max(1, warmup)):
        fn()
    cuda_sync()
    if DEVICE == "cuda":
        torch.cuda.reset_peak_memory_stats()
    start = time.perf_counter()
    for _ in range(frames):
        fn()
    cuda_sync()
    elapsed = time.perf_counter() - start
    peak_mb = torch.cuda.max_memory_allocated() / (1024 * 1024) if DEVICE == "cuda" else 0.0
    ms_per_frame = (elapsed / frames) * 1000.0
    return ms_per_frame, frames / elapsed, peak_mb


# ------------------------------------------------------------------------ A/B


def crop_strip(frame: np.ndarray, label: str, size: int = CROP_SIZE) -> np.ndarray:
    h, w = frame.shape[:2]
    size = max(32, min(size, h, w))
    centres = [
        (w // 2, h // 2),
        (w // 4, h // 3),
        (3 * w // 4, 2 * h // 3),
    ]
    crops = []
    for cx, cy in centres:
        x0 = int(np.clip(cx - size // 2, 0, w - size))
        y0 = int(np.clip(cy - size // 2, 0, h - size))
        crops.append(frame[y0 : y0 + size, x0 : x0 + size].copy())
    strip = np.hstack(crops)
    bar = np.zeros((CROP_LABEL_HEIGHT, strip.shape[1], 3), dtype=np.uint8)
    cv2.putText(
        bar,
        label[:64],
        (6, CROP_LABEL_HEIGHT - 9),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.5,
        (255, 255, 255),
        1,
        cv2.LINE_AA,
    )
    return np.vstack([bar, strip])


def sample_video_frames(path: Path, count: int) -> List[np.ndarray]:
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise RuntimeError(f"Unable to open video: {path}")
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    frames: List[np.ndarray] = []
    if total > 0:
        indexes = np.linspace(0, max(0, total - 1), num=count, dtype=int)
        for idx in indexes:
            cap.set(cv2.CAP_PROP_POS_FRAMES, int(idx))
            ok, frame = cap.read()
            if ok and frame is not None:
                frames.append(frame)
    else:
        read = 0
        while len(frames) < count:
            ok, frame = cap.read()
            if not ok:
                break
            if read % 24 == 0:
                frames.append(frame)
            read += 1
    cap.release()
    if not frames:
        raise RuntimeError(f"No frames could be read from {path}")
    return frames


def run_ab(args, keys: Sequence[str]) -> None:
    if not args.video:
        return
    video_path = Path(args.video)
    if not video_path.exists():
        raise SystemExit(f"--video not found: {video_path}")

    crops_dir = Path(args.crops)
    crops_dir.mkdir(parents=True, exist_ok=True)

    frames = sample_video_frames(video_path, args.ab_frames)
    reference_frames: Optional[List[np.ndarray]] = None
    if args.reference:
        ref_path = Path(args.reference)
        if not ref_path.exists():
            raise SystemExit(f"--reference not found: {ref_path}")
        reference_frames = sample_video_frames(ref_path, len(frames))

    lpips_scorer = LpipsScorer() if args.lpips else None

    header(f"A/B comparison: {video_path.name} ({len(frames)} frames)")
    quality_rows: List[Dict[str, object]] = []

    for key in keys:
        model = load_model(key)
        scores: List[Tuple[float, float, float]] = []
        for index, frame in enumerate(frames):
            lr = frame
            gt: Optional[np.ndarray] = None
            if reference_frames is not None:
                ref = reference_frames[index]
                lr = cv2.resize(
                    ref,
                    (max(2, ref.shape[1] // 2), max(2, ref.shape[0] // 2)),
                    interpolation=cv2.INTER_AREA,
                )
                gt = ref

            out = run_whole_frame(model, key, lr)
            strip = crop_strip(out, str(model_meta(key)["name"]))
            cv2.imwrite(str(crops_dir / f"frame{index:03d}_{key}_crops.png"), strip)

            if args.save_full:
                cv2.imwrite(str(crops_dir / f"frame{index:03d}_{key}_full.png"), out)

            if gt is not None:
                h = min(out.shape[0], gt.shape[0])
                w = min(out.shape[1], gt.shape[1])
                cropped_out = out[:h, :w]
                cropped_gt = gt[:h, :w]
                lp = lpips_scorer(cropped_out, cropped_gt) if lpips_scorer and lpips_scorer.available else float("nan")
                scores.append((psnr(cropped_out, cropped_gt), ssim(cropped_out, cropped_gt), lp))

        if scores:
            psnr_avg = float(np.mean([s[0] for s in scores]))
            ssim_avg = float(np.mean([s[1] for s in scores]))
            lps = [s[2] for s in scores if not np.isnan(s[2])]
            lpips_avg = float(np.mean(lps)) if lps else None
            quality_rows.append(
                {"model": key, "name": str(model_meta(key)["name"]), "psnr": psnr_avg, "ssim": ssim_avg, "lpips": lpips_avg}
            )
            print(f"  {key:<42s} PSNR {psnr_avg:6.2f}  SSIM {ssim_avg:0.4f}" + (f"  LPIPS {lpips_avg:0.4f}" if lpips_avg is not None else ""))
        else:
            print(f"  {key:<42s} crops written to {crops_dir}")

    if quality_rows:
        header("Quality vs reference (higher PSNR/SSIM is better, lower LPIPS is better)")
        print(f"{'model':<42s} {'PSNR':>7s} {'SSIM':>7s} {'LPIPS':>7s}")
        for row in quality_rows:
            lp = f"{row['lpips']:.4f}" if row["lpips"] is not None else "-"
            print(f"{row['model']:<42s} {row['psnr']:7.2f} {row['ssim']:7.4f} {lp:>7s}")


# ----------------------------------------------------------------------- main


def parse_resolutions(raw: str) -> List[str]:
    names = [part.strip().lower() for part in raw.split(",") if part.strip()]
    unknown = [name for name in names if name not in RESOLUTIONS]
    if unknown:
        raise SystemExit(f"Unknown resolution(s): {', '.join(unknown)}. Choose from: {', '.join(RESOLUTIONS)}")
    return names


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Benchmark the upscaler model catalog.")
    parser.add_argument("--models", default="", help="Comma-separated catalog keys (default: all)")
    parser.add_argument("--resolutions", default="480p,720p,1080p", help="Comma-separated: 480p,720p,1080p,1440p")
    parser.add_argument("--warmup", type=int, default=5, help="Untimed warmup iterations per cell (default: 5)")
    parser.add_argument("--frames", type=int, default=20, help="Timed iterations per cell (default: 20)")
    parser.add_argument("--tile", type=int, default=512, help="Tile size for the tiled pass (default: 512, 0 disables)")
    parser.add_argument("--skip-whole", action="store_true", help="Skip the whole-frame pass")
    parser.add_argument("--json", dest="json_path", default="", help="Write results as JSON to this path")
    parser.add_argument("--video", default="", help="Video used for the visual A/B comparison")
    parser.add_argument("--reference", default="", help="Optional higher-resolution master used as ground truth")
    parser.add_argument("--crops", default="bench_crops", help="Directory for A/B crop images (default: bench_crops)")
    parser.add_argument("--ab-frames", type=int, default=5, help="Frames sampled for the A/B comparison (default: 5)")
    parser.add_argument("--save-full", action="store_true", help="Also save the full upscaled A/B frames")
    parser.add_argument("--lpips", action="store_true", help="Also compute LPIPS (needs: pip install lpips)")
    parser.add_argument(
        "--weights",
        action="append",
        default=[],
        metavar="KEY=PATH",
        help="Benchmark an extra .pth that is not in the catalog (repeatable)",
    )
    args = parser.parse_args(argv)

    for spec in args.weights:
        if "=" not in spec:
            raise SystemExit(f"--weights expects KEY=PATH, got: {spec}")
        extra_key, extra_path = spec.split("=", 1)
        extra_key = extra_key.strip()
        candidates = [Path(extra_path.strip()), BACKEND_DIR / extra_path.strip()]
        found = next((p for p in candidates if p.is_file()), None)
        if found is None:
            raise SystemExit(f"--weights file not found: {extra_path.strip()}")
        _EXTRA_MODELS[extra_key] = {"name": f"{extra_key} ({found.name})", "path": found, "scale": 2}

    known = list(UPSCALER_MODELS) + list(_EXTRA_MODELS)
    if args.models:
        keys: List[str] = []
        for raw in args.models.split(","):
            raw = raw.strip()
            if not raw:
                continue
            if raw not in known:
                raise SystemExit(f"Unknown model: {raw}. Available: {', '.join(known)}")
            keys.append(raw)
    else:
        keys = known

    resolutions = parse_resolutions(args.resolutions)
    device_name = torch.cuda.get_device_name(0) if DEVICE == "cuda" else "CPU"

    header("Environment")
    print(f"  device      : {device_name} ({DEVICE})")
    print(f"  torch       : {torch.__version__}  fp16={FP16}")
    print(f"  models      : {', '.join(keys)}")
    print(f"  resolutions : {', '.join(resolutions)}")
    print(f"  warmup/frames: {args.warmup}/{args.frames}")
    if DEVICE != "cuda":
        print("  WARNING: no CUDA device. Numbers are CPU-only and NOT representative.")

    results: List[Dict[str, object]] = []

    header("Inference throughput (1x pass, 2x output)")
    print(f"{'model':<42s} {'res':>6s} {'mode':>11s} {'ms/frame':>10s} {'fps':>8s} {'peakMB':>8s}")
    for key in keys:
        model = load_model(key)
        for res_name in resolutions:
            width, height = RESOLUTIONS[res_name]
            frame = synthetic_frame(width, height)
            modes: List[Tuple[str, Callable[[], object]]] = []
            if not args.skip_whole:
                modes.append(("whole", lambda m=model, k=key, f=frame: run_whole_frame(m, k, f)))
            if args.tile > 0:
                modes.append((f"tile{args.tile}", lambda m=model, k=key, f=frame, t=args.tile: run_tiled(m, k, f, t)))
            for mode_name, fn in modes:
                try:
                    ms, fps, peak = time_call(fn, args.warmup, args.frames)
                except RuntimeError as exc:
                    print(f"{key:<42s} {res_name:>6s} {mode_name:>11s}  failed: {exc}")
                    continue
                print(f"{key:<42s} {res_name:>6s} {mode_name:>11s} {ms:10.2f} {fps:8.2f} {peak:8.0f}")
                results.append(
                    {
                        "model": key,
                        "name": str(model_meta(key)["name"]),
                        "resolution": res_name,
                        "input": [width, height],
                        "mode": mode_name,
                        "ms_per_frame": round(ms, 2),
                        "fps": round(fps, 2),
                        "peak_vram_mb": round(peak, 1),
                        "device": device_name,
                        "precision": "fp16" if FP16 else "fp32",
                    }
                )

    if args.video:
        run_ab(args, keys)

    if args.json_path:
        payload = {
            "device": device_name,
            "torch": torch.__version__,
            "fp16": bool(FP16),
            "results": results,
        }
        Path(args.json_path).write_text(json.dumps(payload, indent=2), encoding="utf-8")
        print(f"\nWrote {args.json_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
