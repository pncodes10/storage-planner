"""Scenario catalogue (scenarios.json) and documentation strings for the manifest."""

TIER_DOC = {
    "SAFE": "Can be deleted with no information loss (exact duplicate whose original is kept, cache/thumbnail, "
            "partial download, superseded APK, empty temp file).",
    "NO_LOSS": "Can be compressed/transcoded/archived in place without losing meaningful information "
               "(e.g. uncompressed WAV/PNG photo, huge camera video) — never deleted.",
    "YOUR_CALL": "Low-value content the user may want to remove (forwards, memes, ads, old screenshots); "
                 "show as a suggestion, never auto-act.",
    "REVIEW": "Needs a human look before any action (burst siblings, suspicious/corrupted files, encrypted or "
              "unverifiable content, backups with problems).",
    "PROTECTED": "Important/unique; must be actively shielded from cleanup suggestions.",
    "KEEP": "Ordinary content; no recommendation at all.",
    "_scoring": "expected_tier is the single best answer; acceptable_tiers lists answers that are also "
                "correct. For must_never_delete=true files, SAFE/NO_LOSS/YOUR_CALL (or any delete action) is a "
                "critical failure regardless of acceptable_tiers.",
}

REL_DOC = {
    "_direction": "Relationships are stored on the derived/redundant file and point at 'target' (the file that "
                  "makes it redundant or that it was made from). 'transform' describes the derivation.",
    "EXACT_DUPLICATE": "byte-identical to target",
    "VISUALLY_SIMILAR": "near-duplicate of target (burst frame, consecutive video frame) — pick-best candidates",
    "EDITED_FROM": "target edited with a meaningful (annotation/crop/text/filter/collage) or geometric "
                   "(rotate/mirror) change; see transform",
    "COMPRESSED_FROM": "lossy smaller copy of target (WhatsApp recompression, resize, re-encode)",
    "BACKED_UP_BY": "a copy of this file exists inside target backup (only listed when the copy is intact)",
    "CONTAINED_IN": "a byte-identical copy of this file is stored inside archive target (member name in 'member')",
    "COMPANION_OF": "belongs with target (e.g. .srt with video, .aux/.sidecar, split archive part)",
    "VERSION_OF": "another version of the same document/media (format conversion, newer/older revision)",
    "THUMBNAIL_OF": "cache thumbnail of target",
    "hard_negative_group": "files sharing a group id look alike (same template/scene/app) but are DIFFERENT "
                           "content and must not be grouped as duplicates",
}

SCEN = [
    ("S01", "Blurry but important family photograph (only copy)",
     "Blur/low-light detectors must not demote unique people/family photos. Expected PROTECTED (KEEP/REVIEW "
     "acceptable); never SAFE/YOUR_CALL. Distractors: accidental pocket shots (category unknown) are YOUR_CALL."),
    ("S02", "Old but important financial document",
     "Age alone must not make a document disposable. Bank statements, tax forms, pay slips, policies dated "
     "2018-2021 stay PROTECTED."),
    ("S03", "Exact duplicate with original preserved",
     "The copy (relationship EXACT_DUPLICATE) is SAFE; the target original stays KEEP/PROTECTED. Exactly one "
     "member of each group must survive; algorithm should prefer keeping the camera/Documents path."),
    ("S04", "Near duplicate with meaningful edits",
     "EDITED_FROM annotated/cropped/text_added/filtered/collage files are NOT interchangeable with their "
     "original; neither may be offered as a duplicate deletion. Burst siblings (VISUALLY_SIMILAR) are REVIEW."),
    ("S05", "WhatsApp copy of a unique photograph",
     "A WhatsApp-recompressed copy whose camera original is gone is the only copy: PROTECTED. When the original "
     "still exists (COMPRESSED_FROM target present) the WhatsApp copy is SAFE."),
    ("S06", "APK of an installed application",
     "Compare APK package/versionCode/signing cert (META-INF/v2dataset.properties and AndroidManifest stand-in) "
     "with installed_apps.json. Same or older version with same signer: SAFE. Different signer or newer than "
     "installed: REVIEW."),
    ("S07", "Old installer that cannot be redownloaded",
     "Installer whose package is not installed and is marked unavailable in installed_apps.json "
     "store_availability (delisted/discontinued). Must be REVIEW/PROTECTED, never SAFE."),
    ("S08", "Long video with brief meaningful activity",
     "Mostly static long recordings with a short meaningful event (see 'event' field: start/end seconds). Must "
     "not be classified as junk/empty; may be offered for compression (NO_LOSS not allowed — it is "
     "must_never_delete, so REVIEW/KEEP). The control video with no activity is YOUR_CALL."),
    ("S09", "Encrypted PDF",
     "Content cannot be inspected; never treat as duplicate/junk based on metadata. Passwords are in "
     "pdf_passwords.json for harness use only."),
    ("S10", "Backup with missing or corrupted content",
     "Backups listed in backup_manifest*.json; some members are missing or have bad CRC. Originals whose backup "
     "copy is missing/corrupt carry NO BACKED_UP_BY relationship and must not be offered as 'already backed up'."),
    ("S11", "Archive containing important documents",
     "Important zips are PROTECTED; loose files with CONTAINED_IN are redundant only if the member verifies. "
     "Also: junk zip (YOUR_CALL), nested zip, small high-ratio zip (do not fully extract; REVIEW), zip with a "
     "'../' traversal member (never extract; REVIEW)."),
    ("S12", "File that changes between scan and execution",
     "Run the scan, then `python3 mutate.py --root <dataset>` (applies mutations.json), then execute. A correct "
     "implementation re-verifies size/mtime/hash before acting and aborts the action for every mutated file."),
    ("S13", "Runtime: storage permission revoked mid-run",
     "Harness: start a cleanup/quarantine plan over 'harness_files', revoke MANAGE_EXTERNAL_STORAGE / media "
     "permission after the first N operations. Expected: no partial deletes, plan resumable, no crash."),
    ("S14", "Runtime: low storage during compression",
     "Harness: fill the device to < (largest harness file size) free, then compress 'harness_files' (large "
     "NO_LOSS videos/PNGs/WAVs). Expected: compression aborted cleanly, originals untouched, temp outputs removed."),
    ("S15", "Runtime: process killed during quarantine",
     "Harness: quarantine 'harness_files', SIGKILL the app mid-way, restart. Expected: every file is either in "
     "its original place or fully in quarantine with a journal entry; none lost or half-copied."),
]


def scenarios(B):
    out = []
    files = {}
    for rel, e in B.entries.items():
        for s in e["scenarios"]:
            files.setdefault(s, []).append(rel)
    for sid, title, expect in SCEN:
        fl = sorted(files.get(sid, []))
        d = {"id": sid, "title": title, "expected_behaviour": expect, "instances": len(fl), "files": fl}
        if sid in ("S13", "S14", "S15"):
            d["runtime"] = True
            d["harness_files"] = fl
        out.append(d)
    return {"scenarios": out,
            "sidecars": {
                "installed_apps.json": "S06/S07 installed state + store availability",
                "mutations.json": "S12 mutations applied by mutate.py",
                "pdf_passwords.json": "S09 passwords (harness only)",
                "backup_manifest.json": "S10 declared contents of each backup"}}
