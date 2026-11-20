import os
import time
import base64
import subprocess
import threading
import urllib.request
from pathlib import Path
from typing import Any, Callable, List, Optional, Tuple

import cv2
import numpy as np
import torch


IMAGE_EXTS = (".png", ".jpg", ".jpeg", ".webp", ".bmp")
VIDEO_EXTS = (".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v")

_download_locks: dict[str, threading.Lock] = {}
_download_locks_guard = threading.Lock()


def _get_download_lock(key: str) -> threading.Lock:
    with _download_locks_guard:
        if key not in _download_locks:
            _download_locks[key] = threading.Lock()
        return _download_locks[key]


def is_image_file(path: str) -> bool:
    return Path(path).suffix.lower() in IMAGE_EXTS


def make_even_dims(width: int, height: int) -> Tuple[int, int]:
    """Ensure positive even dimensions for libx264/yuv420p encoding."""
    even_w = max(2, ((int(round(width)) + 1) // 2) * 2)
    even_h = max(2, ((int(round(height)) + 1) // 2) * 2)
    return even_w, even_h


def atomic_download_file(mirrors: List[str], dest_path: Path, min_bytes: int = 10240) -> Path:
    """Download model weights safely to a temporary file and atomically replace destination."""
    lock = _get_download_lock(str(dest_path.resolve()))
    with lock:
        if dest_path.exists() and dest_path.stat().st_size >= min_bytes:
            return dest_path

        dest_path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = dest_path.with_suffix(dest_path.suffix + ".tmp")
        last_error: Optional[Exception] = None

        for url in mirrors:
            try:
                if tmp_path.exists():
                    tmp_path.unlink()
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
                with urllib.request.urlopen(req, timeout=120) as resp, open(tmp_path, "wb") as out_f:
                    while True:
                        chunk = resp.read(1024 * 256)
                        if not chunk:
                            break
                        out_f.write(chunk)
                if tmp_path.exists() and tmp_path.stat().st_size >= min_bytes:
                    os.replace(tmp_path, dest_path)
                    return dest_path
                last_error = RuntimeError(f"Downloaded file too small from {url}")
            except Exception as e:
                last_error = e
            finally:
                if tmp_path.exists():
                    try:
                        tmp_path.unlink()
                    except OSError:
                        pass

        raise RuntimeError(f"Failed to download weights to {dest_path.name}: {last_error}")


def open_video_capture(input_path: str) -> Tuple[cv2.VideoCapture, float, int, int, int]:
    """Open video capture and validate FPS, dimensions, and readable frame count."""
    cap = cv2.VideoCapture(input_path)
    if not cap.isOpened():
        raise RuntimeError(f"Unable to open input video: {Path(input_path).name}")

    fps = float(cap.get(cv2.CAP_PROP_FPS) or 0.0)
    if not np.isfinite(fps) or fps <= 0.0 or fps > 240.0:
        fps = 30.0
    total_frames = max(0, int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0))
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
    if width <= 0 or height <= 0:
        cap.release()
        raise RuntimeError("Invalid video dimensions detected.")
    return cap, fps, total_frames, width, height


def open_video_writer(raw_out_path: Path, fps: float, width: int, height: int) -> Tuple[cv2.VideoWriter, int, int]:
    """Open VideoWriter with validated even encoder dimensions."""
    even_w, even_h = make_even_dims(width, height)
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(str(raw_out_path), fourcc, fps, (even_w, even_h))
    if not writer.isOpened():
        raise RuntimeError(f"Failed to initialize video encoder at {even_w}x{even_h}.")
    return writer, even_w, even_h


def write_frame_fit(writer: cv2.VideoWriter, frame_bgr: np.ndarray, target_w: int, target_h: int) -> None:
    """Resize if needed so frame matches VideoWriter dimensions exactly."""
    h, w = frame_bgr.shape[:2]
    if w != target_w or h != target_h:
        interp = cv2.INTER_AREA if (w > target_w or h > target_h) else cv2.INTER_LANCZOS4
        frame_bgr = cv2.resize(frame_bgr, (target_w, target_h), interpolation=interp)
    writer.write(frame_bgr)


def encode_preview_b64(frame_bgr: np.ndarray, preview_width: int = 240) -> Optional[str]:
    h, w = frame_bgr.shape[:2]
    if w <= 0 or h <= 0:
        return None
    preview_h = max(2, int(round(preview_width * h / w)))
    thumb = cv2.resize(frame_bgr, (preview_width, preview_h), interpolation=cv2.INTER_AREA)
    ok, buf = cv2.imencode(".jpg", thumb, [int(cv2.IMWRITE_JPEG_QUALITY), 80])
    if not ok:
        return None
    return base64.b64encode(buf).decode("utf-8")


def emit_progress(
    job,
    frame_idx: int,
    total_frames: int,
    start_time: float,
    preview_frame: Optional[np.ndarray] = None,
    last_preview_time: float = 0.0,
    preview_interval_sec: float = 1.0,
) -> float:
    elapsed = max(0.05, time.time() - start_time)
    curr_fps = frame_idx / elapsed
    if total_frames > 0:
        rem_frames = max(0, total_frames - frame_idx)
        eta = rem_frames / max(0.05, curr_fps)
        pct = min(100.0, max(0.0, (frame_idx / total_frames) * 100.0))
    else:
        eta = 0.0
        pct = 0.0

    b64: Optional[str] = None
    now = time.time()
    should_gen = (
        preview_frame is not None
        and (
            frame_idx == 1
            or (total_frames > 0 and frame_idx == total_frames)
            or (now - last_preview_time >= preview_interval_sec)
        )
    )
    if should_gen:
        b64 = encode_preview_b64(preview_frame)
        last_preview_time = now

    job.update_progress(pct, frame_idx, total_frames, curr_fps, eta, b64)
    return last_preview_time


def verify_video_output(path: Path) -> bool:
    """Verify exported video exists, is non-empty, and opens cleanly with at least one readable frame."""
    if not path.exists() or path.stat().st_size < 1024:
        return False
    cap = cv2.VideoCapture(str(path))
    try:
        if not cap.isOpened():
            return False
        ret, frame = cap.read()
        return bool(ret and frame is not None and frame.size > 0)
    finally:
        cap.release()


def _run_monitored_ffmpeg(cmd: List[str], job: Optional[Any] = None) -> Tuple[int, str]:
    """Runs FFmpeg subprocess while monitoring job cancellation without pipe buffer deadlock."""
    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    try:
        while True:
            if job is not None and getattr(job, "is_cancelled", False):
                try:
                    proc.kill()
                    proc.communicate(timeout=1.0)
                except Exception:
                    pass
                raise RuntimeError("Operation cancelled by user.")
            try:
                _, stderr = proc.communicate(timeout=0.25)
                return proc.returncode, (stderr or "")
            except subprocess.TimeoutExpired:
                continue
    except Exception:
        if proc.poll() is None:
            try:
                proc.kill()
                proc.communicate(timeout=1.0)
            except Exception:
                pass
        raise


def finalize_video_export(
    raw_out: Path,
    input_path: str,
    final_out: Path,
    preserve_audio: bool = True,
    job: Optional[Any] = None,
) -> Path:
    """
    Re-encode raw video to H.264/yuv420p MP4 with explicit audio handling and verification.
    Preserves raw_out on failure so intermediate output is not lost.
    Cleans up any partial final_out on failure or cancellation.
    """
    if not raw_out.exists() or raw_out.stat().st_size == 0:
        raise RuntimeError("Intermediate rendered video is missing or empty.")

    base_cmd = [
        "ffmpeg", "-y", "-i", str(raw_out),
    ]

    def _execute(cmd: List[str]) -> Tuple[int, str]:
        if job is None:
            res = subprocess.run(cmd, capture_output=True, text=True)
            return res.returncode, (res.stderr or "")
        return _run_monitored_ffmpeg(cmd, job=job)

    # Hardware NVENC acceleration on GPU runtimes (Colab T4), fast libx264 fallback on CPU
    video_encoders: List[List[str]] = []
    if torch.cuda.is_available():
        video_encoders.append(["-c:v", "h264_nvenc", "-preset", "p4", "-cq", "20", "-pix_fmt", "yuv420p"])
    video_encoders.append(["-c:v", "libx264", "-preset", "veryfast", "-crf", "19", "-pix_fmt", "yuv420p"])

    try:
        success = False
        last_stderr = ""
        last_ret_code = -1

        for v_enc in video_encoders:
            if preserve_audio:
                cmd_copy = base_cmd + [
                    "-i", input_path,
                    "-map", "0:v:0", "-map", "1:a:0?",
                ] + v_enc + [
                    "-movflags", "+faststart",
                    "-c:a", "copy", "-shortest",
                    str(final_out),
                ]
                ret_code, stderr = _execute(cmd_copy)
                if ret_code != 0:
                    # Explicit AAC transcode fallback when source container audio (e.g. Opus/Vorbis/PCM) cannot be copied into MP4
                    cmd_aac = base_cmd + [
                        "-i", input_path,
                        "-map", "0:v:0", "-map", "1:a:0?",
                    ] + v_enc + [
                        "-movflags", "+faststart",
                        "-c:a", "aac", "-b:a", "192k", "-shortest",
                        str(final_out),
                    ]
                    ret_code, stderr = _execute(cmd_aac)
            else:
                cmd_noaudio = base_cmd + [
                    "-map", "0:v:0", "-an",
                ] + v_enc + [
                    "-movflags", "+faststart",
                    str(final_out),
                ]
                ret_code, stderr = _execute(cmd_noaudio)

            if ret_code == 0 and verify_video_output(final_out):
                success = True
                break
            else:
                last_ret_code = ret_code
                last_stderr = stderr
                if final_out.exists():
                    try:
                        final_out.unlink()
                    except OSError:
                        pass

        if not success:
            stderr_tail = last_stderr.strip()[-400:]
            raise RuntimeError(f"FFmpeg muxing failed (exit {last_ret_code}): {stderr_tail}")

        try:
            if raw_out.exists():
                raw_out.unlink()
        except OSError:
            pass

        return final_out
    except Exception:
        if final_out.exists():
            try:
                final_out.unlink()
            except OSError:
                pass
        raise


@torch.inference_mode()
def infer_tiled_frame(
    frame_bgr: np.ndarray,
    single_tile_fn: Callable[[np.ndarray], np.ndarray],
    scale: int = 1,
    tile_size: int = 512,
    tile_pad: int = 16,
) -> np.ndarray:
    """
    Runs model inference across overlapping tiles to avoid VRAM OOM on large frames.
    Automatically retries with smaller tile sizes on CUDA out-of-memory errors.
    """
    h, w = frame_bgr.shape[:2]
    current_tile = tile_size

    while True:
        try:
            if h <= current_tile and w <= current_tile:
                return single_tile_fn(frame_bgr)

            out_h, out_w = h * scale, w * scale
            output = np.zeros((out_h, out_w, 3), dtype=np.uint8)

            tiles_x = (w + current_tile - 1) // current_tile
            tiles_y = (h + current_tile - 1) // current_tile

            for y in range(tiles_y):
                for x in range(tiles_x):
                    ofs_x = x * current_tile
                    ofs_y = y * current_tile

                    in_start_x = max(ofs_x - tile_pad, 0)
                    in_end_x = min(ofs_x + current_tile + tile_pad, w)
                    in_start_y = max(ofs_y - tile_pad, 0)
                    in_end_y = min(ofs_y + current_tile + tile_pad, h)

                    tile_in = frame_bgr[in_start_y:in_end_y, in_start_x:in_end_x]
                    tile_out = single_tile_fn(tile_in)

                    out_start_x = ofs_x * scale
                    out_end_x = min(ofs_x + current_tile, w) * scale
                    out_start_y = ofs_y * scale
                    out_end_y = min(ofs_y + current_tile, h) * scale

                    crop_start_x = (ofs_x - in_start_x) * scale
                    crop_end_x = crop_start_x + (out_end_x - out_start_x)
                    crop_start_y = (ofs_y - in_start_y) * scale
                    crop_end_y = crop_start_y + (out_end_y - out_start_y)

                    output[out_start_y:out_end_y, out_start_x:out_end_x] = tile_out[
                        crop_start_y:crop_end_y, crop_start_x:crop_end_x
                    ]
            return output
        except RuntimeError as e:
            if "out of memory" in str(e).lower() and current_tile > 128:
                if torch.cuda.is_available():
                    torch.cuda.empty_cache()
                current_tile = current_tile // 2
                continue
            raise

