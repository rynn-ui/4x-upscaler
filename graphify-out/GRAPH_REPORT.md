# Graph Report - 4x upscaler  (2026-11-16)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 876 nodes · 1911 edges · 51 communities (31 shown, 20 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 48 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `dcc6dd6d`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- UpscalerRepository
- UpscalerApiService
- JobPhase
- GoogleAuthManager.kt
- TaskJob
- RemoteConfigManager.kt
- HomeScreen.kt
- StudioComponents.kt
- JobNotificationService.kt
- rife.py
- upscaler.py
- test_backend.py
- SettingsScreen.kt
- MediaImportPreviewCard.kt
- media_utils.py
- BeforeAfterComparisonPlayer.kt
- tunnel.py
- StudioScaffold
- .computeTargetDimensions
- server.py
- _resolve_auth_user
- numpy
- .onCreate
- HomeScreen
- Screen
- finalize_video_export
- get
- .__call__
- submit_job
- interface
- interface
- interface
- MainActivity
- UploadAdmissionController
- Theme.kt
- keep_alive.py
- post
- interface
- interface
- hitl-loop.template.sh
- interface
- auth_headers

## God Nodes (most connected - your core abstractions)
1. `UpscalerRepository` - 61 edges
2. `TaskJob` - 30 edges
3. `GoogleAuthManager` - 27 edges
4. `ManagedJob` - 20 edges
5. `JobPhase` - 19 edges
6. `finalize_video_export()` - 17 edges
7. `UpscalerApiService` - 15 edges
8. `process_depth_job()` - 14 edges
9. `open_video_writer()` - 14 edges
10. `HomeScreen()` - 14 edges

## Surprising Connections (you probably didn't know these)
- `LiveProgressCard()` --references--> `ManagedJob`  [EXTRACTED]
  android/app/src/main/java/com/upscaler/studio/ui/components/LiveProgressCard.kt → android/app/src/main/java/com/upscaler/studio/data/model/Models.kt
- `QueueTabContent()` --references--> `ManagedJob`  [EXTRACTED]
  android/app/src/main/java/com/upscaler/studio/ui/home/HomeScreen.kt → android/app/src/main/java/com/upscaler/studio/data/model/Models.kt
- `FilesTabContent()` --references--> `SavedMediaItem`  [EXTRACTED]
  android/app/src/main/java/com/upscaler/studio/ui/home/HomeScreen.kt → android/app/src/main/java/com/upscaler/studio/data/model/Models.kt
- `BackendConnection` --references--> `UpscalerApiService`  [EXTRACTED]
  android/app/src/main/java/com/upscaler/studio/data/repository/UpscalerRepository.kt → android/app/src/main/java/com/upscaler/studio/data/api/UpscalerApiService.kt
- `ConnectionHeroCard()` --references--> `ConnectionState`  [EXTRACTED]
  android/app/src/main/java/com/upscaler/studio/ui/home/HomeScreen.kt → android/app/src/main/java/com/upscaler/studio/data/repository/UpscalerRepository.kt

## Import Cycles
- None detected.

## Communities (51 total, 20 thin omitted)

### Community 0 - "UpscalerRepository"
Cohesion: 0.06
Nodes (11): JobWebSocketClient, ManagedJob, SavedMediaItem, BackendConnection, ConnectionState, Connected, Connecting, Disconnected (+3 more)

### Community 1 - "UpscalerApiService"
Cohesion: 0.09
Nodes (12): UpscalerApiService, WebSocketListener, StreamPacket, Disconnected, Event, CapabilitiesResponse, GpuInfo, HealthResponse (+4 more)

### Community 2 - "JobPhase"
Cohesion: 0.07
Nodes (16): JobPhase, CANCELLED, DOWNLOADING, FAILED, IDLE, PAUSED, PREPARING, PROCESSING (+8 more)

### Community 3 - "GoogleAuthManager.kt"
Cohesion: 0.08
Nodes (3): GoogleAuthManager, googleSignInLauncher(), initGoogleAuth()

### Community 4 - "TaskJob"
Cohesion: 0.10
Nodes (8): _enqueue_safe(), JobManager, TaskJob, test_cannot_resume_cancelled_and_cannot_cancel_completed(), test_cross_thread_asyncio_event_delivery(), _run(), test_queued_pause_preservation(), test_task_job_state_transitions_and_terminal_guards()

### Community 5 - "RemoteConfigManager.kt"
Cohesion: 0.08
Nodes (11): AppUpdateStatus, ForceUpdate, Maintenance, OptionalUpdate, UpToDate, RemoteConfigManager, SettingsManager, UiButtonSize (+3 more)

### Community 9 - "rife.py"
Cohesion: 0.11
Nodes (14): build_rife_call_adapter(), _concat_arg(), _two_arg(), compute_rife_output_spec(), download_and_extract_rife(), _extract_frame_tensor(), generate_intermediate_frames(), _recurse() (+6 more)

### Community 10 - "upscaler.py"
Cohesion: 0.16
Nodes (15): process_denoise_job(), get_depth_model(), process_depth_job(), emit_progress(), encode_preview_b64(), is_image_file(), make_even_dims(), open_video_capture() (+7 more)

### Community 11 - "test_backend.py"
Cohesion: 0.13
Nodes (11): apply_depth_colormap(), infer_depth_map(), _make_synthetic_video(), test_auth_required_on_rest_and_websocket(), test_cancel_client_submission_before_and_during_upload(), test_cancel_during_export_terminates_and_unlinks(), test_depth_fails_loudly_without_silent_fallback(), test_export_failure_preserves_intermediate_file() (+3 more)

### Community 14 - "MediaImportPreviewCard.kt"
Cohesion: 0.13
Nodes (5): extractVideoMeta(), formatTimelineTime(), MediaImportPreviewCard(), MetadataPill(), VideoMeta

### Community 15 - "media_utils.py"
Cohesion: 0.14
Nodes (7): denoise_frame(), _denoise_tile(), get_denoise_model(), SRVGGNetCompact, atomic_download_file(), _get_download_lock(), infer_tiled_frame()

### Community 17 - "tunnel.py"
Cohesion: 0.16
Nodes (9): test_pairing_deep_link_encoding(), build_pairing_deep_link(), display_connection_card(), _drain(), find_free_port(), is_port_in_use(), _read_url_proc(), _render_qr_base64() (+1 more)

### Community 21 - "StudioScaffold"
Cohesion: 0.20
Nodes (9): DenoiserStudioScreen(), DepthStudioScreen(), RifeStudioScreen(), ExpectedOutputBanner(), StudioChoiceChip(), StudioOptionCard(), StudioOptionRow(), StudioScaffold() (+1 more)

### Community 23 - "server.py"
Cohesion: 0.18
Nodes (3): ensure_worker_started(), _gpu_worker_loop(), lifespan()

### Community 24 - "_resolve_auth_user"
Cohesion: 0.18
Nodes (7): auth_me(), _extract_bearer(), get_current_user(), require_auth(), _resolve_auth_user(), stream_progress(), verify_google_id_token()

### Community 25 - "numpy"
Cohesion: 0.22
Nodes (7): apply_crisp_outline(), upscale_frame(), _upscale_tile(), test_crisp_outline_deepens_edges_without_changing_shape(), test_crisp_outline_toggle_runs_before_the_model_pass(), test_upscale_frame_runs_multiple_2x_passes_when_needed(), fake_infer_tiled_frame()

### Community 26 - ".onCreate"
Cohesion: 0.17
Nodes (3): AuthScreen(), QrScannerScreen(), SettingsScreen()

### Community 27 - "HomeScreen"
Cohesion: 0.30
Nodes (9): BottomNavTab(), ConnectionHeroCard(), FilesTabContent(), HomeScreen(), HomeTabContent(), QueueTabContent(), routeForTask(), StudioItem (+1 more)

### Community 28 - "Screen"
Cohesion: 0.20
Nodes (9): Screen, Auth, DenoiserStudio, DepthStudio, Home, QrScanner, RifeStudio, Settings (+1 more)

### Community 29 - "finalize_video_export"
Cohesion: 0.28
Nodes (4): finalize_video_export(), _execute(), _run_monitored_ffmpeg(), verify_video_output()

### Community 30 - "get"
Cohesion: 0.29
Nodes (6): download_result(), get_capabilities(), get_gpu_telemetry(), get_job_status(), health_check(), root_info()

### Community 31 - ".__call__"
Cohesion: 0.29
Nodes (3): get_auth_token(), UploadAdmissionMiddleware, _verify_token_value()

### Community 32 - "submit_job"
Cohesion: 0.29
Nodes (4): _max_upload_bytes(), submit_job(), validate_task_params(), test_oversized_declared_content_length_rejected_early()

### Community 33 - "interface"
Cohesion: 0.33
Nodes (5): interface, display_name, short_description, policy, allow_implicit_invocation

### Community 34 - "interface"
Cohesion: 0.33
Nodes (5): interface, display_name, short_description, policy, allow_implicit_invocation

### Community 35 - "interface"
Cohesion: 0.33
Nodes (5): interface, display_name, short_description, policy, allow_implicit_invocation

### Community 41 - "post"
Cohesion: 0.40
Nodes (4): cancel_client_submission(), cancel_job(), pause_job(), resume_job()

### Community 42 - "interface"
Cohesion: 0.50
Nodes (3): interface, display_name, short_description

### Community 43 - "interface"
Cohesion: 0.50
Nodes (3): interface, display_name, short_description

### Community 44 - "hitl-loop.template.sh"
Cohesion: 0.83
Nodes (3): capture(), hitl-loop.template.sh script, step()

### Community 45 - "interface"
Cohesion: 0.50
Nodes (3): interface, display_name, short_description

## Knowledge Gaps
- **23 isolated node(s):** `Auth`, `DenoiserStudio`, `DepthStudio`, `Home`, `QrScanner` (+18 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 309 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **20 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `UpscalerRepository` connect `UpscalerRepository` to `UpscalerApiService`, `HomeScreen.kt`, `StudioComponents.kt`, `JobNotificationService.kt`, `MainActivity.kt`, `BeforeAfterComparisonPlayer.kt`, `QrScannerScreen.kt`, `Dp`?**
  _High betweenness centrality (0.139) - this node is a cross-community bridge._
- **What connects `Auth`, `DenoiserStudio`, `DepthStudio` to the rest of the system?**
  _23 weakly-connected nodes found - possible missing edges._
- **Should `UpscalerRepository` be split into smaller, more focused modules?**
  _Cohesion score 0.0645045045045045 - nodes in this community are weakly interconnected._
- **Why does `ManagedJob` connect `UpscalerRepository` to `UpscalerApiService`, `JobPhase`, `HomeScreen.kt`, `StudioComponents.kt`, `JobNotificationService.kt`, `HomeScreen`?**
  _High betweenness centrality (0.061) - this node is a cross-community bridge._
- **Should `UpscalerApiService` be split into smaller, more focused modules?**
  _Cohesion score 0.08787878787878788 - nodes in this community are weakly interconnected._
- **Why does `GoogleAuthManager` connect `GoogleAuthManager.kt` to `AuthScreen.kt`, `MainActivity.kt`, `HomeScreen.kt`?**
  _High betweenness centrality (0.050) - this node is a cross-community bridge._
- **Should `JobPhase` be split into smaller, more focused modules?**
  _Cohesion score 0.06976744186046512 - nodes in this community are weakly interconnected._