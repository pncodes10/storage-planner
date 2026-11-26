# Storage Planner

An Android app that scans your phone and builds a plan to reach a storage target (by default 70 GB on a 128 GB phone), using the lowest-risk steps first. It runs the same algorithm as the 128 → 70 GB study. It only **reads**: nothing is deleted or changed, and it never sends your data anywhere: its only internet use is the update check (see **App updates**). The only files it writes are the report, the Lab log and the Lab's test copies, all in `Download/StoragePlanner/`.

## Install

1. Copy `StoragePlanner-1.9.0.apk` to the phone and open it. Allow "Install unknown apps" for your file manager or browser if asked. Play Protect may warn about an unknown developer; choose **Install anyway**. It installs over 1.0–1.8 as an update (same signing key); your Keep decisions are kept.
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

## Your plan: every file, by type and action (new in 1.12)

The home screen shows your storage, your plan (once you have analysed) and one **Analyse my storage** button. After an
analysis, **Your plan** opens:

- **How much you can free**, with a bar for the three actions: **Delete**, **Compress** and **Keep**.
- **By file type**: Photos & images, Videos, Music & audio, Documents, App installers (APK) and Other files, each with
  how many files are set to delete, compress and keep. Installed apps and caches have their own row.
- Tap a type: tabs **Delete · Compress · Keep**, and two views:
  - **Gallery**: the files in a grid, grouped by why they are suggested (Exact duplicate, Old meme or forward,
    Re-encode chat images...). Files to delete or compress are **ticked**; tap a file to untick it (it is then kept),
    tap again to tick it. **All** / **None**, and **Tick all** / **Untick all** per group. Long-press to look at a file.
    On Keep, tap **Select** to pick files and delete or compress them instead.
  - **Cards**: one file at a time, large. **Swipe right** for the tab's action (Delete or Compress; on Keep: Delete),
    **swipe left** to keep; a DELETE / KEEP stamp shows as you drag, and ↶ undoes. **Swipe up** (scroll the card) for the
    full analysis: the verdict and why, every option the rules offered, the rules that match (in words), why the ★ best
    shot of a burst is kept instead, everything the app found about the file (its tags) and the file itself.
  - **Bursts and copies together**: shots of the same moment, exact duplicates and WhatsApp copies of a photo get their
    own sub-heading in the gallery (★ best shot first, then the others), and their cards come one after another, each
    with a strip of the whole set. **Compare side by side** opens two of them full size (zoom together, pick which go);
    **View similar** shows them all in a grid with what the photo check found, ★ best marked, a tick to delete each one,
    **Keep best only** and **Keep all**. Both write to your plan.
- Every file starts with what the rules suggest (delete before compress before keep); your choices win and are kept
  between sessions (`files/finalplan.tsv`). Earlier decisions from the classic review count as choices.
- **Finalize plan** shows the totals and locks the plan (read-only screens with an **Edit plan** button). **Start
  over** puts every file back to its suggestion.
- **Move to Recycle bin** (after finalizing): moves the files to delete to the phone's Recycle bin (Android's trash,
  Android 11+), where they stay 30 days and can be restored, after your confirmation and Android's own. **Not switched
  on in this version**: the APK audit forbids every trash request (the file-deletion guardrail), and changing it is
  waiting for the owner's approval. The step is built (`TrashAction`) but contains no trash request until then; the
  button says so. Compressing is planned, not carried out, from these screens.

**Settings** (menu or ⚙ on the home screen): the target, measuring compression, sorting pictures, your rules,
**Developer mode**, and about. Developer mode shows the **Algorithm Lab** on the home screen and in Settings; the Lab
holds every developer tool (model tests, Mirage, v2 vs v3, Try on my pictures, Video/Zip/Motion Photo labs, and the
**classic plan view** with recommendations, swipe review and the report).

`FinalPlanTest` checks the suggestions, choices, earlier decisions, compress only where possible, tabs, groups, totals,
saving the choices and the card's analysis (`core/Explain`).

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
| **Smiling** | A small network looks at each mouth (see "What a photo shows" below). |
| **Looking at the camera** | The head is turned to the camera: the nose sits between the eyes, not off to one side, and the head is not tipped far up or down. |
| **Focus** | Sharpness of the faces, then of the whole photo. It is measured so that a brighter or darker copy of the same photo doesn't look "sharper". |
| **Lighting** | Blown-out highlights cost the most because they can't be recovered. Then crushed shadows, a photo that is far too dark or too bright, and a face that is too dark or washed out. |

**Ranking.** Each person blinking or missing costs 3 points. Focus is worth up to 1.5 and light up to 1, so a
blink outweighs any focus or light difference between near-identical shots. Each smiling face adds up to 0.75 and each
face turned to the camera 0.5: a smile can outweigh a small focus or light difference, never a blink. The reason is shown on
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

Not judged: where the eyes look when the head is straight (a sideways glance), and composition between the shots
(the aesthetic score below is per photo and is not used to pick the best shot). Near-identical shots measured before
smiles were added are compared again at the next Analyse.

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
| Tickets & passes | Text: PNR, train no., boarding pass, gate, seat, booking ID; for shows and events also M-Ticket, ticket(s), venue, date & time, "booking is confirmed" (any 3 of these words); the travel or show date is read | Yes, once the date has passed by a week (or 6 months after saving if no date is found) |
| Chat screenshots | Screenshot with chat layout and many timestamps | No |
| Greetings & forwards | A greeting ("Good morning", "Happy Diwali" …) read on an image received in a chat | Yes, if older than a week |
| Accidental shots | Almost completely dark, featureless, or shaken so badly that nothing is sharp (a small sharp subject on a blurred background does not count as shaken) | Yes |
| ID documents (sensitive) | Text: Government of India (also "Government of" with the country cut off), Aadhaar, PAN, passport, licence, VID; Aadhaar and PAN number patterns; the gender written after its Indian-language word ("पुरुष / MALE"). A card held up in a photo is read when it has a face on it (see below) | **Never.** Other suggestions are removed too; only an identical extra copy may go |
| Graphics & images | Flat, drawn or designed images (memes, posters, cartoons) that are not screenshots or camera photos; with Mirage also comics and captioned collages | Yes (new), if received in a chat or saved in Download or Pictures more than a week ago: "Old meme or forward". Never camera photos, screenshots or what you sent |
| Other screenshots | Everything else from screenshots | No |

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
whether text was read, faces, faces with eyes open, whether it has a QR code or barcode, and, for pictures, brightness, how dark, how much glare, how
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

**Cards held up in a photo (ID cards).** An ID card fills only part of the frame, so it fails the page check, and its
text was never read. A camera photo is now sent to the text reader when it has some plain bright paper (20%+ of the
picture), a few marks in lines (8+ marks in 2+ lines), and either a face (the photo on the card) or at least 3 lines
of print (a letter on a table). When it was sent only because of the face, the text may only make it an **ID
document**: any other verdict is ignored, so a person standing in front of a poster stays in People. Measured on one
real Aadhaar card held in a hand: the reader found "Government of", "VID:", the gender and the 12-digit number, and the
card became an ID document; before, it was never read and stayed in People. Not measured on a set of everyday photos
(the corpus was not available), so how many more photos get read is not known; the reading time limit still applies.

**Eyes open on every photo with a face.** The eye check that compares near-identical shots now also runs on every
sorted photo with a face, on the 1024 px picture, with the same rules for which faces count. It gives the tags
"Faces with eyes open" (`eyes_open`, a number) and "Someone has eyes closed" (`eyes_closed`), which rules can test.
Results format 6 saves it. Pictures sorted by an older version are measured once more at the next scan (sorting
version 2), so the tags and the ID-card check reach them too.

**Night shots: grain is not detail, and a glare tag.** The focus measure used to see the sensor grain in a night
photo's black sky as fine detail, so a soft night shot scored as sharp (a night street photo scored 2.2, sharper
than daylight photos). Focus now measures the grain in the picture's own empty patches (tiles that vary less than
a luma variance of 25, when there are at least 3) and takes it off every patch, and leaves those empty patches
out. A picture with no empty patches (texture everywhere) is scored as before. This changes the focus used for
"accidental shot" (shaken) and for picking the best of near-identical shots; saved similar-shot measurements from
before are dropped and measured again (results format 7).

A new number tag, **"Glare share"** (`glare_share`, %), is the share of the picture that is bright and within 5% of
the long edge of a blown-out spot (luma 245+): the halo and streaks around headlights and lamps. "Bright" means at least
40 levels brighter than the picture's median and at least 100. A night photo into headlights
measured 19%; three daylight and indoor photos measured 0-1%. A blown-out sky counts too, so pair it with darkness.
Nothing is deleted on glare alone: no built-in rule uses it. A rule you can add yourself:

> Camera photo is yes, Faces = 0, Dark share ≥ 20, Glare share ≥ 10 → delete

On the four real photos tried it matched only the night shot. It can also match night photos you want to keep
(a lit skyline, fireworks), and the limits come from one night photo, so try it on yours in the Lab first.
Sorting version 3 measures every picture once more at the next scan so the tag and the new focus reach them.

**A sharp subject on a blurred background is not a shaken shot.** "Shaken" used to mean a focus below 0.12, and focus
is the 90th percentile of the picture's 64 px patches, so it assumes at least a tenth of the picture is sharp. A
flower or a face shot in portrait mode, with the background blurred on purpose, has fewer sharp patches than that:
a rose photo (forwarded through WhatsApp) scored 0.110 on the phone and was suggested for deletion as an accidental
shot, although the rose itself was sharp (its sharpest patches 0.5 to 1.3; the background about 0.01). Now a shot is
shaken only if its sharpest parts are soft as well: the 3rd-sharpest patch below 0.3 (`PhotoCategory.SHAKEN_PEAK`;
the 3rd, so one or two odd patches do not count). On the four real photos tried here plus blurred and shaken copies
of each (Gaussian blur 2 and 4 px, motion 9 and 21 px in one and in two directions), every copy that was an
accidental shot before still is, the sharpest parts of those copies stayed at or below 0.23, and the rose (0.36 to
0.76, depending on the decoder) no longer is. The limit sits between those two with little room on either side and
comes from these few pictures only. The Lab's picture category line shows both numbers and why a soft picture is or
is not called shaken. Results format 8 saves the new number; sorting version 4 sorts every picture once more.

The start screen has a switch to turn sorting off.

**The text reader is offline.** It is Tesseract compiled to WebAssembly (tesseract.js 7.0.0, Apache-2.0)
with the English model from tessdata_fast. It runs in an invisible WebView that can only load files
packed inside the app:
- every other address gets an empty answer;
- the text reader's WebView answers every request itself and fetches nothing (the app's only network code is the update check, see **App updates**);
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
- **Text rules on their own** (`TextRulesTest`, 25 cases): each kind, ID documents (Aadhaar, PAN, passport, and an Aadhaar card read at an angle) recognised before anything else, an article that mentions Aadhaar, a blank form's "male/female" and a state government notice *not* treated as an ID, and date reading.
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
| Run everything | All of the below (Rules instead of Picture category, which it includes), plus the group checks when two or more pictures are picked |
| Face detection | Exactly what the best-shot check sees (YuNet on the 1280 px picture): every face with both eye positions, eye distance and score, and whether it was counted. Faces are outlined on the picture. Since 1.8 it also lists what Android's old built-in detector finds |
| Faces: other sizes and tilts | The same detector at 640, 1280 and 2048 px, and with the picture turned ±10° and ±20°. Shows whether face size or a tilted head made it miss a face |
| Eyes open or closed | For each face: the eyes-open probability, each eye at the 7 positions tried, how much it counts in the score, and the eye patches the model looked at |
| Sharpness / blur | The focus number used to compare shots, the blur score used for "possibly blurry", and each face's focus |
| Lighting | Brightness, blown-out and black share, and each penalty that makes up the lighting score |
| Picture category | Every measurement used for sorting (including the page-of-paper check: white level, paper share, smoothness, ink share and bands, and which check failed), whether it looks like a card held up, any code, faces and eyes open, the text read and its evidence, the final category and the tags |
| Rules: which ones match | Picture category, then every rule (built-in and yours) on this picture: which match and what they would do, the first condition that fails for each built-in rule with this picture's value, every failing condition of your own rules, and the options the plan would get. Tags that need your whole phone (duplicates, bursts, blur, sent copies) are unknown here, so rules testing them do not match |
| QR code / barcode | Quick and thorough search at two sizes, with the decoded content |
| Read text | Every word the reader found, with its confidence, and what the text rules decided |
| Compress (test copy) | Compresses the picture with the compression tools ([tools README](app/src/main/java/com/storageplanner/tools/README.md)), with the same checks a real run would use, once per way of saving (each HEIC encoder, and WebP), and shows which copy a real run would keep (★). **Treat as** picks the kind: Screenshot becomes lossless WebP, anything else HEIC or WebP. Logs every quality tried with its worst SSIM, PSNR and colour shift, the size before and after, the time taken, and whether a real run would keep the copy. The copies go to their own folder under `Download/StoragePlanner/compressed/`. Every copy is kept, even ones a real run would throw away, so you can see why. Originals are never touched |

3. **Run on all picked pictures:**

| Button | What it shows |
|---|---|
| Best shot among them | Each picture's score split into its parts (eyes open, people missing or blinking, face sharpness, overall sharpness, lighting), the winner, and the reasons |
| Near-duplicate check | Each pair's hash difference, time apart and size, and whether the scan would group them |
| Rules on each picture | Rules: which ones match, for every picked picture |
| Exact duplicate check | SHA-256 of each file |
| Compress all (test copies) | Compress (test copy) for every picked picture |

4. **Compare original vs compressed.** Each test copy gets a **Compare** button under the results. The compare screen has three modes:
   - **Side by side:** stacked for landscape pictures, zooming together.
   - **Flip:** one picture at a time at the same zoom and spot. Switching back and forth makes any change jump out.
   - **Difference:** the change per pixel, amplified 8×, so black means identical. It also gives the average and largest change, and the share of pixels that changed.
5. **Log.**
   - Every line, with the phone model, Android and WebView versions and the app version, is written to a log in the app's private storage.
   - **Save log** copies it to `Download/StoragePlanner/lab-log-<time>.txt`, **Share log** sends that copy, **Copy log** puts it on the clipboard so you can paste it into a message (a very long log is cut to its newest 200,000 characters, with a note), and **Clear log** starts a new one.
   - Picked pictures are copied into the app's own storage, not its cache (20 slots, reused; the originals are not touched). Android deletes cache files whenever storage runs low, which would leave the Lab pointing at a picture that is gone. Each copy is checked against the size the picker reported; a copy that comes out short is not used. A slot is the lowest one no picked picture uses (never counted from how many pictures there are), and a pick that has not finished keeps its slots. The Lab never removes or renames files (only the compression tools may, and the APK audit checks this), so an older copy of a slot with another extension can stay in the folder unused until it is overwritten. Before every run the Lab checks that each copy is still there and the right size; if not, it copies it again from where it was picked and says so in the log.
   - **When a picture does not decode, the log says why**, for every tool (faces, eyes, sharpness, lighting, codes, category, reading text, best shot, near-duplicate and exact-duplicate checks), not just "could not decode". For example:
     ```
     ! could not decode the picture at 1280 px
       file: .../lab/p0.jpg
       the file is not there (missing or deleted)
       this was a copy the Lab made in the app's storage (Android deletes app cache files when storage runs low). Pick the picture again
     ```
     The lines that can appear: whether the file exists, its size and whether it can be read; the size the picker reported; the first 16 bytes and the type they show (read from the bytes, not the name), with a note when the name and the bytes disagree (a HEIC saved as .jpg) or the file is not a picture; what the phone's decoder reported (for example that it could not read the picture's size); and any error. On Android 9 and later, a picture that BitmapFactory cannot decode is tried with ImageDecoder, and the log says `note: ... decoded with ImageDecoder instead`. The group checks go on to the next picture instead of stopping at the first one that fails, so each failing picture gets its reason.

The Lab calls the same code as a scan (`core/Lab.java`), so its numbers are the scan's numbers. `LabTest` runs every tool on real pictures on the desktop.

### Try on my pictures

**Try on my pictures (debug)** is on the start screen, in the overview and at the top of the Algorithm Lab. It has a
number field for each place: **Camera photos**, **Screenshots**, **Downloads**, **WhatsApp images (received)**,
**WhatsApp images (sent)** and **Other pictures** (the Pictures folder), up to 100,000 each. The numbers are remembered.

**Analyse these pictures** runs the real analysis (the same one as **Analyse my storage**, in the background service
with its notification), limited to that many pictures from each place, and opens the plan when it is done. Every
picked picture goes through the whole pipeline (file type, exact duplicates, bursts, blur, sorting into categories,
reading text, your rules) and gets the options it would get in a full analysis, in the usual screens: the plan, the
categories, the swipe review, each file's details and tags.

- **Which pictures:** files named .jpg, .jpeg, .png, .webp, .heic or .heif in those places, placed by the same folder
  rules as a scan (`DCIM/.thumbnails` is not a camera photo, `Pictures/Screenshots` is a screenshot). Picked at
  random, or tick **Take the newest pictures** to take the newest ones, which keeps bursts and near-identical shots
  together. Tick **Pick the same pictures as last time** to analyse the same ones again after changing a rule.
- **What the plan then covers:** only those pictures. No other files and no apps are included, and duplicates, bursts
  and blur (compared with your other photos) are looked for among the picked pictures only. The plan replaces your
  last results until you tap **Analyse my storage** again, and the overview says so at the top ("Sample only: 100
  camera photos, 20 screenshots (random)"). Calibration ("Measure compression on a few of my photos") is skipped,
  and pictures are always sorted, whatever the start screen's switch says. The target is the start screen's default.
- **Time:** reading text takes a few seconds per picture, so 1,000 pictures can take over half an hour.

**Lab check only (no plan)** runs each picked picture through the sorting and your rules one by one, exactly as
**Rules: which ones match** does in the Lab, and lists the results on this screen: for each place, how many fell into
each category, how many would get each suggestion and roughly how much they would free, then every picture that would
get a suggestion or was skipped (the first 400). It makes no plan and keeps your last results. Each picture is judged
on its own, so duplicates, bursts, blur and sent copies are not checked. Every picture's details go to the Lab log
(**Save log** and **Share log** are on this screen too), in full for the first 300 pictures and one line each after
that. It runs on this screen, which stays awake; **Stop** ends it after the current picture.

Nothing is copied, changed or deleted by either button. `SampleTest` covers the listing, the random and newest picks,
the limited analysis (only the picked pictures, sorted, a plan built from them), skipping files that are not
pictures, stopping, and that no file changes.

## Five tags from the file and the gallery

Every picture now also gets these tags, which rules can use:

| Tag | Where it comes from | Unknown when |
|---|---|---|
| **Favourite in your gallery** (`favourite`, yes/no) | The heart or star you set in the phone's gallery (Android's media index, `IS_FAVORITE`; a read-only query) | Before Android 11, or the index could not be read |
| **Age in days (since taken)** (`taken_days`) | The date the camera wrote into the photo (EXIF DateTimeOriginal, with its time zone offset when the camera wrote one, else the phone's). Unlike "age" (the file's date), it survives copying, forwarding and restoring a backup | The picture has no such date (most screenshots, downloads and chat images, and PNG, WebP and HEIC files: only JPEG is read) |
| **Screenshot of app** (`screenshot_app`) | The screenshot's file name: Samsung writes the app's name (`Screenshot_20240115_123456_WhatsApp.jpg`), Xiaomi and others the package (`…_com.whatsapp.jpg`), shown as the app's name when the app is installed | Not a screenshot, or the name does not say (Pixel; OnePlus and Oppo write a code) |
| **Motion photo** (`motion_photo`, yes/no) and **Motion photo video size** (`motion_video_mb`) | The short video inside Samsung and Google Motion Photos, found by the Motion Photo tool's finder, which only reads | Not a camera photo (only JPEG and HEIC camera photos are checked) |
| **Code type** (`code_type`) | What the QR code or barcode found while sorting is: UPI payment, link, Wi-Fi, contact, e-mail, phone number, barcode, text, or none | The picture was not sorted |

**Favourites are never suggested.** A new built-in rule, **Favourite in your gallery**, keeps every favourite out of every
suggestion, like a keep rule of your own. It can be switched off on the rules screen. The old-against-new test checks
that favourites get no option and every other file gets exactly the options it got before.

Date taken and the Motion Photo check read each picture's first 72 KB (and the end of camera photos) once; results
format 9 saves them, so a later scan reads only new or changed pictures. The favourites are asked again at every scan.
Rule ideas: `screenshot_app = Swiggy` and `age_days > 30` → delete; `code_type = UPI payment` and `category = Payment
screenshots`; `motion_photo is yes` and `taken_days > 365` (no rule can remove the video part yet: that is the
Motion Photo tool in the Lab). `PhotoMetaTest` covers the EXIF reader (both byte orders, offsets, blank and damaged
dates), screenshot names from several phones, code types, a scan of a small phone with a favourite and a Motion
Photo, saving and loading, and the favourites rule.

## What a photo shows, how nice it is, and who smiles

Three things that need small offline networks, all run on the phone in plain Java (no download, nothing leaves the
phone). The app grows by about 5 MB.

| Tag | How | How good (on pictures it never saw) |
|---|---|---|
| **Scene** (`scene`): food, nature, document, pet, vehicle, sky, selfie, other | Food, nature, pet and vehicle: MobileNetV3-Small, an ImageNet network (Keras Applications weights, Apache 2.0), added up over its 1000 classes (every dog and cat breed is "pet", dishes, fruit and vegetables "food", landscapes, plants, flowers and wild animals "nature", cars, buses, bikes, trains, planes and boats "vehicle"). A group needs 40% of the network's belief. Document: the picture category (papers, receipts, bills, tickets, ID cards). Selfie: a face close to the camera (eyes at least 9% of the short side apart). Sky: a smooth blue, sunset or cloud area joined to the top edge covering 45%+ of the picture (ImageNet has no sky) | On 29,468 labelled pictures: 92.5% right. Pets 95%, vehicles 94%, food 93% (9 Food-101 dishes), nature 80% (roses and tulips are not ImageNet classes), "other" 97% (also for kinds of things never used to tune it) |
| **Aesthetic score** (`aesthetic`), 1 to 10 | A linear layer on the same network that copies NIMA, a model that learned from 255,000 photos each rated by about 200 photographers (AVA). On NIMA's scale an average photo is about 5; ordinary phone and web pictures score around 4.6, and blurred, dark or crushed copies of them 3.2–3.9 | Follows NIMA closely (Spearman 0.81 on 8,419 held-out pictures, mean difference 0.35 points). NIMA itself agrees with the photographers only roughly (Spearman 0.61), so treat it as a hint |
| **Faces smiling** (`smiling`) | A small network on a patch around each mouth, lined up with the eyes, trained on 13,156 faces marked smiling or not (SMILEs, from Labeled Faces in the Wild), shrunk to phone-photo sizes | 90.5% right on 2,560 held-out faces (AUC 0.96); 86% when the eyes are under 20 px apart |
| **Faces looking at the camera** (`looking_at_camera`) | The head is turned to the camera (nose between the eyes, head not tipped far up or down) and the eyes are open | Geometry, not trained: a sideways glance with the head straight still counts as looking |

Smiles and looking need the mouth and nose positions, which only the built-in face detector (YuNet) gives; with
Android's own detector they stay unknown. They are measured on every photo with a face (counted like "faces with eyes
open"), so every picture is sorted once more at the next Analyse.

**Time.** The scene network takes about 50 ms per photo on a desktop and should take roughly 100–300 ms on a phone; it
runs on up to 4 cores, photos only (not screenshots, papers or chat graphics), newest first, for at most 15 minutes per
Analyse. Photos not reached are done at the next Analyse; until then their scene and score are unknown, and a rule that
tests them does not match. Unchanged photos are never looked at again.

In the Algorithm Lab, **Scene, aesthetic score, smiles (models)** runs the models on the selected picture, whatever its
category: the scene network's 5 likeliest ImageNet classes (and which scene group each counts for), the share of each
group, the sky share, the scene it gives, the aesthetic score, and for every counted face the smile probability, the
head turn and whether it is looking at the camera. **Scene and score on each picture** does the same for all picked
pictures, and **Run everything** includes it. Picture category also shows the scene and score as a scan would.

Rule ideas: `scene = food` and `taken_days > 180` → delete; `aesthetic < 3.5` and `source = chat image (received)` →
delete; `scene = document` and `age_days > 365`; `faces > 0` and `smiling = 0`.

`SceneTest` checks that the Java network gives Keras's numbers for the same weights, which classes count for which scene,
how a scene is decided, the sky measure on drawn pictures, the head-turn geometry, that a scan measures photos only and
keeps the numbers for unchanged ones, saving, and the four tags. With the held-out data (kept outside the repository)
it also checks that Java gives Python's smile probabilities and aesthetic scores. How the models were made:
`tools/scenemodel/README.md` and `tools/smilemodel/README.md`.

## Mirage in the Algorithm Lab (new)

[Mirage](https://cactuscompute.com/blog/mirage) is a 22 MB vision model from Cactus Compute (weights and engine Apache
2.0). In one pass it names the kind of scene, the objects in a picture and reads its text (Latin script only). It runs on
the phone through the Cactus engine, linked into a small native library (`lib/arm64-v8a/libmirage.so`), so it works on
64-bit ARM phones only; on others the Lab says it is not available. Nothing leaves the phone. The 22 MB model is **not
packed in the APK** (an update is a few MB): the Lab and v2 vs v3 show a box with a *Download Mirage model* button. It
fetches the one pinned file from Hugging Face (fixed revision, SHA-256 checked, only that request) into the app's private
files folder, which an app update keeps, so a new version reuses it. Until it is downloaded, the Mirage buttons say so, and
a scan simply skips Mirage.

In the Lab you can try it on any picture (below). Since 1.11 the analysis also runs it and turns what it sees into tags
for the rules ("Mirage in the decision engine", below); the picture category the plan uses stays the app's own (v2).
- **Mirage: scene, objects, text (22 MB model)** on the selected picture: how long it took, the scene (background,
  lighting, style), each object it named with its detection score and description, and each line of text with its score
  and box. Then the app's own text reader (Tesseract) on the same copy, with its time and the words it read, to compare.
- **Mirage on each picture** does the same for every picked picture.
- **Mirage verdict: would the plan delete it?** sorts the picture twice, as a scan does today (with Tesseract) and with
  Mirage, then runs your rules on both and ends with the verdict, e.g. `SUGGEST DELETING (Greeting images from chats)
  (today: KEEP)`. With Mirage:
  - its text, cleaned up (a caption read in overlapping pieces is merged into its longest reading, one- and two-letter
    icon fragments below 0.45 are dropped; other low scores are kept, they are not a reliable sign of a wrong reading),
    goes through the same text rules as Tesseract's (one-time codes, payments, tickets with their date, receipts, bills,
    greetings, ID documents);
  - a meme or forward: a picture received in a chat or saved (Download, Pictures) that Mirage calls drawn ("illustration",
    "cartoon", "graphic design", "poster"...; people in it are drawings), or a collage with captions (8 or more words: a
    comic strip; a photo collage of your family has little text and stays a photo), and whose text is not a ticket, bill,
    ID... is Graphics & images, and the new built-in rule "Old meme or forward" suggests deleting it after a week.
    Mirage's words for real photos ("portrait", "food photography", "close-up") count as photographs;
  - a comic or drawn picture with many words is Graphics & images, not Documents & text;
  - a picture the pixel checks call an accidental shot is not one when Mirage reads its text or names something in it
    clearly (a camera photo where it sees a man becomes People, and is not suggested for deletion);
  - it also lists what no rule uses yet: how many people, and things photographed only to remember them (a price tag,
  a sign, a screen).
  **Mirage verdict on each picture** does it for every picked picture.
  A picked picture whose name says where it came from is treated that way by itself: WhatsApp's names for received
  images (`IMG-20261105-WA0007.jpg`) as a WhatsApp image, `Screenshot_...` as a screenshot (Treat as changes with it).
- The first run loads the model (a few seconds). Run everything leaves Mirage out because it is slow.

What it did in a test on a desktop processor (4 cores), see `tools/mirage/README.md`: about 2 s per picture; text on
clean receipts, tickets, bills and screenshots read exactly, and on photos of them a few letters wrong; its scenes were
no better than the scene network's (90% against 91% on the same 240 labelled pictures; better on flowers, worse on
ordinary objects). The APK is only about 1 MB bigger with it (the engine); the model is downloaded once.

The APK audit checks the model is not packed but downloaded at the pinned revision and hash (MirageModel is the second network user besides the Updater), and that the native library is 64-bit ARM, needs only the C
library, exports only the bridge's three methods and imports nothing that can reach the network, remove or rename a
file, start a process or load more code. Only `MirageEngine` may load it. `MirageTest` checks reading Mirage's answers
and the Lab output with invented data, and the verdict on drawn pictures with saved answers (a greeting forward and a
one-time code screenshot are suggested for deletion, a photo of a person is kept, an "accidental" picture Mirage reads is
not accidental).

### v2 vs v3: which sorting is right more often (new)

**v2** is the app's sorting today (its own text reader, Tesseract, and the picture checks). **v3** is a separate
algorithm (`AlgorithmV3`) that sorts with Mirage; it never changes v2 or the scan. Mirage has no fixed list of labels:
its decoder writes object names, scene words and a style freely (an 8,192-token vocabulary, up to 64 detections), so v3
maps what it says onto the app's 15 categories: its text through the same text rules (one-time codes, payments, tickets,
receipts, bills, chats, greetings, ID documents, documents), memes and forwards from its style (drawn, or a collage
with captions, received or saved), no accidental shot where it clearly sees something, a photo of people when it names
a person with a score of 0.5 or more (the face detector misses small or dimly lit faces in group photos), and the rest
as v2 sorts it.
Both then go through your rules.

Algorithm Lab > **v2 vs v3**:
- Type how many pictures (default 1,000; the newest, or random) and tap **Start**. It takes them from the camera,
  screenshots, downloads, WhatsApp (received and sent) and other pictures, and runs both algorithms on each: about 2-5
  seconds a picture, so 1,000 take about an hour. Keep the page open (the screen stays on). **Stop** and later
  **Continue** carry on where it stopped; the cards and your marks are kept in the app's own storage.
- On top: the score, **v2 : v3**, and how many are compared, left to mark, skipped and where both suggest the same.
- A card shows the picture, and v2's category and suggestion on the left, v3's on the right, with why. Tap **v2 is
  right** or **v3 is right**, or swipe the card towards the side that is right (left: v2, right: v3); the next card
  comes. **Skip** when you cannot tell, **Undo** to take back the last mark.
- Pictures where both agree (the same category and the same suggestion) are counted, not shown (tick "Also show
  pictures where v2 and v3 agree" to mark those too). A different category is a disagreement even when the
  suggestion is the same. You can mark cards while the run goes on.
- At the end the page shows the final score. **Save the score and marks to the Lab log** writes it, with every marked
  picture, to the log you can save or share; the first 150 pictures' full details are in the log too.
- **New run** forgets the cards and marks and lets you pick a new number.

`VersusTest` checks that cards are saved and read back unchanged, the score and the order cards come in, both
algorithms on one picture (with a saved Mirage answer), and which pictures a run takes.

**Faces on received and saved photos (sorting version 6).** Before, the face detector ran only on pictures whose text
was not read, and pictures received in chats, downloads and the Pictures folder always have their text read, so every
such photo of a person came out as "Photos without faces". Now faces are looked for on them too (not on screenshots,
accidental shots or flat drawn graphics such as memes and posters). Pictures already sorted are sorted again once at the
next analysis.

## Mirage in the decision engine (new in 1.11)

The analysis runs Mirage (v3, `AlgorithmV3`) on every sorted picture and keeps what it saw as **tags** the rules can test.
The picture category the plan uses stays **v2's** (the app's own sorting); v3's category is a tag of its own, so nothing
in your plan changes by category until you decide v3 is better (see v2 vs v3).

**How it runs.** After the scene pass: screenshots, chat images, downloads and other pictures first, then camera
photos, newest first in each, within 20 minutes a scan (`Scanner.Options.mirageBudgetMs`); the rest is done at the next
analysis, and pictures already looked at are not looked at again (unless the picture or the sorting changed). About
0.3-1.7 s a picture on a recent phone. On a phone where Mirage cannot run (not 64-bit ARM) the pass stops at once and the
Mirage tags stay unknown. Only short facts are kept, never the text Mirage read, so names, booking IDs and number plates
are not saved (results format 11).

**Faces and people are separate.** Mirage has no face detector: it names whole people ("man", "woman", "child"). The
app's face detector (YuNet) still gives `faces`, eyes open, smiles and looking at the camera; Mirage gives
`mirage_people`, which also counts people with their back turned and small or dim faces the detector misses.

| Tag | Type | What it is |
|---|---|---|
| `mirage_looked` | yes/no, always known | Mirage looked at the picture |
| `mirage_sees` | yes/no, always known | Mirage clearly sees something: a person, a thing it names with a score of 0.5+, or 3+ words. "no" where it has not looked |
| `mirage_style` | text | photograph, drawn, collage, screenshot or unspecified |
| `mirage_people` | number | people it names with a score of 0.5 or more |
| `mirage_objects` | text | the things it names (score 0.3+), e.g. "woman, man, red car"; test with `contains` |
| `mirage_scene` | text | its scene words, e.g. "setting outdoor festival; lighting bright; time of day night" |
| `reference_photo` | yes/no | a photo taken to remember something: a sign, price tag, label, screen, notice, menu... with nobody in it, not drawn, not a screenshot |
| `mirage_category` | text | the category v3 gives (the same 15 categories as `category`) |

Every tag but `mirage_looked` and `mirage_sees` is unknown until Mirage has looked, and an unknown tag never matches.

**New comparison `contains`** for text tags: `mirage_objects contains car, price tag` is true when any of the listed
words or phrases appears as whole words ("car" finds "red car", not "carpet").

**New action "Never delete" (protect).** A protect rule takes away every delete suggestion for the files it matches,
except an identical copy, a sent copy (the original is kept) or a worse shot of a burst; compressing is still suggested.
(A keep rule takes away every suggestion, compressing included.)

**Built-in rules that use Mirage:**
- **Accidental shot** now also needs `mirage_sees is no`: a picture the pixel checks call an accidental shot is not
  suggested when Mirage clearly sees people, things or text in it. Where Mirage has not looked, the rule works as before.
- **Old photo taken to remember something** (new, delete, your call): `reference_photo is yes` AND `age_days > 60`.
- **People seen by Mirage: never delete** (new, protect): `mirage_people >= 1` AND NOT `mirage_style in drawn, collage`
  (drawn people in comics and memes are not protected).

Rule ideas: `mirage_objects contains car` AND `source = download` -> delete; `mirage_style = drawn` AND `chat_media is yes`
AND `age_days > 30` -> delete; `mirage_category = greeting` -> delete (v3's greetings).

In the Lab, **Mirage verdict** now shows the Mirage tags of the picture and three plans: today (v2, no Mirage tags), the
scan now (v2's category with Mirage's tags) and with Mirage (v3's category and tags). `MirageTagsTest` checks the tags,
`contains`, the protect action, the three rules, saving and loading, and the Mirage pass of a scan with a saved answer.

## Runs in the background

- **Foreground service.** The analysis runs in a foreground service, so it keeps going while you use other apps or the screen is off. A notification shows the stage and progress, with a **Cancel** button; tapping it opens the app.
- **Notification when done.** When the plan is ready you get *"Your storage plan is ready: frees X GB"* (or *Analysis failed / cancelled*). If the app is open at that moment, it just shows the plan.
- **Results are saved.** Finished results are stored in the app's private storage (`last_results.bin`, written atomically, so a crash mid-write never damages it). If Android closes the app while it is in the background, reopening it shows *"Loading your last results..."* and then the last plan, without scanning again. Plans are rebuilt from the saved scan, so they are identical.
- **Back where you were.** The screen you were on (plan, a recommendation, a category, an item's details) is restored. Items that no longer exist are skipped.
- **Interrupted scans.** If Android stops the app in the middle of a scan anyway (rare, e.g. "force stop" or a very aggressive battery saver), the start screen says: *"The last analysis was interrupted. Tap Analyse to run it again."* Earlier results stay available under **Open last results**.

## App updates (new in 1.9)

- **On every start** the app asks GitHub for this project's latest release (`GET https://api.github.com/repos/pncodes10/storage-planner/releases/latest`). Nothing about your phone or files is sent. If the release is newer than the installed version, a dialog offers **Update now** or **Later**.
- **Settings: app updates** on the start screen shows the installed version, a **Check for updates** button, and a switch to stop the check at start-up.
- **Update now** downloads the release's APK (only from this project's release address), compares its SHA-256 with the `.apk.sha256` file published beside it, confirms it is this app and a higher version code, and opens Android's installer. Android itself requires the same signing key as the installed app and asks you to confirm; an app cannot install silently. The first time, Android asks you to allow Storage Planner to install apps.
- Pre-releases and drafts are ignored. A build from CI (`1.9.0-abc1234`) is not offered the release of the same version.
- Only `app/.../Updater.java` touches the network; `tools/audit_apk.py` fails the build if any other class does, or if it can delete or rename files outside its known places.

### Making a release

1. Run `tools/release.sh 1.9.1` on the main branch. It raises `versionName`/`versionCode` in `app/build.gradle` and `tools/build_offline.sh`, commits, tags `v1.9.1` and pushes the tag. (`--no-push` only commits and tags.)
2. The **Release** workflow (`.github/workflows/release.yml`) runs on the tag: it checks the tag matches `versionName`, runs the tests, builds, signs with the repository secrets `SIGNING_KEY_PEM` / `SIGNING_CERT_DER_B64` (it fails without them: a release signed with a one-off key cannot update an installed app), audits the APK, and publishes `StoragePlanner-1.9.1.apk`, `StoragePlanner-1.9.1.apk.sha256` and the audit report as a GitHub release.
3. Phones that have 1.9.0 or later find it at their next start. 1.8.1 and older have no update check: install 1.9.0 by hand once.

## Permissions

The app also contains one private, read-only file provider. It isn't exported, and it only hands one file at a time to an app you pick under Open with or Share, through a temporary read grant.

- **All files access** (`MANAGE_EXTERNAL_STORAGE`; `READ_EXTERNAL_STORAGE` up to Android 12): read photos, videos, downloads and WhatsApp media. Read only, except the Lab's test copies in `Download/StoragePlanner/`.
- **`WRITE_EXTERNAL_STORAGE`** (Android 8-10 only): save the report, the Lab log and the Lab's test copies to Download.
- **Usage access** (`PACKAGE_USAGE_STATS`, optional) and **`QUERY_ALL_PACKAGES`**: app sizes, caches and last-used dates for every installed app.
- **`FOREGROUND_SERVICE`** and **`FOREGROUND_SERVICE_DATA_SYNC`** (new in 1.2): run the analysis as a foreground service of type *dataSync* (local file processing) so it is not stopped when you switch apps.
- **`POST_NOTIFICATIONS`** (new in 1.2, asked on Android 13+): show the progress and "plan is ready" notifications. Optional.
- **`WAKE_LOCK`** (new in 1.2): keep the processor awake during the scan when the screen turns off (released when done, at most 60 minutes).
- **`ACCESS_MEDIA_LOCATION`** (new with the Motion Photo Lab, asked on Android 10+ when the Lab opens): without it Android hides the GPS location inside photos from the app, so a Motion Photo copy would silently lose it. It only lets the app read the location already stored in your photos; nothing is sent anywhere. Optional: without it the Lab warns that copies may lack the location.
- **`INTERNET`** (new in 1.9): only for the update check and download (see **App updates**). The audit fails if any class other than `Updater` uses the network.
- **`REQUEST_INSTALL_PACKAGES`** (new in 1.9): lets the app open Android's installer for a downloaded update. Android still asks you to confirm, and the first time asks you to allow Storage Planner to install apps.

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

The suggestions for single files are now **rules**, and you will be able to add your own. A rule is a list of conditions that must **all** be true, plus one action. Each condition tests one tag of a file (see Tags: `age_days`, `source`, `category`, `faces`, `blur`, `brightness`, `size_mb`, ...) and can be turned around with NOT. If you need "or", write two rules. The actions are delete, re-encode (photo, lossless image, video, audio), keep, and never delete (protect: no delete suggestion, compressing allowed). For example:

- `source = chat image (received)` AND `category = greeting` AND `age_days > 3` AND NOT `faces > 0` -> delete
- `camera_photo = yes` AND `blur > 70` AND `brightness < 20` -> delete
- `faces > 0` -> keep (never touch pictures with people)

**Unknown means no.** If a tag was never measured for a file (faces in a screenshot, blur with too few camera photos, a date that was not read), the condition is false, also when it has NOT. So `NOT faces > 0` does not match a picture whose faces were never checked.

**Today's suggestions are built-in rules** (`BuiltInRules`: thumbnails, temp files, exact duplicates, bursts, old payments, re-encoding of camera photos, ...). With no rules of your own the plan is exactly the one the app made before; a test compares the two on tens of thousands of files. A built-in rule can be switched off or have its numbers changed (for example greetings after 3 days instead of 7). Apps and app caches are still decided in code, not by rules.

**Limits on your rules**, enforced in the core and not only on screen:
- A delete from your rule is always R4: it needs your confirmation. A re-encode is always R2. Its technique is shown as "My rule: name".
- A keep rule removes every suggestion for the files it matches, built-in ones included.
- A never-delete (protect) rule removes every delete suggestion except an identical copy, a sent copy or a worse burst shot; compressing stays.
- ID documents never get a delete suggestion from any rule; only an identical extra copy may go.

Rules are saved as plain text (`RuleSet.toText()`, one block per rule, versioned). A rule that names a tag this version does not know is loaded switched off.

**Every rule is for one file type.** A rule says which files it is for: any file, a kind (pictures, videos, music and
audio, documents, app installers, other files) or one format (JPEG, PNG, WebP, HEIC, AVIF, MP4, MKV, MP3, FLAC, M4A,
OGG, PDF, ZIP). It never matches a file of another type; the file type is read from the file's first bytes, not its
name. On top of that, a compress action only ever touches its own kind of file, whatever the rule says: compress
photo and compress screenshot only pictures, compress video only videos, compress music only music and audio. So a
music rule can never act on a JPEG, even one set to "any file". A rule whose file type does not fit its action
("compress music" for JPEG pictures) cannot be saved, and if one is found in the saved rules it is loaded switched off.

The built-in rules have fixed file types: the photo re-encodes are for JPEG, the lossless screenshot rule for PNG,
video for videos, music for music and audio; the picture categories, bursts, blur, sent copies and accidental shots
for pictures; installers for app installers; the area rules (thumbnails, temp files, trash, ...), exact duplicates
and old downloads for any file. Before, a built-in rule kept to its type only through its conditions (for example
"file type is png"), and a rule of your own had no type at all: "age more than 365 days -> compress music" would have
matched JPEGs too. The old-against-new test (`RulesEquivalenceTest`) still finds every option identical on files
that can occur in a scan; its random files no longer give a category to a video or a music bitrate to a JPEG, which a
real scan never does. A file outside the media folders (camera, screenshots, pictures, downloads, music, movies,
WhatsApp, installers) has no file type read during a scan, so a rule for a format does not match it.
Rules saved before file types existed load as "any file".

### The Rules screen

Open **Your rules** from the start screen or from the overview (next to the Algorithm Lab button). Your own rules come first, then the built-in ones. Each row says which files the rule is for ("For JPEG pictures only") and in words what it looks for ("Picture category is Greetings & forwards, age more than 7 days and is chat or Telegram media"), what it does and its risk, and, once you have analysed your storage, how many files and how much space it matches right now. Scanning is not repeated: the counts and the plan come from the scan already on the phone.

- **Switch a rule off or on** with its switch. A rule that is off suggests nothing; a rule that cannot run (it names a tag this version does not know) stays off and says why.
- **Change a built-in rule:** tap it. Its name, action and conditions are fixed, but its numbers can change (greetings after 3 days instead of 7). **Reset to default** appears on a changed rule, in the list and in the editor.
- **Add a rule:** tap **Add a rule**. Give it a name, pick what it does (Delete, Compress photo, Compress screenshot (lossless), Compress video, Compress music, or Keep), pick its **File type** (any file, a kind or one format; the formats are listed under their kind), and add conditions. Each condition is a tag (shown by its plain label, such as "Age in days" or "Picture category"), a comparison that fits the tag (more than, at least, less than, is, is not, is one of; yes or no for yes/no tags), a value (a number with its unit, or a choice from the values the app knows plus the ones your scan has), and an optional NOT. All conditions must be true; for "or", write two rules.
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

**Mirage (optional for your own builds):** `bash tools/mirage/fetch.sh` downloads the model and the engine at pinned
hashes, and `ANDROID_NDK=<NDK r28+> bash tools/mirage/build_native.sh` builds the bridge; `build_offline.sh` then
includes both. Without them the app builds as before and the Lab says Mirage is not in this build. The workflow always
includes them.

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
  - **Sample:** Try on my pictures: picks pictures at random from each place and runs the sorting and the rules on them.
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
  - **Updater:** the update check, checked download and hand-off to the installer (the only network code); `core/Version` compares versions (UpdateTest).
  - **AnalysisService / AnalysisRunner / AnalysisState:** the foreground service, the pipeline and the live progress the screen observes.
  - **CompressCompareScreen:** the Lab's original vs compressed view: side by side, flip, and amplified difference.
- `app/src/main/java/com/storageplanner/tools/`: **tools** for the future action taker, never called by the planner. Today: image compression (ImageCompressor, HeifEncoder, Guard, Ssim, Exif, WebpMeta, CopyPaths). See its [README](app/src/main/java/com/storageplanner/tools/README.md).
  - **ResultFiles:** saves and loads results in app-private storage (AtomicFile).
- `jvmtest/`: runs the unchanged core on a desktop against the virtual phone from the study, plus classifier, planner, review, saved-results (ResultStoreTest), file tags (TagsTest), rules (RulesTest, RuleMatchTest for matches, wording and checks, and RulesEquivalenceTest against a copy of the old hard-coded builder), group-tick (GroupDecisionTest), eye-model (EyeStateTest), photo-quality (PhotoQualityTest), face detector against OpenCV (FaceNetTest), picture-category (CategoryTest, TextRulesTest, PageTest for pages of paper and re-sorting), reader-bridge (OcrBridgeTest), Algorithm Lab (LabTest on real pictures; LabFilesTest for picked-picture slots and decode failures, no pictures needed; SampleTest for Try on my pictures; PhotoMetaTest for favourites, date taken, screenshot app, Motion Photos and code type; SceneTest for scene, aesthetic score, smiles and looking at the camera) and image-compression rules (CompressionTest) tests.
- `tools/eyemodel/`: how the eye model was trained and checked.
- `tools/test_ocr_page.py`: runs the app's offline text-reader page in Chromium the way the app serves it.
- `tools/ocr_assets.sha256`, `tools/face_assets.sha256`: pinned hashes of the text-reader and face-detector files (checked by the audit).
- `tools/facemodel/`: converts YuNet for the app, and records OpenCV's answers for FaceNetTest.
- `tools/`:
  - **release.sh:** bumps the version, tags `vX.Y.Z` and pushes it (starts the Release workflow).
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
