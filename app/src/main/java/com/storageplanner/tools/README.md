# Tools

This folder holds **tools**: code that changes files on the phone, so that a future **action taker** can carry
out what the plan recommends. Today it holds the image compression tools (HEIC, WebP, and lossless WebP) and the
video compression tool (HEVC at five levels, see [Videos](#videos-videocompressor)).

## Ground rules

- **The algorithm never calls a tool.** The scanner, classifier and planner (`com.storageplanner.core`), and the
  screens that show the plan, only decide what action to take. Nothing in the plan or its screens imports
  `com.storageplanner.tools`. The action taker will be built later. It decides which files to act on, asks the
  user, and calls these tools.
- **The tools depend on nothing in the app or core.** They use only the Android framework and each other. They
  have no AndroidX and no other libraries, just like the rest of the app. Keep it that way, so tools can be
  reused and tested on their own.
- **Today the only caller is the Algorithm Lab** (`app/LabScreen.java`): **Compress (test copy)** and
  **Compress all (test copies)**. It writes test copies to `Download/StoragePlanner/compressed/<name>-<time>/`.
  It never touches the original. Original vs compressed is shown by `app/CompressCompareScreen.java`. Videos:
  **Video compression (test copies)** in the Lab opens `app/VideoLabScreen.java`, which works the same way.
- **A tool never deletes or changes an original.** It writes a new file next to it. Removing originals is the
  action taker's job (see [Removing originals](#removing-originals-not-built-yet)).
- **The APK audit enforces this** (`tools/audit_apk.py`, run by CI):
  - only `tools/ImageCompressor` and `tools/VideoCompressor` may call `File.delete` / `File.renameTo` (on their
    own work files and samples);
  - there must be no MediaStore trash / delete / write requests anywhere.
  A new tool or caller that needs more must update that allowlist on purpose, and say why.

## Files

| File | What it is | Android? |
|---|---|---|
| `ImageCompressor.java` | **The entry point.** Makes one checked copy of one picture. | yes |
| `HeifEncoder.java` | Writes HEIC with `MediaCodec` + `MediaMuxer`. Lists every HEIC/HEVC encoder on the phone (`Way`). | yes |
| `Guard.java` | The "looks identical" thresholds, the regions they are checked on, brightness (luma), the WebP format constant. | yes |
| `Ssim.java` | SSIM matching scikit-image. A **copy** of `app/Ssim.java` (used by the planner's Calibrator). | no (plain Java) |
| `Exif.java` | Finds a JPEG's EXIF block, reads and sets orientation, sets the size tags, drops the thumbnail, builds a test block. | no |
| `Extras.java` | Finds what a JPEG holds that a copy would lose (Motion Photo video, gain map, depth map), so it is not compressed. | no |
| `WebpMeta.java` | Adds an EXIF chunk to a WebP file (Android's WebP encoder writes none). | no |
| `CopyPaths.java` | Where a copy goes (`IMG_1.jpg` → `IMG_1.heic` / `.webp`) and its hidden work file. | no |
| `VideoCompressor.java` | **The entry point for videos.** Makes one checked copy of one video at a `VideoLevel`. | yes |
| `VideoTranscoder.java` | Re-encodes a video or a stretch of it: `MediaExtractor` → decoder → OpenGL (scaling) → encoder → `MediaMuxer`, audio copied or re-encoded. | yes |
| `VideoSearch.java` | The decisions: what is refused, the copy's size and frame rate, and the bitrate search over samples. | no |
| `VideoLevel.java` | The five levels: quality targets, size and frame-rate limits, audio. | no |
| `Mp4.java` | Reads an MP4's boxes (tracks, user data, trailer, slow-motion marker, dates) and writes the original dates into a copy. | no |
| `FrameScore.java` | Scores one video frame against the original (SSIM, PSNR, colour PSNR, colour shift). | no |

The plain-Java files are tested on a desktop by `jvmtest/com/storageplanner/test/CompressionTest.java` and
`VideoTest.java`. CI runs both before every build. That test also checks `tools/Ssim` == `app/Ssim`: **change both or neither.**

## How to use

```java
import com.storageplanner.tools.ImageCompressor;

// once per batch of files (a "run"): remembers what worked, so later files search faster
Map<String, Integer> hints = new HashMap<>();
File work = new File(context.getCacheDir(), "compress");          // temporary test encodes

if (!ImageCompressor.available(ImageCompressor.Kind.PHOTO)) { /* show ImageCompressor.unavailableReason(...) */ }

ImageCompressor.Outcome o = ImageCompressor.compress(new File(path), ImageCompressor.Kind.PHOTO, hints, work);
if (o.ok) {
    // o.output: path of the copy (next to the original, .heic or .webp), already checked
    // o.before / o.after: sizes in bytes; o.format: which way made it; o.quality; o.ssim / o.psnr / o.shift
    MediaScannerConnection.scanFile(context, new String[]{o.output}, null, null);   // let galleries see it
} else {
    // o.skipped: why no copy was made (the original is untouched). o.trace: every step, for a log.
}
```

- **`Kind.PHOTO`**: camera photos and chat images. The copy is lossy and must look identical. It is HEIC or
  WebP, whichever passes and is smallest.
- **`Kind.LOSSLESS`**: screenshots and flat graphics. The copy is lossless WebP and must be pixel-identical.
- **`compressForLab(src, kind, base, work)`**: makes a copy with **every** way of saving (`base-<way>.<ext>`)
  and keeps them all, even ones a real run would reject (`Attempt.note` says why). `Outcome.output` is the one
  a real run would keep. Only for the Lab.
- **`encoderCheck(work)`**: tests every way of saving once and returns a one-line summary. `compress()` calls
  it itself; call it early only to log it.
- **`decodeUpright(file, maxSide)`**: decodes a picture for showing, turned upright by its EXIF.

### What the caller (the action taker) must do

1. **Choose the files.** The plan's `Unit`s whose chosen option is `reencode_photo` / `lossless_image`. Leave
   out items the user kept. Leave out items the plan removes another way, such as deleting a duplicate:
   compressing those frees nothing.
2. **Check that each file is still the one the plan means.** Its size and last-modified date should match the
   scan (`FileItem.size`, `FileItem.mtime`, within about 2 s). The tool doesn't know about scans.
3. **Run off the main thread.** A photo takes several seconds. For many files, use a foreground service
   (type `dataSync`) with a partial wake lock, renewed per file, and a way to stop between files. The removed
   `CompressionService` did this; see git history before the commit that created this folder.
4. **Use one `hints` map per run.** After `ImageCompressor.DROP_AFTER` (5) kept copies, a way of saving that
   made none of them is no longer searched.
5. **Scan the new copies** with `MediaScannerConnection` so galleries show them.
6. **Never remove an original** unless its copy still exists and is not empty, and the user confirmed it.
7. **Know what a copy does not keep.** The tool refuses pictures it can't copy fully: Motion Photos (the video
   after the JPEG), Ultra HDR gain maps, and depth maps (`Extras`), including Samsung Portrait photos. A
   wide-colour (Display P3) photo is saved only as WebP that keeps the colour profile, never as HEIC. A copy
   keeps the pixels, the colour profile (WebP) and the EXIF. **Lost silently:** plain XMP (edit history, ratings, panorama data),
   small data after the picture (under 64 KB, such as Samsung's `Image_UTC_Data`), and the EXIF thumbnail of a
   turned photo. Tell the user before removing originals.

### Removing originals (not built yet)

What the earlier version did, and what to keep:

- **Android 11+:** use `MediaStore.createTrashRequest(resolver, uris, true)` → `startIntentSenderForResult`.
  Android shows its own confirmation, and the user can restore from the trash for 30 days. You need MediaStore
  item URIs: query `MediaStore.Files` by `DATA` path. Use `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` + id
  for images. Send batches of about 500.
- **Android 8–10:** there is no trash. Confirm in the app, `File.delete()`, then scan the path so MediaStore
  forgets it.
- **Remember** the originals waiting for removal across restarts. The old `Originals.java` used
  SharedPreferences "original\tcopy". Offer a **Keep both** choice, and a way to compare before removing.
- **Tell the user:**
  - Google Photos and other backup apps will upload the copies again.
  - WhatsApp can't find copies of chat images. Once the originals are gone, those images show as missing
    inside the chats.

## Videos (`VideoCompressor`)

The research behind it (options, measurements, why these choices) is in
[`docs/video-compression-research.md`](../../../../../../../docs/video-compression-research.md).

```java
import com.storageplanner.tools.VideoCompressor;
import com.storageplanner.tools.VideoLevel;

File work = new File(context.getCacheDir(), "video-work");      // samples, deleted as they are scored
VideoCompressor.Outcome o = VideoCompressor.compress(new File(path), VideoLevel.HIGH, new File(outPath), work);
if (o.ok) {
    // o.output == outPath: checked, dates, rotation and location kept; o.before / o.after in bytes
    // o.search.bitrate, o.search.score (worst frame), o.encoder, o.audio, o.lost (what the copy does not keep)
} else {
    // o.skipped: why (nothing was written; the original is untouched). o.trace: every step, for a log.
}
```

- `outPath` must not exist (the tool never replaces a file). Pick a name the action taker can rename to the
  original's name after the original is in the trash, for example `.sp-VID_1.mp4` next to it.
- **Levels** (`VideoLevel`): each is a quality target for the *worst* checked frame, not a percentage.

  | Level | Size / frame rate | Worst frame must reach | Audio |
  |---|---|---|---|
  | 1 Looks identical | kept | SSIM 0.985, PSNR 40 dB | copied |
  | 2 High | kept | SSIM 0.97, PSNR 37 dB | copied |
  | 3 Balanced | ≤ 1080p, ≤ 30 fps | SSIM 0.95, PSNR 34 dB | copied |
  | 4 Space saver | ≤ 720p, ≤ 30 fps | SSIM 0.93, PSNR 32 dB | AAC 96 kbps |
  | 5 Maximum | ≤ 480p, ≤ 30 fps | SSIM 0.90, PSNR 29 dB | AAC 64 kbps |

  Every level compares at the copy's size, halved when the copy is above 720p: sensor grain, which no encoder
  keeps, averages out at half size. (Comparing a 1080p night video at full size kept PSNR at 32.6 dB even at 85% of
  the original's bitrate, on the S23 FE.) Levels 3–5 compare against the original scaled to the copy's size, so
  they measure the encoding, and the smaller size is the level's chosen loss. Every level also
  needs colour PSNR ≥ its limit and a colour shift ≤ 3. **The thresholds are first guesses: calibrate them in the
  Video Lab on real phone videos.**
- **How a copy is made:**
  1. **Refuse first** (`VideoSearch.refuse`): no video track, unreadable MP4, HDR (PQ, HLG, Dolby Vision, 10-bit
     HEVC), more than one video track, under a second (these the Lab can't override); slow motion (Android's
     `com.android.capture.fps` key well above the playback rate, or unreadable; Samsung writes it in every video,
     with the playback rate as its value) or over 61 fps, a second audio track, any other track (subtitles, sensor
     data), 64 KB or more after the video (Samsung's `SEFT` trailer).
  2. **Plan** (`VideoSearch.plan`): the copy's size (even numbers) and frame rate (frames dropped by 1/fps slots,
     never re-timed), a bitrate range (from the level's bits per pixel; at most 85% of the original's video
     bitrate, so "already small" videos are skipped without encoding), and where to sample: the whole video up to
     20 s; otherwise the busiest 2 s (most bytes) plus 2 s at 10/35/60/85%.
  3. **Search** (`VideoSearch.run`): encode the samples at a bitrate, score 3 frames per sample (after its first
     half second), up to 4 rounds of a geometric binary search for the lowest bitrate whose worst frame passes.
  4. **Encode the whole video** at that bitrate + 10% and **verify** it on up to 24 frames spread over the video,
     none of them a frame the search used. If it fails, try once more at +30%.
  5. Keep it only if it passes and saves at least 10%. Write the original's dates into `mvhd`/`tkhd`/`mdhd`
     (`Mp4.writeTimes`) and its last-modified time, then rename the work file into place.
- **Encoder:** the hardware HEVC encoder (`isHardwareAccelerated`, or not `OMX.google.*`/`c2.android.*` before
  Android 10), H.264 when there is no HEVC encoder for the size, software only as a last resort. VBR when offered;
  keyframe every 3 s; `KEY_PRIORITY` 1 (not real time).
- **Cropped decoder buffers:** a 1080p decoder hands over 1920x1088 frames cropped to 1080. Android's
  SurfaceTexture then pulls the texture in by a texel on each cropped side (so filtering never reads the padding),
  which stretched the copy by 2 px and capped PSNR at 34.2 dB at any bitrate on the S23 FE. `VideoTranscoder.Trim`
  puts the exact crop back when the matrix shows that trim; the Lab log says "texture trim undone".
- **Kept:** every frame's own time (variable frame rate), rotation (as a flag, pixels not turned), location
  (`©xyz`), the creation and modified dates, the audio as it is (levels 1–3). **Not kept:** the file's other user
  data (Samsung's `smta` and similar boxes: `o.lost` lists them), a small trailer, HDR (refused instead).
- **Lab:** `compressForLab` makes the copy even when a real run would refuse the video or drop the copy (unless
  nothing meaningful can be made), and says why in `o.wouldSkip`.

### Testing the video tool

- `jvmtest/.../VideoTest.java` (CI): MP4 reading and date writing on hand-built files, refusals, plans, the
  search with a fake encoder, frame scores.
- `jvmtest/.../VideoDesktop.java` (by hand, needs ffmpeg): runs the real `VideoSearch` on real files, with
  ffmpeg's x265 in a "hardware-like" setting (fastest preset, one-pass VBR, no look-ahead, no B-frames) as the
  encoder and the same `FrameScore`:
  `javac -d /tmp/vt app/src/main/java/com/storageplanner/tools/{Mp4,FrameScore,VideoLevel,VideoSearch,Ssim}.java jvmtest/com/storageplanner/test/VideoDesktop.java`
  then `java -cp /tmp/vt com.storageplanner.test.VideoDesktop OUT_DIR video.mp4 ...`.
- On the phone: Algorithm Lab → **Video compression** (the first button). It logs the encoder, its rate modes, every
  sample's bitrate and scores, the time taken, the decoder's output buffer (size, crop, colour range), and for each
  worst frame an alignment check: the shift of the copy (±2 px) that matches the original best in the top, middle
  and bottom third. Anything but `(0,0)` there means the copy is moved or stretched, which no bitrate fixes.
  **Compare worst frame** shows the frame the check scored worst.

## How it works

### Photos (`Kind.PHOTO`)

**First, refuse** what a copy can't fully keep. `compress()` does this before anything else; the Lab compresses
anyway and says a real run would not. Refused: files over 25 MP, files that are already HEIC/WebP, and what
`Extras` finds:
- XMP that names a Motion Photo, gain map or depth map;
- an MPF segment;
- a second JPEG, or "MotionPhoto", after the picture;
- 64 KB or more of data after the picture.

`Extras` streams the file, keeping at most one segment and 64 KB of trailing data in memory, so files of any
size are checked. A file that can't be checked (read error, damaged data) is refused, not let through.

1. **Encoder check, once per process** (`encoderCheck`). Every way of saving is tested on a 1100×700 test
   picture: smooth gradients plus a checkerboard that doesn't line up with the tiles, so misplaced tiles show,
   and partial edge tiles. It carries an EXIF block with a camera name and size tags. Each way records:
   - whether the picture comes back right (PSNR ≥ 30, SSIM ≥ 0.9). If not, it retries without EXIF: some
     phones may store EXIF as picture data;
   - whether the EXIF reads back (`ExifInterface` `TAG_MAKE`);
   - whether Android's **region decoder** (what galleries use to zoom) reads an **aligned** region the same as a
     full decode does.

   The ways of saving:
   - every HEVC encoder fed 512×512 tiles (hardware and software, each separately);
   - a dedicated HEIC encoder, if the phone has one with constant quality;
   - Android's WebP encoder.

   In a real run, a way is skipped if it would lose EXIF, or if the region decoder misreads it. For a
   wide-colour photo (not sRGB), every HEIC way is skipped too. The Lab still tries them, and says a real run
   would not keep them.
2. **Decode** the photo and **turn the pixels upright** from the EXIF orientation (all 8 values, including the
   mirrored ones).
3. **Fix the EXIF:**
   - set Orientation to 1, and drop the thumbnail (IFD1) when the photo was turned: it stays in the camera's
     orientation;
   - set ImageWidth/ImageLength and PixelX/YDimension to the copy's real size (see
     [what we learned](#important-lessons-dont-forget)).
4. **Search** (`search`), for each usable way. Four 512 px regions, at 0.25 and 0.75 of the width and height,
   are packed into one 2×2 mosaic. A binary search finds the lowest quality where every region passes:
   - HEIC searches quality 30–95; WebP searches 50–99.
   - The first try is the quality that worked for the previous photo of this run. Failing that, it's 90 for
     WebP (it passed at 89–93 on every camera photo tried) and mid-range for HEIC.
5. **Rank** the ways by mosaic size. HEIC must be **5% smaller** than WebP to rank ahead
   (`HEIC_PENALTY`), because every app can open WebP.
6. **Full image**, smallest first:
   - Encode it and check it again on six **other** regions of the real file, using a **full decode**: four at
     0.4 / 0.6, plus the top-left and bottom-right corners, where HEVC tiles are partial.
   - If it fails, raise the quality by 3 and retry, up to the top of the range. If it saves less than 10%
     (`MIN_SAVING`), try the next way.
   - Also check that the region decoder reads two **aligned** spots the same as the full decode. If not,
     reject the copy (`REGION_WRONG`).
   - For a wide-colour photo, the copy must decode in the **same colour space** as the original; if not, it's
     rejected.
   - A real run keeps the first copy that passes. The Lab makes a copy with every way.
7. **Safe write.** Each copy is written to a hidden work file in the target folder (`.sp-<name>.<ext>.tmp`),
   checked, then renamed. Work files are deleted in all cases. The copy gets the original's last-modified date.

### Screenshots (`Kind.LOSSLESS`)

The screenshot is compressed with `Bitmap.compress` to lossless WebP: `WEBP_LOSSLESS` on Android 11+, or `WEBP`
at quality 100 before that, which is lossless there. Like photos, the pixels are turned upright and the EXIF
(if it's a JPEG) is carried over with `WebpMeta`. The result is decoded again and must be `sameAs` the upright
original, and it must save at least 10%.

### The guard (what "looks identical" means)

On every checked 512 px region:
- **SSIM(Y) ≥ 0.98** and **PSNR(Y) ≥ 38 dB**, where Y = 0.299 R + 0.587 G + 0.114 B. These are the research
  prototype's thresholds, the same as the app's `Calibrator` (`Guard.SSIM_MIN` / `Guard.PSNR_MIN`).
- **colour shift ≤ 3**: the mean of R, G and B each moves by at most 3 of 255 (`MAX_COLOR_SHIFT`). This catches
  a colour cast, a conversion mistake the brightness checks can't see.
- **colour PSNR ≥ 38 dB**: PSNR of Cb and Cr (BT.601), the lower of the two (`CHROMA_PSNR_MIN`). This catches
  colour bleeding and blur, whose errors cancel out in the mean shift. Copies that passed the brightness guard
  measured 46–52 dB on the desktop test photos, so it only rejects real colour damage.

SSIM matches scikit-image's `structural_similarity` defaults: a 7×7 uniform window, sample covariance,
K1 = 0.01, K2 = 0.03, range 255, and the mean over the area not touched by padding. It was checked against the
app's `Ssim.java` to 15 decimal places on the desktop.

### HEIC writing (`HeifEncoder`)

- **HEVC tiles.**
  - The picture is cut into 512×512 tiles; edge tiles repeat the last row and column.
  - Each tile goes in as a YUV 4:2:0 frame (BT.601, limited range, as declared in the format) through
    `getInputImage()` with flexible YUV. It is filled 2 rows at a time, so a whole photo needs no big buffer.
  - The encoder is set to make every frame a key frame (`KEY_I_FRAME_INTERVAL` 0).
  - `MediaMuxer` (`MUXER_OUTPUT_HEIF`, Android 9+) stores the tiles as an image grid. The track format is the
    encoder's output format (it keeps `csd-0`), with mime `image/vnd.android.heic`, the full width/height,
    and the tile and grid keys.
- **Dedicated HEIC encoder** (where present): the whole picture goes in as one frame at constant quality, and
  the encoder makes the grid. **Not yet tested on a real phone:** the S23 FE has no HEIC encoder.
- **Quality:**
  - Constant quality maps 0–100 onto the encoder's own quality range.
  - Variable bitrate maps quality q to 0.15 + 4·q² bits per pixel per tile (at 30 fps, capped at the
    encoder's maximum).
- **EXIF:** written as one sample with flag `16`, which is `MediaCodec.BUFFER_FLAG_MUXER_DATA` (hidden in older
  SDKs). The block must start `Exif\0\0`, as `Exif.fromJpeg` returns it.
- The same layout as AndroidX HeifWriter, without AndroidX.

### WebP EXIF (`WebpMeta`)

`Bitmap.compress` writes a simple WebP (`RIFF/WEBP/VP8`) without EXIF. `withExif`:
- rewrites the file in the extended format: `VP8X` (canvas size, EXIF flag), then the original chunks, then an
  `EXIF` chunk holding the TIFF part, without the `Exif\0\0` prefix;
- pads odd-sized chunks;
- replaces an existing EXIF chunk.

## Important lessons (don't forget)

Each of these was found on a real phone: a Samsung Galaxy S23 FE (Exynos), Android 16.

- **The region decoder only reads correctly at aligned positions.** Ask `BitmapRegionDecoder` for a region at
  an odd x, such as x = 483, and:
  - **WebP** moves it to an even pixel and returns it **1 px wider** (512 → 513);
  - **HEIC** returns the requested size but **shifted** (PSNR about 29 against a full decode).

  At aligned spots (multiples of 16) both are exact. So **judge quality on a full decode**, and test the
  region decoder only at aligned spots. Galleries zoom at aligned tiles, so the files are fine.
- **Cameras record the size before turning the photo.** A portrait S23 FE photo says ImageWidth 4000 /
  ImageLength 1848, plus orientation 6. A copy whose pixels were turned upright must say 1848×4000, or
  decoders that trust the tags misread it (`Exif.withSize`).
- **Hardware HEVC encoders can be poor for still photos.** The Exynos encoder (`c2.exynos.hevc.encoder`) only
  offers **variable bitrate**. Its copies saved 11–18%. The software `c2.android.hevc.encoder` saved 28–37% at
  **constant quality**; its maximum is 512×512, exactly one tile. That's why every encoder is tried.
- **WebP is often as good as phone HEIC.** It saved 25–37% on the same photos. Desktop x265 HEIC reached 54% on
  a similar photo, but phones don't offer it to apps.
- **No AVIF or JPEG XL encoder** is available to apps on Android. JPEG XL also doesn't open in galleries.
- **Android's WebP encoder writes no EXIF.** Without `WebpMeta`, copies would lose the date taken, camera and
  location.
- **The encoder check must look like real photos.** It once passed every way because its test EXIF had no size
  tags and its test region started at even pixels. Real copies then failed. Keep partial tiles, size tags,
  and both an aligned and an unaligned region in it.
- **Speed.** A real run takes about 5–10 s per 7 MP photo on the S23 FE: three ways searched, then one full
  copy. That's an estimate from the Lab: a Lab run (every way, full size) takes about 20 s. Fewer ways
  (`DROP_AFTER`) and good first guesses (hints, `WEBP_START`) matter.
- **Memory.** Above 25 MP (`MAX_PIXELS`) the tool refuses, `compress()` and `compressForLab()` alike, because
  a full ARGB decode is needed twice: the original and the copy being checked (about 200 MB at 25 MP). On
  Android 8+ bitmap pixels live in native memory, outside the Java heap limit, and an `OutOfMemoryError` ends
  in "not enough memory" with the original untouched. If the copy's full decode doesn't fit, the check falls
  back to aligned region decoding.
- **Samsung cameras take Display P3 photos.** The S23 FE's JPEGs carry a "DCI-P3 D65 Gamut with sRGB
  Transfer" ICC profile. The HEIC writer tags its YUV as BT.601 / sRGB, with no profile (MediaMuxer can't add
  one), so a HEIC copy of such a photo shows **duller colours**. The guard compares raw pixel values, so it
  can't see this. Only copies that decode in the original's colour space are kept (`sameColours`: equal, or the same primaries,
white point and transfer curve within 0.001, since a profile read back from a copy can come out as an unnamed
space). The search mosaic is made in the photo's own colour space.
  **Verified on the S23 FE** (build 764cd46): both HEIC copies of a P3 photo read back as `sRGB IEC61966-2.1`
  and were rejected; the WebP copy kept Display P3 and passed (colour PSNR 49.6 dB). The cost is real: on
  that photo, the HEIC copy was 802 KB against WebP's 1.2 MB, about a third smaller. A HEIF writer that stores an ICC
  profile (`colr` box) would win that back; `MediaMuxer` can't do it. Android's WebP encoder
  writes an ICCP chunk, and `WebpMeta` keeps it; the copy's colour space is checked on every file.
- **Samsung photos end with a trailer after the JPEG** (`SEFT`). Usually it's under 1 KB (`Image_UTC_Data`,
  `Camera_Scene_Info`), and dropping it is fine. A Motion Photo's trailer holds `MotionPhoto_Data` and the
  video; a Portrait photo's holds `DualShot_DepthMap` and a second picture, 3.4 MB on a 5.3 MB test photo.
  Those are refused. Count savings against the main picture, not the whole file.

## Testing a tool on a phone

1. Install the APK from the **Build APK** GitHub Actions run. With the signing secrets set, every build uses the
   same key and installs as an update (see the main README).
2. Open **Algorithm Lab** → **Pick pictures** → **Compress (test copy)**. **Treat as: Screenshot** uses
   `Kind.LOSSLESS`.
3. **Copy log** and read it. The log shows:
   - the encoder list and the `ways of saving:` check result;
   - every quality searched, with its scores;
   - each way's full-size result;
   - the region-decoder note at the unaligned and the aligned spot;
   - the ★ copy a real run would keep.
4. **Compare** opens original vs copy: side by side, flip, or the difference amplified 8×.

## Adding a tool

- Put it in this folder (`com.storageplanner.tools`), self-contained: no imports from `app` or `core`.
- Give it a small public entry point that takes plain inputs (files, enums), returns an outcome with a reason
  when nothing was done, and never touches the original.
- Put plain-Java parts in their own classes and test them in `jvmtest`, added to the CI "Core tests" step.
- Describe it in this README: what it does, how to call it, what the caller must do, and what you learned.
- If it deletes, renames or asks MediaStore to remove files, update `tools/audit_apk.py` deliberately.
