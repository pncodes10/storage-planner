# Storage Planner

An Android app that scans your phone and builds a plan to reach a storage target (by default 70 GB on a 128 GB phone), using the lowest-risk steps first. It runs the same algorithm as the 128 → 70 GB study. It only **reads**: nothing is deleted or changed, and it has no internet permission.

## Install

1. Copy `StoragePlanner-1.6.apk` to the phone and open it. Allow "Install unknown apps" for your file manager or browser if asked. Play Protect may warn about an unknown developer; choose **Install anyway**. It installs over 1.0–1.5 as an update (same signing key); your Keep decisions are kept.
2. Open **Storage Planner**. Then:
   - **Grant all files access.** This is needed to read photos, videos, downloads and WhatsApp media.
   - **Grant usage access** (optional). This adds app sizes, caches, and apps you haven't opened in months. If the toggle is greyed out, go to Settings → Apps → Storage Planner → ⋮ → **Allow restricted settings**, then try again.
3. Check the target (GB of used space) and tap **Analyse my storage**. With tens of thousands of photos this takes a few minutes. You can switch to other apps meanwhile (see below). On Android 13+ the app asks once whether it may show notifications; the scan runs either way.
4. **Your storage plan** (overview) shows:
   - **Mode:** *Reach my target* (the minimum steps needed) or *All safe steps* (everything that loses nothing visible).
   - **Storage bar:** used now, what the plan frees, and a marker at your target.
   - **Recommendations:** lowest risk first, each with a risk label in words (Safe / No loss / Looks identical / Small quality loss / Your call), how much it frees, and how many items are in the plan.
   - **What's using space:** photos, videos, WhatsApp, apps and the rest, largest first.
5. Tap a recommendation to review its items. Every row shows:
   - a thumbnail;
   - the folder, size and date;
   - **why it was flagged**;
   - its **plan status**: in plan, not needed, planned another way, or kept by you;
   - a **Keep / Include** button.

   If you keep something, the plan recalculates and makes up the space elsewhere. Keep decisions are remembered in future scans.
6. Tap any item for its **details**:
   - a large preview;
   - all its information;
   - every flag, with its status;
   - for duplicates, bursts and Sent copies, **the copy that is kept**, with a **Compare side by side** button.

   Buttons open the file in your gallery or viewer, or open an app's settings (where you clear its cache, archive it or uninstall it).
7. **Save report** writes `.txt`, `.csv` and `.json` files to `Download/StoragePlanner/`.

Accessibility:
- TalkBack labels on every row, card and button.
- Headings for navigation.
- Touch targets of at least 48 dp.
- Text that follows your font-size setting.
- Risk always shown in words, not colour alone.
- All text meets the 4.5:1 contrast guideline.
- Dark theme on Android 10+.

Requirements: Android 8.0 or later (API 26+). Archiving apps while keeping their data needs Android 15. On older versions, idle apps can only be suggested for uninstall, which needs your confirmation.

## View files in the app

Tap any file's preview, or the **View** button on its details page, to open the in-app viewer:

| File type | What you get |
|---|---|
| Photos | Full-screen view. Pinch, drag and double-tap to zoom. TalkBack users get Zoom in / Zoom out / Fit to screen actions. |
| Video | Player with the standard controls. |
| Audio and voice notes | Player with play/pause, a seek bar, title, artist and album art. |
| PDFs | Every page, scrollable. |
| Text files | Contents shown as text (`.txt`, `.log`, `.csv`, `.json` and so on). |

Every viewer and details page also has three buttons:
- **Open with…:** every app on the phone that can open that file.
- **Show in folder:** opens your file manager at the file's folder. If no file manager supports this, the folder path is copied instead.
- **Share.**

Playback stops when you leave the viewer or switch apps, and resumes where it was.

## Swipe review

**Review N planned items with swipe cards** (on the overview, and on each recommendation) shows one card per planned item. Items that need your call come first. Each card has:
- a large preview (tap it to view or play);
- the planned action, its risk and how much it frees;
- the reason.

What you can do:
- **Swipe left, or tap ← Keep:** keep the file. It leaves every plan, and other items fill the gap at the end of the deck.
- **Swipe right, or tap Accept →:** accept the decision. It's locked into your plan and marked **Approved by you**.
- **Undo:** reverts the last card.

**Short swipes (new in 1.4).** A drag of about a thumb's width (at most 56 dp, or 15% of the card) is enough, and so is any quick flick in either direction. The KEEP / ACCEPT stamp is fully visible exactly when letting go will count, and the phone gives a light tick at that point. Shorter drags spring back.

TalkBack users get Keep / Accept / View actions on each card. Decisions are remembered across scans; see them under "You approved N items" and "You chose to keep N items" on the overview.

Accepting doesn't delete anything. Storage Planner stays read-only: act on approved items with Open with, Show in folder, or an app's settings.

## Choose which copies to delete (new in 1.5)

Some cards are about a file that has copies: exact duplicates, near-identical shots of the same moment, or
WhatsApp Sent copies of camera photos. These cards show every copy side by side:
- two copies are shown side by side;
- three or four copies fill a 2 × 2 grid;
- with more than four, the last cell says "+N more", and tapping it shows them all.

On each photo:

| Where | What it is |
|---|---|
| Top left | A **delete tick box**. Ticked = this copy will be deleted. |
| Top right | A **✓ KEEP** (green) or **✕ DELETE** (red) badge. It changes as soon as you tick or untick. |
| Underneath | What the photo check found (★ Best shot, Eyes open / Eyes closed, Sharpest, Best light), plus resolution, size, date and folder. |

The algorithm ticks every copy except the one it suggests keeping (★). You can change that:

| What you tick | What happens when you swipe right (or tap **Delete N →**) |
|---|---|
| The suggested copies | Those copies are approved for deletion. |
| One you prefer to delete instead | The ★ one is deleted and the other one is kept. |
| All of them | All are deleted. The card warns you first that nothing of that photo will stay. |
| None | All are kept, the same as swiping left (**← Keep all**). |

- The **frees** figure, the button label and the stamp follow your ticks.
- **Undo** puts the whole group back, ticks included.
- TalkBack reads every copy with its state. Each card has actions to tick or untick each photo, apply, keep all, or compare.
- Like everything else, this only records your approval. The app deletes nothing itself.

**Tap a photo** (or use **Compare side by side and choose** on a file's details page) for the full-screen compare:
- **Two at a time.** Two copies are shown side by side or stacked, each with its tick box, badge, findings and caption.
- **Face markers.** Faces are outlined: green for **Eyes open**, red for **Eyes closed**.
- **Zoom together.** Pinch, drag or double-tap one photo and the other follows to the same spot.
- **Groups of three or more.** A strip of all copies appears below. Tap one to compare it on the right, long-press to put it on the left, and tick it right there.
- **Why the ★ one is suggested.** Compared with the other photo shown, for example "Eyes open, Face sharper".
- **Apply.** When you came from the swipe cards, **Apply and next** applies your ticks and moves to the next card.

## How the better photo is picked (new in 1.5)

For near-identical shots, every photo in the group is checked on the phone during the analysis
("Comparing similar photos"):

| Check | How |
|---|---|
| **Faces** | Android's built-in, on-device face detector (`android.media.FaceDetector`). It finds frontal faces and reports where the eyes are. |
| **Eyes open or closed** | A small neural network built into the app looks at each eye. It was trained on real faces: Closed Eyes In The Wild for closed eyes, plus aligned open-eye faces. On 716 faces it never saw, it is right 97% of the time. It is still 98% right when the eye positions are off by 6%, and 91% when they are off by 12%. |
| **Faces visible** | A shot where someone turned away has fewer faces. Each missing face counts like a blink. |
| **Focus** | Sharpness of the faces, then of the whole photo. It is measured so that a brighter or darker copy of the same photo doesn't look "sharper". |
| **Lighting** | Blown-out highlights cost the most because they can't be recovered. Then crushed shadows, a photo that is far too dark or too bright, and a face that is too dark or washed out. |

**Ranking.** Each person blinking or missing costs 3 points. Focus is worth up to 1.5 and light up to 1, so a
blink outweighs any focus or light difference between near-identical shots. The reason is shown on
the card, for example "Near-duplicate of a better shot, IMG_2.jpg: eyes open, face sharper".

**Tests on real photos and real faces** (`PhotoQualityTest`):

| Test | Result |
|---|---|
| Original vs. its blurred copy | 52 of 52 |
| Original vs. its over-exposed copy | 32 of 34 |
| Original vs. its under-exposed copy | 34 of 34 |
| Same scene with 1–3 real people, one blinking in one shot: open-eyes shot kept | 96% |
| Same, when the blinking shot is the sharper one | 93% |
| Three shots (0, 1 or 2 people blinking): best shot kept | 84 of 86 |
| End to end (scan, plan and reason) on a virtual phone with 30 such bursts | the right shot kept every time |

Photos analysed by version 1.4 or earlier keep working. Their shots are ranked by sharpness until you tap
**Analyse** again.

Not judged: smiles, head turns that keep both eyes visible, and composition.

## Your pictures: categories, search and clean-up (new in 1.6)

The overview has a **Your pictures** section. It shows every category with its count and size, plus a
**search box** for the text found in pictures (try a shop name, "pnr" or "invoice").

| Category | How it is recognised | Suggested for deletion? |
|---|---|---|
| People | Android's on-device face detector | No |
| Photos without faces | Camera photos with no faces and no text | No |
| QR codes & barcodes | The code is decoded (ZXing). The card says what it is for: "UPI payment code for …", "Wi-Fi network …", "Link to …" | No |
| Receipts | Text: totals, cash, change, GST lines, many prices; a tall, narrow shape | No (browse and search) |
| Bills & invoices | Text: invoice, bill to, due date, GSTIN, amount due; a page shape | No (browse and search) |
| Documents & text | Mostly text (pages, notes, printouts) | No |
| Payment screenshots | Text: paid to, UPI transaction ID, Google Pay / PhonePe / Paytm … | Yes, if older than 3 months |
| One-time codes | Text: OTP, verification code, "do not share", plus a 4–8 digit code | Yes, if older than 1 day |
| Tickets & passes | Text: PNR, train no., boarding pass, gate, seat, booking ID; the travel date is read | Yes, once the date has passed by a week (or 6 months after saving if no date is found) |
| Chat screenshots | Screenshot with chat layout and many timestamps | No |
| Greetings & forwards | A greeting ("Good morning", "Happy Diwali" …) read on an image received in a chat | Yes, if older than a week |
| Accidental shots | Almost completely dark, featureless, or shaken so badly that nothing is sharp | Yes |
| ID documents (sensitive) | Text: Government of India, Aadhaar, PAN, passport, licence; Aadhaar and PAN number patterns | **Never.** Other suggestions are removed too; only an identical extra copy may go |
| Other screenshots, Graphics | Everything else from screenshots, downloads and chat apps | No |

**Browsing.**
- Tap a category for a photo grid (newest first). It has an age filter: any, older than a month, three months or a year.
- Tap a picture to view it. Tap **Select** (or long-press a picture) to pick several, then:
  - **Approve deletion** adds them to your plan as "Approved by you";
  - **Keep** leaves them out of every plan;
  - **Undo** reverses either.
- ID documents need a second tap before they can be approved.
- Each picture's details page shows its category, any code, any date found, and the text that was read.

As everywhere else, the app deletes nothing itself.

**How it runs.** Everything happens on the phone, during **Analyse**:
1. **Sorting pictures.** Every picture is measured:
   - rows of character-shaped marks (text);
   - bright colourless areas (paper);
   - perfectly flat areas (screens);
   - darkness and blur;
   - QR codes and barcodes;
   - faces.

   Camera photos are measured from the same decode that the duplicate and burst checks already use.
2. **Reading text in pictures.** Screenshots, downloads, chat images, and photos that show text or paper are read
   with **Tesseract**, the open-source text reader. Screenshots and the newest pictures go first. Reading is
   limited to 20 minutes per scan; anything left says "text not read yet" and is read at the next **Analyse**.
3. **Next scans.** Pictures that have not changed (same size and date) keep their category, text and
   measurements, so later scans skip all of this.

The start screen has a switch to turn sorting off.

**The text reader is offline.** It is Tesseract compiled to WebAssembly (tesseract.js 7.0.0, Apache-2.0)
with the English model from tessdata_fast. It runs in an invisible WebView that can only load files
packed inside the app:
- every other address gets an empty answer;
- the app still has **no internet permission**;
- the audit checks that the reader's files are the published releases, byte for byte (pinned SHA-256 hashes);
- only the text-reader class uses the WebView, and it loads only the bundled page.

The app grew from about 0.1 MB to about 5 MB because of these files.

**Accuracy.** `CategoryTest` runs the real scan on a 399-picture test phone:

| Category | Right | Test pictures |
|---|---|---|
| Receipts | 57 / 60 (95%) | Real: SROIE receipt scans |
| Bills & invoices | 21 / 33 (64%) | Real invoices (invoice2data), as PDF pages and as simulated phone photos. Photos of a full page are often too small to read |
| Documents & text | 25 / 35 (71%) | Real test pages (Tesseract, OCRmyPDF), scanned and as simulated photos |
| Photos without faces | 47 / 47 | Real phone photos |
| People | 31 / 31 | Real faces on real photos |
| QR codes | 36 / 36 | Synthetic codes, on screens and photographed on real backgrounds |
| Payments, one-time codes, tickets, chats, other screenshots | 96 / 96 | **Synthetic** screenshots rendered at phone size |
| Accidental shots | 18 / 24 (75%) | Real photos made dark, smeared or shaken |
| Greetings & forwards | 13 / 36 (36%) | **Synthetic**. Decorative letters on busy pictures are hard for Tesseract; missed ones stay under "Photos" |

- **Overall:** 346 of 399 (87%).
- **No wrong deletion suggestions on the test phone.** No everyday photo was put in a deletable category, and no greeting, OTP, payment or ticket was wrongly assigned.
- **Little extra reading.** Only 8 of 78 everyday photos were sent to the text reader.
- **Text rules on their own** (`TextRulesTest`, 21 cases): each kind, ID documents (Aadhaar, PAN, passport) recognised before anything else, an article that mentions Aadhaar *not* treated as an ID, and date reading.
- **The app's own reader page** (`tools/test_ocr_page.py`, run in Chromium with the app's offline rules): read a real receipt and an OTP screenshot correctly, and made no outside requests.

## Runs in the background

- **Foreground service.** The analysis runs in a foreground service, so it keeps going while you use other apps or the screen is off. A notification shows the stage and progress, with a **Cancel** button; tapping it opens the app.
- **Notification when done.** When the plan is ready you get *"Your storage plan is ready: frees X GB"* (or *Analysis failed / cancelled*). If the app is open at that moment, it just shows the plan.
- **Results are saved.** Finished results are stored in the app's private storage (`last_results.bin`, written atomically, so a crash mid-write never damages it). If Android closes the app while it is in the background, reopening it shows *"Loading your last results..."* and then the last plan, without scanning again. Plans are rebuilt from the saved scan, so they are identical.
- **Back where you were.** The screen you were on (plan, a recommendation, a category, an item's details) is restored. Items that no longer exist are skipped.
- **Interrupted scans.** If Android stops the app in the middle of a scan anyway (rare, e.g. "force stop" or a very aggressive battery saver), the start screen says: *"The last analysis was interrupted. Tap Analyse to run it again."* Earlier results stay available under **Open last results**.

## Permissions

The app also contains one private, read-only file provider. It isn't exported, and it only hands one file at a time to an app you pick under Open with or Share, through a temporary read grant.

- **All files access** (`MANAGE_EXTERNAL_STORAGE`; `READ_EXTERNAL_STORAGE` up to Android 12): read photos, videos, downloads and WhatsApp media. Read only.
- **`WRITE_EXTERNAL_STORAGE`** (Android 8-10 only): save the report to Download.
- **Usage access** (`PACKAGE_USAGE_STATS`, optional) and **`QUERY_ALL_PACKAGES`**: app sizes, caches and last-used dates for every installed app.
- **`FOREGROUND_SERVICE`** and **`FOREGROUND_SERVICE_DATA_SYNC`** (new in 1.2): run the analysis as a foreground service of type *dataSync* (local file processing) so it is not stopped when you switch apps.
- **`POST_NOTIFICATIONS`** (new in 1.2, asked on Android 13+): show the progress and "plan is ready" notifications. Optional.
- **`WAKE_LOCK`** (new in 1.2): keep the processor awake during the scan when the screen turns off (released when done, at most 60 minutes).
- **No `INTERNET`.** The app cannot send anything off the phone.

## What the plan contains

| Tier | Meaning | Examples |
|---|---|---|
| R0 | Rebuildable or junk | App caches, thumbnails, temp files, leftovers of uninstalled apps, expired statuses |
| R1 | Nothing lost | Exact duplicates, installers for installed apps, WhatsApp Sent copies of photos still in your camera roll, trash, old WhatsApp backups, Telegram media (re-downloadable), archiving idle apps (Android 15), lossless screenshots |
| R2 | Visually identical | Photos re-encoded to HEIC/WebP, H.264 videos re-encoded to HEVC |
| R3 | Small quality trade-off | Music above 256 kbps to 128 kbps |
| R4 | Needs your confirmation | Burst near-duplicates (keeps the sharpest), possibly blurry photos, unused games/apps, old downloads |

The planner uses the lowest tier that can reach the target. Within that tier it takes the cheapest bytes first, then drops anything not needed.

**Exact vs estimated.** Deletions, caches and app sizes are exact. Re-encoding savings are estimates:
- **Photos and chat images:** if "Measure compression" is on, these are **measured on a sample of your own files**. The app re-encodes each sampled photo to WebP at the lowest quality that still passes the study's guard (SSIM ≥ 0.98 and PSNR ≥ 38 dB) and records the real size. HEIC usually saves more, so this is conservative.
- **Screenshots:** measured with lossless WebP.
- **Video:** a conservative 45% for high-bitrate H.264.

## Build it yourself

**Android Studio:** open this folder, let it sync (AGP 8.5, JDK 17), then **Build → Build APK(s)**. The project has no dependencies beyond the Android framework.

**Without Android Studio:** `tools/build_offline.sh` builds it from javac, D8, aapt2 and a small Python v2 signer. The supplied APK was built this way, because Google's Maven was not reachable from the build machine. An APK you build yourself is signed with your own key, so uninstall the supplied one first.

## Layout

- `app/src/main/java/com/storageplanner/core/`: the algorithm in pure Java, with no Android imports.
  - **Scanner:** walk, duplicate check, media features.
  - **Classifier:** path rules.
  - **ImageFeatures:** perceptual hash and sharpness.
  - **PhotoQuality / EyeState:** which near-identical shot is better (faces, eyes, focus, light).
  - **Sorter / ImageSignals / Codes / TextRules / PhotoCategory:** picture categories (1.6).
- `app/src/main/java/com/google/zxing/`: ZXing 3.5.3 decoding code (Apache-2.0, unmodified; encoders left out).
- `app/src/main/assets/ocr/`: the offline text reader (tesseract.js 7.0.0, tesseract.js-core 7.0.0, tessdata_fast English; Apache-2.0).
  - **UnitBuilder:** options.
  - **Planner:** tiered planner.
  - **Report:** output.
  - **ResultStore:** versioned binary format for saved results (with checksum).
- `app/src/main/java/com/storageplanner/app/`: the Android layer. Screens: SetupScreen, OverviewScreen, ListScreen (review lists), DetailScreen, DeckScreen (swipe cards), CompareScreen and ViewerScreen; Group holds the copies of one photo and the user's ticks; Tick is the delete box; PicturesScreen is the category grid; OcrEngine runs the offline text reader; Session holds results and Keep decisions; Thumbs, MeterView, MediaOpen.
  - **AndroidPlatform:** reads files and system stats.
  - **Calibrator:** on-device measurement.
  - **Ssim:** matches scikit-image to 1e-14.
  - **MainActivity:** the UI, back stack and its restore after process death.
  - **AnalysisService / AnalysisRunner / AnalysisState:** the foreground service, the pipeline and the live progress the screen observes.
  - **ResultFiles:** saves and loads results in app-private storage (AtomicFile).
- `jvmtest/`: runs the unchanged core on a desktop against the virtual phone from the study, plus classifier, planner, review, saved-results (ResultStoreTest), group-tick (GroupDecisionTest), eye-model (EyeStateTest), photo-quality (PhotoQualityTest), picture-category (CategoryTest, TextRulesTest) and reader-bridge (OcrBridgeTest) tests.
- `tools/eyemodel/`: how the eye model was trained and checked.
- `tools/test_ocr_page.py`: runs the app's offline text-reader page in Chromium the way the app serves it.
- `tools/ocr_assets.sha256`: pinned hashes of the text-reader files (checked by the audit).
- `tools/`:
  - **apkbuild.py:** packager and v2 signer.
  - **strip_methodparams.py:** strips an attribute old D8 can't read.
  - **audit_apk.py:** independent APK audit.
  - **build_offline.sh:** the offline build.

## Known limits

- **Not run on a real device.** It was compiled against API 34 and the algorithm was tested on a desktop. The Android-specific code (permissions, decoding, system stats, the background service and notifications) has not run on a phone, so first-run issues are possible.
- **Background limits.** Some manufacturers (e.g. aggressive battery savers) can still stop a foreground service; the app then reports the interrupted scan. Results are only saved once a scan finishes.
- **Android/data is unreadable on Android 11+.** App data there is counted through system stats instead, so leftover files of uninstalled apps in that folder can't be found.
- **Face detection is basic.** Android's built-in detector finds frontal, upright faces. Faces in profile, very small faces (eyes under about 16 px apart in a 1280 px photo) and faces behind sunglasses aren't checked, and those shots are judged on focus and light only. The face and eye code has not run on a real phone yet. The eye model was tested on a desktop with eye positions from another detector plus added error.
- **Picture sorting is new and untested on a phone.**
  - The text reader runs in Android's WebView, which differs between phones; if it cannot start, pictures are sorted without reading text.
  - The first scan with sorting on takes noticeably longer. Estimated from desktop timings, not measured on a phone: about a quarter of a second per picture, plus one to three seconds for each picture whose text is read.
  - Only English letters are read, so Hindi and other scripts are not.
  - Greeting images are often missed.
- **Cache clearing is manual.** Android doesn't let one app clear another app's cache, so you do it from each app's settings page.
