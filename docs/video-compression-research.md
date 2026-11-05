# Video compression: research and recommendation

Status: research only, no app code yet. Written 2026-11-05, before the video tool is built.
The image tools (`app/src/main/java/com/storageplanner/tools/`) are the model this follows. The app
only plans; a later action taker will run the tool. Originals are removed only through Android's trash,
with the user's confirmation.

## 1. Summary

- **Codec: HEVC (H.265) through the phone's hardware encoder** (`MediaCodec`). It is on almost every
  phone the app supports (minSdk 26), it is fast, and every phone, PC and chat app plays it.
- **AV1** gives about 10–15% smaller files at the same quality in our tests. Few phones can encode it
  in hardware, and the S23 FE's Exynos 2200 can only decode it. So AV1 is an option for later, used only
  when `MediaCodecList` reports a hardware AV1 encoder. **H.264** is the fallback for phones without
  HEVC.
- **Don't use a fixed percentage.** A fixed share of the source bitrate was the worst approach in every
  test. It wasted bits on easy clips and starved hard ones: on the grainy clip, 60% of the bitrate gave
  PSNR 32 dB, while a quality-targeted encode of the same size gave 38 dB. The tool should **aim for a
  quality level**, find the bitrate that reaches it for each video, and report the saving it achieves.
- **Phone hardware encoders ignore or lack "constant quality" mode**, Exynos included. So the tool finds
  the bitrate itself with a **probe search**: encode a few 2-second samples at candidate bitrates,
  measure them against the original, and choose the lowest bitrate that passes. Then encode the whole
  file and check it again. This works like `ab-av1 crf-search` and Netflix's per-title encoding, adapted
  to a VBR-only hardware encoder.
- **Five levels.** The safest one targets "looks identical". The most aggressive gives a phone-watchable
  480p copy. Expected savings are in §5.
- **First, refuse.** As with photos, some videos must not be touched until each case is tested:
  - HDR10+ and HLG;
  - slow motion and high frame rate;
  - Samsung-specific boxes and trailers;
  - extra tracks;
  - already-efficient files.
- **Metadata needs work.** `MediaMuxer` keeps rotation and location if you pass them in, but it stamps
  the *current* time as the creation date. The tool has to write the original date back, or the gallery
  will move the video to "today".

## 2. What phones record (the input)

From published figures, mostly older Galaxy phones. The Lab will confirm the S23 FE's own values.

| Mode | H.264 | HEVC ("High efficiency video") | Per minute (H.264) |
|---|---|---|---|
| 1080p 30 fps | ~13–17 Mbps | ~7–9 Mbps | ~100–130 MB |
| 1080p 60 fps | ~27 Mbps | ~14 Mbps | ~200 MB |
| 4K 30 fps | ~41–48 Mbps | ~20–25 Mbps | ~340 MB |
| 4K 60 fps | ~72 Mbps | ~42 Mbps | ~520 MB |

Two things make phone videos easy to shrink:
- Phone encoders must work in real time and use little power. They use short GOPs (a keyframe every
  second), few or no B-frames, and generous fixed bitrates.
- Samsung's default is H.264 unless the user turns on "High efficiency video".

An offline re-encode can spend far more effort per frame. There is also a hard case: low-light video is
full of sensor noise, and noise can't be compressed.

## 3. Options considered

| Approach | Size vs H.264 at equal quality | On-phone encoder | Plays everywhere | Verdict |
|---|---|---|---|---|
| H.264 re-encode | baseline | every phone | yes | fallback only |
| **HEVC hardware** | ~40–50% smaller | almost every phone since ~2016 | yes (Android 5+, iOS 11+, Windows with the free codec) | **default** |
| AV1 hardware | ~50–60% smaller | few (Pixel 8+ Tensor G3, some MediaTek Dimensity; not Exynos 2200) | Android 10+ (software decode), most browsers; older TVs no | optional, where present |
| AV1 software (`c2.android.av1-aom`) | same as above | Android 14+, very slow, drains the battery | — | no |
| VVC / H.266 | ~25% smaller than HEVC | no phone encoders | almost nothing plays it | no |
| VP9 | ≈ HEVC | few hardware encoders | Android, browsers; not iOS gallery | no |
| Downscaling (4K→1080p, 1080p→720p) | big | GL pass before the encoder | yes | for the lower levels |
| 60→30 fps | ~25–35% | drop frames before the encoder | yes | for the lower levels, never for slow motion |
| Denoising before encoding | helps only very noisy clips | GL shader; costly | yes | only at the lowest level, see §4 |
| AV1 film-grain synthesis (remove the grain, re-add it on playback) | very large on grainy video | only software encoders | AV1 players only | trend to watch, not practical yet |
| ML upscaling or denoising on the phone | — | NPU, vendor-specific | — | not practical in a framework-only app |
| **Media3 Transformer** (Jetpack library) | — | wraps MediaCodec, adds HDR tone mapping, GL effects, a muxer | — | not used: the project is framework-only. We borrow its approach (decoder→Surface→GL→encoder Surface) and its lessons (HDR handling) |

Compression figures in this table come from public encoder comparisons. They agree only loosely, and
hardware encoders do worse than the software ones those comparisons used. The shares above are what
our own run showed (§4) and match the middle of the published ranges.

## 4. Experiments (desktop, ffmpeg 6.1)

Reproduce with `python3 tools/video_bench.py OUT clip.mp4 ...`. Each clip is 6 s of 1080p30, and every
copy is scored against the clip (Y SSIM, Y PSNR; smaller copies are upscaled back first).

- **A**: fractal zoom with heavy per-frame grain, like low-light footage, H.264 17 Mbps.
- **B, C**: handheld pans over two real S23 FE photos with light grain, H.264 ~16 Mbps (Samsung's
  default).
- **D**: clip C stored as HEVC 8 Mbps ("High efficiency video" on).
- A first attempt with a pure-noise gradient ("sky") was dropped. It was unlike any real footage: every
  encoder made it 3–7× *bigger*. It did show one lesson: **a re-encode can be larger than the original,
  so the tool must compare sizes and keep the original when the copy isn't clearly smaller.**

The pans move a still photo, so they are easier to predict than real scenes, and the savings on B–D are
higher than real videos will give. Trust the *comparisons between rows*, not the exact percentages.

Selected results (saved = file-size reduction against the original):

| Run | A (grainy) saved / SSIM / PSNR | C (clean, H.264 src) | D (clean, HEVC src) |
|---|---|---|---|
| x264 crf23 | 3% / .968 / 42.0 | 90% / .986 / 41.9 | 83% / .986 / 41.8 |
| x265 crf24 | 40% / .957 / 40.3 | 94% / .987 / 42.5 | 88% / .988 / 42.8 |
| x265 crf28 | 60% / .952 / 38.3 | 96% / .980 / 40.6 | 93% / .981 / 40.7 |
| x265 crf32 | 71% / .946 / 36.0 | 97% / .967 / 38.2 | 95% / .970 / 38.5 |
| SVT-AV1 crf38 | 47% / .955 / 40.2 | 92% / .990 / 44.2 | 86% / .990 / 43.8 |
| SVT-AV1 crf46 | 69% / .950 / 38.1 | 95% / .987 / 42.8 | 91% / .987 / 42.6 |
| HEVC "hardware-like", 60% of source bitrate | 65% / .933 / **32.2** | 40% / .992 / 45.3 | 41% / .988 / 43.4 |
| HEVC "hardware-like", 25% | 90% / .919 / 30.6 | 77% / .986 / 42.5 | 78% / .975 / 39.5 |
| HEVC "hardware-like", 15% | 95% / .914 / 30.1 | 87% / .977 / 40.1 | 88% / .956 / 36.8 |
| x265 crf28 + denoise | 60% / .952 / 38.2 | 96% / .977 / 40.3 | 93% / .978 / 40.4 |
| x265 crf28 at 720p | 81% / .939 / 33.3 | 97% / .964 / 37.5 | 95% / .966 / 37.6 |
| x265 crf30 at 540p | 91% / .929 / 31.8 | 98% / .938 / 35.1 | 97% / .942 / 35.3 |
| SVT-AV1 crf40 at 720p | 78% / .944 / 34.1 | 96% / .980 / 40.1 | 93% / .980 / 40.1 |

What the numbers say:

1. **Content decides the saving, not the setting.** The same x265 crf28 saved 60% on the grainy clip
   and 96% on the clean one. A slider that promises "50%" would be wrong for almost every video.
   That is why the levels below are quality targets.
2. **A fixed bitrate share is the wrong control.** At 60% of the source bitrate, the grainy clip fell to
   PSNR 32 dB, which shows visible smearing, while x265 at a similar size reached 38 dB. On clean clips,
   the same 60% wasted space: 40% saved where 90%+ was available at better quality. Phone encoders only
   offer bitrate control, so the bitrate has to be chosen per video by measuring (§6).
3. **Noise sets the floor.** On the grainy clip, x264 crf23 saved only 3%. Even the best quality-matched
   copies kept SSIM below 0.97: grain changes randomly every frame, no encoder keeps it, and SSIM counts
   that as loss. **The photo guard (SSIM ≥ 0.98) can't be reused as it is.** Video needs thresholds
   tuned for video, measured at a reduced size where grain averages out (§6).
4. **AV1 beats HEVC by about 10–15% at the same quality.** On C, AV1 crf46 and x265 crf24 have the same
   size and nearly the same scores. That is real, but small next to the gap between a fixed bitrate and
   a measured one. HEVC's compatibility and hardware support win for now.
5. **Denoising didn't pay off at the higher levels.** Against the original it lowered scores (it removes
   real detail along with grain) and saved nothing extra at crf28. It is worth trying only at the most
   aggressive level, where we accept the loss.
6. **Downscaling is the biggest lever for space.** 720p cut sizes by another third to half against
   1080p at the same crf, at a clear but moderate drop (SSIM ≈ 0.94–0.97). 540p roughly halves it again.
   These are for the "save space" levels.
7. **HEVC sources shrink less than H.264 sources**, but still a lot (93% vs 96% at crf28 on the same
   content). Re-encoding HEVC camera files is worth doing.

## 5. Proposed levels

Each level is a quality target plus a size policy. The tool searches for the lowest bitrate that meets
the target, and the Lab reports what that saved. The savings are estimates from §4 and published
figures; real phone videos will land lower than the pans. **The Lab on the S23 FE sets the real numbers
before any of this ships.**

| Level | Resolution / fps | Video target (worst sample) | Audio | Expected saving on H.264 camera video | On HEVC camera video |
|---|---|---|---|---|---|
| **1. Looks identical** | keep | SSIM ≥ 0.985 and PSNR ≥ 40 at half size, no colour shift | copy as-is | 40–70% | 15–40% |
| **2. High** | keep | SSIM ≥ 0.97, PSNR ≥ 37 at half size | copy | 60–80% | 35–60% |
| **3. Balanced** | max 1080p, 60→30 fps if not slow motion | SSIM ≥ 0.95 at 1080p-equivalent | copy if AAC ≤ 256 kbps, else AAC 160 kbps | 75–90% | 60–80% |
| **4. Space saver** | max 720p, max 30 fps | SSIM ≥ 0.93 | AAC 96 kbps stereo | 85–95% | 80–90% |
| **5. Maximum** | max 480p (854×480), max 30 fps, light denoise | bitrate floor ~0.6–0.8 Mbps; just keep it watchable on a phone | AAC 64 kbps (mono if the source is mono) | 93–98% | 90–96% |

Rules for every level:
- Keep the copy only if it passes its target **and** is at least 10% smaller, as with photos.
  Otherwise keep the original and report why.
- Never upscale. Never raise the frame rate. Never re-encode a file that is already at or below the
  level's size.
- Levels 1–2 are safe defaults for automatic suggestions. Levels 3–5 lose visible quality: the plan
  must show them as such, with a before/after preview in the Lab, and the user chooses them per video or
  per folder (for example, "WhatsApp Video → Space saver").

## 6. How the tool should work on a phone (framework only)

**Pipeline.**
1. Read the file with `MediaExtractor` and decode it with a `MediaCodec` decoder into a `Surface`.
2. An OpenGL ES pass on an EGL context scales the picture (levels 3–5), applies the rotation, and later
   handles denoising or tone mapping.
3. The pass draws into the encoder's input `Surface` (HEVC Main, 8-bit).
4. Write the result with `MediaMuxer`. At levels 1–2, audio is copied sample by sample with no
   re-encode.
5. Keep each frame's timestamp (`eglPresentationTimeANDROID`), because phone videos often have a
   variable frame rate.

**Encoder setup.**
- Prefer a hardware encoder (`MediaCodecInfo.isHardwareAccelerated()`, API 29+; on older phones, skip
  `c2.android.*` and `OMX.google.*`).
- Rate control is `BITRATE_MODE_VBR`. Use `BITRATE_MODE_CQ` only if `isBitrateModeSupported(CQ)` is true
  *and* the Lab shows it actually responds: on several chipsets it is missing, and on some it is ignored.
- Use a longer keyframe interval (`KEY_I_FRAME_INTERVAL` 2–5 s instead of the camera's 1 s) for an easy
  size gain.
- Allow B-frames where offered (`KEY_MAX_B_FRAMES`, API 29+).
- `KEY_PRIORITY` = 1 (not real time) and a high `KEY_OPERATING_RATE`, so the encoder runs faster than
  real time.

**Probe search** (finding the bitrate):
1. Choose 4 samples of 2 s each, spread through the video, plus the busiest part (the largest frames
   in the source).
2. Encode the samples at a starting bitrate taken from the level's bits per pixel, then binary-search the
   bitrate (3–4 rounds) until the *worst* sample just passes the level's target.
3. Add a 10% margin, encode the whole file, then verify it: decode one frame per second from both the
   copy and the original at half size, and score SSIM and PSNR on Y plus colour PSNR, reusing `Ssim` and
   `Guard`.
4. Keep the copy only if it passes. Expected cost: about 20–30% more time than a single encode.

**Measuring without VMAF.** VMAF (Netflix's perceptual metric) is the industry reference, but it needs
native code. We use SSIM and PSNR at half size, on the Y channel, taking the worst sample. This
measures picture structure and ignores grain that no encoder keeps. Section 4 shows why: full-size SSIM
on a grainy clip stays below 0.97 even for good copies.

**Refuse, or handle explicitly, before encoding** (each case needs a Lab test before it is allowed):
- **HDR** (HDR10+, HLG, or 10-bit BT.2020 with a PQ or HLG transfer). The S23 FE can record HDR10+.
  Keeping HDR needs a 10-bit Main10 encoder, the right colour keys and static metadata, and HDR10+
  dynamic metadata is lost. Refuse at first. Later, keep HDR where `FEATURE_HdrEditing` (API 33) is
  reported, or tone-map to SDR at levels 4–5 only.
- **Slow motion and high frame rate** (over 60 fps, or Samsung's slow-motion and super slow-mo boxes in
  `udta`/`smta`). The playback speed lives in metadata a re-encode would lose.
- **Samsung trailers and extra boxes** (`SEFT` after the movie data, like the photo trailers in
  `Extras`). Refuse until each kind is understood.
- **Extra tracks:** anything besides one video and one audio track, such as a second audio track,
  subtitles, or a timed metadata or gyro track.
- **Unusual or protected streams:** DRM, interlaced video, odd sizes the encoder rejects, Dolby Vision.
- **Already efficient:** HEVC or AV1 at or below the level's bits per pixel.

**Metadata to keep.**
- Rotation: `MediaMuxer.setOrientationHint`.
- Location: `MediaMuxer.setLocation`, read from `MediaMetadataRetriever`.
- **Creation time:** `MediaMuxer` writes "now". Patch the original value back into `mvhd`, `tkhd` and
  `mdhd`, a small fixed-size rewrite like `Exif`. Also keep the file's modified time and MediaStore's
  `DATE_TAKEN`.
- Samsung's `udta` items beyond these, written to the plan as "lost on copy", like `Extras` does for
  photos.

**Running it.**
- Long jobs run in a foreground service, only while charging by default.
- Pause when `PowerManager.getCurrentThermalStatus()` (API 29+) reaches `THERMAL_STATUS_SEVERE`.
- Write to a temp file, check free space first (allow the source's size plus 10%), and resume per file.
- A 1-minute 1080p clip should take seconds to a few tens of seconds on a hardware encoder. The Lab
  measures it.

## 7. Next steps

1. **Video Lab, the first deliverable.**
   - Build `VideoCompressor` in `tools/` with the pipeline, probe search and verification above, at
     levels 1–5.
   - Add a Lab screen that compresses a chosen video at every level into the Lab folder (never touching
     the original).
   - Log the encoder name, supported modes, the chosen bitrate, the time taken, the scores and the
     saving.
   - Add a before/after player with a split view.
2. **Calibrate on the S23 FE** with real clips: daylight, low light, a screen recording, a WhatsApp
   video, 4K, 60 fps, HDR10+ and slow motion (the last two should be refused). Then fix the level
   thresholds and the expected-savings table from those runs.
3. **Plan integration:** suggest levels per folder or file kind, and show the saving per level before
   the user chooses.
4. Later: AV1 on phones with a hardware AV1 encoder, keeping HDR where the encoder supports
   `FEATURE_HdrEditing`, and denoising for level 5.

## Sources

- Android reference: [`MediaCodecInfo.EncoderCapabilities`](https://developer.android.com/reference/android/media/MediaCodecInfo.EncoderCapabilities) (bitrate modes, quality range)
- [codec-wiki: MediaCodec hardware encoders](https://codec-wiki.pages.dev/docs/encoders_hw/mediacodec): Exynos H.264/HEVC and Google's AV1 encoder have no CQ mode
- [FFmpeg ticket 10689](https://trac.ffmpeg.org/ticket/10689): MediaCodec rate-control behaviour
- [MediaCodec inconsistencies between chipsets](https://groups.google.com/g/android-platform/c/vAkV7xU7LxE): CQ/VBR settings ignored on some chipsets
- [Pixel CQ removal commit](https://git.evolution-x.org/Evolution-X-Tensor/device_google_gs101/commit/495567bb43): CQ mode withdrawn from a hardware HEVC encoder
- [r/androiddev: hardware vs software AV1 on Pixel](https://red.joygnu.org/r/androiddev/comments/1jypish/the_difference_between_hw_and_sw_encoder_of_av1)
- [Media3 Transformer tone mapping](https://developer.android.com/media/media3/transformer/tone-mapping) and [supported formats](https://developer.android.com/media/media3/transformer/supported-formats)
- [Evaluation of hardware video encoders for UHD live streaming (arXiv 2511.18686)](https://arxiv.org/pdf/2511.18686): Qualcomm hardware lags software encoders
- [Evaluation of GPU encoders for low-latency 4K (arXiv 2511.18688)](https://arxiv.org/pdf/2511.18688)
- [Comparing VVC, HEVC and AV1 (University of Bristol)](https://research-information.bris.ac.uk/en/publications/comparing-vvc-hevc-and-av1-using-objective-and-subjective-assessm/)
- [NVIDIA: AV1 on Ada Lovelace](https://developer.nvidia.com/blog/improving-video-quality-and-performance-with-av1-and-nvidia-ada-lovelace-architecture) (vendor claim: 40% vs H.264)
- [Fora Soft: AV1 in 2026](https://www.forasoft.com/learn/video-encoding/articles/av1-state-2026), [Jellyfin AV1 tests](https://jellywatch.app/blog/av1-codec-jellyfin-future-streaming-encode-decode-2026)
- [GSMArena: Galaxy S9 video bitrates](https://www.gsmarena.com/samsung_galaxy_s9-review-1734p8.php), [PhoneArena: S9 HEVC file sizes](https://www.phonearena.com/news/How-to-save-memory-space-when-shooting-video-with-the-Galaxy-S9_id103219), [SamMobile: storage used by photos and videos](https://www.sammobile.com/news/how-much-storage-do-photos-and-videos-actually-use/)
- [GSMArena: S23 FE uses the Exynos 2200](https://m.gsmarena.com/samsung_galaxy_s23_fe_runs_geekbench_confirming_exynos_2200_on_board-news-59108.php); Samsung's Exynos 2200 page lists AV1 *decode* only
- [Google Transcoder API: HDR output](https://docs.cloud.google.com/transcoder/docs/how-to/convert-to-hdr) (Main10 needed for HDR10)
