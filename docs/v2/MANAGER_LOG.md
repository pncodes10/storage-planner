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

## Data on disk (ephemeral container — rebuild if lost)

- `/home/user/sp-dataset-drive/files/…`: 268 of 270 Drive images (706 MiB), no exact
  duplicates, 49 name-timestamp burst groups (`bursts_by_name.json`),
  26 WhatsApp `IMG-…-WA…` + 6 "WhatsApp Image … (n)". 2 HEIC files (~8 MB) too
  large for the tool. `inventory.jsonl`, `downloaded.jsonl` alongside.
- `/home/user/sp-dataset/`: generated dataset — build in progress.

## State

- Codebase survey: done 22:30 UTC.
- Architect agent: writing `docs/v2/ARCHITECTURE.md`, `PHASE_1.md`, `PHASE_2.md` (started 22:32 UTC). Next: reviewer agent, then implementer.
- Dataset builder agent: relaunched 22:25 UTC, running.
- Phase 1: design in progress.

## Next steps

1. When the survey reports: record the architecture decisions (language, package
   name, test harness, where the algorithm switch goes) here.
2. Phase 1: planner agent expands it -> reviewer agent -> implementer agent.
3. When the dataset builder reports: review gaps, commit `tools/v2dataset/`.
4. Continue phases 2–10 in order. Each ML piece must show a measured gain on the
   dataset before it becomes a dependency (spec section 17).
