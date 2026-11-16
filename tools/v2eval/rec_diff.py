#!/usr/bin/env python3
"""Compare the recommendations of two v2 results per file path (PHASE_6 13, S1).

usage: rec_diff.py OLD NEW [--allow CODE,...] [--expect-none]
Prints every path whose translated tier (evaluate.translate) or primary reason code (reasonCodes[0] of the first
recommendation) differs, as `path: OLDTIER/OLDCODE -> NEWTIER/NEWCODE`, then counts by (old -> new).
--expect-none: exit 1 on any change.  --allow: exit 1 if a change involves neither an allowed code in OLD nor in NEW.
"""
import argparse
import json
import sys
import unicodedata


def translate(recs):
    """v2 recs of one file -> (translated removal prediction, raw delete tier or None, has transform). Copy of evaluate.translate."""
    dele = [x for x in recs if x["action"] in ("DELETE", "QUARANTINE")]
    if dele:
        t = dele[0]["tier"]
        return ("SAFE" if t in ("SAFE", "NO_LOSS") else t), t, False
    if any(x["tier"] == "PROTECTED" for x in recs):
        return "PROTECTED", None, False
    if any(x["action"] == "REVIEW" for x in recs):
        return "REVIEW", None, False
    return "KEEP", None, any(x["action"] in ("COMPRESS", "ARCHIVE") for x in recs)


def load(path):
    with open(path, encoding="utf-8") as fh:
        r = json.load(fh)
    path_of = {f["fileId"]: unicodedata.normalize("NFC", f["path"]) for f in r["files"]}
    by = {p: [] for p in path_of.values()}
    for x in r.get("recommendations", []):
        by.setdefault(path_of.get(x["fileId"], x["fileId"]), []).append(x)
    out = {}
    for p, recs in by.items():
        code = None
        if recs and recs[0].get("reasonCodes"):
            code = recs[0]["reasonCodes"][0]
        out[p] = (translate(recs)[0], code)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("old"); ap.add_argument("new")
    ap.add_argument("--allow", default="")
    ap.add_argument("--expect-none", action="store_true")
    a = ap.parse_args()
    allow = set(c for c in a.allow.split(",") if c)
    old, new = load(a.old), load(a.new)
    changes, counts, bad = [], {}, []
    for p in sorted(set(old) | set(new)):
        o, n = old.get(p, ("ABSENT", None)), new.get(p, ("ABSENT", None))
        if o == n:
            continue
        changes.append(p)
        print("%s: %s/%s -> %s/%s" % (p, o[0], o[1], n[0], n[1]))
        k = (o[0], n[0]); counts[k] = counts.get(k, 0) + 1
        if allow and o[1] not in allow and n[1] not in allow:
            bad.append(p)
    print("changed files: %d of %d" % (len(changes), len(set(old) | set(new))))
    for (o, n), c in sorted(counts.items()):
        print("  %s -> %s: %d" % (o, n, c))
    if a.expect_none and changes:
        print("rec_diff: FAIL (--expect-none, %d changes)" % len(changes)); return 1
    if bad:
        print("rec_diff: FAIL (%d changes not explained by --allow %s)" % (len(bad), ",".join(sorted(allow)))); return 1
    print("rec_diff: OK"); return 0


if __name__ == "__main__":
    sys.exit(main())
