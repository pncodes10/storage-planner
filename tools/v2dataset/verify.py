#!/usr/bin/env python3
"""Verify a built dataset against its manifest.

    python3 tools/v2dataset/verify.py --root /home/user/sp-dataset [--fast]

Checks: every entry exists (symlinks as symlinks) with matching size+sha256
and mtime, every relationship target exists in the manifest, schema values are
valid, must_never_delete files are not in forbidden tiers, every scenario has
>= 3 instances, sidecars exist, and lists any untracked files on disk.
Exit status 0 = pass.  (Run before mutate.py: S12 mutations intentionally break hashes.)
"""
import argparse
import hashlib
import json
import os
import sys

TIERS = {"SAFE", "NO_LOSS", "YOUR_CALL", "REVIEW", "PROTECTED", "KEEP"}
RELS = {"EXACT_DUPLICATE", "VISUALLY_SIMILAR", "EDITED_FROM", "COMPRESSED_FROM", "BACKED_UP_BY",
        "CONTAINED_IN", "COMPANION_OF", "VERSION_OF", "THUMBNAIL_OF"}
IMP = {"critical", "high", "medium", "low", "disposable"}
META = {"manifest.json", "scenarios.json", ".v2dataset", "installed_apps.json", "mutations.json",
        "pdf_passwords.json", "backup_manifest.json", "mutate.py", ".rebuilt_ok"}


def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def hash_collision_problems(files):
    """Identical content must be declared: files sharing a sha256 must be linked by
    EXACT_DUPLICATE edges (directly or via a common original), and no two members
    of one hard_negative_group may share a sha256."""
    parent = {}

    def find(x):
        while parent.setdefault(x, x) != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    for e in files:
        for r in e["relationships"]:
            if r["type"] == "EXACT_DUPLICATE":
                parent[find(e["path"])] = find(r["target"])
    by_hash, out = {}, []
    for e in files:
        if e.get("sha256"):
            by_hash.setdefault(e["sha256"], []).append(e)
    for h, grp in sorted(by_hash.items()):
        for i, x in enumerate(grp):
            for y in grp[i + 1:]:
                if find(x["path"]) != find(y["path"]):
                    out.append(f"identical sha256 without EXACT_DUPLICATE link: {x['path']} == {y['path']}")
                hg = x.get("hard_negative_group")
                if hg and hg == y.get("hard_negative_group"):
                    out.append(f"hard_negative_group {hg} members identical: {x['path']} == {y['path']}")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="/home/user/sp-dataset")
    ap.add_argument("--fast", action="store_true", help="size only, skip sha256")
    a = ap.parse_args()
    m = json.load(open(os.path.join(a.root, "manifest.json"), encoding="utf-8"))
    errs = []
    paths = {e["path"] for e in m["files"]}
    if len(paths) != len(m["files"]):
        errs.append("duplicate paths in manifest")
    for e in m["files"]:
        p = os.path.join(a.root, e["path"])
        if e.get("symlink_target") is not None:
            if not os.path.islink(p):
                errs.append(f"not a symlink: {e['path']}")
            elif os.readlink(p) != e["symlink_target"]:
                errs.append(f"symlink target mismatch: {e['path']}")
        elif not os.path.isfile(p) or os.path.islink(p):
            errs.append(f"missing: {e['path']}")
            continue
        else:
            st = os.stat(p)
            if st.st_size != e["size"]:
                errs.append(f"size mismatch: {e['path']}")
            elif not a.fast and sha(p) != e["sha256"]:
                errs.append(f"sha256 mismatch: {e['path']}")
            if int(st.st_mtime) != e["mtime"]:
                errs.append(f"mtime mismatch: {e['path']}")
        if e["expected_tier"] not in TIERS or not set(e["acceptable_tiers"]) <= TIERS:
            errs.append(f"bad tier: {e['path']}")
        if e["importance"] not in IMP:
            errs.append(f"bad importance: {e['path']}")
        if e["must_never_delete"] and set(e["acceptable_tiers"]) & {"SAFE", "NO_LOSS", "YOUR_CALL"}:
            errs.append(f"must_never_delete with deletable tier: {e['path']}")
        if not e.get("source") and not e["synthetic"]:
            errs.append(f"real file without source: {e['path']}")
        for r in e["relationships"]:
            if r["type"] not in RELS:
                errs.append(f"bad relationship type {r['type']}: {e['path']}")
            if r["target"] not in paths:
                errs.append(f"relationship target missing: {e['path']} -> {r['target']}")
            elif not os.path.lexists(os.path.join(a.root, r["target"])):
                errs.append(f"relationship target not on disk: {r['target']}")
    by = {e["path"]: e for e in m["files"]}
    for e in m["files"]:
        for r in e["relationships"]:
            if r["type"] == "EXACT_DUPLICATE" and r["target"] in by and by[r["target"]]["sha256"] != e["sha256"]:
                errs.append(f"EXACT_DUPLICATE hash differs: {e['path']}")
    errs += hash_collision_problems(m["files"])
    sc = json.load(open(os.path.join(a.root, "scenarios.json"), encoding="utf-8"))
    for s in sc["scenarios"]:
        if s["instances"] < 3:
            errs.append(f"scenario {s['id']} has only {s['instances']} instances")
        for f in s["files"]:
            if f not in paths:
                errs.append(f"scenario {s['id']} file not in manifest: {f}")
    for side in m.get("sidecars", []):
        if not os.path.exists(os.path.join(a.root, side)):
            errs.append(f"sidecar missing: {side}")
    # untracked files
    untracked = []
    for dp, dn, fn in os.walk(a.root, followlinks=False):
        for n in fn + [d for d in dn if os.path.islink(os.path.join(dp, d))]:
            rel = os.path.relpath(os.path.join(dp, n), a.root)
            if rel not in paths and rel not in META:
                untracked.append(rel)
    if untracked:
        errs.append(f"{len(untracked)} untracked files, e.g. {untracked[:5]}")
    print(f"{len(m['files'])} entries checked; {len(errs)} problems")
    for x in errs[:50]:
        print("  ", x)
    sys.exit(1 if errs else 0)


if __name__ == "__main__":
    main()
