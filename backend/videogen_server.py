"""
FastAPI Server for Video Generation Studio (Dedicated Colab Backend).
Provides endpoints for text-to-video, image-to-video, video-to-video,
motion copy, and character exchange powered by Wan 2.1/2.2 and Diffusers.
"""

from __future__ import annotations

import asyncio
import hmac
import json
import os
import queue
import secrets
import shutil
import tempfile
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional

import torch
from fastapi import (
    Depends,
    FastAPI,
    File,
    Form,
    Header,
    HTTPException,
    Request,
    UploadFile,
    WebSocket,
    WebSocketDisconnect,
)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse

from models.base import TERMINAL_STATES, TaskJob, job_manager
from models.media_utils import IMAGE_EXTS, VIDEO_EXTS
from models.videogen import (
    DEFAULT_NEGATIVE_PROMPT,
    VALID_ASPECT_RATIOS,
    VALID_DURATIONS_SEC,
    VIDEOGEN_MODES,
    VIDEOGEN_MODELS,
    default_model_for_mode,
    get_videogen_capabilities,
    process_videogen_job,
)

ALLOWED_EXTENSIONS = set(IMAGE_EXTS) | set(VIDEO_EXTS)
MAX_QUEUED_JOBS = int(os.environ.get("VIDEOGEN_MAX_QUEUED_JOBS", "6"))
MAX_CONCURRENT_UPLOADS = int(os.environ.get("VIDEOGEN_MAX_CONCURRENT_UPLOADS", "2"))
MULTIPART_OVERHEAD_BYTES = 10 * 1024 * 1024
_BACKEND_INSTANCE_ID = uuid.uuid4().hex[:12]

# Auth Token setup
_token_lock = threading.Lock()
_SESSION_TOKEN: str = os.environ.get("UPSCALER_AUTH_TOKEN") or secrets.token_urlsafe(24)


def get_auth_token() -> str:
    with _token_lock:
        return _SESSION_TOKEN


def set_auth_token(token: str) -> None:
    global _SESSION_TOKEN
    if not token or not token.strip():
        raise ValueError("Auth token must not be empty.")
    with _token_lock:
        _SESSION_TOKEN = token.strip()


def _verify_token_value(candidate: Optional[str]) -> bool:
    if not candidate:
        return False
    expected = get_auth_token()
    return hmac.compare_digest(candidate.strip(), expected)


def _extract_bearer(authorization: Optional[str]) -> Optional[str]:
    if not authorization:
        return None
    parts = authorization.strip().split(" ", 1)
    if len(parts) == 2 and parts[0].lower() == "bearer":
        return parts[1].strip()
    return None


async def require_auth(
    request: Request,
    authorization: Optional[str] = Header(default=None),
    x_upscaler_token: Optional[str] = Header(default=None),
) -> None:
    candidate = _extract_bearer(authorization) or x_upscaler_token
    if not _verify_token_value(candidate):
        raise HTTPException(status_code=401, detail="Unauthorized: invalid or missing session token.")
    request.state.user = {"provider": "session"}


def _max_upload_bytes() -> int:
    mb = int(os.environ.get("UPSCALER_MAX_UPLOAD_MB", "500"))
    return max(1, mb) * 1024 * 1024


# ---------------------------------------------------------------------------
# Worker & Admission Queue
# ---------------------------------------------------------------------------

_gpu_queue: queue.Queue = queue.Queue(maxsize=MAX_QUEUED_JOBS)
_worker_thread: Optional[threading.Thread] = None
_worker_lock = threading.Lock()


def _gpu_worker_loop():
    while True:
        job: TaskJob = _gpu_queue.get()
        try:
            if not job.is_cancelled:
                process_videogen_job(job, job.params)
        except Exception as exc:
            if not job.is_cancelled:
                job.mark_failed(f"Worker unhandled error: {exc}")
        finally:
            _gpu_queue.task_done()


def ensure_worker_started():
    global _worker_thread
    with _worker_lock:
        if _worker_thread is None or not _worker_thread.is_alive():
            _worker_thread = threading.Thread(target=_gpu_worker_loop, daemon=True, name="VideoGenGPUWorker")
            _worker_thread.start()


# ---------------------------------------------------------------------------
# GPU Telemetry
# ---------------------------------------------------------------------------

def get_gpu_telemetry() -> Dict[str, Any]:
    if torch.cuda.is_available():
        try:
            device_idx = 0
            dev_name = torch.cuda.get_device_name(device_idx)
            free_mem, total_mem = torch.cuda.mem_get_info(device_idx)
            return {
                "available": True,
                "device": dev_name,
                "vram_total_mb": round(total_mem / (1024 * 1024), 1),
                "vram_free_mb": round(free_mem / (1024 * 1024), 1),
            }
        except Exception:
            pass
    return {
        "available": False,
        "device": "CPU Fallback",
        "vram_total_mb": 0.0,
        "vram_free_mb": 0.0,
    }


# ---------------------------------------------------------------------------
# FastAPI App Definition
# ---------------------------------------------------------------------------

app = FastAPI(
    title="Video Generation Studio Backend",
    version="1.0.0",
    docs_url=None,
    redoc_url=None,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.on_event("startup")
async def on_startup():
    ensure_worker_started()


@app.get("/")
async def root_info():
    return {
        "service": "Video Generation Studio Backend",
        "version": "1.0",
        "engine": "Wan 2.1 / Wan 2.2 / Diffusers",
        "auth_required": True,
    }


@app.get("/api/v1/health", dependencies=[Depends(require_auth)])
async def health_check():
    gpu = get_gpu_telemetry()
    return {
        "status": "ok",
        "version": "1.0",
        "backend_instance_id": _BACKEND_INSTANCE_ID,
        "gpu": gpu,
        "active_jobs": job_manager.active_or_queued_count(),
        "queue_capacity": MAX_QUEUED_JOBS,
    }


@app.get("/api/v1/capabilities", dependencies=[Depends(require_auth)])
async def capabilities():
    gpu = get_gpu_telemetry()
    vram_mb = gpu.get("vram_total_mb", 0.0)
    return get_videogen_capabilities(vram_mb)


# ---------------------------------------------------------------------------
# Job Submission Endpoint
# ---------------------------------------------------------------------------

def validate_videogen_params(raw_params: Dict[str, Any], vram_mb: float) -> Dict[str, Any]:
    mode = str(raw_params.get("mode", "t2v")).lower().strip()
    if mode not in VIDEOGEN_MODES:
        raise HTTPException(status_code=422, detail=f"Unsupported mode '{mode}'. Allowed: {list(VIDEOGEN_MODES.keys())}")

    default_model = default_model_for_mode(mode, vram_gb=vram_mb / 1024.0)
    model = str(raw_params.get("model", default_model)).strip()
    if model not in VIDEOGEN_MODELS:
        raise HTTPException(status_code=422, detail=f"Unsupported model '{model}'.")

    model_meta = VIDEOGEN_MODELS[model]
    if mode not in model_meta["modes"]:
        raise HTTPException(status_code=422, detail=f"Model '{model}' does not support mode '{mode}'.")

    aspect_ratio = str(raw_params.get("aspect_ratio", "16:9")).strip()
    if aspect_ratio not in VALID_ASPECT_RATIOS:
        raise HTTPException(status_code=422, detail=f"Unsupported aspect_ratio '{aspect_ratio}'.")

    try:
        duration_sec = int(raw_params.get("duration_sec", 5))
    except (TypeError, ValueError):
        duration_sec = 5
    if duration_sec not in VALID_DURATIONS_SEC:
        raise HTTPException(status_code=422, detail=f"Unsupported duration '{duration_sec}'s; allowed: {VALID_DURATIONS_SEC}")

    try:
        num_inference_steps = max(10, min(50, int(raw_params.get("num_inference_steps", 28))))
    except (TypeError, ValueError):
        num_inference_steps = 28

    try:
        guidance_scale = max(1.0, min(15.0, float(raw_params.get("guidance_scale", 5.0))))
    except (TypeError, ValueError):
        guidance_scale = 5.0

    try:
        seed = int(raw_params.get("seed", -1))
    except (TypeError, ValueError):
        seed = -1

    return {
        "mode": mode,
        "model": model,
        "prompt": str(raw_params.get("prompt", "")).strip(),
        "negative_prompt": str(raw_params.get("negative_prompt", DEFAULT_NEGATIVE_PROMPT)).strip(),
        "aspect_ratio": aspect_ratio,
        "resolution": str(raw_params.get("resolution", "480p")),
        "duration_sec": duration_sec,
        "num_inference_steps": num_inference_steps,
        "guidance_scale": guidance_scale,
        "seed": seed,
        "use_depth_guidance": bool(raw_params.get("use_depth_guidance", False)),
        "preserve_audio": bool(raw_params.get("preserve_audio", True)),
        "mask_point_x": raw_params.get("mask_point_x"),
        "mask_point_y": raw_params.get("mask_point_y"),
    }


async def _save_upload_file(file: UploadFile, dest_dir: Path) -> Path:
    raw_ext = Path(file.filename or "upload.mp4").suffix.lower()
    safe_ext = raw_ext if raw_ext in ALLOWED_EXTENSIONS else ".mp4"
    dest_path = dest_dir / f"{uuid.uuid4().hex}{safe_ext}"
    max_bytes = _max_upload_bytes()
    written = 0

    try:
        with open(dest_path, "wb") as out_f:
            while True:
                chunk = await file.read(1024 * 1024)
                if not chunk:
                    break
                written += len(chunk)
                if written > max_bytes:
                    raise HTTPException(
                        status_code=413,
                        detail=f"Upload exceeds maximum allowed size of {max_bytes // (1024 * 1024)} MB.",
                    )
                out_f.write(chunk)
    finally:
        await file.close()

    if written == 0:
        if dest_path.exists():
            dest_path.unlink()
        raise HTTPException(status_code=400, detail="Uploaded file is empty.")
    return dest_path


@app.post("/api/v1/videogen/submit", dependencies=[Depends(require_auth)])
async def submit_videogen_job(
    file: Optional[UploadFile] = File(None),
    reference_image: Optional[UploadFile] = File(None),
    params_json: Optional[str] = Form(None),
    client_submission_id: Optional[str] = Form(None),
):
    if _gpu_queue.full():
        raise HTTPException(status_code=429, detail="Server busy: Video Gen GPU queue is full. Try again shortly.")

    raw_params: Dict[str, Any] = {}
    if params_json and params_json.strip():
        try:
            raw_params = json.loads(params_json)
        except json.JSONDecodeError as exc:
            raise HTTPException(status_code=400, detail=f"Invalid params_json: {exc.msg}")

    gpu_telemetry = get_gpu_telemetry()
    validated_params = validate_videogen_params(raw_params, gpu_telemetry.get("vram_total_mb", 0.0))
    mode = validated_params["mode"]

    work_base = Path(os.environ.get("UPSCALER_WORK_DIR", tempfile.gettempdir()))
    upload_dir = work_base / "upscaler_uploads"
    upload_dir.mkdir(parents=True, exist_ok=True)

    input_path: Optional[str] = None
    if file and file.filename:
        saved_path = await _save_upload_file(file, upload_dir)
        input_path = str(saved_path)

    if VIDEOGEN_MODES[mode]["requires_primary_file"] and not input_path:
        raise HTTPException(status_code=400, detail=f"Mode '{mode}' requires a primary input media file.")

    if reference_image and reference_image.filename:
        ref_path = await _save_upload_file(reference_image, upload_dir)
        validated_params["reference_image_path"] = str(ref_path)

    if VIDEOGEN_MODES[mode]["requires_reference_image"] and not validated_params.get("reference_image_path"):
        raise HTTPException(status_code=400, detail=f"Mode '{mode}' requires an uploaded character reference image.")

    clean_sub_id = (client_submission_id.strip() if client_submission_id and client_submission_id.strip() else None)
    if clean_sub_id and job_manager.is_submission_cancelled(clean_sub_id):
        job_manager.consume_cancelled_submission(clean_sub_id)
        if input_path and os.path.exists(input_path):
            try:
                os.unlink(input_path)
            except OSError:
                pass
        return {"job_id": None, "status": "cancelled", "message": "Cancelled by client before queuing."}

    job = job_manager.create_job(
        task_type="videogen",
        input_path=input_path or "",
        params=validated_params,
        client_submission_id=clean_sub_id,
    )

    ensure_worker_started()
    try:
        _gpu_queue.put_nowait(job)
    except queue.Full:
        job.mark_failed("Server busy: queue saturated.")
        raise HTTPException(status_code=429, detail="Server busy: Video Gen GPU queue is full.")

    return {"job_id": job.job_id, "status": job.status}


# ---------------------------------------------------------------------------
# Job Control Endpoints
# ---------------------------------------------------------------------------

@app.get("/api/v1/jobs/{job_id}/status", dependencies=[Depends(require_auth)])
async def get_job_status(job_id: str):
    job = job_manager.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    return job.status_dict()


@app.post("/api/v1/jobs/{job_id}/pause", dependencies=[Depends(require_auth)])
async def pause_job(job_id: str):
    job = job_manager.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    ok, current_status = job.pause()
    if not ok:
        raise HTTPException(status_code=409, detail=f"Cannot pause job in '{current_status}' state.")
    return {"status": current_status}


@app.post("/api/v1/jobs/{job_id}/resume", dependencies=[Depends(require_auth)])
async def resume_job(job_id: str):
    job = job_manager.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    ok, current_status = job.resume()
    if not ok:
        raise HTTPException(status_code=409, detail=f"Cannot resume job in '{current_status}' state.")
    return {"status": current_status}


@app.post("/api/v1/jobs/{job_id}/cancel", dependencies=[Depends(require_auth)])
async def cancel_job(job_id: str):
    job = job_manager.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    ok, current_status = job.cancel()
    if not ok:
        raise HTTPException(status_code=409, detail=f"Cannot cancel job in '{current_status}' state.")
    return {"status": "cancelling" if current_status not in TERMINAL_STATES else current_status}


@app.post("/api/v1/jobs/cancel-client-submission", dependencies=[Depends(require_auth)])
async def cancel_client_submission(client_submission_id: str):
    if not client_submission_id or not client_submission_id.strip():
        raise HTTPException(status_code=400, detail="client_submission_id parameter is required.")
    ok, status = job_manager.cancel_client_submission(client_submission_id.strip())
    return {"client_submission_id": client_submission_id.strip(), "status": status}


@app.get("/api/v1/jobs/{job_id}/download", dependencies=[Depends(require_auth)])
async def download_result(job_id: str):
    job = job_manager.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    if job.status != "completed" or not job.output_path or not os.path.exists(job.output_path):
        raise HTTPException(status_code=404, detail=f"No completed output available for job '{job_id}'.")

    filename = Path(job.output_path).name
    return FileResponse(
        path=job.output_path,
        media_type="video/mp4",
        filename=filename,
    )


# ---------------------------------------------------------------------------
# WebSocket Real-Time Progress & Thumbnail Streaming
# ---------------------------------------------------------------------------

@app.websocket("/api/v1/jobs/{job_id}/stream")
@app.websocket("/api/v1/jobs/{job_id}/ws")
async def job_websocket(websocket: WebSocket, job_id: str):
    raw_token = websocket.query_params.get("token")
    if not raw_token:
        auth_header = websocket.headers.get("authorization", "")
        raw_token = _extract_bearer(auth_header)
    if not raw_token:
        raw_token = websocket.headers.get("x-upscaler-token")

    if not _verify_token_value(raw_token):
        await websocket.close(code=4401)
        return

    job = job_manager.get_job(job_id)
    if not job:
        await websocket.accept()
        await websocket.send_json({"type": "error", "message": f"Job '{job_id}' not found."})
        await websocket.close()
        return

    await websocket.accept()
    loop = asyncio.get_running_loop()
    event_queue = job.subscribe(loop)

    try:
        initial = job.current_snapshot_event()
        await websocket.send_json(initial)
        if initial.get("type") in ("complete", "error", "cancelled"):
            return

        while True:
            evt = await event_queue.get()
            await websocket.send_json(evt)
            if evt.get("type") in ("complete", "error", "cancelled"):
                break
    except (WebSocketDisconnect, asyncio.CancelledError):
        pass
    finally:
        job.unsubscribe(event_queue)
        try:
            await websocket.close()
        except Exception:
            pass
