#!/usr/bin/env python3
"""PHASE_3 11.2: fit the V2 (SAME_CONTENT) thresholds on fold 0 of the labelled pairs, report on fold 1 (REVIEW_2 F-03).

  python3 -I tools/v2eval/calibrate.py --pairs pairs.jsonl [--drive-pairs drive.jsonl] --out thresholds.json --report report.md

Input lines come from `V2Cli scan ... --pairs-out` (PairsDump). Python stdlib only. Runtime never reads the output:
chosen values are copied by hand into GraphConfig (P3-7).
"""
import argparse
import hashlib
import json
import sys

SAME = {"COMPRESSED_FROM", "THUMBNAIL_OF", "VERSION_OF", "EXACT_DUPLICATE"}
INIT = {"T_SAME_HAM": 6, "V2_SSIM_MEAN": 0.985, "V2_SSIM_MIN": 0.90, "V2_DIFF_MAX": 6.0, "V2_CHROMA": 2.5,
        "V2_FINE_SSIM_MIN": 0.93, "V2_FINE_DIFF_MAX": 9.5}
# (metric, kind) kind: lo = metric must be >= threshold, hi = metric must be <= threshold; step; band-A only
PARAMS = {"T_SAME_HAM": ("hamming", "hi", 1, False), "V2_SSIM_MEAN": ("ssim64Mean", "lo", 0.005, False),
          "V2_SSIM_MIN": ("ssim64Min", "lo", 0.005, False), "V2_DIFF_MAX": ("diff64Max", "hi", 0.25, False),
          "V2_CHROMA": ("chroma16", "hi", 0.25, False), "V2_FINE_SSIM_MIN": ("ssimFineMin", "lo", 0.005, True),
          "V2_FINE_DIFF_MAX": ("diffFineMax", "hi", 0.25, True)}


def fold(root):
    return hashlib.sha256(root.encode("utf-8")).digest()[0] & 1


def klass(p):
    lab = p.get("label")
    if p.get("hnGroup"):
        return "NEG"
    if lab in SAME:
        return "POS_SAME"
    if lab == "EDITED_FROM":
        return "POS_EDIT"
    if lab == "VISUALLY_SIMILAR":
        return "POS_SCENE"
    if lab in ("RANDOM", "CROSS_SECOND"):
        return "NEG"
    return None


def get(p, k):
    if k == "hamming":
        return p.get("hamming")
    return (p.get("metrics") or {}).get(k)


def same(p, th):
    m = p.get("metrics")
    if not m or p.get("transform") or m.get("ssim64Mean") is None or p.get("hamming", -1) < 0:
        return False
    band_a = m.get("band") == 1
    for name, (k, kind, _, a_only) in PARAMS.items():
        if a_only and not band_a:
            continue
        v = get(p, k)
        if v is None:
            return False
        if kind == "lo" and v < th[name]:
            return False
        if kind == "hi" and v > th[name]:
            return False
    if not band_a:   # band B: the smaller image must be in a thumbnail location
        small_b = (p.get("pixelsB") or 0) <= (p.get("pixelsA") or 0)
        return bool(p.get("thumbB") if small_b else p.get("thumbA"))
    return True


def score(pairs, th):
    bad = sum(1 for p in pairs if p["cls"] in ("NEG", "POS_EDIT", "POS_SCENE") and same(p, th))
    pos = [p for p in pairs if p["cls"] == "POS_SAME"]
    rec = sum(1 for p in pos if same(p, th)) / len(pos) if pos else 0.0
    return bad, rec


def fit(pairs, th):
    th = dict(th)
    for _ in range(3):
        for name, (k, kind, step, _) in PARAMS.items():
            vals = sorted({get(p, k) for p in pairs if get(p, k) is not None})
            if not vals:
                continue
            lo, hi = min(vals), max(vals)
            grid, x = [], lo - step
            while x <= hi + step:
                grid.append(round(x, 6))
                x += step
            best, feas = None, []
            for g in grid:
                t2 = dict(th); t2[name] = g
                bad, rec = score(pairs, t2)
                if bad:
                    continue
                feas.append((rec, g))
            if not feas:
                continue
            top = max(r for r, _ in feas)
            gs = sorted(g for r, g in feas if r == top)
            # tight end = just admits the max-recall positives; then relax by up to 2 steps towards the
            # nearest negative (half the gap at most), so a threshold never drifts into empty space (P3-7 margin)
            tight, loose = (gs[-1], gs[0]) if kind == "lo" else (gs[0], gs[-1])
            gap = abs(loose - tight)
            move = min(2 * step, gap / 2)
            th[name] = round(tight - move if kind == "lo" else tight + move, 6)
            init = INIT[name]   # never tighter than the reviewed initial value while that value is still feasible
            if (kind == "lo" and loose <= init < th[name]) or (kind == "hi" and th[name] < init <= loose):
                th[name] = init
            if score(pairs, th)[0]:
                th[name] = tight
    return th


def report_rows(pairs, th, title):
    out = ["### " + title, "", "| class | n | SAME_CONTENT |", "|---|---|---|"]
    for c in ("POS_SAME", "POS_EDIT", "POS_SCENE", "NEG"):
        sel = [p for p in pairs if p["cls"] == c]
        out.append("| %s | %d | %d |" % (c, len(sel), sum(1 for p in sel if same(p, th))))
    by = {}
    for p in pairs:
        if p["cls"] == "POS_SAME":
            k = p.get("label")
            by.setdefault(k, [0, 0]); by[k][0] += 1; by[k][1] += same(p, th)
    for k, (n, s) in sorted(by.items()):
        out.append("| recall %s | %d | %d (%.3f) |" % (k, n, s, s / n))
    gen = [p for p in pairs if p["cls"] in ("POS_SAME", "POS_EDIT", "POS_SCENE")]
    out.append("| candidate recall (labelled pairs generated) | %d | %d |" % (len(gen), sum(1 for p in gen if p.get("generated"))))
    return out + [""]


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairs", required=True)
    ap.add_argument("--drive-pairs")
    ap.add_argument("--out", required=True)
    ap.add_argument("--report", required=True)
    a = ap.parse_args(argv)
    pairs = []
    for line in open(a.pairs, encoding="utf-8"):
        p = json.loads(line)
        p["cls"] = klass(p)
        if p["cls"] is None:
            continue
        p["fold"] = fold(p.get("root") or min(p["a"], p["b"]))
        pairs.append(p)
    f0 = [p for p in pairs if p["fold"] == 0]
    f1 = [p for p in pairs if p["fold"] == 1]
    th = fit(f0, INIT)
    bad0, rec0 = score(f0, th)
    bad1, rec1 = score(f1, th)
    badall, _ = score(pairs, th)
    drive_bad = None
    if a.drive_pairs:
        dp = [json.loads(l) for l in open(a.drive_pairs, encoding="utf-8")]
        for p in dp:
            p["cls"] = "NEG" if p.get("label") == "CROSS_SECOND" else None
        drive_bad = sum(1 for p in dp if p["cls"] == "NEG" and same(p, th))
    status = 0
    if rec0 == 0 or badall or (drive_bad or 0):
        status = 1
    rep = ["# Phase 3 calibration report", "",
           "Fit on fold 0 (%d pairs), held-out fold 1 (%d pairs)." % (len(f0), len(f1)), "",
           "| threshold | initial | fitted |", "|---|---|---|"]
    rep += ["| %s | %s | %s |" % (k, INIT[k], th[k]) for k in INIT]
    rep += ["", "fold 0: false SAME %d, POS_SAME recall %.4f; fold 1: false SAME %d, POS_SAME recall %.4f; all pairs false SAME %d%s"
            % (bad0, rec0, bad1, rec1, badall, "" if drive_bad is None else "; Drive cross-second SAME %d" % drive_bad), ""]
    if rec0 - rec1 > 0.05:
        rep += ["**over-fitting flag: fold-1 recall more than 0.05 below fold 0**", ""]
    rep += report_rows(f0, th, "fold 0 (fit)") + report_rows(f1, th, "fold 1 (held out)")
    json.dump({"thresholds": th, "fold0": {"falseSame": bad0, "recall": rec0}, "fold1": {"falseSame": bad1, "recall": rec1},
               "allFalseSame": badall, "driveCrossSecondSame": drive_bad}, open(a.out, "w"), indent=1, sort_keys=True)
    open(a.report, "w", encoding="utf-8").write("\n".join(rep) + "\n")
    print("\n".join(rep))
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
