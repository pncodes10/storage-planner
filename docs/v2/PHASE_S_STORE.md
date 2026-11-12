# Phase S: disk-backed v2 store (design)

Status: reviewed (docs/v2/REVIEW_S.md); BLOCKER and MAJOR fixes applied in place, tagged `REVIEW_S F-…`, and they override
any older sentence they contradict. No production code has been written for it yet. Owner decision A31 asked for this ("SQLite store,
next engineering priority"). This document proposes a pure-Java disk-backed store behind the same `V2Store` interface, and
section 8 explains why it deviates from spec section 13 for now.

The goal is that v2 runs on a phone with **150,000 files on a 256 MB heap**. Today it stops at about 15,000. Section 2 says
what can honestly be reached.

---

## 0. Summary

* Heap goes to three places, in this order (measured, section 1):
  1. the in-memory store, about **2.5 KB per file** (records about 1.6 KB, MemoryStore's TreeMap/TreeSet indexes and
     their key strings about 0.9 KB);
  2. the **verdict list** inside ASSESS, about **2.0–2.6 KB per file** (boxed `Double`s, `EnumMap`s, `TreeMap`s);
  3. **whole-library copies** made by the stages: GRAPH `Node`s (1.1 KB per file), `GraphView` (0.5 KB), the PLAN files map
     (0.3 KB) and `ExtractionLoop.present` (0.3 KB, transient in T1–T3).
* A disk store alone fixes item 1 only. Peak heap would still be about 2.5 KB per file in ASSESS, so the capacity would rise
  only to about 40,000. **The stage working sets must become streaming too.** That is half of this phase (section 4).
* Design: one segmented append-only log for all tables (CRC-framed JSON records, the same `Codecs` JSON as the snapshot). An
  in-memory index per table maps key to (segment, offset) at about 50 B per record. Records are decoded on demand through an
  LRU cache. A manifest is replaced atomically at checkpoints and defines the durable state. Compaction writes into the
  alternate file set and then truncates the old one, so there is no delete or rename.
* Target: in-memory store index **≤ 260 B per file** (≤ 190 with packed hex keys). Pipeline working sets **≤ 300 B per file**.
  The classic result model built by the adapter stays at **0.5 KB per file** (out of scope). The resulting capacity is
  **about 150,000 files on a 256 MB heap** and **about 300,000 on 512 MB**. The binding phase is the end of PLAN plus the adapter, and 150k needs the S6 option of closing the store before the adapter's result grows; without it the limit is about 130k.
* The lead's direction is right: a pure-Java store is testable here, and SQLite is not. Two amendments: (a) the store must be
  paired with the streaming caller changes, or it buys almost nothing; (b) one shared log instead of one record file per
  table, because a commit spans several tables and a single log makes it atomic with one commit marker.

---

## 1. Measurements

### 1.1 Method

* A scratch harness with the same stages as `V2Cli` (DISCOVER, T1, T2, T3, GRAPH, then the `V2Runner.analyse` path: ASSESS,
  `ProvisionalRecommendations`, `Plans.make`) ran on a volatile `MemoryStore`. At each checkpoint it did 4× `System.gc()`,
  measured used heap and took a class histogram through the `DiagnosticCommand` MBean (`gcClassHistogram`, the same data as
  `jcmd GC.class_histogram`). After the run it emptied the tables one at a time (remove all keys, commit, GC) to get the
  exact retained size of each table, indexes included.
* JDK 21, 64-bit, compressed oops. ART object headers are 8 B instead of 12 B; the 25 % margin of `V2Capacity` covers the
  difference, as in REVIEW_A.
* Inputs:
  * `/home/user/sp-dataset`: 2,414 files, real photos.
  * Synthetic trees from a scratch generator (`gen.py`, deterministic): 30 % 96×72 PNGs, 20 % of them in near-duplicate
    bursts with timestamped camera names; the rest small docs and bins with colliding sizes and 5 % exact duplicates.
    Sizes 10,000 and 100,000 files. Generation takes about 40 s for 100k files (430 MB on disk) and the full profile run
    about 100 s.
* "Per file" means (used − base) / file records. It includes about 2 MB of fixed residue (loaded classes' statics,
  ImageIO), which adds about 0.8 KB to the dataset figures and less than 0.07 KB to the 100k figures.
* Scratch artefacts: `/tmp/claude-0/-home-user-storage-planner/69e294b1-7ff5-57b8-9df7-ed244c7e43c3/scratchpad/v2out/`
  (`prof/…/Prof.java`, `prof/…/Bench.java`, `gen.py`, `hdiff.py`, `hcat.py`, histograms in `ds/`, `s10k/`, `s100k/`).
  Slice S0 brings the generator and the harness into the repo.

### 1.2 Heap per file at each checkpoint (bytes, after full GC)

| Checkpoint | dataset 2,414 | synth 10k | synth 100k |
|---|---:|---:|---:|
| after DISCOVER (store only) | 1,919 | 1,434 | 1,296 |
| after T1 | 2,642 | 1,917 | 1,775 |
| after T2 | 2,745 | 2,426 | 2,319 |
| after T3 | 3,013 | 2,503 | 2,370 |
| after GRAPH, `GraphStage.last` still referenced | 25,342 ¹ | 4,503 | 3,655 |
| store after GRAPH (stages dropped) | 3,404 | 2,691 | 2,541 |
| + `ContextBuilder`/`GraphView` (start of ASSESS) | 3,976 | 3,183 | 3,036 |
| + all verdicts (**ASSESS peak**) | **6,577** | **5,748** | **5,082** |
| store + files map + recommendations (hash-on-demand) | 3,856 | 3,222 | 3,072 |
| + `Plans.make` result | 3,923 | 3,274 | 3,121 |
| store only, end | 3,426 | 2,696 | 2,543 |

¹ Includes the picture-signature cache (49.9 MB of `int[]` for 256 real photos). It is 17.5 MB for the small synthetic
PNGs. Not captured above: transient peaks *inside* a stage, such as `ExtractionLoop.present` (a list of copies of every
present FileRecord, about 290 B per file, held during T1–T3), T2's `TreeMap<size, List<FileRecord>>` and the candidate
generator's boxed indexes. So the real T1–T3 peak is about 0.3 KB per file above the "after" rows.

### 1.3 (a) The store, by table (exact retained size, indexes included)

| Table | dataset B/record | dataset B/file | 100k B/record | 100k B/file |
|---|---:|---:|---:|---:|
| files | 1,315 | 1,315 | 1,326 | 1,326 |
| file_features | 865 | 865 | 585 | 585 |
| file_hashes | 755 (295 rec) | 92 | 398 (99,975 rec) | 398 |
| file_relationships | 750 | 146 | 647 | 89 |
| duplicate_groups | 370 | 45 | 563 | 31 |
| pair_checks | 290 | 125 | 379 | 45 |
| directories | 522 | 21 | 385 | 15 |
| backup_records, volumes, sessions, other | – | 1 | – | 0 |
| **sum** | | **2,610** | | **2,489** |

The `files` table's 1.3 KB per record splits into about 600 B for the record (FileRecord object 256 B plus its strings) and
about 700 B for MemoryStore's indexes. There are five TreeMap/TreeSet entries per file (`map`, `ordered`, `byPathKey`,
`bySize`, `children`), a TreeSet object per path, and two composite key strings per file (`vol\0path\0fileId` and
`vol\0path`).

### 1.4 (c) The store by kind of overhead (B per file)

| | strings (`String` + `byte[]`) | collections (TreeMap/TreeSet/HashMap entries, ArrayList) | model objects | primitive arrays | boxing |
|---|---:|---:|---:|---:|---:|
| dataset | 1,135 | 920 | 723 | 179 | 23 |
| synth 100k | 979 | 794 | 602 | 120 | 1 |

Strings and collection overhead are about 70 % of the store. The record fields themselves are a minority.

### 1.5 (b) Per-stage working sets (B per file, synth 100k; dataset in brackets)

| Working set | Size | Made of | Scales with |
|---|---:|---|---|
| GRAPH `Node`s + `Perceptual` (retained after the stage) | 1,113 (≈ 1,200 + 49.9 MB cache) | copies of FileRecord+FeatureRecord+HashRecord per present file (519), collections (243), signature cache `int[]` (180, bounded at 256 entries), `Node` (48) | present files; the cache is fixed |
| ASSESS verdict list | 2,046 (2,601) | collections 1,358 (TreeMap entries, `EnumMap`, `ArrayList`), boxed `Double` 528, `Assessment`/`Interval`/`Verdict` 163 (440) | present files |
| `GraphView` | 494 (572) | FileRecord copies 256, HashMaps 139, path/physicalKey keys | all file records |
| PLAN files map `fm` | 288 | FileRecord copies + HashMap nodes | all file records |
| recommendations | 240 (0.37 per file at 100k) | Recommendation + lists/maps | removal candidates |
| `Plans.make` structures | 49 (67) | units, items | recommendations |
| candidate pairs, pair checks (in GRAPH) | < 50 | `TreeMap<Long,Integer>`, PairCheck maps | candidate pairs (0.14–0.47 per image) |
| on-screen result (adapter, REVIEW_A) | ≈ 500 | classic `FileItem` per present file | present files |

### 1.6 Scaling and other findings

* **Everything per file is linear**. From 10k to 100k the per-file figures stay flat or fall slightly (store 2.70 → 2.54 KB;
  verdicts 2.6 → 2.0 KB). Nothing superlinear in memory was observed. Time is roughly linear: ASSESS+PLAN takes 5.5 s at
  10k and 42.9 s at 100k; GRAPH takes 1.3 s and 9.1 s.
* **Decode budget (not a store problem; must be decided).** On the 100k tree (30,000 images) GRAPH hit
  `GraphConfig.MAX_DECODES_PER_RUN = 20,000` with 2,362 pairs still pending and returned `BUDGET_EXHAUSTED`. `V2Runner` then
  reports "Analysis stopped early (BUDGET_EXHAUSTED)". A real phone with 30,000 photos is likely to do the same. Pending
  checks are ordered by size, not by node, so the 256-entry signature cache gets few hits (20,001 decodes for 14,165 pairs).
  This is outside this phase. The lead should decide between ordering pending checks by node, raising the cap, or letting
  GRAPH finish with UNKNOWN for pending pairs. The scale tests here use a 20 % image share so that they stay under the cap.
  **REVIEW_S F-13: decided in slice S7 (section 6).**
* Disk size of one record (current `Codecs` JSON): FileRecord 1,057–1,085 B (793–812 B without the `null` fields, REVIEW_A
  m-10); FeatureRecord 634–1,041 B (493–883 B).
* Random read plus decode of a FileRecord from a 100 MB file in the page cache with `RandomAccessFile` takes **8–12 µs** on
  this JVM. JSON decode dominates at 6–10 µs. Assume about 10× that on a mid-range phone, about 100 µs. At about 10 random
  record reads per file per run, that adds about 2.5 min for 150,000 files on a phone. This is acceptable next to T3 (55 s
  for 2,414 real photos here), but it is the reason for the LRU cache and for compacting in key order (section 3.9).

---

## 2. What must change, and the target

| Item | Today (B/file) | Change | Target (B/file) |
|---|---:|---|---:|
| Store records | ≈ 1,600 | move to disk (all 17 tables), decode on demand | 0 + fixed LRU |
| Store indexes | ≈ 900 | compact per-table key index + path index (section 3.5) | ≤ 260 (≤ 190 with packed hex keys) |
| `ExtractionLoop.present` (T1–T3) | ≈ 290 | compact id list (`FileIdList`), load each record per job | ≤ 40 |
| T2 size grouping | ≈ 300 | sorted primitive (size, ordinal) array; load records one group at a time | ≤ 16 |
| GRAPH nodes | ≈ 1,100 | "lite" nodes (primitives + ordinal); records through a bounded cache; primitive candidate arrays | ≤ 120 (+ edges/groups ≤ 150) |
| `GraphView` | ≈ 500 | backed by the store (relationships index, LRU) + primitive size counts + alias sets only | ≤ 30 |
| Verdict list | ≈ 2,000–2,600 | `Assessor.run` streams verdicts to a sink; recommendations made per verdict | 0 |
| PLAN `fm` | ≈ 290 | read-only `Map` view over the store (`StoreFileMap`) | 0 |
| recommendations + plan | ≈ 290 | unchanged (removal candidates only) | ≈ 290 |
| Adapter result | ≈ 500 | unchanged (shared with Classic; out of scope) | ≈ 500 |

**What stays in memory:** the small tables (`storage_volumes`, `scan_sessions`, `user_preferences`, `model_metadata`) as
decoded records, through a "pinned" flag on the same disk table, so every write is still logged. Also: the
signature cache (fixed, 256 entries), recommendations and the plan during PLAN, and the adapter's result.

**Capacity.** Per-file costs below are the targets, multiplied by 1.25 for ART as in `V2Capacity` today. Fixed costs: 20 MB
in use before the run, the store's fixed 16 MB (LRU 8 MB, write buffers, delta overlays), and the GRAPH reserve of 64 MB
(signature cache and decode buffers). Budget = 0.8 × heap.

| Phase | Per file (target × 1.25) | 256 MB heap | 512 MB heap |
|---|---:|---:|---:|
| GRAPH: store index 260 + graph 270 | 660 B | (204.8 − 20 − 64 − 16) / 660 B ≈ **159k** | ≈ 469k |
| ASSESS: 260 + view 30 + recommendations 290 | 725 B | ≈ 233k | ≈ 515k |
| End of PLAN + adapter: 260 + 290 + 500 | 1,313 B | (204.8 − 20 − 16) / 1,313 B ≈ **128k** | ≈ 284k |
| same, with packed hex keys (190) | 1,225 B | ≈ **138k** | ≈ 305k |
| same, store index released before the adapter's result grows (S6 option) | 988 B | ≈ **171k** | ≈ 378k |

**Honest target: at least 150,000 files on a 256 MB heap,** reached only with the S6 option. That option builds the
adapter's file list from a compact projection streamed once from the store, then closes the store before
`V2Adapter.adapt` allocates the `FileItem`s. Without it the limit is about 128–138k. The classic result model at 0.5 KB per
file is then the largest single cost; slimming it is the next lever (about 250k) but belongs to Classic/the adapter, not to
this phase. On 512 MB the limit is about 300k in every variant.

**REVIEW_S F-08 (safety factor and what the tests must prove).** The 1.25 is **not** an ART header correction: ART object
headers (8 B) and ART's compressed strings make objects the same size or smaller than on HotSpot. The real Android unknowns
are GC headroom (concurrent-copying evacuation, heap growth limit), large-object-space fragmentation for the multi-MB index
arrays, and transients not measured here. Treat the uncertainty as ±25 % until a device measures it. So 1.25 stays, as an
explicit **safety factor** in `V2Capacity`, and it must not be "spent" by the tests: a test that proves N files at 256 MB runs
at `-Xmx` = (0.8 × 256 − 20) / 1.25 ≈ **148 MB**, not 200 MB. Recomputed from this document's own numbers (store index 270 worst
case, recommendations + plan 290, adapter 500; budget 0.8 × 256 MiB − 20 MiB − 16 MiB = 176.9 MB): **≈ 133,000 files without
the S6 option**, ≈ 175,000 with it (as specified by F-09 below). 150,000 is honest only with the S6 option.

**REVIEW_S F-09 (the S6 option, specified).** "A compact projection" was not costed and conflicts with `StoreFileMap`
(the store would be closed). The option is: after `Plans.make`, the store produces a `long[]` of the locators of PRESENT
`files` records in ascending fileId (8 B/file), then releases every in-memory index (`DiskStore.releaseIndexes()`; the store
stays readable only through that array); `V2Adapter.adapt` takes `Iterable<FileRecord>` that decodes each locator in turn
(CRC and key checked as in 3.6) and builds `FileItem`s directly, with no intermediate list. Peak = 8 + 290 + 500 B/file
(× 1.25 = 998 B ⇒ ≈ 177k at 256 MB). Everything else `adapt` needs (`recs`, `keepers`, the plan) is already in memory. The
JSON-equality test (5.2) covers this path.

---

## 3. The disk store (`v2.store.DiskStore`)

### 3.1 Files (all in the BlobStore directory, `getFilesDir()/v2` on Android)

| Name | Written by | Content |
|---|---|---|
| `ds.manifest` | `BlobStore.replace` (atomic) | the durable state: generation, file set, segment list with **durable lengths**, index file and what it covers, txn and commit counters |
| `ds-<set>-<nnnn>.seg` | `BlobStore.append` (append-only) | log segments, each ≤ 32 MiB; `<set>` ∈ {`a`, `b`}; `<nnnn>` = 0001… |
| `ds-<set>.idx` | `BlobStore.replace` | snapshot of the in-memory indexes (section 3.4) |
| `store.snap` | (old MemoryStore) | truncated to 0 bytes the first time a DiskStore is created (section 5.1) |

Nothing is ever deleted or renamed. Space is returned with `BlobStore.truncate` (to 0 bytes). The file names come from two
alternating sets, so at most about 2 × (segments) + 4 files exist.

### 3.2 Frame format (segments)

Every segment starts with a header frame. All integers are big-endian; `varint` is unsigned LEB128.

```
frame   := magic:u8=0xD5  type:u8  table:u8  flags:u8  txn:u64  keyLen:varint  key:bytes(UTF-8)  valLen:u32  val:bytes  crc:u32
  type 1 PUT     val = UTF-8 JSON of Codecs.<table>.write(record)   (byte-identical to the snapshot's "v")
  type 2 DEL     valLen = 0
  type 3 COMMIT  table = 0, key = "", val = commitSeq:u64
  type 4 HEADER  table = 0, key = "sp-v2-ds", val = JSON {"version":1,"schema":V2.STORE_SCHEMA,"gen":G,"set":"a","seq":n}
  crc    = CRC32 of every preceding byte of the frame (magic … val)
  table  = 1..17, the ARCHITECTURE 4.1 order (files = 1 … directories = 17); 0 = control frame
  flags  = 0 (reserved; a reader rejects unknown bits)
```

A record is valid only if its magic, lengths and CRC check out **and** its txn has a COMMIT frame at or before the replay
end. Unknown higher `version` → `StoreException` → the caller starts a fresh store (the existing `D_STORE_RESET` path).
Lower `schema` → `Codecs.migrate` on decode, as the snapshot does today.

### 3.3 Manifest

One JSON object, followed by `"crc32"` over the canonical text before it (the same pattern as the snapshot trailer):

```
{"format":"sp-v2-ds","version":1,"schema":S,"gen":G,"set":"a","txnNext":T,"commitSeq":C,
 "segments":[{"name":"ds-a-0001.seg","durable":33554411},{"name":"ds-a-0002.seg","durable":120394}],
 "index":{"name":"ds-a.idx","txn":T0,"covers":[33554411,0]},
 "live":L,"garbage":Gb,"damaged":false,"crc32":"…"}
```

`durable` is the length of the segment at the last checkpoint. **Bytes beyond `durable` do not exist**, even if they are on
disk after a crash. `index.covers` gives, for each segment, how far the index file already reflects it.

**REVIEW_S F-01 (index file bound to the manifest).** The index file is replaced in place under one name, so after a kill
between its `replace` and the manifest's `replace`, the old manifest would name a newer index (same gen) whose `covers` lie
beyond the old `durable` — the store would reopen past its checkpoint, and a later rebuild-by-scan would disagree with it.
Rules: (1) the index file carries `idxSeq` (incremented per index write) and the manifest's `"index"` entry records
`{"name","idxSeq","crc32"}` of the index file it names; (2) on open the index is used only if magic, CRC, `gen`, `idxSeq` and
`crc32` all equal the manifest's entry **and** `covers[i] ≤ durable[i]` for every segment; otherwise it is ignored and the
indexes are rebuilt by scanning; (3) the index file is written only after the segments it covers have been synced (3.9 step 2),
and always immediately before a manifest that names it.

**REVIEW_S F-02 (segment high-water mark).** The manifest also records `"hw":{"a":n,"b":m}`, the highest segment number ever
created in each set. A segment beyond `hw` is created only after a checkpoint whose manifest has raised `hw` (one extra
manifest write per rotation or open). This is what lets compaction and open enumerate every file of a set without a
directory listing (BlobStore has none).

### 3.4 Index file

Binary, written with `replace`: magic `SPDSIDX1`, version, gen, txn covered, `covers[]`, then for each table:
`tableId, count, entries…`, then a CRC32 trailer. Each entry is
`keyLen:varint key loc:u64 hash:u64 [secondary keys]`. The secondary keys are written only for tables with secondary
indexes:

* files: `volumeId\0path\0fileId`, size, sha256 or nothing;
* relationships: src, dst;
* recommendations: fileId;
* operations: opId.

The index file is written at `flush()` when the tail it does not cover is over 8 MiB, at compaction and at `close()`.
At 150k files it is about 15–20 MB, so it is written a few times per run, not every checkpoint (lesson of REVIEW_A M-2).

### 3.5 In-memory index and its cost

For each table, a `KeyIndex`:

* **Base**: immutable sorted arrays. `byte[] keyArena`, `int[] keyOff`, `int[] slot`. Keys are stored as UTF-8 but
  **compared as UTF-16 code-unit sequences**, i.e. exactly `String.compareTo` (REVIEW_S F-03): UTF-8 byte order is code-point
  order, which differs from `String.compareTo` whenever a supplementary character (emoji in a file name, a surrogate pair in
  UTF-16) meets a character in U+E000–U+FFFF. A wrong order would make binary search miss existing paths (`byPath` → "file
  absent", a second record for one file) and change iteration order. The comparator decodes on the fly with an ASCII fast
  path; there is no runtime "mismatch fallback". The oracle test (S2) includes paths mixing emoji, U+FF01–U+FFEF and
  U+E000 private-use characters.
* **Per slot**, stable for the record's lifetime: `long[] loc` (segment:16 | offset:48) and `long[] hash` (64-bit FNV-1a of
  the encoded JSON, used for no-change detection). (REVIEW_S F-15: the former `long[] pathHash` had no consumer and is cut.)
* **Delta**: `TreeMap<String, Integer>` for keys inserted since the last merge. A removed base key gets `loc = DELETED`.
  Merge (rebuild the base arrays) happens when the delta exceeds max(4,096, n/8), inside a store call. Iterators are not
  affected (section 3.8). REVIEW_S F-14: a merge briefly holds old and new base arrays (for `files` at 150k about +17 MB,
  during DISCOVER), and each delta TreeMap can hold n/8 keys at about 130 B (≈ 2.4 MB per large table). Both belong in
  `STORE_FIXED`; S6 measures them and raises `STORE_FIXED` if they exceed it.
* Cost per record: key bytes + 4 (offset) + 4 (slot) + 16 (loc, hash) ≈ **56 B for a 32-hex key**. Option for slice S6:
  keys that are exactly 32 lower-case hex characters are stored as two `long`s instead of 32 bytes plus an offset
  (≈ 36 B per record).

Secondary indexes:

| Index | Structure | Cost | Serves |
|---|---|---:|---|
| files by (volume, path, fileId) | front-coded sorted blocks of 64 entries (first key in full, then shared-prefix length + suffix) + delta TreeMap; value = slot | measured 45–51 B/file (key) + 4 | `byPath`, `byVolume`, `underPrefix`, `childrenOf` (range scan of `vol\0dir/`, skipping entries with a further `/`) |
| files by size | sorted `long[]` size + `int[]` slot, **built lazily** on the first `bySize` call | 12 B, only if used (no production caller today) | `bySize` |
| files by sha256 | 64-bit hash → slot, lazy | 12 B per hashed file, only if used | `bySha256` |
| relationships by node | hash64(nodeRef) → slot multimap (sorted arrays + delta) | 24 B per edge | `of(nodeRef)` |
| recommendations by file | hash64(fileId) → slot | 12 B per rec | `forFile` |
| operations by op | hash64(opId) → slot | 12 B per entry | `forOp`, `openOps` |
| directories | primary key is already `vol\0path` (ordered); children via a range scan | 0 | `childrenOf`, `underPrefix` |

Results reached through a 64-bit hash are always checked against the decoded record (src/dst, fileId, opId, path). Results
come back in the same order as MemoryStore (ascending record key; `forOp` by seq).

**Store index total (target ≤ 280 B per file worst case, ≤ 260 typical; the capacity in section 2 assumes 260):**

| Part | Cost per file |
|---|---:|
| files: 56 + path 50 (pathHash cut, F-15) | ≈ 106 |
| features | 56 |
| hashes: worst case, every file hashed (the synthetic tree) / dataset, ≈ 10 % hashed | 56 / 6 |
| relationships | ≈ 0.2 × 80 |
| groups + pair checks + recommendations + directories | ≈ 0.9 × 30 |
| **total** | **≈ 270 worst case / ≈ 220 typical** |

### 3.6 Read path

`get(key)`:

1. Pending-writes map (txn) or LRU hit → return `copy()`.
2. Otherwise look up `loc`, read the frame with `RandomRead.read(pos, …)` (4 KiB first, the rest if longer), check magic and
   CRC, decode with the table codec (+ migrate), put into the LRU and return a copy.
3. If `loc` points into bytes still in the append buffer, flush the buffer first.

**REVIEW_S F-04 (every read is verified).** Before decoding, a read checks: magic, flags, CRC, frame `type == PUT`,
frame `table` == the table asked, frame `key` bytes == the key asked, and frame end ≤ the readable limit of that segment
(`durable`, or the current written length for the active segment). After decoding, the record's own key (`fileId`,
`edgeId`, …) must equal the key asked. A **CRC/length** failure is damage (3.11 rules). A **table, type or key mismatch on a
frame whose CRC is valid** is a locator bug, not media damage: it throws `StoreCorruption` for every table, derived ones
included, and is never turned into `null`. The same checks apply to the secondary-index paths (hash64 lookups, 3.5) and to
compaction's copy.

LRU: one `LinkedHashMap` (access order) per store, capacity 4,096 decoded records **and** about 8 MiB by encoded length,
whichever is hit first. Pinned tables bypass the LRU and always stay decoded. Segment read handles are kept in an LRU of at
most 16 open handles.

### 3.7 Write path, commit, rollback

* `put(r)`:
  1. Encode with the codec and hash the bytes.
  2. If the key exists and the hash equals the stored hash, read and compare the stored bytes. If they are identical the put
     is a no-op (same as MemoryStore's JSON comparison, so `changedPutsSinceOpen` matches).
  3. Otherwise append a PUT frame (txn = current) to the active segment's 64 KiB buffered stream. Record `(table, key,
     prev slot state)` in the undo list, update `loc`/`hash`/secondaries and the LRU.
  4. Count `garbage += old frame length`.
* `remove(key)` appends a DEL frame, with undo the same as put.
* `commit()` appends `COMMIT(txn, ++commitSeq)`, clears the undo list and sets `txn = txnNext++`. If `checkpointEveryMs` has
  passed since the last checkpoint, it checkpoints (section 3.9). The cadence is the MemoryStore rule: `snapshotEveryMs`
  0 = every commit, −1 = adaptive. Here adaptive is max(5 s, 20 × the last checkpoint's duration), because a checkpoint costs
  an fsync, not a full rewrite.
* `rollback()` restores every undo entry (loc, hash, secondaries, slot freed for inserts), drops their LRU entries and sets
  `txn = txnNext++`. The rolled-back frames are in the log without a COMMIT, so replay ignores them. They count as garbage.
* The undo list is about 60 B per key touched in the open txn. GraphStage.write commits once for all of GRAPH today; it is
  changed to commit every 2,000 puts. Re-running GRAPH after a kill is idempotent, so this is safe.
* Rotation: when the active segment passes 32 MiB, at a commit boundary, open `ds-<set>-<n+1>.seg` and write its HEADER.

### 3.8 Iteration and interface changes

`Table.all()` keeps its contract: copies, ascending key, iterating a **key snapshot**, each value read when the iterator
reaches it, keys removed meanwhile skipped. The snapshot is cheap:

* the base arrays are immutable (a merge makes new ones), so the iterator keeps references to them;
* the delta keys are copied (≤ max(4,096, n/8) Strings);
* the iterator merges the two in order and calls `get(key)` for each.

Peak per open iterator is about 0.6 MB at 150k files, not 100k Strings as in MemoryStore. `byVolume`/`underPrefix` work the
same way over the path index.

REVIEW_S F-16: iterators hold **no file handles** (reads go through the store's handle LRU), so there are no `Closeable`
cursors and nothing to close on exception paths. Writing while iterating — including nested iteration — is defined: the
iterator's key snapshot is taken **when `all()`/`childrenOf`/`underPrefix`/`keys()` is called** (as MemoryStore's list
copy is), values are read when reached, removed keys are skipped, inserted keys are not seen. `DirectoryReconciler` relies on
this (it `put`s files and recurses into `directoryGone` inside `childrenOf` loops); the oracle test interleaves writes with
open iterators.

Signature changes (all in `v2.store`; callers listed):

| Method | Today | New | Callers to adapt |
|---|---|---|---|
| `FileTable.childrenOf` | `List<FileRecord>` | `Iterable<FileRecord>` (a single directory can hold 20,000 WhatsApp images) | `DirectoryReconciler:15` (already iterates); `StoreContractTest` (already uses `paths(Iterable)`) |
| `DirTable.childrenOf` | `List<DirRecord>` | `Iterable<DirRecord>` | `DirectoryReconciler:30`; `StoreContractTest:68` (`.size()` becomes a count helper) |
| `Table.keys()` | – | **new**: `Iterable<String>`, ascending key snapshot, no decode | `GraphStage.diff:79`, `Perceptual:113` (both decode whole tables only to collect keys) |
| `V2Store.stats()` | – | **new**: `StoreStats {records, indexBytesEstimate, liveBytes, garbageBytes, diskBytes}`; MemoryStore returns estimates | V2Capacity calibration, scale harness |

Unchanged:

* `all`, `byVolume`, `underPrefix` (already `Iterable`);
* `bySize`, `bySha256`, `RelationshipTable.of`, `RecommendationTable.forFile`, `JournalTable.forOp`/`openOps` (results are
  small and bounded: an exact group's star is ≤ 64 edges; a file has ≤ 2 recommendations);
* `get`, `put`, `remove`, `size`, `commit`, `rollback`, `flush`, `close`.

### 3.9 Checkpoint, crash safety, resume

* **Checkpoint**:
  1. flush the append buffers;
  2. `BlobStore.sync` every segment written since the last checkpoint;
  3. write the manifest with `replace` (fsync and atomic replace inside the device layer).

  The manifest records each segment's `durable` length. All bytes up to `durable` were synced **before** the manifest that
  names them was committed.
* `flush()` = `commit()` + checkpoint now (+ index file when due). It is called at stage end, pause, cancel and close, as
  today.
* **Open**:
  1. read the manifest (invalid → `StoreException` → caller starts fresh, as today);
  2. load the index file if its CRC and gen match, else rebuild all indexes by scanning every segment up to `durable`;
  3. **replay** `[covers, durable)` of each segment in log order: frames are buffered per txn as (key → loc) and applied when
     their COMMIT frame is seen;
  4. start a **new** segment for further appends. The old tail beyond `durable` is never appended to, which handles torn
     writes without truncate-to-length. It is garbage until compaction.

  **REVIEW_S F-02 (no stale bytes in a reused segment name).** A segment name beyond the manifest's list (step 4, or a
  rotation) may already exist with bytes from a run that crashed after its last checkpoint — including complete frames
  and COMMIT frames whose txn numbers the new run will reuse. Appending after them would put the new HEADER at a non-zero
  offset, and a later rebuild-by-scan would replay those phantom commits. Rules: (a) before its first append, every
  segment is `truncate`d and the truncation synced, so its HEADER is at offset 0; (b) the HEADER carries
  `{"gen","set","seq","epoch"}` where `epoch` = the manifest's `commitSeq` when the segment was started; replay and
  rebuild accept a segment only if its HEADER at offset 0 matches the manifest's gen, set and seq; (c) segment numbers
  beyond the manifest's `hw` are created only after `hw` is raised (3.3).

  **REVIEW_S F-05 (damage inside the durable range fails closed).** If, during replay or rebuild-by-scan, any frame below
  `durable` fails its CRC or length check, or a listed segment is shorter than its `durable` (e.g. a new file whose
  directory entry was lost in a power cut), the open throws `StoreException` → fresh store (`D_STORE_RESET`, subject to
  F-06). Replay never "stops at the first bad frame and continues": that would silently drop every later commit — the
  failure mode A13 recorded for the old log — and could revert a file's presence to an older state.
* **Contract**, the same as ARCHITECTURE 4.1 / 5.2: after a crash the store reopens at the last checkpoint, which is always a
  commit boundary, never between two commits. Stages are idempotent and resumable from store state, so a kill at any point
  resumes to the same result as a clean run. `KillingBlobStore`'s `--halt-after-commits N` counts `replace` commits; manifest
  and index writes are such commits, so the end-to-end kill test in `run_tests.sh` works unchanged with
  `--store disk --snapshot-every-ms 0`.
* `KillingBlobStore` gains two kill points: `killAfterAppendedBytes(n)` (writes exactly n bytes of an append, then kills:
  a torn frame) and `killBeforeTruncate(n)` (inside compaction).

### 3.10 Updates and deletes without File.delete: compaction

* Garbage is tracked exactly: superseded frames, DEL frames and rolled-back frames.
* Trigger, checked at checkpoints: `garbage ≥ max(64 MiB, live)`, i.e. disk ≤ about 2 × live. Compaction is never started
  inside an open txn. REVIEW_S F-11: it runs at **any** checkpoint where the trigger holds (a checkpoint is always outside a
  txn, and iterators hold keys only, 3.13), not only at stage boundaries. Otherwise a stage that rewrites every FileRecord
  starts just under the trigger, adds about one more live's worth of garbage, and the compaction copy makes the peak about
  4 × live instead of 3 × live. The bullet "Run inside `flush()` at a stage boundary" below is superseded by this.
* Compaction also truncates every segment of the target set numbered 1…`hw` (F-02) in step 1, and the index file of the
  target set; step 5 truncates 1…`hw` of the old set.
* **Compaction** (generation G → G+1, from set `a` to set `b`):
  1. `truncate` every `ds-b-*` file left from a crashed earlier compaction;
  2. write a HEADER, then every live record **table by table in key order** (so later key-order scans read sequentially),
     then one COMMIT, into `ds-b-0001…`;
  3. sync and write `ds-b.idx`;
  4. replace `ds.manifest` (gen G+1, set `b`). **This is the commit point**;
  5. `truncate` every `ds-a-*` file.

  A crash before step 4 leaves gen G intact. The partial `b` files are truncated at the next compaction. A crash after step 4
  leaves stale `a` files, which the next compaction truncates (step 1 for the other set).
* Run inside `flush()` at a stage boundary when triggered, so it never interleaves with a stage's commits. Cost: one
  sequential rewrite of live data, about 2.5 KB per file (375 MB at 150k; REVIEW_A m-10's null-dropping would cut about
  25 %; it stays a separate decision because it changes the snapshot bytes).

### 3.11 Corruption handling

A frame whose magic, length or CRC fails, or a read past a segment's end, is **damaged**. Rules:

| Table class | Damaged record behaves as | Why it is safe |
|---|---|---|
| **Derived per file**: `file_features`, `file_hashes`, `file_embeddings`, `pair_checks` | `get` returns `null` and the key goes into `stats().damaged`. The session gets event `D_STORE_DAMAGED` with a count. | Every stage already treats a missing or stale derived record as "not done", so it re-extracts: T1/T3 select files without a current FeatureRecord, T2 re-hashes a collision without a current HashRecord, GRAPH re-checks a pair without a PairCheck. In ASSESS a missing feature is UNKNOWN (`HardRules.featuresUnknown`), never "unimportant". **Each of these four claims gets a test in S3; a table whose test fails moves to the next row.** |
| **Everything else** (`files`, `directories`, `relationships`, `duplicate_groups`, `backup_records`, `recommendations`, `action_plans`, `action_operations`, sessions, volumes, preferences, models, feedback) | `StoreCorruption` (unchecked) is thrown from the read. The store writes `"damaged":true` into the manifest at once. `Pipeline` turns the exception into a FAILED session (existing path). The next `open` refuses a damaged manifest, so the caller starts a fresh store (`D_STORE_RESET`) and rescans. | A damaged `files` record is never skipped by an iterator or turned into `null`, which would read as "file absent". Absence could make another copy look unique, or make a group look smaller. Failing closed and rescanning is always correct. |

Also:

* A damaged **index file** is rebuilt from segments with no data loss.
* A damaged **manifest** means a fresh store.
* **`action_operations` (Phase 9 journal)** is in the fail-closed class. A damaged journal entry must never let an
  operation look finished. Phase 9 may later give it its own verification rule.
* `V2Runner` maps `StoreCorruption` / a failed open to: one automatic retry with a fresh store in the same run, then the
  message "v2's saved data was damaged and has been reset; please run again". Nothing on the user's files changes.

**REVIEW_S F-06 (a reset must never discard user decisions).** A reset discards every table, including `feedback`,
`preferences` and `action_operations` (the Phase 9 journal: what was moved or trashed). Today no production code writes those
three tables (checked: only tests touch them), so a reset loses nothing that is not re-derivable. To keep it that way, S3
adds `ResetSafetyTest`: it fails if any class under `v2/`, `v2adapter/` or `app/` other than `v2.store` calls `put` on
`feedback()`, `preferences()` or `operations()`. The phase that first writes one of them must first define how it survives a
reset (salvage of CRC-valid committed frames into the new store, or a separate file), and remove that test only then. Until
then an automatic reset is acceptable.

**REVIEW_S F-07 (derived damage found after its producer ran).** "Missing ⇒ re-extract" holds at selection time. A
features/hashes record damaged after T1–T3 ran in this run is not re-extracted until the next run; this run's safety then
rests on "missing ⇒ unknown" downstream. Checked: GRAPH skips a node without features (no perceptual status), and
`RecoverabilityEstimator` gives `UNKNOWN`, not `NO_VERIFIED_BACKUP`, to an image without a TRUE/FALSE status; ASSESS treats
missing features as unknown. A missing hash can only remove duplicate evidence, never add it. S3 tests this (see S3), and the
session records the damaged count so the coverage report says the result is partial.

### 3.12 Disk space

The store gets a `SpaceProbe` (`long freeBytes()`, −1 = unknown). It is wired from `device.resources().sample().storageFreeBytes`;
on Android that is `StatFs` of the shared root, which is the same `/data` partition as `getFilesDir()`.

* **Preflight** (in `CapacityGuard`, right after DISCOVER, with the heap guard): needed = files × **8 KiB** + 64 MiB +
  reserve (REVIEW_S F-11: worst case is live + garbage up to max(64 MiB, live) + a compaction copy of live = 3 × 2.5 KiB,
  plus the two index files; the former 6 KiB was below 3 × live). At 150k files: ≈ 1.2 GB + 256 MiB reserve. If free is below that, stop before T1 with "Not enough free storage
  for v2's working data (about N MB needed)". The run ends without a result and the session is CANCELLED, as with the heap
  guard.
* **During the run**: after every 8 MiB appended, and before rotation and compaction, check free space. If
  free < reserve (`LOW_SPACE_RESERVE`, 256 MiB on Android, configurable; tests use a fake probe), stop appending and throw
  `StoreException("D_LOW_STORAGE")`. Afterwards the store accepts only a **final 1 MiB** of frames, enough for
  `Pipeline.fail` to write the FAILED session and checkpoint, then refuses everything.
* Compaction is skipped (not failed) when free < live + reserve. If garbage then keeps growing, the in-run check stops the
  run. **The store never writes the device below the reserve.**
* An `IOException` from append or sync (`ENOSPC` included) becomes a `StoreException`. The manifest is not advanced, so the
  durable state stays the previous checkpoint.

### 3.13 Concurrency

The same as today: a single writer (the pipeline thread). Every public method synchronizes on the store, iterators
included (each `next()` takes the lock). `rollback`, merge and compaction happen inside store calls, so no iterator sees a
half-built structure. Iterators keep only keys, never locators, so they stay valid across merges and compactions.
`ARCHITECTURE 5.3` workers (not enabled yet) may read through the lock.

### 3.14 Device layer (BlobStore) additions

```java
/** Positional reads of an appended blob; null if absent. */
RandomRead openRead(String name) throws IOException;
interface RandomRead extends java.io.Closeable {
    int read(long pos, byte[] b, int off, int len) throws IOException;   // -1 at end
    long length() throws IOException;
}
```

* Android (`AndroidV2Device.AndroidBlobStore`): `java.io.RandomAccessFile(f(name), "r")`. The audit allows it: no delete or
  rename. Segments are plain files, not `AtomicFile`s. Only `ds.manifest` and `*.idx` go through `replace`/`AtomicFile`.
* JVM (`DirBlobStore`): `FileChannel.read(ByteBuffer, pos)`.
* `KillingBlobStore`: pass-through, plus the kill points of section 3.9.
* The existing `append`, `sync`, `truncate` and `length` are enough for everything else. `ForbiddenApiTest` needs no change.

---

## 4. Caller changes (streaming working sets)

Each change is verified by **golden equality**: CLI JSON with MemoryStore, before and after the change, must be identical on
the dataset, synth 10k and every scenario fixture. They are independent of DiskStore and can land first.

1. **`ExtractionLoop.present` → `FileIdList`** (`v2.util`, new). An append-only id list: 32-hex ids packed as 2 `long`s,
   other ids as Strings. `run(ctx, stageId, FileIdList jobs, …)` loads each record with `files().get(id)` when it reaches it,
   and its stop path does the same. Callers: `T1MetadataStage`, `T2HashStructStage` (lines 42, 89, 219), `T3ImageStage`.
2. **T2 size groups**: one `long[]` of `(size, ordinal)` pairs sorted by size, then records loaded one collision group at a
   time. The `count` map (line 218) gets the same treatment.
3. **GRAPH**:
   * `Node` keeps `String id` (or an ordinal into a `FileIdList`), `long size, phash, captureTime`, the fingerprint as three
     `long`s, flags (hasPhash, eligible, alias, trashed, thumbLocation, editorFolder), and `exactGroup`/`canonical` only when
     non-null.
   * `f()`, `fr()`, `hr()` become methods that load through a `GraphRecords` cache (bounded, 2,048 nodes).
   * One streaming pass computes everything the passes need. `ExactGrouper` gets only nodes with a current sha.
     `AliasResolver` gets hash64(physicalKey), with collisions confirmed by loading the records. `CompanionLinker` keeps only
     candidate nodes (its own path predicates) in its `byPath`/`basesByPkg` maps. `ArchiveMatcher` keeps its size:crc map
     (bounded by archive entries).
   * `CandidateGenerator`: replace the boxed `HashMap<Integer, List<Integer>>` band index with sorted `int[]` per band and
     `TreeMap<Long,Integer> pairs` with a growable `long[]` key + `int[]` source pair array, sorted at the end. Iteration order
     must stay ascending (i<<32|j), so results are identical.
   * `GraphStage.write` commits every 2,000 puts and uses `keys()` for the diff.
   * Edges and groups stay in lists (≤ 150 B/file measured). Streaming them is a follow-up only if S6 measures over target.
4. **ASSESS**:
   * `GraphView.of(store)` returns a store-backed view. `file(id)` → `files().get` through a 4,096-entry cache. `edgesOf` →
     `relationships().of(id)`. `groupsOf` → a member → group-key index built in one pass over `duplicate_groups`.
     `backupsOf`/`backupsInArchive` → a small index built over `backup_records`. `sizeUnique` → a sorted `long[]` of present
     sizes with a binary-search count. `alias`/canonical → only the alias sets. `apkStates` stays in a map (APK files only).
   * `GraphView.add(...)` stays for tests (`RiskFx`).
   * `Assessor.run(store, apps, now, pol, cfg, VerdictSink sink)` is new. The old list-returning signature remains as a thin
     wrapper for the CLI and tests.
5. **PLAN**:
   * `ProvisionalRecommendations.ofOne(Verdict, FileRecord, scanId, List out)`. `V2Runner`'s sink calls it per verdict and
     drops the verdict.
   * `StoreFileMap extends AbstractMap<String, FileRecord>`: `get` → `files().get` (LRU), `entrySet`/`keySet` stream
     `files().all()` in ascending id, `size` → `files().size()`, mutators throw. It replaces `fm` in `V2Runner`/`V2Analysis`.
     Planning only reads `fm` (`HashOnDemand`, `UnitBuilder2`, `PlanStaleness`, `ProvisionalRecommendations` were checked:
     only `get`).
   * `V2Adapter.adapt` iterates `in.files` directly when it is a `SortedMap` or a `StoreFileMap`, instead of copying and
     sorting all ids.
6. **`V2Analysis`** (`v2adapter`, new, Android-free): the body of `V2Runner.analyse` (ASSESS → recommendations →
   hash-on-demand → app subjects → PLAN → adapt), so the JVM scale harness runs the app's exact code path. `V2Runner` keeps
   only the Android wiring.
**REVIEW_S F-10 (equality pins for items 4–5).** These are part of the spec, each with a test:

* `ofOne` receives the **post-ASSESS** FileRecord (the written copy `w` when `Assessor.run` wrote one, else `f`). Today `fm`
  is built after `Assessor.run` returns, so recommendations see the updated U/L/R/C.
* The store-backed `GraphView` sees ASSESS's U/L/R/C writes mid-loop; today's snapshot does not. That is safe only while no
  rule reads `utility`/`lossSeverity`/`recoverability`/`confidence` of a FileRecord other than the subject (true today;
  `GraphView.endCurrent` reads only presence and fingerprint). A test asserts that `GraphView.file(x)` callers in `v2.risk`
  and `v2.plan` read none of those four fields, or the view masks them to the values at ASSESS start.
* `GraphView` orders: `edgesOf` in ascending relationship key; `groupsOf` in ascending group key; `sizeCount` over PRESENT
  records with `sizeBytes ≥ 0`; canonical = smallest fileId per (volume, physicalKey). Same as `GraphView.of` today.
* `StoreFileMap` overrides `get`, `containsKey`, `size`, `keySet().iterator()`, `entrySet().iterator()`; every other method
  throws. (`AbstractMap`'s defaults for `containsKey`/`get` scan the whole entry set, decoding every record.)
* Golden equality per item is against JSON produced by the **unchanged** code (recorded before item 1 lands), not only
  "before and after" each sub-commit.

7. CLI-only and not needed for the target: `V2Json` collects all FileRecords to sort by path. It is left as is; heap-capped
   tests do not use the CLI JSON.

---

## 5. Migration, selection, equality, capacity guard

### 5.1 Selection and migration

* `MemoryStore` stays unchanged as the oracle, for unit tests, small runs and the CLI default.
* `v2.store.Stores.open(Kind kind, BlobStore b, long checkpointEveryMs, Clock c, SpaceProbe p)` and `Stores.create(…)`
  select the implementation. Kind is `MEMORY` or `DISK`.
* CLI: `--store memory|disk` (default `memory` until S6, then `disk`).
* `V2Runner` uses `DISK` from S6.
* When `DiskStore.create` runs and `store.snap` exists (an earlier app version), it truncates `store.snap` to 0 bytes. There
  is no data migration: a v2 run never resumes (`cfg.resume` false), and `V2Runner.restore` then finds no completed DiskStore
  session and returns false, which shows nothing. That is the existing behaviour for "no v2 result".

### 5.2 Equality

`tools/v2eval/store_equal.sh <tree>` runs the CLI twice with fixed `--now`, `--store memory` and `--store disk`, with
`--assess --target-bytes max`. The two outputs must be **byte-identical after removing the `timings` object** (the only
nondeterministic block). It runs on:

* the `run_tests.sh` tree;
* every scenario fixture (`ScenarioFixtureTest` gains a store parameter and runs both);
* the dataset and synth 10k (manual and exit criteria);
* kill/resume: disk-killed-then-resumed vs memory-clean, compared with `evaluate.py --compare`.

### 5.3 V2Capacity recalculated

* Replace the single `BYTES_PER_FILE` with phase constants measured in S6 by the scale harness (JVM peak per phase × 1.25):
  * `GRAPH_BYTES_PER_FILE` (target 660);
  * `END_BYTES_PER_FILE` (target 990–1,310);
  * `STORE_FIXED` (16 MB);
  * `GRAPH_RESERVE` (64 MB).
* `maxFiles = min((0.8·max − used − STORE_FIXED − GRAPH_RESERVE) / GRAPH_BYTES, (0.8·max − used − STORE_FIXED) / END_BYTES)`.
* `V2AdapterTest` "memory guard" checks change to the new formula, with expected values for 256 and 512 MB.
* The disk preflight (section 3.12) is added to the same guard.
* The m-11 caveat (discovery itself could run out of memory) shrinks but does not go away: DISCOVER still grows the index
  (≈ 110–170 B/file), so a 1M-file tree could still exhaust the heap before the guard. REVIEW_S F-17: `CapacityGuard` also
  checks the running file count every 10,000 records inside DISCOVER and stops with the same message.

---

## 6. Implementation plan

Every slice ends with `bash tools/v2eval/run_tests.sh` green, CI's APK build green, and no change to CLI JSON for MemoryStore
runs (golden check on the dataset).

**S0. Harness and baseline (no behaviour change)**

* Add `tools/v2eval/gen_synth.py` (the generator of section 1.1, with `--images` share and `--seed`).
* Add `jvmtest/.../HeapProfile.java` (checkpoint histograms, per-table retained size, no reflection).
* Add `jvmtest/.../ScaleRun.java`, which runs `V2Analysis` with a selectable store.
* Add `tools/v2eval/run_scale.sh` (generate N files into `$TMPDIR`, run `ScaleRun` under `-Xmx`, print peak per phase).
* Exit: the section 1 table is reproduced within ±10 %.

**S1. Device layer and frames**

* `BlobStore.openRead`, implemented in DirBlobStore, Android and KillingBlobStore (with the new kill points).
* `v2.store.disk.Frame` (encode/decode/CRC), `SegmentWriter` and `SegmentReader`.
* Tests (`FrameTest`):
  * round trip of every record type through `Codecs`;
  * every single-byte flip in a frame is detected;
  * truncation at every byte offset is detected;
  * a reader never reads past `durable`.

**S2. DiskStore core**

* `KeyIndex` and the delta/merge, the secondary indexes, txn/undo, commit/rollback/flush, manifest, index file, open/replay.
* Tests:
  * `StoreContractTest.run(DiskStore)`;
  * **`StoreOracleTest`**: 2,000 random seeds × 300 operations each (put/remove/commit/rollback/flush/close+reopen/`all`/
    `byPath`/`childrenOf`/`underPrefix`/`bySize`/`of`/`forFile`/`forOp`/`keys`, mixed keys including non-ASCII paths and
    non-hex ids). After each operation, results are compared with MemoryStore. After each reopen, `StorePersistenceTest.dumpAll`
    must be equal for the committed prefix;
  * `StorePersistenceTest` on DiskStore;
  * merge threshold forced to 8 in one run so that merges happen constantly.
* Exit: the oracle passes; `stats().indexBytesEstimate` ≤ 280 B per file on the 100k store (synthetic records injected through
  the API, no file tree needed).

**S3. Crash safety and corruption; CLI `--store`**

* Tests (`DiskKillTest`), in process with `SimulatedKill`, on a 300-file fixture tree with images and duplicates. 200 random
  runs, each choosing one kill:
  * after replace commit k;
  * before replace commit k;
  * after b appended bytes (torn frame);
  * during index write.

  Each run resumes, and the result must equal the clean run (`evaluate.py --compare` semantics on the JSON).
* REVIEW_S F-12, added kill/crash tests:
  * **`PowerLossBlobStore`** (jvmtest fake): tracks each file's last synced length; at a kill it reverts every file to
    that length, optionally appends random garbage (0–4 KiB), drops files created since their last sync, and loses an
    uncommitted `replace`. 200 random power-loss runs, same equality requirement. This is the only test that exercises
    "what was fsynced and when".
  * kill between the index `replace` and the manifest `replace` → the stale-bound index is rejected (F-01) and the store
    reopens exactly at the previous checkpoint;
  * a segment file beyond the manifest's list pre-filled with valid, committed frames from a crashed run → never replayed,
    including after a forced rebuild-by-scan (F-02);
  * a CRC failure placed below `durable` and a listed segment shorter than `durable` → fresh store, never partial replay
    (F-05);
  * a locator pointed at another key's valid frame → `StoreCorruption` for a files and a features table (F-04);
  * **multi-run evolving tree**: three runs over one persistent store (`cfg.resume` false, as the app does) with files
    added, removed, moved and modified between runs, compared with MemoryStore on every run — the app's real path.
* REVIEW_S F-07, added corruption tests: damage a features frame and a hashes frame **after T3 and before GRAPH**, and a
  features frame **after GRAPH and before ASSESS**, in the same run. Required: the run completes; its removal
  recommendations are a subset of the clean run's; no file gets `NO_VERIFIED_BACKUP` or perceptual FALSE that the clean run
  did not; the next run equals clean.
* `ResetSafetyTest` (F-06).
* `run_tests.sh`' kill test runs once per store.
* Corruption (`DiskCorruptionTest`):
  * flip a byte in a features/hashes/pairChecks frame → the next scan re-extracts, the result equals clean, and
    `D_STORE_DAMAGED` is counted;
  * flip a byte in a files/relationships frame → the run FAILS with `StoreCorruption`, the next open resets, and the rescan
    equals clean (apart from the reset event);
  * damaged or truncated `.idx` → rebuilt and identical;
  * garbage after `durable` → ignored;
  * damaged manifest → fresh store.
* Equality (section 5.2) on the run_tests tree and all fixtures.
* Exit: all green; 200/200 kill runs equal.

**S4. Compaction and space**

* Generations and sets, truncation of the old set, triggers, `SpaceProbe`, preflight, in-run reserve, final-1-MiB allowance.
* Tests:
  * kill at every replace/truncate commit inside a compaction (forced threshold) → resume equals clean;
  * oracle test with compaction forced every 50 operations;
  * fake `SpaceProbe` dropping below the reserve mid-T1 → the run stops with `D_LOW_STORAGE`, the session is FAILED and
    durable, the next run works;
  * preflight refusal message;
  * appended bytes never exceed reserve-crossing + 1 MiB.
* Exit: disk total ≤ 2 × live + 64 MiB at the end of a 100k run.

**S5. Streaming callers (section 4)**

* One sub-commit per item 1–6, each with golden equality (MemoryStore before and after) on fixtures, the dataset and synth
  10k.
* Exit: peak per phase measured with `HeapProfile` on synth 100k with **MemoryStore** drops by the working-set amounts in
  section 2. In particular the ASSESS peak minus the store is ≤ 400 B per file.

**S6. App switch and scale**

* `V2Runner` → `V2Analysis` + DiskStore; `store.snap` truncation; V2Capacity phase constants; packed hex keys if the S2 exit
  measured over 260 B/file on the dataset; the store-closed-before-adapter option (section 2) if the 150k target needs it.
* Scale tests (`run_scale.sh`, not in `run_tests.sh` because they take minutes; `run_tests.sh` gets a 10k smoke at
  `-Xmx64m`):
  * **(a)** 100,000 files (20 % images), DISCOVER → PLAN, DiskStore, **`-Xmx128m`**: must complete.
  * **(b)** 150,000 files, full `V2Analysis` including `V2Adapter`, **`-Xmx148m`** ((0.8 × 256 − 20) / 1.25, REVIEW_S
    F-08; 200 MB would spend the safety factor): must complete. `V2Capacity` at 256 MB may not claim more files than the
    largest N that passes at this cap.
  * **(c)** (a) with MemoryStore at `-Xmx128m` must fail with OOM. This proves the test bites.
  * **(d)** equality of (a) DiskStore against MemoryStore at a large heap: CLI JSON without timings is byte-identical.
* Exit criteria:

| Criterion | Target |
|---|---|
| in-memory store index | ≤ 280 B/file on synth 100k, ≤ 260 on the dataset (≤ 200 / 190 packed) |
| pipeline peak minus adapter | ≤ 600 B/file + ≤ 80 MB fixed |
| scale tests (a), (b) | pass |
| wall time DiskStore / MemoryStore on synth 100k | ≤ 1.5× |
| disk live | ≤ 2.6 KB/file |
| V2Capacity at 256 MB with 20 MB used | ≥ 150,000 |
| Android | CI APK builds; the PHASE_A device checklist is updated (the message thresholds change) |

**S7. GRAPH decode budget resumes across runs (REVIEW_S F-13; independent of S1–S6, can land first)**

Facts (code read): the cap lives in `Perceptual.run` (`GraphConfig.MAX_DECODES_PER_RUN = 20,000`, checked before each pending
pair). On hitting it GRAPH still writes everything, nodes with a pending pair get perceptual `UNKNOWN` (F-01 of PHASE_3), and
`RecoverabilityEstimator` turns that into `UNKNOWN`, never `NO_VERIFIED_BACKUP`. But the stage returns `BUDGET_EXHAUSTED`, the
pipeline PAUSES, and `V2Runner` shows no result at all ("Analysis stopped early"). Completed `PairCheck`s are already
persisted, so the *checks* already carry across runs; only the result is withheld, and every run re-decodes from scratch
because pending pairs are ordered by size and the 256-entry signature cache misses.

Persisting decoded signatures is **rejected**: a `StructSig` is ≈ 195 KB (49.9 MB / 256 measured); 30,000 photos would be
≈ 6 GB. The persisted `PairCheck` is the right unit to carry over.

Change:

1. **Order pending pairs for cache reuse.** Union-find over the pending pairs; process components in order of their smallest
   pair key, and within a component by (ascending smaller node id, then pair key). Bursts are small components, so the cache
   holds them and decodes approach the number of distinct pending nodes (one per node) instead of ≈ 1.4 per pair. Deterministic.
2. **The cap becomes a per-run budget, not a stop.** When the decode cap (not the time budget, not pause/cancel) is reached,
   `Perceptual` stops checking, GRAPH completes and returns `DONE`; coverage records `graph.pendingPairs` and
   `graph.pendingImages`. Pending nodes are perceptual `UNKNOWN` exactly as today. `V2Runner` shows the result with a line
   "N photos have not been compared yet; run again to finish." The next run re-uses the persisted checks and continues.
3. Nothing in ASSESS/PLAN changes: an unchecked pair yields no relationship, so it can never support a removal, and a pending
   image is never "unique".

Tests (`PerceptualBudgetTest`, fixture tree with bursts, cap forced to a small number):

* a run that hits the cap ends `DONE` with a result; every node with a pending pair has status `UNKNOWN` and
  `Recoverability.UNKNOWN`; no recommendation's evidence cites a pending pair; removal recommendations ⊆ uncapped run's;
* repeated runs over the same store converge: after k runs the pending count is 0 and the JSON equals one uncapped run
  (timings and the `decodes` counter removed);
* decodes ≤ distinct pending nodes + components on the synthetic burst tree (ordering works);
* uncapped results are byte-identical to today's except the `decodes` coverage counter (the ordering changes only how often
  the cache misses); the golden files are updated for that counter only;
* kill/resume equality (S3) runs on trees below the cap: a resumed run gets a fresh per-run counter, so above the cap a
  killed-and-resumed run legitimately checks more pairs than a clean one (REVIEW_S F-18).

---

## 7. Risks

1. **Riskiest: the log/index replay and the rollback/undo interplay.** One wrong locator silently returns another
   generation's record, and results stay plausible. The mitigations are the oracle test (MemoryStore on every operation,
   with reopens and forced merges and compactions), CRC per frame, and the decoded key being checked against the requested
   key on every read (a mismatch is a `StoreCorruption`, never a wrong answer).
2. **GRAPH refactor equality.** Node-lite and the primitive candidate generator touch the most algorithm code. Golden
   equality on fixtures, the dataset and the synth tree, plus the existing `PerceptualTest`/`ExactGrouperTest`, must hold
   bit for bit.
3. **Phone I/O speed.** About 100 µs per random read on a phone is an estimate (desktop measured 8–12 µs). Before S6 merges,
   time the 2,414-file dataset on a phone (PHASE_A checklist). If ASSESS reads are slow, the GraphView cache size is the
   knob.
4. **Flash wear and size.** About 8 KB written per file per full scan (6 FileRecord rewrites + features + hashes) plus
   compaction. That is about 1.2–2.4 GB for 150k files. The current snapshot design writes more (≈ 400 MB per snapshot at
   150k, ≥ 1 per minute), but the number should be reported. Dropping nulls (m-10) cuts about 25 %.
5. **The 150k target depends on the adapter phase** (section 2). Without the store-closed-before-adapter option the honest
   number is about 130k at 256 MB.
6. **Decode budget at scale** (section 1.6). It is independent of the store, but it will stop large phones before the store
   matters. Addressed by slice S7 (REVIEW_S F-13).

---

## 8. SQLite: spec section 13 versus this design

**What the spec asked for.** SQLite tables (spec 13; ARCHITECTURE 4.3: each table as `k TEXT PRIMARY KEY, v BLOB` with index
columns, commit = end transaction). It was planned for Phase 10. A31 brought it forward.

**Why this design for now.**

1. **Testability.** `android.database.sqlite` does not exist on the JVM, and v2 allows no third-party library (no
   sqlite-jdbc, no Robolectric). A SQLite store would be untestable here: the kill test, the oracle test, corruption tests and
   the 100k heap-capped scale test could only run on a device, and there is none in this environment. The disk store is pure
   Java on `BlobStore`, so every line runs on the JVM and the same bytes run on Android.
2. **Same interface, same records.** Both implementations sit behind `V2Store` and store the same `Codecs` JSON, so neither
   blocks the other.
3. **Memory.** The heap win comes mostly from the caller changes (section 4), which SQLite would need just as much. SQLite's
   own page cache (default about 2 MB) is comparable to the LRU here.
4. **Durability semantics are unchanged.** Checkpoints at commit boundaries satisfy the existing contract. SQLite would make
   every commit durable (stricter, also fine).

**What a later SQLite implementation needs.**

* `SqliteStore implements V2Store` in the app module (`android.*` is forbidden under `v2/`).
* Tables as ARCHITECTURE 4.3. The files index columns are `(volume_id, path, file_id)`, matching section 3.5's ordering; the
  ordering must be byte-wise, so use `COLLATE BINARY` and check non-ASCII paths against the oracle.
* `all()`/`keys()`/`underPrefix` as cursors over a key snapshot (read the keys first, then fetch by key, to keep "stable under
  concurrent put"); `rollback` = `endTransaction` without success + new `beginTransaction`.
* Corruption: `SQLiteDatabaseCorruptException` → the same fail-closed rule (section 3.11).
* Space: the same `SpaceProbe` rules.
* **Testing**: the S2 oracle test and the S3 kill test must be run as instrumented tests on a device or emulator before
  SQLite replaces DiskStore. That is the gate that is missing today.
