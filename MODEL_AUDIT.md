# Upscaler Model Audit & Replacement Plan

**Status: proposal only.** No application, backend, notebook or Android code has
been changed for this document. The only artifact added alongside it is
`backend/bench_upscaler.py`, a standalone measurement harness that imports the
existing backend modules read-only.

---

## 1. TL;DR

1. The catalog offers three models. **Two of them are the same 2022
   Real-ESRGAN family**, and the third (`2x-sudo-UltraCompact`) is a distilled
   sibling of the same family.
2. `Real-ESRGAN General` is labelled "Movie Model 2X" but is actually
   `realesr-general-x4v3`, a **4× model**. The app declares it as 2× and
   downscales the 4× output with bicubic (`backend/models/upscaler.py:160-167`).
3. `Real-ESRGAN Anime` is also a **native 4×** model (`realesr-animevideov3`),
   so it pays full 4× compute and then throws half the detail away.
4. Reaching 4K from 1080p runs the 2× model **twice**
   (`backend/models/upscaler.py:213-230`). Pass 2 runs on a 2160p frame, so a
   4K export costs ~5× a single 1080p pass.
5. `2x-sudo-UltraCompact` and `2x-AnimeJaNai-HD-V3-UltraCompact` have
   **identical compute (303,552 MACs/pixel)**. Swapping to AnimeJaNai costs
   nothing in speed and buys a 2023–24 trained model that also removes
   compression artifacts.
6. There is **no measured FPS anywhere in the repo**. All numbers below are
   either derived from MAC counts or copied from a third-party benchmark that
   used a *different* model family (clearly labelled).

---

## 2. What ships today

| Catalog key | Weights | Native scale | MACs/pixel (measured) | Params |
|---|---|---|---|---|
| `2x-sudo-UltraCompact` | `2x-sudo-UltraCompact.pth` | 2× | **303,552** | 304,716 |
| `Real-ESRGAN Anime` | `realesr-animevideov3.pth` | **4×** | **619,200** | 621,424 |
| `Real-ESRGAN General` | `realesr-general-x4v3.pth` | **4×** | **1,209,024** | 1,213,296 |

Measured with a forward pass of a 64×64 input through each model loaded by
spandrel 0.4.2, summing `Conv2d` MACs and dividing by input pixels.

Consequences of the 4×-native models being declared as 2×:

- `upscale_frame` computes `target_scale` from the declared `scale` (2), so for
  a 1080p → 4K job it schedules two 2× passes even though one native 4× pass
  from the source would be enough.
- Every tile pays 4× compute inside the network, then gets a bicubic downscale
  to 2×. That is the Chrome-flavoured version of "upscale then blur".
- A "2×" job on those models is already a 4× → 2× round trip, so their
  measured FPS understates nothing — it overstates the quality you get per
  FLOP.

### Catalog duplication

The model list is hard-coded in **four** places and they must be changed
together:

- `backend/models/upscaler.py:27-45` (`UPSCALER_MODELS`, source of truth for
  `/api/v1/capabilities`)
- `android/app/src/main/java/com/upscaler/studio/ui/studios/UpscalerStudioScreen.kt:40-47`
  (hard-coded; the app fetches `/capabilities` but does not use it for this list)
- `4x_upscaler.ipynb` (registry ~line 691, label mapping ~1249, dropdown ~1647,
  predownload ~336)
- `backend/tests/test_backend.py` and
  `android/app/src/test/java/com/upscaler/studio/data/model/ResolutionPolicyTest.kt`

---

## 3. Where the time goes

Throughput is proportional to `MACs/pixel × pixels`, so the budget is:

```
FLOPs per frame = MACs/pixel × 2 × width × height
```

At 1080p that is 2,073,600 pixels:

| Model | FLOPs/frame @1080p | Relative |
|---|---|---|
| AnimeJaNai V3 SuperUltraCompact (44,712) | 0.19 TFLOP | 0.15× |
| sudo UltraCompact / AnimeJaNai UltraCompact (303,552) | 1.26 TFLOP | 1.00× |
| AnimeJaNai V3 Compact (598,464) | 2.48 TFLOP | 1.97× |
| realesr-animevideov3 (619,200) | 2.57 TFLOP | 2.04× |
| realesr-general-x4v3 (1,209,024) | 5.01 TFLOP | 3.98× |

### Calibration

The only anchor we have is the in-app label for `Real-ESRGAN Anime` ("6 FPS" at
1080p on the reference GPU). Taking that at face value:

```
2.57 TFLOP/frame × 6 FPS = 15.4 TFLOPS sustained
```

15.4 TFLOPS is 23.7% of an NVIDIA T4's 65 TFLOPS fp16 peak — a plausible
sustained fraction for a small, memory-bound conv net with per-tile overhead.
Everything else in this document is scaled from that single anchor, so treat
the absolute FPS as ±25% and the *ratios* as reliable.

### Projected FPS table (T4-class, fp16, same anchor)

| Model | 480p | 720p | 1080p | 1440p | 1080p → 4K (two-pass) |
|---|---|---|---|---|---|
| AnimeJaNai SuperUltraCompact | 420 | 187 | 83 | 47 | 17 |
| sudo UltraCompact | 62 | 27 | 12 | 6.9 | 2.4 |
| AnimeJaNai UltraCompact | 62 | 27 | 12 | 6.9 | 2.4 |
| AnimeJaNai Compact | 31 | 13.9 | 6.2 | 3.5 | 1.25 |
| Real-ESRGAN Anime | 30 | 13.5 | 6.0 | 3.4 | 1.2 |
| Real-ESRGAN General | 15.5 | 6.9 | 3.1 | 1.7 | 0.6 |

The 4K column is 5× the 1080p column because pass 2 runs on the already-2×
frame: `1 + 4 = 5` units of work.

**On the user's RTX 3050 6 GB Laptop GPU** assume roughly 0.38× the T4 numbers
(~25 TFLOPS fp16 roofline), i.e. `sudo` ≈ 4.5 FPS and `Real-ESRGAN General`
≈ 1.2 FPS at 1080p.

---

## 4. Measured evidence (third-party, different models)

`https://benchmarks.animejan.ai/benchmarks.json` — community-submitted runs,
snapshot 2026-11-07, 91 submissions. These measure **AnimeJaNai HD V3.1 SPAN
models exported to TensorRT**, decoding + 2× upscaling + rendering, *not* the
Compact V3 models proposed in section 6. They bound what a well-optimised
pipeline can do; they are not a prediction for this repo's PyTorch path.

| GPU | Tier | 720p FPS | 1080p FPS |
|---|---|---|---|
| RTX 3050 6 GB Laptop | Balanced | 39.0 | 18.5 |
| RTX 3050 6 GB Laptop | Performance | 67.9 | 28.2 |
| RTX 3060 Ti | Balanced | — | 29.0 |
| RTX 3080 | Balanced | — | 59.2 |
| RTX 4090 | Balanced | — | 123 |

Reading: a 3050 Laptop can hit 18–28 FPS at 1080p with TensorRT-exported SPAN
tiers. Our PyTorch compact models are ~10× slower at the same resolution, which
is consistent with the calibration above rather than contradicting it.

---

## 5. Quality evidence

### The current anime pair is one 2022 family

- OpenModelDB for `2x-sudo-UltraCompact` lists `pretrainedModelG` =
  `2x-realesrganv2-animevideo-xsx2`, which is the 2× sibling of
  `realesr-animevideov3`. The author describes it as a first attempt and says
  it "does seem a little noisy if you zoom in a lot".
- `realesr-animevideov3` is the same architecture line. Both were trained for
  anime *video* in 2022, before modern compression-artifact removal.

### AnimeJaNai HD V3

- OpenModelDB tags: `anime`, `compression-removal`, `restoration`.
- Training: 425k iterations, dataset of 239,484 anime frames at HR 512, batch 9.
- Described use case: scaling artefacts, blur, oversharpening, ringing,
  compression blocking, depth-of-field blur, line colour and weight.
- Three shipped tiers are all 2× native and load with spandrel's existing
  `Compact` architecture — **no new dependency**.

### Honest caveats

- No PSNR/SSIM/LPIPS numbers are published for these models; "better" is
  perceptual and the AnimeJaNai tier is explicitly tuned to be *faithful and
  natural*, which can read as **softer** than sudo's over-sharpened look.
  Section 8 exists precisely to settle this on your own footage.
- `SuperUltraCompact`'s OpenModelDB entry lists only **5,000 iterations** and a
  small dataset (727 images). It is fast because it is tiny, not because it is
  well trained. Do not make it the default without the A/B in section 8.
- The repo's two-pass 4× path doubles error accumulation; any quality gain from
  the source model will show up most clearly on 2× jobs.

---

## 6. Candidates

| Candidate | Native | MACs/pixel | Size | Compute vs sudo | License |
|---|---|---|---|---|---|
| 2x-AnimeJaNai-HD-V3-UltraCompact | 2× | 303,552 (measured) | 2.4 MB | 1.0× | CC-BY-NC-SA 4.0 |
| 2x-AnimeJaNai-HD-V3-Compact | 2× | 598,464 (measured) | 4.8 MB | 2.0× | CC-BY-NC-SA 4.0 |
| 2x-AnimeJaNai-HD-V3-SuperUltraCompact | 2× | 44,712 (measured) | 0.38 MB | 0.15× | CC-BY-NC-SA 4.0 |
| 2x-ModernSpanimationV1 | 2× | ~380–525k (SPAN 64ch, unmeasured) | 15.8 MB | 1.3–1.7× | MIT |

### License decision (still open)

Two coherent paths — pick one, don't mix:

- **Path A — non-commercial only.** Use AnimeJaNai V3 (CC-BY-NC-SA 4.0). Fine
  for personal use and for keeping the repo free; incompatible with paid tiers,
  ads, or a monetised Play Store listing. Attribution must be added to the
  in-app about/credits and to the README.
- **Path B — commercial-safe.** Use `2x-ModernSpanimationV1` (MIT) as the
  shipped anime model. Lower quality ceiling than AnimeJaNai Compact, but it is
  permissively licensed and spandrel supports SPAN natively
  (`conv_1.sk.weight` detection keys), so the loader needs no change either.

Weights are already mirrored on Hugging Face
(`nuriyoo/openmodeldb-mirror`), the same pattern `backend/models/denoiser.py`
uses, so no zip extraction is needed:

| Model | File | SHA256 |
|---|---|---|
| AnimeJaNai UltraCompact | `2x_AnimeJaNai_HD_V3_UltraCompact.pth` | `7c74ae2f919a31b96a08afc7dee37f1d1bae8b80844b7cc24ee82d43efd64110` |
| AnimeJaNai SuperUltraCompact | `2x_AnimeJaNai_HD_V3_SuperUltraCompact.pth` | `402f81e5b3202dfead0e6d2e092c9fc0372b687bff4bf2b5da68735bdc8206c8` |
| AnimeJaNai Compact | `2x_AnimeJaNai_HD_V3_Compact.pth` | `af7307eee19e5982a8014dd0e4650d3bde2e25aa78d2105a4bdfd947636e4c8f` |

---

## 7. Proposed change set (not applied)

If approved, the swap is small and reversible:

1. `backend/models/upscaler.py`
   - Replace the two `Real-ESRGAN *` catalog entries with the AnimeJaNai V3
     entries, keep `2x-sudo-UltraCompact`.
   - Add `MODEL_ALIASES` mapping the two legacy keys to their replacements and
     `resolve_model_key()` used by validation / loading / dimension math, so
     saved jobs and older app builds do not 422.
   - Pin `filename`, `sha256` and `min_bytes`; extend
     `atomic_download_file` with an `expected_sha256` check.
2. `backend/server.py:362` — validate through `resolve_model_key` instead of
   `in UPSCALER_MODELS`.
3. `UpscalerStudioScreen.kt:40-47` — same four keys/labels, sudo first.
4. Notebook registry / label mapping / dropdown / predownload, tests, and the
   three docs (`README.md`, `HOW_TO_USE.md`, `memory.md`).

Deliberately **out of scope** here: tiling/batch tuning, ffmpeg pipe instead of
the mp4 writer, NVENC, the two-pass 4× fix, ONNX/TensorRT export, and having
Android drive its model list from `/capabilities`.

Documented-only follow-ups (biggest wins, in order):

1. **Fix 4× two-pass** — one native 4× pass is ~3× cheaper than two 2× passes
   at 4K output. Largest single win, no quality loss, but it changes tiling and
   memory behaviour.
2. **Reuse the notebook's batched tile loop** — the notebook uses
   `channels_last` + prefetch; the backend is serial per tile.
3. **ONNX Runtime / TensorRT export** — the community numbers in section 4 come
   from TensorRT; expect a large multiple over eager PyTorch.

---

## 8. How to prove it (no code changes required)

`backend/bench_upscaler.py` measures the current catalog and any ad-hoc
weights file, with crop dumps and PSNR/SSIM for a visual A/B.

```powershell
# 1. Throughput of what ships today (GPU machine or Colab T4)
python backend/bench_upscaler.py --models "2x-sudo-UltraCompact,Real-ESRGAN Anime,Real-ESRGAN General" `
  --resolutions 480p,720p,1080p --frames 20 --json bench_current.json

# 2. A/B a candidate without editing the catalog
python backend/bench_upscaler.py `
  --weights "ajn-ultra=C:/models/2x_AnimeJaNai_HD_V3_UltraCompact.pth" `
  --weights "ajn-compact=C:/models/2x_AnimeJaNai_HD_V3_Compact.pth" `
  --weights "ajn-super=C:/models/2x_AnimeJaNai_HD_V3_SuperUltraCompact.pth" `
  --models "2x-sudo-UltraCompact,ajn-ultra,ajn-compact,ajn-super" `
  --resolutions 1080p --frames 20 --json bench_candidates.json

# 3. Visual + quantitative A/B on your own footage, against a 4K master
python backend/bench_upscaler.py `
  --models "2x-sudo-UltraCompact,ajn-ultra,ajn-compact" `
  --weights "ajn-ultra=C:/models/2x_AnimeJaNai_HD_V3_UltraCompact.pth" `
  --weights "ajn-compact=C:/models/2x_AnimeJaNai_HD_V3_Compact.pth" `
  --video D:/clips/episode01.mp4 --reference D:/clips/episode01_4k_master.mp4 `
  --crops bench_crops --ab-frames 5 --save-full
```

On a CPU-only machine the script prints a warning and the numbers are not
representative; use a CUDA box or Colab T4 (the script needs no CUDA-only
dependency).

### Results table to fill in

| Model | 1080p FPS (measured) | 4K-target FPS | PSNR vs master | SSIM | LPIPS | Verdict |
|---|---|---|---|---|---|---|
| 2x-sudo-UltraCompact | | | | | | |
| AnimeJaNai UltraCompact | | | | | | |
| AnimeJaNai Compact | | | | | | |
| AnimeJaNai SuperUltraCompact | | | | | | |

Decision rule: default to AnimeJaNai UltraCompact only if it is within ~10% of
sudo's FPS **and** wins the visual crops at 100% zoom. Keep SuperUltra out of
the default path regardless of its speed.

---

## 9. Environment notes

- The audit machine has `torch 2.13.0+cpu` / `cuda False`, so no real FPS can be
  measured locally. Derived numbers above are all labelled as such.
- `/api/v1/capabilities` already enumerates `UPSCALER_MODELS`; Android ignores
  it for the upsell list, which is why the list has to be edited in two places.
- Downloads are verified against OMDB SHA256 in section 6; all four files above
  were downloaded and loaded successfully with spandrel 0.4.2 during this audit.
