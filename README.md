# Storage Planner

An Android app that scans your phone and builds a plan to reach a storage target (by default 70 GB on a 128 GB phone), using the lowest-risk steps first. It runs the same algorithm as the 128 → 70 GB study. It only **reads**: nothing is deleted or changed, and it has no internet permission. The only files it writes are the report, the Lab log and the Lab's test copies, all in `Download/StoragePlanner/`.

## Install

1. Copy `StoragePlanner-1.8.1.apk` to the phone and open it. Allow "Install unknown apps" for your file manager or browser if asked. Play Protect may warn about an unknown developer; choose **Install anyway**. It installs over 1.0–1.8 as an update (same signing key); your Keep decisions are kept.
2. Open **Storage Planner**. Then:
   - **Grant all files access.** This is needed to read photos, videos, downloads and WhatsApp media.
   - **Grant usage access** (optional). This adds app sizes, caches, and apps you haven't opened in months. If the toggle is greyed out, go to Settings → Apps → Storage Planner → ⋮ → **Allow restricted settings**, then try again.
3. Check the target (GB of used space) and tap **Analyse my storage**. With tens of thousands of photos this takes a few minutes. You can switch to other apps meanwhile (see below). On Android 13+ the app asks once whether it may show notifications; the scan runs either way.
4. **Your storage plan** (overview) shows:
   - **Mode:** *All safe steps* (everything that loses nothing visible; the default) or *Reach my target* (the minimum steps needed to get there, which can include items that need your confirmation).
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
7. **Save report** writes `.txt`, `.csv` and `.json` files to `Download/StoragePlanner/`, plus `actions-<time>.json`: every suggestion with its state, for the engine that will carry actions out (see [The decision algorithm](#the-decision-algorithm)).

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

## The decision algorithm

Finding something that *could* be removed is not the same as deciding that it *should* be. After the scan,
every suggestion is only a proposal. The **Assessor** (`core/Assessor.java`) decides which proposals stand.
It only reads the scan: it calls no tool and changes no file.

For each file it works out five things and keeps them apart, so that a low-value file is never treated as
safe to delete just because it is low-value:

| Value | Meaning |
|---|---|
| Retention | How much keeping the file is worth. Unknown means valuable. Camera originals, faces, documents and records raise it |
| Redundancy | How sure it is that another file, which stays, holds the same content |
| Regenerable | How sure it is that the file can be had again (rebuilt, downloaded again) |
| Confidence | How strong the evidence is. Different kinds of evidence that agree count; one kind alone counts for little unless it is conclusive (identical bytes, a system fact) |
| Harm | What is lost if the suggestion is wrong. Weak evidence counts as possible harm |

**Rules that size never overrides:**

| Rule | What it does |
|---|---|
| Personal records | ID pictures, and files named like a personal record (passport, bank statement, tax, insurance, medical, deed, certificate …), get no suggestion at all, except removing an identical extra copy. They are never re-encoded |
| One reason is not enough | Age alone, blur alone, darkness alone or a folder name alone never suggests a deletion. At least two different kinds of reason must agree (see [Do you need this file?](#do-you-need-this-file)) |
| The kept copy stays | The file that is kept from a group (duplicates, a burst, a Sent copy's original) is never planned for deletion itself, whatever else is true of it |
| Companions stay together | RAW + JPEG pairs, edit sidecars (`.xmp`, `.aae`), subtitles, and a photo with a video of the same name are not split and not re-encoded |
| Text stays exact | Receipts, bills, documents, QR codes, payments and tickets are not re-encoded with loss |
| Backups | An older WhatsApp backup may go only while a newer one is in the same folder |
| Leftovers of uninstalled apps | Game data (`Android/obb`) is junk. Files in the app's media folder may be your own recordings or exports, so they are review-only |
| WhatsApp Sent copies | "No loss" only when the match with the camera photo is very close and the original is not smaller. Otherwise they are listed as *Sent copies to check* |
| Telegram media | "Your call", not "No loss": the app cannot check that a file is still in Telegram's cloud |

**Review-only** suggestions are listed and you can approve them, but no plan ever includes one by itself, in
either mode. In the list they say *For you to decide*, with the reason.

**Tiers are worked out, not assigned.** *Safe* needs a file that rebuilds itself, is worth nothing kept, and
strong evidence. *No loss* needs the same content elsewhere or a file that can be had again, and strong
evidence. Everything else that passes the rules is *Your call*. The numbers are in `core/Policy.java`; they
are conservative starting values, not yet tuned on labelled data.

**Output for the action engine** (`core/Actions.java`, saved as `actions-<time>.json`). Each suggestion has:
- a **state**: `approved` (you accepted it), `planned` (in the plan, not looked at yet), `available` (passed
  every rule, not needed), or `review`;
- the action, tier, bytes and reason, and the five values;
- `keeps`: the file that must still be there when this one goes;
- `verify`: the file's size, modified time and SHA-256 (when known) at scan time, so an engine can refuse a
  file that changed since.

Protected files and withdrawn suggestions are listed too, with the reason.

**Tests** (`AssessorTest`, run by CI): 81 checks. A hand-built phone with the hard cases (the only copy of a
blurry family photo, an old deed, an ID picture and its copy, a kept copy that is also an old download, a
RAW + JPEG pair, recordings left by an uninstalled app), the scenarios below at each setting, videos, and
300 random phones on which no plan at any risk level or setting deletes a kept copy together with its
copies, touches a protected file, or picks a review-only item.

### Do you need this file?

Some suggestions are not about a copy of anything: a soft photo, a dark shot, an old download, a one-time
code, a greeting, a ticket, a payment screenshot, an accidental video. For these the app estimates how
likely you are to need the file, from 0 to 100 (`core/Need.java`), and then does one of three things:

| Estimate | What happens |
|---|---|
| High | The file is **kept without asking**. Nothing is suggested and nothing is shown for review |
| Low, with at least two different kinds of reason against it | **Suggested** for deletion ("Your call") |
| In between, or only one kind of reason | Shown **for you to decide**, with the reason |

**What the app looks at.** Each of these adds to or takes away from the estimate, and says why in words. The
reasons are shown on the file's details page ("Do you need it? Estimated 15 of 100. For keeping: … Against: …").

| Way | What it uses | Effect |
|---|---|---|
| Where it came from | Camera, Pictures, download, screenshot, chat | The starting point: a camera photo starts at 70, a chat image or screenshot at 40 |
| What it shows | The picture's category and the text read in it; for other files, the kind of file | A one-time code, a greeting, an empty frame, or a ticket whose date passed over a month ago count against. A receipt, document, payment, chat, QR code, or a ticket for a date to come count for keeping. In Downloads, documents count for keeping; archives and installers against |
| People in it | Faces found in the photo, or in two frames of the video | The strongest reason to keep |
| Blur and length | Sharpness against your other photos, picture size, video length | Very soft, very small, or a clip under four seconds counts against |
| Age, by kind | Days since it was saved, read by what the file is | A code is useless after a day, a greeting after a week, a screenshot fades over months. **Your own photos and videos never count as old.** Anything from the last two days counts as still in use |
| Taken with others | Other camera photos and videos within the hour | Five or more means a trip or a party: keep |
| Videos: does anything happen | Whether the frames differ | A video whose frames all look the same counts against |
| Your earlier choices | What you kept or approved on the same kind of suggestion | Counts after five decisions |

**Scenarios** (all in `AssessorTest`, at the Balanced setting):

| File | Result | Why |
|---|---|---|
| Blurry photo with three faces | Kept without asking | People outweigh blur |
| Soft photo, one of eight taken at a party | Kept without asking | Part of a set |
| Soft photo taken alone, no faces | For you to decide | One reason only |
| Black **and** soft shot, no faces | Suggested | Two reasons: nothing to see, and soft |
| One-time code, 40 days old | Suggested | Code, and long expired |
| Ticket for a trip five months ago | Suggested | Date long past, and saved long ago |
| Ticket for next week | Kept without asking | Still to come |
| Payment screenshot, 200 days old | For you to decide | A record: only its age speaks against it |
| Greeting image from a chat, a month old | Suggested | Greeting, and old |
| Greeting image with faces in it | For you to decide | It may be a personal photo with a caption |
| Meme or poster from a chat, over a week old | Suggested | A designed picture, and old |
| The same with a phone number or web address on it | Suggested | An advert |
| Card with a face and only a few words | For you to decide | It may be personal |
| Old `.zip` in Downloads | Suggested | Archive, and untouched for over six months |
| Old `.pdf` in Downloads | For you to decide | A document: only its age speaks against it |

### Videos

During the analysis the app looks at **five frames of each of your own videos** (`core/VideoLook.java`), at
10%, 30%, 50%, 70% and 90% of its length, without playing it. From them it knows whether the video is black,
whether there is anything in it, whether anything moves, and (from two frames) whether people are in it. It
keeps a fingerprint of each frame.

| Video | Result | Why |
|---|---|---|
| Black, three seconds long | Suggested as an accidental recording | Nothing to see, and very short |
| Black, twelve seconds long | For you to decide | Darkness alone is one reason |
| Black, ten minutes long | For you to decide | It may have been recorded for its sound, which the app cannot judge |
| One second long, something in it | For you to decide | Shortness alone is one reason |
| One second long, a person in it | Kept without asking | People |
| Screen recording older than three months | Suggested | A screen recording, and old |
| The copy WhatsApp made of a video you sent, original still on the phone | "No loss" when every frame matches closely, otherwise "Your call" | Same length within a second, same shape, and the frames at the same moments match |
| Any other video of yours | Nothing suggested | A video is not "not needed" because it is old or large |

Videos received in chats are only suggested when they are exact duplicates: the app cannot tell a forwarded
clip from a family video someone sent you. Sound is not analysed.

Looking inside videos takes about a second per video the first time (an estimate; not measured on a phone),
up to ten minutes per analysis; the rest is done at the next one. Unchanged videos are not looked at again.

### Algorithm settings

**Algorithm settings** is on the start screen and at the bottom of the plan. Changes apply to the current
results at once, without a new analysis.

| Setting | Suggests when the estimate is at most | Keeps without asking from | Adds to the default plan |
|---|---|---|---|
| Careful | 10 | 45 | Nothing unique |
| Balanced (default) | 25 | 60 | Nothing unique |
| Thorough | 35 | 70 | Suggestions with an estimate of 10 or less, such as expired one-time codes |

Below the setting, each way of looking at a file can be switched off, and so can looking inside videos. At
every setting two kinds of reason are needed for a suggestion, and the fixed rules above (personal records,
the kept copy, companions) are never loosened. The settings and their numbers are in `core/Policy.java`.

The research and design behind this is in [`docs/storage-engine-design.md`](docs/storage-engine-design.md).

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
| Documents & text | Mostly text (pages, notes, printouts) | No |
| Payment screenshots | Text: paid to, UPI transaction ID, Google Pay / PhonePe / Paytm … | Yes, if older than 3 months |
| One-time codes | Text: OTP, verification code, "do not share", plus a 4–8 digit code | Yes, if older than 1 day |
| Tickets & passes | Text: PNR, train no., boarding pass, gate, seat, booking ID; the travel date is read | Yes, once the date has passed by a week (or 6 months after saving if no date is found) |
| Chat screenshots | Screenshot with chat layout and many timestamps | No |
| Greetings & forwards | A greeting ("Good morning", "Happy Diwali" …) read on an image received in a chat | Yes, if older than a week |
| Posters, memes & forwards | Recognised **without reading a word**: lettering laid over the picture (a row of large letters across a quarter of the width, or five or more lines of letters) on a picture that does not have the shape of a camera frame, or has large flat areas. Works in any language. Never a camera photo or a screenshot | Yes, if received in a chat more than a week ago. With a face and few words (it may be a personal card): for you to decide |
| Accidental shots | Almost completely dark, featureless, or shaken so badly that nothing is sharp | Yes |
| ID documents (sensitive) | Text: Government of India, Aadhaar, PAN, passport, licence; Aadhaar and PAN number patterns | **Never.** Other suggestions are removed too; only an identical extra copy may go |
| Graphics | Chat and download images with large flat areas and little text (stickers, wallpapers, glitter cards) | Yes, if received in a chat more than a week ago |
| Other screenshots | Everything else from screenshots | No |

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

**Marathi and Hindi (Devanagari).** A colourful picture with lettering on it that the English reader makes
nothing of is read once more with Tesseract's Marathi and Hindi models (tessdata_fast `mar` and `hin`,
Apache-2.0, 3.2 MB together), on a 360 px copy and, if that finds nothing, on a 720 px copy. A greeting or
festival word (शुभेच्छा, शुभकामना, मंगलमय, दिवाळी, पाडवा, सुप्रभात …) read with some confidence makes it a
**Greeting**. "Lettering" is measured, not guessed: a wide row of large letters, or twenty or more
letter-shaped marks in two lines. That keeps this second read off ordinary photos. It takes about half a
second per picture on a desktop. The switch is under **Algorithm settings → Read Marathi and Hindi text**.

The second read was 720 px at first; a busy festival poster only gave up its plain lines at 1000 px, so that
is the second size now.

**Tried on twelve real pictures** (the app's reader in Chromium, the face detector and the decision code on a
desktop; ten days after they were received):

| Picture | Sorted as | Decision |
|---|---|---|
| Marathi Padwa greeting, lamp and headline | Greeting (words read) | Suggested |
| "Happy Diwali" in gold glitter on black (no reader could read it) | Graphic (large flat areas) | Suggested |
| Three-panel comic meme with captions | Poster/meme (lettering, odd shape) | Suggested; the drawn faces count little in a picture full of text |
| Marathi festival poster with a man's photo, a company logo and a phone number | Poster/meme (lettering, odd shape), an advert | Suggested |
| The same camera photo three times, byte for byte | Photo | Two deleted by the default plan, one kept |
| Two more shots of that scene | Photo | One suggested as a near-duplicate, one kept |
| Photo of a chocolate box, photo of a candle held in a hand | Photo | **Kept.** Nothing measurable says these are not wanted |

The last row is the limit of this approach: whether you still want an ordinary photo of an object is not
something lettering, faces, blur or shape can tell.

Checked on a real forwarded Diwali Padwa greeting (720 × 843, a lamp with a Marathi headline): the English
reader returned noise, the Marathi/Hindi reader returned बलिप्रतिपदा (96), मंगलमय (87) and शुभेच्छा (46), and
the picture is sorted as a greeting. The yellow headline line in a decorative face was not read; one
recognised word is enough.

**The text reader is offline.** It is Tesseract compiled to WebAssembly (tesseract.js 7.0.0, Apache-2.0)
with the English model from tessdata_fast. It runs in an invisible WebView that can only load files
packed inside the app:
- every other address gets an empty answer;
- the app still has **no internet permission**;
- the audit checks that the reader's files are the published releases, byte for byte (pinned SHA-256 hashes);
- only the text-reader class uses the WebView, and it loads only the bundled page.

The app grew from about 0.1 MB to about 5 MB because of these files, and by about 1.5 MB more with the Marathi and Hindi models.

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
   **Treat as** sets how the picture is sorted. It starts on **Automatic (by file name)**: `IMG-20261110-WA0001.jpg`
   is a WhatsApp image, `Screenshot_…` a screenshot, `20261103_235249.jpg` a camera photo, anything else a
   download. Tap it to force camera photo, screenshot, WhatsApp image or download. (The picker hands the Lab a
   copy, not the folder the picture was in, so the name is all there is to go by. A real analysis uses the folder.)
2. **Run on the selected picture:**

| Button | What it shows |
|---|---|
| Run everything | All of the below, plus the group checks when two or more pictures are picked |
| Face detection | Exactly what the best-shot check sees (YuNet on the 1280 px picture): every face with both eye positions, eye distance and score, and whether it was counted. Faces are outlined on the picture. Since 1.8 it also lists what Android's old built-in detector finds |
| Faces: other sizes and tilts | The same detector at 640, 1280 and 2048 px, and with the picture turned ±10° and ±20°. Shows whether face size or a tilted head made it miss a face |
| Eyes open or closed | For each face: the eyes-open probability, each eye at the 7 positions tried, how much it counts in the score, and the eye patches the model looked at |
| Sharpness / blur | The focus number used to compare shots, the blur score used for "possibly blurry", and each face's focus |
| Lighting | Brightness, blown-out and black share, and each penalty that makes up the lighting score |
| Picture category | Every measurement used for sorting, any code, faces, the text read and its evidence, and the final category |
| QR code / barcode | Quick and thorough search at two sizes, with the decoded content |
| Read text | Every word the reader found, with its confidence, and what the text rules decided |
| Compress (test copy) | Compresses the picture with the compression tools ([tools README](app/src/main/java/com/storageplanner/tools/README.md)), with the same checks a real run would use, once per way of saving (each HEIC encoder, and WebP), and shows which copy a real run would keep (★). **Treat as** picks the kind: Screenshot becomes lossless WebP, anything else HEIC or WebP. Logs every quality tried with its worst SSIM, PSNR and colour shift, the size before and after, the time taken, and whether a real run would keep the copy. The copies go to their own folder under `Download/StoragePlanner/compressed/`. Every copy is kept, even ones a real run would throw away, so you can see why. Originals are never touched |

3. **Run on all picked pictures:**

| Button | What it shows |
|---|---|
| Run the algorithm | The whole decision algorithm on the picked pictures, as if they were the only files on the phone, with the current **Algorithm settings**. The log shows six steps (file types, exact copies, picture measurements, near-identical shots, what each picture shows, then every proposal and how it was judged, with the need estimate point by point). The fifth step prints every measurement and every word each reader returned, with its confidence. It ends with a **CONCLUSION** block: for each picture what it was recognised as, then *Keep*, *Protected*, *Delete (in the plan)*, *Keep, make smaller (in the plan)*, *Suggested to delete (your call)* or *For you to decide*, and why. Nothing is deleted. Because only the picked pictures are compared with each other, pick related pictures together (copies, a burst, photos of one event) |
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
| R0 | Rebuildable or junk | App caches, thumbnails, temp files, expired statuses |
| R1 | Nothing lost | Exact duplicates, installers for installed apps, WhatsApp Sent copies that surely match a photo still in your camera roll, trash, old WhatsApp backups (while a newer one exists), game data left by uninstalled games, archiving idle apps (Android 15), lossless screenshots |
| R2 | Visually identical | Photos re-encoded to HEIC/WebP, H.264 videos re-encoded to HEVC |
| R3 | Small quality trade-off | Music above 256 kbps to 128 kbps |
| R4 | Needs your confirmation | Burst near-duplicates (keeps the best shot), Telegram media, Sent copies to check, unused games/apps, and the files you probably do not need (one-time codes, greetings, accidental shots and recordings, old screen recordings, past tickets, old archives). **Review only, never planned:** anything the app could not tell about, and files left by uninstalled apps |

*All safe steps* takes everything in R0–R2. *Reach my target* uses the lowest tier that can reach the target; within that tier it takes the cheapest bytes first, then drops anything not needed. Neither ever takes a review-only item.

**Exact vs estimated.** Deletions, caches and app sizes are exact. Re-encoding savings are estimates:
- **Photos and chat images:** if "Measure compression" is on, these are **measured on a sample of your own files**. The app re-encodes each sampled photo to WebP at the lowest quality that still passes the study's guard (SSIM ≥ 0.98 and PSNR ≥ 38 dB) and records the real size. HEIC usually saves more, so this is conservative.
- **Screenshots:** measured with lossless WebP.
- **Video:** a conservative 45% for high-bitrate H.264.

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
- `app/src/main/java/com/google/zxing/`: ZXing 3.5.3 decoding code (Apache-2.0, unmodified; encoders left out).
- `app/src/main/assets/faces/`: YuNet's weights converted by `tools/facemodel/export_yunet.py` (MIT licence, in `LICENSE-yunet.txt`).
- `app/src/main/assets/ocr/`: the offline text reader (tesseract.js 7.0.0, tesseract.js-core 7.0.0, tessdata_fast English; Apache-2.0).
  - **UnitBuilder:** proposals.
  - **Assessor / Assessment / Policy:** the decision algorithm: which proposals stand, at which tier; Policy also holds the user's algorithm settings.
  - **Need:** the estimate of whether the user needs a file. **VideoLook:** the look inside videos.
  - **Actions:** the algorithm's output for the action engine.
  - **Planner:** tiered planner.
  - **Report:** output.
  - **ResultStore:** versioned binary format for saved results (with checksum).
- `app/src/main/java/com/storageplanner/app/`: the Android layer. Screens: SetupScreen, AlgorithmScreen (algorithm settings), OverviewScreen, ListScreen (review lists), DetailScreen, DeckScreen (swipe cards), CompareScreen and ViewerScreen; Group holds the copies of one photo and the user's ticks; Tick is the delete box; PicturesScreen is the category grid; LabScreen and LabLog are the Algorithm Lab; OcrEngine runs the offline text reader; Session holds results and Keep decisions; Thumbs, MeterView, MediaOpen.
  - **AndroidPlatform:** reads files and system stats.
  - **Calibrator:** on-device measurement.
  - **Ssim:** matches scikit-image to 1e-14.
  - **MainActivity:** the UI, back stack and its restore after process death.
  - **AnalysisService / AnalysisRunner / AnalysisState:** the foreground service, the pipeline and the live progress the screen observes.
  - **CompressCompareScreen:** the Lab's original vs compressed view: side by side, flip, and amplified difference.
- `app/src/main/java/com/storageplanner/tools/`: **tools** for the future action taker, never called by the planner. Today: image compression (ImageCompressor, HeifEncoder, Guard, Ssim, Exif, WebpMeta, CopyPaths). See its [README](app/src/main/java/com/storageplanner/tools/README.md).
  - **ResultFiles:** saves and loads results in app-private storage (AtomicFile).
- `jvmtest/`: runs the unchanged core on a desktop against the virtual phone from the study, plus classifier, planner, review, saved-results (ResultStoreTest), group-tick (GroupDecisionTest), eye-model (EyeStateTest), photo-quality (PhotoQualityTest), face detector against OpenCV (FaceNetTest), picture-category (CategoryTest, TextRulesTest), reader-bridge (OcrBridgeTest), Algorithm Lab (LabTest), image-compression rules (CompressionTest) and decision-algorithm (AssessorTest) tests.
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
  - English, Marathi and Hindi are read. Other scripts (Tamil, Bengali, Gujarati, Urdu …) are not. In Marathi and Hindi only greetings are recognised, not documents, payments or tickets.
  - Greeting images are often missed.
- **The compression tools are tested on one phone only** (Galaxy S23 FE, Android 16). The HEIC writer depends on each phone's encoders. The path through a dedicated HEIC encoder hasn't run on a phone yet. See the [tools README](app/src/main/java/com/storageplanner/tools/README.md).
- **The look inside videos has not run on a phone.** It is tested on made-up frames on the desktop. Reading the exact frame at a moment is slower than reading the nearest key frame, and how long it takes on a phone is not measured. A Sent copy that WhatsApp turned or trimmed is not matched (it is then simply not suggested).
- **The need estimate uses points chosen by reasoning, not fitted to real decisions.** "Learn from my choices" adjusts it per kind of suggestion as you decide.
- **Cache clearing is manual.** Android doesn't let one app clear another app's cache, so you do it from each app's settings page.
