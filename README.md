# Storage Planner

An Android app that scans your phone and builds a plan to reach a storage target (by default 70 GB on a 128 GB phone), using the lowest-risk steps first. It runs the same algorithm as the 128 → 70 GB study. It only **reads**: nothing is deleted or changed, and it has no internet permission. The only files it writes are the report, the Lab log and the Lab's test copies, all in `Download/StoragePlanner/`.

## Install

1. Copy `StoragePlanner-1.8.1.apk` to the phone and open it. Allow "Install unknown apps" for your file manager or browser if asked. Play Protect may warn about an unknown developer; choose **Install anyway**. It installs over 1.0–1.8 as an update (same signing key); your Keep decisions are kept.
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

## Tools (for the action taker)

The plan only **decides** what to do. A later phase, the **action taker**, will carry the plan out using
**tools**: code that changes files. The tools live in their own folder,
`app/src/main/java/com/storageplanner/tools/`. The planner never calls them.

The first tools compress images, and they are tested on a Galaxy S23 FE:
- **Photos and chat images** become HEIC or WebP, whichever passes the study's "looks identical" check
  (SSIM ≥ 0.98, PSNR ≥ 38 dB, no colour shift) and is smallest. EXIF is kept.
- **Screenshots** become pixel-identical lossless WebP.

Today only the Algorithm Lab uses them (**Compress (test copy)**, see below), and it never touches originals.

**Read [`app/src/main/java/com/storageplanner/tools/README.md`](app/src/main/java/com/storageplanner/tools/README.md)
before using or changing a tool.** It covers how to call them, what the caller must do (for example, removing
originals only through Android's trash with the user's confirmation), how they work, and the lessons from
testing on a phone.

Video compression: the research, desktop measurements and the five quality levels are in
[`docs/video-compression-research.md`](docs/video-compression-research.md) (benchmark script: `tools/video_bench.py`).
Zip archives: `ZipCompressor` puts any files into one checked `.zip` at four levels (see the
[tools README](app/src/main/java/com/storageplanner/tools/README.md#zip-archives-zipcompressor)).

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
| **Faces** | YuNet (OpenCV Zoo, MIT licence), a small face-detection network built into the app and run on the phone in plain Java (since 1.8; before that Android's `android.media.FaceDetector`). It finds faces down to about 10 px, tilted or partly turned, and gives the position of each eye. A face counts when its score is at least 0.7; faces much smaller than the main one (bystanders) are not counted. |
| **Eyes open or closed** | A small neural network built into the app looks at each eye. It was trained on real faces: Closed Eyes In The Wild for closed eyes, plus aligned open-eye faces. On 716 faces it never saw, it is right 98.5% of the time when it looks at each eye where the detector found it (97% from the old detector's eye midpoint). It is still 98% right when the eye positions are off by 6%, and 91% when they are off by 12%. |
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
| End to end (scan, plan and reason) on a virtual phone with 30 such bursts, faces found by the real detector | 28 bursts found; the right shot kept in 27 |

Photos analysed by version 1.4 or earlier keep working. Their shots are ranked by sharpness until you tap
**Analyse** again.

Not judged: smiles, head turns that keep both eyes visible, and composition.

## Your pictures: categories, search and clean-up (new in 1.6)

The overview has a **Your pictures** section. It shows every category with its count and size, plus a
**search box** for the text found in pictures (try a shop name, "pnr" or "invoice").

| Category | How it is recognised | Suggested for deletion? |
|---|---|---|
| People | The face detector (YuNet, on the phone); 31 of 31 test photos with people | No |
| Photos without faces | Camera photos with no faces and no text | No |
| QR codes & barcodes | The code is decoded (ZXing). The card says what it is for: "UPI payment code for …", "Wi-Fi network …", "Link to …" | No |
| Receipts | Text: totals, cash, change, GST lines, many prices; a tall, narrow shape | No (browse and search) |
| Bills & invoices | Text: invoice, bill to, due date, GSTIN, amount due; a page shape | No (browse and search) |
| Documents & text | Mostly text (pages, notes, printouts), or a photo of a handwritten or printed page that is recognised by its look alone (see below) | No |
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

**Tags on every file.** A file's details page now also has a **Tags** section: a plain list of facts about the
file, such as where it came from (camera photo, screenshot, chat image received or sent, download, ...), its kind,
size, age (days since it was last *modified*; Android does not say when you last opened it), picture size, category,
whether text was read, faces, whether it has a QR code or barcode, and, for pictures, brightness, how dark, how
colourful, how much of it is flat graphics, how much is paper (judged against the picture's own light), how much is
ink (pen or print on that paper), whether it looks like a page of paper, and how much text. Yes/no facts (exact duplicate, extra shot of a
burst, best shot of a burst, sent copy of a camera photo, installer of an app that is already installed, ID document,
someone has eyes closed, and so on) are listed only when they are true. Anything that was not measured is left out,
never shown as zero: for example faces are not looked for in screenshots. **Blur** (0 to 100, higher is blurrier) is
compared with your own camera photos, so it needs at least ten of them; it is not a measure against other people's
photos. Nothing the planner decides has changed: tags only show what it knows. They prepare the ground for rules you
will be able to write yourself later ("chat image, greeting, older than 3 days, no faces: delete"). The picture
measurements are saved with the results (results format 5); results saved by older versions still open, and the
tags that need them appear after the next scan. `paper_share` changed meaning in this step: it is now the share of
the picture that is plain, colourless and as bright as the picture's own white (so a page in dim light counts), not
the share above a fixed brightness. `ink_share` and the yes/no `looks_like_page` are new; none of the built-in rules uses them. The Algorithm Lab's **Picture category** run prints the same tags.

As everywhere else, the app deletes nothing itself.

**How it runs.** Everything happens on the phone, during **Analyse**:
1. **Sorting pictures.** Every picture is measured:
   - rows of character-shaped marks (text);
   - bright colourless areas (paper);
   - whether the whole picture is a page of paper (see **Pages of paper** below);
   - perfectly flat areas (screens);
   - darkness and blur;
   - QR codes and barcodes;
   - faces.

   Camera photos are measured from the same decode that the duplicate and burst checks already use.
2. **Reading text in pictures.** Screenshots, downloads, chat images, and photos that show text or paper are read
   with **Tesseract**, the open-source text reader. Screenshots and the newest pictures go first. Reading is
   limited to 20 minutes per scan; anything left says "text not read yet" and is read at the next **Analyse**.
3. **Next scans.** Pictures that have not changed (same size and date) keep their category, text and
   measurements, so later scans skip all of this. The exception is a picture that was sorted by an older version
   of the sorting rules: each sorted picture is saved with the version that sorted it, and when the version goes up,
   the picture is measured and sorted again once, at the next **Analyse**, even though the file is unchanged. In that
   one pass a verdict that came from the text or a code (receipt, ticket, ID, QR ...) is kept, text already read is not
   read again, and only pictures in Photos, People or Accidental shots can change category. This is how the page check
   below reaches pictures that were sorted before it existed. A picture that cannot be decoded at that moment keeps its
   old result.

**Pages of paper (handwritten notes, also in dim light).** Text found by its shape works for printed letters in
even rows; it does not work for handwriting (joined words, a headline line), and the text reader here is English only.
So a picture is also recognised as a page without reading anything. All of these must hold:
- **Paper, relative to the picture's own light.** The white level is the brightness of the brightest tenth of the
  picture. Paper is every colourless pixel within 25 levels of it. It must cover at least half the picture, and the
  white level must be at least 100, so a dark room is not paper. (The old paper-like share needed brightness 165, which a
  page in a dim room never reaches; it is still measured and still used by the old rules.)
- **Smooth.** At least half of that paper has almost no local variation (standard deviation under 8 in a 9 x 9 window
  at 1024 px). Concrete, gravel and cloth fail here.
- **Almost no colour.** Colourfulness under 15.
- **Marks on it.** Ink is a pixel clearly darker (25 levels) than the average of its surroundings (33 x 33 window),
  where those surroundings are paper. It must be between 1% and 15% of the picture and be present in at least 3 of 8
  horizontal bands, so one dark object on a white wall does not count.

A match makes the picture a **Document** ("Looks like a handwritten or printed page") and makes it worth reading, so a
printed page in dim light also gets its text read. It comes after the text, code, screenshot and accidental-shot
checks, so ID documents, receipts and so on keep their priority; a page is also looked at for faces, and a face in it
means someone holding it up, which stays a photo of a person. A page can only ever move a picture **into** Documents,
which is never suggested for deletion; it adds no delete suggestion. Known misses: a page that covers less than half
the frame, coloured paper, and a page under strongly coloured light. Where the numbers come from is in the Lab:
**Picture category** prints the white level, paper share, smoothness, ink share and bands, and which check failed.

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

- **Overall:** 346 of 399 (87%). These numbers are from before the page check was added. The 399-picture corpus was not available when it was added, so it was not re-run and there are no accuracy figures for it; the page check is covered by `PageTest` (synthetic pages, walls, sky, scenes, a dark room, a lone dark object, rough surfaces, and re-sorting) and by one real handwritten photo, checked by hand. The same goes for the figure on extra reading below.
- **No wrong deletion suggestions on the test phone.** No everyday photo was put in a deletable category, and no greeting, OTP, payment or ticket was wrongly assigned.
- **Little extra reading.** Only 8 of 78 everyday photos were sent to the text reader.
- **Text rules on their own** (`TextRulesTest`, 21 cases): each kind, ID documents (Aadhaar, PAN, passport) recognised before anything else, an article that mentions Aadhaar *not* treated as an ID, and date reading.
- **The app's own reader page** (`tools/test_ocr_page.py`, run in Chromium with the app's offline rules): read a real receipt and an OTP screenshot correctly, and made no outside requests.

## Face detector replaced (new in 1.8)

Found with the Algorithm Lab on a night photo (IMG-20261015-WA0005): the Lab said "Eyes closed" and outlined a
dark patch of background. Android's built-in detector had reported a "face" there, missed the real face, and
gives every face the same confidence (about 0.51), so false faces could not be filtered out. The eye model
then judged the dark patch and called it closed eyes. The same kind of false face showed up on a dress and a
building in another photo.

1.8 uses **YuNet** instead (OpenCV Zoo `face_detection_yunet_2023mar`, MIT licence, 53,000 weights, 216 KB):

- It runs in plain Java on the phone (`core/FaceNet.java`), with no native code and nothing sent anywhere.
  `FaceNetTest` checks it against OpenCV's own `FaceDetectorYN`: 29 faces in 19 pictures, identical to 0.001 px.
- It gives a real score. At 0.7, 1 false face in 271 test pictures without people (Android's detector cannot be filtered at all), and no real face lost among 57.
- It gives both eye positions, so the eye check looks at each eye where it is, also when the head is tilted.
- **Second look.** A face that is small in the 640 px copy can score just under 0.7 (IMG-20261015-WA0005 on a phone: 0.69). Every candidate scoring 0.45 to 0.7 is looked at again on a crop two face-widths wide, taken from the full picture; that score replaces the first. It lifted that face to 0.85, and on the 368 test pictures without people it added no false face (it did find a real woman in sunglasses in one).
- Speed: about 0.2 s per picture on a desktop for the best-shot check (640 px copy), less for sorting (512 px copy). On a phone expect 2 to 4 times that, so the first scan takes longer.
- In the Lab, **Face detection** also lists what Android's old detector finds in the same picture, for comparison.

## Algorithm Lab: debug tool (new in 1.7)

**Algorithm Lab (debug)** is on the start screen and at the bottom of the plan. On the compare screen,
**Debug this choice in the Algorithm Lab** opens the Lab with every copy of that group already loaded.

**Video compression** (the first button in the Lab) opens the **Video Lab**. Pick one video, then compress test
copies at **All five levels** or one level (Looks identical, High, Balanced, Space saver, Maximum; see the
[tools README](app/src/main/java/com/storageplanner/tools/README.md#videos-videocompressor)). It logs the encoder,
every sample bitrate tried with its worst SSIM / PSNR, the size before and after and the time taken, and says
whether a real run would keep each copy. **Play copy**, **Play the original** and **Compare worst frame** show the
result. Copies go to `Download/StoragePlanner/compressed/`; the original is never touched. Keep the app open while
it runs.

**Zip compression** (the second button) opens the **Zip Lab**. Pick any files (more than one at a time), then zip
them at **All four levels** or one (Store only, Fast, Balanced, Maximum). It logs each file's size before and after,
whether it was compressed or stored as it is, and the archive's total saving and time. **Share this zip** sends an
archive. Archives go to `Download/StoragePlanner/compressed/zip-<time>/`; the picked files are only read.

**Motion Photos** (the third button) opens the **Motion Photo Lab**. A Motion Photo stores a 1–3 second video after
the photo, often a third to half of the file. Pick photos and tap **Remove the videos (test copies)**: each copy
keeps the photo byte for byte (same pixels, EXIF, HDR gain map) and leaves out only the clip. The Lab checks that
the copy decodes to the same pixels, and saves the removed clip next to it so you can play it. **Survey DCIM**
reads your camera folder and says how much removing every clip would free. Works with Google (Pixel and others,
JPEG and HEIC) and Samsung Motion Photos ([how](app/src/main/java/com/storageplanner/tools/README.md#motion-photos-motionphoto)).
Copies go to `Download/StoragePlanner/compressed/motion-<time>/`; the originals are only read.

1. **Pick pictures** with the system file picker: one or more, up to 20. Tap a thumbnail to select it.
   **Treat as** sets how the picture is sorted: camera photo, screenshot, WhatsApp image or download.
2. **Run on the selected picture:**

| Button | What it shows |
|---|---|
| Run everything | All of the below, plus the group checks when two or more pictures are picked |
| Face detection | Exactly what the best-shot check sees (YuNet on the 1280 px picture): every face with both eye positions, eye distance and score, and whether it was counted. Faces are outlined on the picture. Since 1.8 it also lists what Android's old built-in detector finds |
| Faces: other sizes and tilts | The same detector at 640, 1280 and 2048 px, and with the picture turned ±10° and ±20°. Shows whether face size or a tilted head made it miss a face |
| Eyes open or closed | For each face: the eyes-open probability, each eye at the 7 positions tried, how much it counts in the score, and the eye patches the model looked at |
| Sharpness / blur | The focus number used to compare shots, the blur score used for "possibly blurry", and each face's focus |
| Lighting | Brightness, blown-out and black share, and each penalty that makes up the lighting score |
| Picture category | Every measurement used for sorting (including the page-of-paper check: white level, paper share, smoothness, ink share and bands, and which check failed), any code, faces, the text read and its evidence, and the final category |
| QR code / barcode | Quick and thorough search at two sizes, with the decoded content |
| Read text | Every word the reader found, with its confidence, and what the text rules decided |
| Compress (test copy) | Compresses the picture with the compression tools ([tools README](app/src/main/java/com/storageplanner/tools/README.md)), with the same checks a real run would use, once per way of saving (each HEIC encoder, and WebP), and shows which copy a real run would keep (★). **Treat as** picks the kind: Screenshot becomes lossless WebP, anything else HEIC or WebP. Logs every quality tried with its worst SSIM, PSNR and colour shift, the size before and after, the time taken, and whether a real run would keep the copy. The copies go to their own folder under `Download/StoragePlanner/compressed/`. Every copy is kept, even ones a real run would throw away, so you can see why. Originals are never touched |

3. **Run on all picked pictures:**

| Button | What it shows |
|---|---|
| Best shot among them | Each picture's score split into its parts (eyes open, people missing or blinking, face sharpness, overall sharpness, lighting), the winner, and the reasons |
| Near-duplicate check | Each pair's hash difference, time apart and size, and whether the scan would group them |
| Exact duplicate check | SHA-256 of each file |
| Compress all (test copies) | Compress (test copy) for every picked picture |

4. **Compare original vs compressed.** Each test copy gets a **Compare** button under the results. The compare screen has three modes:
   - **Side by side:** stacked for landscape pictures, zooming together.
   - **Flip:** one picture at a time at the same zoom and spot. Switching back and forth makes any change jump out.
   - **Difference:** the change per pixel, amplified 8×, so black means identical. It also gives the average and largest change, and the share of pixels that changed.
5. **Log.**
   - Every line, with the phone model, Android and WebView versions and the app version, is written to a log in the app's private storage.
   - **Save log** copies it to `Download/StoragePlanner/lab-log-<time>.txt`, **Share log** sends that copy, **Copy log** puts it on the clipboard so you can paste it into a message (a very long log is cut to its newest 200,000 characters, with a note), and **Clear log** starts a new one.
   - Picked pictures are copied into the app's cache (20 slots, reused); the originals are not touched.

The Lab calls the same code as a scan (`core/Lab.java`), so its numbers are the scan's numbers. `LabTest` runs every tool on real pictures on the desktop.

## Runs in the background

- **Foreground service.** The analysis runs in a foreground service, so it keeps going while you use other apps or the screen is off. A notification shows the stage and progress, with a **Cancel** button; tapping it opens the app.
- **Notification when done.** When the plan is ready you get *"Your storage plan is ready: frees X GB"* (or *Analysis failed / cancelled*). If the app is open at that moment, it just shows the plan.
- **Results are saved.** Finished results are stored in the app's private storage (`last_results.bin`, written atomically, so a crash mid-write never damages it). If Android closes the app while it is in the background, reopening it shows *"Loading your last results..."* and then the last plan, without scanning again. Plans are rebuilt from the saved scan, so they are identical.
- **Back where you were.** The screen you were on (plan, a recommendation, a category, an item's details) is restored. Items that no longer exist are skipped.
- **Interrupted scans.** If Android stops the app in the middle of a scan anyway (rare, e.g. "force stop" or a very aggressive battery saver), the start screen says: *"The last analysis was interrupted. Tap Analyse to run it again."* Earlier results stay available under **Open last results**.

## Permissions

The app also contains one private, read-only file provider. It isn't exported, and it only hands one file at a time to an app you pick under Open with or Share, through a temporary read grant.

- **All files access** (`MANAGE_EXTERNAL_STORAGE`; `READ_EXTERNAL_STORAGE` up to Android 12): read photos, videos, downloads and WhatsApp media. Read only, except the Lab's test copies in `Download/StoragePlanner/`.
- **`WRITE_EXTERNAL_STORAGE`** (Android 8-10 only): save the report, the Lab log and the Lab's test copies to Download.
- **Usage access** (`PACKAGE_USAGE_STATS`, optional) and **`QUERY_ALL_PACKAGES`**: app sizes, caches and last-used dates for every installed app.
- **`FOREGROUND_SERVICE`** and **`FOREGROUND_SERVICE_DATA_SYNC`** (new in 1.2): run the analysis as a foreground service of type *dataSync* (local file processing) so it is not stopped when you switch apps.
- **`POST_NOTIFICATIONS`** (new in 1.2, asked on Android 13+): show the progress and "plan is ready" notifications. Optional.
- **`WAKE_LOCK`** (new in 1.2): keep the processor awake during the scan when the screen turns off (released when done, at most 60 minutes).
- **`ACCESS_MEDIA_LOCATION`** (new with the Motion Photo Lab, asked on Android 10+ when the Lab opens): without it Android hides the GPS location inside photos from the app, so a Motion Photo copy would silently lose it. It only lets the app read the location already stored in your photos; nothing is sent anywhere. Optional: without it the Lab warns that copies may lack the location.
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

## Rules (new)

The suggestions for single files are now **rules**, and you will be able to add your own. A rule is a list of conditions that must **all** be true, plus one action. Each condition tests one tag of a file (see Tags: `age_days`, `source`, `category`, `faces`, `blur`, `brightness`, `size_mb`, ...) and can be turned around with NOT. If you need "or", write two rules. The actions are delete, re-encode (photo, lossless image, video, audio) and keep. For example:

- `source = chat image (received)` AND `category = greeting` AND `age_days > 3` AND NOT `faces > 0` -> delete
- `camera_photo = yes` AND `blur > 70` AND `brightness < 20` -> delete
- `faces > 0` -> keep (never touch pictures with people)

**Unknown means no.** If a tag was never measured for a file (faces in a screenshot, blur with too few camera photos, a date that was not read), the condition is false, also when it has NOT. So `NOT faces > 0` does not match a picture whose faces were never checked.

**Today's suggestions are built-in rules** (`BuiltInRules`: thumbnails, temp files, exact duplicates, bursts, old payments, re-encoding of camera photos, ...). With no rules of your own the plan is exactly the one the app made before; a test compares the two on tens of thousands of files. A built-in rule can be switched off or have its numbers changed (for example greetings after 3 days instead of 7). Apps and app caches are still decided in code, not by rules.

**Limits on your rules**, enforced in the core and not only on screen:
- A delete from your rule is always R4: it needs your confirmation. A re-encode is always R2. Its technique is shown as "My rule: name".
- A keep rule removes every suggestion for the files it matches, built-in ones included.
- ID documents never get a delete suggestion from any rule; only an identical extra copy may go.

Rules are saved as plain text (`RuleSet.toText()`, one block per rule, versioned). A rule that names a tag this version does not know is loaded switched off.

### The Rules screen

Open **Your rules** from the start screen or from the overview (next to the Algorithm Lab button). Your own rules come first, then the built-in ones. Each row says in words what the rule looks for ("Picture category is Greetings & forwards, age more than 7 days and is chat or Telegram media"), what it does and its risk, and, once you have analysed your storage, how many files and how much space it matches right now. Scanning is not repeated: the counts and the plan come from the scan already on the phone.

- **Switch a rule off or on** with its switch. A rule that is off suggests nothing; a rule that cannot run (it names a tag this version does not know) stays off and says why.
- **Change a built-in rule:** tap it. Its name, action and conditions are fixed, but its numbers can change (greetings after 3 days instead of 7). **Reset to default** appears on a changed rule, in the list and in the editor.
- **Add a rule:** tap **Add a rule**. Give it a name, pick what it does (Delete, Compress photo, Compress screenshot (lossless), Compress video, Compress music, or Keep), and add conditions. Each condition is a tag (shown by its plain label, such as "Age in days" or "Picture category"), a comparison that fits the tag (more than, at least, less than, is, is not, is one of; yes or no for yes/no tags), a value (a number with its unit, or a choice from the values the app knows plus the ones your scan has), and an optional NOT. All conditions must be true; for "or", write two rules.
- **Live preview:** under the form, "Matches 143 files, 1.2 GB" and a grid of the largest matching files update about a third of a second after each change. Tap a file to see its details, including every tag. It also says how many of the matches another rule already suggests, how many are ID documents (left out of a delete), and, for a compress rule, how many a built-in compress rule claims first (only the first compressing rule that matches a file counts). Without a scan the editor still works; it just says "Analyse your storage to see what this rule matches".
- **Delete a rule** from its row or from its editor; the button asks you to tap it a second time.

When you save, the rules are stored on the phone (`rules.txt` in the app's private storage, written so that a crash cannot leave half a file; no file means the built-in rules) and the plan is rebuilt from the scan in memory. Your kept and approved decisions stay. An approval that named a suggestion that no longer exists (you switched that rule off) is ignored and not counted, and it comes back if the suggestion does. Each later analysis uses the saved rules.

**Safety limits the screen adds** (the core enforces the ones in the first list above as well):
- A rule needs a name that none of your other rules has (the name is how its suggestions are grouped; saving is refused for a duplicate, in any letter case), at least one condition, and every condition filled in.
- A delete rule needs at least one condition that says more than the kind of file. "Kind is image" or "File type is jpeg" alone is refused, so a rule cannot delete every picture.
- A delete rule that matches more than a quarter of all your scanned files shows a warning in words, and Save changes to **Save anyway**: you have to tap it a second time.
- A delete from your rule is still always "Your call": nothing happens without your confirmation, and the app never deletes anything itself.
- Saved reports list the technique of each suggestion, so a rule of yours appears there as "My rule: name".

## Build it yourself

**Android Studio:** open this folder, let it sync (AGP 8.5, JDK 17), then **Build → Build APK(s)**. The project has no dependencies beyond the Android framework.

**On GitHub (no setup):** the **Build APK** workflow (`.github/workflows/build-apk.yml`) runs `tools/build_offline.sh` on every push, and on demand from the Actions tab. It runs the image-compression tests first and the APK audit after. Download the APK from the run's **Artifacts**. Android installs a new APK as an update only when it's signed with the same key. The workflow takes that key from two repository secrets (Settings → Secrets and variables → Actions): `SIGNING_KEY_PEM` (contents of `key.pem`) and `SIGNING_CERT_DER_B64` (`base64 -w0 cert.der`). Use the key the released 1.8.1 was signed with, so builds also install over that release. Without the secrets, each APK is signed with a one-off key and installs only after uninstalling the app. Each run's summary shows its certificate fingerprint. The key is never kept in the Actions cache: in a public repository, a fork's pull request could restore it. The Python packages the job installs are pinned by hash in `tools/ci-requirements.txt`, and the APK is uploaded only after it passes the audit.

**Without Android Studio:** `tools/build_offline.sh` builds it from javac, D8, aapt2 and a small Python v2 signer. The supplied APK was built this way, because Google's Maven was not reachable from the build machine. An APK you build yourself is signed with your own key, so uninstall the supplied one first.

## Layout

- `app/src/main/java/com/storageplanner/core/`: the algorithm in pure Java, with no Android imports.
  - **Scanner:** walk, duplicate check, media features.
  - **Classifier:** path rules.
  - **ImageFeatures:** perceptual hash and sharpness.
  - **PhotoQuality / EyeState:** which near-identical shot is better (faces, eyes, focus, light).
  - **FaceNet / Faces:** the YuNet face detector in plain Java, and the face list format (1.8).
  - **Sorter / ImageSignals / Codes / TextRules / PhotoCategory:** picture categories (1.6).
  - **Lab:** the Algorithm Lab's step-by-step runs (1.7).
  - **Tags:** the named facts about every file (source, kind, age, blur, brightness, faces, duplicate, ...) shown on the details page, and a listing of them (label, type, unit) for the rule editor.
  - **Rule / RuleSet / BuiltInRules / RuleText:** the rule engine, the built-in rules, matches and checks for the Rules screen, and rules in words.
- `app/src/main/java/com/google/zxing/`: ZXing 3.5.3 decoding code (Apache-2.0, unmodified; encoders left out).
- `app/src/main/assets/faces/`: YuNet's weights converted by `tools/facemodel/export_yunet.py` (MIT licence, in `LICENSE-yunet.txt`).
- `app/src/main/assets/ocr/`: the offline text reader (tesseract.js 7.0.0, tesseract.js-core 7.0.0, tessdata_fast English; Apache-2.0).
  - **UnitBuilder:** options (finds duplicates, bursts, blur, sent copies, then lets the rules decide per file; apps are decided in code).
  - **Rule / RuleSet / BuiltInRules:** the rule engine: conditions on tags, the built-in rules, the user's rules and their text format.
  - **Planner:** tiered planner.
  - **Report:** output.
  - **ResultStore:** versioned binary format for saved results (with checksum).
- `app/src/main/java/com/storageplanner/app/`: the Android layer. Screens: SetupScreen, OverviewScreen, ListScreen (review lists), DetailScreen, DeckScreen (swipe cards), CompareScreen and ViewerScreen; Group holds the copies of one photo and the user's ticks; Tick is the delete box; PicturesScreen is the category grid; LabScreen and LabLog are the Algorithm Lab; OcrEngine runs the offline text reader; Session holds results and Keep decisions; Thumbs, MeterView, MediaOpen.
  - **AndroidPlatform:** reads files and system stats.
  - **Calibrator:** on-device measurement.
  - **Ssim:** matches scikit-image to 1e-14.
  - **MainActivity:** the UI, back stack and its restore after process death.
  - **AnalysisService / AnalysisRunner / AnalysisState:** the foreground service, the pipeline and the live progress the screen observes.
  - **CompressCompareScreen:** the Lab's original vs compressed view: side by side, flip, and amplified difference.
- `app/src/main/java/com/storageplanner/tools/`: **tools** for the future action taker, never called by the planner. Today: image compression (ImageCompressor, HeifEncoder, Guard, Ssim, Exif, WebpMeta, CopyPaths). See its [README](app/src/main/java/com/storageplanner/tools/README.md).
  - **ResultFiles:** saves and loads results in app-private storage (AtomicFile).
- `jvmtest/`: runs the unchanged core on a desktop against the virtual phone from the study, plus classifier, planner, review, saved-results (ResultStoreTest), file tags (TagsTest), rules (RulesTest, RuleMatchTest for matches, wording and checks, and RulesEquivalenceTest against a copy of the old hard-coded builder), group-tick (GroupDecisionTest), eye-model (EyeStateTest), photo-quality (PhotoQualityTest), face detector against OpenCV (FaceNetTest), picture-category (CategoryTest, TextRulesTest, PageTest for pages of paper and re-sorting), reader-bridge (OcrBridgeTest), Algorithm Lab (LabTest) and image-compression rules (CompressionTest) tests.
- `tools/eyemodel/`: how the eye model was trained and checked.
- `tools/test_ocr_page.py`: runs the app's offline text-reader page in Chromium the way the app serves it.
- `tools/ocr_assets.sha256`, `tools/face_assets.sha256`: pinned hashes of the text-reader and face-detector files (checked by the audit).
- `tools/facemodel/`: converts YuNet for the app, and records OpenCV's answers for FaceNetTest.
- `tools/`:
  - **apkbuild.py:** packager and v2 signer.
  - **strip_methodparams.py:** strips an attribute old D8 can't read.
  - **audit_apk.py:** independent APK audit.
  - **build_offline.sh:** the offline build.

## Known limits

- **Not run on a real device.** It was compiled against API 34 and the algorithm was tested on a desktop. The Android-specific code (permissions, decoding, system stats, the background service and notifications) has not run on a phone, so first-run issues are possible.
- **Background limits.** Some manufacturers (e.g. aggressive battery savers) can still stop a foreground service; the app then reports the interrupted scan. Results are only saved once a scan finishes.
- **Android/data is unreadable on Android 11+.** App data there is counted through system stats instead, so leftover files of uninstalled apps in that folder can't be found.
- **Face detection.** YuNet finds most faces, but faces in full profile, very small faces (eyes under about 6 px apart in the 640 px copy) and heavily covered faces can be missed; those shots are judged on focus and light only. Eyes that are looking down can read as closed. The detector has been checked against OpenCV on a desktop; its speed on a phone has not been measured yet.
- **Picture sorting is new and untested on a phone.**
  - The text reader runs in Android's WebView, which differs between phones; if it cannot start, pictures are sorted without reading text.
  - The first scan with sorting on takes noticeably longer. Estimated from desktop timings, not measured on a phone: about a quarter of a second per picture, plus one to three seconds for each picture whose text is read.
  - Only English letters are read, so Hindi and other scripts are not.
  - Greeting images are often missed.
- **The compression tools are tested on one phone only** (Galaxy S23 FE, Android 16). The HEIC writer depends on each phone's encoders. The path through a dedicated HEIC encoder hasn't run on a phone yet. See the [tools README](app/src/main/java/com/storageplanner/tools/README.md).
- **Cache clearing is manual.** Android doesn't let one app clear another app's cache, so you do it from each app's settings page.
