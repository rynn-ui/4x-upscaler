import asyncio
import hmac
import json
import os
import queue
import secrets
import shutil
import tempfile
import threading
import uuid
from contextlib import asynccontextmanager
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

# Google Sign-In (ID Token) verification
from google.oauth2 import id_token as google_id_token
from google.auth.transport import requests as google_requests

from models.base import TERMINAL_STATES, TaskJob, job_manager
from models.depth import DEPTH_MODELS, VALID_DEPTH_COLORMAPS, process_depth_job
from models.denoiser import VALID_DENOISE_MODELS, process_denoise_job
from models.media_utils import IMAGE_EXTS, VIDEO_EXTS
from models.rife import RIFE_MODEL_REGISTRY, VALID_RIFE_MODES, VALID_RIFE_MULTIPLIERS, process_rife_job
from models.upscaler import UPSCALER_MODELS, VALID_TARGET_QUALITIES, process_upscale_job


ALLOWED_EXTENSIONS = set(IMAGE_EXTS) | set(VIDEO_EXTS)
MAX_QUEUED_JOBS = int(os.environ.get("UPSCALER_MAX_QUEUED_JOBS", "8"))
MAX_CONCURRENT_UPLOADS = int(os.environ.get("UPSCALER_MAX_CONCURRENT_UPLOADS", "2"))
MULTIPART_OVERHEAD_BYTES = 10 * 1024 * 1024  # 10 MB overhead for multipart boundaries & fields
_BACKEND_INSTANCE_ID = uuid.uuid4().hex[:12]

_PROCESSORS = {
    "upscale": process_upscale_job,
    "rife": process_rife_job,
    "denoise": process_denoise_job,
    "depth": process_depth_job,
}

# Session auth token (never printed verbatim to uvicorn logs)
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


# OAuth 2.0 Web Client ID from Google Cloud Console (used as the ID-token audience).
# Set via the GOOGLE_CLIENT_ID environment variable, e.g. in Colab:
#   os.environ["GOOGLE_CLIENT_ID"] = "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"
GOOGLE_CLIENT_ID: Optional[str] = os.environ.get("GOOGLE_CLIENT_ID") or None


def verify_google_id_token(id_token_value: str) -> Optional[Dict[str, Any]]:
    """Verify a Google Sign-In ID token and return the user profile, or None if invalid.

    The token is a signed JWT issued by Google. We verify its signature, expiry and
    audience (must match GOOGLE_CLIENT_ID). On success we return the user's Google
    sub/email/name/picture so the session can be associated with that Google account.
    """
    if not id_token_value or not GOOGLE_CLIENT_ID:
        return None
    try:
        http_request = google_requests.Request()
        info = google_id_token.verify_oauth2_token(
            id_token_value, http_request, GOOGLE_CLIENT_ID
        )
        issuer = info.get("iss")
        if issuer not in ("accounts.google.com", "https://accounts.google.com"):
            return None
        return {
            "provider": "google",
            "google_id": info.get("sub"),
            "email": info.get("email"),
            "name": info.get("name"),
            "picture": info.get("picture"),
            "email_verified": bool(info.get("email_verified")),
        }
    except Exception:
        # Invalid signature, wrong audience, expired, malformed, etc.
        return None


def _resolve_auth_user(candidate: Optional[str]) -> Optional[Dict[str, Any]]:
    """Resolve a bearer candidate to an authenticated user.

    Tries the legacy session token first, then a Google ID token. Returns a user
    dict (with a ``provider`` field) on success, or None when authentication fails.
    """
    if not candidate:
        return None
    if _verify_token_value(candidate):
        return {"provider": "session"}
    return verify_google_id_token(candidate)


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


async def get_current_user(request: Request) -> Optional[Dict[str, Any]]:
    """Return the authenticated user attached to the request state (None if session-only)."""
    return getattr(request.state, "user", None)


async def require_auth(
    request: Request,
    authorization: Optional[str] = Header(default=None),
    x_upscaler_token: Optional[str] = Header(default=None),
) -> None:
    candidate = _extract_bearer(authorization) or x_upscaler_token
    user = _resolve_auth_user(candidate)
    if user is None:
        raise HTTPException(status_code=401, detail="Unauthorized: invalid or missing session token.")
    request.state.user = user


def _max_upload_bytes() -> int:
    mb = int(os.environ.get("UPSCALER_MAX_UPLOAD_MB", "500"))
    return max(1, mb) * 1024 * 1024


class UploadAdmissionController:
    """Thread-safe admission control bounding concurrent in-flight uploads and overall queue load."""

    def __init__(self, max_concurrent: int):
        self.max_concurrent = max(1, max_concurrent)
        self.active_uploads = 0
        self._lock = threading.Lock()

    def acquire(self) -> bool:
        with self._lock:
            active_jobs = job_manager.active_or_queued_count()
            if self.active_uploads >= self.max_concurrent:
                return False
            if self.active_uploads + active_jobs >= MAX_QUEUED_JOBS:
                return False
            self.active_uploads += 1
            return True

    def release(self) -> None:
        with self._lock:
            if self.active_uploads > 0:
                self.active_uploads -= 1

    def count(self) -> int:
        with self._lock:
            return self.active_uploads


_admission_controller = UploadAdmissionController(MAX_CONCURRENT_UPLOADS)


class UploadAdmissionMiddleware:
    """
    Early ASGI middleware enforcing:
    1. Immediate 401 rejection for unauthenticated/bad-token requests to /api/v1/jobs/submit
       BEFORE reading or spooling the multipart body.
    2. Early 413 rejection when Content-Length exceeds max allowed file size + multipart overhead.
    3. Early 429 rejection when concurrent uploads or queue capacity are saturated.
    4. Running byte-counter limit on streamed/chunked uploads to prevent unbounded parser spooling.
    5. Clean admission slot release on all success/error/disconnect paths.
    """

    def __init__(self, app_instance):
        self.app = app_instance

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        path = scope.get("path", "")
        method = scope.get("method", "GET").upper()

        if path == "/api/v1/jobs/submit" and method == "POST":
            # Extract headers from ASGI scope
            headers = dict(scope.get("headers", []))
            raw_auth = headers.get(b"authorization", b"").decode("latin-1", errors="ignore")
            raw_x_token = headers.get(b"x-upscaler-token", b"").decode("latin-1", errors="ignore")
            candidate = _extract_bearer(raw_auth) or (raw_x_token.strip() if raw_x_token else None)

            # 1. Early auth check: reject before reading any body bytes
            if not _verify_token_value(candidate):
                res = JSONResponse(
                    {"detail": "Unauthorized: invalid or missing session token."},
                    status_code=401,
                )
                await res(scope, receive, send)
                return

            max_media_bytes = _max_upload_bytes()
            max_total_bytes = max_media_bytes + MULTIPART_OVERHEAD_BYTES

            # 2. Early Content-Length check
            raw_cl = headers.get(b"content-length")
            if raw_cl:
                try:
                    cl_val = int(raw_cl.decode("ascii"))
                    if cl_val > max_total_bytes:
                        res = JSONResponse(
                            {"detail": f"Upload exceeds maximum allowed size of {max_total_bytes // (1024 * 1024)} MB."},
                            status_code=413,
                        )
                        await res(scope, receive, send)
                        return
                except ValueError:
                    pass

            # 3. Admission control check: reserve upload slot
            if not _admission_controller.acquire():
                res = JSONResponse(
                    {"detail": "Server busy: upload admission or GPU queue capacity reached. Try again shortly."},
                    status_code=429,
                )
                await res(scope, receive, send)
                return

            # 4. Streamed / chunked byte limiter on receive()
            running_bytes = 0

            async def bounded_receive():
                nonlocal running_bytes
                msg = await receive()
                if msg["type"] == "http.request":
                    chunk_body = msg.get("body", b"")
                    running_bytes += len(chunk_body)
                    if running_bytes > max_total_bytes:
                        raise HTTPException(
                            status_code=413,
                            detail=f"Upload exceeded maximum allowed size of {max_total_bytes // (1024 * 1024)} MB.",
                        )
                return msg

            try:
                await self.app(scope, bounded_receive, send)
            finally:
                _admission_controller.release()
            return

        await self.app(scope, receive, send)


# Bounded GPU execution queue & single dedicated worker thread
_gpu_queue: "queue.Queue[TaskJob]" = queue.Queue(maxsize=MAX_QUEUED_JOBS)
_worker_lock = threading.Lock()
_worker_started = False


def _gpu_worker_loop() -> None:
    while True:
        job = _gpu_queue.get()
        try:
            if job.is_cancelled or job.status in TERMINAL_STATES:
                job.mark_cancelled()
                continue
            processor = _PROCESSORS.get(job.task_type)
            if processor is None:
                job.mark_failed(f"Unknown task_type: {job.task_type}")
                continue
            processor(job, job.params)
        except Exception as e:
            if job.is_cancelled:
                job.mark_cancelled()
            else:
                job.mark_failed(str(e))
        finally:
            _gpu_queue.task_done()


def ensure_worker_started() -> None:
    global _worker_started
    with _worker_lock:
        if not _worker_started:
            t = threading.Thread(target=_gpu_worker_loop, name="upscaler-gpu-worker", daemon=True)
            t.start()
            _worker_started = True


@asynccontextmanager
async def lifespan(_app: FastAPI):
    ensure_worker_started()
    yield


app = FastAPI(title="Upscaler Studio Backend API", version="2.1", lifespan=lifespan)

# Configure CORS cleanly without wildcard + allow_credentials=True
# Configure CORS cleanly to allow mobile app and web clients
_cors_env = [o.strip() for o in os.environ.get("UPSCALER_CORS_ORIGINS", "*").split(",") if o.strip()]
app.add_middleware(
    CORSMiddleware,
    allow_origins=_cors_env,
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.add_middleware(UploadAdmissionMiddleware)


def get_gpu_telemetry() -> Dict[str, Any]:
    if torch.cuda.is_available():
        props = torch.cuda.get_device_properties(0)
        free_mem, total_mem = torch.cuda.mem_get_info(0)
        return {
            "available": True,
            "device": props.name,
            "vram_total_mb": round(total_mem / (1024 * 1024), 1),
            "vram_free_mb": round(free_mem / (1024 * 1024), 1),
        }
    return {
        "available": False,
        "device": "CPU Fallback",
        "vram_total_mb": 0.0,
        "vram_free_mb": 0.0,
    }


def validate_task_params(task_type: str, raw_params: Dict[str, Any]) -> Dict[str, Any]:
    """Strict allowlist & range validation per task_type before any file upload or processing."""
    if task_type == "upscale":
        model = str(raw_params.get("model", "2x-sudo-UltraCompact"))
        if model not in UPSCALER_MODELS:
            raise HTTPException(status_code=422, detail=f"Unsupported upscaler model '{model}'.")
        target_quality = str(raw_params.get("target_quality", "Auto (Native)")).strip()
        tq_norm = target_quality.lower().replace("×", "x")
        raw_passes = raw_params.get("passes", 1)
        try:
            passes = int(raw_passes)
        except (TypeError, ValueError):
            passes = 1
        if tq_norm in {"4x", "4"} or passes >= 2:
            target_quality = "4×"
        elif tq_norm in {"2x", "2x native", "auto (2x native)"}:
            target_quality = "2×"
        elif tq_norm in {"auto", "auto (native)", "native"}:
            target_quality = "Auto (Native)"
        if target_quality not in VALID_TARGET_QUALITIES:
            raise HTTPException(status_code=422, detail=f"Unsupported target_quality '{target_quality}'.")
        raw_crisp = raw_params.get("crisp_outline", False)
        if isinstance(raw_crisp, str):
            crisp_outline = raw_crisp.strip().lower() in {"1", "true", "yes", "on"}
        else:
            crisp_outline = bool(raw_crisp)
        return {"model": model, "target_quality": target_quality, "crisp_outline": crisp_outline}

    if task_type == "rife":
        model_key = str(raw_params.get("model_key", "rife_v4.6"))
        if model_key not in RIFE_MODEL_REGISTRY:
            raise HTTPException(status_code=422, detail=f"Unsupported RIFE model '{model_key}'.")
        try:
            multiplier = int(raw_params.get("multiplier", 2))
        except (TypeError, ValueError):
            raise HTTPException(status_code=422, detail="RIFE multiplier must be an integer (2, 4, or 8).")
        if multiplier not in VALID_RIFE_MULTIPLIERS:
            raise HTTPException(status_code=422, detail=f"Unsupported RIFE multiplier {multiplier}; allowed: 2, 4, 8.")
        mode = str(raw_params.get("mode", "interpolate"))
        if mode not in VALID_RIFE_MODES:
            raise HTTPException(status_code=422, detail=f"Unsupported RIFE mode '{mode}'; allowed: interpolate, slow_motion.")
        return {"model_key": model_key, "multiplier": multiplier, "mode": mode}

    if task_type == "denoise":
        model = str(raw_params.get("model", "1x_archiver_antilines"))
        if model not in VALID_DENOISE_MODELS:
            raise HTTPException(status_code=422, detail=f"Unsupported denoiser model '{model}'.")
        return {"model": model}

    if task_type == "depth":
        model = str(raw_params.get("model", "depth_anything_v2_small"))
        if model not in DEPTH_MODELS:
            raise HTTPException(status_code=422, detail=f"Unsupported depth model '{model}'.")
        colormap = str(raw_params.get("colormap", "inferno")).lower()
        if colormap not in VALID_DEPTH_COLORMAPS:
            raise HTTPException(status_code=422, detail=f"Unsupported depth colormap '{colormap}'.")
        return {"model": model, "colormap": colormap}

    raise HTTPException(status_code=400, detail=f"Unknown task_type: '{task_type}'")


@app.get("/")
async def root_info():
    return {"service": "Upscaler Studio Backend", "version": "2.1", "auth_required": True}


@app.get("/api/v1/health", dependencies=[Depends(require_auth)])
async def health_check():
    return {
        "status": "ok",
        "version": "2.1",
        "backend_instance_id": _BACKEND_INSTANCE_ID,
        "gpu": get_gpu_telemetry(),
        "active_jobs": job_manager.active_or_queued_count(),
        "active_uploads": _admission_controller.count(),
        "queue_capacity": MAX_QUEUED_JOBS,
    }


@app.post("/api/v1/jobs/cancel-client-submission", dependencies=[Depends(require_auth)])
async def cancel_client_submission(client_submission_id: str):
    if not client_submission_id or not client_submission_id.strip():
        raise HTTPException(status_code=400, detail="client_submission_id parameter is required.")
    ok, status = job_manager.cancel_client_submission(client_submission_id.strip())
    return {"client_submission_id": client_submission_id.strip(), "status": status}


@app.get("/api/v1/capabilities", dependencies=[Depends(require_auth)])
async def get_capabilities():
    return {
        "studios": {
            "upscale": {
                "title": "4X Super-Resolution",
                "enabled": True,
                "models": {
                    key: {"name": meta["name"], "scale": meta["scale"]}
                    for key, meta in UPSCALER_MODELS.items()
                },
                "qualities": ["1080p (FHD)", "2K (1440p)", "4K (2160p)", "2×"],
            },
            "rife": {
                "title": "RIFE 60/120 FPS",
                "enabled": True,
                "models": {k: v["name"] for k, v in RIFE_MODEL_REGISTRY.items()},
                "multipliers": [2, 4, 8],
                "modes": ["interpolate", "slow_motion"],
            },
            "denoise": {
                "title": "AI Anti-Line Denoiser",
                "enabled": True,
                "models": {"1x_archiver_antilines": "1x-Archiver-AntiLines (Lines & Noise Removal)"},
            },
            "depth": {
                "title": "Depth Map Studio",
                "enabled": True,
                "models": {
                    "depth_anything_v2_small": "Depth Anything V2 Small (Fast)",
                    "depth_anything_v2_base": "Depth Anything V2 Base (High Precision)",
                },
                "colormaps": sorted(VALID_DEPTH_COLORMAPS),
            },
        }
    }


@app.post("/api/v1/jobs/submit", dependencies=[Depends(require_auth)])
async def submit_job(
    file: UploadFile = File(...),
    task_type: str = Form(...),
    params_json: Optional[str] = Form(None),
    client_submission_id: Optional[str] = Form(None),
):
    if task_type not in _PROCESSORS:
        raise HTTPException(status_code=400, detail=f"Unknown task_type: '{task_type}'")

    raw_params: Dict[str, Any] = {}
    if params_json is not None and params_json.strip() != "":
        try:
            parsed = json.loads(params_json)
        except json.JSONDecodeError as e:
            raise HTTPException(status_code=400, detail=f"Invalid params_json: {e.msg}")
        if not isinstance(parsed, dict):
            raise HTTPException(status_code=400, detail="Invalid params_json: expected a JSON object.")
        raw_params = parsed

    validated_params = validate_task_params(task_type, raw_params)

    # Check queue capacity before buffering file to disk
    if _gpu_queue.full():
        raise HTTPException(status_code=429, detail="Server busy: GPU job queue is full. Try again shortly.")

    work_base = Path(os.environ.get("UPSCALER_WORK_DIR", tempfile.gettempdir()))
    upload_dir = work_base / "upscaler_uploads"
    upload_dir.mkdir(parents=True, exist_ok=True)

    raw_ext = Path(file.filename or "upload.mp4").suffix.lower()
    safe_ext = raw_ext if raw_ext in ALLOWED_EXTENSIONS else ".mp4"
    temp_name = f"{uuid.uuid4().hex}{safe_ext}"
    input_path = upload_dir / temp_name

    max_bytes = _max_upload_bytes()
    total_written = 0

    def _write_chunk(f, data):
        f.write(data)

    try:
        with open(input_path, "wb") as out_f:
            while True:
                chunk = await file.read(1024 * 1024)
                if not chunk:
                    break
                total_written += len(chunk)
                if total_written > max_bytes:
                    out_f.close()
                    if input_path.exists():
                        input_path.unlink()
                    raise HTTPException(
                        status_code=413,
                        detail=f"Upload exceeds maximum allowed size of {max_bytes // (1024 * 1024)} MB.",
                    )
                await asyncio.to_thread(_write_chunk, out_f, chunk)
    except HTTPException:
        raise
    except Exception as e:
        if input_path.exists():
            input_path.unlink()
        raise HTTPException(status_code=500, detail=f"Failed to receive upload: {e}")
    finally:
        await file.close()

    if total_written == 0:
        if input_path.exists():
            input_path.unlink()
        raise HTTPException(status_code=400, detail="Uploaded media file is empty.")

    clean_sub_id = (
        client_submission_id.strip() if client_submission_id and client_submission_id.strip() else None
    )
    if clean_sub_id and job_manager.is_submission_cancelled(clean_sub_id):
        job_manager.consume_cancelled_submission(clean_sub_id)
        if input_path.exists():
            try:
                input_path.unlink()
            except OSError:
                pass
        return {
            "job_id": None,
            "status": "cancelled",
            "message": "Submission cancelled by client before processing.",
        }

    job = job_manager.create_job(
        task_type,
        str(input_path),
        validated_params,
        client_submission_id=clean_sub_id,
    )

    if clean_sub_id and job_manager.is_submission_cancelled(clean_sub_id):
        job.cancel()
        if input_path.exists():
            try:
                input_path.unlink()
            except OSError:
                pass
        return {"job_id": job.job_id, "status": "cancelled"}

    ensure_worker_started()
    try:
        _gpu_queue.put_nowait(job)
    except queue.Full:
        job.mark_failed("Server busy: queue saturated.")
        if input_path.exists():
            input_path.unlink()
        raise HTTPException(status_code=429, detail="Server busy: GPU job queue is full.")

    return {"job_id": job.job_id, "status": job.status}


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


@app.get("/api/v1/jobs/{job_id}/download", dependencies=[Depends(require_auth)])
async def download_result(job_id: str):
    job = job_manager.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    if job.status != "completed" or not job.output_path:
        raise HTTPException(status_code=409, detail=f"Output not ready (job status: '{job.status}').")

    out_file = Path(job.output_path)
    if not out_file.exists() or out_file.stat().st_size == 0:
        raise HTTPException(status_code=500, detail="Processed output file is missing or empty on server.")

    media_type = "image/png" if out_file.suffix.lower() in IMAGE_EXTS else "video/mp4"
    return FileResponse(path=str(out_file), filename=out_file.name, media_type=media_type)


@app.get("/api/v1/auth/me", dependencies=[Depends(require_auth)])
async def auth_me(user: Optional[Dict[str, Any]] = Depends(get_current_user)):
    """Return the profile of the currently authenticated user.

    Works with both legacy session tokens and Google Sign-In ID tokens.
    """
    return {
        "authenticated": True,
        "provider": (user or {}).get("provider", "session"),
        "user": user,
    }


@app.websocket("/api/v1/jobs/{job_id}/stream")
async def stream_progress(websocket: WebSocket, job_id: str):
    auth_header = websocket.headers.get("authorization")
    token_header = websocket.headers.get("x-upscaler-token")
    token_query = websocket.query_params.get("token")
    candidate = _extract_bearer(auth_header) or token_header or token_query
    if _resolve_auth_user(candidate) is None:
        await websocket.close(code=4401)
        return

    job = job_manager.get_job(job_id)
    if not job:
        await websocket.accept()
        await websocket.send_json({"type": "error", "status": "failed", "message": "Job not found"})
        await websocket.close()
        return

    await websocket.accept()
    loop = asyncio.get_running_loop()
    q = job.subscribe(loop)

    try:
        # Deliver immediate snapshot so already-completed/failed/paused jobs don't miss state
        initial = job.current_snapshot_event()
        await websocket.send_json(initial)
        if initial.get("type") in ("complete", "error", "cancelled"):
            return

        while True:
            event = await q.get()
            await websocket.send_json(event)
            if event.get("type") in ("complete", "error", "cancelled"):
                break
    except WebSocketDisconnect:
        pass
    finally:
        job.unsubscribe(q)
