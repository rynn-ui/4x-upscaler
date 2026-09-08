# ⚡ 4X Upscaler & RIFE Frame Interpolation Studio
**Ultra HD AI Super-Resolution & 60/120 FPS Video Interpolation • Powered by Real-ESRGAN, RIFE v4.x & CUDA**  
*Made with ❤️ by Ryn*

---

[![Open In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/rynn-ui/4x-upscaler/blob/main/4x_upscaler.ipynb)

---

## 🌟 Overview

**4X Upscaler & RIFE Studio** is an all-in-one, browser-based AI video and image enhancement suite running on **free Google Colab GPU (Tesla T4 / A100)**. It combines **Real-ESRGAN AI Super-Resolution**, an **Anime & AMV Color Correction Suite**, and **RIFE Optical Flow Frame Interpolation** into sleek, dark-themed Web GUIs.

---

## 🚀 Key Features

### ⚡ 1. 4X AI Super-Resolution (Cell 1)
- **AI Upscaling Models**:
  - `4x_upscaler` *(Default)*: Ultra-crisp, high-contrast super-resolution fine-tuned for anime, AMVs, and twixtors.
  - `Real-ESRGAN Anime`: High-fidelity enlargement for illustrations & general anime footage.
- **🎯 Target Quality & Resolutions**: Select `Auto (2× Native)`, `1080p (FHD)`, `2K (1440p / QHD)`, or `4K (2160p / UHD)`.
- **⏸️ Play / Pause & Cancel**: Real-time interactive Pause, Resume, and Stop controls during processing.
- **🎨 Color Correction (CC) Suite**:
  - `🔥 Sanchez CC`: Fine-tuned anime & AMV color grade featuring rich contrast, warm skin tones, punchy vibrance, and clean specular highlights.
  - `⚡ Zhakacc CC`: Cool, high-definition anime color grade with crisp outlines, lifted exposure, controlled highlights, deep contrast, and stylized modern tones.
  - **Live Split-Screen Preview**: Real-time Before vs Graded side-by-side comparison with dynamic labels.
  - **⏱️ Video Timeline Scrubber**: Scrub anywhere along the video timeline to test CC and filters on different scenes.
  - **Fine-Tuning Color Drawer**: Full control over Exposure, Contrast, Saturation, Temperature, Tint, Highlights, Shadows, Whites, Blacks, Sharpen, and Film Fade.
- **✨ Anime & AMV Quality Enhancers**:
  - `🗡️ Crisp Line Art & Outlines`: Deepens outlines and line art without white halo artifacts.
  - `🧹 De-Noise & Clean Artifacts`: Removes low-bitrate compression noise, banding, and JPEG ringing.
  - `🌈 AMV Color & Vibrance Boost`: Punchy contrast and aesthetic color grading.
- **Direct Download & Comparison Player**: View synchronized Before vs After video player and tap one-click direct download.

---

### 🌊 2. RIFE Video Frame Interpolation (Cell 2)
- **Butter-Smooth 60 & 120 FPS**: Converts standard 24 FPS / 30 FPS clips into ultra-fluid 60 FPS, 120 FPS, or high-frame-rate slow motion.
- **RIFE v4.x Optical Flow Engine**:
  - `RIFE v4.6` (Recommended — Best for Anime, AMVs & General Footage)
  - `RIFE v4.25` (Latest — Best Motion Handling & Fast Camera Pans)
  - `RIFE v4.22 Lite` (Ultra Fast & Lightweight)
  - `RIFE v4.22` (Realistic & Live-Action Footage)
  - `RIFE v4.26` (Heavy Ultra-Quality)
- **Multipliers & Slow-Motion**: Supports 2×, 4×, and 8× frame multipliers.
- **Preserved Audio & Codecs**: Full FFmpeg audio passthrough with MP4 (H.264/H.265) and MKV support.
- **Side-by-Side Comparison**: Synchronized slow-motion / 60 FPS comparison preview with one-click direct video download.

---

### 🌊 3. Depth Map Studio (Cell 3)
- **AI Monocular Depth Estimation**:
  - `Depth Anything V2 (Small)`: Ultra-fast (>60 FPS on T4 GPU) with sharp character & geometric depth contours.
  - `Depth Anything V2 (Base)`: High precision depth detail for complex backgrounds and scenery.
  - `MiDaS (DPT-Hybrid)`: Classic VFX displacement and 3D camera projection depth.
- **Acoustic Depth Map**: 3D Frequency-Time Spectrogram visualizer for audio files with synchronized audio playback.
- **VFX Palettes**: Grayscale (Raw Depth / VFX Displacement), Inverted Grayscale, Inferno, Turbo, Magma, Viridis, Plasma, Spectral.
- **Lossless Audio Passthrough**: Full audio preservation for videos and songs.

---

### 🎬 4. Roto Studio (Cell 4) — (Under Development)
- **Precision AI Background Removal & Rotoscoping**:
  - `RMBG v1.4` *(State-of-the-Art BRIA AI Matting ONNX)*: Ultra-crisp hair & edge isolation.
  - `MODNet` *(Portrait Matting Fallback ONNX)*: Lightweight real-time human matting.
- **Edge-Band Selective Refinement**: Guided filtering on uncertain boundary zones (0.05 < alpha < 0.95) with soft feathering.
- **Temporal Optical Flow Smoothing**: Confidence-weighted Farneback optical flow blending eliminates video mask flickering with automatic scene-cut detection.
- **Dual Export Downloads**:
  - `WebM Transparent Video` (`libvpx-vp9` with `yuva420p` alpha channel + audio passthrough).
  - `MP4 Composited Video` (Green screen, black, white, or custom background color + audio passthrough).
  - High-res Transparent PNGs & Composited Images for photos.
- **Live Mask Preview on Upload**: Immediate first-frame preview on checkerboard upon dragging & dropping media.

---

## 🎯 Supported Models & Specifications

| Feature | Model / Engine | Best For | Output Resolution / FPS |
| :--- | :--- | :--- | :--- |
| **Super-Resolution** | `4x_upscaler` *(Default)* | Anime, AMVs, Twixtors | 1080p, 2K, 4K, or 2× Native |
| **Super-Resolution** | `Real-ESRGAN Anime` | Illustrations, Anime | 1080p, 2K, 4K, or 2× Native |
| **Color Grading** | `CC Suite & Enhancers` | AMVs, Demon Slayer / JJK Edits | Real-Time Preview & Graded Export |
| **Interpolation** | `RIFE v4.6 (Anime/AMV)` | Anime, Animations, Cartoons | 24/30 FPS ➔ 60 FPS / 120 FPS |
| **Interpolation** | `RIFE v4.25 (Motion)` | Fast Action, Sports, CGI | 2×, 4×, 8× Frame Multiplier |
| **Depth Estimation** | `Depth Anything V2` | 3D Displacement, Parallax | Grayscale, Inferno, Turbo, etc. |
| **Audio Depth** | `3D Spectrogram Visualizer`| Music, Songs, Podcasts | High-Def 3D Depth Spectrogram |
| **Rotoscoping** | `RMBG v1.4 (BRIA AI)` | Background Removal, Cutouts | Transparent WebM & Composited MP4 |

---

## 🚀 How to Run in Google Colab (1-Click Start)

1. Click the **[Open in Colab](https://colab.research.google.com/github/rynn-ui/4x-upscaler/blob/main/4x_upscaler.ipynb)** badge above.
2. In Google Colab:
   - Make sure GPU is active: **Runtime ➔ Change runtime type ➔ T4 GPU**.
3. **To Upscale & Color Grade (Cell 1)**:
   - Click **Play (▶️)** on **`Step 1: 4X Upscaler Studio`**.
   - Click **`🚀 OPEN STUDIO IN NEW TAB`** to launch the web interface.
4. **To Interpolate to 60/120 FPS (Cell 2)**:
   - Click **Play (▶️)** on **`Step 2: RIFE Video Frame Interpolation Studio`**.
   - Click **`🚀 OPEN RIFE STUDIO IN NEW TAB`** to interpolate your clips.
5. **To Generate Depth Maps (Cell 3)**:
   - Click **Play (▶️)** on **`Step 3: Depth Map Studio`**.
   - Click **`🌊 OPEN DEPTH STUDIO IN NEW TAB`** to generate depth maps.
6. **To Remove Backgrounds & Rotoscope (Cell 4)**:
   - Click **Play (▶️)** on **`Step 4: Roto Studio`**.
   - Click **`🎬 OPEN ROTO STUDIO IN NEW TAB`** to rotoscope videos and images.

---

## 🔒 Privacy & Performance

- **100% Private**: Files are processed entirely inside your personal Google Colab runtime and are automatically wiped upon session termination.
- **Hardware Acceleration**: Built with native C/Cython kernels, CUDA FP16 tensor core acceleration, and hardware NVENC encoding.
- **Interactive Control**: Every studio features instant Pause/Resume and Cancel controls.

---

*Made with ❤️ by Ryn*

