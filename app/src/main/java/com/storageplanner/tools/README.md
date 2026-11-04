# Tools

This folder holds **tools**: code that changes files on the phone, so that a future **action taker** can carry
out what the plan recommends. Today it holds the image compression tools: HEIC, WebP, and lossless WebP.

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
  It never touches the original. Original vs compressed is shown by `app/CompressCompareScreen.java`.
- **A tool never deletes or changes an original.** It writes a new file next to it. Removing originals is the
  action taker's job (see [Removing originals](#removing-originals-not-built-yet)).
- **The APK audit enforces this** (`tools/audit_apk.py`, run by CI):
  - only `tools/ImageCompressor` may call `File.delete` / `File.renameTo` (on its own work files);
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

The plain-Java files are tested on a desktop by `jvmtest/com/storageplanner/test/CompressionTest.java`. CI
runs it before every build. That test also checks `tools/Ssim` == `app/Ssim`: **change both or neither.**

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

## How it works

### Photos (`Kind.PHOTO`)

**First, refuse** what a copy can't fully keep. `compress()` does this before anything else; the Lab compresses
anyway and says a real run would not. Refused: files over 25 MP, files that are already HEIC/WebP, and what
`Extras` finds:
- XMP that names a Motion Photo, gain map or depth map;
- an MPF segment;
- a second JPEG, or "MotionPhoto", after the picture;
- 64 KB or more of data after the picture.

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
  can't see this. Only copies that decode in the original's colour space are kept. Android's WebP encoder
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
