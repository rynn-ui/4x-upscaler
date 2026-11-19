import asyncio
import time
import uuid
import queue
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


TERMINAL_STATES = frozenset({"completed", "failed", "cancelled"})


def _enqueue_safe(q: asyncio.Queue, event: Dict[str, Any]) -> None:
    """Called on the subscriber's event loop thread via loop.call_soon_threadsafe."""
    if q.full():
        try:
            q.get_nowait()
        except asyncio.QueueEmpty:
            pass
    try:
        q.put_nowait(event)
    except asyncio.QueueFull:
        pass


class TaskJob:
    """Thread-safe GPU processing job with strict state transitions and cross-loop event delivery."""

    def __init__(
        self,
        job_id: str,
        task_type: str,
        input_path: str,
        params: Dict[str, Any],
        client_submission_id: Optional[str] = None,
    ):
        self.job_id = job_id
        self.task_type = task_type
        self.input_path = input_path
        self.params = params
        self.client_submission_id = client_submission_id
        self.output_path: Optional[str] = None
        self.raw_intermediate_path: Optional[str] = None
        self.status: str = "queued"
        self.progress: float = 0.0
        self.current_frame: int = 0
        self.total_frames: int = 0
        self.fps: float = 0.0
        self.eta_seconds: float = 0.0
        self.preview_b64: Optional[str] = None
        self.error_message: Optional[str] = None
        self.is_paused: bool = False
        self.is_cancelled: bool = False
        self.created_at: float = time.time()
        self.completed_at: Optional[float] = None

        self._lock = threading.RLock()
        self._pause_clear_event = threading.Event()
        self._pause_clear_event.set()  # set = NOT paused
        self._subscribers: List[Tuple[asyncio.AbstractEventLoop, asyncio.Queue]] = []

    def subscribe(self, loop: asyncio.AbstractEventLoop) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=128)
        with self._lock:
            self._subscribers.append((loop, q))
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        with self._lock:
            self._subscribers = [(lp, sub_q) for lp, sub_q in self._subscribers if sub_q is not q]

    def _emit(self, event: Dict[str, Any]) -> None:
        with self._lock:
            subscribers = list(self._subscribers)
        stale: List[asyncio.Queue] = []
        for loop, q in subscribers:
            if loop.is_closed():
                stale.append(q)
                continue
            try:
                loop.call_soon_threadsafe(_enqueue_safe, q, event)
            except RuntimeError:
                stale.append(q)
        if stale:
            with self._lock:
                self._subscribers = [
                    (lp, sub_q) for lp, sub_q in self._subscribers if sub_q not in stale
                ]

    def current_snapshot_event(self) -> Dict[str, Any]:
        """Returns the current state as a WebSocket-compatible event for newly connected/reconnected clients."""
        with self._lock:
            if self.status == "completed":
                return {
                    "type": "complete",
                    "status": "completed",
                    "progress": 100.0,
                    "current_frame": self.current_frame,
                    "total_frames": self.total_frames,
                    "fps": self.fps,
                    "eta_seconds": 0.0,
                    "preview_b64": self.preview_b64,
                    "download_url": f"/api/v1/jobs/{self.job_id}/download",
                }
            if self.status == "failed":
                return {
                    "type": "error",
                    "status": "failed",
                    "progress": self.progress,
                    "current_frame": self.current_frame,
                    "total_frames": self.total_frames,
                    "message": self.error_message or "Job failed.",
                }
            if self.status == "cancelled":
                return {
                    "type": "cancelled",
                    "status": "cancelled",
                    "progress": self.progress,
                    "current_frame": self.current_frame,
                    "total_frames": self.total_frames,
                    "message": self.error_message or "Job cancelled by user.",
                }
            return {
                "type": "status" if self.status in ("queued", "paused") else "progress",
                "status": self.status,
                "progress": round(self.progress, 2),
                "current_frame": self.current_frame,
                "total_frames": self.total_frames,
                "fps": round(self.fps, 2),
                "eta_seconds": round(max(0.0, self.eta_seconds), 1),
                "preview_b64": self.preview_b64,
                "message": "Paused" if self.status == "paused" else ("Waiting in GPU queue..." if self.status == "queued" else None),
            }

    def status_dict(self) -> Dict[str, Any]:
        with self._lock:
            download_url = f"/api/v1/jobs/{self.job_id}/download" if self.status == "completed" else None
            return {
                "job_id": self.job_id,
                "task_type": self.task_type,
                "status": self.status,
                "progress": round(self.progress, 2),
                "current_frame": self.current_frame,
                "total_frames": self.total_frames,
                "fps": round(self.fps, 2),
                "eta_seconds": round(max(0.0, self.eta_seconds), 1),
                "download_url": download_url,
                "error_message": self.error_message,
            }

    def start_processing(self) -> bool:
        with self._lock:
            if self.is_cancelled or self.status in TERMINAL_STATES:
                return False
            if self.is_paused or self.status == "paused":
                self.status = "paused"
                self.is_paused = True
                self._pause_clear_event.clear()
            else:
                self.status = "processing"
                self.is_paused = False
                self._pause_clear_event.set()
            evt = self.current_snapshot_event()
        self._emit(evt)
        return True

    def pause(self) -> Tuple[bool, str]:
        with self._lock:
            if self.status in TERMINAL_STATES or self.is_cancelled:
                return False, ("cancelled" if self.is_cancelled else self.status)
            if self.status == "paused":
                return True, "paused"
            if self.status not in ("queued", "processing"):
                return False, self.status
            self.is_paused = True
            self.status = "paused"
            self._pause_clear_event.clear()
            evt = {
                "type": "status",
                "status": "paused",
                "progress": round(self.progress, 2),
                "current_frame": self.current_frame,
                "total_frames": self.total_frames,
                "fps": round(self.fps, 2),
                "eta_seconds": round(max(0.0, self.eta_seconds), 1),
                "preview_b64": self.preview_b64,
                "message": "Job paused",
            }
        self._emit(evt)
        return True, "paused"

    def resume(self) -> Tuple[bool, str]:
        with self._lock:
            if self.is_cancelled or self.status in TERMINAL_STATES:
                return False, ("cancelled" if self.is_cancelled else self.status)
            if self.status == "processing":
                return True, "processing"
            if self.status != "paused":
                return False, self.status
            self.is_paused = False
            self.status = "processing"
            self._pause_clear_event.set()
            evt = {
                "type": "status",
                "status": "processing",
                "progress": round(self.progress, 2),
                "current_frame": self.current_frame,
                "total_frames": self.total_frames,
                "fps": round(self.fps, 2),
                "eta_seconds": round(max(0.0, self.eta_seconds), 1),
                "preview_b64": self.preview_b64,
                "message": "Job resumed",
            }
        self._emit(evt)
        return True, "processing"

    def cancel(self) -> Tuple[bool, str]:
        emit_evt: Optional[Dict[str, Any]] = None
        with self._lock:
            if self.status in TERMINAL_STATES:
                return False, self.status
            if self.is_cancelled:
                return True, "cancelled"
            self.is_cancelled = True
            self.is_paused = False
            self._pause_clear_event.set()
            if self.status in ("queued", "paused"):
                self.status = "cancelled"
                self.error_message = "Cancelled by user."
                self.completed_at = time.time()
                emit_evt = {
                    "type": "cancelled",
                    "status": "cancelled",
                    "progress": round(self.progress, 2),
                    "current_frame": self.current_frame,
                    "total_frames": self.total_frames,
                    "message": "Cancelled by user.",
                }
        if emit_evt is not None:
            self._emit(emit_evt)
        return True, self.status

    def wait_if_paused(self) -> bool:
        """
        Blocks efficiently while job is paused. Returns True if the job has been cancelled.
        """
        while True:
            with self._lock:
                if self.is_cancelled or self.status == "cancelled":
                    return True
                if not self.is_paused:
                    return False
            self._pause_clear_event.wait(timeout=0.25)

    def update_progress(
        self,
        progress: float,
        current_frame: int,
        total_frames: int,
        fps: float,
        eta: float,
        preview_b64: Optional[str] = None,
    ) -> None:
        with self._lock:
            if self.status in TERMINAL_STATES:
                return
            self.progress = min(100.0, max(0.0, float(progress)))
            self.current_frame = max(0, int(current_frame))
            self.total_frames = max(0, int(total_frames))
            self.fps = max(0.0, float(fps))
            self.eta_seconds = max(0.0, float(eta))
            if preview_b64 is not None:
                self.preview_b64 = preview_b64
            event = {
                "type": "progress",
                "status": self.status,
                "progress": round(self.progress, 2),
                "current_frame": self.current_frame,
                "total_frames": self.total_frames,
                "fps": round(self.fps, 2),
                "eta_seconds": round(self.eta_seconds, 1),
                "preview_b64": preview_b64 if preview_b64 is not None else self.preview_b64,
            }
        self._emit(event)

    def mark_completed(self, output_path: str) -> None:
        with self._lock:
            if self.is_cancelled or self.status in TERMINAL_STATES:
                if output_path and os.path.exists(output_path):
                    try:
                        os.unlink(output_path)
                    except OSError:
                        pass
                return
            self.output_path = output_path
            self.status = "completed"
            self.progress = 100.0
            self.eta_seconds = 0.0
            self.completed_at = time.time()
            event = {
                "type": "complete",
                "status": "completed",
                "progress": 100.0,
                "current_frame": self.current_frame,
                "total_frames": self.total_frames,
                "fps": round(self.fps, 2),
                "eta_seconds": 0.0,
                "preview_b64": self.preview_b64,
                "download_url": f"/api/v1/jobs/{self.job_id}/download",
            }
        self._emit(event)

    def mark_cancelled(self, message: str = "Cancelled by user.") -> None:
        with self._lock:
            if self.status in TERMINAL_STATES:
                return
            self.status = "cancelled"
            self.is_cancelled = True
            self.is_paused = False
            self.error_message = message
            self.completed_at = time.time()
            event = {
                "type": "cancelled",
                "status": "cancelled",
                "progress": round(self.progress, 2),
                "current_frame": self.current_frame,
                "total_frames": self.total_frames,
                "message": message,
            }
        self._emit(event)

    def mark_failed(self, error_msg: str) -> None:
        with self._lock:
            if self.is_cancelled:
                self.mark_cancelled()
                return
            if self.status in TERMINAL_STATES:
                return
            self.status = "failed"
            self.error_message = error_msg
            self.completed_at = time.time()
            event = {
                "type": "error",
                "status": "failed",
                "progress": round(self.progress, 2),
                "current_frame": self.current_frame,
                "total_frames": self.total_frames,
                "message": error_msg,
            }
        self._emit(event)


class JobManager:
    """Thread-safe registry for jobs with bounded retention and artifact cleanup."""

    def __init__(self, retention_seconds: float = 3600.0):
        self.jobs: Dict[str, TaskJob] = {}
        self._lock = threading.RLock()
        self.retention_seconds = retention_seconds
        self._client_submission_to_job_id: Dict[str, str] = {}
        self._cancelled_submissions: Dict[str, float] = {}

    def create_job(
        self,
        task_type: str,
        input_path: str,
        params: Dict[str, Any],
        client_submission_id: Optional[str] = None,
    ) -> TaskJob:
        self.prune_expired()
        job_id = uuid.uuid4().hex[:16]
        job = TaskJob(
            job_id=job_id,
            task_type=task_type,
            input_path=input_path,
            params=params,
            client_submission_id=client_submission_id,
        )
        with self._lock:
            self.jobs[job_id] = job
            if client_submission_id:
                self._client_submission_to_job_id[client_submission_id] = job_id
        return job

    def get_job(self, job_id: str) -> Optional[TaskJob]:
        with self._lock:
            return self.jobs.get(job_id)

    def cancel_client_submission(self, client_submission_id: str) -> Tuple[bool, str]:
        with self._lock:
            job_id = self._client_submission_to_job_id.get(client_submission_id)
            if job_id and job_id in self.jobs:
                job = self.jobs[job_id]
                ok, status = job.cancel()
                return ok, status
            self._cancelled_submissions[client_submission_id] = time.time()
            return True, "cancelled"

    def is_submission_cancelled(self, client_submission_id: Optional[str]) -> bool:
        if not client_submission_id:
            return False
        with self._lock:
            return client_submission_id in self._cancelled_submissions

    def consume_cancelled_submission(self, client_submission_id: Optional[str]) -> bool:
        if not client_submission_id:
            return False
        with self._lock:
            return self._cancelled_submissions.pop(client_submission_id, None) is not None

    def active_or_queued_count(self) -> int:
        with self._lock:
            return sum(1 for j in self.jobs.values() if j.status not in TERMINAL_STATES)

    def prune_expired(self) -> int:
        now = time.time()
        expired: List[TaskJob] = []
        with self._lock:
            for jid, job in list(self.jobs.items()):
                if job.status in TERMINAL_STATES and job.completed_at is not None:
                    if now - job.completed_at > self.retention_seconds:
                        expired.append(self.jobs.pop(jid))
                        if job.client_submission_id:
                            self._client_submission_to_job_id.pop(job.client_submission_id, None)
            for sub_id, ts in list(self._cancelled_submissions.items()):
                if now - ts > self.retention_seconds:
                    self._cancelled_submissions.pop(sub_id, None)
        for job in expired:
            for p_str in (job.input_path, job.output_path, job.raw_intermediate_path):
                if p_str:
                    try:
                        p = Path(p_str)
                        if p.exists():
                            p.unlink()
                    except OSError:
                        pass
        return len(expired)


job_manager = JobManager()
