#!/usr/bin/env python3
"""Scores a V2Cli result JSON against the labelled dataset (Phase-1 scope; later phases extend it).

Usage (stdlib only; run with `python3 -I`):

  evaluate.py --result R.json --dataset D [--root-prefix P] [--gate phase1] [--out metrics.json]
      Discovery coverage: manifest files found PRESENT with equal size, missing, size mismatches, unlabelled
      files, presence histogram, inaccessible locations by reason. Gate phase1: missing == 0 and size_mismatch == 0.

  evaluate.py mutations --before R1.json --after R2.json --mutations mutations.json
      Expected change state per mutation op (table OPS below); reports unexpected and missing change states.
      Gate: both empty.

  evaluate.py --compare A.json B.json
      Structural JSON equality ignoring volatile keys (IGNORED below). Exit 0 if equal.

Exit status: 0 pass, 1 gate failure / difference, 2 usage error.
"""
import argparse
import os
import hashlib
import collections
import json
import sys
import unicodedata

# dataset agent's op vocabulary -> expected change states, per affected path:
#   "path" = the mutation's path, "target"/"new_path" = the extra path the op names.
# "none" = Phase 1 cannot see it (same size and mtime: only a content hash can, documented limit).
OPS = {
    "add": {"path": "ADDED"},
    "append": {"path": "CHANGED"},
    "modify": {"path": "CHANGED|REPLACED"},
    "overwrite_same_size": {"path": "none"},
    "touch": {"path": "CHANGED"},
    "delete": {"path": "REMOVED"},
    "delete_target": {"target": "REMOVED"},
    "rename": {"path": "REMOVED", "new_path": "ADDED"},
}

# keys ignored by --compare (dotted paths; "*" matches any key at that level)
IGNORED = {"timings", "scan.resumedCount", "coverage.resumedCount", "coverage.events", "coverage.eventsDropped"}


def nfc(p):
    return unicodedata.normalize("NFC", p).strip("/")


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def manifest_files(dataset):
    m = load(dataset.rstrip("/") + "/manifest.json")
    files = m["files"] if isinstance(m, dict) else m
    return files


def discovery(args):
    r = load(args.result)
    files = manifest_files(args.dataset)
    prefix = nfc(args.root_prefix) + "/" if args.root_prefix else ""
    by_path = {}
    for f in r["files"]:
        by_path.setdefault(nfc(f["path"]), []).append(f)
    found, missing, size_mismatch, skipped = 0, [], [], []
    labelled = set()
    for e in files:
        p = nfc(e["path"])
        if prefix:
            if not p.startswith(prefix):
                continue
            p = p[len(prefix):]
        if "symlink_target" in e:   # symlinks are not followed (PHASE_1 4.4): not files of the scan
            skipped.append(p)
            continue
        labelled.add(p)
        recs = [x for x in by_path.get(p, []) if x.get("presence") == "PRESENT"]
        if not recs:
            missing.append(p)
            continue
        found += 1
        if e.get("size") is not None and recs[0].get("size") != e["size"]:
            size_mismatch.append({"path": p, "manifest": e["size"], "result": recs[0].get("size")})
    unlabelled = sorted(p for p in by_path if p not in labelled and any(x.get("presence") == "PRESENT" for x in by_path[p]))
    presence = collections.Counter(f.get("presence") for f in r["files"])
    cov = r.get("coverage") or {}
    inacc = collections.Counter(l.get("reason") for l in cov.get("inaccessible", []))
    metrics = {
        "phase1": {
            "manifest_files": len(labelled), "found": found, "missing": len(missing), "size_mismatch": len(size_mismatch),
            "unlabelled": len(unlabelled), "symlinks_skipped": len(skipped),
            "found_fraction": (found / len(labelled)) if labelled else None,
            "presence": dict(sorted(presence.items(), key=lambda kv: str(kv[0]))),
            "inaccessible_by_reason": dict(sorted(inacc.items())),
            "inaccessible_total": cov.get("inaccessibleTotal"),
            "complete": cov.get("complete"),
            "missing_paths": missing[:50], "size_mismatch_items": size_mismatch[:50], "unlabelled_paths": unlabelled[:50],
        },
        "operational": r.get("timings"),
    }
    if not args.gate:
        print(json.dumps(metrics, indent=1, ensure_ascii=False))
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(metrics, f, indent=1, ensure_ascii=False)
    if args.gate:
        n = int(args.gate.replace("phase", ""))
        ok = True
        restricted_missing = [p for p in missing if under_restricted(p)]
        real_missing = [p for p in missing if not under_restricted(p)]
        restricted_reported = restricted_coverage(cov, restricted_missing)
        print("phase1: manifest %d, found %d, missing %d (of which %d under Android/data|obb: expected inaccessible, %s), size mismatch %d"
              % (len(labelled), found, len(missing), len(restricted_missing),
                 "reported" if restricted_reported else "NOT REPORTED", len(size_mismatch)))
        if n >= 1 and (real_missing or size_mismatch or not restricted_reported):
            ok = False
            print("GATE phase1 FAILED: missing=%d size_mismatch=%d restricted_reported=%s"
                  % (len(real_missing), len(size_mismatch), restricted_reported), file=sys.stderr)
        if n >= 2:
            ok = phase2(args, r) and ok
        if n >= 3:
            ok = phase3(args, r) and ok
        print("GATE %s %s" % (args.gate, "PASSED" if ok else "FAILED"))
        return 0 if ok else 1
    return 0


# A18: on a real phone these are restricted; the gate expects them reported as inaccessible, not found.
RESTRICTED = ("Android/data", "Android/obb")


def under_restricted(p):
    return any(p == r or p.startswith(r + "/") for r in RESTRICTED)


def restricted_coverage(cov, restricted_missing):
    """Every restricted prefix holding a missing manifest file appears in coverage.inaccessible (or a parent does)."""
    locs = [nfc(l.get("path") or "") for l in cov.get("inaccessible", [])]
    need = {r for r in RESTRICTED for p in restricted_missing if p == r or p.startswith(r + "/")}
    return all(any(r == l or r.startswith(l + "/") or l.startswith(r) for l in locs) for r in need)


# ---------------------------------------------------------------------------------------------------- phase 2

FAMILY = {}
for fam, ids in {"image": "jpeg png gif webp bmp tiff dng heic heif avif ico svg", "video": "mp4 mov 3gp mkv webm avi",
                 "audio": "m4a mp3 aac flac ogg opus wav amr midi",
                 "document": "pdf ooxml-docx ooxml-xlsx ooxml-pptx odf ole2 rtf epub text csv html xml json vcf ics",
                 "archive": "zip tar gzip bzip2 xz zstd 7z rar", "installer": "apk apks xapk", "data": "sqlite wa-crypt",
                 "executable": "elf dex class jar", "special": "empty unknown"}.items():
    for t in ids.split():
        FAMILY[t] = fam
ZIP_FAMILY = {"zip", "apk", "apks", "xapk", "jar", "ooxml-docx", "ooxml-xlsx", "ooxml-pptx", "odf", "epub"}


def sniff(path, size):
    """Independent oracle: expected family (or 'zipfam') from the file's first bytes; None = no opinion."""
    if size == 0:
        return "special"
    try:
        with open(path, "rb") as f:
            h = f.read(4096)
    except OSError:
        return None
    ext = path.rsplit(".", 1)[-1].lower() if "." in path.rsplit("/", 1)[-1] else ""
    if h[:3] == b"\xff\xd8\xff" or h[:8] == b"\x89PNG\r\n\x1a\n" or h[:4] in (b"GIF8",) or (h[:4] == b"RIFF" and h[8:12] == b"WEBP") \
            or h[:4] in (b"II*\x00", b"MM\x00*"):
        return "image"
    if h[4:8] == b"ftyp":
        b = h[8:12]
        if b in (b"heic", b"heix", b"mif1", b"avif"):
            return "image"
        return "audio" if b in (b"M4A ", b"M4B ") or ext == "m4a" else "video"
    if h[:4] == b"OggS" or h[:3] == b"ID3" or (h[:4] == b"RIFF" and h[8:12] == b"WAVE") or h[:4] == b"fLaC":
        return "audio"
    if b"%PDF-" in h[:1024]:
        return "document"
    if h[:4] == b"PK\x03\x04":
        return "zipfam"
    if h[:2] == b"\x1f\x8b":
        return "archive"
    if ext in ("crypt12", "crypt14", "crypt15"):
        return "data"
    if h and b"\x00" not in h:
        try:
            h.decode("utf-8")
            return "document"
        except UnicodeDecodeError:
            pass
    return None


def pairs_of(groups):
    out = set()
    for g in groups:
        g = sorted(g)
        for i in range(len(g)):
            for j in range(i + 1, len(g)):
                out.add((g[i], g[j]))
    return out


def phase2(args, r):
    root = args.dataset.rstrip("/")
    man = manifest_files(args.dataset)
    mby = {nfc(e["path"]): e for e in man if "symlink_target" not in e}
    rby = {nfc(f["path"]): f for f in r["files"] if f.get("presence") == "PRESENT"}
    rows = []
    ok = True

    def row(name, value, gate=None, passed=None):
        nonlocal ok
        if passed is False:
            ok = False
        rows.append((name, value, gate or "", "" if passed is None else ("PASS" if passed else "FAIL")))

    # type accuracy by manifest category, against the byte-sniff oracle
    bycat = collections.defaultdict(lambda: [0, 0])
    wrong = []
    for p, e in mby.items():
        f = rby.get(p)
        if f is None:
            continue
        exp = sniff(root + "/" + p, e.get("size"))
        if exp is None:
            continue
        t = f.get("type")
        good = (t in ZIP_FAMILY) if exp == "zipfam" else FAMILY.get(t) == exp
        bycat[e["category"]][0] += 1
        bycat[e["category"]][1] += good
        if not good:
            wrong.append("%s: %s expected %s" % (p, t, exp))
    tot = sum(v[0] for v in bycat.values()); good = sum(v[1] for v in bycat.values())
    acc = good / tot if tot else None
    row("type accuracy (sniff oracle)", "%d/%d = %.4f" % (good, tot, acc or 0), ">= 0.99", acc is not None and acc >= 0.99)
    worst = sorted(((v[1] / v[0], k, v) for k, v in bycat.items() if v[0]), key=lambda x: x[0])[:4]
    row("  lowest categories", ", ".join("%s %d/%d" % (k, v[1], v[0]) for _, k, v in worst))

    # sha256 wherever v2 computed one
    with_sha = [(p, f) for p, f in rby.items() if f.get("sha256") and p in mby]
    bad = [p for p, f in with_sha if f["sha256"] != mby[p].get("sha256")]
    row("sha256 correct where computed", "%d/%d (coverage %d/%d PRESENT)" % (len(with_sha) - len(bad), len(with_sha), len(with_sha), len(rby)),
        "100%", not bad)

    # exact-duplicate grouping, pairwise
    parent = {}
    def find(x):
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    for p, e in mby.items():
        for rel in e.get("relationships", []):
            if rel.get("type") == "EXACT_DUPLICATE" and nfc(rel["target"]) in mby:
                parent[find(p)] = find(nfc(rel["target"]))
    comps = collections.defaultdict(list)
    for x in list(parent):
        comps[find(x)].append(x)
    truth = pairs_of(c for c in comps.values() if len(c) > 1)
    pg = collections.defaultdict(list)
    for p, f in rby.items():
        if f.get("duplicateGroupId"):
            pg[f["duplicateGroupId"]].append(p)
    pred = pairs_of(pg.values())
    identical = {(a, b) for a, b in pred if a in mby and b in mby and mby[a]["sha256"] == mby[b]["sha256"]}
    prec = len(identical) / len(pred) if pred else 1.0
    rec = len(pred & truth) / len(truth) if truth else 1.0
    row("exact-dup precision (byte-identical)", "%d/%d = %.4f" % (len(identical), len(pred), prec), "= 1.0", prec == 1.0)
    row("exact-dup recall (EXACT_DUPLICATE)", "%d/%d = %.4f; labelled-pair precision %.4f; unlabelled identical pairs %d"
        % (len(pred & truth), len(truth), rec, (len(pred & truth) / len(pred)) if pred else 1.0, len(identical - truth)))

    # encrypted PDFs
    try:
        pw = load(root + "/pdf_passwords.json")["files"]
    except (OSError, KeyError, ValueError):
        pw = {}
    try:
        s09 = [nfc(x) for s in load(root + "/scenarios.json")["scenarios"] if s["id"] == "S09" for x in s["files"]]
    except (OSError, KeyError, ValueError):
        s09 = []
    enc_expected = {nfc(x) for x in pw} | set(s09)
    tp = fn = fp = unk = 0
    for p, f in rby.items():
        if f.get("type") != "pdf":
            continue
        e = (f.get("pdf") or {}).get("encrypted")
        if p in enc_expected:
            tp += e is True
            fn += e is not True
            unk += e is None
        elif e is True:
            fp += 1
    row("encrypted PDF detected", "%d/%d (unknown %d), false positives %d" % (tp, len(enc_expected & set(rby)), unk, fp),
        "all, 0 FP", tp == len(enc_expected & set(rby)) and fp == 0)

    # zip safety flags
    for sub, flag, test in (("path_traversal", "pathTraversal", lambda a: a.get("pathTraversal") is True),
                            ("high_ratio_archive", "bombSuspect", lambda a: a.get("bombSuspect") is True or (a.get("maxRatio") or 0) >= 100),
                            ("nested_archive", "nestedArchives", lambda a: (a.get("nestedArchives") or 0) >= 1)):
        files = [p for p, e in mby.items() if e.get("subcategory") == sub and p in rby]
        hit = [p for p in files if test(rby[p].get("archive") or {})]
        row("zip %s -> %s" % (sub, flag), "%d/%d" % (len(hit), len(files)), "all", len(hit) == len(files))

    # APK identity and install state
    try:
        inst = {a["package"]: a for a in load(root + "/installed_apps.json")["installed"]}
    except (OSError, KeyError, ValueError):
        inst = {}
    n = idok = stok = sgok = 0
    bad_apk = []
    for p, e in mby.items():
        a = e.get("apk")
        if not a or p not in rby:
            continue
        n += 1
        g = rby[p].get("apk") or {}
        if g.get("packageName") == a.get("package") and g.get("versionCode") == a.get("versionCode"):
            idok += 1
        else:
            bad_apk.append(p)
        i = inst.get(a.get("package"))
        want = "NOT_INSTALLED" if i is None else ("SAME_VERSION" if i["versionCode"] == a["versionCode"] else
                                                  "INSTALLED_NEWER" if i["versionCode"] > a["versionCode"] else "INSTALLED_OLDER")
        stok += g.get("installState") == want
        if i is not None and a.get("signing_cert_sha256"):
            wsg = i.get("signing_cert_sha256") == a["signing_cert_sha256"]
            sgok += g.get("sameSigner") == wsg
        else:
            sgok += 1
    row("APK package+versionCode", "%d/%d" % (idok, n), "all", idok == n)
    row("APK installState / sameSigner", "%d/%d / %d/%d" % (stok, n, sgok, n), "all", stok == n and sgok == n)

    # mismatch flags on the misleading-filename files
    misl = {p: e["subcategory"] for p, e in mby.items() if (e.get("subcategory") or "").startswith("misleading") and p in rby}
    flagged = {p for p, f in rby.items() if f.get("extMismatch") in ("BENIGN", "SUSPICIOUS")}
    ext_files = [p for p, s in misl.items() if s == "misleading_extension"]
    hit = [p for p in ext_files if p in flagged]
    row("mismatch: misleading_extension flagged", "%d/%d" % (len(hit), len(ext_files)), "all", len(hit) == len(ext_files))
    name_files = [p for p, s in misl.items() if s == "misleading_name"]
    row("  misleading_name (content matches ext)", ", ".join("%s=%s" % (p.rsplit("/", 1)[-1], rby[p].get("extMismatch")) for p in name_files))
    row("  other files flagged", "%d (BENIGN %d, SUSPICIOUS %d)" % (
        len(flagged - set(misl)), sum(1 for p in flagged - set(misl) if rby[p]["extMismatch"] == "BENIGN"),
        sum(1 for p in flagged - set(misl) if rby[p]["extMismatch"] == "SUSPICIOUS")))

    # unknown handling (A9)
    imgs = [f for f in rby.values() if f.get("image") and (f["image"].get("decodeStatus") not in (None, "OK"))]
    badu = [f["path"] for f in imgs if any(f["image"].get(k) is not None for k in
            ("sharpness", "brightness", "contrast", "exposure", "colorfulness")) or f.get("phash") or f["extraction"]["status"] == "COMPLETE"]
    row("unknown handling (undecoded images)", "%d/%d clean" % (len(imgs) - len(badu), len(imgs)), "100%", not badu)

    st = collections.Counter(f["extraction"]["status"] for f in rby.values())
    row("extraction status", ", ".join("%s %d" % kv for kv in sorted(st.items())))
    dec = collections.Counter((f.get("image") or {}).get("decodeStatus") for f in rby.values() if f.get("image"))
    row("image decodeStatus", ", ".join("%s %d" % (k, v) for k, v in sorted(dec.items(), key=lambda kv: str(kv[0]))))
    ph = sum(1 for f in rby.values() if f.get("phash")); sh = sum(1 for f in rby.values() if (f.get("image") or {}).get("sharpness") is not None)
    row("pHash / sharpness coverage", "%d / %d of %d images" % (ph, sh, sum(1 for f in rby.values() if f.get("image"))))
    t = r.get("timings") or {}
    st1 = (t.get("stages") or {}).get("T1_METADATA")
    row("T1 throughput", "%.0f files/s" % (len(rby) * 1000.0 / st1) if st1 else "n/a")
    row("timings ms", json.dumps(t.get("stages")))
    if args.incremental:
        r2 = load(args.incremental)
        x = ((r2.get("coverage") or {}).get("extraction") or {}).get("extractedThisScan")
        row("incremental re-run extractedThisScan", str(x), "0", x == 0)

    w = max(len(a) for a, _, _, _ in rows)
    print("phase2 metrics")
    for a, b, c, d in rows:
        print("  %-*s  %-60s %-10s %s" % (w, a, b, c, d))
    if wrong:
        print("  type disagreements (first 10): " + "; ".join(wrong[:10]))
    if bad_apk:
        print("  APK identity wrong: " + ", ".join(bad_apk))
    return ok


CE_TYPES = ("COMPRESSED_FROM", "THUMBNAIL_OF", "VERSION_OF")
NEAR_TYPES = ("EDITED_FROM",) + CE_TYPES
IMAGE_TYPES = {"jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "tiff", "dng"}


def fold_of(root):
    return hashlib.sha256(root.encode("utf-8")).digest()[0] & 1


def phase3(args, r):
    """PHASE_3 11.3 / 15. Accuracy rows on --fold (F-03), safety rows on all pairs."""
    root = args.dataset.rstrip("/")
    man = manifest_files(args.dataset)
    mby = {nfc(e["path"]): e for e in man if "symlink_target" not in e}
    rby = {nfc(f["path"]): f for f in r["files"] if f.get("presence") == "PRESENT"}
    path_of = {f["fileId"]: nfc(f["path"]) for f in r["files"]}
    want_fold = None if args.fold == "all" else int(args.fold)
    rows, ok = [], True

    def row(name, value, gate=None, passed=None):
        nonlocal ok
        if passed is False:
            ok = False
        rows.append((name, value, gate or "", "" if passed is None else ("PASS" if passed else "FAIL")))

    def is_img(p):
        f = rby.get(p)
        return f is not None and (f.get("type") in IMAGE_TYPES or f.get("image") is not None)

    def node(ref):
        if "!" in ref:
            fid, ent = ref.split("!", 1)
            return path_of.get(fid, fid) + "!" + nfc(ent)
        return path_of.get(ref, ref)

    hn = {p: e["hard_negative_group"] for p, e in mby.items() if e.get("hard_negative_group")}
    # manifest sha classes
    sha_cls = collections.defaultdict(list)
    for p, e in mby.items():
        if e.get("sha256") and (e.get("size") or 0) >= 1 and p in rby:
            sha_cls[e["sha256"]].append(p)
    cls_of = {p: tuple(sorted(v)) for v in sha_cls.values() for p in v}
    # result exact groups
    groups = r.get("duplicateGroups") or []
    gcls = {}
    for g in groups:
        if g.get("exact"):
            mem = tuple(sorted(path_of[m] for m in g["members"]))
            for m in mem:
                gcls[m] = mem

    def expand(a, b, cls):
        out = set()
        for x in cls.get(a, (a,)):
            for y in cls.get(b, (b,)):
                if x != y:
                    out.add(tuple(sorted((x, y))))
        return out

    # ---- exact clusters (F-04)
    truth_x = pairs_of(v for v in sha_cls.values() if len(v) > 1)
    pred_x = pairs_of(set(gcls.values()))
    tp = len(pred_x & truth_x)
    px = tp / len(pred_x) if pred_x else 1.0
    rx = tp / len(truth_x) if truth_x else 1.0
    row("exact cluster precision (truth = manifest sha256)", "%d/%d = %.4f" % (tp, len(pred_x), px), "= 1.000", px == 1.0)
    row("exact cluster recall", "%d/%d = %.4f" % (tp, len(truth_x), rx), ">= 0.99", rx >= 0.99)
    lab = set()
    for p, e in mby.items():
        for rel in e.get("relationships", []):
            if rel["type"] == "EXACT_DUPLICATE":
                lab.add(tuple(sorted((p, nfc(rel["target"])))))
    row("  label-only / sha-only pairs", "%d / %d" % (len(lab - truth_x), len(truth_x - lab)))

    # ---- manifest labelled edges
    labelled = collections.defaultdict(set)   # type -> {(src, dst, root)}
    for p, e in mby.items():
        for rel in e.get("relationships", []):
            t = nfc(rel["target"])
            labelled[rel["type"]].add((p, t, hn.get(p) or t))
    edges = r.get("relationships") or []
    pred = collections.defaultdict(set)
    for e in edges:
        pred[(e["type"], e["verification"])].add((node(e["src"]), node(e["dst"])))

    def in_fold(root_):
        return want_fold is None or fold_of(root_) == want_fold

    label_root = {}
    for t_, lst in labelled.items():
        for a_, b_, rt_ in lst:
            label_root[tuple(sorted((a_, b_.split("!")[0])))] = rt_

    def pred_fold(a, b):
        q = tuple(sorted((a.split("!")[0], b.split("!")[0])))
        if q in label_root:
            return in_fold(label_root[q])
        g = hn.get(a) if hn.get(a) and hn.get(a) == hn.get(b) else None
        return in_fold(g or b.split("!")[0])

    # ---- content equivalence: compared as clusters (PHASE_3_NOTES D-9): truth = transitive closure of labelled
    # COMPRESSED/THUMBNAIL/VERSION image pairs and manifest sha classes; prediction = closure of VERIFIED edges of those
    # types and result exact groups. Pairs that are byte-identical on both sides are left to the exact rows.
    def ce_pairs(links, cls):
        par = {}

        def find(x):
            par.setdefault(x, x)
            while par[x] != x:
                par[x] = par[par[x]]
                x = par[x]
            return x
        for a_, b_ in links:
            for x in (a_, b_):
                for y in cls.get(x, (x,)):
                    par[find(y)] = find(x)
            par[find(a_)] = find(b_)
        comps = collections.defaultdict(list)
        for x in list(par):
            comps[find(x)].append(x)
        return {q for q in pairs_of(comps.values()) if cls_of.get(q[0]) is None or cls_of.get(q[0]) != cls_of.get(q[1])}
    lab_ce = [(a, b, rt) for t in CE_TYPES for a, b, rt in labelled[t] if is_img(a) and is_img(b)]
    tr_ce_all = ce_pairs([(a, b) for a, b, _ in lab_ce], cls_of)
    root_of = {}
    for a, b, rt in sorted(lab_ce, key=lambda x: x[2]):
        root_of.setdefault(a, rt); root_of.setdefault(b, rt)
    tr_root = {q: min(root_of.get(q[0], q[0]), root_of.get(q[1], q[1])) for q in tr_ce_all}
    pr_ce_all = ce_pairs([(a, b) for t in CE_TYPES for a, b in pred[(t, "VERIFIED")]], gcls)
    tr_ce = {q for q in tr_ce_all if in_fold(tr_root[q])}
    pr_ce = {q for q in pr_ce_all if (in_fold(tr_root[q]) if q in tr_root else pred_fold(q[0], q[1]))}
    tp = len(pr_ce & tr_ce)
    pc = tp / len(pr_ce) if pr_ce else 1.0
    rc = tp / len(tr_ce) if tr_ce else 1.0
    hn_false = sorted(q for q in pr_ce_all | pred_x if hn.get(q[0]) and hn.get(q[0]) == hn.get(q[1]))
    def same_source(q):
        sa = (mby.get(q[0]) or {}).get("source") or {}
        sb = (mby.get(q[1]) or {}).get("source") or {}
        edited = any(x["type"] == "EDITED_FROM" for p_ in q for x in (mby.get(p_) or {}).get("relationships", []))
        return not edited and sa.get("id") not in (None, "synthetic") and sa.get("id") == sb.get("id")
    gap = {q for q in pr_ce - tr_ce if same_source(q)}
    pc2 = tp / (len(pr_ce) - len(gap)) if len(pr_ce) > len(gap) else 1.0
    row("content-equivalence precision (fold %s), strict" % args.fold, "%d/%d = %.4f" % (tp, len(pr_ce), pc), ">= 0.99 (see next)", None)
    row("content-equivalence precision, unlabelled same-source pairs excluded",
        "%d/%d = %.4f (%d same-source pairs: %s)" % (tp, len(pr_ce) - len(gap), pc2, len(gap), sorted(gap)[:3]), ">= 0.99", pc2 >= 0.99)
    for q in sorted(pr_ce - tr_ce - gap)[:8]:
        row("    CE false pair", "%s ~ %s" % q)
    for q in sorted(tr_ce - pr_ce)[:15]:
        row("    CE missed pair", "%s ~ %s" % q)
    row("content-equivalence recall (fold %s)" % args.fold, "%d/%d = %.4f" % (tp, len(tr_ce), rc), ">= 0.95", rc >= 0.95)
    nodec = {p_ for p_, f_ in rby.items() if ((f_.get("image") or {}).get("decodeStatus") or "OK") != "OK"}
    tr_dec = {q for q in tr_ce if q[0] not in nodec and q[1] not in nodec}
    tpd = len(pr_ce & tr_dec)
    row("  recall, pairs with an undecodable member (WebP/HEIC on the JVM) excluded", "%d/%d = %.4f (%d excluded: relationships unknown)"
        % (tpd, len(tr_dec), tpd / len(tr_dec) if tr_dec else 1.0, len(tr_ce) - len(tr_dec)))
    row("hn_false_pairs (interchangeable inside a hard-negative group, all pairs)", "%d" % len(hn_false), "= 0", not hn_false)
    any_hn = sum(1 for e in edges if hn.get(node(e["src"])) and hn.get(node(e["src"])) == hn.get(node(e["dst"])))
    row("  any edge inside a hard-negative group", "%d" % any_hn)

    # ---- near duplicates (A-4): NEAR_TYPES edges + VS clusters
    def closure(pairs):
        parent = {}

        def find(x):
            parent.setdefault(x, x)
            while parent[x] != x:
                parent[x] = parent[parent[x]]
                x = parent[x]
            return x
        for a, b in pairs:
            parent[find(a)] = find(b)
        comps = collections.defaultdict(list)
        for x in list(parent):
            comps[find(x)].append(x)
        return comps.values()
    tr_n, tr_root_n = set(), {}
    for t in NEAR_TYPES:
        for a, b, rt in labelled[t]:
            if is_img(a) and is_img(b):
                for q in expand(a, b, cls_of):
                    tr_root_n[q] = rt
    vs_pairs = [(a, b) for a, b, _ in labelled["VISUALLY_SIMILAR"] if a in rby and b in rby]
    vs_root = {}
    for a, b, rt in labelled["VISUALLY_SIMILAR"]:
        vs_root.setdefault(a, rt); vs_root.setdefault(b, rt)
    for c in closure(vs_pairs):
        rt = min(vs_root.get(x, x) for x in c)
        for q in pairs_of([c]):
            tr_root_n[q] = rt
    tr_n = {q for q, rt in tr_root_n.items() if in_fold(rt)}
    pr_n_all = set()
    for t in NEAR_TYPES:
        for v in ("VERIFIED", "UNVERIFIED"):
            for a, b in pred[(t, v)]:
                if is_img(a) and is_img(b):
                    pr_n_all |= expand(a, b, gcls)
    for g in groups:
        if g.get("kind") == "BURST":
            pr_n_all |= pairs_of([[path_of[m] for m in g["members"]]])
    pr_n = {q for q in pr_n_all if (in_fold(tr_root_n[q]) if q in tr_root_n else pred_fold(q[0], q[1]))}
    tp = len(pr_n & tr_n)
    pn = tp / len(pr_n) if pr_n else 1.0
    rn = tp / len(tr_n) if tr_n else 1.0
    gap_n = {q for q in pr_n - tr_n if same_source(q)}
    pn2 = tp / (len(pr_n) - len(gap_n)) if len(pr_n) > len(gap_n) else 1.0
    row("near-duplicate precision (A-4, fold %s), strict" % args.fold, "%d/%d = %.4f" % (tp, len(pr_n), pn), "", None)
    row("near-duplicate precision, unlabelled same-source pairs excluded", "%d/%d = %.4f" % (tp, len(pr_n) - len(gap_n), pn2), ">= 0.95", pn2 >= 0.95)
    for q in sorted(pr_n - tr_n - gap_n)[:12]:
        row("    near false pair", "%s ~ %s" % q)
    row("near-duplicate recall (A-4, fold %s)" % args.fold, "%d/%d = %.4f" % (tp, len(tr_n), rn), ">= 0.80", rn >= 0.80)

    # ---- per type, directed
    for t in ("EXACT_DUPLICATE",) + NEAR_TYPES + ("VISUALLY_SIMILAR", "BACKED_UP_BY", "COMPANION_OF"):
        src_t = ("BACKED_UP_BY", "CONTAINED_IN") if t == "BACKED_UP_BY" else (t,)
        tr = {(a, b) for tt in src_t for a, b, rt in labelled[tt] if in_fold(rt)}
        pr = {(a, b) for v in ("VERIFIED", "UNVERIFIED") for a, b in pred[(t, v)] if pred_fold(a, b)}
        if t in ("EXACT_DUPLICATE", "VISUALLY_SIMILAR"):
            tr = {tuple(sorted(x)) for x in tr}
            pr = {tuple(sorted(x)) for x in pr}
        if t == "BACKED_UP_BY":
            pr = {(a, b.split("!")[0]) for a, b in pr}
        tp = len(tr & pr)
        p_ = tp / len(pr) if pr else float("nan")
        r_ = tp / len(tr) if tr else float("nan")
        f_ = 2 * p_ * r_ / (p_ + r_) if pr and tr and (p_ + r_) else float("nan")
        gate = passed = None
        if t == "EDITED_FROM":
            gate, passed = ">= 0.90 P", (not pr) or p_ >= 0.90
        row("  %-16s TP %3d  pred %3d  truth %3d  P %.3f R %.3f F1 %.3f" % (t, tp, len(pr), len(tr), p_, r_, f_), "", gate, passed)

    # ---- backups
    try:
        bman = load(root + "/backup_manifest.json")
    except (OSError, ValueError):
        bman = {}
    # truth: (archive, sha) of labelled BACKED_UP_BY / CONTAINED_IN subjects and backup_manifest intact members
    good = set()
    for tt in ("BACKED_UP_BY", "CONTAINED_IN"):
        for a, b, _ in labelled[tt]:
            if a in mby:
                good.add((b, mby[a].get("sha256")))
    for arch, v in bman.items():
        for m_ in v.get("intact", []):
            for p_, e_ in mby.items():
                if os.path.basename(p_) == os.path.basename(m_) and p_.startswith("DCIM/"):
                    good.add((nfc(arch), e_.get("sha256")))
    # false_verified: the archive member's bytes (read here with zipfile) differ from the subject's manifest sha256
    import zipfile
    fv, ver = [], 0
    for a, b in pred[("BACKED_UP_BY", "VERIFIED")]:
        ver += 1
        arch, ent = b.split("!", 1)
        try:
            with zipfile.ZipFile(root + "/" + arch) as z:
                h = hashlib.sha256(z.read(ent)).hexdigest()
        except Exception:
            h = None
        if h is None or h != (mby.get(a) or {}).get("sha256"):
            fv.append((a, b))
    row("backup false_verified", "%d (of %d VERIFIED)" % (len(fv), ver), "= 0", not fv)
    tr_b = {(a, t) for tt in ("BACKED_UP_BY", "CONTAINED_IN") for a, t, _ in labelled[tt]}
    pr_b = {(a, b.split("!")[0]) for a, b in pred[("BACKED_UP_BY", "VERIFIED")]}
    rb = len(tr_b & pr_b) / len(tr_b) if tr_b else 1.0
    row("BACKED_UP_BY recall (VERIFIED)", "%d/%d = %.3f" % (len(tr_b & pr_b), len(tr_b), rb), ">= 0.9", rb >= 0.9)

    unk = sum(1 for f in rby.values() if f.get("perceptualStatus") == "UNKNOWN")
    imgs = sum(1 for f in rby.values() if f.get("perceptualStatus") is not None)
    row("images with relationships unknown", "%d of %d images" % (unk, imgs))
    gcov = r.get("graphCoverage") or {}
    row("graph coverage", ", ".join("%s=%s" % kv for kv in sorted(gcov.items())))
    row("GRAPH time", "%s ms" % ((r.get("timings") or {}).get("stages") or {}).get("GRAPH"))
    w = max(len(x[0]) for x in rows)
    for name, value, gate, res in rows:
        print("%-*s  %s  %s  %s" % (w, name, value, gate, res))
    return ok


def changes(result):
    """path -> set of change states recorded in this result's scan (any record at the path)."""
    out = collections.defaultdict(set)
    for f in result["files"]:
        if f.get("change"):
            out[nfc(f["path"])].add(f["change"])
    return out


def mutations(args):
    before, after = load(args.before), load(args.after)
    spec = load(args.mutations)
    muts = spec["mutations"] if isinstance(spec, dict) else spec
    got = changes(after)
    expected = {}
    unexpected, missing = [], []
    for m in muts:
        table = OPS.get(m["op"])
        if table is None:
            unexpected.append({"op": m["op"], "error": "unknown op"})
            continue
        for key, states in table.items():
            p = nfc(m[key]) if m.get(key) else None
            if p is None:
                continue
            expected[p] = states
            want = set() if states == "none" else set(states.split("|"))
            have = got.get(p, set())
            if states == "none":
                if have:
                    unexpected.append({"path": p, "op": m["op"], "got": sorted(have), "expected": "no change visible"})
            elif not (have & want):
                missing.append({"path": p, "op": m["op"], "got": sorted(have), "expected": sorted(want)})
    for p, have in sorted(got.items()):
        if p not in expected:
            unexpected.append({"path": p, "got": sorted(have), "expected": "unchanged"})
    before_ids = {f["fileId"] for f in before["files"] if f.get("presence") == "PRESENT"}
    after_ids = {f["fileId"] for f in after["files"] if f.get("presence") == "PRESENT"}
    metrics = {"mutations": len(muts), "missing": missing, "unexpected": unexpected,
               "ids_kept": len(before_ids & after_ids), "ids_before": len(before_ids), "ids_after": len(after_ids)}
    print(json.dumps(metrics, indent=1, ensure_ascii=False))
    ok = not missing and not unexpected
    print("GATE mutations %s" % ("PASSED" if ok else "FAILED"))
    return 0 if ok else 1


def strip(o, path=""):
    if isinstance(o, dict):
        return {k: strip(v, path + "." + k if path else k) for k, v in o.items() if (path + "." + k if path else k) not in IGNORED}
    if isinstance(o, list):
        return [strip(x, path) for x in o]
    return o


def compare(a, b):
    x, y = strip(load(a)), strip(load(b))
    if x == y:
        print("EQUAL")
        return 0
    def diff(p, u, v, out):
        if len(out) > 20:
            return
        if isinstance(u, dict) and isinstance(v, dict):
            for k in sorted(set(u) | set(v)):
                diff(p + "." + k, u.get(k), v.get(k), out)
        elif isinstance(u, list) and isinstance(v, list) and len(u) == len(v):
            for i, (s, t) in enumerate(zip(u, v)):
                diff("%s[%d]" % (p, i), s, t, out)
        elif u != v:
            out.append("%s: %s != %s" % (p, json.dumps(u, ensure_ascii=False)[:120], json.dumps(v, ensure_ascii=False)[:120]))
    out = []
    diff("", x, y, out)
    print("DIFFERENT\n" + "\n".join(out))
    return 1


def main(argv):
    if argv and argv[0] == "mutations":
        ap = argparse.ArgumentParser(prog="evaluate.py mutations")
        ap.add_argument("--before", required=True)
        ap.add_argument("--after", required=True)
        ap.add_argument("--mutations", required=True)
        return mutations(ap.parse_args(argv[1:]))
    ap = argparse.ArgumentParser(prog="evaluate.py")
    ap.add_argument("--compare", nargs=2, metavar=("A", "B"))
    ap.add_argument("--result")
    ap.add_argument("--dataset")
    ap.add_argument("--root-prefix", default="")
    ap.add_argument("--gate")
    ap.add_argument("--baseline")
    ap.add_argument("--out")
    ap.add_argument("--fold", default="1", help="phase3: accuracy rows on fold 0, 1 or all (F-03); safety rows always on all pairs")
    ap.add_argument("--incremental", help="phase2: result of a second --kind incremental run on the same --state-dir")
    a = ap.parse_args(argv)
    if a.compare:
        return compare(*a.compare)
    if not a.result or not a.dataset:
        ap.print_usage(sys.stderr)
        return 2
    return discovery(a)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
