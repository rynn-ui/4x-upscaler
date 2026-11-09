# 🧠 Memory & Architecture Guide — 4X Upscaler Colab Suite

> **File:** `memory.md`  
> **Repository:** [rynn-ui/4x-upscaler](https://github.com/rynn-ui/4x-upscaler)  
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

*Last Updated: November 2026 • Maintained for [rynn-ui/4x-upscaler](https://github.com/rynn-ui/4x-upscaler)*

