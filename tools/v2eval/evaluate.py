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
