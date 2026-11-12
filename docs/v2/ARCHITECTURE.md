# Storage Planner v2 — Architecture

Status: design, for review. Author: architect agent. Scope: everything that spans phases. Per-phase
implementation plans: `PHASE_1.md`, `PHASE_2.md` (later phases get their own file before they start).

Spec references are to the v2 technical specification (sections 1–19). Lead decisions A1–A9 are taken
as given; where this document extends them it says so under **Design choices** (section 15).

---

## 0. Ground rules every class obeys

| Rule | Consequence for the implementer |
|---|---|
| Java 8 source/target, compiled with `javac --release 8` | No `List.of`, `var`, `String.repeat`, `Optional.isEmpty`, etc. `--release 8` makes the compiler reject them. |
| No third-party jars | Vendored source already in the tree (`com.google.zxing`, used by `core.Codes`) may be called only through `core` classes. |
| `com.storageplanner.v2..` has **no** `android.*`, `java.awt.*`, `javax.imageio.*`, `java.net.*`, `java.lang.reflect.*` imports, no `Class.forName`, `ClassLoader`, `ProcessBuilder`, `Runtime.exec`, `System.load*`, `File.delete`, `File.renameTo`, `Files.delete*`, `Files.move` | Enforced by `ForbiddenApiTest` (Phase 1), which greps the v2 sources and fails the build. Deletion/moves only ever happen behind `v2.device.FileOps` (implementations outside v2). |
| v2 may call `core.ImageFeatures`, `core.Classifier` (`classify`, `magic`), `core.Codes`, `core.Faces`, `core.FaceNet`, `core.TextRules`, `core.PhotoQuality`, `core.EyeState`, `core.ImageSignals`; and the pure-Java `tools.Exif` | v2 never imports `core.Scanner`, `core.UnitBuilder`, `core.Planner`, `core.Review`, `core.Unit`, `core.Option`, `core.FileItem`, `core.ResultStore`, `core.Sorter`. `ForbiddenApiTest` checks these imports too. |
| `core/` edits: visibility widening only | Every widening is listed in the phase plan that needs it, with the exact member. Phase 1 and 2 need none. |
| Every timestamp comes from `v2.device.Clock` | Tests use a fixed clock; no `System.currentTimeMillis()` in v2. |
| Every file read goes through `v2.device.StorageAccess` | No `new File(..)` / `FileInputStream` / `RandomAccessFile` on user files in v2 except inside `v2.discovery.PosixStorageAccess` (the one pure-Java implementation, see §3.1). |
| Deterministic output | Iteration orders are defined (sorted by key or by path); JSON output is byte-identical for identical input and clock. |
| JVM must run with `LC_ALL=C.UTF-8` | In this container `sun.jnu.encoding` is `ANSI_X3.4-1968` by default, so non-ASCII file names come back as `?`. `tools/v2test.sh` exports `LC_ALL=C.UTF-8`; `V2Cli` refuses to start unless `System.getProperty("sun.jnu.encoding")` is `UTF-8`. |
| Tests run as root in this container | `chmod 000` does not make a directory unreadable for root. Unreadable/permission tests use the fault-injecting `FaultyStorageAccess` (jvmtest), never file modes. |

---

## 1. Package map

All paths under `app/src/main/java/com/storageplanner/` unless noted.

| Package | Phase | Contents | May depend on |
|---|---|---|---|
| `v2` | 1 | `V2` (version constants) | — |
| `v2.model` | 1 (grows) | Records and enums of §2. No logic beyond `copy()`, `key()`, small derived getters. | `v2` |
| `v2.util` | 1 | `Json` (reader/writer), `Hex`, `Hashing` (SHA-256, CRC32 helpers), `Bytes` (endian reads), `Io` (bounded reads over `ReadableFile`), `Text` (NFC, safe truncation) | `v2` |
| `v2.device` | 1 (grows) | Device interfaces of §3 and their small value types | `v2.model`, `v2.util` |
| `v2.store` | 1 | `V2Store`, table interfaces, `MemoryStore`, snapshot/WAL codec (§4) | `v2.model`, `v2.util`, `v2.device.BlobStore` |
| `v2.discovery` | 1 | Volumes, capabilities, walker, file ids, change detection, coverage, `PosixStorageAccess` | model, util, device, store |
| `v2.features` | 2 | Type detection, metadata probes, hashing, image basics, APK/PDF/archive/MP4/audio probes, scheduler/budget | + `core.ImageFeatures`, `core.Classifier`, `tools.Exif` |
| `v2.pipeline` | 1 (grows) | `Pipeline`, `PipelineConfig`, `Stage`, `StageOutcome`, `Progress` | everything above |
| `v2.graph` | 3 | Relationship builders, duplicate groups, near-duplicate matcher, archive/backup matcher, keeper selection | model, util, device, store, features |
| `v2.content` | 3 (interfaces), 6, 7 | `ContentClassifier`, `EmbeddingProvider`, `TextReader` bridges; deterministic classifiers wrapping `core.TextRules`, `core.Codes`, `core.Faces`; Phase 7 model manager | + core utilities |
| `v2.risk` | 4 | `HardRules`, `UtilityEstimator`, `LossEstimator`, `RecoverabilityEstimator`, `ConfidenceEstimator`, `Assessor` | graph, content |
| `v2.plan` | 5 | `ReasonCatalog`, `CandidateGenerator`, `TierAssigner`, `PresentationPolicy` (standard/aggressive), `Planner2`, `PlanValidator` | risk |
| `v2.learn` | 8 | `FeedbackRecorder`, `PreferenceModel` (regularised logistic), `PreferenceExplainer` | plan |
| `v2.exec` | 9 | `Revalidator`, `Executor`, `Journal`, `QuarantineManager`, `CompressionVerifier`, `RecoveryOnStart` | plan, `v2.device.FileOps` |
| `app.v2` (`com.storageplanner.app.v2`) | A, 10 | Android implementations of `v2.device` interfaces, `V2Runner`, `V2Adapter` (android-free file), Android SQLite store (Phase 10) | android + v2 + core |
| `jvmtest/com/storageplanner/v2test` | 1 (grows) | JVM device implementations, fault injectors, fixture builders, test mains, `AllV2Tests`, `V2Cli` | v2 + core + JDK |
| `tools/v2eval/` | 1 (grows) | `evaluate.py` (stdlib only), `README` section inside the script docstring | Python 3.8+ stdlib |
| `tools/v2test.sh` | 1 | Compiles core + v2 + v2test with `--release 8`, runs `AllV2Tests` with `LC_ALL=C.UTF-8` | JDK 17+ |

Dependency direction is strictly downward in the table (a package may use packages above it, never below).
`v2.pipeline` is the only package that knows all stages.

---

## 2. Core data model (`v2.model`)

### 2.1 Unknown values (spec 5.1: "unknown, not zero")

| Java type | Unknown representation | JSON | Helper |
|---|---|---|---|
| timestamp `long` (epoch ms, UTC) | `Unknown.TIME = Long.MIN_VALUE` | `null` | `Unknown.knownTime(long)` |
| size/count `long`, `int` | `Unknown.LONG = -1L`, `Unknown.INT = -1` | `null` | `Unknown.knownCount(long)` / `knownCount(int)` |
| `double` measure / probability / score | `Double.NaN` | `null` | `Double.isNaN` |
| `String`, objects, lists | `null` (an empty list means "known to be empty") | `null` vs `[]` | — |
| boolean facts | `Tri.UNKNOWN` (enum `Tri {TRUE, FALSE, UNKNOWN}`) | `null` | `Tri.of(boolean)` |
| enums | an explicit `UNKNOWN` constant in every enum that can be unknown | `"UNKNOWN"` | — |
| 64-bit perceptual hash | `boolean hasPhash = false` (all 2^64 values are valid hashes) | `null` | — |

Rule U1: code never substitutes 0/false/"" for an unknown when computing a score; every estimator takes
unknown inputs explicitly and widens its uncertainty instead (Phase 4 tests this per estimator).

```java
package com.storageplanner.v2.model;
public final class Unknown {
    public static final long TIME = Long.MIN_VALUE;
    public static final long LONG = -1L;
    public static final int INT = -1;
    public static boolean knownTime(long t) { return t != TIME; }      // -1 is a valid (1969) timestamp
    public static boolean knownCount(long v) { return v >= 0; }         // sizes and counts: -1 = unknown
    public static boolean knownCount(int v) { return v >= 0; }
    private Unknown() {}
}
public enum Tri { TRUE, FALSE, UNKNOWN; public static Tri of(boolean b) { return b ? TRUE : FALSE; } }
public interface Record { String key(); Record copy(); }   // every store record implements this
```

### 2.2 Volume and capability registry (spec 4.1, 4.2)

```java
public enum VolumeKind { PRIMARY_SHARED, SECONDARY_SHARED, REMOVABLE, SAF_TREE, APP_PRIVATE, DIRECTORY /*JVM*/, UNKNOWN }
public enum AccessMode { A_MEDIASTORE, B_ALL_FILES, C_SAF_TREE, D_APP_PRIVATE }

public final class AccessGrant {          // one capability: what a mode lets us do under a path prefix
    public String volumeId;
    public String prefix;                 // normalized relative path; "" = whole volume
    public AccessMode mode;
    public Tri canList, canReadMetadata, canReadContent, canWrite, canDelete;  // canWrite/canDelete always FALSE until Phase 9 sign-off (A8)
    public java.util.List<String> mediaTypes; // Mode A only: subset of {"image","video","audio"}; null = not restricted by media type
    public long firstSeenAt = Unknown.TIME, lastSeenAt = Unknown.TIME, revokedAt = Unknown.TIME;
    public boolean active;
    public String grantId() { return mode + ":" + volumeId + ":" + prefix; }
}

public final class Volume implements Record {   // table storage_volumes
    public String volumeId;               // see §2.2.1
    public VolumeKind kind = VolumeKind.UNKNOWN;
    public String label;                  // user-visible name, null unknown
    public String root;                   // absolute root path, or tree URI for SAF; null if not path-addressable
    public String fsUuid;                 // filesystem UUID, null unknown
    public long totalBytes = Unknown.LONG, freeBytes = Unknown.LONG;
    public boolean mounted;
    public Tri fileKeyStable = Tri.UNKNOWN;    // inode numbers survive remount/reboot → used in file ids
    public Tri caseInsensitive = Tri.UNKNOWN;
    public Tri atimeReliable = Tri.FALSE;      // Android mounts with relatime/noatime: never trusted unless an impl proves it
    public String identityScheme;         // frozen at first sight: "ino" or "path"; never changes for a volumeId (§2.3)
    public java.util.List<String> restrictedPrefixes = new java.util.ArrayList<String>(); // e.g. "Android/data", "Android/obb"
    public java.util.List<AccessGrant> grants = new java.util.ArrayList<AccessGrant>();
    public long firstSeenAt = Unknown.TIME, lastSeenAt = Unknown.TIME;
    public long lastFullScanId = Unknown.LONG;
}
```

#### 2.2.1 Volume identifiers (stable strings)

| Volume | `volumeId` |
|---|---|
| Primary shared storage, user 0 (`/storage/emulated/0`) | `primary` |
| Primary shared storage of another Android user N | `primary@N` |
| Removable / secondary volume with filesystem UUID | `uuid:<UUID upper-case>` (e.g. `uuid:1A2B-3C4D`) |
| Removable volume without UUID | `vol:<sha256(root path) first 16 hex>` (marked `fileKeyStable=FALSE`) |
| SAF tree grant | `saf:<sha256(tree URI string) first 16 hex>` |
| This app's private storage | `private` |
| JVM directory (V2Cli/tests) | value of `--volume-id`, default `primary` (so dataset ids look like a phone's) |

### 2.3 FileRecord (spec 13.1, table `files`)

Every 13.1 field is present (first block). Fields after it are what Phases 1–2 need to keep the 13.1
fields honest. Key = `fileId`.

| Field | Type | Unknown | Set by | Meaning |
|---|---|---|---|---|
| `fileId` | String (32 hex) | never unknown | P1 `FileIds` | §2.3.1 |
| `volumeId` | String | never | P1 | §2.2.1 |
| `path` (= uri_or_path) | String | never | P1 | normalized relative path (§2.3.1) |
| `uri` | String | null = path-addressable | P1 | `content://` URI for Mode A/C entries |
| `name` | String | never | P1 | last path component, NFC |
| `extension` | String | never (`""` = none) | P1 | lower-case text after the last `.`; `""` for no dot or a leading-dot-only name (`.nomedia`) |
| `mimeType` | String | null | P2 | from content type; from extension only if content type unknown (`mimeSource`) |
| `sizeBytes` | long | -1 | P1 | stat size; 0 is a real zero-byte file |
| `createdAt` | long | `TIME` | P2 | best creation time, source in `createdAtSource` |
| `modifiedAt` | long | `TIME` | P1 | fs mtime |
| `lastAccessedAt` | long | `TIME` | P1 | only if `Volume.atimeReliable == TRUE`; otherwise always unknown |
| `sha256` | String (64 hex) | null | P2 | full content hash (only where needed, see Phase 2 hash plan) |
| `phash` / `hasPhash` | long / boolean | `hasPhash=false` | P2 | `core.ImageFeatures.phash` of upright luma, long edge 1024 |
| `embeddingRef` (semantic_embedding_reference) | String | null | P7 | key into `file_embeddings` |
| `category` | String | `"UNKNOWN"` | P3/P6 | content category id (§2.10) |
| `extractionStatus` | enum `ExtractionStatus` | `NOT_STARTED` | P2 | `NOT_STARTED, PARTIAL, COMPLETE, UNREADABLE, FAILED, UNSUPPORTED` |
| `utility` | double | NaN | P4 | copy of `Assessment.u90.mid` for list views |
| `lossSeverity` | double | NaN | P4 | copy of `Assessment.loss` |
| `recoverability` | enum `Recoverability` | `UNKNOWN` | P4 | copy |
| `confidence` | double | NaN | P4 | copy of `Assessment.confidence` |
| `protection` (protection_status) | enum `Protection` | `NONE` | P4/P8 | `NONE, USER_PROTECTED, RULE_PROTECTED` |
| `scanVersion` | int | -1 | P1 | `V2.SCAN_VERSION` of the scan that last wrote this record |
| — extras — | | | | |
| `inode` | long | -1 | P1 | from `FileStat.inode` |
| `presence` | enum `Presence` | — | P1 | `PRESENT, REMOVED, UNVERIFIABLE, VOLUME_ABSENT` |
| `presenceReason` | String (reason code) | null | P1 | why UNVERIFIABLE/REMOVED (`D_PARENT_UNREADABLE`, `D_PERMISSION_REVOKED`, `D_REPLACED`, ...) |
| `change` | enum `ChangeState` | — | P1 | last change observed: `ADDED, CHANGED, REMOVED, REPLACED`; it happened in scan `changeScanId` (a record not written in scan S was unchanged in S — unchanged files are never rewritten) |
| `firstSeenScanId`, `changeScanId`, `removedScanId` | long | -1 | P1 | scan ids |
| `rawPath` | String | null = same as `path` | P1 | on-disk name chain when it differs from the NFC `path` (NFD names, NFC collisions); all `StorageAccess` calls use `rawPath != null ? rawPath : path` |
| `readable`, `writable` | Tri | UNKNOWN | P1 | permissions as reported by stat |
| `accessMode` | enum `AccessMode` | null | P1 | the grant through which it was seen |
| `area`, `ownerPackage` | String | null | P1 | `core.Classifier.classify(path, installedPkgs)` → `{area, pkg}` |
| `hidden` | boolean | — | P1 | any path component starts with `.` |
| `type` | String | `"unknown"` | P2 | content type from signature (Phase 2 `FileType`) |
| `typeSource` | enum `TypeSource` | `NONE` | P2 | `SIGNATURE, STRUCTURE, EXTENSION, NONE` |
| `extMismatch` | enum `ExtMismatch` | `UNKNOWN` | P2 | `NONE, BENIGN, SUSPICIOUS, UNKNOWN` |
| `integrity` | enum `Integrity` | `UNKNOWN` | P2 | `OK, TRUNCATED, CORRUPT, ENCRYPTED, UNKNOWN` |
| `createdAtSource` | enum `TimeSource` | `NONE` | P2 | `EXIF, CONTAINER, FILENAME, FS_BIRTH, NONE` |
| `extractionConfidence` | double | NaN | P2 | 0..1, Phase 2 §Confidence |
| `tiersDone`, `tiersFailed`, `tiersPending` | int bitmasks (bit k = tier k, k=1..6) | 0 | P2+ | progressive processing state |
| `duplicateGroupId` | String | null | P3 | `duplicate_groups` key |
| `lastError` | String | null | P1/P2 | last read error class + short message (no file content) |

#### 2.3.1 Stable file id (spec 4.3)

```
fileId = lowerHex( SHA-256( UTF-8( "sp-fid-1" + "\u0000" + volumeId + "\u0000" + path + "\u0000" + identityToken ) ) )[0..32)
path          = PathNormalizer.normalize(...) : relative to the volume root, '/' separators, Unicode NFC,
                no leading/trailing '/', no "." or ".." components, case preserved, "" = volume root
identityToken = "ino:" + inode   if Volume.identityScheme == "ino" and inode is known
              = ""               otherwise
```

Consequences (tested in Phase 1): rename/move → new id; in-place rewrite (same inode) → same id,
`change=CHANGED`; delete+recreate or atomic-replace at the same path (new inode) → old id `REMOVED`
(`presenceReason=D_REPLACED`), new id `ADDED`. The id is an application key, never proof of identity:
execution re-verifies with `Fingerprint` + SHA-256 (spec 4.3, 11.1).

`Fingerprint` (value class): `{long size, long mtime, long inode}`; `sameAs(Fingerprint o)` = sizes equal,
mtimes equal, and inodes equal when both known. Every derived record (features, hashes, relationships,
recommendations) stores the fingerprint it was computed from; a mismatch makes it stale.

### 2.4 Directory record (table `directories`, an addition to spec 13)

```java
public final class DirRecord implements Record {   // key = volumeId + "\u0000" + path
    public String volumeId, path;
    public String rawPath;              // as in FileRecord
    public DirStatus status;            // see PHASE_1.md §5.1 for the full enum and meaning of each value
    public long lastVisitedScanId = Unknown.LONG;   // scan in which the walker reached this directory (listed or not)
    public long lastListedOkScanId = Unknown.LONG;  // scan in which it was listed completely
    public long mtime = Unknown.TIME, inode = Unknown.LONG;
    public String identity;             // loop key: "ino:<inode>" or "path:<canonical path>"; null unknown
    public int entries = Unknown.INT, entriesSkipped = Unknown.INT;
    public String error;
}
```

### 2.5 Features, hashes, embeddings (tables `file_features`, `file_hashes`, `file_embeddings`)

`FeatureRecord` (key `fileId`): `Fingerprint fp; int[] extractorVersions` (indexed by `Extractors.*` ids);
`double confidence;` and at most one non-null sub-record per kind: `ImageInfo image; VideoInfo video;
AudioInfo audio; ApkInfo apk; PdfInfo pdf; ArchiveInfo archive;` plus `ExifInfo exif; NameDate nameDate;`
and `Map<String,String> extra` (free-form, small, for later phases). Field-level definitions of every
sub-record are in `PHASE_2.md` §2; each uses the unknown conventions of §2.1.

`HashRecord` (key `fileId`): `Fingerprint fp; String quickHash /*null*/; String sha256 /*null*/; long crc32 = -1;
long hashedAt = TIME; String reason /* "SIZE_COLLISION", "ALL", "REVALIDATE", ... */`.

`EmbeddingRecord` (key `fileId + ":" + modelId`, Phase 7): `String fileId, modelId; Fingerprint fp; float[] vector; long createdAt`.

### 2.6 Relationship edge (table `file_relationships`, spec 6.1)

```java
public enum RelType { EXACT_DUPLICATE, VISUALLY_SIMILAR, SEMANTICALLY_SIMILAR, EDITED_FROM, COMPRESSED_FROM,
                      BACKED_UP_BY, CONTAINED_IN, DEPENDS_ON, COMPANION_OF, VERSION_OF, THUMBNAIL_OF }
public enum Verification { UNVERIFIED, VERIFIED, REFUTED, STALE }

public final class Evidence {           // one observation behind an edge or a recommendation
    public String code;                 // e.g. "SHA256_EQUAL", "PHASH_DIST", "DIMS_RATIO", "NAME_PATTERN_WA", "ZIP_CRC_SIZE_EQUAL"
    public String value;                // machine-readable value, e.g. "6" (bits), "0.98"
    public boolean structural;          // true = byte/structure fact; false = similarity or classification signal
}

public final class Relationship implements Record {
    public String edgeId;               // hex(SHA-256(src + "\u0000" + type + "\u0000" + dst))[0..32)
    public String src, dst;             // node refs: fileId, or fileId + "!" + entryPath for an archive entry
    public RelType type;
    public double confidence;           // 0..1, never NaN on a stored edge
    public java.util.List<Evidence> evidence;
    public Verification verification;
    public Fingerprint srcFp, dstFp;    // fingerprints the edge was established on
    public long createdScanId, verifiedAt = Unknown.TIME;
}
```

Direction convention: "`src` TYPE `dst`" reads as an English sentence — `src EDITED_FROM dst` (src is the
edit), `src COMPRESSED_FROM dst` (src is the smaller copy), `src BACKED_UP_BY dst` (dst holds the backup),
`src CONTAINED_IN dst` (src is an archive-entry node or file, dst the archive), `src THUMBNAIL_OF dst`,
`src VERSION_OF dst` (src is the newer version), `src DEPENDS_ON dst`, `src COMPANION_OF dst` (src is the
sidecar: `.srt`, `.xmp`, `.aae`). Symmetric types (`EXACT_DUPLICATE`, `VISUALLY_SIMILAR`,
`SEMANTICALLY_SIMILAR`) are stored once with `src < dst` (String order).

Invariant G1 (A9): only `EXACT_DUPLICATE` with `verification == VERIFIED` (full SHA-256 equal, both
fingerprints current) and `BACKED_UP_BY` with `verification == VERIFIED` (byte-level match, §8) can make two
nodes *interchangeable*. All other types are context for utility/loss, never interchangeability.

### 2.7 DuplicateGroup (table `duplicate_groups`)

```java
public final class DuplicateGroup implements Record {
    public String groupId;               // "x:" + sha256 for exact groups; "n:" + smallest member fileId for near groups
    public boolean exact;                // true = all members share a verified SHA-256
    public java.util.List<String> members;   // fileIds, sorted
    public String keeperId;              // null = undecided (Phase 3 keeper selection)
    public java.util.List<String> keeperReasons;   // reason codes (§6)
    public long memberBytes, recoverableBytes = Unknown.LONG;   // recoverable computed in Phase 5 after keeper + dependency checks
    public long createdScanId; public Verification verification;
}
```

### 2.8 BackupRecord (table `backup_records`)

`backupId` (key, = edgeId of the `BACKED_UP_BY` edge); `subjectId` (fileId); `copyRef` (node ref);
`kind ∈ {LOCAL_COPY, ARCHIVE_ENTRY, USER_DECLARED}`; `level ∈ {NONE, NAME_MATCH, SIZE_MATCH, CRC32_MATCH,
SHA256_MATCH}`; `state ∈ {VERIFIED, INCOMPLETE, CORRUPT, MISSING, UNKNOWN}`; `verifiedAt`; `copyFp`.
Spec 8.3: only `level == SHA256_MATCH` (or `CRC32_MATCH` plus size plus a successful full inflate
of the entry in Phase 6+) gives `state = VERIFIED`. Name matches are evidence of a *possible* backup only.
Cloud backup is never verified (spec 2.2) and has no record kind.

### 2.9 Assessment (spec 8; not a table of its own)

Assessments are recomputed every run (cheap, deterministic) and are stored only as copies in `FileRecord`
plus the `Recommendation` that used them. Keeping six separate values is mandatory:

```java
public final class Assessment {
    public String fileId; public long scanId;
    // U: probability of need, per horizon, as an interval (low, mid, high); NaN = unknown
    public Interval u30, u90, u365, u1095;
    // L: loss severity 0..1 plus its factor breakdown (personal, financial, legal, sentimental, uniqueness, density, replacement)
    public double loss = Double.NaN; public java.util.Map<String, Double> lossFactors;
    // R: recoverability state + the evidence (edge ids / backup ids)
    public Recoverability recoverability = Recoverability.UNKNOWN;   // UNKNOWN, NO_VERIFIED_BACKUP, POSSIBLE_BACKUP, VERIFIED_BACKUP, REGENERABLE
    public java.util.List<String> recoveryEvidence;
    // C: confidence 0..1 in this assessment
    public double confidence = Double.NaN;
    // I: irreversibility per action 0..1 (DELETE, QUARANTINE, COMPRESS, ARCHIVE)
    public java.util.EnumMap<Action, Double> irreversibility;
    // B: usable bytes recovered per action; Unknown.LONG = cannot be estimated
    public java.util.EnumMap<Action, Long> benefit;
    public java.util.List<String> hardBlocks;   // hard-rule reason codes that forbid destructive actions (§7)
    public Protection protection = Protection.NONE;
}
public final class Interval { public final double low, mid, high; }   // all NaN = unknown; low<=mid<=high
```

### 2.10 Content categories

`category` strings: images — `PERSONAL_PHOTO, PEOPLE, PET, DOCUMENT_PHOTO, RECEIPT, SCREENSHOT, QR_CODE,
GREETING_MEME, ADVERTISEMENT, PRODUCT, FOOD, TRAVEL_SCENERY, SCREEN_PHOTO`; documents — `DOC_FINANCIAL,
DOC_LEGAL, DOC_MEDICAL, DOC_EDUCATIONAL, DOC_PERSONAL_RECORD, DOC_RECEIPT, DOC_INVOICE, DOC_CERTIFICATE,
DOC_MANUAL, DOC_BUSINESS, DOC_TEMPORARY`; other — `VIDEO, AUDIO, VOICE_NOTE, INSTALLER, ARCHIVE, BACKUP,
CACHE, TEMP, OTHER`; and `UNKNOWN`. Category is always accompanied by `categoryConfidence` (in
`FeatureRecord.extra["category.confidence"]`) and `categorySource` (`PATH`, `RULES`, `MODEL`).

### 2.11 Recommendation (table `recommendations`, spec 13.2)

```java
public enum Action { KEEP, DELETE, COMPRESS, ARCHIVE, QUARANTINE, REVIEW }
public enum Tier { SAFE, NO_LOSS, YOUR_CALL, REVIEW, PROTECTED }   // KEEP = absence of a recommendation
public enum ApprovalState { NOT_REQUESTED, PENDING, APPROVED, REJECTED, EXPIRED }

public final class Recommendation implements Record {
    public String recommendationId;     // hex(SHA-256(fileId + action + planVersion))[0..24)
    public String fileId;
    public Action proposedAction;
    public Tier tier;
    public long expectedSavingBytes = Unknown.LONG;
    public double expectedLoss = Double.NaN, confidence = Double.NaN, uncertainty = Double.NaN;
    public java.util.List<String> reasonCodes;            // ≥ 1, catalogue codes (§6), first = primary reason
    public java.util.Map<String, String> reasonParams;    // template values, e.g. keeper=DCIM/Camera/IMG_1.jpg
    public java.util.List<String> evidenceRefs;           // edgeIds, backupIds, "hash:<fileId>"
    public java.util.List<String> dependencyIds;          // fileIds that must stay (keeper, companions)
    public ApprovalState approvalState = ApprovalState.NOT_REQUESTED;
    public int planVersion;
    public Fingerprint fp;                                // fingerprint at recommendation time (staleness)
    public long createdScanId;
}
```

Tier semantics (spec 9.4) and the action each tier may carry:

| Tier | Allowed actions | Requires |
|---|---|---|
| SAFE | DELETE, QUARANTINE | regenerable/temporary/trashed by **structural** evidence (location + type), or verified redundant |
| NO_LOSS | DELETE, QUARANTINE, COMPRESS (lossless), ARCHIVE | a VERIFIED interchangeable copy that is kept (G1), or verified redundant installer/backup, or a lossless transform |
| YOUR_CALL | DELETE, QUARANTINE, COMPRESS (lossy) | plausible but not verified disposability; explicit approval |
| REVIEW | REVIEW | contradictory/unknown evidence; never part of an automatic plan |
| PROTECTED | KEEP | user protection, high-risk unique record, unresolved critical dependency |

### 2.12 ActionPlan (table `action_plans`)

```java
public final class ActionPlan implements Record {
    public String planId; public int planVersion; public long createdAt, scanId;
    public long targetBytes;            // Long.MAX_VALUE = "maximum safe recovery"
    public PlanMode mode;               // STANDARD, AGGRESSIVE
    public java.util.List<PlanItem> items;   // ordered by spec 10.3 priority
    public long achievableBytes;        // de-duplicated sum (no double counting across groups)
    public boolean reachesTarget;
    public Tier highestTier;            // highest-risk tier used, null if empty
    public PlanState state;             // DRAFT, PRESENTED, PARTIALLY_APPROVED, APPROVED, EXECUTING, DONE, STALE, CANCELLED
}
public final class PlanItem { public String recommendationId, fileId; public Action action; public Tier tier; public long bytes; public int order; public java.util.List<String> requires; }
```

### 2.13 Operation journal entry (table `action_operations`, spec 11)

Append-only. Key = `opId + "#" + seq` (seq 0..n per operation). One operation = one file + one action.

```java
public enum OpPhase { INTENT, PRECHECK_OK, PRECHECK_FAILED, STARTED, OUTPUT_WRITTEN, VERIFIED, COMMITTED,
                      ROLLBACK_STARTED, ROLLED_BACK, FAILED, RESTORE_STARTED, RESTORED }
public final class OperationEntry implements Record {
    public String opId; public int seq; public OpPhase phase; public long at;
    public String planId, recommendationId, fileId; public Action action;
    public String volumeId, originalPath, quarantinePath, outputPath, originalName;
    public String sha256Before, sha256After; public long sizeBefore = -1, sizeAfter = -1, mtimeBefore = Unknown.TIME;
    public long freeBefore = -1, freeAfter = -1;
    public String reasonCode;           // failure / skip reason (§6, X_* and H_* codes)
    public String detail;               // no file content, ever
}
```

Revalidation rules (spec 11.1) — implemented in Phase 9 `Revalidator`, listed here because Phase 5 must
store what they need:

| # | Check before any destructive step | Fail → reason |
|---|---|---|
| V1 | `approvalState == APPROVED` and plan not `STALE`/`CANCELLED` | `H_NOT_APPROVED` |
| V2 | stat: exists, same `Fingerprint` as `Recommendation.fp` | `H_STALE` (scenario 12) |
| V3 | SHA-256 recomputed equals stored, for DELETE/QUARANTINE of NO_LOSS duplicates and for every COMPRESS | `H_STALE` |
| V4 | every `dependencyIds` file: present, same fingerprint, readable, SHA-256 equal to the group hash (keeper re-hashed) | `H_KEEPER_MISSING` |
| V5 | capability for the path still grants `canDelete`/`canWrite` (permission re-read *now*) | `H_PERMISSION_LOST` (scenario 13) |
| V6 | destination free space ≥ needed + `SPARE_BYTES` (16 MiB, same as `tools.ZipCompressor.SPARE_BYTES`) | `X_LOW_SPACE` (scenario 14) |
| V7 | no other file in the plan depends on this file (DEPENDS_ON/COMPANION_OF closure) unless it is removed too | `H_DEPENDENCY` |

Journal protocol: write `INTENT` (committed) → precheck → `STARTED` → do the work → `OUTPUT_WRITTEN` →
verify (spec 11.5) → `VERIFIED` → `COMMITTED`. `RecoveryOnStart` (Phase 9) scans for operations whose
last phase is not terminal (`COMMITTED, ROLLED_BACK, FAILED, PRECHECK_FAILED, RESTORED`) and completes
or rolls back each one deterministically (scenario 15). Quarantine = move within the same volume to
`<volume>/.StoragePlanner/quarantine/<opId>/<originalName>`; never cross-volume moves (no copy+delete).

### 2.14 Feedback (table `user_feedback`, spec 12.2) and preferences

```java
public enum FeedbackLabel { DELETE, KEEP_PERMANENTLY, KEEP_FOR_NOW, ALREADY_BACKED_UP, DO_NOT_RECOMMEND_ITEM, NOT_INTERESTED_IN_TYPE }
public final class Feedback implements Record {
    public String feedbackId; public String fileId, recommendationId; public FeedbackLabel label;
    public String recommendationType;   // Action + ":" + primary reason code
    public long at; public Fingerprint fp; public boolean ambiguous;   // ambiguous = undo within 10 s, bulk action > 200 items, ... (Phase 8)
}
```
`Preference` (key `name`): `String name, value; long updatedAt`. `ModelRecord` (key `modelId`): `String
modelId, version, sha256; boolean enabled; String metricsJson; long installedAt`.
`ScanSession` (table `scan_sessions`) is defined in `PHASE_1.md` §2.

---

## 3. Device interfaces (`v2.device`)

All interfaces are android-free. Implementations: JVM in `jvmtest/.../v2test`, Android in `app.v2` (Phase A),
plus `v2.discovery.PosixStorageAccess` (pure `java.nio.file`, used by the JVM *and* by Android Mode B/D).

### 3.1 StorageAccess (file system and volume access)

```java
package com.storageplanner.v2.device;
public interface StorageAccess {
    /** Volumes visible now. Never null; empty list if none. */
    java.util.List<VolumeInfo> volumes();
    /** Access grants in force now, re-read on every call (permission changes are detected by diffing). */
    java.util.List<com.storageplanner.v2.model.AccessGrant> grants();
    /** Lists one directory with per-entry attributes. Never throws for I/O; status says what happened. */
    DirListing list(String volumeId, String dirPath);
    /** Stat without following symlinks. Never throws for I/O. */
    FileStat stat(String volumeId, String path);
    /** Random-access read handle; IOException if it cannot be opened (status mapped by IoStatus.of). */
    ReadableFile open(String volumeId, String path) throws java.io.IOException;
    /** Identity for loop detection: "ino:<dev>:<ino>" or "path:<canonical>"; null if unavailable. */
    String dirIdentity(String volumeId, String dirPath);
    /** Absolute local path if the file is path-addressable (JVM, Mode B, D), else null (Mode A/C). */
    String localPath(String volumeId, String path);
}

public final class VolumeInfo { public String volumeId; public VolumeKind kind; public String label, root, fsUuid;
    public long totalBytes = -1, freeBytes = -1; public boolean mounted = true; public Tri fileKeyStable, caseInsensitive;
    public java.util.List<String> restrictedPrefixes; }
public enum IoStatus { OK, NOT_FOUND, PERMISSION_DENIED, NOT_A_DIRECTORY, IO_ERROR, PARTIAL_IO_ERROR, UNSUPPORTED;
    public static IoStatus of(java.io.IOException e) { /* AccessDenied→PERMISSION_DENIED, NoSuchFile/FileNotFound→NOT_FOUND, NotDirectory→NOT_A_DIRECTORY, else IO_ERROR */ } }
public enum EntryKind { FILE, DIRECTORY, SYMLINK, OTHER, UNKNOWN }
public final class DirEntry { public String name; public EntryKind kind; public long size = -1, mtime = Long.MIN_VALUE, inode = -1; public String uri; }
public final class DirListing { public IoStatus status; public java.util.List<DirEntry> entries; public String error; }
public final class FileStat { public IoStatus status; public EntryKind kind; public long size = -1, mtime = Long.MIN_VALUE,
    birthTime = Long.MIN_VALUE, atime = Long.MIN_VALUE, inode = -1; public Tri readable, writable; public String linkTarget, error; }

public interface ReadableFile extends java.io.Closeable {
    long size() throws java.io.IOException;
    /** Reads up to len bytes at position; returns -1 at EOF. Never reads past size(). */
    int read(long position, byte[] buf, int off, int len) throws java.io.IOException;
}
```

### 3.2 MediaDecoder

```java
public interface MediaDecoder {
    /** Decodes to 8-bit luma, UPRIGHT (EXIF orientation applied), long edge ≈ longEdge (never upscaled).
     *  Must refuse (TOO_LARGE) when width*height > maxPixels, decided from the header before decoding. */
    DecodedImage decode(String volumeId, String path, int longEdge, long maxPixels, boolean wantArgb);
    /** Formats this decoder can decode, lower-case FileType ids (e.g. "jpeg","png","webp","gif","bmp"; Android adds "heic","avif"). */
    java.util.Set<String> decodableTypes();
    /** Frame of a video at timeMs, same contract as decode; null-status UNSUPPORTED if no video decoder (JVM). Phase 6. */
    DecodedImage videoFrame(String volumeId, String path, long timeMs, int longEdge);
}
public enum DecodeStatus { OK, NO_DECODER, UNSUPPORTED_FORMAT, CORRUPT, TOO_LARGE, IO_ERROR, OUT_OF_MEMORY, TIMEOUT }
public final class DecodedImage { public DecodeStatus status; public int[] luma, argb /*null unless wanted*/;
    public int w, h, origW = -1, origH = -1, orientation = -1 /*EXIF 1..8 applied*/; public String error; }
```
JVM implementation (`JvmMediaDecoder`): `ImageIO` for jpeg/png/gif/bmp (+webp only if an ImageIO
plugin exists — it does not by default: `NO_DECODER`), applies EXIF orientation read by
`v2.features.ExifReader`; HEIC/AVIF → `NO_DECODER`. `NO_DECODER` is *never* evidence about the image.

### 3.3 InstalledApps

```java
public interface InstalledApps {
    Availability availability();          // FULL, PARTIAL (package visibility filtered), UNAVAILABLE
    java.util.List<InstalledApp> list();  // empty if UNAVAILABLE
    InstalledApp get(String packageName); // null = not visible (NOT "not installed" unless availability == FULL)
    /** Optional fallback parse of an APK by the platform (Android PackageManager.getPackageArchiveInfo); null if unsupported. */
    InstalledApp inspectArchive(String localPath);
}
public final class InstalledApp { public String packageName, versionName, label, installerPackage;
    public long versionCode = -1, firstInstallTime = Long.MIN_VALUE, lastUpdateTime = Long.MIN_VALUE;
    public java.util.List<String> signingCertSha256;   // lower-hex, null unknown
    public Tri system, hasLauncher; }
```
JVM implementation reads `installed_apps.json` (dataset) or reports `UNAVAILABLE`.

### 3.4 Clock, ResourceMonitor, ScanControl

```java
public interface Clock { long nowMillis(); long monotonicNanos(); }
public interface ResourceMonitor { ResourceState sample(); DeviceProfile profile(); }
public enum DeviceProfile { LOW, BALANCED, HIGH }
public enum Thermal { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, UNKNOWN }
public final class ResourceState { public long heapFreeBytes = -1, heapMaxBytes = -1, storageFreeBytes = -1;
    public int batteryPercent = -1, cpuCount = -1; public Tri charging = Tri.UNKNOWN, userInteractive = Tri.UNKNOWN;
    public Thermal thermal = Thermal.UNKNOWN; }
public interface ScanControl {     // supplied by the caller (service / CLI / test)
    boolean cancelRequested();
    boolean pauseRequested();
    void progress(String stage, long done, long total);   // total -1 = unknown
}
```
Throttle policy (used by every stage, defined once in `v2.pipeline.Throttle`):

| Condition (from `sample()`) | Effect |
|---|---|
| `thermal >= SEVERE` or (`batteryPercent` in 0..14 and `charging != TRUE`) | stop at next commit, `ScanState.PAUSED`, reason `P_PAUSED_DEVICE` |
| `thermal == MODERATE` or `heapFreeBytes < 64 MiB` | concurrency 1, decodes 1 |
| `storageFreeBytes` in 0..(256 MiB) | do not write snapshots > 10% of free space; Phase 9: no compression |
| unknown values | no effect (unknown is not "bad") |

### 3.5 BlobStore (persistence of the store, app-private)

```java
public interface BlobStore {
    java.io.InputStream read(String name) throws java.io.IOException;     // null if absent
    java.io.OutputStream append(String name) throws java.io.IOException;  // creates if absent; caller flushes + closes
    /** fsync the named blob (after append+close) */
    void sync(String name) throws java.io.IOException;
    AtomicWrite replace(String name) throws java.io.IOException;          // old content stays until commit()
    void truncate(String name) throws java.io.IOException;                // to 0 bytes (no deletes anywhere)
    long length(String name);                                             // -1 if absent
    interface AtomicWrite { java.io.OutputStream stream(); void commit() throws java.io.IOException; void abort(); }
}
```
Android: `android.util.AtomicFile` in `getFilesDir()/v2/`. JVM (`DirBlobStore`, jvmtest): temp file +
`FileChannel.force` + `Files.move(ATOMIC_MOVE)`. v2 itself never renames or deletes.

### 3.6 FileOps (Phase 9 only; A8)

```java
public interface FileOps {
    OpResult moveWithinVolume(String volumeId, String fromPath, String toPath);   // no overwrite; fails if target exists
    OpResult delete(String volumeId, String path);
    OpResult createNew(String volumeId, String path, ContentWriter writer);       // fails if exists; fsyncs before returning OK
    long freeBytes(String volumeId);                                              // -1 unknown
    Tri supportsQuarantine(String volumeId);
    interface ContentWriter { void write(java.io.OutputStream out) throws java.io.IOException; }
}
public final class OpResult { public IoStatus status; public String error; public long bytesWritten = -1; }
```
Only `jvmtest` (temp directories) implements this until the owner signs off; there is no Android
implementation and no UI path to it (A8).

### 3.7 Aggregate

```java
public interface V2Device {
    StorageAccess storage(); MediaDecoder media(); InstalledApps apps(); Clock clock();
    ResourceMonitor resources(); BlobStore blobs();
    FileOps fileOps();                  // null = read-only device (the shipped app)
    com.storageplanner.v2.content.TextReader text();   // Phase 6; null = no OCR
}
```

---

## 4. Store (`v2.store`, A3)

### 4.1 Interface

```java
public interface V2Store extends java.io.Closeable {
    int schemaVersion();
    FileTable files();                        // files
    Table<FeatureRecord> features();          // file_features
    Table<HashRecord> hashes();               // file_hashes
    Table<EmbeddingRecord> embeddings();      // file_embeddings
    RelationshipTable relationships();        // file_relationships
    Table<DuplicateGroup> duplicateGroups();  // duplicate_groups
    Table<BackupRecord> backups();            // backup_records
    RecommendationTable recommendations();    // recommendations
    Table<ActionPlan> actionPlans();          // action_plans
    JournalTable operations();                // action_operations (append-only: put of an existing key throws)
    Table<Feedback> feedback();               // user_feedback
    Table<Preference> preferences();          // user_preferences
    Table<ModelRecord> models();              // model_metadata
    Table<ScanSession> scanSessions();        // scan_sessions
    Table<Volume> volumes();                  // storage_volumes
    DirTable directories();                   // directories (addition)
    /** Makes every put/remove since the last commit durable, atomically. */
    void commit() throws StoreException;
    /** Drops uncommitted changes (reloads them from the committed state). */
    void rollback();
    /** Rewrites durable state compactly (snapshot) — call at scan end; no-op for SQLite. */
    void compact() throws StoreException;
}
public interface Table<R extends Record> {
    R get(String key);                  // a copy; null if absent
    void put(R record);                 // stores a copy (read-your-writes before commit)
    boolean remove(String key);
    int size();
    Iterable<R> all();                  // copies, ascending key order, stable under concurrent put (iterates a key snapshot)
}
public interface FileTable extends Table<FileRecord> {
    FileRecord byPath(String volumeId, String path);
    java.util.List<FileRecord> bySize(long size);
    java.util.List<FileRecord> bySha256(String sha256);
    Iterable<FileRecord> byVolume(String volumeId);          // ascending path
    Iterable<FileRecord> underPrefix(String volumeId, String dirPath);   // all descendants, ascending path
    java.util.List<FileRecord> childrenOf(String volumeId, String dirPath); // direct children only, ascending path
}
public interface RelationshipTable extends Table<Relationship> { java.util.List<Relationship> of(String nodeRef); }
public interface RecommendationTable extends Table<Recommendation> { java.util.List<Recommendation> forFile(String fileId); }
public interface JournalTable extends Table<OperationEntry> { java.util.List<OperationEntry> forOp(String opId); java.util.List<String> openOps(); }
public interface DirTable extends Table<DirRecord> { DirRecord get(String volumeId, String path);
    java.util.List<DirRecord> childrenOf(String volumeId, String dirPath); Iterable<DirRecord> underPrefix(String volumeId, String dirPath); }
public final class StoreException extends Exception { ... }
```
Concurrency: single writer (the pipeline coordinator thread); all methods `synchronized` in `MemoryStore`.

### 4.2 MemoryStore persistence format (JSON lines)

Two blobs: `store.snap` (full snapshot, replaced atomically) and `store.wal` (append-only log).

```
store.snap:
  {"format":"sp-v2-store","schema":1,"lastSeq":<long>,"createdAt":<ms>}
  {"t":"<table>","v":{<record JSON>}}            one line per record, tables in §4.1 order, keys ascending
  {"end":true,"records":<n>,"crc32":"<8 hex of all preceding bytes>"}
store.wal:
  {"seq":<n>,"ops":[{"t":"files","op":"put","v":{...}},{"t":"files","op":"del","k":"..."}],"crc32":"<8 hex of the ops array text>"}
  ... one line per commit
```
Load: read snapshot (reject if header/trailer/CRC invalid → `StoreException`, the caller starts a fresh
store and the coverage report says `D_STORE_RESET`); then replay WAL lines with `seq > lastSeq` in order,
stopping silently at the first line that is truncated or fails its CRC (a torn last write is an
uncommitted transaction). `commit()`: serialise the pending ops as one line, `append` + `sync`. `compact()`:
write a new snapshot with `lastSeq = current seq` via `replace()`, commit it, then `truncate("store.wal")`;
a crash between the two leaves WAL lines with `seq <= lastSeq`, which load skips. Compaction runs at
stage ends and whenever the WAL exceeds 16 MiB.

Record ↔ JSON: one codec class per record in `v2.store.Codecs` (`static void write(JsonWriter, FileRecord)`,
`static FileRecord readFile(java.util.Map<String,Object>)`, ...). Unknowns are written as JSON `null` and read
back as the §2.1 sentinels. Unknown JSON fields are ignored (forward compatibility); missing fields take
their unknown value (backward compatibility). `schema` bump rules: additive changes keep the number;
renames/semantic changes bump it and add a migration in `Codecs.migrate(int from, Map rec)`.

### 4.3 SQLite fit (Phase 10)

Each `Table` becomes `CREATE TABLE <name>(k TEXT PRIMARY KEY, v BLOB /*the same JSON*/, <index columns>)`;
index columns: `files(volume_id, path UNIQUE, size_bytes, sha256)`, `file_relationships(src, dst)`,
`recommendations(file_id)`, `action_operations(op_id, seq)`, `directories(volume_id, path)`. `commit()` =
`setTransactionSuccessful(); endTransaction(); beginTransaction()`. `compact()` = no-op (or `VACUUM` when
idle). The same `Codecs` are reused, so both implementations share one record format and one test suite
(`StoreContractTest` runs against any `V2Store`).

---

## 5. Pipeline orchestration (`v2.pipeline`)

### 5.1 Stages and the six processing tiers (spec 14.1)

| Order | Stage id | Spec tier | Phase | Works on | Output |
|---|---|---|---|---|---|
| 1 | `DISCOVER` | — | 1 | volumes, directories | `files`, `directories`, `storage_volumes`, presence/change states |
| 2 | `T1_METADATA` | Tier 1 | 2 | files with `change ∈ {ADDED, CHANGED, REPLACED}` or stale extractor versions | type, mime, mismatch, integrity, header metadata (EXIF, dims, container, PDF, name dates) |
| 3 | `T2_HASH_STRUCT` | Tier 2 | 2 | size collisions; zip/APK/OOXML/tar central directories; APK manifest + signer | `file_hashes`, archive listings, APK features |
| 4 | `T3_IMAGE` | Tier 3 | 2 | decodable images (priority order) | phash, sharpness, brightness, contrast, exposure, colour stats |
| 5 | `GRAPH` | — | 3 | all present files | relationships, duplicate groups, backups |
| 6 | `T4_TEXT` | Tier 4 | 6 | documents, screenshots, receipts candidates | OCR text, document fields/categories |
| 7 | `T5_MODEL` | Tier 5 | 7 (optional) | per-model selection | categories, embeddings |
| 8 | `T6_DETAIL` | Tier 6 | 3, 4, 6 | uncertain candidates only (ambiguous near-dups, unverified backups, long videos) | SSIM checks, full-entry CRC verification, adaptive video sampling |
| 9 | `ASSESS` | — | 4 | all present files | `Assessment` (in memory) + `FileRecord` copies |
| 10 | `RECOMMEND` | — | 5 | assessments | `recommendations` |
| 11 | `PLAN` | — | 5 | recommendations, target, mode | `action_plans` |

```java
public interface Stage {
    String id();
    /** Runs until done, budget exhausted, pause or cancel. Must be idempotent and resumable from store state alone. */
    StageOutcome run(StageContext ctx) throws StoreException;
}
public final class StageContext { public V2Store store; public V2Device device; public PipelineConfig config;
    public ScanSession session; public ScanControl control; public Budget budget; public Throttle throttle; }
public enum StageOutcome { DONE, PAUSED, CANCELLED, BUDGET_EXHAUSTED, FAILED }
public final class Pipeline {
    public Pipeline(V2Device device, V2Store store, PipelineConfig config, java.util.List<Stage> stages);
    /** Starts a new scan, or resumes the latest RUNNING/PAUSED/INTERRUPTED session when config.resume. */
    public PipelineResult run(ScanControl control) throws StoreException;
}
```
`PipelineConfig`: `ScanKind kind` (FULL/INCREMENTAL), `boolean resume`, `int maxTier` (1..6), `HashPolicy`,
`WalkLimits`, `Budget` (ms, bytes read, decodes), `int workers` (0 = inline, deterministic), `PlanMode`,
`long targetBytes`, `Set<String> volumeIds` (null = all).

### 5.2 Checkpoint and resume

* Every stage keeps its progress **in the store**, written in the same commit as its results, so a kill
  between commits loses at most one batch and never produces an inconsistent pair (records without
  checkpoint or vice versa).
* `DISCOVER` uses an explicit `ScanCheckpoint` (frontier of directories still to list, partially listed
  directory + last processed name) stored inside `ScanSession` — see `PHASE_1.md` §4.
* Tier stages need no cursor: their work set is "files whose `tiersDone` bit k is clear, or whose stored
  `FeatureRecord.fp` / extractor versions differ from current". Re-running a stage after a kill re-selects
  exactly the unfinished files.
* `ScanSession.stage` records the stage in progress; `Pipeline.run(resume)` skips completed stages.
* Commit cadence: every 500 files or 3 s (whichever first) in tier stages; every directory (or every 500
  entries of a large directory) in `DISCOVER`. Compaction at each stage end.
* A session whose process died is found on next start with `state == RUNNING` and is marked `INTERRUPTED`
  before resuming (so the coverage report can say the scan was interrupted).

### 5.3 Concurrency and backpressure

Coordinator (caller thread) selects work in a deterministic order and feeds an `ArrayBlockingQueue<Job>`
(capacity `2 × workers`, min 4); `workers` threads run extractors and put results into a second bounded
queue (capacity 64); only the coordinator writes to the store. Decodes are limited by a `Semaphore`
(`LOW` 1, `BALANCED` 2, `HIGH` 3). Profiles: `LOW` → 1 worker, `BALANCED` → 2, `HIGH` → 4. With
`workers == 0` jobs run inline (all tests except the concurrency test). Budget and throttle are checked by
the coordinator before each enqueue and every 2 s; on stop it drains in-flight jobs, commits, and returns
`PAUSED` / `BUDGET_EXHAUSTED`.

---

## 6. Reason-code catalogue (`v2.plan.ReasonCatalog`)

Stable string codes; the sentence is a template filled from `Recommendation.reasonParams`
(`{name}` placeholders; a missing parameter renders as "unknown"). Codes are never renamed; retired codes
stay in the table with `retired=true`. Prefixes: `S_` safe, `N_` no-loss, `Y_` your call, `R_` review,
`P_` protected, `H_` hard-rule block, `G_` graph/keeper, `D_` discovery/coverage, `X_` execution,
`L_` learning, `PL_` planner. Every Recommendation carries ≥ 1 code; `ReasonCatalogTest` checks every code
used anywhere in v2 sources exists here and every template's placeholders are documented.

| Code | Tier | Sentence (user-facing) |
|---|---|---|
| `S_TEMP_FILE` | SAFE | "Unfinished download or temporary file ({ext}), last changed {age} ago; apps do not need it afterwards." |
| `S_TRASHED` | SAFE | "Already in the trash; Android would remove it on its own {when}." |
| `S_THUMBNAIL` | SAFE | "Thumbnail the gallery rebuilds automatically from the original." |
| `S_APP_CACHE_FILE` | SAFE | "Cache file of {app}; the app recreates it when needed." |
| `S_UNINSTALLED_LEFTOVER` | SAFE | "Left behind by {pkg}, which is no longer installed." (only for non-media data; media leftovers are `Y_`) |
| `N_EXACT_DUPLICATE` | NO_LOSS | "Identical copy: {keeper} has exactly the same content (checked byte for byte) and is kept." |
| `N_INSTALLER_INSTALLED` | NO_LOSS | "Installer for {app} {version}: the same version, signed by the same developer, is installed." |
| `N_OLDER_BACKUP` | NO_LOSS | "Older backup of {what}: a newer backup ({newer}) exists and was checked to be complete." |
| `N_LOSSLESS_SMALLER` | NO_LOSS | "Can be stored {saving} smaller without changing any pixel or byte of content." |
| `Y_SIMILAR_SHOT` | YOUR_CALL | "Nearly the same picture as {keeper}, which is kept; this one {why}." |
| `Y_CHAT_COPY` | YOUR_CALL | "Smaller chat copy of {original}, which is kept. The copy itself may matter to you (who sent it, the chat)." |
| `Y_OLD_DOWNLOAD` | YOUR_CALL | "Downloaded {age} ago. Android does not tell us whether you opened it since." |
| `Y_OLD_SCREENSHOT` | YOUR_CALL | "Screenshot from {age} ago that looks like {kind}." |
| `Y_LOW_QUALITY` | YOUR_CALL | "Very {problem} picture. Quality says nothing about how much it means to you, so it is your call." |
| `Y_OLDER_INSTALLER` | YOUR_CALL | "Installer of an older {app} ({version}); {installed} is installed. Keep it if you may want to go back." |
| `Y_INSTALLER_NOT_INSTALLED` | YOUR_CALL | "Installer for {app}, which is not installed. It may be hard to get again." |
| `Y_BACKUP_UNVERIFIED` | YOUR_CALL | "Looks backed up in {where}, but the backup could not be fully checked." |
| `Y_FORWARDED_MEDIA` | YOUR_CALL | "Looks like a forwarded {kind} received in {app} (and {signal})." |
| `Y_LEFTOVER_MEDIA` | YOUR_CALL | "Media left by {pkg}, which is no longer installed." |
| `R_UNREADABLE` | REVIEW | "Could not be read ({error}), so we cannot tell what it contains." |
| `R_CONTENT_UNKNOWN` | REVIEW | "Its content could not be analysed on this device ({format}), so it is not judged." |
| `R_CONTRADICTORY` | REVIEW | "Signals disagree: {a}, but {b}." |
| `R_UNCERTAIN_COPY` | REVIEW | "May be a copy of {other}, but this could not be confirmed." |
| `R_UNKNOWN_DEPENDENCY` | REVIEW | "Other files may need it ({hint})." |
| `R_NAME_CONTENT_MISMATCH` | REVIEW | "Its name says .{ext} but it contains {type}." |
| `R_ENCRYPTED` | REVIEW | "Encrypted or password-protected; its content cannot be checked." |
| `R_ARCHIVE_UNSAFE` | REVIEW | "Archive with entries that could not be checked safely ({issue})." |
| `R_CANNOT_ACT` | REVIEW | "Current permissions do not allow acting on it ({mode})." |
| `P_USER_PROTECTED` | PROTECTED | "You marked this as protected." |
| `P_UNIQUE_RECORD` | PROTECTED | "Only copy of what looks like a {category}; losing it would be hard to undo." |
| `P_KEEPER` | PROTECTED | "Kept as the best copy of {n} identical or similar files." |
| `P_CRITICAL_DEPENDENCY` | PROTECTED | "Needed by {other}." |
| `P_ARCHIVE_UNIQUE_CONTENT` | PROTECTED | "Archive holding {n} documents that exist nowhere else on this phone." |
| `P_PERSONAL_MEDIA_UNIQUE` | PROTECTED | "Only copy of a personal photo or video{people}." |
| `H_LAST_VERIFIED_COPY` | (block) | "Not offered for deletion: no other verified copy exists." |
| `H_SIMILAR_NOT_IDENTICAL` | (block) | "Similar is not identical, so it is only offered as your call." |
| `H_CLASSIFICATION_ONLY` | (block) | "A guess about the content alone never decides deletion." |
| `H_UNKNOWN_FEATURES` | (block) | "Missing information is treated as unknown, not as unimportant." |
| `H_NOT_APPROVED` | (exec) | "Skipped: you have not approved this." |
| `H_STALE` | (exec) | "Skipped: the file changed after the plan was made." |
| `H_KEEPER_MISSING` | (exec) | "Skipped: the copy that would be kept is no longer available or has changed." |
| `H_PERMISSION_LOST` | (exec) | "Skipped: access to this folder was removed." |
| `H_DEPENDENCY` | (exec) | "Skipped: another kept file needs it." |
| `G_KEEPER_CANONICAL` | — | "Kept because it is in {place}, where the original usually lives." |
| `G_KEEPER_QUALITY` | — | "Kept because it is the {better} version." |
| `G_KEEPER_OLDEST` | — | "Kept because it is the earliest copy." |
| `D_PARENT_UNREADABLE` | — | "Could not check whether it still exists: its folder could not be read." |
| `D_PERMISSION_REVOKED` | — | "Not checked: access to {prefix} was removed." |
| `D_REPLACED` | — | "Replaced by a new file with the same name." |
| `D_VOLUME_ABSENT` | — | "{volume} is not inserted or not mounted." |
| `D_STORE_RESET` | — | "Saved results could not be read; this scan started from scratch." |
| `PL_TARGET_UNREACHABLE` | — | "Your target of {target} cannot be reached safely; {achievable} can." |
| `PL_PAUSED_DEVICE` | — | "Paused to protect the battery or temperature; it resumes later." |
| `X_VERIFIED` | — | "Done and checked: {bytes} freed." |
| `X_ROLLED_BACK` | — | "Undone: {why}." |
| `X_LOW_SPACE` | — | "Stopped: not enough free space to finish safely." |
| `X_RESTORED` | — | "Restored to {path}." |
| `L_PERSONALISED` | — | "Shown {more_less} because you {did} for similar items before." |

---

## 7. Hard rules (A9) — single implementation in `v2.risk.HardRules`

`HardRules.check(FileRecord f, Assessment a, Graph g, Action action, Tier proposed)` returns the list of
blocking codes; it has **no mode parameter** (rule H7). Every recommendation passes through it in
`TierAssigner` and again in `PlanValidator`; `Executor` repeats V1–V7 of §2.13.

| Id | Rule | Block code | Test (phase) |
|---|---|---|---|
| H1 | A DELETE/QUARANTINE at SAFE or NO_LOSS needs regenerability (S_ rules, structural evidence) or a VERIFIED interchangeable copy (G1) that is present, readable, kept, and not itself in the plan; at any tier, never all members of an exact group | `H_LAST_VERIFIED_COPY` | P4 unit + P5 property test (random graphs) |
| H2 | VISUALLY_SIMILAR / SEMANTICALLY_SIMILAR / EDITED_FROM / COMPRESSED_FROM / VERSION_OF never justify SAFE or NO_LOSS | `H_SIMILAR_NOT_IDENTICAL` | P3/P5 |
| H3 | A category/classification is never the only non-size evidence of a DELETE; it can never move a file into SAFE/NO_LOSS; it may raise protection | `H_CLASSIFICATION_ONLY` | P5 (classifier stub returning "meme 0.99" for every file) |
| H4 | Unknown features never lower U, L or I; a file whose content features are unknown is never SAFE/NO_LOSS unless the rule is purely structural (S_ location rules) | `H_UNKNOWN_FEATURES` | P2 (HEIC corpus), P4 |
| H5 | No destructive action without `APPROVED`; there is no API that approves on the user's behalf (only `ApprovalService.approve(recId, UserToken)`, Phase 9) | `H_NOT_APPROVED` | P9 |
| H6 | `Protection != NONE` → only KEEP | — | P4 |
| H7 | Aggressive mode only changes `PresentationPolicy` thresholds (what YOUR_CALL/REVIEW items are shown); `HardRules` output is identical in both modes | — | P5: run both modes, compare blocks |
| H8 | Never break DEPENDS_ON / COMPANION_OF: a dependency of a kept file is kept | `H_DEPENDENCY` | P3/P5 |
| H9 | Execution revalidates (V1–V7) | `H_STALE`, ... | P9 |

---

## 8. Phase-3–5 algorithm outline (binding interfaces; detail in their phase plans)

* **Exact duplicates (P3):** group by `sha256` (Phase 2 hashes); zero-byte files never grouped; edge
  `EXACT_DUPLICATE` VERIFIED when both full hashes are current. Keeper selection order: protected member →
  canonical location (`area` order of `core.UnitBuilder`'s `prio`: camera, screenshot, document, music, movies,
  pictures, download, wa_doc, wa_image, wa_video, telegram, wa_sent, wa_sent_video) → earliest `createdAt`
  → shortest path → smallest fileId.
* **Near duplicates (P3):** candidates by phash LSH (4 bands × 16 bits), confirm with Hamming ≤ `T_near`,
  aspect ratio within 2%, then structural check (downscaled SSIM ≥ `T_ssim` via `MediaDecoder` luma; T6).
  `T_near` and `T_ssim` calibrated on the dataset by `tools/v2eval/calibrate.py` (Phase 3), starting from
  classic `NEAR_DUP_BITS=10`, `SENT_MATCH_BITS=8`. Relationship sub-typing: smaller dims + JPEG re-encode +
  WhatsApp name/location → `COMPRESSED_FROM`; same dims, phash ≤ T but edited EXIF `Software` / crop /
  name pattern (`_edited`, `-01`, `(1)`) → `EDITED_FROM`; same burst (timestamp ≤ 10 min, name timestamps)
  → `VISUALLY_SIMILAR`.
* **Archives and backups (P3):** archive entries are nodes `fileId!entryPath`; `CONTAINED_IN` edges from
  listings; a file is `BACKED_UP_BY` an entry when name and size match (POSSIBLE), CRC32 matches the
  file's CRC32 (CRC32_MATCH), and VERIFIED only after T6 inflates the entry and compares SHA-256.
  Corrupt/truncated archives (`integrity != OK`) yield `state = CORRUPT` and never support recoverability
  (scenario 10).
* **Risk (P4):** `ExpectedLoss(delete) = U90.mid × L × (1 − Rv)` where `Rv` = 1.0 VERIFIED_BACKUP / REGENERABLE,
  0.3 POSSIBLE_BACKUP, 0 otherwise; `UncertaintyPenalty = λ × (U90.high − U90.low) × L` with λ = 1; unknown
  inputs use the *pessimistic* bound (high U, high L, Rv = 0). All terms in [0,1]; storage benefit
  normalised by `B / (B + 50 MiB)`.
* **Planner (P5):** one action per file; group savings counted once (members minus keeper); priority order of
  spec 10.3; within the lowest feasible tier, greedy by benefit/expected-loss then prune (the classic
  tiered-greedy-prune approach, re-implemented on v2 types); never includes REVIEW/PROTECTED; reports
  achievable bytes when the target is unreachable (`PL_TARGET_UNREACHABLE`).

---

## 9. V2Cli and its JSON (A4)

`com.storageplanner.v2test.V2Cli` (jvmtest):

```
java -cp <classes> com.storageplanner.v2test.V2Cli scan <root> --out <result.json>
     [--state-dir <dir>]          persistent store (BlobStore); default: fresh temp dir (no incremental)
     [--volume-id primary] [--kind full|incremental] [--resume]
     [--max-tier 1..6]            default: highest implemented
     [--hash collisions|all]      default collisions
     [--installed-apps <installed_apps.json>] [--now <epoch ms>] [--workers N] [--budget-ms N]
     [--restricted <prefix>]...   simulate OS-restricted prefixes (default: Android/data, Android/obb)
     [--exclude <relpath>]...     not scanned (e.g. manifest.json); reported in coverage as EXCLUDED_BY_USER
     [--mode standard|aggressive] [--target-bytes N|max]
     [--halt-after-commits N]     test hook: Runtime.halt(137) after N commits (kill simulation)
     [--pretty]
```

Output (UTF-8, keys in this order, arrays sorted by `path` then by id; `null` = unknown):

```json
{
  "schema": "sp-v2-result", "schemaVersion": 1,
  "algorithmVersion": "2.0.0-p2", "scanVersion": 2, "phase": 2,
  "generatedAt": 1792000000000, "root": "/home/user/sp-dataset/phone", "volumeId": "primary",
  "options": {"kind": "full", "maxTier": 3, "hash": "collisions", "mode": "standard", "targetBytes": null},
  "scan": {"scanId": 1, "state": "COMPLETED", "startedAt": 0, "endedAt": 0, "resumedCount": 0, "stagesDone": ["DISCOVER","T1_METADATA","T2_HASH_STRUCT","T3_IMAGE"]},
  "volumes": [ {"volumeId": "primary", "kind": "DIRECTORY", "totalBytes": null, "freeBytes": null, "modes": ["B_ALL_FILES"], "identityScheme": "ino"} ],
  "files": [ {
     "fileId": "…", "path": "DCIM/Camera/IMG_20240627_175114.jpg", "name": "…", "ext": "jpg",
     "size": 2699117, "mtime": 0, "createdAt": 0, "createdAtSource": "EXIF", "lastAccessedAt": null,
     "presence": "PRESENT", "change": "ADDED", "area": "camera", "ownerPackage": null, "hidden": false,
     "type": "jpeg", "typeSource": "SIGNATURE", "mime": "image/jpeg", "extMismatch": "NONE", "integrity": "OK",
     "quickHash": null, "sha256": null, "phash": "1f2e…(16 hex)",
     "extraction": {"status": "COMPLETE", "tiersDone": [1,2,3], "tiersPending": [], "tiersFailed": [], "confidence": 0.95, "error": null},
     "image": {…ImageInfo…}, "video": null, "audio": null, "apk": null, "pdf": null, "archive": {…ArchiveInfo, entries capped at 200…},
     "category": "UNKNOWN", "categoryConfidence": null, "duplicateGroupId": null,
     "assessment": null
  } ],
  "relationships": [ {"edgeId": "…", "src": "<path or path!entry>", "dst": "…", "srcId": "…", "dstId": "…", "type": "EXACT_DUPLICATE", "confidence": 1.0, "verification": "VERIFIED", "evidence": [{"code": "SHA256_EQUAL", "value": "…", "structural": true}]} ],
  "duplicateGroups": [ {"groupId": "…", "exact": true, "members": ["path", "…"], "keeper": "path", "keeperReasons": ["G_KEEPER_CANONICAL"], "memberBytes": 0, "recoverableBytes": 0} ],
  "recommendations": [ {"recommendationId": "…", "fileId": "…", "path": "…", "action": "DELETE", "tier": "NO_LOSS",
       "expectedSavingBytes": 0, "expectedLoss": 0.0, "confidence": 0.0, "uncertainty": 0.0,
       "reasonCodes": ["N_EXACT_DUPLICATE"], "reasons": ["Identical copy: … is kept."], "reasonParams": {"keeper": "…"},
       "evidenceRefs": ["…"], "dependencyIds": ["…"], "dependencyPaths": ["…"], "approvalState": "NOT_REQUESTED"} ],
  "plan": {"planId": "…", "planVersion": 1, "mode": "STANDARD", "targetBytes": null, "achievableBytes": 0, "reachesTarget": true, "highestTier": "NO_LOSS",
           "items": [ {"order": 1, "path": "…", "action": "DELETE", "tier": "NO_LOSS", "bytes": 0, "requires": ["…"]} ]},
  "coverage": { …CoverageReport, PHASE_1.md §6 + PHASE_2.md extraction section… },
  "execution": null,
  "timings": {"totalMs": 0, "stages": {"DISCOVER": 0}, "filesPerSecond": 0.0, "bytesRead": 0, "peakHeapBytes": 0}
}
```
Sections a phase has not implemented are emitted empty (`[]`) or `null`, never omitted. `timings` is the
only nondeterministic section; `V2CliDeterminismTest` compares two runs with `timings` removed.

---

## 10. Evaluator (`tools/v2eval/evaluate.py`, A4/A5)

```
python3 -I tools/v2eval/evaluate.py --result result.json --dataset /home/user/sp-dataset
        [--root-prefix <prefix stripped from manifest paths>] [--baseline metrics.json] [--gate phase1..phase10]
        [--out metrics.json]
```
Stdlib only. Joins result files to manifest entries by normalized relative path (NFC, `/`). Files in the
result that are not in the manifest are "unlabelled" (counted, not scored; any SAFE/NO_LOSS DELETE on one
is listed as a warning). Manifest relationship entries are normalised from `{"type", "target"|"other"|"to"|"path"}`.

Ranks used below: `SAFE 0 < NO_LOSS 1 < YOUR_CALL 2 < REVIEW 3 < KEEP 4 < PROTECTED 5`.
Predicted tier of a file: `PROTECTED` if it has a PROTECTED recommendation; else the minimum-rank tier over its
recommendations; else `KEEP`. "Delete recommendation" = action `DELETE` or `QUARANTINE`.

| Metric | Definition | Gate |
|---|---|---|
| **Dangerous deletion recommendation rate (DDRR)** | `#{files: must_never_delete ∧ has delete rec at SAFE or NO_LOSS} / #{must_never_delete files}` (lead's definition). Also reported: `exposure` = same with any tier incl. YOUR_CALL, and the list of offending paths | DDRR = 0 at every phase ≥ 4 (hard fail) |
| **Deletion precision** | per tier T ∈ {SAFE, NO_LOSS, YOUR_CALL}: of the files with a delete rec at T, the fraction that are *correct*, where correct iff `¬must_never_delete ∧ expected_tier ∈ {SAFE,NO_LOSS,YOUR_CALL} ∧ rank(expected) ≤ rank(T)` (the recommendation is not more aggressive than the label). Also reported: strict precision (`expected == T`) and overall precision over all three tiers | P5: SAFE ≥ 0.98, NO_LOSS ≥ 0.98, YOUR_CALL ≥ 0.80 |
| Deletion recall | of files with `expected_tier ∈ {SAFE, NO_LOSS}`, fraction with a delete rec at the same tier | P5: ≥ 0.90 |
| **Duplicate cluster accuracy** | pairwise over unordered file pairs. Truth = transitive closure of manifest `EXACT_DUPLICATE`; prediction = co-membership in an `exact` duplicate group. `P = TP/(TP+FP)`, `R = TP/(TP+FN)`, `F1`. Same for near-duplicates with truth = undirected manifest `VISUALLY_SIMILAR ∪ EDITED_FROM ∪ COMPRESSED_FROM` edges and prediction = undirected edges of those types (no closure) | P3: exact P = 1.000, R ≥ 0.99; near P ≥ 0.95, R ≥ 0.80 |
| Relationship accuracy | per type (directed for non-symmetric types) P/R/F1 against manifest edges | reported; P3 thresholds per type set at calibration |
| **Tier confusion matrix** | 6 × 6 (expected rows: SAFE, NO_LOSS, YOUR_CALL, REVIEW, PROTECTED, KEEP; predicted columns, same order), counts and row-normalised; plus `under_cautious` = files with predicted ∈ {SAFE,NO_LOSS,YOUR_CALL} ∧ rank(pred) < rank(expected) | P5: under_cautious on must_never_delete = 0; diagonal ≥ 0.80 on SAFE/NO_LOSS rows |
| **Storage recovery accuracy** | `pred = Σ expectedSavingBytes of SAFE+NO_LOSS delete recs`, `true = Σ size of files with expected_tier ∈ {SAFE, NO_LOSS}`; relative error `(pred − true)/true`; `correct_bytes` = bytes in both; `dangerous_bytes` = predicted bytes on must_never_delete files; plan consistency: `plan.achievableBytes == Σ plan item bytes` (no double counting) | P5: \|rel. error\| ≤ 0.05, dangerous_bytes = 0 |
| **Per-scenario pass/fail** | for each scenario in `scenarios.json`: FAIL if any member file is dangerous (DDRR condition) or under-cautious; PASS if every member's predicted == expected; CAUTIOUS otherwise (no danger, some over-caution). Execution scenarios (12–15) read `execution` section; `N/A` until Phase 9 | P5: scenarios 1–11 PASS or CAUTIOUS, ≥ 8 PASS; P9: 12–15 PASS |
| Discovery coverage (P1) | fraction of manifest files found with equal size; files found that are not in manifest | P1: 100% found, sizes equal |
| Extraction (P2) | type accuracy vs manifest category→type map; mismatch detection on `misleading filename` scenario files; `sha256` agreement (with `--hash all`): 100%; HEIC/undecodable: fraction with image pixel features unknown (must be 100%) and never `extraction.status == COMPLETE` with invented values | P2: type ≥ 0.99, sha 1.000, unknown-handling 1.000 |
| Operational | scan time, files/s, bytes read, peak heap (from `timings`) and their change vs `--baseline` | reported; > 25% slowdown is a warning |

`--gate phaseN` applies the gates of phases ≤ N that have data and exits 1 on any failure; metrics are
written to `--out` for the next run's `--baseline`.

---

## 11. Phase table

| Phase | Packages | Deliverables | Measurable exit criteria |
|---|---|---|---|
| 1 Storage discovery & capabilities | `v2`, `v2.model` (P1 subset), `v2.util`, `v2.device` (StorageAccess, Clock, ResourceMonitor, BlobStore, ScanControl, V2Device), `v2.store`, `v2.discovery`, `v2.pipeline` (DISCOVER only); jvmtest device impls + tests; `V2Cli scan` (discovery + coverage); `evaluate.py` (coverage metrics); `tools/v2test.sh`; CI step | see PHASE_1.md | all P1 tests pass; `ForbiddenApiTest` passes; dataset: 100% manifest files found with equal sizes; ids identical across two scans; resume-after-halt test gives a store equal to an uninterrupted scan; incremental scan after `mutations.json` reports exactly the added/changed/removed set |
| 2 Metadata & incremental scanner | `v2.features`, `v2.device` (MediaDecoder, InstalledApps), pipeline T1–T3 | see PHASE_2.md | type accuracy ≥ 0.99; SHA-256 (with `--hash all`) equals manifest for 100%; every HEIC in the Drive corpus: dims known (ispe), pixel features unknown, status not `UNREADABLE`; second incremental run re-extracts 0 files; encrypted PDFs `encrypted=TRUE`; archive bomb/traversal fixtures flagged; ≥ 300 files/s tier 1 on JVM dataset |
| 3 Exact duplicates & relationship graph | `v2.graph`, `v2.content` (interfaces + deterministic wrappers), T6 SSIM; `tools/v2eval/calibrate.py` | relationships + groups in V2Cli JSON | exact dup P = 1.000, R ≥ 0.99; near-dup P ≥ 0.95, R ≥ 0.80 at calibrated thresholds; backup verification: 0 false VERIFIED on corrupted-backup scenario |
| 4 Utility, recoverability, expected loss | `v2.risk` | `assessment` in JSON | DDRR = 0 (with a provisional tiering of assessments); every must_never_delete file has `loss ≥ 0.5` or `protection != NONE` or `recoverability ∈ {VERIFIED_BACKUP}`; unknown-input property tests pass |
| 5 Planner | `v2.plan` | recommendations, plan, reason sentences | gates in §10 (DDRR 0 in both modes, precision, recall, recovery error, scenarios 1–11); 100% recommendations have ≥ 1 catalogued reason with all params filled |
| A App integration | `app.v2`, small edits in `SetupScreen`, `AnalysisRunner`, `MainActivity` (restore path), `Session.replan` | Classic / v2 switch | CI build + audit pass; `V2AdapterTest` (JVM) proves the 5 tiers map to distinguishable Options and REVIEW/PROTECTED are never planned; classic path byte-identical (classic jvmtests unchanged) |
| 6 OCR, document intelligence, adaptive video | `v2.content` (TextReader, document parser, video sampler), T4, T6 | categories for docs/screenshots | measured gain on dataset scenarios 2, 8, 11 vs Phase 5 baseline with no regression in DDRR/precision |
| 7 LiteRT & embeddings (optional) | `v2.content.models` | model manager; provider impls | ships only if a model improves a §10 metric by a pre-registered margin on the dataset *and* the private corpus; otherwise documented as not adopted |
| 8 Personal learning | `v2.learn` | feedback capture, preference model, explanations | property test: learning never changes `HardRules` output or moves a file into SAFE/NO_LOSS; labels kept distinct; reset works |
| 9 Execution | `v2.exec`, jvmtest `TempDirFileOps` | journal, quarantine/restore, compression verify, `V2Cli exec-sim` | scenarios 12–15 PASS; kill at every journal phase recovers; undo success 100% in tests; no UI wiring without owner sign-off |
| 10 Device optimisation & release | `app.v2` SQLite store, Android device impls, perf | regression dataset in CI | Android store passes `StoreContractTest` (instrumented or via on-device lab); measured scan time/memory on ≥ 2 devices; spec §18 checklist |

---

## 12. The 15 required scenarios (spec 16.4)

| # | Scenario | Phase(s) | How it is tested |
|---|---|---|---|
| 1 | Blurry but important family photo | 2 (sharpness, faces later), 4, 5 | dataset scenario: predicted tier ≥ YOUR_CALL rank, never SAFE/NO_LOSS; P4 unit test: low sharpness never lowers L; P6 faces raise L |
| 2 | Old but important financial document | 2 (PDF/doc basics), 4, 5, 6 | dataset: PROTECTED or KEEP; P4 test: age alone never raises deletion propensity for documents |
| 3 | Exact duplicate with original preserved | 2 (hashes), 3, 5 | dataset: copy NO_LOSS DELETE with keeper = original; original PROTECTED (`P_KEEPER`) |
| 4 | Near duplicate with meaningful edits | 3, 5 | dataset: `EDITED_FROM` edge; neither file SAFE/NO_LOSS (H2) |
| 5 | WhatsApp copy of a unique photo | 3, 4, 5 | dataset: the only copy is never deleted; if an original exists the copy is at most YOUR_CALL (`Y_CHAT_COPY`) |
| 6 | APK of an installed app | 2 (APK parse, InstalledApps), 5 | dataset with `installed_apps.json`: same version + same cert → NO_LOSS; cert unknown → YOUR_CALL |
| 7 | Old installer that cannot be redownloaded | 2, 5 | dataset: not installed / older version → never SAFE/NO_LOSS |
| 8 | Long video with brief meaningful activity | 2 (container), 6 (adaptive sampling) | dataset: never SAFE/NO_LOSS before P6 (features unknown); P6 sampling finds the activity window (unit test with synthetic frames) |
| 9 | Encrypted PDF | 2 (`/Encrypt`), 4, 5 | P2 unit test on fixture; dataset: REVIEW or PROTECTED, `R_ENCRYPTED` |
| 10 | Backup with missing/corrupted content | 2 (archive listing, integrity), 3 (backup verification), 4 | dataset: originals keep `recoverability != VERIFIED_BACKUP`; no NO_LOSS on originals justified by that backup |
| 11 | Archive containing important documents | 2 (listing), 3 (`CONTAINED_IN`), 4, 5 | dataset: archive PROTECTED/KEEP when its entries exist nowhere else |
| 12 | File changes between scan and execution | 1 (fingerprints), 5 (rec.fp), 9 (V2/V3) | P9 `ExecTest`: mutate after planning → `H_STALE`, file untouched; V2Cli `exec-sim` with `mutations.json` |
| 13 | User revokes permission during execution | 1 (grants diff), 9 (V5) | P1: grant revoked between scans → `UNVERIFIABLE`, not REMOVED; P9: `FaultyStorageAccess` revokes mid-plan → remaining items `H_PERMISSION_LOST`, journal consistent |
| 14 | Device runs low on storage during compression | 9 (V6, verification) | P9: `TempDirFileOps` with a free-space limit → `X_LOW_SPACE`, original untouched, partial output removed by the tools-side FileOps |
| 15 | App process killed during quarantine | 9 (journal, RecoveryOnStart) | P9: simulated kill after each `OpPhase`; restart recovers to COMMITTED or ROLLED_BACK; file exists exactly once |

---

## 13. Phase A — app integration (after Phase 5)

1. **Switch.** `SetupScreen` gets a two-option control "Algorithm: Classic / v2" stored as
   `SharedPreferences("analysis").getString("algorithm", "classic")`; helpers
   `AnalysisRunner.algorithm(Context)` / `setAlgorithm(Context, String)` next to `sortPictures`.
2. **Branch.** `AnalysisRunner.run`: `if ("v2".equals(algorithm(app))) return V2Runner.run(app, targetGb, pr);`
   before any classic code. `MainActivity` restore path (currently `new UnitBuilder(s.result, …)` at the
   `ResultFiles.load` site) branches the same way to `V2Runner.restore(app)`, which reloads the v2 store
   from `AndroidBlobStore` and re-adapts (no rescan).
3. **Adapter** `com.storageplanner.app.v2.V2Adapter` (android-free source file, compiled into jvmtest):
   `static Adapted adapt(V2Store store, ActionPlan plan, long now)` → `Adapted { Scanner.Result result; UnitBuilder ub;
   Set<String> notPlannable; }`. `result` holds one `core.FileItem` per present v2 file (rel, abs, size,
   mtime, area, type, sha256, phash, sharp, width, height); `ub = new UnitBuilder(emptyResult, new Estimates(), sdk)`
   (an empty input yields no units), then `ub.units.addAll(v2Units)`; `ub.det.duplicateOf` filled from exact groups
   so the existing "copies of" links work.
4. **Tier mapping** (each v2 recommendation → one `Option` on `Unit.forFile`):

   | v2 tier / action | `Option.action` | `Option.risk` | `Option.technique` prefix | Planned automatically? |
   |---|---|---|---|---|
   | SAFE DELETE | `delete` | R0 | `"Safe · "` + group label | yes |
   | NO_LOSS DELETE / lossless COMPRESS | `delete` / `v2_compress` | R1 | `"No loss · "` | yes |
   | YOUR_CALL DELETE / lossy COMPRESS | `delete` / `v2_compress` | R4 / R3 | `"Your call · "` | only when the user accepts it (Session MODE_SAFE never includes R3/R4; MODE_TARGET may — same as classic R4 behaviour, which already requires the user's confirmation screen) |
   | REVIEW | `v2_review` | R4 | `"Review · "` | never (in `notPlannable`) |
   | PROTECTED | `v2_protected` | R4 | `"Protected · "` | never (in `notPlannable`; accept ignored) |

   `Option.why` = the reason sentences joined by " "; `exact = true` for measured sizes. Session change:
   `Session.replan()` removes units in `notPlannable` from the list given to `Review.planWithDecisions` and
   ignores `accepted` entries for them. The five tiers stay distinguishable by `technique` prefix and action;
   badges use `risk` as today.
5. **Persistence.** v2 state lives in `getFilesDir()/v2/` (BlobStore); classic `ResultFiles` untouched.
6. **CI.** Workflow compiles and runs `tools/v2test.sh` (JVM tests) before the APK build; the audit must
   pass unchanged (no new permissions, no deletes, no network).

---

## 14. Privacy (spec 15) in v2

OCR text, document text and embeddings are stored only in the app-private store; `V2Cli` writes them only
with `--include-text` (default off: JSON carries lengths/flags, not text). No v2 class logs file content;
`lastError`/`detail` carry exception class + message, truncated to 200 chars. Archive extraction never
writes entries to disk (listing and bounded in-memory inflate only).

---

## 15. Design choices (with rejected alternatives)

| # | Choice | Rejected alternative | Why |
|---|---|---|---|
| C1 | `PosixStorageAccess` (pure `java.nio.file`) lives in `v2.discovery`, shared by JVM and Android Mode B/D; fault injection via jvmtest decorators | JVM-only implementation in jvmtest plus a second Android copy | Same walker semantics on both platforms; java.nio.file exists from API 26; a duplicate implementation would drift. It contains no Android API, so A1 still holds. |
| C2 | File id identity token = inode only where the volume's inode numbers are stable, frozen per volume (`identityScheme`) | Path only; path + size + mtime | Path-only lets a file replaced at the same path inherit approvals/feedback of the old one. Size/mtime would change the id on every edit, losing feedback and history. Inodes on vfat/exFAT SD cards are synthesised per mount, hence the per-volume switch. |
| C3 | 128-bit (32 hex) truncation of the SHA-256 file id and edge id | Full 64 hex | Half the memory/JSON size; collision probability for 10^7 files ≈ 10^-25. Content hashes (`sha256`) stay full length. |
| C4 | Store = in-memory tables + JSON-lines snapshot + WAL; checkpoints written in the same WAL frame as data | Binary snapshot like `core.ResultStore`; full snapshot per checkpoint | JSON lines are inspectable and share the codec with V2Cli; WAL makes a checkpoint cost O(batch) instead of O(store). SQLite later reuses the codecs. |
| C5 | Added a 16th table `directories` | Derive directory state from files | "Removed" vs "unverifiable" needs per-directory listing status of this scan; without it a permission loss would look like mass deletion. |
| C6 | Sequential stages (discover → T1 → T2 → T3), each resumable from store state | Fully streaming pipeline (extract while walking) | Tier 2 needs global size groups; sequential stages make resume trivially correct. Discovery is a small part of the cost. |
| C7 | Incremental scan = full listing + stat of every entry, reuse of extraction by fingerprint | Skip listing of directories whose mtime is unchanged | Directory mtime does not change when a file inside is rewritten in place; skipping would miss changes (scenario 12 risk). Listing is cheap relative to extraction. |
| C8 | Losing permission, unreadable parents, unmounted volumes → `UNVERIFIABLE`/`VOLUME_ABSENT`, never `REMOVED` | Treat unseen files as removed | Unknown ≠ gone (A9); otherwise feedback and backups of those files would be dropped. |
| C9 | Own MP4/PDF/ZIP/APK probes over `ReadableFile` | Calling `tools.Mp4.read(File)`, `PackageManager` | `tools.Mp4` takes a `java.io.File` (not usable for SAF/MediaStore) and does not expose duration, dimensions or codec. It is used as a test oracle for the fields it has (creation times, handlers). |
| C10 | Phase A builds `UnitBuilder` from an empty `Scanner.Result` and appends v2 units | Widening/adding a `UnitBuilder` constructor in core | Needs no core change at all; `units`/`det` are already public mutable fields. |
| C11 | Evaluator "predicted tier" = protection first, then most aggressive recommendation | Primary recommendation only | Scores the worst case a user could be shown; that is what DDRR must bound. |
