# Checking videos

The app only asks whether a video is **junk**: taken by mistake, black or covered, blurred, still or silent, or a
copy. It does not try to understand what a video shows. To stay fast, it reads the video's index first and decodes
pictures only when the index finds something suspicious (`core/VideoIndex`, `core/VideoCheck`).

## Step 1: the index, every video, about 10–50 ms

An MP4 file lists how big every compressed frame is, which frames are keyframes, and how big each chunk of sound is.
The app reads that list without decoding anything:

| From the index | What it shows |
|---|---|
| Keyframe size, in bits per pixel | Detail. A black, covered or badly blurred keyframe compresses to almost nothing: under 0.04 bits per pixel counts as "next to nothing". |
| Size of the frames between keyframes | Movement. Under 0.002 bits per pixel, nothing changes, like a phone lying still. |
| Bytes of sound per second | Quiet. A second counts as quiet when it is under 1,500 bytes, or under a quarter of the video's loud seconds. |
| Keyframes all the same size | The encoder pads them, so their sizes tell nothing. The video is then checked by decoding frames. |

A video gets a closer look only when it is under 2.5 s long, at least half its keyframes show next to nothing,
nothing moves in at least 80% of it, or its keyframe sizes tell nothing. Everything else is done here, with no frame
decoded.

## Step 2: the richest frames, suspicious videos only, about 0.5–1 s

The app decodes the **3 keyframes with the most detail** (at least 1 s apart), plus the middle frame for its
fingerprint. These are the frames most likely to prove the video is worth keeping:
- **If even the richest frames are dark, covered or blurred**, the rest of the video holds even less, so it is junk.
- **If any of them shows a face or real content**, the video is kept.

A frame is dark only when it is also almost featureless: average brightness under 35 and spread under 20, or a spread
under 10 (flat). Night scenes with lights in them have a wide spread, so they are not dark.

Videos without a usable index (not MP4, or damaged) get 5 frames spread over the clip instead, as before.

## The junk score: 0 to 100

| Sign | Points |
|---|---|
| Under 2.5 s long | +60 |
| 2.5–5 s long | +15 |
| Dark or covered decoded frames | +60 × their share |
| Blurred all through: the sharpest frame is under 25% of your camera photos' median sharpness (not counted when every frame is dark) | +30 |
| Nothing moves | +20 |
| No sound track, or quiet in at least 90% of seconds | +10 |
| A face in any decoded frame | score becomes 0 |

**60 or more** means taken by mistake. The rule `accidental_clip` (camera videos) suggests removing it, in the
"Blurry and accidental shots" pile, and the reason shows the score.

Examples:
- **A pocket recording:** dark 3 of 3 (+60), still (+20), quiet (+10) = **90**, so it is removed.
- **A 1.2 s tap:** +60, so it is removed.
- **A deliberate night clip:** the index looks fine, so nothing is decoded, and the score is **0**: kept.

## Copies

- **Exact duplicates:** byte for byte, as for every file.
- **The WhatsApp copy of a camera video:** same length within 1.5 s, same orientation, sent after it was filmed, and
  middle-frame fingerprints at most 12 bits apart. Only the middle frames of sent videos, and of camera videos of the
  same length, are decoded for this.

The old "forwarded video" rule is gone: it guessed from how a clip looks. The tag `forward_like_video` is still there
for your own rules.

## Tags

The video tags are:
- `video_seconds`, `video_fps` and `vertical_video`;
- `video_looked`;
- `dark_video`, `blurry_video`, `still_video` and `silent_video`;
- `video_people` and `video_text_words`;
- `video_junk_score` and `accidental_clip`;
- `forward_like_video`.

## Trying one video in the Algorithm Lab

Open Algorithm Lab › Videos, pick a video, set **Treat as**, then tap **Video verdict**. The log shows:
- the index: frames, keyframes, keyframe detail, the share of low-detail keyframes, stillness and quiet seconds;
- whether the index was enough, or why it took a closer look;
- each decoded frame;
- the junk score with each sign that added to it;
- the tags, the matching rules and the verdict.

The frames shown under the verdict are the ones the check decoded. When the index was enough, frames spread over the
video are shown just for you to look at.

## Tests

`VideoCheckTest` (37 checks, in CI) writes small MP4 indexes:
- a pocket recording;
- a normal clip;
- an encoder that pads keyframes.

It then checks:
- what the index reader finds in them;
- that a normal clip needs no decoding;
- that a pocket recording decodes 3 frames and scores 90;
- that rich frames keep a video the index found suspicious;
- the score's signs and the sent-copy match;
- the rule and the pile;
- that the results survive a save, and the Lab verdict.

`LegacyUnitBuilder` and the random phones in `RulesEquivalenceTest` follow the same rules, and the results are
identical.
