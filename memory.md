# 🧠 Memory & Architecture Guide — 4X Upscaler Colab Suite

> **File:** `memory.md`  
> **Repository:** [rynn-ui/upscaler-studio](https://github.com/rynn-ui/upscaler-studio)  
> **Author:** Ryn  
> **Purpose:** Persistent technical documentation, architecture reference, and development memory for maintaining and updating the Google Colab notebook (`4x_upscaler.ipynb`).

---

## 📌 Project Overview

**4X Upscaler Colab Suite** is an all-in-one, browser-based AI media processing toolkit running on **free Google Colab GPU runtimes (Tesla T4 / A100)**. It wraps state-of-the-art computer vision models into dark-themed, private **Gradio** web applications accessible either locally inside the Colab session or externally via secure reverse tunnels.

### 🌐 System Architecture & Networking

Every studio cell in the notebook adheres to a unified 3-stage lifecycle:
1. **Stage 1 (Dependencies):** Fast batched check and installation of missing pip packages (`gradio`, `requests`, `Pillow`, `tqdm`, `spandrel`, `static-ffmpeg`). Background fetch of `cloudflared` binary into `/tmp/cloudflared`.
2. **Stage 2 (Engine & Model Preload):** Model weights are pre-downloaded, loaded into PyTorch GPU memory (FP16 enabled), and warmed up with a dummy forward pass to eliminate click delays.
3. **Stage 3 (Launch & Multi-Tunneling):**
   - **Local Server:** Dynamic free port discovery starting at `7860` (`_port_open(port)`).
   - **Primary Tunnel:** Gradio built-in `share=True` link.
   - **Mirror Tunnel 1:** Cloudflare Tunnel (`/tmp/cloudflared tunnel --no-autoupdate --url http://localhost:{port}`).
   - **Mirror Tunnel 2:** `localhost.run` SSH tunnel fallback (`nokey@localhost.run`).
   - **Colab UI Card:** Sleek, centered HTML launch card with an `OPEN STUDIO IN NEW TAB ➔` primary button and mirror link.
   - **Keep-Alive:** Non-blocking daemon keep-alive loop (`while True: time.sleep(30)`).

---

## 🎨 Global UI & Styling Conventions

All studio cells share a unified dark-mode CSS theme:
- **Base Background:** `#09090b` (Zinc 950)
- **Card Background:** `#18181b` (Zinc 900) with `#27272a` (Zinc 800) borders
- **Typography:** `Plus Jakarta Sans`, sans-serif
- **Hero Banner:** Linear gradient header with model badge and live GPU specs (`GPU_NAME (VRAM_GB)`).
- **Process Controls (`#process-controls`):**
  - `btn_pause` (`#eab308` amber border): Real-time Pause/Resume toggle (`CONTROL['paused']`).
  - `btn_cancel` (`#ef4444` red border): Real-time Job Cancellation (`CONTROL['cancelled']`).
- **Prominent Download Button (`.btn-dl-prominent`):** High-contrast emerald green `#10b981` min-height 48px button for single-click clean output download.
- **Comparison Player (`#comp-video`):** Suppresses preview video download icons to prevent downloading low-bitrate comparison streams instead of the full pristine render.

---

## 🧩 Cell-by-Cell Technical Breakdown

```
4x_upscaler.ipynb
├── Cell 0: Markdown Intro & Quick Start Guide
├── Cell 1: 4X Upscaler Studio (Real-ESRGAN + CC Suite)
├── Cell 2: Video Interpolation Studio (RIFE v4.x)
├── Cell 3: Depth Map Studio (Depth Anything V2 + Spectrogram)
└── Cell 4: Denoiser Studio (1x-Archiver-AntiLines)

backend/
├── backend_colab.ipynb       # 1-Click Headless Colab Notebook (single cell run)
├── server.py                 # FastAPI server (REST + WebSocket progress endpoints)
├── tunnel.py                 # Cloudflare tunnel manager & QR code generator
├── keep_alive.py             # Anti-disconnect keepalive daemon (45s ping)
├── requirements.txt
└── models/                   # Neural network loader & pipelines
    ├── base.py               # TaskJob & JobManager with Pause/Cancel & WebSocket dispatch
    ├── upscaler.py           # Real-ESRGAN & compact Anime models
    ├── rife.py               # RIFE v4.x optical flow frame interpolation
    ├── denoiser.py           # 1x-Archiver anti-line denoiser
    └── depth.py              # Depth Anything V2

android/
├── app/
│   ├── build.gradle.kts      # Android config (applicationId="com.ryn.studio", version="1.0.0", Compose, Media3, Retrofit)
│   ├── google-services.json  # Google OAuth Client Config (package_name="com.ryn.studio")
│   └── src/main/
│       ├── AndroidManifest.xml # Deep link filters (ryn://connect, upscaler://connect)
│       └── java/com/upscaler/studio/
│           ├── MainActivity.kt
│           ├── UpscalerApp.kt
│           ├── theme/        # Dark navy/purple/emerald theme with pure white button typography
│           ├── data/         # Retrofit API, WebSocket client, Repositories, GoogleAuthManager
│           ├── ui/
│           │   ├── home/     # Studio Hub Dashboard, Colab connection hero card with pop-out spring animations & haptics
│           │   ├── studios/  # Upscaler (Real-ESRGAN), RIFE (v4.x), Denoiser (1x-Archiver), Depth Map (Depth Anything V2)
│           │   ├── player/   # BeforeAfterComparisonPlayer (Dual FrameLayout, non-squishing hardware clip, drift sync)
│           │   └── scanner/  # CameraX + ML Kit QR Code Scanner
│           └── utils/        # MediaStore Scoped Storage auto-saver
```

---

### ⚡ Cell 0: Markdown Header & Quickstart
- **Type:** Markdown
- **Contents:**
  - Embedded Base64 Before/After preview image.
  - Colab GPU requirement notice (**Runtime ➔ Change runtime type ➔ T4 GPU**).
  - Quick summary links for all 4 studios.

---

### 🚀 Cell 1: 4X Upscaler Studio
- **Title:** `#@title 🚀 **Start 4X Upscaler** { display-mode: "form" }`
- **Core Technology:**
  - Pre-generated Cython C kernels (`SOURCE_PAYLOADS`: `lut`, `bilateral`, `unsharp`, `color_curve`) compiled in parallel with `gcc` on startup.
  - Native PyTorch super-resolution engine.
- **Supported Models:**
  - `4x_upscaler` *(Default)*: Lightweight ultra-fast compact network (`2x_XyetherAnime.safetensors`).
  - `Real-ESRGAN Anime`: High-fidelity enlargement for illustrations & general anime footage.
- **Color Correction (CC) Suite:**
  - `🔥 Sanchez CC` (warm, vibrant anime AMV grade).
  - `⚡ Zhakacc CC` (cool, high-contrast modern grade).
  - Fine-tuning drawer: Exposure, Contrast, Saturation, Temperature, Tint, Highlights, Shadows, Whites, Blacks, Sharpen, Film Fade.
- **Enhancers:**
  - Crisp Line Art & Outlines.
  - De-Noise & Clean Artifacts.
  - AMV Color & Vibrance Boost.
- **Outputs:**
  - Side-by-side comparison video player (`comparison_video`) / image (`comparison_image`).
  - Direct full-resolution download button.

---

### 🎞️ Cell 2: Video Interpolation Studio (RIFE Studio)
- **Title:** `#@title 🎞️ **Start Video Interpolation (RIFE Studio)** { display-mode: "form" }`
- **Core Technology:**
  - RIFE (Real-Time Intermediate Flow Estimation) v4.x optical flow neural networks.
- **Supported Models:**
  - `RIFE v4.6` *(Recommended — Anime & AMV)*
  - `RIFE v4.25` *(Motion & Fast Action)*
  - `RIFE v4.22 Lite` *(Fastest)*
  - `RIFE v4.22` *(Live-Action / Film)*
  - `RIFE v4.26` *(Heavy Quality)*
- **FPS Multipliers:** 2×, 4×, 8× frame multiplication.
- **Pipeline:**
  - Batched frame interpolation on CUDA.
  - FFmpeg NVENC / libx264 encoding with audio passthrough.
  - 8-second side-by-side comparison preview (`hstack=inputs=2`) with FPS tags.
  - Full-length interpolated video download.

---

### 🌊 Cell 3: Depth Map Studio
- **Title:** `#@title 🌊 **Start Depth Map Studio** { display-mode: "form" }`
- **Core Technology:**
  - Monocular depth estimation via `Depth Anything V2` and `MiDaS`.
  - Acoustic Depth: 3D Time-Frequency Spectrogram visualizer for audio files.
- **Supported Models:**
  - `Depth Anything V2 (Small)`: >60 FPS on T4 GPU, crisp contours.
  - `Depth Anything V2 (Base)`: Detailed depth for complex scenes.
  - `MiDaS (DPT-Hybrid)`: VFX camera projection & displacement.
- **Color Palettes:** Grayscale (VFX raw displacement), Inverted Grayscale, Inferno, Turbo, Magma, Viridis, Plasma, Spectral.
- **Features:** Lossless audio passthrough for music/videos, side-by-side comparison, prominent export download.

---

### 🧹 Cell 4: Denoiser Studio (Anti-Lines & Artifact Removal)
- **Title:** `#@title 🧹 **Start Denoiser Studio (Anti-Lines & Artifact Removal)** { display-mode: "form" }`
- **Model Used:**
  - **`1x-Archiver-AntiLines`** by Loganavter ([OpenModelDB](https://openmodeldb.info/models/1x-Archiver-AntiLines)).
  - Architecture: **`SRVGGNetCompact`** (1x Scale, 3 in, 3 out, 48 features, 16 conv blocks, PReLU activation).
  - Primary URL: `https://github.com/Loganavter/Archivist-Project-Denoiser/releases/download/v1.0/1x-Archivist_AntiLines.pth`
  - Mirror URL: `https://huggingface.co/nuriyoo/openmodeldb-mirror/resolve/main/models/1x-Archiver-AntiLines/1x-Archivist_AntiLines.pth`
- **Key Features:**
  - **Preload & Warmup:** Model is preloaded and warmed up in Stage 2 during notebook cell startup. Zero first-click delay.
  - **Tiling Engine (`_denoise_tensor_tiled`):** Linear cross-fade overlap blending (`tile_size=1024`, `tile_pad=32`) prevents VRAM OOM on 2K/4K media without tile seams.
  - **Deadlock-Free Video Pipeline:**
    - FFmpeg rawvideo `bgr24` input/output pipes with `stderr=subprocess.DEVNULL` to avoid 64KB OS buffer deadlocks.
    - `_read_exact()` helper ensures exact frame chunk reads without partial pipe truncation.
  - **Live FPS & Progress Tracking:**
    - `progress=gr.Progress(track_tqdm=True)` drives the native Gradio progress line.
    - Active status card yields live speed in **FPS**, current/total frame counter, percentage, and ETA.
  - **Upload Experience:** Fast metadata inspection without first-frame rendering lag.
  - **Results Experience:**
    - Side-by-side **Before vs. After Comparison Video / Image** in the preview player.
    - Single prominent download button: `⬇️ DOWNLOAD DENOISED FILE` downloading the pristine native-resolution output.

---

## ⚠️ Critical Gotchas & Solved Bugs

### 1. FFmpeg Subprocess Pipe Deadlock
- **Symptom:** Video processing gets stuck indefinitely ("just loading and loading").
- **Root Cause:** When `Popen(..., stderr=subprocess.PIPE)` is used without an active reader thread, FFmpeg's stderr fills the OS pipe buffer (64KB on Linux). Once full, FFmpeg blocks on write, causing Python to block on stdout read — a permanent deadlock.
- **Solution:** Always use `stderr=subprocess.DEVNULL` for high-throughput video streaming pipes unless explicitly draining via a dedicated thread.

### 2. Partial Reads from Pipe Streams
- **Symptom:** Corrupted frames, premature video termination, or shape mismatch errors.
- **Root Cause:** `stream.read(n)` on non-file pipes can return fewer than `n` bytes when data is in flight. A 1080p BGR frame is 6,220,800 bytes, which exceeds single pipe chunk sizes.
- **Solution:** Always use an exact chunk reading loop:
  ```python
  def _read_exact(stream, nbytes):
      buf = bytearray(nbytes)
      view = memoryview(buf)
      pos = 0
      while pos < nbytes:
          chunk = stream.readinto(view[pos:])
          if not chunk: break
          pos += chunk
      return bytes(buf) if pos == nbytes else None
  ```

### 3. Gradio Progress Freezing on Heavy Tasks
- **Symptom:** UI displays a spinning wheel with no percentage updates until the task completely finishes.
- **Root Cause:** If the click handler function calls a synchronous blocking function without `yield`, Gradio cannot push UI updates to the browser.
- **Solution:** Implement the processing engine as a generator (`process_video_denoise_gen`) that yields progress dictionaries periodically (~0.25s), and have `handle_start_denoise` yield `(preview, download_btn, status_html)` updates actively.

### 4. Hugging Face Mirror URL Paths
- In `nuriyoo/openmodeldb-mirror`, model weights are stored inside a `models/` directory:
  - ❌ Incorrect: `.../resolve/main/1x-Archivist_AntiLines.pth` (returns 404)
  - ✅ Correct: `.../resolve/main/models/1x-Archiver-AntiLines/1x-Archivist_AntiLines.pth`

---

## 🛠️ How to Safely Update `4x_upscaler.ipynb`

`4x_upscaler.ipynb` is a standard Jupyter Notebook JSON file. When making changes:

1. **Cell Index Map:**
   - `nb['cells'][0]` -> Markdown Header
   - `nb['cells'][1]` -> Cell 1: 4X Upscaler
   - `nb['cells'][2]` -> Cell 2: RIFE Studio
   - `nb['cells'][3]` -> Cell 3: Depth Map Studio
   - `nb['cells'][4]` -> Cell 4: Denoiser Studio

2. **Editing Pattern in Python:**
   ```python
   import json

   with open('4x_upscaler.ipynb', 'r', encoding='utf-8') as f:
       nb = json.load(f)

   # Convert multi-line python code to ipynb source list
   code = Path('updated_cell.py').read_text(encoding='utf-8')
   lines = [l + '\n' for l in code.split('\n')]
   if lines and lines[-1] == '\n': lines.pop()
   if lines: lines[-1] = lines[-1].rstrip('\n')

   nb['cells'][target_idx]['source'] = lines
   nb['cells'][target_idx]['outputs'] = []
   nb['cells'][target_idx]['execution_count'] = None

   with open('4x_upscaler.ipynb', 'w', encoding='utf-8') as f:
       json.dump(nb, f, indent=1, ensure_ascii=False)
   ```

3. **Integrity Check:**
   Always run a syntax validation after editing:
   ```bash
   python -c "import json; nb=json.load(open('4x_upscaler.ipynb', encoding='utf-8')); assert len(nb['cells']) == 5; print('Valid!')"
   ```

---

## 🛡️ Second Remediation Pass — Security, Concurrency & Binding Architecture

### 1. Upload Security & Admission Control
- **Early ASGI Middleware (`UploadAdmissionMiddleware`):**
  - Authenticates incoming `/api/v1/jobs/submit` requests **before** reading, consuming, or spooling multipart body bytes to disk. Rejects unauthenticated or invalid tokens with HTTP 401 JSON immediately.
  - Early `Content-Length` enforcement: rejects requests declaring body size larger than configured media limit + 10MB multipart overhead with HTTP 413.
  - Running byte-counter on `receive()` stream: terminates chunked or spoofed streams with HTTP 413 if total bytes exceed limit, preventing disk/memory exhaustion.
  - Bounded concurrency (`UploadAdmissionController`): limits simultaneous in-flight uploads (`UPSCALER_MAX_CONCURRENT_UPLOADS`) and overall queue load (`MAX_QUEUED_JOBS`), rejecting saturation with HTTP 429.
  - Guarantees exactly-once reservation release on all success, failure, disconnect, and cancellation exit paths.
  - Non-blocking file writes: offloads synchronous disk chunk writes using `asyncio.to_thread`.

### 2. Authoritative Cancellation & Race Elimination
- **Client Submission Identifier & Cancellation Endpoint:**
  - Android generates a unique `client_submission_id` per upload and attaches it to the submission.
  - Cancelling during upload calls `POST /api/v1/jobs/cancel-client-submission?client_submission_id=...`.
  - Backend records pre-cancelled submissions; when an upload completes, the server discards the file immediately and never queues it to the GPU worker.
  - Android submission coroutine handles are tracked; cancellations terminate local coroutines and prevent late responses from overwriting terminal `CANCELLED` states.
  - Startup reconciliation: interrupted jobs (`PREPARING`/`UPLOADING` without `serverJobId`) automatically reconcile to `FAILED` instead of blocking studios as persistent ghost jobs.
- **Backend State Invariants:**
  - `start_processing()` preserves paused states requested while queued.
  - `resume()` rejects cancelled jobs; `cancel()` rejects already completed jobs.
  - Cancelled jobs unhook/unlink output files and cannot complete.
  - Cancellation-aware FFmpeg muxing monitors subprocess execution, kills the process immediately, and unlinks partial outputs.

### 3. Connection & Pairing Transactions
- **Candidate Connection Pattern:**
  - Candidate connections `(baseUrl, authToken, OkHttpClient, apiService, wsClient)` are verified in isolation via `getHealth()` prior to state mutation.
  - Failed connection/pairing preserves the working active connection and credentials untouched.
  - Same-host reconnection without specifying a token retains the existing saved token.
  - Connecting to a different endpoint never inherits old credentials.
  - Deep link (`upscaler://connect?...`) and QR scanner prompt user confirmation before switching destination hosts.
  - Non-zero exponential backoff separates WebSocket drops from HTTP status recovery.

### 4. Job Origin Binding
- **Immutable Job Connection Binding:**
  - Every `ManagedJob` records `originBaseUrl`, `originAuthToken`, `originSessionId`, and `clientSubmissionId`.
  - Upload, stream monitoring, pause/resume/cancel, recovery, and download all execute through the job's originating cached connection.
  - Switching the active backend never migrates existing queued/running jobs.
  - Backend instance ID (`backend_instance_id`) exposed via `/api/v1/health` detects Colab session rotation/restarts.
  - Legacy unbound jobs without origin URL reconcile to `FAILED` safely on startup.

---


---

## 🔐 Authentication & Google Sign-In

Beyond the legacy per-session bearer token (`UPSCALER_AUTH_TOKEN`), the backend and Android app now support **Google Sign-In via OAuth 2.0 ID tokens**:

- **Android (`data/auth/GoogleAuthManager.kt`):** Builds a `GoogleSignInClient` requesting an ID token for the OAuth Web Client ID. On successful sign-in it persists the ID token + profile (`sub`/`email`/`name`/`picture`) to `SharedPreferences`. The ID token is then sent as `Authorization: Bearer <id_token>` on every REST/WebSocket call via the existing OkHttp interceptor — no new network code required.
- **Backend (`backend/server.py`):**
  - `GOOGLE_CLIENT_ID` env var holds the Web Client ID audience.
  - `verify_google_id_token()` validates the JWT signature, expiry, issuer, and audience using Google's `google-auth` library.
  - `require_auth()` accepts either a legacy session token or a Google ID token and attaches the resolved user to `request.state.user`.
  - WebSocket auth (`/api/v1/jobs/{job_id}/stream`) accepts Google ID tokens too.
  - `GET /api/v1/auth/me` returns the authenticated profile (`provider: "google"` for ID tokens, `provider: "session"` for legacy tokens).
- **Pairing preserved:** Google Sign-In is layered on top of the existing backend connection flow. The user still connects via QR/deep-link (session token), then signs in with Google to bind the session to a Google account. Legacy session-only use remains fully supported.

**Caveats:**
- Google ID tokens expire after ~1 hour; on expiry the backend returns `401` and the user re-signs in. Add refresh-token handling for longer-lived sessions.
- A new Colab runtime rotates the backend instance ID / session token, but the Google ID token is independent of that.

See `googlesignin.md` for the full setup guide (Google Cloud Console credentials, env var, `google-services.json`, string resources, and checklist).

---

## 📱 Mobile App Modernization & Studio Architecture (v1.0.0)

### 1. App Identity & Package Configuration
- **Application ID (Package Name):** `com.ryn.studio` (`android/app/build.gradle.kts`).
- **Version:** `1.0.0` (versionCode `1`).
- **Deep Linking:** Dual-scheme support in `AndroidManifest.xml`:
  - `ryn://connect?url=https://...&token=...`
  - `upscaler://connect?url=https://...&token=...` (backward compatibility)
- **Firebase / Google Services:** `google-services.json` updated with package name `com.ryn.studio`.

### 2. Colab Connection Card & Motion Architecture (`HomeScreen.kt`)
- **Action Buttons:**
  - **Open Colab (Green Vibrant Gradient):** Launches the 1-click Colab notebook in the browser.
  - **Disconnect Colab (Red Crimson Gradient):** Placed directly next to "Open Colab"; invokes `repository.disconnect()`, clearing active connections, session credentials, and resetting connection state to `Disconnected`.
- **Pop-Out Spring Animations:**
  - Powered by `animateFloatAsState` with physics-based spring specification:
    `spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)`
  - When pressed, buttons compress smoothly (`0.92f`), and upon release they bounce and overshoot past `1.0f` before settling.
- **Haptic Tactile Feedback:**
  - Powered by `LocalHapticFeedback.current`.
  - Emits `HapticFeedbackType.LongPress` on button clicks for satisfying tactile clicks and `HapticFeedbackType.TextHandleMove` on collapsible drawer toggles.
- **Collapsible Tools Window ("Collap Window"):**
  - Smooth animated expansion (`AnimatedVisibility`) under the main action buttons.
  - Contains **Scan QR** (CameraX + ML Kit), **Paste URL** (manual URL dialog for local development / same-device Colab), and **Retry/Refresh Connection**.

### 3. Non-Squishing Split Comparison Player (`BeforeAfterComparisonPlayer.kt`)
- **Dual Hardware-Clipped FrameLayout Architecture:**
  - Resolves video stretching and distortion by keeping both original input and enhanced output videos in 1:1 pixel-matching layers.
  - Layer 1 displays the full-resolution enhanced video.
  - Layer 2 hosts the original video within an Android `FrameLayout` clipped using `clipBounds` / layout width clipping at `splitFraction`. Neither video changes aspect ratio or scales dynamically during scrubbing.
- **Drift Synchronization Engine:**
  - Continuous playback loop monitors delta between `outputPlayer.currentPosition` and `originalPlayer.currentPosition`.
  - Automatically performs seek corrections if drift exceeds 70ms, ensuring flawless side-by-side synchronization.
- **Interactive Draggable Divider Handle:**
  - Circular knob with dual `< >` comparison arrows (`Icons.AutoMirrored.Filled.CompareArrows`).
  - Supports continuous horizontal dragging and tap-to-split repositioning.
  - Top corner badges ("BEFORE (ORIGINAL)" and "AFTER (ENHANCED)") provide immediate orientation.
- **High-Contrast Pure White Typography:**
  - All button labels, chip texts, and action icons across all studio screens, comparison players, and gallery pickers use pure `Color.White` with bold weight for crisp legibility against dark surfaces.

### 4. Manual Gallery Save Workflow, Polished Action Buttons & Files Management
- **Decoupled Internal Storage & Gallery Export:**
  - Completed video downloads stream into private app storage (`context.filesDir/outputs/`) with FileProvider URIs instead of automatically populating the user's public gallery.
  - The preview player immediately loads and compares the video without public gallery pollution.
  - Replaced the old "Open" button with **"Save"**: on tap, calls `repository.saveVideoToGallery(context, outputUri, fileName)` to write to public `MediaStore` (`Movies/UpscalerStudio`), shows a Toast notification, and updates the button state to "Saved" with a green checkmark.
- **Polished Button Alignment & Indentation:**
  - Standardized all 3 action buttons (`Save`, `Share`, `New Edit`) to equal `weight(1f)` and height `46.dp`.
  - Zeroed excessive default vertical padding, enforced `maxLines = 1` with ellipsis, and centered icon + label rows symmetrically for a clean, professional aesthetic.
- **Files Tab Deletion Feature:**
  - Each item on the Files tab includes Open, Share, and a dedicated **Delete** action (`Icons.Default.DeleteOutline`).
  - Added `deleteSavedMedia(context, item)` in `UpscalerRepository.kt` removing files from MediaStore (`content://`) and local output directories.
  - Includes an `AlertDialog` confirmation with "Cancel" and "Delete" actions and Toast feedback upon successful removal.

### 5. Settings Screen & Global Dynamic UI Scaling Accessibility (`SettingsScreen.kt`)
- **Global Reactive UI Scale Engine (`MainActivity.kt` & `SettingsManager.kt`):**
  - Integrated `CompositionLocalProvider(LocalDensity provides scaledDensity)` at the root application layer.
  - **Large (Default):** Scale `1.0f` — standard full size UI.
  - **Medium:** Scale `0.91f` — reduces overall UI and button sizes slightly for a more compact, dense aesthetic.
  - **Small:** Scale `0.82f` — scales down further for maximum information density.
  - Changes in `SettingsScreen` apply instantly across all screens and persist in `SharedPreferences`.
- **App Developer & Social Contacts Card (`SettingsScreen.kt`):**
  - Dedicated developer card: "App Developer", "Developed by ryn", "Get in touch or review portfolio" with high-contrast typography and purple accent avatar icon.
  - Interactive "Contact" button: clicking it triggers a sleek `AlertDialog` pop-up displaying all developer links:
    1. **GitHub:** `https://github.com/rynn-ui` (launches browser/app via `Intent.ACTION_VIEW`).
    2. **Discord:** `@rynn0976` (copies `@rynn0976` to Android clipboard and displays a Toast confirmation).
  - Clean "Close" button with high-contrast white text to dismiss the pop-up.
- **App Info & Privacy Card:**
  - "About Upscaler Studio" (app suite overview & architecture) and "Privacy Policy" (zero tracking, private GPU execution) dialogs.
- **Hero Card Laptop (PC) Icon Navigation:**
  - Clicking the gear icon in the top app bar opens `SettingsScreen`.
  - The Laptop (PC) icon on the backend connection hero card is now clickable with pop-out scale animation and haptic feedback, opening the "Connect to Colab Backend" pairing dialog.
  - Small rotating refresh button adjacent to "Backend Offline / Online" text provides instant live connection checks.

### 6. Authentication Architecture & Account Profile Suite (`AuthScreen.kt` & `HomeScreen.kt`)
- **First-Install / Onboarding Auth Screen (`AuthScreen.kt`):**
  - **Visual Identity:** Pure dark theme with glowing neon emerald lightning bolt (`#10B981`), "Upscaler Studio" header, and "AI video enhancement for anime & AMVs." subtitle.
  - **Segmented Control:** Sleek pill switcher toggling between `[Log In]` and `[Sign Up]` with emerald green active border and dark green tint.
  - **Social Authentication:** High-contrast "Continue with Google" button with authentic 4-color Google vector logo (`ic_google_logo.xml`).
  - **Email & Password Authentication:**
    - Dark rounded input fields with focus highlights and password visibility toggle.
    - Full Firebase Email/Password integration: `signInWithEmailAndPassword`, `createUserWithEmailAndPassword`, and `sendPasswordResetEmail`.
    - "Forgot password?" recovery flow with instant email dispatch.
  - **Guest Access:** "Skip for now & explore as guest" text link allowing users to access studios or pair with local Colab without mandatory sign-in.
- **Pixel-Perfect Account Profile Modal (`HomeScreen.kt`):**
  - Custom rounded card dialog (`ShapeCorner24`, dark `#13151F` surface with subtle borders).
  - **Header:** "Account" title with top-right "✕" close button.
  - **Profile Hero:**
    - Large circular avatar (`64.dp`) with emerald green container (`#102E22`), letter initial fallback (e.g. "R"), or live Google profile photo (`AsyncImage`).
    - User display name and email address.
    - Copyable ID badge chip (`ID: USR-8F3D72E1`) with one-tap clipboard copy and Toast feedback.
  - **Metadata Rows:**
    - **Account Provider:** Badge icon + provider name (`Google`, `Email`, or `Session`).
    - **Backend URL:** Link icon + truncated URL + chevron right (`>`), tap opens backend connection configuration dialog.
    - **Session Token:** Key icon + masked token (e.g. `zfxVv...7nCR7`) + copy icon button.
  - **Sign Out Action:** Outlined card button with `Logout` icon, synchronously clearing Firebase Auth session, Google client cache, and returning to the Auth screen.

### 7. Firebase Remote Config & Force Update Architecture (`RemoteConfigManager.kt`)
- **Remote Config Integration (`com.google.firebase:firebase-config`):**
  - Real-time cloud-driven version and maintenance control without releasing APK updates.
  - In-app baseline defaults: configured via `res/xml/remote_config_defaults.xml`.
  - Deployment template: `remote_config.json` and root `firebase.json` for CLI deployment (`firebase deploy --only remoteconfig`).
  - Tuned fetch intervals: `0s` minimum interval in debug builds for instant testing; `3600s` in production.
- **Update States & Enforcement (`AppUpdateDialog.kt`):**
  - `ForceUpdateRequired`: If `BuildConfig.VERSION_CODE < min_version_code`, displays an un-dismissible dialog (`dismissOnBackPress = false`, `dismissOnClickOutside = false`), showing current vs required version, update reasons, and an "Update Now" button linking directly to the releases/store URL.
  - `OptionalUpdateAvailable`: If `latest_version_code > VERSION_CODE` and `VERSION_CODE >= min_version_code`, prompts the user with an update suggestion with "Later" or "Update Now".
  - `MaintenanceMode`: If `is_maintenance_mode = true`, displays a non-dismissible maintenance screen informing the user of temporary downtime.

### 8. Colab Execution Model & GPU Quota Security
- **Independent User Runtimes:**
  - The shared Colab notebook (`backend_colab.ipynb`) is hosted with **Viewer** permissions.
  - When a user taps "Open Colab", Google Colab opens a fresh, isolated runtime attached strictly to **their own Google account**.
  - All GPU compute units, RAM, and disk are billed against **their quota**, completely protecting the host's GPU resources.
- **Cryptographic Session Tokens:**
  - Each Colab session generates a unique, isolated session bearer token (`UPSCALER_AUTH_TOKEN`) and exposes it through a randomized Cloudflare tunnel.
  - The QR code and deep links securely transmit this token to the paired Android app, preventing unauthorized external access to the public tunnel.

---

## ⚡ Third Remediation Pass — Performance Acceleration, Dual-Tunneling & UI/UX Pipeline (v2.1)

### 1. GPU Inference & Video Export Acceleration
- **Adaptive Full-Frame VRAM Inference:**
  - Resolved the severe GPU over-tiling bottleneck: previously, frames were split into 512×512 tiles with 16px overlap (causing 52 separate forward passes per frame for 4K and 12 passes for 1080p).
  - Modern 15GB Tesla T4 GPUs run full frames in VRAM natively up to $1088\text{p}$ and vertical/portrait phone videos ($1080\times 1920$) via orientation-aware bounds:
    `if can_cuda and (cur_h * cur_w <= 1920 * 1088 or (min(cur_h, cur_w) <= 1088 and max(cur_h, cur_w) <= 1920))`
  - Includes transparent `torch.cuda.OutOfMemoryError` fallback to high-resolution 1024-tile execution.
- **Fast FP16 Bilinear Downscaling for 4X Architectures:**
  - `Real-ESRGAN Anime v3 2X Compact` (`realesr-animevideov3.pth`) is internally a 4X network with 16 conv layers and 64 feature maps.
  - Replaced PyTorch's heavy antialiased bicubic downscaling (`mode="bicubic", antialias=True`, float32 casting) with direct FP16 bilinear downscaling, delivering a **2.3× speedup** per frame and cutting VRAM spikes in half.
- **cuDNN Auto-Tuning Benchmark:**
  - Enabled `torch.backends.cudnn.benchmark = True` on CUDA runtimes to dynamically select the highest-performing convolution algorithm for fixed frame dimensions (15–30% speedup).
- **Hardware NVENC Video Muxing:**
  - Upgraded `finalize_video_export()` in `backend/models/media_utils.py` to use GPU-accelerated hardware encoding (`-c:v h264_nvenc -preset p4 -cq 20`) with fallback to fast software encoding (`-c:v libx264 -preset veryfast -crf 19`), eliminating CPU bottlenecks during final 4K video assembly.
- **Base64 Preview Throttling:**
  - Throttled base64 JPEG thumbnail encoding to at most once per 1.0 second across all studio models (`upscaler`, `denoiser`, `rife`, `depth`), preventing CPU GIL contention while streaming frame progress every tick.

### 2. Dual Tunneling & Network Resilience
- **Dual Tunnel Architecture (Ngrok + Cloudflare Fallback):**
  - Cloudflare Quick Tunnels (`trycloudflare.com`) enforce a strict 100MB body limit, 100-second streaming timeouts, and are throttled by certain mobile ISPs.
  - Implemented `start_best_tunnel(port, ngrok_token)` in `backend/tunnel.py`:
    - Automatically initializes `pyngrok` if a token is present, providing persistent edge connections and unlimited upload/download sizes.
    - Seamlessly falls back to Cloudflare tunnel when `ngrok_token` is left blank (zero-setup mode).
- **Colab GPU / CPU Hardware Detection Banner:**
  - Integrated diagnostic hardware verification in `backend/backend_colab.ipynb` checking `torch.cuda.is_available()`.
  - Instantly alerts users in prominent bold text if the notebook was launched in CPU mode, providing 1-2-3 instructions to switch to **T4 GPU** (`Runtime ➔ Change runtime type ➔ T4 GPU`).
- **Android Client Network Resilience:**
  - Extended OkHttp connect, read, and write timeouts to **300 seconds** to accommodate large video transfers and long-running renders.
  - Added a proactive **95MB Cloudflare file-size safety guard** that alerts users before uploading oversized videos over Cloudflare tunnels.
  - Replaced the premature 6-disconnect fatal failure with a **20-attempt persistent HTTP polling status recovery loop** (~2–3 minutes of continuous reconnection attempts before declaring failure).

### 3. UI/UX Motion Design & High-Throughput Gallery Save Pipeline
- **Dedicated Upload Phase Animation (`UploadAnimationBlock`):**
  - Clarified the "Uploading to GPU" transition so users do not mistake file transmission for frozen AI processing.
  - Added an animated pulsing emerald `CloudUpload` badge (`rememberInfiniteTransition`), live transfer size label (e.g., `Uploading 18.5MB to Colab GPU...`), and a spinning circular progress ring with a helpful keep-alive reminder.
- **Optimized Gallery Save Pipeline & Download Animation (`SaveGalleryAnimationBlock`):**
  - Diagnosed and resolved user perception that "saving to gallery takes 1 min": the app was previously downloading the entire 50MB–200MB 4K video over the internet under the label "Saving to gallery" with progress locked at 100% and unbuffered streams.
  - Refactored `downloadToInternalStorage`: streams video data using an optimized **64KB chunk buffer** (`ByteArray(64 * 1024)`) and emits real-time downloaded megabytes (`Downloading: 18.4 / 45.0 MB`).
  - Added `SaveGalleryAnimationBlock`: renders an animated bouncing `CloudDownload` badge with live byte counter.
  - `saveVideoToGallery` now performs high-throughput 64KB writes to `MediaStore` (`Movies/UpscalerStudio`), completing local saves in **under 300ms**.
- **Universal Studio Consistency:**
  - Shared across all 4 studios via `StudioScaffold` and `LiveProgressCard`:
    1. ⚡ **2X Super-Resolution Studio** (`UpscalerStudioScreen.kt`)
    2. 🎞️ **RIFE 60/120 FPS Video Interpolation** (`RifeStudioScreen.kt`)
    3. 🧹 **AI Anti-Line Denoiser** (`DenoiserStudioScreen.kt`)
    4. 🌊 **Depth Map Studio** (`DepthStudioScreen.kt`)

---

*Last Updated: November 2026 • Maintained for [rynn-ui/upscaler-studio](https://github.com/rynn-ui/upscaler-studio)*

