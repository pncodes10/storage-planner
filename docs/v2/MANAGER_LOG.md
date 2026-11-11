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

## Data on disk (ephemeral container — rebuild if lost)

- `/home/user/sp-dataset-drive/files/…`: 268 of 270 Drive images (706 MiB), no exact
  duplicates, 49 name-timestamp burst groups (`bursts_by_name.json`),
  26 WhatsApp `IMG-…-WA…` + 6 "WhatsApp Image … (n)". 2 HEIC files (~8 MB) too
  large for the tool. `inventory.jsonl`, `downloaded.jsonl` alongside.
- `/home/user/sp-dataset/`: generated dataset — build in progress.

## State

- Codebase survey (Explore agent): running.
- Dataset builder agent: running.
- Phase 1 (storage discovery + permission capabilities): not started; waits
  for the survey.

## Next steps

1. When the survey reports: record the architecture decisions (language, package
   name, test harness, where the algorithm switch goes) here.
2. Phase 1: planner agent expands it -> reviewer agent -> implementer agent.
3. When the dataset builder reports: review gaps, commit `tools/v2dataset/`.
4. Continue phases 2–10 in order. Each ML piece must show a measured gain on the
   dataset before it becomes a dependency (spec section 17).
