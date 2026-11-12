#!/usr/bin/env python3
"""Apply S12 mutations (mutations.json) to a built dataset: run AFTER the scan
and BEFORE the execution phase.  Not idempotent — rebuild the dataset to reset.

    python3 mutate.py --root /home/user/sp-dataset
"""
import argparse
import hashlib
import json
import os
import random


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.dirname(os.path.abspath(__file__)))
    a = ap.parse_args()
    spec = json.load(open(os.path.join(a.root, "mutations.json"), encoding="utf-8"))
    for m in spec["mutations"]:
        p = os.path.join(a.root, m["path"])
        op = m["op"]
        if op == "append":
            with open(p, "ab") as f:
                f.write(b"\x00MUTATED-AFTER-SCAN\x00")
        elif op == "delete_target":
            os.remove(os.path.join(a.root, m["target"]))
        elif op == "overwrite_same_size":
            n = os.path.getsize(p)
            r = random.Random(m.get("content_seed", 0))
            data = (b"%PDF-1.4\n% important replacement\n" + bytes(r.randrange(256) for _ in range(n)))[:n]
            st = os.stat(p)
            with open(p, "wb") as f:
                f.write(data)
            os.utime(p, (st.st_atime, st.st_mtime))  # same size AND same mtime: only a hash reveals it
        elif op == "touch":
            st = os.stat(p)
            os.utime(p, (st.st_atime, st.st_mtime + 3600))
        elif op == "rename":
            os.rename(p, os.path.join(a.root, m["new_path"]))
        elif op == "delete":
            os.remove(p)
        else:
            raise SystemExit("unknown op " + op)
        after = hashlib.sha256(open(p, "rb").read()).hexdigest()[:16] if os.path.exists(p) else "-"
        print(f"{op:20s} {m['path']}  sha256[:16] now {after}")


if __name__ == "__main__":
    main()
