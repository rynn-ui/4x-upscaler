import asyncio
import json
import os
import sys
import time
from pathlib import Path
from unittest import mock

import cv2
import numpy as np
import pytest
import torch
from fastapi.testclient import TestClient

BACKEND_DIR = Path(__file__).resolve().parent.parent
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import server
from models.base import JobManager, TaskJob
from models.depth import apply_depth_colormap, infer_depth_map, process_depth_job
from models.media_utils import finalize_video_export, make_even_dims
from models.rife import (
    build_rife_call_adapter,
    compute_rife_output_spec,
    generate_intermediate_frames,
    process_rife_job,
)
from models.upscaler import (
    apply_crisp_outline,
    compute_target_dimensions,
    process_upscale_job,
    upscale_frame,
)
from tunnel import build_pairing_deep_link


def _make_synthetic_video(path: Path, frames: int = 4, fps: float = 24.0, w: int = 64, h: int = 48) -> Path:
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(str(path), fourcc, fps, (w, h))
    assert writer.isOpened()
    for i in range(frames):
        val = int(40 + i * (180 / max(1, frames - 1)))
        frame = np.full((h, w, 3), val, dtype=np.uint8)
        cv2.putText(frame, str(i), (8, h - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 100, 50), 1)
        writer.write(frame)
    writer.release()
    return path


@pytest.fixture
def auth_headers():
    server.set_auth_token("test-secret-token-123")
    return {"Authorization": "Bearer test-secret-token-123"}


def test_task_job_state_transitions_and_terminal_guards():
    job = TaskJob("j1", "upscale", "/tmp/in.mp4", {})
    assert job.status == "queued"

    ok, st = job.pause()
    assert ok and st == "paused"

    ok, st = job.resume()
    assert ok and st == "processing"

    job.mark_completed("/tmp/out.mp4")
    assert job.status == "completed"

    # Terminal state invariants
    ok_p, st_p = job.pause()
    assert not ok_p and st_p == "completed"

    ok_r, st_r = job.resume()
    assert not ok_r and st_r == "completed"

    ok_c, st_c = job.cancel()
    assert not ok_c and st_c == "completed"

    job.mark_failed("should be ignored")
    assert job.status == "completed"


def test_cross_thread_asyncio_event_delivery():
    async def _run():
        job = TaskJob("j_async", "upscale", "/tmp/in.mp4", {})
        loop = asyncio.get_running_loop()
        q = job.subscribe(loop)
        import threading

        t = threading.Thread(target=lambda: job.update_progress(42.0, 5, 10, 12.5, 0.4))
        t.start()
        t.join()

        evt = await asyncio.wait_for(q.get(), timeout=2.0)
        assert evt["type"] == "progress"
        assert evt["progress"] == 42.0
        assert evt["current_frame"] == 5
        job.unsubscribe(q)

    asyncio.run(_run())


def test_auth_required_on_rest_and_websocket(auth_headers):
    client = TestClient(server.app)

    # Root info omits secrets and works
    r_root = client.get("/")
    assert r_root.status_code == 200
    assert r_root.json()["auth_required"] is True

    # Missing/bad token rejected
    assert client.get("/api/v1/health").status_code == 401
    assert client.get("/api/v1/health", headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert client.get("/api/v1/capabilities").status_code == 401

    # Valid auth works
    r_ok = client.get("/api/v1/health", headers=auth_headers)
    assert r_ok.status_code == 200
    assert r_ok.json()["status"] == "ok"

    # WebSocket without auth closes
    with pytest.raises(Exception):
        with client.websocket_connect("/api/v1/jobs/nonexistent/stream"):
            pass


def test_submit_validation_allowlists_and_size_limit(auth_headers, tmp_path, monkeypatch):
    monkeypatch.setenv("UPSCALER_WORK_DIR", str(tmp_path))
    client = TestClient(server.app)

    # Unknown task_type -> 400
    r = client.post(
        "/api/v1/jobs/submit",
        headers=auth_headers,
        data={"task_type": "../../etc/passwd"},
        files={"file": ("clip.mp4", b"1234", "video/mp4")},
    )
    assert r.status_code == 400

    # Malformed params_json -> 400
    r = client.post(
        "/api/v1/jobs/submit",
        headers=auth_headers,
        data={"task_type": "upscale", "params_json": "{not-json"},
        files={"file": ("clip.mp4", b"1234", "video/mp4")},
    )
    assert r.status_code == 400

    # Disallowed model -> 422
    r = client.post(
        "/api/v1/jobs/submit",
        headers=auth_headers,
        data={"task_type": "upscale", "params_json": json.dumps({"model": "../evil.pth"})},
        files={"file": ("clip.mp4", b"1234", "video/mp4")},
    )
    assert r.status_code == 422

    # Invalid RIFE multiplier -> 422
    r = client.post(
        "/api/v1/jobs/submit",
        headers=auth_headers,
        data={"task_type": "rife", "params_json": json.dumps({"multiplier": 16})},
        files={"file": ("clip.mp4", b"1234", "video/mp4")},
    )
    assert r.status_code == 422

    # Empty upload -> 400
    r = client.post(
        "/api/v1/jobs/submit",
        headers=auth_headers,
        data={"task_type": "upscale"},
        files={"file": ("clip.mp4", b"", "video/mp4")},
    )
    assert r.status_code == 400

    # Size limit -> 413 and cleans temp file
    monkeypatch.setenv("UPSCALER_MAX_UPLOAD_MB", "1")
    oversized = b"x" * (1024 * 1024 + 512)
    r = client.post(
        "/api/v1/jobs/submit",
        headers=auth_headers,
        data={"task_type": "upscale"},
        files={"file": ("big.mp4", oversized, "video/mp4")},
    )
    assert r.status_code == 413
    upload_dir = tmp_path / "upscaler_uploads"
    assert list(upload_dir.glob("*")) == []


def test_precompleted_job_ws_delivers_terminal_snapshot_and_rejects_pause(auth_headers, tmp_path):
    client = TestClient(server.app)
    job = server.job_manager.create_job("upscale", str(tmp_path / "in.mp4"), {})
    out_file = tmp_path / "out.mp4"
    out_file.write_bytes(b"\x00" * 2048)
    job.mark_completed(str(out_file))

    # Status exposes download_url
    st = client.get(f"/api/v1/jobs/{job.job_id}/status", headers=auth_headers)
    assert st.status_code == 200
    assert st.json()["download_url"] == f"/api/v1/jobs/{job.job_id}/download"

    # Pause/Cancel on completed job return 409 Conflict
    assert client.post(f"/api/v1/jobs/{job.job_id}/pause", headers=auth_headers).status_code == 409
    assert client.post(f"/api/v1/jobs/{job.job_id}/cancel", headers=auth_headers).status_code == 409

    # WS connecting AFTER completion still receives complete immediately
    with client.websocket_connect(f"/api/v1/jobs/{job.job_id}/stream", headers=auth_headers) as ws:
        msg = ws.receive_json()
        assert msg["type"] == "complete"
        assert msg["progress"] == 100.0
        assert msg["download_url"] == f"/api/v1/jobs/{job.job_id}/download"


def test_upscaler_aspect_ratio_and_orientation_policy():
    # Landscape 16:9 -> 4K = 3840x2160
    assert compute_target_dimensions(1280, 720, "4K (2160p)", "Real-ESRGAN Anime") == (3840, 2160)
    # Portrait 9:16 -> 4K = 2160x3840 (never stretched!)
    assert compute_target_dimensions(720, 1280, "4K (2160p)", "Real-ESRGAN Anime") == (2160, 3840)
    # Portrait 9:16 -> 1080p = 1080x1920
    assert compute_target_dimensions(540, 960, "1080p (FHD)", "2x-sudo-UltraCompact") == (1080, 1920)
    # Square 1:1 -> 1080p = 1080x1080
    assert compute_target_dimensions(512, 512, "1080p (FHD)", "Real-ESRGAN General") == (1080, 1080)
    # 4:3 -> 1080p = 1440x1080
    assert compute_target_dimensions(640, 480, "1080p (FHD)", "Real-ESRGAN General") == (1440, 1080)
    # 2x always doubles the source: 1080p -> 4K, on every model
    assert compute_target_dimensions(1920, 1080, "2×", "2x-sudo-UltraCompact") == (3840, 2160)
    assert compute_target_dimensions(1920, 1080, "2×", "Real-ESRGAN Anime") == (3840, 2160)
    assert compute_target_dimensions(1080, 1920, "2x", "Real-ESRGAN General") == (2160, 3840)
    # Native 2x with odd input dimensions stays even
    assert compute_target_dimensions(333, 221, "Auto (Native)", "2x-sudo-UltraCompact") == (666, 442)
    assert compute_target_dimensions(333, 221, "Auto (Native)", "Real-ESRGAN Anime") == (666, 442)
    assert compute_target_dimensions(333, 221, "Auto (Native)", "Real-ESRGAN General") == (666, 442)
    assert make_even_dims(333, 221) == (334, 222)


def test_upscale_frame_runs_multiple_2x_passes_when_needed():
    frame = np.full((90, 160, 3), 100, dtype=np.uint8)
    passes = []

    def fake_infer_tiled_frame(img, infer_fn, scale, tile_size, tile_pad):
        h, w = img.shape[:2]
        passes.append((w, h))
        return cv2.resize(img, (w * scale, h * scale), interpolation=cv2.INTER_NEAREST)

    with mock.patch("models.upscaler.infer_tiled_frame", new=fake_infer_tiled_frame):
        # 160x90 -> 320x180 is one 2x step
        one = upscale_frame(object(), 2, frame, 320, 180)
        assert one.shape[:2] == (180, 320)
        assert passes == [(160, 90)]

        passes.clear()
        # 160x90 -> 640x360 needs 4x, i.e. two 2x steps
        four = upscale_frame(object(), 2, frame, 640, 360)
        assert four.shape[:2] == (360, 640)
        assert passes == [(160, 90), (320, 180)]

        passes.clear()
        # 160x90 -> 480x270 (3x) also needs two 2x steps, then a downscale to target
        three = upscale_frame(object(), 2, frame, 480, 270)
        assert three.shape[:2] == (270, 480)
        assert passes == [(160, 90), (320, 180)]

        passes.clear()
        # No upscale needed (target <= source): no model pass at all
        same = upscale_frame(object(), 2, frame, 160, 90)
        assert same.shape[:2] == (90, 160)
        assert passes == []


def test_crisp_outline_deepens_edges_without_changing_shape():
    frame = np.zeros((32, 32, 3), dtype=np.uint8)
    frame[:, 16:] = 220
    out = apply_crisp_outline(frame)
    assert out.shape == frame.shape
    assert out.dtype == np.uint8

    before_edge = np.abs(frame[:, 16].astype(np.int16) - frame[:, 15].astype(np.int16)).mean()
    after_edge = np.abs(out[:, 16].astype(np.int16) - out[:, 15].astype(np.int16)).mean()
    assert after_edge > before_edge


def test_crisp_outline_toggle_runs_before_the_model_pass():
    frame = np.full((16, 16, 3), 128, dtype=np.uint8)
    enhancer_calls = []

    def fake_enhance(img, strength=0.85):
        enhancer_calls.append(img.copy())
        return img

    def fake_infer_tiled_frame(img, infer_fn, scale, tile_size, tile_pad):
        h, w = img.shape[:2]
        return cv2.resize(img, (w * scale, h * scale), interpolation=cv2.INTER_NEAREST)

    with mock.patch("models.upscaler.apply_crisp_outline", side_effect=fake_enhance), \
            mock.patch("models.upscaler.infer_tiled_frame", new=fake_infer_tiled_frame):
        upscale_frame(object(), 2, frame, 32, 32, crisp_outline=True)
        assert len(enhancer_calls) == 1
        upscale_frame(object(), 2, frame, 32, 32, crisp_outline=False)
        assert len(enhancer_calls) == 1


def test_upscaler_fails_cleanly_if_model_load_fails(tmp_path):
    vid = _make_synthetic_video(tmp_path / "clip.mp4", frames=2)
    job = TaskJob("j_up_fail", "upscale", str(vid), {})
    with mock.patch("models.upscaler.get_upscaler_model", side_effect=RuntimeError("Download blocked")):
        process_upscale_job(job, {"model": "2x-sudo-UltraCompact", "target_quality": "Auto (Native)"})
    assert job.status == "failed"
    assert "Download blocked" in (job.error_message or "")


def test_rife_2x_4x_8x_frame_count_and_ordering(tmp_path):
    # Linear interpolation mock adapter so each step returns exact midpoint (a + b) / 2
    def linear_adapter(t0: torch.Tensor, t1: torch.Tensor, _ts: float) -> torch.Tensor:
        return 0.5 * (t0 + t1)

    f0 = np.zeros((32, 32, 3), dtype=np.uint8)
    f1 = np.full((32, 32, 3), 255, dtype=np.uint8)

    mids_2x = generate_intermediate_frames(linear_adapter, f0, f1, 2)
    mids_4x = generate_intermediate_frames(linear_adapter, f0, f1, 4)
    mids_8x = generate_intermediate_frames(linear_adapter, f0, f1, 8)

    assert len(mids_2x) == 1
    assert len(mids_4x) == 3
    assert len(mids_8x) == 7

    vals_8x = [int(frame[0, 0, 0]) for frame in mids_8x]
    assert vals_8x == sorted(vals_8x)
    assert all(0 < v < 255 for v in vals_8x)
    assert len(set(vals_8x)) == 7

    # Mode separation
    fps_interp, audio_interp = compute_rife_output_spec(24.0, 8, "interpolate")
    assert fps_interp == pytest.approx(192.0) and audio_interp is True

    fps_slow, audio_slow = compute_rife_output_spec(24.0, 8, "slow_motion")
    assert fps_slow == pytest.approx(24.0) and audio_slow is False

    # End-to-end 8x RIFE job on 3-frame synthetic clip -> (3 - 1) * 8 + 1 = 17 output frames
    clip = _make_synthetic_video(tmp_path / "src.mp4", frames=3, fps=24.0, w=64, h=48)
    job = TaskJob("j_rife_8x", "rife", str(clip), {})
    with mock.patch("models.rife.get_rife_model", return_value=linear_adapter):
        process_rife_job(job, {"multiplier": 8, "model_key": "rife_v4.6", "mode": "slow_motion"})

    assert job.status == "completed", job.error_message
    assert job.output_path is not None and Path(job.output_path).exists()
    out_cap = cv2.VideoCapture(job.output_path)
    out_count = int(out_cap.get(cv2.CAP_PROP_FRAME_COUNT))
    out_fps = float(out_cap.get(cv2.CAP_PROP_FPS))
    out_cap.release()
    assert out_count == 17
    assert out_fps == pytest.approx(24.0, abs=1.0)


def test_rife_signature_validation():
    class BadModel(torch.nn.Module):
        def forward(self, x):
            return {"invalid": True}

    adapter = build_rife_call_adapter(BadModel())
    with pytest.raises(RuntimeError, match="Unexpected RIFE model output"):
        adapter(torch.zeros(1, 3, 64, 64), torch.zeros(1, 3, 64, 64), 0.5)


def test_depth_fails_loudly_without_silent_fallback(tmp_path):
    frame = np.zeros((32, 32, 3), dtype=np.uint8)
    with pytest.raises(RuntimeError, match="not loaded"):
        infer_depth_map(None, frame, "inferno")

    # Grayscale vs colormap honors palette
    depth_u8 = np.tile(np.arange(0, 256, 8, dtype=np.uint8), (32, 1))
    gray = apply_depth_colormap(depth_u8, "grayscale")
    assert np.array_equal(gray[:, :, 0], gray[:, :, 1])
    inferno = apply_depth_colormap(depth_u8, "inferno")
    assert not np.array_equal(inferno[:, :, 0], inferno[:, :, 2])

    # Job marks failed on missing model
    clip = _make_synthetic_video(tmp_path / "depth_src.mp4", frames=2)
    job = TaskJob("j_depth_fail", "depth", str(clip), {})
    with mock.patch("models.depth.get_depth_model", side_effect=RuntimeError("transformers missing")):
        process_depth_job(job, {"model": "depth_anything_v2_small", "colormap": "inferno"})
    assert job.status == "failed"
    assert "transformers" in (job.error_message or "")


def test_export_failure_preserves_intermediate_file(tmp_path):
    raw_vid = _make_synthetic_video(tmp_path / "raw_keep.mp4", frames=2)
    final_vid = tmp_path / "final_fail.mp4"
    with mock.patch("models.media_utils.subprocess.run") as run_mock:
        run_mock.return_value = mock.Mock(returncode=1, stderr="simulated mux failure")
        with pytest.raises(RuntimeError, match="FFmpeg muxing failed"):
            finalize_video_export(raw_vid, str(raw_vid), final_vid, preserve_audio=True)

    assert raw_vid.exists(), "Intermediate raw file must be preserved on export failure"


def test_pairing_deep_link_encoding():
    link = build_pairing_deep_link("https://abc-123.trycloudflare.com", "tok+1/2=")
    assert link.startswith("upscaler://connect?")
    assert "https%3A%2F%2Fabc-123.trycloudflare.com" in link
    assert "tok%2B1%2F2%3D" in link


def test_early_auth_rejection_without_body_consumption(auth_headers):
    client = TestClient(server.app)
    initial_job_count = server.job_manager.active_or_queued_count()
    assert server._admission_controller.count() == 0

    # 1. Missing auth
    r_unauth = client.post(
        "/api/v1/jobs/submit",
        data={"task_type": "upscale"},
        files={"file": ("test.mp4", b"dummy video bytes", "video/mp4")},
    )
    assert r_unauth.status_code == 401
    assert server._admission_controller.count() == 0
    assert server.job_manager.active_or_queued_count() == initial_job_count

    # 2. Invalid auth token
    r_bad = client.post(
        "/api/v1/jobs/submit",
        headers={"Authorization": "Bearer wrong-token-value"},
        data={"task_type": "upscale"},
        files={"file": ("test.mp4", b"dummy video bytes", "video/mp4")},
    )
    assert r_bad.status_code == 401
    assert server._admission_controller.count() == 0
    assert server.job_manager.active_or_queued_count() == initial_job_count


def test_oversized_declared_content_length_rejected_early(auth_headers):
    client = TestClient(server.app)
    max_allowed = server._max_upload_bytes() + server.MULTIPART_OVERHEAD_BYTES

    # Declared Content-Length way larger than maximum allowed size
    headers = dict(auth_headers)
    headers["Content-Length"] = str(max_allowed + 1024 * 1024)

    r = client.post(
        "/api/v1/jobs/submit",
        headers=headers,
        data={"task_type": "upscale"},
        files={"file": ("huge.mp4", b"some content", "video/mp4")},
    )
    assert r.status_code == 413
    assert server._admission_controller.count() == 0


def test_streamed_oversized_body_rejected_and_admission_released(auth_headers, tmp_path, monkeypatch):
    # Set a tiny 1MB upload limit for test
    monkeypatch.setenv("UPSCALER_MAX_UPLOAD_MB", "1")
    client = TestClient(server.app)

    # 2MB payload exceeds 1MB limit
    oversized_data = b"0" * (2 * 1024 * 1024)
    r = client.post(
        "/api/v1/jobs/submit",
        headers=auth_headers,
        data={"task_type": "upscale"},
        files={"file": ("large.mp4", oversized_data, "video/mp4")},
    )
    assert r.status_code == 413
    # Admission slot must be released
    assert server._admission_controller.count() == 0


def test_concurrent_upload_admission_bounding_and_queue_saturation(auth_headers):
    client = TestClient(server.app)
    assert server._admission_controller.count() == 0

    # Manually saturate admission controller
    max_uploads = server._admission_controller.max_concurrent
    for _ in range(max_uploads):
        assert server._admission_controller.acquire() is True

    # Saturated -> next submit rejected with 429
    r_busy = client.post(
        "/api/v1/jobs/submit",
        headers=auth_headers,
        data={"task_type": "upscale"},
        files={"file": ("test.mp4", b"dummy content", "video/mp4")},
    )
    assert r_busy.status_code == 429
    assert "upload admission or GPU queue capacity" in r_busy.json()["detail"]

    # Release slots
    for _ in range(max_uploads):
        server._admission_controller.release()
    assert server._admission_controller.count() == 0


def test_cancel_client_submission_before_and_during_upload(auth_headers, tmp_path):
    client = TestClient(server.app)
    client_sub_id = "test-cancel-sub-12345"

    # Client cancels submission before/during upload
    r_cancel = client.post(
        f"/api/v1/jobs/cancel-client-submission?client_submission_id={client_sub_id}",
        headers=auth_headers,
    )
    assert r_cancel.status_code == 200
    assert r_cancel.json()["status"] == "cancelled"

    clip = _make_synthetic_video(tmp_path / "cancel_clip.mp4", frames=2)
    with open(clip, "rb") as f:
        r_sub = client.post(
            "/api/v1/jobs/submit",
            headers=auth_headers,
            data={
                "task_type": "upscale",
                "client_submission_id": client_sub_id,
            },
            files={"file": ("cancel_clip.mp4", f, "video/mp4")},
        )
    assert r_sub.status_code == 200
    res = r_sub.json()
    assert res["status"] == "cancelled"
    assert res["job_id"] is None
    # No job queued to GPU
    assert server._gpu_queue.empty()


def test_queued_pause_preservation():
    job = TaskJob("j_pause_test", "upscale", "/tmp/dummy.mp4", {})
    assert job.status == "queued"

    # User pauses while job is waiting in queue
    ok, st = job.pause()
    assert ok and st == "paused"
    assert job.is_paused is True

    # Worker starts processing: MUST preserve paused state!
    started = job.start_processing()
    assert started is True
    assert job.status == "paused"
    assert job.is_paused is True

    # Resume transitions to processing
    ok_res, st_res = job.resume()
    assert ok_res and st_res == "processing"
    assert job.is_paused is False


def test_cannot_resume_cancelled_and_cannot_cancel_completed():
    # 1. Cancelled job cannot be resumed
    job1 = TaskJob("j_canc_test", "upscale", "/tmp/dummy.mp4", {})
    ok, st = job1.cancel()
    assert ok and st == "cancelled"

    ok_res, st_res = job1.resume()
    assert not ok_res and st_res == "cancelled"

    # 2. Completed job cannot be cancelled
    job2 = TaskJob("j_comp_test", "upscale", "/tmp/dummy.mp4", {})
    job2.mark_completed("/tmp/dummy_out.mp4")
    assert job2.status == "completed"

    ok_canc, st_canc = job2.cancel()
    assert not ok_canc and st_canc == "completed"


def test_cancel_during_export_terminates_and_unlinks(tmp_path):
    raw_vid = _make_synthetic_video(tmp_path / "raw_export.mp4", frames=2)
    final_vid = tmp_path / "final_export.mp4"

    job = TaskJob("j_export_canc", "upscale", str(raw_vid), {})
    job.is_cancelled = True

    with pytest.raises(RuntimeError, match="Operation cancelled by user"):
        finalize_video_export(raw_vid, str(raw_vid), final_vid, preserve_audio=False, job=job)

    assert not final_vid.exists(), "Partial exported output must be unlinked on cancellation"


def test_health_telemetry_includes_backend_instance_and_upload_count(auth_headers):
    client = TestClient(server.app)
    r = client.get("/api/v1/health", headers=auth_headers)
    assert r.status_code == 200
    data = r.json()
    assert "backend_instance_id" in data
    assert len(data["backend_instance_id"]) > 0
    assert "active_uploads" in data
    assert data["active_uploads"] == 0


def test_emit_progress_preview_throttling():
    from models.media_utils import emit_progress
    job = TaskJob("j_thumb", "upscale", "/tmp/dummy.mp4", {})
    synth_frame = np.zeros((64, 64, 3), dtype=np.uint8)

    t0 = time.time()
    # Frame 1: must generate preview
    last_t = emit_progress(job, 1, 10, t0, synth_frame, last_preview_time=0.0, preview_interval_sec=1.0)
    assert job.preview_b64 is not None
    orig_b64 = job.preview_b64

    # Frame 2 immediately after: should NOT regenerate preview (returns None in event, keeps existing)
    job.preview_b64 = "sentinel"
    emit_progress(job, 2, 10, t0, synth_frame, last_preview_time=last_t, preview_interval_sec=1.0)
    assert job.preview_b64 == "sentinel"
    assert job.current_frame == 2


def test_finalize_video_export_creates_valid_video(tmp_path):
    raw_vid = _make_synthetic_video(tmp_path / "raw_valid.mp4", frames=3)
    final_vid = tmp_path / "final_valid.mp4"

    res = finalize_video_export(raw_vid, str(raw_vid), final_vid, preserve_audio=False)
    assert res.exists()
    assert res.stat().st_size > 500


def test_start_best_tunnel_routing():
    from tunnel import start_best_tunnel

    with mock.patch("tunnel.start_ngrok_tunnel", return_value="https://test.ngrok-free.app") as mock_ngrok:
        with mock.patch("tunnel.start_cloudflared_tunnel", return_value="https://test.trycloudflare.com") as mock_cf:
            # 1. With ngrok token: routes to ngrok
            url1 = start_best_tunnel(8000, ngrok_token="dummy_token_123")
            assert url1 == "https://test.ngrok-free.app"
            mock_ngrok.assert_called_once_with(8000, authtoken="dummy_token_123")
            mock_cf.assert_not_called()

            # 2. Without ngrok token: routes to cloudflare
            mock_ngrok.reset_mock()
            mock_cf.reset_mock()
            url2 = start_best_tunnel(8000, ngrok_token="")
            assert url2 == "https://test.trycloudflare.com"
            mock_cf.assert_called_once_with(8000)


