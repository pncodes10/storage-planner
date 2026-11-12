# Storage Planner v2 — Design review 1

Reviewer: independent review agent. Reviewed: `ARCHITECTURE.md`, `PHASE_1.md`, `PHASE_2.md` (drafts of 12 Nov,
01:27 UTC) against the spec text copy in the session scratchpad (`spec/spec.md`, present), the lead's decisions
A1–A9 / D1–D8 (`MANAGER_LOG.md`), and the code. All BLOCKER and MAJOR findings marked FIXED have been applied in
place in the three documents; each edit is tagged with its finding id (`REVIEW_1 F-…`) so it can be found with grep.

## Code claims checked (all hold unless a finding says otherwise)

| Claim | Result |
|---|---|
| `core.ImageFeatures.phash(int[],int,int)`, `sharpness(...)` (returns -1 below 64 px), `hamming(long,long)` | ok |
| `core.Classifier.classify(String rel, Set<String> installed)` → `{area, pkg}`; `magic(String, long)` | ok — but `classify` returns `residue` for any package absent from the set (F-M3) |
| `core.Scanner.quickHash` / `fullHash` package-private; quick hash = SHA-256(first 64 KiB, last 64 KiB if size > 131 072, `Long.toString(size)`) | ok (F-m10 fixes the doc's contradictory use) |
| Luma `(299R+587G+114B)/1000` in `Scanner.quality` | ok (line 226) |
| `UnitBuilder.NEAR_DUP_BITS = 10, SENT_MATCH_BITS = 8`, `prio` list, `public final units`, `det.duplicateOf`, `UnitBuilder(Scanner.Result, Estimates, int)`; empty `Result` yields no units | ok — `prio` contains `telegram`, which `Classifier` never returns (F-m15); an empty `Result.apps` is exactly why v2 shows no caches (F-M11) |
| `tools.ZipCompressor.SPARE_BYTES = 16 MiB`; `tools.Mp4.read(File)`; `tools.Exif.orientation(byte[])` | ok |
| `Session.replan()`, `MODE_SAFE/MODE_TARGET`, `accepted`; `Review.planWithDecisions(...)`; `MainActivity` restore builds `new UnitBuilder(s.result, s.est, s.sdk)` after `ResultFiles.load` (l. 126–129) | ok |
| `AnalysisRunner.run(app, targetGb, pr)` | wrong: 4 parameters `(Context, double, boolean measure, Scanner.Progress)` (F-m11) |
| FileIds golden vectors | recomputed with Python `hashlib`: all 5 correct; `("a","bc")` ≠ `("ab","c")` |
| core + zxing + `tools/Exif` + `tools/Mp4` compile with `javac --release 8` | ok (checked with JDK 21) |
| Audit (`tools/audit_apk.py`) | v2 sources under `app/src/main/java` are compiled into the APK from Phase 1 on (`build_offline.sh` compiles every source, `-source 8 -bootclasspath android-34.jar`, **not** `--release 8`, so `tools/v2test.sh --release 8` is the only JDK-8 API gate — keep it in CI). Nothing in the design calls a flagged API from app code: `Files.move` is only in jvmtest `DirBlobStore`, Android uses `AtomicFile`. |
| Java 9+ / non-API-26 usage in the design | none found (`toRealPath`, `newDirectoryStream`, `readAttributes(NOFOLLOW_LINKS)`, `fileKey`, `FileChannel.open`, `Normalizer`, lookbehind regex, `Inflater`, `CRC32` all exist on API 26). `Files.getFileStore` works on Android but the Phase A implementation should use `StatFs`. |

## Findings

Severity: BLOCKER = would let data be lost or the implementation fail as specified; MAJOR = safety/correctness gap,
unmet spec requirement or contradiction an implementer cannot resolve alone; MINOR = local inconsistency or cost.

| Id | Sev | Document § | Problem | Required change | Status |
|---|---|---|---|---|---|
| F-B1 | BLOCKER | ARCH §2.6 G1, §8; P1 §3 step 2, §4.3 step 2 | One physical file can appear as two records: a SAF tree inside a walked primary volume (the draft keeps both and proposes a Phase 3 exclusion by "same canonical path", which SAF records do not have), bind-mount/LOOP aliases whose records from earlier scans stay PRESENT, hard links. Both records hash equal → `EXACT_DUPLICATE` VERIFIED → NO_LOSS delete of "the copy" deletes the only file. | `FileRecord.physicalKey` (dev:ino); invariant G2: interchangeable only if provably different physical files, else UNVERIFIED; aliases merged, recoverable bytes 0; LOOP subtree → UNVERIFIABLE; alias dedupe only for equal roots; tests (hard link, stale alias). | FIXED |
| F-B2 | BLOCKER | P1 §4.3 step 3, ARCH §3.1 `IoStatus.of` | `list()` → NOT_FOUND called `directoryGone`, marking every file below REMOVED. On the volume root (card pulled between `refresh` and walk, resume of a checkpoint whose volume is gone, provider error) this removes a whole volume. `IoStatus.of` mapped `java.io.FileNotFoundException` (also thrown for EACCES and revoked SAF grants) to NOT_FOUND. | List NOT_FOUND → subtree UNVERIFIABLE (removal only via the next authoritative parent listing); root NOT_FOUND → volume absent; `FileNotFoundException` → IO_ERROR, NOT_FOUND only on positive evidence; `vanished` requires NOT_FOUND + authoritative grant; tests 18–19. | FIXED |
| F-B3 | BLOCKER | ARCH §4.2, P1 §8 | WAL torn tail: load stops at a torn line, but the next commit is appended after the garbage, so every later commit is silently dropped on every later load. | Replaced the WAL by one atomically replaced snapshot written at adaptive intervals + `flush()` at stage end/pause/cancel (see 7(b)). `commit()` = consistency point. Kill tests moved to a `KillingBlobStore`. | FIXED |
| F-M1 | MAJOR | P1 §4.5 step 2 | On an `ino` volume a failed stat (inode -1) produced token `""` → a new id → existing record `REPLACED` (presence REMOVED): "unreadable" became "removed", and the new record lost history. | Identity guard: unknown inode on `ino` volume → existing record UNVERIFIABLE (`D_STAT_FAILED`), no new id; stat when listing lacks inode. Test 19. | FIXED |
| F-M2 | MAJOR | P1 §1, §1.1 `authoritativeFor`, §10 PermissionChangeTest | Mode A (MediaStore) listings were authoritative for media files. MediaStore omits `.nomedia` folders, unindexed/pending items, and with Android 14 partial access everything not selected → mass REMOVED. | Mode A is never authoritative; missing → UNVERIFIABLE. Test updated. | FIXED |
| F-M3 | MAJOR | P1 §4.5; ARCH §2.3 `area` | `Classifier.classify` returns `residue` for any package not in the set; Phase 1 passes an empty set, Android package visibility gives PARTIAL sets, and `area` was frozen at first sight. Result: installed apps' media marked "left by uninstalled app" → `S_UNINSTALLED_LEFTOVER` (SAFE). | `installed == null` unless availability FULL; `residue` then stored as `app_owner_unknown`; areas with `ownerPackage` re-derived every DISCOVER; `S_UNINSTALLED_LEFTOVER` only with FULL. | FIXED |
| F-M4 | MAJOR | P1 §7 `Pipeline.run`, §9 `SimulatedKill`/`TestControl` | `Pipeline.run` caught Throwable, marked the session FAILED and committed → a simulated kill (or OOM) made the session unresumable, so `DiscoveryResumeTest` could not pass; `killAfterCommits` as a `CommitHook` wrapper missed the Pipeline's own commits. | Never catch `Error` in v2 (ForbiddenApiTest, no exception); `Exception` → FAILED; kills injected at the `BlobStore` (`KillingBlobStore`); stale sessions superseded when not resuming. | FIXED |
| F-M5 | MAJOR | P1 §4.4 | `OUTSIDE_ROOT` was checked only for `path:` identities, but `PosixStorageAccess.dirIdentity` always returns `ino:` on Linux/Android → links escaping the root are walked; the specified test cannot pass. | New `StorageAccess.resolveLink`; checked for every followed link before walking. | FIXED |
| F-M6 | MAJOR | P2 §3.4; ARCH §2.3.1, §2.11, §2.13 V3, §8 | Hashing only size collisions leaves later phases without hashes they rely on: (a) a file backed up only inside a zip is size-unique among files → no CRC32/SHA-256 → backup matching (scenarios 10/11, plan priority "verified backups") impossible; (b) V3 revalidated SHA-256 only for NO_LOSS/COMPRESS, so a SAFE/YOUR_CALL approval survives replacement on `path`-scheme volumes or ext4 inode reuse with equal size+mtime. | Archive-entry sizes join the hashed set; Phase 5 hashes every destructive candidate (`reason=PLAN`); V3 for every destructive action; rule F1: judgements/approvals/feedback valid only while fingerprint matches, recommendations EXPIRE on change. | FIXED |
| F-M7 | MAJOR | ARCH §2.8 vs §8 | Backup VERIFIED by "CRC32 + size + full inflate" (§2.8) vs "inflate and compare SHA-256" (§8). A 32-bit CRC is not interchangeability proof. | SHA-256 only; CRC32_MATCH = POSSIBLE. | FIXED |
| F-M8 | MAJOR | ARCH §7; P2 §3.3 | Heuristic integrity verdicts could feed "broken → disposable". Concretely, JPEG "no FF D9 in last 64 KiB → TRUNCATED" fires on valid Samsung photos with SEF trailers. | New hard rule H4b (integrity never lowers U/L, never below REVIEW); JPEG no-EOI → UNKNOWN unless decode fails; tests. | FIXED |
| F-M9 | MAJOR | P2 header; ARCH §11 | Spec 5.5 silence/speech/music/fingerprint were neither designed nor deferred; face count, DOCX/XLSX metadata and encryption (5.3) not assigned to a phase. | Named deferrals to Phase 6 (with `AudioDecoder` interface and "absence is not evidence"). | FIXED |
| F-M10 | MAJOR | P2 §3.4, design choice 2-2 | Spec 5.1 lists SHA-256 "for every file"; the design hashes only size collisions (+ now archive-entry sizes and plan candidates) and cites "the lead's 'only where needed'", which is not in MANAGER_LOG. A deviation from the spec needs a recorded decision. | Lead: accept "SHA-256 where a later decision needs it; unknown otherwise" as decision A10, or require `--hash all` as default. | LEFT FOR LEAD |
| F-M11 | MAJOR | ARCH §2.11, §3.3, §6, §8, §11, §13 (point 7a) | v2 ignored app caches and idle apps, so it would recover less than classic (whose `UnitBuilder` turns `AppItem.cacheBytes` into R0 and idle apps into archive/uninstall). Spec 10.3 priority 1 is rebuildable data. | Phase 2: `InstalledApp` gains code/data/cache bytes, lastUsed, game/essential. Phase 5: app subjects `cache:<pkg>`/`app:<pkg>`, actions `CLEAR_APP_CACHE/ARCHIVE_APP/UNINSTALL_APP`, codes `S_APP_CACHE/N_APP_ARCHIVE/Y_IDLE_APP`, exit criterion "v2 SAFE+NO_LOSS bytes ≥ classic R0+R1". Phase A maps them to `Unit.forCache/forApp`. | FIXED |
| F-M12 | MAJOR | P2 §2 `ArchiveInfo` | Up to 10 000 entries stored for *every* zip-family file: each APK, docx, jar → hundreds of thousands of objects in the in-memory store and snapshot. | Store entries only for plain archives (`subtype == null`); counts for the rest. | FIXED |
| F-M13 | MAJOR | P2 §1–§9 (scope) | Over-engineering for phases 1–5: `MkvProbe`, `AviProbe`, `TarProbe`, full `AudioProbe` (MP3 Xing/CBR, FLAC, Ogg granules, WAV, AMR), ICO, GIF frame counting, and the multi-threaded `ExtractionScheduler` with reorder buffer and concurrency tests. None of phases 3–5's decisions use them; they cost roughly a third of Phase 2 plus fixtures (`FxMkv`, `FxAudio`, `FxTar`). | Proposed cut: keep type detection for all formats; keep JPEG/PNG/WebP/HEIF/TIFF, MP4 (video + m4a), PDF, ZIP, APK probes; defer MKV/AVI/TAR/other-audio probes, GIF frame count and ICO to Phase 6; run Phase 2 inline (`workers = 0`, bounded by Budget) and move worker threads to Phase 10; the T1 throughput gate then uses `workers = 0`. | LEFT FOR LEAD |
| F-M14 | MAJOR | ARCH §4, §11 Phase A | Phase A ships v2 to phones with the all-in-memory store; SQLite only arrives in Phase 10. Roughly 1–2 KB per file (FileRecord + FeatureRecord) → 100–200 MB for 100 k files, beyond many app heaps. | Lead: measure bytes/file on the dataset at the end of Phase 2 and gate Phase A on it (e.g. ≤ 1 KB/file), or pull the SQLite store forward to Phase A. | LEFT FOR LEAD |
| F-m1 | MINOR | ARCH §2.5 vs P2 §2 | `extractorVersions` vs `versions`. | `versions`. | FIXED |
| F-m2 | MINOR | ARCH §2.4 | Points to PHASE_1 §5.1 for `DirStatus`; it is §4.2. | Fixed ref. | FIXED |
| F-m3 | MINOR | P1 §2, §7 | `Pipeline.run` sets `lastError`, which `ScanSession` lacks. | Field added. | FIXED |
| F-m4 | MINOR | ARCH §3.4 vs P1 §7 | `P_PAUSED_DEVICE` (P_ = protected) vs `PL_PAUSED_DEVICE`. | `PL_PAUSED_DEVICE`. | FIXED |
| F-m5 | MINOR | P1 §10 WalkerTest 8 | `vanishOnStat` never triggers: the walker stats only entries with unknown attributes. | Decorator blanks the entry's listing attributes. | FIXED |
| F-m6 | MINOR | P1 §4.5 `reconcile` | A directory replaced by a file of the same name (or vice versa) left the old subtree/file PRESENT forever. | Reconcile by (name, kind); test 20. | FIXED |
| F-m7 | MINOR | P1 §3 step 2 | "One root inside the other" dedupe would drop APP_PRIVATE external dirs and SAF trees. | Equal canonical roots only. | FIXED |
| F-m8 | MINOR | P1 §7 | With `resume=false`, an old RUNNING session stayed RUNNING forever. | Superseded → CANCELLED. | FIXED |
| F-m9 | MINOR | P2 §9 | Confidence factors had no combination rule. | Product. | FIXED |
| F-m10 | MINOR | P2 §3.4, HashStageTest | "≤ 128 KiB: quickHash = sha256" contradicts "quickHash equals classic for sizes 1/65 536/131 072". | `quickHash` always = classic definition, computed in the same read. | FIXED |
| F-m11 | MINOR | ARCH §13 | `AnalysisRunner.run` has 4 parameters. | Corrected. | FIXED |
| F-m12 | MINOR | P1 §4.5, P2 §3.2 | Size 0 from MediaStore/SAF (pending/virtual documents) became `empty`, integrity OK, confidence 1.0. | Provider-reported 0 → unknown; T1 measures by opening. | FIXED |
| F-m13 | MINOR | ARCH §1 | Package cycles against the doc's own "strictly downward" rule: discovery/features use Stage/Throttle/Budget from `v2.pipeline`, which depends on them; `v2.model.ImageInfo` uses `DecodeStatus` from `v2.device`; `V2Device` returns `v2.content.TextReader`. | New `v2.stage` package; `DecodeStatus` in model; `TextReader` in device. | FIXED |
| F-m14 | MINOR | P2 §5 | `ApkInfo.extra` does not exist. | `problems += apk:SIGNATURE_UNVERIFIED`. | FIXED |
| F-m15 | MINOR | ARCH §8 | Keeper order copies classic `telegram`; the area is `telegram_media`. | Corrected. | FIXED |
| F-m16 | MINOR | P2 §3.2 | MP4 `moov` up to 32 MiB loaded per worker. | Seek-walk, ≤ 1 MiB read. | FIXED |
| F-m17 | MINOR | ARCH §8 | Spec 5.6 "duplicate installer detection" beyond byte-identical APKs not in the P3 outline. | Group by package+version+signer; SHA-256 still required for interchangeability. | FIXED |
| F-m18 | MINOR | ARCH §7 | Spec 2.2 "old ≠ unnecessary, large ≠ disposable" had no hard rule (only a document-specific test). | H10. | FIXED |
| F-m19 | MINOR | ARCH §13 | Spec 14.3 WorkManager is an AndroidX library (not allowed); a `JobService` fails the audit's single-service check; Mode A on API 33+ needs `READ_MEDIA_*`, outside the audited permission set. | Documented: foreground `AnalysisService` now, Mode A only on API ≤ 32, both owner decisions for Phase 10. | FIXED (documented) |
| F-m20 | MINOR | P1 §4.5 | `classify` on a SAF tree volume gets tree-relative paths → wrong areas. | Prefix the mapped shared-storage path, else `user_other`. | FIXED |
| F-m21 | MINOR | ARCH §6 | `ReasonCatalogTest` requires every code used in v2 to be catalogued, but ~20 `D_` codes used by Phase 1 were missing. | Phase 1 adds its D_ codes to the catalogue. | FIXED |
| F-m22 | MINOR | ARCH C2 | Inode stability on Android FUSE/sdcardfs is assumed, not known. | Phase A measures; default FALSE; wrong TRUE is safe under F1. | FIXED |

## Answers to the lead's two questions

**7(a) App caches and installed apps.** They belong to Phase 5 (planner), with the data in Phase 2: the
`InstalledApps` interface gains the storage/usage fields classic `AndroidPlatform.apps()` already collects; Phase 4
assesses app subjects; Phase 5 emits `cache:<pkg>` → `CLEAR_APP_CACHE` at SAFE, plan priority 1, and idle apps as
`ARCHIVE_APP` (API 35+, NO_LOSS) / `UNINSTALL_APP` (YOUR_CALL). Recommendation subjects reuse classic unit ids, so
Phase A maps them onto `Unit.forCache`/`Unit.forApp` directly. Added to the Phase 5 row of ARCHITECTURE §11 with a
non-regression exit criterion against classic. (If Phase 5 slips, the cheapest bridge is for the Phase A adapter to
put the classic `platform.apps()` list into the otherwise-empty `Scanner.Result`, which makes classic's own app units
appear unchanged.)

**7(b) WAL store.** Not the simplest thing. Interrupted-scan recovery needs a consistent committed state plus
stages that resume idempotently from the store, which the design already has; it does not need every batch to be
durable. One atomically replaced snapshot (adaptive interval, ≤ 5% of scan time; flush at stage end, pause, cancel)
gives that with far less code (no sequence numbers, line CRCs, torn-tail handling or compaction) and removes the
torn-tail bug (F-B3). What is lost is at most one interval of work after a kill. The only component that genuinely
needs per-step durability is the Phase 9 operation journal, which gets its own small append-only blob then; Phase 10
SQLite makes every commit durable anyway. Applied.

## Verdicts

| Document | Verdict |
|---|---|
| ARCHITECTURE.md | APPROVE WITH CHANGES — changes applied; F-M10, F-M14 need the lead's decision before Phase 5 / Phase A, not before Phase 1. |
| PHASE_1.md | APPROVE WITH CHANGES — the two removal paths (F-B2, F-M1, F-M2) and the alias issue (F-B1) were the substantive problems; all applied. Ready to implement. |
| PHASE_2.md | APPROVE WITH CHANGES — applied fixes make it correct; implementation should wait for the lead's answer on the scope cut (F-M13) and the hashing policy (F-M10). |
