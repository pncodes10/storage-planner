# Storage Planner

An Android app that scans your phone and builds a plan to reach a storage target (by default 70 GB on a 128 GB phone), using the lowest-risk steps first. It runs the same algorithm as the 128 → 70 GB study. It only **reads**: nothing is deleted or changed, and it has no internet permission.

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

Since 1.9 the last column is decided by what each picture shows, not by its category alone (see
[Will you need it again?](#will-you-need-it-again-new-in-19)).

| Category | How it is recognised | Suggested for deletion? (1.6) |
|---|---|---|
| People | The face detector (YuNet, on the phone); 31 of 31 test photos with people | No |
| Photos without faces | Camera photos with no faces and no text | No |
| QR codes & barcodes | The code is decoded (ZXing). The card says what it is for: "UPI payment code for …", "Wi-Fi network …", "Link to …" | No (since 1.9: yes after about 3 weeks, except Wi-Fi codes) |
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
- **The app's own reader page** (`scripts/test_ocr_page.py`, run in Chromium with the app's offline rules): read a real receipt and an OTP screenshot correctly, and made no outside requests.

## Will you need it again? (new in 1.9)

Every sorted picture now gets a verdict from what it shows (`FutureNeed`):
- **Keep:** likely needed again.
- **Your call:** may not be needed. Never suggested.
- **Not needed any more:** suggested for deletion (still needs your confirmation).

The details page shows the verdict, the reasons and, for things that expire, when the picture will stop
being needed ("Not needed after about 12 Dec 2026"). **Your pictures** starts with a **Not needed any more**
tile that opens all of them in the usual grid, with Select, Approve deletion, Keep and Undo.

**What it reads in a picture:**

| Kind | Useful until | Example reason |
|---|---|---|
| One-time code | About a day and a half | "A one-time code stops working within minutes" |
| Coupon or offer | A few days after its expiry date (about 11 weeks after saving if none is printed) | "Expired on 30 Aug 2026, 45 days ago" |
| Ticket or pass | About two weeks after the trip or show (about 7 months after saving if no date is read); cancelled ones about 3 months | "Trip or show was on 12 Aug 2026" |
| Delivery or order update | About six weeks after delivery | "Delivery or order update: \"delivered on\", \"order #\"" |
| Ride or food order status; map; weather; parking or queue slip | About 4 weeks; 2 weeks; 5 days; 6 days | |
| Appointment or reservation | About 10 days after its date | |
| Shopping page | About 3 months | |
| QR code or barcode (payment, link, anything but Wi-Fi) | About 3 weeks: a code is used once | "A QR code is used once (UPI payment code for …)" |
| Sale or shop ad ("Buy 1 Get 1", "Sale is live"), not a photo you took | About 2 weeks (until its end date, if it prints one) | "Sale or shop ad: \"buy 1 get\", \"sale is live\"" |
| Payment | Under ₹2,000: about 4 months. ₹2,000 to ₹10,000: about 22 months. Failed: about 3 weeks. Pending or refund: about 3½ months | "Small payment (about ₹250)" |
| Payment for rent, fees, EMI, insurance or tax; ₹10,000 or more | **Always kept** | "Payment for \"house rent\": proof of payment" |
| Payment whose amount could not be read | **Never suggested** (it may be a large one) | |
| Small receipt (under ₹1,000) | About 10 months | |
| Greeting from a chat; meme or forward | About 11 days; about 7 weeks | |
| Warranty card or receipt with a serial number / IMEI, prescription, lab report, insurance, certificate, contract, salary slip, Wi-Fi or bank details (Wi-Fi QR codes too), ID documents, bills | **Always kept** | "Medical record: \"prescription\", \"patient\"" |
| Photos, people, chats, documents, other screenshots | **Never suggested** (old screenshots become "your call") | |

**What else counts:**
- **Faces.** A greeting or forward with people in it is at most "your call". Since 1.9, faces are also counted on
  pictures that have text (a family photo with a caption, an ID card); pictures sorted earlier get this check once.
- **A folder you named yourself**, like `Pictures/Important` or `Pictures/Tax 2025`. Anything in it is kept.
- **Text not read yet.** Not judged until the text is read.

**New recommendations:**
- Expired coupons and offers.
- Old delivery, ride and food order updates.
- Short-lived screenshots: maps, weather, tokens.
- Old shopping screenshots.
- Small receipts past any return window.
- Memes and forwards from chats.
- QR codes you have already used.

They replace 1.6's fixed rules for one-time codes, payments, tickets, greetings and accidental shots.

**Reading improvements:**
- The big bold amount on payment screens is often read with low confidence. Those amounts are now kept.
- The reader has no rupee sign and may read "₹1,093" as "71,093". That can only make an amount look
  larger, so it is used as an upper bound.
- Completely black camera frames (lens covered) are now "Accidental shots".

**Tests on pictures** (`AlgorithmBench`; how they were made is in `testdata/futureneed/README.md`):
- A test phone with 368 pictures, rendered and read with the app's own text reader.
- The rules were tuned on one set. The numbers below come from a fresh set made after the rules stopped
  changing:

| | Not needed and suggested | Still needed but suggested |
|---|---|---|
| 1.9 | **132 of 144 (91.7%)** | **0 of 224** |
| 1.6 rules on the same pictures | 33 of 144 (22.9%) | 31 of 224 (large and rent payments, cancelled tickets awaiting refund, pictures in "Important") |

What is still missed is mostly payments whose amount can't be read reliably. They are left as "your call".
`FutureNeedTest` checks 56 hand-written cases (each kind, dates, amounts, folders and the safety floors).

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

1. **Pick pictures** with the system file picker: one or more, up to 20. Tap a thumbnail to select it.
   **Treat as** sets how the picture is sorted: camera photo, screenshot, WhatsApp image or download. While it is on camera, a picture whose name says WhatsApp (`IMG-…-WA0007.jpg`) or Screenshot is treated as one (1.9).
2. **Run on the selected picture:**

| Button | What it shows |
|---|---|
| Run everything | All of the below, plus the group checks when two or more pictures are picked |
| Face detection | Exactly what the best-shot check sees (YuNet on the 1280 px picture): every face with both eye positions, eye distance and score, and whether it was counted. Faces are outlined on the picture. Since 1.8 it also lists what Android's old built-in detector finds |
| Faces: other sizes and tilts | The same detector at 640, 1280 and 2048 px, and with the picture turned ±10° and ±20°. Shows whether face size or a tilted head made it miss a face |
| Eyes open or closed | For each face: the eyes-open probability, each eye at the 7 positions tried, how much it counts in the score, and the eye patches the model looked at |
| Sharpness / blur | The focus number used to compare shots, the blur score used for "possibly blurry", and each face's focus |
| Lighting | Brightness, blown-out and black share, and each penalty that makes up the lighting score |
| Category and will it be needed | Every measurement used for sorting, any code, faces, the text read and its evidence, and the final category; then what every deletion algorithm version decides (keep / your call / not needed), with its reasons and when it stops being needed (1.9) |
| QR code / barcode | Quick and thorough search at two sizes, with the decoded content |
| Read text | Every word the reader found, with its confidence, and what the text rules decided |

3. **Run on all picked pictures:**

| Button | What it shows |
|---|---|
| Best shot among them | Each picture's score split into its parts (eyes open, people missing or blinking, face sharpness, overall sharpness, lighting), the winner, and the reasons |
| Near-duplicate check | Each pair's hash difference, time apart and size, and whether the scan would group them |
| Exact duplicate check | SHA-256 of each file |

4. **Log.**
   - Every line, with the phone model, Android and WebView versions and the app version, is written to a log in the app's private storage.
   - **Save log** copies it to `Download/StoragePlanner/lab-log-<time>.txt`, **Share log** sends that copy, and **Clear log** starts a new one.
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

**Without Android Studio:** `scripts/build_offline.sh` builds it from javac, D8, aapt2 and a small Python v2 signer. The supplied APK was built this way, because Google's Maven was not reachable from the build machine. An APK you build yourself is signed with your own key, so uninstall the supplied one first.

## Layout

The code is split into **tools that measure**, **algorithms that decide**, and the pipeline and app around them.
Tools never decide anything; algorithms never touch pixels, files or Android. They meet in `facts/PictureFacts`,
so any algorithm version can be swapped in and compared on the same pictures.

```
app/src/main/java/com/storageplanner/
├── tools/               detectors: look at a picture, report facts (versions listed in tools/Tools.java)
│   ├── faces/           FaceDetector (interface), FaceNet + Faces: YuNet in plain Java (1.8)
│   ├── eyes/            EyeState: eyes open or closed, a small CNN (1.5)
│   ├── ocr/             TextReader (interface), TextRules: what kind of text it is, dates, amounts
│   ├── codes/           Codes: QR codes and barcodes (wraps ZXing)
│   ├── layout/          ImageSignals: text rows, paper, flat screen areas, colour, darkness
│   ├── sharpness/       Sharpness: focus of the sharpest parts
│   ├── similarity/      PerceptualHash: near-identical pictures
│   ├── exposure/        Exposure: dark, covered, featureless or shaken shots
│   ├── quality/         PhotoQuality: which near-identical shot is better (faces, eyes, focus, light)
│   ├── category/        PhotoCategory: the picture's category from all of the above
│   └── Pixels           down-scaling and luma, shared by the tools
├── facts/               PictureFacts: everything the tools found about one picture, plus its file details
├── algorithms/          deciders: PictureFacts -> Decision (keep / your call / not needed, with reasons)
│   ├── DeletionAlgorithm, Decision, Registry (every version; which one the app uses)
│   ├── v1_rules/        RulesV1: 1.6's fixed rules per category, kept as the baseline
│   └── v2_futureneed/   FutureNeed: useful life of what the picture shows (1.9); the app uses this
├── core/                the pipeline, pure Java, no Android imports
│   ├── Scanner, Classifier, Sorter: walk, path rules, duplicate check, running the tools
│   ├── UnitBuilder, Planner, Review: options, tiered plan, your decisions
│   ├── Report, ResultStore: output; saved results (versioned binary, with checksum)
│   ├── Lab: the Algorithm Lab's step-by-step runs (1.7)
│   └── Platform: what the core needs from the device (implements FaceDetector and TextReader)
├── platform/android/    AndroidPlatform (files, system stats, faces), OcrEngine (offline text reader in a WebView),
│                        Calibrator + Ssim (on-device compression measurement)
└── app/                 the Android screens and service: Setup, Overview, List, Detail, Deck (swipe cards), Compare,
                         Viewer, Pictures, Lab; MainActivity, Session, AnalysisService/Runner/State, ResultFiles
```

- `app/src/main/java/com/google/zxing/`: ZXing 3.5.3 decoding code (Apache-2.0, unmodified; encoders left out).
- `app/src/main/assets/faces/`: YuNet's weights converted by `scripts/facemodel/export_yunet.py` (MIT licence, in `LICENSE-yunet.txt`).
- `app/src/main/assets/ocr/`: the offline text reader (tesseract.js 7.0.0, tesseract.js-core 7.0.0, tessdata_fast English; Apache-2.0).
- `jvmtest/com/storageplanner/test/`: runs the unchanged code on a desktop. `JvmPlatform` stands in for Android
  (faces with the same YuNet code, words recorded from the app's own text reader); `TestMain` runs the study's
  virtual phone.
  - `tools/`: one test per tool: TextRulesTest, EyeStateTest, PhotoQualityTest, FaceNetTest (against OpenCV),
    CategoryTest, OcrBridgeTest, LabTest.
  - `algorithms/`: hand-written cases per version: RulesV1Test, FutureNeedTest.
  - `bench/`: **AlgorithmBench** runs every algorithm version on the same labelled test phone and prints one
    comparison table (caught, wrongly suggested, your call, per kind of picture).
  - `core/`: PlannerTest, ReviewTest, DecisionTest, GroupDecisionTest, ResultStoreTest, ClassifierTest.
- `testdata/`: recipes for the test pictures (the pictures themselves are not included):
  - `futureneed/`: the labelled test phone for the deletion algorithms (build.py, ocr.py) and its results;
  - `categories/`: the picture-category corpus.
- `scripts/`:
  - **build_offline.sh:** the offline build; **apkbuild.py:** packager and v2 signer; **strip_methodparams.py:** strips an attribute old D8 can't read.
  - **audit_apk.py:** independent APK audit; **ocr_assets.sha256**, **face_assets.sha256:** pinned hashes it checks.
  - **test_ocr_page.py:** runs the app's offline text-reader page in Chromium the way the app serves it.
  - **eyemodel/:** how the eye model was trained and checked; **facemodel/:** converts YuNet for the app and records OpenCV's answers for FaceNetTest.

**Adding an algorithm version:** write `algorithms/v3_<name>/` implementing `DeletionAlgorithm` (read only
`PictureFacts`), add it to `Registry.ALL`, add hand-written cases under `jvmtest/.../algorithms/`, and run
`AlgorithmBench` on the test phones. Change `Registry.current()` only when it beats the current version without
any wrong suggestion.

## Code review

Every pull request is reviewed by Claude (`.github/workflows/claude-code-review.yml`, using
`anthropics/claude-code-action`). It posts inline comments on the lines it has findings for and one summary
comment, checked against this project's rules: read-only, no network, tools measure and algorithms decide, and
deletion suggestions stay safe. It runs again on every push to the pull request; drafts are skipped.

One-time setup by a repository admin:
1. Install the Claude GitHub App on the repository: https://github.com/apps/claude
2. Add the repository secret `ANTHROPIC_API_KEY` (Settings → Secrets and variables → Actions → New repository
   secret) with a key from console.anthropic.com.
   To use a Claude Pro or Max subscription instead, run `claude setup-token` and store the token as
   `CLAUDE_CODE_OAUTH_TOKEN`, then replace `anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}` with
   `claude_code_oauth_token: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}` in the workflow.

Pull requests from forks get no secrets, so they are not reviewed.

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
- **Cache clearing is manual.** Android doesn't let one app clear another app's cache, so you do it from each app's settings page.
