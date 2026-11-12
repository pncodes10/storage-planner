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
    print(json.dumps(metrics, indent=1, ensure_ascii=False))
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(metrics, f, indent=1, ensure_ascii=False)
    if args.gate:
        n = int(args.gate.replace("phase", ""))
        ok = True
        if n >= 1 and (missing or size_mismatch):
            ok = False
            print("GATE phase1 FAILED: missing=%d size_mismatch=%d" % (len(missing), len(size_mismatch)), file=sys.stderr)
        print("GATE %s %s" % (args.gate, "PASSED" if ok else "FAILED"))
        return 0 if ok else 1
    return 0


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
    a = ap.parse_args(argv)
    if a.compare:
        return compare(*a.compare)
    if not a.result or not a.dataset:
        ap.print_usage(sys.stderr)
        return 2
    return discovery(a)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
