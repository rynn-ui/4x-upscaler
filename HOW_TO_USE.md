# 📱 Quick Start Guide: How to Use AI Upscaler Mobile & Colab

> **The Easy 3-Step Guide for Video Editors & Creators**  
> No coding knowledge required. Everything runs from your phone against your private Colab GPU!

---

## 💡 How It Works

1. **Your Android Phone**: Runs **Upscaler Studio** with video previews, durable background job tracking (`Home`, `Queue`, `Files`), and synchronized Before/After playback.
2. **Google Colab**: Provides a **free cloud GPU (NVIDIA Tesla T4)** running the FastAPI media backend (`Real-ESRGAN`, `RIFE v4.x`, `1x-Archiver`, `Depth Anything V2`, and `AMV Color Grading`).
3. **Authenticated 1-Tap Pairing**: Connects over HTTPS/WSS through a Cloudflare tunnel using a per-session bearer token embedded inside both the **`🚀 OPEN IN UPSCALER APP`** deep link and the **QR code**.

```
📱 Your Phone (Select Video, Configure Model, Tap Start)
       │
       ▼ (Authenticated Cloudflare Tunnel: Bearer Token + WSS)
☁️ Google Colab (Free T4 GPU runs AI models & FFmpeg muxing)
       │
       ▼ (Verified download & save)
💾 Phone Gallery (Saved to Movies/UpscalerStudio & visible in Files tab)
```

---

## 🚀 3 Easy Steps to Run & Use

### 🟢 Step 1: Install the Android App

1. Open **Android Studio** and select the `android/` directory *(or install the built APK)*.
2. Connect your phone via USB (with USB Debugging enabled) and click **Run (▶️)**.

---

### ☁️ Step 2: Start the Free Colab Cloud GPU

1. Tap **Open Colab** in the app (or open **[backend_colab.ipynb](https://colab.research.google.com/drive/1i1WCx7EPUrMkc-jjME60AMMRDrw8XvkX)**).
2. Ensure GPU is enabled: **Runtime ➔ Change runtime type ➔ Select T4 GPU ➔ Save**.
3. Click **Play (▶️)** on the runner cell. Setup takes ~30–60 seconds.
4. When live, Colab displays a dark pairing card with:
   - **`🚀 OPEN IN UPSCALER APP`** (1-tap deep-link button with embedded session token).
   - A compact **Pairing QR Code** (scan with **Scan QR** in the app if running Colab on your PC).
   - The public `https://...trycloudflare.com` tunnel URL.

---

### ⚡ Step 3: Pair & Process!

1. **Pairing**:
   - **On phone**: Tap **`🚀 OPEN IN UPSCALER APP`**.
   - **Colab on PC**: Tap **Scan QR** on Home and point your camera at the QR code in Colab.
   - *(Manual fallback)*: Tap **Paste URL** or the **Settings** gear icon and paste either the `upscaler://connect?...` pairing link or the URL + session token.
2. When verified, the top card displays **`🟢 Backend Online`** with your Colab GPU device and free VRAM.
3. **Choose a Studio**:   - **⚡ 4X Super-Resolution**: Pick `2x-sudo-UltraCompact`, `Real-ESRGAN Anime`, or `Real-ESRGAN General` ➔ Pick a target (`1080p`, `2K`, `4K`, or `2×` to double the source, e.g. 1080p ➔ 4K).
   - **🎞️ RIFE 60/120 FPS**: Pick RIFE model (`v4.6`, `v4.25`, `v4.22 Lite`, `v4.22`, `v4.26`) ➔ Choose `High FPS (Keep Audio)` or `Slow Motion (Muted)` ➔ Pick `2×`, `4×`, or `8×`.
   - **🧹 AI Anti-Line Denoiser**: Clean compression banding, horizontal tears, and noise using `1x-Archiver-AntiLines`.
   - **🌊 Depth Map Studio**: Generate AI depth maps (`Depth Anything V2 Small/Base` with `Inferno`, `Turbo`, `Grayscale`, or `Viridis`).
4. **Track & Export**:
   - Jobs survive navigation across **Home**, **Queue**, and **Files**—you can check **Queue** anytime to pause, resume, cancel, or retry downloads.
   - Completed videos are saved to **`Movies/UpscalerStudio/`** and open immediately in the synchronized Before/After comparison player.

---

## ❓ Frequently Asked Questions

### Do I have to pay anything?
**No.** Google Colab's T4 GPU runtime is free.

### Will Colab disconnect while I am editing?
Colab keeps running via the background keep-alive ping, and if a mobile network drop interrupts streaming, the Android app automatically reconnects and recovers job state via `/api/v1/jobs/{job_id}/status`.
* **Important for Mobile Editors**: If running Colab on your phone's browser, mobile OSes (Android/iOS) may freeze background tabs after 20 seconds. Run your browser in **Split-Screen** or **Floating Window** mode alongside Upscaler Studio, or run Colab on your PC/laptop and scan the QR code!

### What if my video is large (>100MB) or Cloudflare is blocked by my ISP?
Cloudflare's free tunnel has a 100MB upload limit and may be throttled by some mobile carriers. For unlimited video sizes and ultra-stable connections, paste a free **Ngrok AuthToken** (from [ngrok.com](https://ngrok.com)) into the Colab runner cell.

### Which model gives the fastest processing speed?
* **Fastest (15–25 FPS)**: `2x-sudo-UltraCompact` — full-frame accelerated in FP16 on Colab GPU.
* **Balanced Interpolation**: `RIFE v4.22 Lite` or `RIFE v4.6` at 2×.
* Heavy models like `Real-ESRGAN General` or 4-pass 4K require significantly more compute.

### Can anyone guess my backend URL and submit jobs?
Every session generates a random **Bearer session token** required for all REST and WebSocket endpoints.
