# Phase 1 — Storage discovery and permission capabilities

Implements spec §4 (4.1–4.3) on the architecture in `ARCHITECTURE.md` (read that first: §0 ground rules,
§2 model, §3 device interfaces, §4 store, §5 pipeline). Nothing in this phase reads file *content*.

## 0. Deliverables

| Path | Classes / content |
|---|---|
| `app/src/main/java/com/storageplanner/v2/V2.java` | `ALGORITHM_VERSION = "2.0.0-p1"`, `SCAN_VERSION = 1`, `STORE_SCHEMA = 1` |
| `v2/model/` | `Unknown`, `Tri`, `Record`, `Fingerprint`, `AccessMode`, `AccessGrant`, `VolumeKind`, `Volume`, `FileRecord`, `Presence`, `ChangeState`, `Protection`, `Recoverability`, `ExtractionStatus`, `DirRecord`, `DirStatus`, `ScanSession`, `ScanKind`, `ScanState`, `ScanCheckpoint`, `ScanEvent`, `InaccessibleLocation`, `InaccessReason`, `CoverageReport` (+ `VolumeCoverage`, `AreaCoverage`). Records of later phases (`FeatureRecord`, `HashRecord`, `Relationship`, ...) are created now as empty-bodied classes with their key field only, so the store schema is complete from day one. |
| `v2/util/` | `Json`, `JsonWriter`, `JsonException`, `Hex`, `Hashing`, `Text` |
| `v2/device/` | `StorageAccess`, `VolumeInfo`, `DirListing`, `DirEntry`, `EntryKind`, `FileStat`, `IoStatus`, `ReadableFile`, `Clock`, `ResourceMonitor`, `ResourceState`, `Thermal`, `DeviceProfile`, `BlobStore`, `ScanControl`, `V2Device` (Phase 2 adds `MediaDecoder`, `InstalledApps`; Phase 9 `FileOps`) |
| `v2/store/` | `V2Store`, `Table`, `FileTable`, `DirTable`, `RelationshipTable`, `RecommendationTable`, `JournalTable`, `StoreException`, `MemoryStore`, `Codecs`, `Snapshot` |
| `v2/stage/` | `Stage`, `StageContext`, `StageOutcome`, `PipelineConfig`, `Throttle`, `Budget`, `CommitHook` (ARCHITECTURE §1: shared stage contract, avoids a discovery ↔ pipeline cycle) |
| `v2/discovery/` | `PathNormalizer`, `FileIds`, `VolumeRegistry`, `CapabilityRegistry`, `WalkLimits`, `Walker`, `DirectoryReconciler`, `FileUpsert`, `DiscoveryEngine`, `CoverageBuilder`, `PosixStorageAccess`, `MediaFamilies` |
| `v2/pipeline/` | `Pipeline`, `PipelineResult` |
| `jvmtest/com/storageplanner/v2test/` | `T`, `AllV2Tests`, `TempTree`, `FixedClock`, `JvmResourceMonitor`, `DirBlobStore`, `KillingBlobStore`, `FaultyStorageAccess`, `MediaOnlyStorageAccess`, `SimulatedKill`, `TestControl`, `JvmDevice`, `V2Cli`, and the test classes of §10 |
| `tools/v2test.sh` | compile + run (ARCHITECTURE §1) |
| `tools/v2eval/evaluate.py` | Phase-1 metrics: discovery coverage and mutation check (§9) |
| `tools/v2eval/apply_mutations.py` | applies `mutations.json` to a *copy* of the dataset (only if the dataset agent does not ship one) |
| `.github/workflows/build-apk.yml` | new step "v2 tests" after "Core tests": `bash tools/v2test.sh` |

No `core/` change. No Android code.

---

## 1. Access modes A–D as capabilities

A mode is never a global switch; it is a set of `AccessGrant`s (ARCHITECTURE §2.2), each covering a path
prefix of one volume. The walker asks `CapabilityRegistry` for the grant that covers a directory.

| Mode | What Android gives | Grant(s) the Android impl reports (Phase A) | canList | canReadMetadata | canReadContent | mediaTypes | JVM simulation |
|---|---|---|---|---|---|---|---|
| A — MediaStore (READ_MEDIA_* / READ_EXTERNAL_STORAGE) | media rows only (images/video/audio), per `RELATIVE_PATH` | one grant per volume, prefix `""` | TRUE (virtual listing grouped by RELATIVE_PATH) | TRUE | TRUE | granted subset of `image, video, audio` | `MediaOnlyStorageAccess` decorator hides non-media files, reports mode A |
| B — MANAGE_EXTERNAL_STORAGE | all of shared storage except `Android/data`, `Android/obb` (API 30+) | prefix `""` per shared volume; volume `restrictedPrefixes = [Android/data, Android/obb]` | TRUE | TRUE | TRUE | null | `PosixStorageAccess` default |
| C — SAF tree | one user-picked tree, document ids opaque | one grant per persisted tree: volume `saf:<hash>`, prefix `""` (own pseudo-volume) | TRUE | TRUE | TRUE | null | `PosixStorageAccess` with a second root and kind `SAF_TREE` |
| D — app-private | `getFilesDir()`, `getExternalFilesDir()` | volume `private`, prefix `""` | TRUE | TRUE | TRUE | null | second root, kind `APP_PRIVATE` |

`canWrite`/`canDelete` are `FALSE` for every grant in Phases 1–8 (A8).

Mode A listings are **never authoritative for absence** (REVIEW_1 F-M2): MediaStore omits files in `.nomedia`
folders, items not yet indexed or pending, and — with Android 14 partial photo access — everything the user did not
select. A file missing from a Mode A listing is `UNVERIFIABLE` (`D_MEDIA_ONLY_VISIBILITY`), never `REMOVED`.
On API 33+ Mode A needs `READ_MEDIA_*`, which the app's audited permission set does not contain (ARCHITECTURE §13).

### 1.1 `CapabilityRegistry` (v2.discovery)

```java
public final class CapabilityRegistry {
    public CapabilityRegistry(java.util.List<Volume> volumes);              // grants come from Volume.grants
    /** Active grant with the longest prefix covering path; ties broken B > C > A > D. null = not covered. */
    public AccessGrant best(String volumeId, String path);
    public boolean canList(String volumeId, String dirPath);                // best(..) != null && best.canList == TRUE
    /** Whether a listing of dirPath proves absence of a child file with this extension (Mode A: only for its media families). */
    public boolean authoritativeFor(String volumeId, String dirPath, String extension);
    /** Diff stored grants against current ones, update Volume.grants (active, lastSeenAt, revokedAt), return events. */
    public static java.util.List<ScanEvent> refresh(java.util.List<Volume> volumes, java.util.List<AccessGrant> current, long now);
}
```
`refresh` keys grants by `grantId()`. Events: `D_GRANT_ADDED` (new id), `D_GRANT_REVOKED` (stored active, absent
now → `active=false, revokedAt=now`), `D_GRANT_CHANGED` (any Tri or `mediaTypes` differ; detail lists the fields),
`D_GRANT_RESTORED` (stored inactive, present again). `MediaFamilies.of(extension)` → `"image" | "video" |
"audio" | null` using fixed lists (image: jpg jpeg png gif webp heic heif avif bmp dng; video: mp4 m4v 3gp 3g2
mkv webm mov avi ts; audio: mp3 m4a aac ogg oga opus flac wav amr awb mid midi). `authoritativeFor` = the best grant
exists, is not `A_MEDIASTORE`, and has `canList == TRUE` (the `extension` parameter is kept for coverage detail only;
`MediaFamilies` is used by `MediaOnlyStorageAccess` and coverage).

---

## 2. Model classes added in this phase

```java
public enum ScanKind { FULL, INCREMENTAL }
public enum ScanState { RUNNING, PAUSED, INTERRUPTED, COMPLETED, CANCELLED, FAILED }
public enum Presence { PRESENT, REMOVED, UNVERIFIABLE, VOLUME_ABSENT }
public enum ChangeState { ADDED, CHANGED, REMOVED, REPLACED }

public final class ScanSession implements Record {      // key = String.format("%012d", scanId)
    public long scanId;
    public ScanKind kind; public ScanState state;
    public long startedAt, endedAt = Unknown.TIME;
    public int resumedCount;
    public String stage;                                // stage id in progress, null when idle
    public java.util.List<String> stagesDone = new java.util.ArrayList<String>();
    public String algorithmVersion; public int scanVersion;
    public ScanCheckpoint checkpoint;                   // DISCOVER progress; null when discovery is not in progress
    public java.util.List<ScanEvent> events = new java.util.ArrayList<ScanEvent>();   // capped at 1000
    public int eventsDropped;
    public java.util.Map<String, Long> counters = new java.util.TreeMap<String, Long>();
    public String pauseReason;                          // reason code, null
    public String lastError;                            // exception class + message (≤ 200 chars) when state FAILED, null
    public java.util.List<AccessGrant> grantsAtStart;   // for D_GRANTS_CHANGED_DURING_SCAN
}
public final class ScanCheckpoint {
    public java.util.List<String> volumeQueue;          // volume ids still to walk, in order; [0] = current
    public java.util.List<String> frontier;             // stack of directory paths of volumeQueue[0]; last element = next to process.
                                                        // The top may be a directory that was partially processed before a kill: it is re-processed from the start.
    public int commits;                                 // commits made in this stage (for test hooks/diagnostics)
}
public final class ScanEvent { public long at; public String code, volumeId, path, detail; }
public enum InaccessReason { OS_RESTRICTED, NOT_GRANTED, PERMISSION_DENIED, READ_ERROR, PARTIAL_READ_ERROR,
    DEPTH_LIMIT, ENTRY_LIMIT, TOTAL_LIMIT, PATH_TOO_LONG, LOOP, SYMLINK_NOT_FOLLOWED, OUTSIDE_ROOT,
    VOLUME_ABSENT, MEDIA_ONLY_VISIBILITY, EXCLUDED_BY_USER, UNDECODABLE_NAMES, NOT_FOUND }
public final class InaccessibleLocation {               // derived by CoverageBuilder; not a table
    public String volumeId, path; public InaccessReason reason; public AccessMode mode;
    public long knownFiles = -1, knownBytes = -1;        // from earlier scans' records under the path, -1 unknown
    public String detail;
}
```

---

## 3. Volume identification — `VolumeRegistry`

```java
public final class VolumeRegistry {
    /** Reconciles sa.volumes() with stored Volume records; writes changes; returns the mounted volumes to walk (sorted by id). */
    public static java.util.List<Volume> refresh(V2Store store, StorageAccess sa, long now, ScanSession session,
                                                 java.util.Set<String> onlyIds /* null = all */) throws StoreException;
}
```
Algorithm:
1. `infos = sa.volumes()`; drop infos whose `volumeId` is null/empty (event `D_VOLUME_INVALID`).
2. Alias de-duplication: two infos whose `root` values are **equal** after `PosixStorageAccess.canonical(root)`
   — keep the one with `kind` order PRIMARY_SHARED < SECONDARY_SHARED <
   REMOVABLE < DIRECTORY < SAF_TREE < APP_PRIVATE, then smaller id; event `D_VOLUME_ALIAS` for the dropped one.
   Nested roots are not aliases and are both kept (e.g. `getExternalFilesDir()` lies under primary's restricted
   `Android/data`; a SAF tree may lie inside primary). A SAF tree inside a walked volume is a second route to the
   same files: its records carry the same `physicalKey` as the primary records where it can be derived
   (ARCHITECTURE §2.3), and Phase 3 never treats such records as copies (invariant G2, REVIEW_1 F-B1). When Mode B
   covers the whole tree, the Android implementation should not report the SAF tree as a volume at all.
3. For each info: load stored `Volume` by id or create one with `firstSeenAt = now`. Copy kind, label, root,
   fsUuid, total/free, caseInsensitive, restrictedPrefixes, `mounted = info.mounted`, `lastSeenAt = now`.
   `identityScheme` is set **only when null**: `"ino"` if `info.fileKeyStable == TRUE`, else `"path"`. If a later
   info reports a different stability, keep the scheme and emit `D_IDENTITY_SCHEME_KEPT`.
4. Stored volumes absent from `infos`, or present with `mounted == false`: `mounted = false`; event
   `D_VOLUME_ABSENT` (only on transition); every `FileRecord` of the volume with presence `PRESENT` or
   `UNVERIFIABLE` → `VOLUME_ABSENT`, `presenceReason = D_VOLUME_ABSENT` (records are not otherwise changed).
5. A volume that was absent and is mounted again: event `D_VOLUME_RETURNED`; its files become `PRESENT` again
   only by being listed in the walk.
6. `store.volumes().put` each changed volume (no write when nothing changed).

---

## 4. Directory walker

### 4.1 `WalkLimits`

| Field | Default | Effect when exceeded |
|---|---|---|
| `int maxDepth` | 64 (root = depth 0) | child directory recorded `DEPTH_LIMIT`, not listed |
| `int maxEntriesPerDir` | 100 000 | entries after the first N (in sorted order) not processed; dir `TRUNCATED_ENTRY_LIMIT`, `entriesSkipped` set |
| `long maxTotalEntries` | 5 000 000 per volume per scan | current dir `TOTAL_LIMIT`, every directory left on the frontier recorded `TOTAL_LIMIT`; walk ends `DONE` with `complete=false` |
| `int maxPathChars` | 4096 (Java chars of the normalized path) | entry skipped, counter `pathTooLong`, event `D_PATH_TOO_LONG` (first 100) ; a directory gets a `DirRecord` with `PATH_TOO_LONG` |
| `boolean followSymlinks` | false | see §4.4 |
| `int commitEvery` | 500 entries | chunk commit inside a large directory |
| `long commitEveryMs` | 3000 | commit when this much time passed since the last commit |
| `java.util.Set<String> excluded` | empty | normalized paths not walked: `EXCLUDED` |

### 4.2 `DirStatus`

`OK` (listed completely), `PARTIAL_IO_ERROR` (listing aborted mid-way), `PERMISSION_DENIED`, `READ_ERROR`,
`NOT_FOUND` (vanished or deleted), `NOT_A_DIRECTORY`, `RESTRICTED` (in `Volume.restrictedPrefixes`, never
listed), `NOT_GRANTED` (no grant can list it), `DEPTH_LIMIT`, `TRUNCATED_ENTRY_LIMIT`, `TOTAL_LIMIT`,
`PATH_TOO_LONG`, `LOOP`, `SYMLINK_SKIPPED` (also used for a symlink to a file: "entry not followed"),
`OUTSIDE_ROOT`, `EXCLUDED`.
"Authoritative" statuses (a listing that proves absence): `OK` and `TRUNCATED_ENTRY_LIMIT` (the names were all
read; only processing was cut). All others are non-authoritative.

### 4.3 `Walker`

```java
public final class Walker {
    public Walker(StorageAccess sa, V2Store store, Volume vol, CapabilityRegistry caps, WalkLimits lim,
                  Clock clock, ScanControl control, Throttle throttle, ScanSession session, CommitHook commit,
                  java.util.Set<String> installedPackages /* null = not known completely (no InstalledApps, or availability != FULL) */);
    /** Walks vol from session.checkpoint.frontier. Returns DONE (frontier empty), PAUSED, CANCELLED. */
    public StageOutcome walk() throws StoreException;
}
public interface CommitHook { void commit() throws StoreException; }   // DiscoveryEngine: put(session); store.commit(); test hooks
```
State held in memory (rebuildable from the store): `frontier` (= `session.checkpoint.frontier`), `visited`
(`Set<String>` of dir identities), `entriesThisWalk`, `sinceCommit`, `lastCommitNanos`.

On construction with a non-empty frontier from a resumed session: `visited` = identities of every
`DirRecord` of this volume with `lastVisitedScanId == session.scanId`, **except** the directory on top of the
frontier (it will be re-processed). `entriesThisWalk` = Σ `entries` of those DirRecords.

Main loop:

```
while frontier not empty:
    if control.cancelRequested(): commitNow(); return CANCELLED
    if control.pauseRequested() or throttle.shouldPause(): session.pauseReason = ...; commitNow(); return PAUSED
    dir = frontier.peekLast()                       // stays on the stack until fully processed
    processDirectory(dir)
    frontier.removeLast(); push this dir's child directories in DESCENDING name order (so ascending is processed first)
    commitIfDue()                                   // after every directory at least once per commitEvery entries/commitEveryMs
return DONE
```

`processDirectory(dir)`, step by step:

1. `raw = rawPathOf(dir)` (from the `DirRecord`, else = `dir`). `depth = PathNormalizer.depth(dir)`.
2. Loop check: `id = sa.dirIdentity(vol, raw)`; if `id != null && visited.contains(id)` → `DirRecord(dir).status = LOOP`,
   `lastVisitedScanId = scan`, event `D_LOOP` (path + identity), `subtreeUnverifiable(dir, "D_LOOP")` — records stored
   under the alias path from earlier scans would otherwise stay PRESENT unverified and look like copies of the
   first-visited files (REVIEW_1 F-B1); return. Else `visited.add(id)`.
3. `listing = sa.list(vol, raw)`.
   * `NOT_FOUND` → DirRecord status `NOT_FOUND`; `subtreeUnverifiable(dir, "D_VANISHED_DURING_SCAN")`; return. A listing
     failure never removes anything (REVIEW_1 F-B2): removal of a vanished directory is decided by the next
     authoritative listing of its parent (§4.5 `reconcile`), which needs no special case. If `dir == ""` (the volume
     root): additionally treat the volume as absent for this scan (`VolumeRegistry` step 4 semantics, event
     `D_VOLUME_ABSENT`) — this covers a card removed between `refresh` and the walk, and a resumed checkpoint whose
     volume is gone.
   * `PERMISSION_DENIED`, `IO_ERROR`, `NOT_A_DIRECTORY`, `UNSUPPORTED` → DirRecord status `PERMISSION_DENIED` /
     `READ_ERROR` / `NOT_A_DIRECTORY` / `READ_ERROR`, `error = listing.error`; if the stored DirRecord had
     `lastListedOkScanId` known, call `permissionProbe(dir)` (§4.6); `DirectoryReconciler.subtreeUnverifiable(dir,
     reason)` with reason `D_PERMISSION_REVOKED` if the probe found a revoked grant else `D_PARENT_UNREADABLE`; return.
4. Normalize names: for each entry `n = PathNormalizer.normalizeName(entry.name)`; on `IllegalArgumentException`
   (empty, `.`, `..`, contains `/` or NUL) → counter `invalidNames`, skip. If `n` contains U+FFFD → keep the entry
   but mark it `suspectUndecodable`. Build a `TreeMap<String, DirEntry>` by `n`; on a collision (two raw names with
   the same NFC) the entry whose raw name is *not* already NFC keeps its raw name as its key, event `D_NAME_COLLISION`.
5. If `map.size() > maxEntriesPerDir`: process only the first `maxEntriesPerDir` keys; `status = TRUNCATED_ENTRY_LIMIT`,
   `entriesSkipped = size - max`.
6. For each processed key `n` (ascending), `child = join(dir, n)`, `childRaw = join(raw, entry.name)`:
   1. `if (++entriesThisWalk > maxTotalEntries)` → status `TOTAL_LIMIT` for `dir`; every path left on the frontier gets a
      DirRecord `TOTAL_LIMIT` (and `subtreeUnverifiable(path, "D_TOTAL_LIMIT")`); clear the frontier; break.
   2. `if excluded.contains(child)` → DirRecord(child) `EXCLUDED` (directories) or counter `excludedFiles`; `subtreeUnverifiable`
      for directories; continue.
   3. `if child.length() > maxPathChars` → as in §4.1; continue.
   4. Resolve kind: if `entry.kind == UNKNOWN` or (`FILE` and (`entry.size < 0` or, on an `ino` volume, `entry.inode < 0`))
      → `st = sa.stat(vol, childRaw)`; use `st`.
   5. `DIRECTORY`: if `depth + 1 > maxDepth` → DirRecord `DEPTH_LIMIT` + `subtreeUnverifiable(child, "D_DEPTH_LIMIT")`;
      else if `isRestricted(child)` → DirRecord `RESTRICTED` + `subtreeUnverifiable(child, "D_OS_RESTRICTED")`;
      else if `!caps.canList(vol, child)` → DirRecord `NOT_GRANTED` + `subtreeUnverifiable(child, "D_NOT_GRANTED")`;
      else add `child` to this directory's `childDirs` (and remember `childRaw` in its DirRecord `rawPath` if it differs).
   6. `SYMLINK`: see §4.4.
   7. `FILE`: `FileUpsert.apply(...)` (§4.5). If the stat in step 4 returned `NOT_FOUND`: if `suspectUndecodable` →
      counter `undecodableNames`, event `D_NAME_UNDECODABLE` (dir + escaped name); else counter `vanishedDuringScan`
      and `FileUpsert.vanished(vol, child)`.
   8. `OTHER` (FIFO, socket, device node): counter `specialFilesSkipped`.
   9. `if (++sinceCommit >= commitEvery || elapsed >= commitEveryMs)` → `commitNow()` (chunk commit; the directory stays on top of
      the frontier, so a kill here re-processes it — `FileUpsert` is idempotent).
7. If listing status was `PARTIAL_IO_ERROR`: DirRecord status `PARTIAL_IO_ERROR`; reconcile non-authoritatively.
   Else status `OK` (unless already `TRUNCATED_ENTRY_LIMIT`/`TOTAL_LIMIT`).
8. `DirectoryReconciler.reconcile(dir, namesInListing /* all keys of the map, incl. unprocessed */, authoritative)`.
9. Write `DirRecord(dir)`: `status`, `lastVisitedScanId = scan`, `lastListedOkScanId = scan` if `OK`, `entries = map.size()`,
   `entriesSkipped`, `identity = id`, `mtime`/`inode` from the parent's entry (root: `sa.stat(vol, "")`).

`isRestricted(path)` = some prefix `p` in `vol.restrictedPrefixes` with `PathNormalizer.isUnder(path, p)`.

### 4.4 Symlinks and loops

* `followSymlinks == false` (default, and the only setting Phase A uses): every symlink entry gets a DirRecord
  `SYMLINK_SKIPPED` (with `error = linkTarget` if known), counter `symlinksSkipped`. Nothing behind it is walked, so
  a file reachable through both a link and its real path is recorded once.
* `followSymlinks == true`: `target = sa.resolveLink(vol, childRaw)`; `null` → `OUTSIDE_ROOT` (checked for every link,
  whatever form `dirIdentity` returns — `PosixStorageAccess` always returns `ino:` identities, so the draft's
  "`path:` identity outside the root" test never fired; REVIEW_1 F-M5). Then `id = sa.dirIdentity(vol, childRaw)`. If the
  target is not a directory → `SYMLINK_SKIPPED`. If `visited.contains(id)` → `LOOP`. Else the link is walked like a
  directory (its children get paths under the link name).
* Independently of symlinks, step 2 of §4.3 catches bind-mount aliases and FUSE oddities (same identity under two
  paths).

### 4.5 `FileUpsert` and `DirectoryReconciler` (change and removal detection)

```java
final class FileUpsert {
    /** Creates or updates the FileRecord for a listed file. Writes nothing when nothing changed. */
    static void apply(V2Store store, Volume vol, CapabilityRegistry caps, String path, String rawPathOrNull,
                      DirEntry e, FileStat stOrNull, long scanId, long now, java.util.Set<String> installed) throws StoreException;
    /** A listed file that vanished before stat: confirmed absent. */
    static void vanished(V2Store store, String volumeId, String path, long scanId) throws StoreException;
}
final class DirectoryReconciler {
    static void reconcile(V2Store s, Volume v, CapabilityRegistry caps, String dir, java.util.Set<String> names, boolean authoritative, long scanId);
    static void directoryGone(V2Store s, String volumeId, String dir, long scanId);
    static void subtreeUnverifiable(V2Store s, String volumeId, String dir, String reasonCode, long scanId);
}
```

`FileUpsert.apply`:
1. Attributes: `size`, `mtime`, `inode`, `physicalKey` from `st` if given, else from `e`; `readable`/`writable` from `st` (UNKNOWN if
   no stat was needed). If `st.status ∈ {PERMISSION_DENIED, IO_ERROR}`: size/mtime from `e` if known else unknown,
   `readable = FALSE`, `lastError = st.status + ": " + st.error`.
2. Identity guard (REVIEW_1 F-M1): on an `ino` volume with `inode < 0` (stat failed) the id cannot be computed. Do **not**
   fall back to the empty token (that would make a new id and mark the existing record `REPLACED`/REMOVED merely because a
   stat failed). Instead: if `files.byPath(vol, path)` gives a record with presence `PRESENT` → `UNVERIFIABLE`,
   `presenceReason = "D_STAT_FAILED"`, `lastError`; counter `statFailed`; return. Otherwise
   `token = ("ino".equals(vol.identityScheme)) ? "ino:" + inode : ""`; `id = FileIds.of(vol.volumeId, path, token)`.
3. `old = files.byPath(vol, path)` (the record at this path with presence ≠ REMOVED, if any).
   If `old != null && !old.fileId.equals(id)` → `old.presence = REMOVED; old.change = REPLACED; old.changeScanId = scanId;
   old.removedScanId = scanId; old.presenceReason = "D_REPLACED"`; put.
4. `cur = files.get(id)`.
   * `cur == null` → new `FileRecord`: ids, `path`, `rawPath`, `name = PathNormalizer.name(path)`, `extension =
     Text.extension(name)`, size, `modifiedAt = mtime`, `lastAccessedAt` (only if `vol.atimeReliable == TRUE`), inode,
     `presence = PRESENT`, `change = ADDED`, `firstSeenScanId = changeScanId = scanId`, readable/writable,
     `accessMode = caps.best(vol, parent).mode`, `{area, ownerPackage} = core.Classifier.classify(path, installed == null ?
     emptySet : installed)` — `path` here is relative to shared storage: for a `SAF_TREE` volume whose tree maps to a
     shared-storage path (externalstorage provider document id `primary:DCIM`) that prefix is prepended, otherwise area is
     `user_other` (F-m20) — with a `residue` result stored as `app_owner_unknown` when `installed == null` (REVIEW_1 F-M3;
     `DiscoveryEngine` re-derives areas of records with `ownerPackage != null` at stage end),
     `size == 0` reported by a Mode A/C provider (`uri != null`) is stored as `-1` (unknown) — providers report 0 for
     pending/virtual documents; Phase 2 measures it by opening the file,
     `hidden = any component starts with '.'`, `scanVersion = V2.SCAN_VERSION`, Phase-2 fields at their unknown values; put.
   * `cur.presence != PRESENT` (it was REMOVED / UNVERIFIABLE / VOLUME_ABSENT) → `presence = PRESENT`, `presenceReason =
     null`, `removedScanId = -1`; if it was `REMOVED` → `change = ADDED, changeScanId = scanId`; then continue with the
     fingerprint comparison below; put.
   * fingerprint `(size, mtime, inode)` differs from `cur` → update those fields, `change = CHANGED, changeScanId = scanId`,
     `scanVersion = V2.SCAN_VERSION`; put. (Phase 2 sees the new fingerprint and re-extracts.)
   * otherwise: no write (also when only `readable`/`writable` are unknown in this listing).
   Counters are *not* incremented here; `DiscoveryEngine` derives them at the end (so they are exact after a resume).

`FileUpsert.vanished`: only when `st.status == NOT_FOUND` (positive evidence, ARCHITECTURE §3.1 `IoStatus.of`) **and**
`caps.authoritativeFor(vol, parent, ext)`; then if `byPath` gives a record with presence ≠ REMOVED → `REMOVED`, `change = REMOVED`,
`presenceReason = "D_VANISHED"`, `changeScanId = removedScanId = scanId`. Otherwise → `UNVERIFIABLE` (`D_STAT_FAILED`).

`DirectoryReconciler.reconcile(dir, names, authoritative)` — `names` maps each name to its resolved `EntryKind`:
* For each stored file `f` in `files.childrenOf(vol, dir)` with presence ∈ {PRESENT, UNVERIFIABLE} whose name key
  (`PathNormalizer.name(f.path)`) is not in `names`, **or is in `names` as a DIRECTORY/SYMLINK** (a file replaced by a
  directory of the same name is gone as a file):
  * `authoritative && caps.authoritativeFor(vol, dir, f.extension)` → REMOVED (`presenceReason = "D_NOT_IN_LISTING"`,
    `change = REMOVED`, scan ids).
  * else → `UNVERIFIABLE`, `presenceReason = authoritative ? "D_MEDIA_ONLY_VISIBILITY" : "D_PARTIAL_LISTING"` (write only if it changes).
* For each stored `DirRecord d` in `directories.childrenOf(vol, dir)` whose name is not in `names` or is in `names` as a
  FILE/OTHER (a directory replaced by a file): if authoritative → `directoryGone(d.path)`; else
  `subtreeUnverifiable(d.path, "D_PARTIAL_LISTING")`. (A name now listed as SYMLINK keeps the DirRecord `SYMLINK_SKIPPED`
  written by §4.4, and its stored subtree becomes `UNVERIFIABLE` with `D_SYMLINK`.)

`directoryGone(dir)`: every file in `files.underPrefix(vol, dir)` with presence ≠ REMOVED → REMOVED (`D_DIR_REMOVED`);
every DirRecord in `directories.underPrefix(vol, dir)` and `dir` itself → `status = NOT_FOUND, lastVisitedScanId = scanId`.

`subtreeUnverifiable(dir, reason)`: every file in `files.underPrefix(vol, dir)` with presence `PRESENT` → `UNVERIFIABLE`,
`presenceReason = reason` (records already UNVERIFIABLE keep their first reason; no other field changes).

Resulting guarantee (tested): a file becomes `REMOVED` only if an authoritative (non-Mode-A) listing of its parent — or
of an ancestor that no longer contains the path — was obtained in this scan, or a stat returned positive NOT_FOUND evidence
for a file that the same authoritative listing had just shown. Permission loss, I/O errors, stat failures, a directory
listing that fails with NOT_FOUND, limits, restrictions, exclusions, loops, Mode A visibility and unmounted volumes never
produce `REMOVED` (nor `REPLACED`).

### 4.6 Permission-change detection

* **At scan start** (`DiscoveryEngine` step 2): `CapabilityRegistry.refresh(volumes, sa.grants(), now)`; events go into
  `session.events`; `session.grantsAtStart = sa.grants()`. A revoked prefix is then simply not listable, and the walk
  marks its subtree `UNVERIFIABLE` with `D_NOT_GRANTED`; `DiscoveryEngine` rewrites the reason to `D_PERMISSION_REVOKED`
  for subtrees under a grant revoked in this scan.
* **During the walk** (`permissionProbe(dir)`, called when a directory that was listed OK in an earlier scan now fails
  with PERMISSION_DENIED): re-read `sa.grants()`, run `refresh`; if anything changed → event `D_GRANTS_CHANGED_DURING_SCAN`,
  rebuild `caps`; return whether a grant covering `dir` was revoked. Calls are rate-limited to one per 2 s.
* **At walk end**: compare `sa.grants()` with `session.grantsAtStart` by `grantId` + flags; any difference →
  `session.counters["grantsChangedDuringScan"] = 1` and the coverage report flag.

### 4.7 File-change detection summary

| Observation in this scan | Result |
|---|---|
| new path | `ADDED` |
| same id, different size or mtime | `CHANGED` (same id) |
| same path, different inode on an `ino` volume | old id `REPLACED` (presence REMOVED), new id `ADDED` |
| path missing from an authoritative parent listing | `REMOVED` |
| path missing from a non-authoritative listing (incl. any Mode A listing) / parent unreadable / parent listing NOT_FOUND / stat failed on an `ino` volume / limits / loop / revoked | `UNVERIFIABLE` (no change state) |
| file of an unmounted volume | `VOLUME_ABSENT` |
| identical size + mtime + inode | nothing written ("unchanged" = `changeScanId < scanId`) |
| content changed with size and mtime forged back | not detected in Phase 1/2 (documented limit); execution revalidates SHA-256 (ARCHITECTURE V3) |

---

## 5. Paths and file ids

### 5.1 `PathNormalizer` (v2.discovery, pure functions)

```java
public final class PathNormalizer {
    public static String normalizeName(String raw);          // NFC (java.text.Normalizer.Form.NFC); throws IllegalArgumentException
                                                             // for "", ".", "..", or a name containing '/' or '\u0000'
    public static String normalizeRelative(String raw);      // split on '/', drop empty parts, normalizeName each, join; "" stays ""
    public static String join(String dir, String name);      // dir "" → name; else dir + "/" + name
    public static String parent(String path);                // "a/b" → "a"; "a" → ""; "" → null
    public static String name(String path);                  // last component; "" → ""
    public static int depth(String path);                    // "" → 0, "a" → 1, "a/b" → 2
    public static boolean isUnder(String path, String prefix); // prefix "" → true; equals(prefix) or startsWith(prefix + "/")
}
```
Backslashes, spaces, leading/trailing spaces, emoji and RTL characters are legal and preserved. Case is preserved
(case-insensitive volumes list each file once under its stored case, so no folding is needed).

### 5.2 `FileIds`

```java
public final class FileIds {
    public static String of(String volumeId, String normalizedPath, String identityToken);   // ARCHITECTURE §2.3.1
}
```
Golden vectors (the test hard-codes these; computed with Python `hashlib`):

| volumeId | path | token | fileId |
|---|---|---|---|
| `primary` | `DCIM/Camera/IMG_1.jpg` | `` | `e555bafe3de22350be0aed7b0ddeed74` |
| `primary` | `DCIM/Camera/IMG_1.jpg` | `ino:12345` | `3c9b8dfb7dae38927f8db7a5f42f92ec` |
| `uuid:1A2B-3C4D` | `Download/é.pdf` (U+00E9) | `` | `4ceb62ed957ae351e46058e691e3503a` |
| `primary` | `` | `` | `b5ab5da22a47e601a5ad59746c3408bf` |
| `primary` | `a/bc` | `` | `9b1fb29a38bf631f6ba81eb1e4e588cd` |

`Hashing.sha256Hex(byte[])` and `Hex` are the only digest helpers; `FileIds` uses `MessageDigest.getInstance("SHA-256")`
(one instance per thread via `ThreadLocal`).

---

## 6. Coverage report — `CoverageBuilder`

```java
public final class CoverageBuilder {
    public static CoverageReport build(V2Store store, long scanId, int maxListed /* 1000 */);
    public static void write(JsonWriter w, CoverageReport r);
}
public final class CoverageReport {
    public long scanId; public ScanKind kind; public ScanState state; public boolean complete; public int resumedCount;
    public boolean grantsChangedDuringScan; public long startedAt, endedAt;
    public java.util.List<VolumeCoverage> volumes;
    public java.util.List<InaccessibleLocation> inaccessible; public int inaccessibleTotal;   // listed ≤ maxListed, sorted (volumeId, path)
    public java.util.List<ScanEvent> events; public int eventsDropped;
    public java.util.Map<String, Long> anomalies;   // undecodableNames, nameCollisions, invalidNames, vanishedDuringScan,
                                                    // specialFilesSkipped, pathTooLong, symlinksSkipped, excludedFiles, readErrors, statFailed
    public java.util.List<AreaCoverage> areas;      // per (volumeId, area): files, bytes — areas from core.Classifier
    public Object extraction;                       // Phase 2 fills (PHASE_2.md §9); null in Phase 1
}
public final class VolumeCoverage {
    public String volumeId, label; public VolumeKind kind; public boolean mounted; public java.util.List<AccessMode> modes;
    public long totalBytes = -1, freeBytes = -1, usedBytes = -1;        // used = total - free when both known
    public long discoveredFiles, discoveredBytes;                       // presence PRESENT; bytes of known sizes
    public long unknownSizeFiles;                                       // PRESENT with sizeBytes == -1
    public long unaccountedBytes = -1;                                  // used - discoveredBytes when used known: system, app-private, other users,
                                                                        // restricted folders. Never treated as recoverable.
    public double coverageRatio = Double.NaN;                           // discoveredBytes / usedBytes
    public long filesUnverifiable, filesVolumeAbsent;
    public long added, changed, removed, replaced;                      // change records with changeScanId == scanId
    public long dirsVisited, dirsListedOk, dirsPartial, dirsDenied, dirsReadError, dirsRestricted, dirsNotGranted,
                dirsDepthLimited, dirsTruncated, dirsTotalLimited, dirsLoop, dirsSymlinkSkipped, dirsOutsideRoot, dirsExcluded;
}
```
Derivation rules:
* `inaccessible`: one entry per DirRecord with `lastVisitedScanId == scanId` and status not in {OK, NOT_FOUND}
  (status → reason: PERMISSION_DENIED→PERMISSION_DENIED, READ_ERROR/NOT_A_DIRECTORY→READ_ERROR, PARTIAL_IO_ERROR→PARTIAL_READ_ERROR,
  RESTRICTED→OS_RESTRICTED, NOT_GRANTED→NOT_GRANTED (or PERMISSION_DENIED with detail "revoked" when the subtree reason is
  `D_PERMISSION_REVOKED`), DEPTH_LIMIT, TRUNCATED_ENTRY_LIMIT→ENTRY_LIMIT, TOTAL_LIMIT, PATH_TOO_LONG, LOOP,
  SYMLINK_SKIPPED→SYMLINK_NOT_FOLLOWED, OUTSIDE_ROOT, EXCLUDED→EXCLUDED_BY_USER); plus one entry per unmounted volume
  (`VOLUME_ABSENT`, path `""`), one per media-restricted grant (`MEDIA_ONLY_VISIBILITY`, path = prefix, detail = media types),
  and one per restricted prefix of each mounted volume even if the walker never reached it (`OS_RESTRICTED`), and one per
  directory with undecodable names (`UNDECODABLE_NAMES`). `knownFiles/knownBytes` = count/Σ size of records under the path
  (any presence except REMOVED), else -1.
* Volume totals for `VolumeKind.DIRECTORY` are unknown (`-1`): a JVM directory is not a whole filesystem.
* `complete` = state COMPLETED and no DirRecord of this scan has TOTAL_LIMIT.

Spec 18 "accurate storage coverage reporting" is met when (tested): every non-OK directory of the scan appears in
`inaccessible` (or is counted in `inaccessibleTotal` beyond the cap), and Σ `areas.bytes` == Σ `discoveredBytes`.

---

## 7. `DiscoveryEngine` (Stage `DISCOVER`) and `Pipeline` (Phase-1 subset)

```java
public final class DiscoveryEngine implements Stage {
    public DiscoveryEngine();                         // installed packages come from ctx.device.apps() when present (Phase 2), else empty set
    public String id() { return "DISCOVER"; }
    public StageOutcome run(StageContext ctx) throws StoreException;
}
```
1. `volumes = VolumeRegistry.refresh(...)`.
2. `events = CapabilityRegistry.refresh(volumes, sa.grants(), now)`; `caps = new CapabilityRegistry(volumes)`; store
   volumes; `session.grantsAtStart` if null.
3. If `session.checkpoint == null`: `checkpoint = {volumeQueue: ids of mounted volumes (sorted), frontier: [""]}`;
   if the volume root itself is not listable (`!caps.canList(vol, "")`) → DirRecord(root) `NOT_GRANTED`,
   `subtreeUnverifiable("", ...)`, skip the volume.
4. While `volumeQueue` not empty: build a `Walker` for `volumeQueue[0]`; `walk()`. On `DONE`: if complete set
   `volume.lastFullScanId = scanId`; remove the volume from the queue; `frontier = [""]`; commit. On `PAUSED`/`CANCELLED`
   → return that outcome (the session keeps the checkpoint).
5. End: re-read grants (§4.6); rewrite subtree reasons for revoked grants; re-derive `area` of every PRESENT record with
   `ownerPackage != null` from the current installed set (ARCHITECTURE §2.3; write only on change); derive counters from records
   (`added/changed/removed/replaced` = records with `changeScanId == scanId` by `change`; dir counts by status);
   `session.checkpoint = null`; `stagesDone += "DISCOVER"`; `store.flush()`; return `DONE`.

`Pipeline.run(control)` (Phase-1 behaviour):
* `resume == true`: latest session (highest scanId) with state RUNNING/PAUSED/INTERRUPTED; if RUNNING (process died) set
  INTERRUPTED + event `D_INTERRUPTED`; then `state = RUNNING`, `resumedCount++`, event `D_RESUMED`; commit.
* otherwise: every older session still `RUNNING`/`PAUSED`/`INTERRUPTED` is set `INTERRUPTED` → `CANCELLED` (event
  `D_SUPERSEDED`), then a new `ScanSession` (scanId = 1 + max existing, state RUNNING, kind, `startedAt = clock.nowMillis()`, versions); commit.
* For each configured stage not in `stagesDone`: `session.stage = id`; commit; `outcome = stage.run(ctx)`;
  `PAUSED/CANCELLED/BUDGET_EXHAUSTED` → set state PAUSED/CANCELLED/PAUSED, `flush()`, return. `FAILED` or an `Exception` → state
  FAILED, `lastError`, `flush()`, rethrow (wrapped in `StoreException` if it is not one). **`Error`s are never caught** (REVIEW_1
  F-M4): an `OutOfMemoryError` or a test's `SimulatedKill` must behave like process death — the session stays `RUNNING` in
  the last snapshot and the next start marks it `INTERRUPTED` and resumes it. (Marking it FAILED would make it unresumable.)
* All done: `state = COMPLETED, endedAt = now, stage = null`; `flush()`; return `PipelineResult {session, coverage}`.
* `ScanKind.FULL` vs `INCREMENTAL` makes no difference to the walk (C7 in ARCHITECTURE); it is recorded, and Phase 2
  uses it to ignore cached extraction. Starting from an empty store is the same as a full first scan.

`Throttle` (ARCHITECTURE §3.4) samples `ResourceMonitor` at most every 2 s; `shouldPause()` returns true for the pause
conditions and sets `pauseReason = "PL_PAUSED_DEVICE"`.

---

## 8. Store in Phase 1 (`MemoryStore`)

```java
public final class MemoryStore implements V2Store {
    public static MemoryStore open(BlobStore blobs, long snapshotEveryMs /* -1 = adaptive (§4.2), 0 = every commit */, Clock clock)
        throws StoreException;                                               // loads store.snap (ARCHITECTURE §4.2)
    public static MemoryStore volatileStore();                               // no persistence (unit tests); commit() only clears pending ops
    public long changedPutsSinceOpen();                                      // puts whose record differed from the stored one (tests)
}
```
* Each table: `TreeMap<String, R>` (key order) + pending op list since last commit.
* `FileTable` indexes, all maintained on put/remove: `byPathKey: HashMap<volumeId+"\u0000"+path, TreeSet<fileId>>`
  (`byPath()` returns the member with presence ≠ REMOVED, else the one with the greatest `changeScanId`, ties by fileId);
  `bySize: HashMap<Long, TreeSet<fileId>>`; `bySha: HashMap<String, TreeSet<fileId>>`; `ordered: TreeMap<volumeId+"\u0000"+path+"\u0000"+fileId, fileId>`;
  `underPrefix(v, d)` = `ordered.subMap(v+"\0"+d+"/", v+"\0"+d+"0")` (`'0'` is the successor of `'/'`), for `d == ""` the whole
  volume range; `childrenOf` = `underPrefix` filtered to `parent(path).equals(d)`, but implemented with a
  `HashMap<volumeId+"\0"+parent, TreeSet<fileId>>` index for O(children).
* `DirTable` has the same `ordered`/children indexes.
* `put` stores `record.copy()`; `get`/`all` return copies (record classes implement a field-by-field `copy()`; lists are
  copied, `Fingerprint` is immutable).
* `JournalTable.put` of an existing key → `IllegalStateException`.
* `commit()`: clear the pending-inverse list; `commitSeq++`; if `dirty` and `clock.monotonicNanos()` shows ≥ `snapshotEveryMs`
  since the last snapshot → write one (`Snapshot.write` streams tables in order through `blobs.replace("store.snap")`, then
  `commit()` of the `AtomicWrite`). `flush()`: commit + snapshot if dirty.
* `rollback()`: re-apply the inverse of pending ops (kept as `(table, key, previousValueOrNull)`), then clear.

---

## 9. JVM side (jvmtest/com/storageplanner/v2test)

| Class | Purpose |
|---|---|
| `T` | `static void section(String)`, `check(String name, boolean ok)`, `eq(String name, Object expected, Object actual)`, `int failures()`, `int passes()`, `static int finish(String suite)` prints `"<suite>: P pass, F fail"` and returns F |
| `AllV2Tests` | `main`: calls each test class's `static int run(String[] args)` in a fixed order (no reflection), prints a summary, `System.exit(totalFailures == 0 ? 0 : 1)` |
| `TempTree` | `static TempTree create()` under `java.io.tmpdir` + `/v2t-<nanos>`; `Path root()`; `file(String rel, byte[] data)`, `file(String rel, int size, long seed)` (deterministic bytes), `dir(String rel)`, `symlink(String rel, String target)`, `mtime(String rel, long ms)`, `append(String rel, byte[])`, `replaceAtomically(String rel, byte[])` (temp + `Files.move(ATOMIC_MOVE)` → new inode), `delete(String rel)` (recursive), `rename(String from, String to)`, `close()` (recursive delete) |
| `FixedClock` | `nowMillis()` returns a settable value (default 1 792 000 000 000); `advance(ms)`; `monotonicNanos()` advances 1 ms per call |
| `JvmResourceMonitor` | heap from `Runtime`; battery/thermal UNKNOWN; profile from `-Dv2.profile`; scriptable: `script(ResourceState... states)` |
| `DirBlobStore` | `BlobStore` over a directory: `replace` = temp file in the same dir + `FileChannel.force(true)` + `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`; `append` = `FileOutputStream(append=true)`; `sync` = `FileChannel.force`; `truncate` = `FileChannel.truncate(0)` |
| `KillingBlobStore` | decorator over `DirBlobStore`: `killAfterReplaceCommits(n)` throws `SimulatedKill` right after the n-th successful `AtomicWrite.commit()` (kill after a durable commit), `killBeforeReplaceCommit(n)` throws inside the n-th `commit()` before the move (kill during a snapshot write — old snapshot must load); `replaceCommits()` counter. Every commit made by any class goes through it, unlike a `CommitHook` wrapper |
| `FaultyStorageAccess` | decorator; rules: `denyList(path)`, `ioErrorList(path)`, `partialList(path, int entriesBeforeError)`, `vanishOnStat(path)` (also blanks the entry's attributes in its parent's listing — kind UNKNOWN, size -1 — so the walker actually stats it), `statFails(path)` (stat → IO_ERROR, listing attributes blanked), `listNotFound(path)`, `injectEntry(dir, DirEntry)` (e.g. kind OTHER, or a name with U+FFFD), `aliasIdentity(path, String identity)`, `setGrants(List<AccessGrant>)`, `revokeAfterListCalls(int n, String grantId)`, `killOnListCall(int n)` (throws `SimulatedKill`), `volumeOverrides(VolumeInfo)` (fake total/free, mounted=false), `listCalls()` counter |
| `SimulatedKill` | `extends Error` — never caught by v2 code (v2 catches only `Exception` subclasses; `ForbiddenApiTest`: no `catch (Throwable` / `catch (Error` / `catch (OutOfMemoryError` anywhere in v2, no exception) |
| `MediaOnlyStorageAccess` | decorator simulating Mode A: lists only files whose `MediaFamilies.of(ext)` ∈ configured types (directories still listed), grants = one A_MEDIASTORE grant with `mediaTypes` |
| `TestControl` | `ScanControl` with `cancelAfterProgress(n)`, `pauseAfterProgress(n)`; kills after commits are done with `KillingBlobStore` and `snapshotEveryMs = 0` |
| `JvmDevice` | `V2Device` wiring for tests and V2Cli (`fileOps()` null, `text()` null) |
| `V2Cli` | ARCHITECTURE §9; Phase 1 implements `scan` with `--state-dir`, `--volume-id`, `--kind`, `--resume`, `--restricted`, `--exclude`, `--now`, `--halt-after-commits`, `--pretty`; `static int run(String[] args, PrintStream out, PrintStream err)` used by tests, `main` calls it and exits |

`V2Cli scan` wiring: `PosixStorageAccess` with one `DIRECTORY` volume (`root` = argument, `fileKeyStable = TRUE`,
`restrictedPrefixes` = `--restricted` values or default `[Android/data, Android/obb]`), one B_ALL_FILES grant;
`MemoryStore.open(new DirBlobStore(stateDir), --snapshot-every-ms or -1, clock)` (wrapped in `KillingBlobStore` for
`--halt-after-commits`, whose kill is `Runtime.halt(137)`) or `volatileStore()`; `Pipeline` with `[DiscoveryEngine]`; JSON via
`V2Json.writeResult(...)` (a jvmtest class; field order exactly as ARCHITECTURE §9). Exit codes: 0 ok, 2 usage, 3
store error, 4 encoding not UTF-8, 137 halt hook.

`PosixStorageAccess` (v2.discovery):

```java
public final class PosixStorageAccess implements StorageAccess {
    public static final class Root { public String volumeId; public VolumeKind kind; public String label, path;
        public Tri fileKeyStable = Tri.UNKNOWN, caseInsensitive = Tri.FALSE; public java.util.List<String> restrictedPrefixes; }
    public PosixStorageAccess(java.util.List<Root> roots, java.util.List<AccessGrant> grants);
    public static String canonical(String path);    // toRealPath() string, or normalized absolute path if that fails
}
```
* `list`: resolve `root/raw`; refuse (`PERMISSION_DENIED`, error "outside root") if the normalized absolute path is not
  under the root; `Files.newDirectoryStream`; per entry `Files.readAttributes(p, BasicFileAttributes.class, NOFOLLOW_LINKS)`:
  kind from `isSymbolicLink/isDirectory/isRegularFile/isOther`, `size()`, `lastModifiedTime().toMillis()`, inode parsed with
  regex `ino=(\d+)` from `fileKey().toString()` (else -1). An attribute failure on one entry → that entry `kind = UNKNOWN`,
  `size = -1`. `DirectoryIteratorException` → `PARTIAL_IO_ERROR` with the entries read so far. Exceptions map through `IoStatus.of`.
* `stat`: `readAttributes(..., NOFOLLOW_LINKS)`; `readable = Tri.of(Files.isReadable(p))`, `writable` likewise; `birthTime`
  and `atime` are always unknown (JDK 21 on Linux reports mtime as creation time).
* `open`: `FileChannel.open(p, READ)` wrapped as `ReadableFile` (`read(pos, ..)` = `channel.read(ByteBuffer.wrap(..), pos)`).
* `dirIdentity`: `"ino:" + fileKey().toString()` when non-null, else `"path:" + canonical(p)`; null on error.
* `physicalKey` (in `list` entries and `stat`): `fileKey().toString()` parsed to `"<dev>:<ino>"`, null if unavailable.
* `resolveLink`: `canonical(p)` (i.e. `toRealPath()`), relativised against `canonical(root)`; null if not under it or on error.
* `volumes`: for each root: `mounted = Files.isDirectory(root)`; total/free `-1` for `DIRECTORY`, else
  `Files.getFileStore(root).getTotalSpace()/getUsableSpace()`.

---

## 10. Tests (one class per line; each a `run(String[])` returning failures)

| Test class | Cases (each is a `T.check`) |
|---|---|
| `ForbiddenApiTest` | scans every `.java` under `app/src/main/java/com/storageplanner/v2/`: no `import android.`, `java.awt`, `javax.imageio`, `java.net`, `java.lang.reflect`; no `Class.forName`, `ClassLoader`, `ProcessBuilder`, `Runtime.getRuntime().exec`, `System.load`, `.delete()` (no-argument call, i.e. `File.delete`; `StringBuilder.delete(int,int)` is fine), `.renameTo(`, `Files.move`, `Files.delete`, `deleteOnExit`, `System.currentTimeMillis`, `System.nanoTime`, `new Date()`; no `catch (Throwable` / `catch (Error` / `catch (OutOfMemoryError` anywhere; no import of `core.Scanner/UnitBuilder/Planner/Review/Unit/Option/FileItem/ResultStore/Sorter`; a planted violation in a temp copy is detected (self-test) |
| `PathNormalizerTest` | NFD `é` → NFC `é`; `""`/`.`/`..`/`a/b`(as a name)/NUL rejected; `normalizeRelative("a//b/")` = `a/b`; `a/../b` rejected; `join`, `parent` (`""`→null), `name`, `depth`; `isUnder("ab","a")` false, `isUnder("a/b","a")` true, `isUnder(x,"")` true; backslash, leading space, emoji `😀`, Devanagari `हिन्दी`, Arabic preserved; 255-byte UTF-8 name accepted |
| `FileIdsTest` | the 5 golden vectors; output length 32, lower-case hex; each argument change changes the id; separator ambiguity (`("a","bc")` vs `("ab","c")` as volume/path) gives different ids |
| `JsonTest` | round-trip of nested maps/lists; escapes `"` `\` control chars, U+2028, surrogate pair emoji; `Long.MAX_VALUE` stays a `Long`; doubles formatted deterministically (6 decimals max, no exponent, `-0.0` → `0`); NaN/Infinity written as `null`; `valueTime(Unknown.TIME)` → `null`; depth > 64 rejected; malformed inputs (trailing comma, unterminated string, bare word) throw `JsonException` with offset |
| `StoreContractTest` | (runs against `MemoryStore.volatileStore()`; Phase 10 reuses it for SQLite) copy semantics (mutate returned record → store unchanged); `byPath` preference (present over removed); `bySize`, `bySha256` updated on change and remove; `childrenOf`/`underPrefix` boundaries (`a` vs `ab`, root `""`), ascending order; `all()` key order; journal duplicate key throws; `rollback` restores previous values incl. removed records |
| `StorePersistenceTest` | flush + reopen equal (all 16 tables, a record with every unknown sentinel and NaN round-trips); `killBeforeReplaceCommit` during the 2nd snapshot → reopen gives exactly the 1st snapshot's state; `killAfterReplaceCommits(2)` → the 2nd state; snapshot cut at every 997th byte offset or with one flipped byte → `StoreException` (caller resets, `D_STORE_RESET`), never a partial load; `snapshotEveryMs = 60000` with `FixedClock`: commits within the interval write nothing, the first commit after it writes one snapshot; `flush()` with nothing dirty writes nothing; unknown JSON field ignored; missing field → unknown value |
| `CapabilityRegistryTest` | longest prefix wins; tie order B > C > A > D; inactive grant ignored; refresh events ADDED/REVOKED/CHANGED/RESTORED with correct `revokedAt`; `authoritativeFor` true for unrestricted grant, true for `.jpg` under image-only grant, false for `.pdf` under it |
| `VolumeRegistryTest` | first sight: `identityScheme` "ino"/"path" by stability; later stability change keeps scheme + event; alias dedupe (same canonical root) keeps the primary + `D_VOLUME_ALIAS`; volume unmounted → its PRESENT files `VOLUME_ABSENT` + event once (second refresh: no new event, no writes); remount + walk → PRESENT again |
| `WalkerTest` | (TempTree + PosixStorageAccess, `--release 8` APIs only) 1 basic tree: every file once, sizes/mtimes equal to `Files` values, ids = `FileIds.of(..., "ino:"+inode)`, deterministic order of `files.all()`; 2 zero-byte files: `sizeBytes == 0`, presence PRESENT; 3 Unicode: NFD-named file on disk → `path` NFC, `rawPath` NFD, `sa.stat(rawPath)` OK; emoji/Devanagari names; 127×`é` (254-byte) name; 4 `maxPathChars = 64` → `PATH_TOO_LONG` dir record and counter, no crash; 5 symlink loop (`a/loop -> ..`): default → `SYMLINK_SKIPPED`, each real file once; `followSymlinks=true` → walk terminates, `LOOP` recorded, each real file once; link to a directory outside the root → `OUTSIDE_ROOT` (nothing outside read: `listCalls` has no path outside); 6 bind-mount alias via `aliasIdentity` → `LOOP`, and records stored under the alias path by an earlier scan (alias visited first then) become `UNVERIFIABLE` (`D_LOOP`), never REMOVED; 6b hard link (`Files.createLink`) → two records with equal `physicalKey`; 7 unreadable dir (`denyList`) → `PERMISSION_DENIED`, coverage lists it, previously known files under it `UNVERIFIABLE` with `D_PARENT_UNREADABLE`, none `REMOVED`; 8 vanishing file (`vanishOnStat`) → not recorded on first scan, `REMOVED` (`D_VANISHED`) when known, counter `vanishedDuringScan == 1`; 9 `maxDepth = 3` on depth-5 tree → `DEPTH_LIMIT`, deeper known files `UNVERIFIABLE`; 10 `maxEntriesPerDir = 10` with 25 files → 10 records, `entriesSkipped = 15`, `TRUNCATED_ENTRY_LIMIT`; known files among the unprocessed 15 untouched; 11 injected `OTHER` entry → `specialFilesSkipped == 1`; 12 `partialList(dir, 3)` → `PARTIAL_IO_ERROR`, known files missing from the partial listing `UNVERIFIABLE`, none `REMOVED`; 13 restricted `Android/data` (readable on disk) → `RESTRICTED`, not listed, coverage `OS_RESTRICTED`; 14 excluded path → `EXCLUDED`, nothing below recorded; 15 injected U+FFFD name with vanish → `undecodableNames == 1`, coverage `UNDECODABLE_NAMES`; 16 NFC/NFD twins in one dir → two records, second keeps raw path, `D_NAME_COLLISION`; 17 `maxTotalEntries = 20` → `TOTAL_LIMIT`, `complete == false`; 18 (REVIEW_1 F-B2) `listNotFound("")` on the volume root after a first scan → zero REMOVED, all files `UNVERIFIABLE`/`VOLUME_ABSENT`; `listNotFound("a")` on a subdirectory → its files UNVERIFIABLE in this scan, REMOVED only in the next scan once the parent listing no longer contains `a`; 19 (F-M1) `statFails(f)` on an `ino` volume for a known file → `UNVERIFIABLE` (`D_STAT_FAILED`), same fileId, nothing REPLACED; 20 directory `x` replaced by a file `x` (and vice versa) → old subtree REMOVED (`D_DIR_REMOVED`) / old file REMOVED, new record ADDED |
| `DiscoveryResumeTest` | (store opened with `snapshotEveryMs = 0`, plus one run with 60 000 and `FixedClock` to cover "resume from an older snapshot") reference = uninterrupted scan of a 3-level tree with one 1 200-file directory (`commitEvery = 500`); for k in {1, 2, 4, 7}: `killOnListCall(k)` (kill before commit) and `KillingBlobStore.killAfterReplaceCommits(k)` (kill after commit) and `killBeforeReplaceCommit(k)`: discard the store, reopen from the same `DirBlobStore`, `Pipeline.run(resume=true)` → `COMPLETED`; files and directories tables equal to the reference (compared as Codecs JSON lines); `resumedCount == 1`; events include `D_INTERRUPTED`, `D_RESUMED`; two kills in one scan → still equal; `pauseAfterProgress` → `PAUSED` then resume → equal; `cancelAfterProgress` → `CANCELLED`, a following non-resume run creates scanId 2 and completes |
| `IncrementalScanTest` | scan 1; then: add file; append to file (same inode, mtime +2 s); `replaceAtomically` a file; delete a file; rename a file; delete a directory with 3 files; add a directory with 2 files; `touch` only (mtime change) a file; scan 2 (INCREMENTAL) → `ADDED` = {new, renamed target, 2 in new dir, replacement}, `CHANGED` = {appended, touched}, `REPLACED` = {old id of replaced}, `REMOVED` = {deleted, rename source, 3 in deleted dir}; all others not written in scan 2 (`changedPutsSinceOpen()` during scan 2 ≤ changed records + changed DirRecords + session puts); on a `"path"`-scheme volume the replaced file keeps its id and is `CHANGED`; same-size content change with mtime restored → not detected (asserts the documented limit) |
| `PermissionChangeTest` | grant for `Documents` revoked between scans (`setGrants`) → event `D_GRANT_REVOKED`, `Documents` `NOT_GRANTED`, its files `UNVERIFIABLE` with `D_PERMISSION_REVOKED`, none REMOVED; re-granted → `D_GRANT_RESTORED`, files PRESENT; `revokeAfterListCalls` mid-scan → `D_GRANTS_CHANGED_DURING_SCAN`, coverage `grantsChangedDuringScan == true`, affected files UNVERIFIABLE; Mode A (`MediaOnlyStorageAccess`) after a Mode B scan → `.pdf`/`.apk` files `UNVERIFIABLE` (`D_MEDIA_ONLY_VISIBILITY`); a `.jpg` hidden by the decorator (simulating `.nomedia` / partial photo access) → `UNVERIFIABLE`, not REMOVED; a `.jpg` deleted on disk → also `UNVERIFIABLE` (Mode A is never authoritative, F-M2); coverage has `MEDIA_ONLY_VISIBILITY`; `installed == null` → an `Android/media/<pkg>/x` file has area `app_owner_unknown`, never `residue`; with a FULL installed set lacking `<pkg>` → `residue`, and after the package appears → re-derived to its media area (F-M3) |
| `CoverageTest` | Σ `areas.bytes` == Σ `discoveredBytes`; every non-OK DirRecord of the scan is in `inaccessible` (cap 5 in this test → 5 listed, `inaccessibleTotal` correct); restricted prefixes listed even if absent on disk; DIRECTORY volume totals `-1` and `unaccountedBytes == -1`; with `volumeOverrides(total=10 GB, free=4 GB)` → `used = 6 GB`, `unaccounted = used − discovered`; unmounted volume → `VOLUME_ABSENT` entry |
| `V2CliTest` | `run(["scan", tmp, "--out", f])` exit 0; JSON parses with `Json`; top-level keys in ARCHITECTURE §9 order; Phase-2+ sections present and empty/null; two runs byte-identical after removing `timings`; `--state-dir` + second run with `--kind incremental` reports changes; `--halt-after-commits` is exercised only by the shell test below (it halts the JVM) |

`tools/v2test.sh` additionally runs one end-to-end kill test as shell: `V2Cli scan <TempTree copy> --state-dir S
--halt-after-commits 2` (expect exit 137), then `--resume`, then compares the JSON with a clean run using
`python3 -I tools/v2eval/evaluate.py --compare a.json b.json` (ignores `timings`, `scan.resumedCount`, `coverage.events`).

### 10.1 Dataset checks (run by the lead/implementer, not in CI)

1. `V2Cli scan /home/user/sp-dataset/<phone root> --out p1.json --exclude manifest.json --exclude scenarios.json ...`
   then `evaluate.py --result p1.json --dataset /home/user/sp-dataset --gate phase1`.
2. Mutation check: copy the dataset (`cp -a` to a scratch dir), scan with `--state-dir S`, apply `mutations.json` with
   `tools/v2eval/apply_mutations.py`, scan again `--kind incremental --state-dir S`, then
   `evaluate.py mutations --before r1.json --after r2.json --mutations mutations.json`.
3. `V2Cli scan /home/user/sp-dataset-drive/files` → 268 files found (the corpus has no subdirectories of note; it checks
   non-ASCII-free but long names and HEIC files are discovered like any other file).

## 11. `evaluate.py` — Phase-1 scope

* `evaluate.py --result R --dataset D [--root-prefix P] [--gate phase1] [--out M]`: loads `manifest.json`
  (expects `{"files":[...]}` or a list; each file at least `path`, `size`), reports `found`, `missing` (manifest paths not in
  R or not PRESENT), `size_mismatch`, `unlabelled` (in R, not in manifest), `presence` histogram, coverage summary
  (inaccessible by reason). Gate phase1: `missing == 0 && size_mismatch == 0`.
* `evaluate.py mutations --before R1 --after R2 --mutations M`: expected changes per mutation op (`add` → ADDED, `modify` →
  CHANGED or REPLACED+ADDED, `delete` → REMOVED, `rename` → source REMOVED + target ADDED, `touch` → CHANGED); reports
  unexpected and missing change states; gate: both empty. The op names are mapped through a table at the top of the
  script so the dataset agent's vocabulary can be adapted in one place.
* `evaluate.py --compare A B`: structural JSON equality ignoring the volatile keys listed above.

## 12. Exit criteria (Phase 1)

1. `bash tools/v2test.sh` passes locally and in CI; `ForbiddenApiTest` included.
2. Dataset: `--gate phase1` passes (100% of manifest files found with equal sizes).
3. Two consecutive scans of an unchanged dataset: identical `fileId`s, zero ADDED/CHANGED/REMOVED in scan 2, and scan 2
   writes no file records.
4. Resume and incremental tests (§10) pass, including the shell kill test.
5. Coverage report lists every injected inaccessible location with the right reason.

## 13. Design choices

| # | Choice | Rejected alternative | Why |
|---|---|---|---|
| 1-1 | Removal is decided per directory at the time its listing is complete (`DirectoryReconciler`) | A global "not seen this scan → removed" sweep based on a per-file `lastSeenScanId` | The sweep needs every unchanged file rewritten every scan (and a dirty store, hence a snapshot, on every unchanged scan) and gives wrong answers after partial walks. Per-directory reconciliation writes only changes and is naturally correct after resume. |
| 1-2 | A partially processed directory is re-processed from its start on resume (no "last name done" cursor) | Persist the last processed name | Upserts are idempotent and no-ops when unchanged; a name cursor would miss files created before the cursor between kill and resume. Cost: at most one directory re-statted. |
| 1-3 | Symlinks are not followed by default; following exists only as an option with canonical-root and visited-set checks | Follow symlinks with loop detection | Shared storage on Android has no meaningful symlinks; following risks double counting and escaping the granted root. The option keeps the loop-protection code tested. |
| 1-4 | Restricted prefixes (Android/data, Android/obb) are reported, never listed, even if listing would "succeed" | Try to list and report what comes back | On Android 11+ these return empty listings, which would look like "covered and empty". |
| 1-5 | Counters are derived from records at stage end | Increment counters while walking | Exact after kills/resumes without double counting. |
| 1-6 | Permission tests use fault injection | `chmod` | Tests run as root here; file modes do not restrict root. |
| 1-7 | `rawPath` kept separately from the NFC `path` | Normalize and use NFC for file access | Linux file names are byte strings; an NFD name cannot be opened by its NFC spelling. |
