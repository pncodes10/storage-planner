#!/usr/bin/env python3
"""Synthetic phone tree for the v2 scale harness (PHASE_S_STORE section 1.1, slice S0).

Usage: gen_synth.py OUT NFILES [--images SHARE] [--seed N]
  PNG images (96x72; 20 % of scenes are bursts of 2-4 near duplicates with timestamped camera names), the rest small
  docs/bins with colliding sizes and 5 % exact duplicates. Deterministic for a given (NFILES, SHARE, SEED).
  Defaults: --images 0.3 --seed 7 (the profile of section 1). Stdlib only; run with python3 -I.
"""
import argparse, os, random, struct, sys, zlib, time

ap = argparse.ArgumentParser()
ap.add_argument("out"); ap.add_argument("n", type=int)
ap.add_argument("--images", type=float, default=0.3); ap.add_argument("--seed", type=int, default=7)
args = ap.parse_args()
out, n, share = args.out, args.n, args.images
rnd = random.Random(args.seed)

def png(w, h, rows):
    raw = b"".join(b"\x00" + bytes(r) for r in rows)
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 0, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b"")

def image(seed, noise):
    r = random.Random(seed)
    w, h = 96, 72
    blocks = [[r.randrange(256) for _ in range(8)] for _ in range(6)]
    rows = []
    for y in range(h):
        row = []
        for x in range(w):
            v = blocks[y * 6 // h][x * 8 // w]
            if noise: v = max(0, min(255, v + rnd.randrange(-noise, noise + 1)))
            row.append(v)
        rows.append(row)
    return png(w, h, rows)

nimg = int(n * share)
base_t = 1672560000  # 2023-01-01 08:00 UTC; fixed so the tree does not depend on the TZ
made = 0
scene = 0
while made < nimg:
    scene += 1
    burst = 1 if rnd.random() < 0.8 else rnd.randrange(2, 5)        # 20% of scenes are bursts of near duplicates
    t = base_t + scene * 600
    d = os.path.join(out, "DCIM", "Camera", "b%03d" % (made // 1000))
    os.makedirs(d, exist_ok=True)
    for k in range(burst):
        if made >= nimg: break
        tt = time.gmtime(t + k * 2)
        name = "IMG_%s_%d.png" % (time.strftime("%Y%m%d_%H%M%S", tt), made)
        p = os.path.join(d, name)
        with open(p, "wb") as f: f.write(image(scene, 0 if k == 0 else 6))
        os.utime(p, (t + k * 2, t + k * 2))
        made += 1

sizes = [rnd.randrange(1, 60000) for _ in range(4000)]
kinds = [("Download", ".pdf"), ("Documents", ".txt"), ("Android/media/com.app%d", ".bin"), ("WhatsApp/Media/WhatsApp Documents", ".docx"), ("Music", ".mp3")]
other = n - nimg
prev = []
for i in range(other):
    top, ext = kinds[i % len(kinds)]
    if "%d" in top: top = top % (i % 50)
    d = os.path.join(out, top, "f%03d" % (i // 2000), "s%02d" % (i % 7))
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, "file_%06d%s" % (i, ext))
    if prev and rnd.random() < 0.05:
        data = rnd.choice(prev)                                     # exact duplicate
    else:
        sz = rnd.choice(sizes) // 8 + 1
        data = rnd.getrandbits(sz * 8).to_bytes(sz, "little")
        if len(prev) < 2000: prev.append(data)
    with open(p, "wb") as f: f.write(data)
print("images", made, "others", other)
