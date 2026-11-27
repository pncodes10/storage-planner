# The analysis: biggest files first, one at a time, pause any time

The analysis used to run in stages over every file (measure every photo, then read text in every picture, then scenes,
then Mirage, then videos), with a time limit per stage, and nothing was usable until the end. Since 2.0.0 it looks at
**one batch of files at a time, the biggest first**, and gives each file every check before moving on. You can pause
whenever you like and use the plan made from the files done (`core/Scanner.analyse`).

## The order

1. **The file list, in seconds.** Every file is listed and typed, and exact duplicates are found. Files that need no
   look inside (installers, old downloads, backups, junk, documents) are decided right here.
2. **Files done last time, as they were.** An unchanged file analysed before only copies its measurements, so after a
   pause the plan so far is back in seconds.
3. **The rest, biggest first.** Pictures, videos and music, 12 at a time. Each one gets everything:
   - its date taken;
   - its fingerprint and sharpness;
   - faces, codes and text;
   - what a photo shows;
   - Mirage;
   - a video's index, and its frames when the index finds something.

## Checks that compare files

| Check | Needs | While paused |
|---|---|---|
| Exact copies | every file's size and hash | done with the file list, so always complete |
| **Similar shots** (keep the best) | every camera photo taken within 10 minutes of each other | a group with a photo of its moment not looked at yet **waits**: nothing in it is removed. When a photo is done, the photos taken within minutes of it come next, so groups finish quickly. |
| Blurry photos | the median sharpness of your camera photos | from the photos done so far; a photo with a shot of its moment still to come is not called blurry yet |
| WhatsApp copy of a camera photo or video | the original, analysed | found once both are done; until then the copy is kept |

A file not looked at yet is **pending**: it gets no suggestion at all, and none of the checks above counts it.

## Play, pause and stop

- **Pause** (on the progress screen, or in the notification) stops after the files being looked at. It saves what is
  done and opens the plan made from it. A file cut short is done again next time.
- **Carry on** (on Clean up, or on the start screen) lists the files again. The files done are taken over and the
  files no longer on the phone are gone from the list, so it goes on with the next biggest file.
- **✕** asks what to do:
  - **Stop and keep the plan**: the same as pause.
  - **Throw it away**: the saved results are deleted, and the next analysis starts from the beginning.
- What is done is also saved every minute. If the app is killed, it opens as a paused plan.

The per-stage time limits are gone: you decide when to stop. Mirage is not tried again in that analysis once it cannot
run, and neither is the text reader when the phone has none.

## The progress screen

It shows:
- what can be freed so far;
- how much is looked at, in gigabytes and in files;
- the file being looked at, and its check;
- the piles ready so far.

The plan is rebuilt every 4 seconds at most. When rebuilding is slow, it is rebuilt less often, at most one fifth of the
time.

## Saved results

Format 14 adds whether each file is pending. Results saved by older versions load as all done.

## Tests

`AnalysisOrderTest` (in CI) builds a small phone with invented pictures:
- three shots of one moment;
- an unrelated photo;
- a download.

It checks:
- that the biggest file is looked at first, and the shots of its moment come next, before a bigger unrelated photo;
- that a pause leaves the rest pending, without suggestions;
- that the two shots done wait for the third;
- that pending files are saved and loaded back;
- that carrying on ends with the same groups and suggestions as a whole analysis;
- that a file removed while paused is counted as gone.
