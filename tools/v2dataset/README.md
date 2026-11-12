# v2 storage-analysis evaluation dataset

A labelled, phone-shaped file tree for evaluating the v2 storage-analysis algorithm. The algorithm must
find files that are safe to delete or compress, and it must never recommend deleting important unique
files.

## Rebuild

```sh
pip install -r tools/v2dataset/requirements.txt
python3 tools/v2dataset/build.py --out /home/user/sp-dataset --cache /home/user/sp-dataset-cache
python3 tools/v2dataset/verify.py --root /home/user/sp-dataset      # exit 0 = pass
```

* `--quick` gives a smaller smoke-test build in about 5 minutes. A full build takes about 15 minutes and
  most of that time goes on video encoding.
* `build.py` wipes `--out` before it builds. It only does this when the directory is empty or contains the
  `.v2dataset` marker from an earlier build. Runs are deterministic: seeds are fixed, ZIP/OOXML timestamps
  are fixed, reportlab runs with `invariant=1`, pypdf encryption randomness is seeded, ffmpeg runs
  bit-exact with one thread, and mtimes come from the manifest.
* Downloads are cached in `--cache/dl/`. `--cache/sources.lock.json` records the sha256 of every
  download, so you can tell if an upstream file changes.
* The dataset itself is **not** in git. `manifest.sample.json` (a trimmed sample, under 500 KB) and
  `scenarios.json` are copied here on every build.

## Files

| file | purpose |
|---|---|
| `build.py` | orchestrator; writes `manifest.json`, `scenarios.json` and the sidecar files |
| `sources.py` | catalogue of real sources (URL and licence) plus a cached downloader |
| `gen_photos.py` | photo-derived files: camera originals with EXIF, WhatsApp copies, bursts, meaningful edits, collages, thumbnails, conversions, rotations, hard negatives, blurry family photos, flowers, video frames, photos of screens |
| `gen_synth.py` | synthetic images: chat/payment/settings/OTP/ticket/feed screenshots, photographed receipts, ID-like cards, certificates, festival greetings (English + Devanagari), memes, ads, QR codes |
| `gen_docs.py` | real sample PDFs, synthetic bank statements, old financial papers, encrypted PDFs, scanned PDFs, DOCX/XLSX/PPTX, CSV/TXT/logs |
| `gen_media.py` | videos (camera, duplicate, WhatsApp re-encode, screen recording, long static videos with a brief event, truncated video) and audio (voice notes, recordings, call recordings, WAV, silence, music, tones) |
| `gen_apps.py` | APK zips and an installer stand-in; `installed_apps.json` |
| `gen_misc.py` | archives, backups, junk/cache, corrupted files, symlink loop, long and Devanagari names, S12 mutations, runtime harness selection |
| `scenarios.py` | scenario text and the tier and relationship semantics |
| `verify.py` | checks the manifest against the disk |
| `mutate.py` | applies `mutations.json` (S12); also copied into the dataset root |

## Ground truth (`manifest.json`)

There is one entry per file with these fields:

* `path`, `size`, `sha256`, `mtime`
* `category`, `subcategory`, `scenarios`
* `relationships` (`[{type, target, transform?, member?}]`). Each relationship is stored on the derived or
  redundant file and points at the target.
* `importance`, `expected_tier`, `acceptable_tiers`, `must_never_delete`
* `synthetic`, `source` (`{id, url, licence, transform}`)
* `hard_negative_group` (optional). Files in the same group look alike but are different and must never be
  treated as duplicates.

Symlinks carry `symlink_target` instead of a hash.

The tier and relationship definitions are embedded in the manifest. Tiers are SAFE, NO_LOSS, YOUR_CALL,
REVIEW, PROTECTED and KEEP (KEEP means no recommendation). A `must_never_delete=true` file placed in SAFE,
NO_LOSS or YOUR_CALL counts as a critical failure.

Sidecar files:

| sidecar | contents |
|---|---|
| `installed_apps.json` | installed state for S06/S07 |
| `pdf_passwords.json` | S09 passwords, for the test harness only |
| `backup_manifest.json` | S10: what each backup declares versus what it actually contains |
| `mutations.json` + `mutate.py` | S12 changes made between scan and execution |

## Scenarios

`scenarios.json` covers S01–S15. Each scenario has its expected behaviour and lists its files.
S13–S15 are runtime scenarios and list `harness_files`.

To run S12:

1. Scan the dataset.
2. Run `python3 mutate.py --root <dataset>`.
3. Execute the plan.

Rebuild afterwards to reset the dataset. `verify.py` fails after `mutate.py` has run, and that is intended.

## Real sources (all from reachable hosts)

Every entry records its exact URL and licence note.

| source | licence | used for |
|---|---|---|
| `gs://cloud-samples-data` longcap100 (100 high-resolution photos) | believed to be DOCCI, CC BY 4.0 | camera originals |
| `gs://cloud-samples-data` other files | Google public sample data, evaluation use only (verify before redistributing) | generative-ai/image (people, pets, food, products, scenes), vision/* samples, dlvm images, vegetable images, Cymbal Pets product renders, Document AI samples (passport/licence specimens, receipts, pay stubs, W-2/1099, bank statement, synthetic invoices), traffic clip, evaluation videos |
| Cosmos Laundromat clips | CC BY 4.0 (Blender) | video |
| TensorFlow `flower_photos.tgz` | CC BY 2.0, per-image attribution | photos |
| `gs://mediapipe-assets` | Apache-2.0 | people, pets, food images; speech WAVs |
| `gs://learning-datasets` MLColab images | | cats and dogs |
| ageitgey/face_recognition examples | MIT; US-government portraits are public domain | faces |
| opencv/opencv `samples/data` | Apache-2.0/BSD | images; `vtest.avi`, `Megamind.avi` |
| zxing blackbox `qrcode-*` | Apache-2.0 | real photos of QR codes |
| Noto Sans, Noto Sans Devanagari, Noto Sans Mono, Anton fonts | OFL | text in synthetic images |

All personal data in synthetic files is fake. IDs and certificates are marked "SPECIMEN".

**Private corpus.** A second, private corpus of the owner's real phone photos exists read-only at
`/home/user/sp-dataset-drive/files/`. This builder does not copy, derive from or reference it.
