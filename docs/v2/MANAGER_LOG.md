# Storage Planner v2 — manager log

Lead's running record: state, decisions with reasons, next steps. The hourly
check-in reads this file first. Spec: `Storage_Planner_Technical_Specification_v2.docx`
(text copy kept in the session scratchpad; phases listed in section 17).

## Operating rules (from the owner)

- The lead decides and delegates; all implementation is done by Opus subagents.
- Per phase: expand the phase in detail -> independent agent reviews the plan ->
  implementer agent builds it -> tests verified -> commit and push -> log updated.
- v2 lives in its own package next to the existing algorithm; the app gets a
  switch between "Classic" and "v2".
- Testing runs in the cloud session against sample images. No APK is installed
  on the phone.
- Branch: `claude/algorithm-project-management-7k8b5r` only.

## Decisions

| # | Decision | Reason |
|---|----------|--------|
| D1 | Work on the session's assigned branch rather than a newly named one. | The session may only push to this branch. |
| D2 | No APK install on the phone tonight; cloud-only testing. | Owner allowed either; cloud runs are repeatable and need nobody at the phone. |
| D3 | Evaluation data = generated labelled dataset (`tools/v2dataset/`, output in `/home/user/sp-dataset/`, not in git) + the owner's Drive photos. | Network policy blocks most image hosts (Wikimedia, Unsplash, Pexels, Flickr, Hugging Face, Kaggle). Reachable: raw.githubusercontent.com, PyPI, storage.googleapis.com, Maven. Generated derivatives give exact ground truth for duplicate/near-duplicate groups. |
| D4 | Drive photos stay outside git, in `/home/user/sp-dataset-drive/`; Drive is read-only. | Personal photos, some owned by other accounts. |
| D5 | Drive videos skipped. | Drive tool caps downloads at 10 MB; smallest video is 18.6 MB. No direct Google API access used. |
| D6 | Hourly scheduled check-in wakes this session. | Owner asked that work continue unattended. |
| D7 | Relaunched the survey and dataset agents at 22:25 UTC. | Both had been silently stopped at 20:25/20:33 by the owner's interrupts (made to reconnect Drive), and could not be resumed. Two hours lost. Lesson: after any interrupt, check that background agents are still alive. |
| D8 | At most two Opus agents at a time; work committed as soon as it exists. | Both running agents hit the account's 5-hour usage limit at ~22:50 UTC and were stopped until 01:20 UTC (2.5 h lost). Resumed 01:27 UTC from their saved state. |
| D9 | One Opus agent at a time from here on. | At ~08:20 UTC the account hit its *weekly* usage limit (reset 09:00 UTC); the session was idle until 11:24 UTC. Total lost to stoppages so far: about 11 of 15 hours. |

## Architecture decisions (after the codebase survey, 22:30 UTC)

Survey facts that drive these: the app is Java 8 with zero third-party
libraries; the real build is `tools/build_offline.sh` (no Gradle); the APK audit
fails on INTERNET permission, java.net, reflection, and `File.delete` outside the
tools classes; the classic core has no android.* imports and is tested with
plain `main()` programs in `jvmtest/`; there is no Android SDK in this container,
so only CI can build an APK. The spec's "Assessor" and "Need" classes do not
exist; the real classic pipeline is Scanner -> Sorter -> UnitBuilder -> Planner/Review.

| # | Decision | Reason |
|---|----------|--------|
| A1 | v2 is Java 8, no libraries, in `app/src/main/java/com/storageplanner/v2/`, with no android.* imports. | Matches the app's build and audit; keeps the decision engine desktop-testable, which the spec requires (section 3). |
| A2 | Classic `core/` stays behaviourally untouched; v2 may reuse its utilities (pHash, sharpness, QR, faces, magic bytes); only visibility widenings allowed there. | Two algorithms must stay comparable; no risk to the shipped one. |
| A3 | Persistence behind a store interface: in-memory + snapshot file now, Android SQLite in Phase 10. | Spec wants SQLite, but the JVM has none without a library. |
| A4 | Tests are `main()` programs in `jvmtest/.../v2test/`; a CLI runs v2 on a folder and writes JSON; `tools/v2eval/evaluate.py` scores it against the dataset manifest. | Same style as the repo; gives one fixed regression number per change (spec 16.3). |
| A5 | Undecodable files (e.g. HEIC on the JVM) = "unknown", never "unimportant". | Spec 5.1/5.3 safety rule; 38 of the owner's photos are HEIC. |
| A6 | App switch "Algorithm: Classic / v2" on the setup screen; v2 results adapted into the existing Unit/Option/Plan types. Done as "Phase A" after Phase 5. | Reuses every existing screen; the glue stays small because it can only be compiled by CI. |
| A7 | ML (spec Phase 7, LiteRT) deferred; plug-in interface only. | Spec section 17: no ML becomes a dependency before a measured gain. LiteRT would also add native libraries the current build cannot package. |
| A8 | Execution engine (delete/quarantine/compress) built and tested on the JVM against a FileOps interface, NOT wired to a delete button. | The shipped app is read-only and the audit forbids deletion; turning that on is the owner's call. |
| A9 | Safety invariants tested in every phase: unknown != zero; classification never authorises deletion; never the last verified copy; similarity alone != interchangeable; aggressive mode never weakens hard rules. | Spec sections 2.2, 6.3, 9.5. |
| A10 | SHA-256 is computed only where a decision needs it (size collisions, and every delete candidate before it is proposed), not for every file. | Spec 5.1 lists a hash per file, but spec 14.1 forbids running expensive work on everything; hashing a 128 GB phone in full is hours of I/O. Reviewer agreed. Deviation from 5.1 recorded here on purpose. |
| A11 | Phase 2 scope cut: MKV, AVI, TAR, extra audio formats, ICO and GIF-frame parsers move to Phase 6; scanning is single-threaded until Phase 10. | Reviewer finding: high cost, no effect on phases 1-5 results. Unparsed formats stay "unknown", which is safe. |
| A12 | Phase A (app switch) waits for a memory measurement after Phase 2: at most about 1 KB per file in the in-memory store, otherwise SQLite is brought forward. | The phone build holds the whole store in memory until Phase 10. |
| A13 | Store = one snapshot file replaced atomically at intervals, not a write-ahead log. | Reviewer found a log bug that silently lost every commit after a half-written line; snapshots are simpler and enough for scan recovery. The Phase 9 operation journal gets its own log. |
| A14 | v2 includes app caches and idle apps (data gathered in Phase 2, planned in Phase 5); exit test: v2's safe bytes are at least classic's. | Spec 10.3 ranks rebuildable data first; the first draft ignored it. |
| A15 | A14 amended: v2 need not match classic's safe bytes where classic's call is unsafe (WhatsApp "sent" copies matched on look alone, Telegram media assumed cloud-backed, older APKs, encrypted chat backups). Parity is checked per item against a documented exemption list. | Architect showed strict parity would force v2 to copy calls the spec forbids (6.2: similarity alone never makes files interchangeable; 8.3: a backup must be verified). Safety outranks matching the old number. |
| A16 | The spec's tier definitions are the authority, not the dataset's labels; the evaluator translates dataset tiers to spec tiers. A delete at SAFE/NO-LOSS on a must-never-delete file fails under any translation. | The dataset marked exact and WhatsApp copies SAFE; the spec reserves NO-LOSS for verified identical copies and caps look-alike copies at YOUR CALL. |
| A17 | A duplicate group's keeper is a label with no recommendation, not a PROTECTED item. | Otherwise about 300 ordinary originals would show as "protected", which reads as over-caution. |
| A18 | `Android/data` and `Android/obb` stay restricted by default; the scoring gate expects them reported as inaccessible, not scanned. | Android 11+ blocks them on real phones. The dataset's one file under `Android/obb` made the Phase 1 gate fail by a single file; the algorithm was right and the gate was wrong. |
| A19 | A verified byte-identical copy stays NO-LOSS even when the names differ; the keeper is the copy with the more descriptive name and more organised folder, and the reason shows both names. Reviewer's proposal to send such pairs to REVIEW was overruled. | Spec 9.4 says so, and the proposal was a workaround for a dataset bug (two "different" invoices generated identical). Demoting every renamed identical file would hide the safest recoveries. The generator bug gets fixed instead. |
| A20 | A temp-looking name over complete, real content (e.g. a full bank-statement PDF named `temp123.tmp`) goes to REVIEW when it is the only copy. | Reviewer blocker: deciding "temp" from the extension alone would have produced a SAFE delete of a must-never-delete file. |
| A21 | The in-memory store fails the A12 limit (measured 2.7 KB heap and 2.1 KB snapshot per file, limit ~1 KB). Before Phase A the records are shrunk (unknown fields not written, features loaded on demand); if still over 1 KB, SQLite moves into Phase A. Cloud phases 3-5 proceed meanwhile. | 100,000 files would need about 270 MB of heap, more than a phone app can count on. Desktop evaluation is unaffected. |
| A22 | On the phone, photos must be decoded at reduced size by the device layer. | Full-size JVM decoding costs about 180 ms per photo (42 s for 268 photos); 30,000 photos would take about 90 minutes. |

## Data on disk (ephemeral container — rebuild if lost)

- `/home/user/sp-dataset-drive/files/…`: 268 of 270 Drive images (706 MiB), no exact
  duplicates, 49 name-timestamp burst groups (`bursts_by_name.json`),
  26 WhatsApp `IMG-…-WA…` + 6 "WhatsApp Image … (n)". 2 HEIC files (~8 MB) too
  large for the tool. `inventory.jsonl`, `downloaded.jsonl` alongside.
- `/home/user/sp-dataset/`: generated dataset — build in progress.

## State (11:30 UTC, 12 Nov)

- Phases 1 and 2 done, committed, 21 suites green.
- Phases 3-5 design reviewed and final.
- Dataset generator fixed (identical invoices now differ); dataset rebuilt and
  verified by the lead (2,409 entries, 0 problems; verify.py now also fails on
  unlinked identical files); marker `.rebuilt_ok` set by the lead.
- Phase 3 implementer resumed at 11:27 UTC (graph package started; work in
  progress committed).

## Next steps

1. Phase 3 report -> lead re-runs tests and the phase3 gate (hard-negative
   check must be zero) -> commit.
2. Phase 4 (risk engine) -> Phase 5 (planner) -> first full scored run.
3. Record shrink (A21) -> Phase A (app switch; CI builds the APK).
4. Phases 6-10 as far as the session allows.
5. If an agent stops with a usage-limit error: wait for the reset time in the
   error, then resume it with a message rather than starting over.
