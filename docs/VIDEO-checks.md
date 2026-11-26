# Looking inside videos

Before this change the app judged a video only from its file: where it is, whether it's a byte-for-byte duplicate, and
whether re-encoding it would save space. Nothing looked at what's in the video. `core/VideoCheck` now decodes five
frames of each video and runs the checks pictures already have on them.

## What is measured

The five frames are taken at 8%, 30%, 50%, 70% and 92% of the clip, with a long edge of 1024 px. For each video:

| Measurement | How |
|---|---|
| Dark or covered frames | The mean brightness is under 35, or the spread is under 10 (a covered lens, a pocket, a wall) |
| Movement | The average number of fingerprint bits that change between neighbouring frames; under 3 means nothing happens |
| Sharpness | The sharpest frame, on the same scale as photos, compared with your camera photos: under 25% of their median means blurred all through |
| Faces | The most faces in any one frame (the photo face detector) |
| Words | The text read in the middle frame (captions, memes) |
| Fingerprint | The middle frame's fingerprint, used to find WhatsApp copies |
| Frame rate | Read from the video track. It is now used in the compression estimate instead of always assuming 30 |

It runs at the end of a scan, chat videos first, newest first, within 10 minutes. Unchanged videos keep their
measurements from the last scan. It also runs for videos you sent on WhatsApp, so their copies can be matched.

## Tags

| Tag | Meaning |
|---|---|
| `video_seconds` | Length |
| `video_fps` | Frame rate |
| `vertical_video` | Upright |
| `video_looked` | Its frames were looked at |
| `dark_video` | At least 4 of 5 frames are dark or covered |
| `blurry_video` | Blurred all through |
| `still_video` | Nothing moves |
| `video_people` | Faces in it |
| `video_text_words` | Words in the middle frame |
| `accidental_clip` | Nobody in it, and short (under 2.5 s), dark, blurred or still |
| `forward_like_video` | Up to 3 minutes, and upright or with at least 4 words of caption |

The tags you can't know without looking are unknown until the frames have been looked at, and an unknown tag never
matches a rule. A clip with a face in any frame is never called accidental and never counts as a forward.

## Rules

| Rule | Removes | When | Pile |
|---|---|---|---|
| `video_sent_copy` | The WhatsApp copy of a camera video (the original stays) | Same length within 1.5 s, same orientation, sent after it was filmed, middle frames at most 12 bits apart | Copies of the same thing |
| `accidental_clip` | Camera clips taken by mistake | `accidental_clip` is yes | Blurry and accidental shots |
| `old_forward_video` | Old forwarded videos | Received in a chat or downloaded, over 30 days old, `forward_like_video` is yes, no faces | Memes and forwards |

The rules can be switched off or edited in Settings › Cleaning rules, like the other rules.

## Tests

- `VideoCheckTest` (29 checks, in CI) uses frames drawn in the test, not real videos. It covers:
  - measuring the frames;
  - what the measurements mean;
  - matching sent copies;
  - the three rules in the plan;
  - the piles and their plain reasons;
  - the real frame rate in the compression estimate;
  - saving and reading back.
- The test copy of the old engine (`LegacyUnitBuilder`) and the random phones in `RulesEquivalenceTest` have the video
  rules too. The rules and the old engine give the same options on all of them.
