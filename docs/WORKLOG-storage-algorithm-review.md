# Work log: storage algorithm review

Branch `claude/storage-algorithm-review-vzx17l` (app 1.8.1 builds, results format 11, sorting version 6).
This file records what was built on this branch, why, how it was checked, and what is still open, so the next piece of
work can start from here. The README describes the features for users; this log is the engineering history.

---

## 1. Where things stand

| Area | State |
|---|---|
| Picture sorting (v2) | Sorting version 6; faces on every possible photo; event tickets recognised |
| Tags | 61 tags on every file, including 8 from Mirage |
| Rule engine | 30 built-in rules; user rules; file types; new `contains` comparison; new **protect** ("never delete") action |
| Scene, aesthetic, smiles | MobileNetV3-Small in plain Java (5 MB), aesthetic score, smile and looking-at-camera models |
| Mirage (v3) | 22 MB vision model on the phone through a native bridge; in the Lab, in the v2 vs v3 page, and as tags in every analysis |
| Category used by the plan | **v2** (decision "option (a)"); v3's category is the tag `mirage_category` |
| Versions | `Sorter.VERSION` 6, `Sorter.SCENE_VERSION` 1, `AlgorithmV3.VERSION` 1, `ResultStore.VERSION` 11, `RuleSet.VERSION` 1 |

### The decision path of one picture (today)

```
scan (Scanner)
 └─ sort (Sorter, v2)
     ├─ pass 1  measure: ImageSignals, codes, faces (YuNet) + eyes/smile/looking  -> category from pixels
     ├─ pass 2  read text (Tesseract in a WebView, 20 min budget)               -> TextRules -> category (v2)
     ├─ pass 3  scene network + aesthetic score (photos, 15 min, up to 4 threads)
     └─ pass 4  Mirage (every sorted picture, 20 min, one at a time)             -> AlgorithmV3 -> mirage_* tags, v3 category
 └─ tags (Tags.of) ── rules (RuleSet: keep -> deletes -> one compress -> protect -> ID rail) ── units/options ── plan
```

---

## 2. Timeline (commits on this branch)

| Date | Commit | What |
|---|---|---|
| 17 Nov | b41088b | Decision graph of how one file gets its action (`docs/algorithm-decision-graph.html`) |
| 17 Nov | ab2d1ec | Tags on every file; picture measurements saved (format 4) |
| 17 Nov | 4a6b528 | Rule engine: built-in rules reproduce the old planner exactly; user rules |
| 17 Nov | 8edcb3c | Rules in the app: storage, Rules screen, rule editor with live preview |
| 17 Nov | c328e85 | Photos of paper pages (handwriting, dim light) are Documents (format 5, sorting v1) |
| 17 Nov | 47816ab, e244f17 | Algorithm Lab: why a picture cannot be decoded; copies without delete/rename |
| 17 Nov | a2e387a | ID cards held up in photos; eyes open on every face; rules in the Lab (format 6, sorting v2) |
| 17 Nov | 3a0b6cb | Night shots: focus ignores sensor grain; glare share tag (format 7, sorting v3) |
| 18 Nov | 5c86a0e, 274e911 | Rules are file-type specific; Try on my pictures (Lab check and real limited analysis) |
| 18 Nov | 8e55345 | Portrait-mode shots are not accidental (format 8, sorting v4) |
| 18 Nov | 42efa25 | Tags: favourite, date taken, screenshot app, Motion Photo, code type (format 9) |
| 18 Nov | 3a31aa3, 42baaff | Scene, aesthetic score, smiles, looking at the camera (format 10, sorting v5) |
| 19 Nov | 4417461 | Lab: run the scene, aesthetic and smile models on a picture |
| 22 Nov | d75d281 | Mirage in the Lab: native bridge, pinned download, APK audit rules |
| 22 Nov | 38bddc1 | Lab: Mirage verdict (would the plan delete it?) |
| 23 Nov | 445b6a3 | Memes and forwards: Mirage finds comics; built-in rule "Old meme or forward" |
| 23 Nov | 3a44b69 | Lab: Treat as set from WhatsApp and screenshot file names |
| 23 Nov | c8c6b1c | v3 as its own algorithm (`AlgorithmV3`); v2 vs v3 comparison page |
| 23 Nov | 5511d5b | v3: People when Mirage names a person; category differences are disagreements |
| 23 Nov | 3271804, c96dc72 | Sorting v6: faces on received, downloaded and saved photos |
| 23 Nov | e772ce1 | Event and concert tickets (M-Ticket) recognised |
| 23 Nov | (Mirage tags commit) | Mirage in the decision engine: tags, `contains`, protect action, 2 new rules, accidental guard, scan pass (format 11) |

---

## 3. What was built, area by area

### 3.1 Tags and the rule engine (17-18 Nov)
- **Tags** (`core/Tags.java`): named facts per file (source, area, kind, size, age, category, text, faces, eyes, blur
  against your own camera photos, brightness, dark/glare/flat/paper/ink shares, duplicates, bursts, sent copies,
  installers, ID document, dates in pictures, file type, codecs, bitrates...). Unknown is never 0: a fact that was not
  measured is unknown.
- **Rules** (`core/Rule.java`, `RuleSet.java`, `BuiltInRules.java`, `RuleText.java`): conditions (AND, each can be NOT)
  on tags plus one action. **Unknown rule (safety):** a condition on an unknown tag is false, also with NOT. The
  built-in rules reproduce the old hard-coded planner; `RulesEquivalenceTest` compares the engine with
  `jvmtest/.../LegacyUnitBuilder` (the old planner, kept in step with every new built-in rule) on ~88,000 options.
- Limits on user rules: delete is always R4 (confirm each), re-encode R2; ID documents never get a delete except an
  identical copy; every rule has a file type and a compress action only touches its own kind of file.
- Rules screen and editor (`app/RulesScreen.java`, `RuleEditScreen.java`, `RulesStore.java`): live match counts,
  preview grid, warnings for broad delete rules.

### 3.2 Sorting fixes (17-18 Nov)
- Pages of paper by look alone (white level, smooth plain paper, ink spread), so handwriting and dim light work.
- ID cards held up in photos; "Government of" with the country cut off; gender after its Indian-language word.
- Eyes open on every photo with a face. Night shots: focus ignores sensor grain; glare share.
- Portrait mode (sharp subject, blurred background) is not an accidental shot: the sharpest parts must be soft too.
- Each picture stores the sorting version; raising `Sorter.VERSION` re-sorts pictures once (text/code verdicts kept).

### 3.3 Scene, aesthetic score, smiles (18-19 Nov)
- `SceneNet`: MobileNetV3-Small, Keras ImageNet weights (Apache 2.0), 16-bit, 5 MB, plain Java; same numbers as Keras.
- Scene tag from ImageNet class groups (food, nature, pet, vehicle), document from the category, selfie from face size,
  sky from pixels. 92.5% on 29,468 labelled pictures.
- Aesthetic score: linear layer on the network's features distilled from NIMA (Spearman 0.81).
- Smile model (mouth patch, SMILEs data, 90.5% held out) and looking at the camera (head turn + eyes open).
- Best shot: smiles and faces turned to the camera add to the score. Lab button shows all of it on one picture.

### 3.4 Mirage: research and evaluation (22 Nov)
- Mirage (Cactus Compute, 6 Nov 2026): 51.3 M parameters, 2-bit, 21.6 MB; one pass gives scene words, named objects
  (boxes, masks), text lines (Latin script) and relations. Weights and engine Apache 2.0.
- **No fixed label list**: its decoder writes names freely (8,192-token vocabulary, up to 64 detections).
- Desktop test (4-core x86): 1.4-2.2 s a picture. Phone: 0.26-1.7 s, depending on how much it finds.
- Scene from object names vs SceneNet on the same 240 labelled pictures: 90.4% vs 91.2% (better on flowers, worse on
  ordinary objects: it names trees and grass that barely show). Its "pick one of our labels" form answers were poor.
- Text: clean invented receipts, tickets, bills, notes, chat screenshots read exactly; photographed copies a few letters
  wrong. Clearly better than Tesseract on screenshots and photos.
- Details: `tools/mirage/README.md`.

### 3.5 Mirage on the phone (22 Nov)
- Native bridge `app/src/main/cpp/mirage_jni.cpp` linked with `libcactus.a` (engine 4.1.4, android-arm64) into
  `lib/arm64-v8a/libmirage.so`; uses only `cactus_load` (the engine copies the model) and `cactus_describe` (RGB in,
  JSON out, retried with a bigger buffer when needed). Defines its own `remove()` that always refuses.
- `app/MirageEngine.java` loads the library and the model downloaded by `MirageModel` (files/mirage/mirage.cact, kept across updates); one picture at a time.
- `tools/mirage/fetch.sh` downloads model and engine at pinned revisions with sha256 checks (`files.sha256`); the
  model is not in git. `tools/mirage/build_native.sh` needs NDK r28+ (the engine uses LLVM 19's libc++).
- APK audit (`tools/audit_apk.py`): Mirage files pinned; the library must be AArch64, need only libc/libm/libdl,
  export only the bridge's three methods, import nothing for network, file removal/renaming, processes or code loading;
  only `MirageEngine` may call `System.loadLibrary`. The file-deletion guardrail is unchanged.
- APK grew by about 23 MB (31 MB).

### 3.6 Mirage verdict, memes, v2 vs v3 (22-23 Nov)
- `core/Mirage.java`: reads the answer; text clean-up (overlapping caption pieces merged into the longest; 1-2 letter
  icon fragments below 0.45 dropped; low scores otherwise kept: a number plate read well scored 0.40, a misread logo 0.64);
  style words (photograph / drawn / collage / screenshot; "portrait", "food photography", "close-up" are photographs);
  people (whole people, score threshold 0.5; "face" does not count, Mirage says "cat face"); reference objects.
- `core/AlgorithmV3.java` (v3): Mirage's text through the same `TextRules`; accidental overruled when Mirage clearly
  sees something; People when it names a person (score 0.5+) where the face detector found none; memes and forwards
  (received or saved, drawn or a collage with 8+ words, text of no other kind) are Graphics & images; drawn pictures
  with many words are not Documents.
- Built-in rule **Old meme or forward**: Graphics & images older than 7 days in wa_image, telegram_media, download,
  pictures.
- Lab: Treat as set from WhatsApp names (`IMG-yyyymmdd-WAnnnn`) and screenshot names.
- **v2 vs v3 page** (`app/VersusScreen.java`, `core/Versus.java`): runs both on up to N pictures (newest or random),
  one card per picture, mark v2 or v3 right (buttons or swipe), Skip, Undo, score on top; cards saved in the app's own
  storage (`files/versus/cards.jsonl`, `todo.txt`); pictures that agree (same category and same suggestion) are counted,
  optionally shown.

### 3.7 Fixes found with real pictures (23 Nov)
- **Group photo at night** (people too small and dim for the face detector): v3 now People (Mirage named 17 people).
- **Received photos were never checked for faces** (sorting v6): faces were only looked for on pictures whose text was
  not read; chat images, downloads and Pictures are always read, so every received photo of a person was "Photos
  without faces". Now checked too, except screenshots, accidental shots and flat graphics (flat share 0.35+).
- **Event ticket** (BookMyShow M-Ticket): only 1 of the needed 3 ticket words matched. Added m-ticket, ticket(s),
  venue, date & time, booking is confirmed, admit, district, paytm insider, ticketnew, zomato live. An invitation with
  only venue and date & time stays a document.

### 3.8 Mirage in the decision engine (23 Nov)
Decision: **option (a)**: the plan keeps v2's category; Mirage's findings become tags; v3's category is a tag.

- **Scan pass 4** (`Sorter.mirage`): every sorted picture; non-camera first, then camera; newest first; 20 min budget
  (`Scanner.Options.mirageBudgetMs`, 0 = off); stops at once where Mirage cannot run; unchanged pictures keep what
  Mirage saw unless the sorting changed (`copyMirage`).
- **Recorded** (`AlgorithmV3.record`, `FileItem.mirage*`, `v3Category`, `v3Date`): style, people (score 0.5+),
  objects (score 0.3+, max 24, no "text"), scene words (max 200 chars), reference photo, "sees something", v3 category.
  **Never the text it read.** Saved in results format 11 (format 10 files load with Mirage not looked at).
- **Tags**: `mirage_looked`, `mirage_sees` (both always known; "no" where Mirage has not looked), `mirage_style`,
  `mirage_people`, `mirage_objects`, `mirage_scene`, `reference_photo`, `mirage_category`.
- **Faces vs people**: Mirage has no face detector. `faces` (YuNet: faces with landmarks, eyes, smiles) and
  `mirage_people` (whole people, back turned and small/dim faces included) are separate tags.
- **Comparison `contains`** for text tags (whole words, any of a comma list).
- **Action PROTECT** ("Never delete"): removes delete options except exact duplicates, sent copies and burst extras;
  compression stays. Applied after compress, before the ID rail. In the rule editor and rule wording.
- **Built-in rules** (30 now):
  - Accidental shot: added `mirage_sees is no` (uses the always-known tag so the rule still works where Mirage did not look).
  - **Old photo taken to remember something** (`old_reference_photo`, delete, R4): `reference_photo is yes` AND `age_days > 60`.
  - **People seen by Mirage: never delete** (`protect_people_mirage`, protect): `mirage_people >= 1` AND NOT `mirage_style in drawn,collage`.
- **Lab Mirage verdict** shows the Mirage tags and three plans: today (v2, no Mirage tags), the scan now (v2 + Mirage
  tags), with Mirage (v3 + tags). The v2 vs v3 page's v3 side uses Mirage's tags too.

### 3.9 Product UI: the final plan (23 Nov)
- Home (`SetupScreen`) rebuilt: storage card with meter, permissions only while missing, plan card, one Analyse
  button, ⚙ Settings; the Algorithm Lab only in developer mode.
- `core/FinalPlan`: every file's suggested action (delete > compress > keep, from the unit's options), your choice
  (`PlanStore`, `files/finalplan.tsv`), earlier kept/approved decisions as choices, compress only where an option
  exists, totals per type and action, tab lists (unticked files stay in their tab), groups by reason. `FinalPlanTest` (21).
- `PlanScreen` (Your plan): freed space, three-action bar, file-type rows with action chips, installed apps,
  Finalize plan / Edit plan / Start over, Move to Recycle bin.
- `PlanTypeScreen`: Delete / Compress / Keep tabs; Gallery (ListView rows of 3, reason headers with Tick all / Untick
  all, ticks red for delete and blue for compress, unticked dimmed, long-press opens the viewer, Keep tab Select ->
  Delete / Compress) and Cards (swipe, buttons, undo); read-only with a banner when finalized.
- `SettingsScreen`: target, measure, sort pictures, Your rules, Developer mode switch, About. Settings in the action bar
  menu on Home and Your plan. After an analysis the app opens Your plan; the classic overview is in the Lab.
- `TrashAction`: looks files up in MediaStore and would ask Android to move them to its trash (30 days, restorable),
  in batches of 1,000, removing moved files from the results and the plan afterwards. **Not switched on**: changing
  the APK audit to allow `MediaStore.createTrashRequest` (from TrashAction only) was refused by the session's
  permission check because the owner's standing rule says the file-deletion guardrail must not be loosened. The build
  has no trash request (`request()` returns null, `enabled()` false) until the owner approves; then: allow it in
  `tools/audit_apk.py` for `Lcom/storageplanner/app/TrashAction;` only, keep delete/write requests forbidden, and
  call `MediaStore.createTrashRequest(cr, uris, true)` in `request()`.

### 3.10 Review cards: bursts together, analysis, swipes (24 Nov)
- Cards: a scrolling card inside a `SwipeFrame` (sideways drags are swipes, vertical drags scroll): page 1 is the
  picture, verdict and, for copies, a strip of the set with Compare side by side / View similar (N); swiping up shows
  the analysis. Right = the tab's action (Delete on Keep), left = Keep, with DELETE / KEEP stamps while dragging.
- `core/Explain`: verdict (final action, suggestion, why, risk, saving, your change), options the rules offered,
  rules that match (`RuleText`), all known tags, file facts; plus "why the ★ best shot is kept" from `Group.whyBest`.
- Gallery: copies of one photo or moment (duplicates, burst shots, WhatsApp copies) under their own sub-heading,
  ★ best first, with Compare and Similar; cards in the same order (`copiesTogether`).
- `SimilarScreen`: the set in two columns with notes, why ★ is better, ticks, Keep best only, Keep all, Compare;
  `SimilarScreen.compare` opens `CompareScreen` with the ticks from the final plan and writes them back.
- CompareScreen's "Debug in the Algorithm Lab" only in developer mode. FinalPlanTest 24 checks.

---

## 4. Decisions and reasons

| Decision | Why |
|---|---|
| Unknown tag never matches, even with NOT | A rule can never act on something the app does not know |
| `mirage_sees` always known ("no" when not looked) | The accidental-shot guard must not switch the rule off on pictures Mirage has not seen |
| Protect action instead of keep for people | Keep also blocks compressing and duplicate removal; protecting only from deletion is what was wanted |
| v2 category stays the plan's (option a) | v3 must first win in the v2 vs v3 comparison on your pictures |
| No Mirage text in the results | Names, booking IDs, number plates must not be stored |
| Mirage model downloaded in CI, not in git | 22 MB binary; pinned revision + sha256 keeps builds reproducible |
| Own `remove()` in the native bridge, audit of imports | The app is read-only; native code must not be able to delete files |
| Score thresholds: people 0.5, objects 0.3, fragments 0.45 | From the phone tests: low scores are not reliable alone; whole-word and multi-signal checks instead |

---

## 5. Testing

### Tests (all in `jvmtest/com/storageplanner/test`, invented data only)
| Test | Checks | In CI |
|---|---|---|
| SceneTest | 47 | yes |
| MirageTest (answer reading, clean-up, verdicts, memes, style words) | 47 | yes |
| VersusTest (cards, score, both algorithms, picking, face check v6) | 21 | yes |
| MirageTagsTest (tags, contains, protect, rules, saving, scan pass) | 28 | yes |
| RulesTest / RuleMatchTest / RulesEquivalenceTest | 85 / 60 / ~88,000 options identical, every built-in exercised | local |
| TagsTest / TextRulesTest / PageTest / SampleTest / LabFilesTest / PlannerTest | 124 / 27 / 73 / 30 / 76 / 2000 | local |

Tests that need outside data (labelled corpus or the test phone): CategoryTest, LabTest, ResultStoreTest,
GroupDecisionTest, DecisionTest, ReviewTest, EyeStateTest, FaceNetTest, PhotoQualityTest. Their known failures
(missing fixture parts) are unchanged by this branch's later work; they were compared before and after each change.

Build and run locally (as used in this work):
```
javac -source 8 -target 8 -d <out> $(find app/src/main/java/com/storageplanner/core app/src/main/java/com/google -name "*.java") \
  app/src/main/java/com/storageplanner/tools/MotionPhoto.java app/src/main/java/com/storageplanner/tools/CopyPaths.java jvmtest/com/storageplanner/test/*.java
java -cp <out> com.storageplanner.test.MirageTagsTest
# Android compile check: javac -source 8 -target 8 -classpath android-all.jar ...
```

### Checked on the phone (through Phone Pilot)
| Picture | Before | After |
|---|---|---|
| WhatsApp group photo at night | v2 and v3: Photos without faces | v3: People (Mirage sees 17 people) |
| WhatsApp photo of one person | Photos without faces (faces not checked) | People (1 face) |
| WhatsApp event M-Ticket (17 Oct) | Documents & text, re-encode | Tickets & passes, delete (date passed) |
| WhatsApp comic | re-encode (as camera photo) | meme, delete (as WhatsApp image) |

---

## 6. Environment notes (for the next session)
- Network hosts the cloud environment needs: `huggingface.co`, `cas-bridge.xethub.hf.co`, `cas-server.xethub.hf.co`,
  `transfer.xethub.hf.co`, `us.aws.cdn.hf.co` (Mirage downloads); `productionresultssaN.blob.core.windows.net` to pull
  a CI artifact into the session (N changes per build).
- APK links: GitHub artifact links need a signed-in browser; `https://nightly.link/pncodes10/storage-planner/actions/artifacts/<id>.zip`
  works without sign-in (public repo).
- CI (`.github/workflows/build-apk.yml`): tests, Mirage fetch + NDK 28.2.13676358 native build, offline APK build,
  audit, artifact upload.
- Phone Pilot (`github.com/pncodes10/Phone-pilot`): the Android picker ignores automated taps on files; an alarm
  notification asks the user to pick the picture.

## 7. Standing rules followed
- Always send the direct APK artifact link (and the nightly.link one).
- Never commit or quote personal data from the user's pictures; tests use invented data; user pictures stay in the scratchpad.
- Never loosen the APK audit's file-deletion guardrail. Nothing in the app deletes originals; plans only suggest.

## 8. Open items and next steps
0. **Owner decision pending**: approve changing the APK audit so "Move to Recycle bin" can use Android's trash request
   (see 3.9). Until then the final plan cannot delete anything.
1. Run **v2 vs v3** (New run) on ~1,000 pictures and mark the cards; if v3 wins, add a setting to use v3's category in the plan.
2. Eyes-closed check is eager on lowered or sideways eyes (seen on the one-person WhatsApp photo).
3. Mirage's scene words can be wrong (a night photo described as sunny); nothing decides on them yet.
4. Mirage time on the phone over a full analysis (20 min budget) and battery use are not measured yet.
5. Invitations and notices that look like memes; forwards received several times (exact duplicates across chats)
   could mark forwards more reliably.
6. CategoryTest's labelled corpus was not available in the cloud session; re-run it where it exists.
