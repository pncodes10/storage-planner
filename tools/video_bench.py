#!/usr/bin/env python3
"""Desktop video-compression benchmark behind docs/video-compression-research.md.

Usage: python3 tools/video_bench.py OUT_DIR clip1.mp4 [clip2.mp4 ...]

Re-encodes each clip with H.264 (x264), HEVC (x265), AV1 (SVT-AV1), a
"hardware-like" HEVC run (fast preset, one-pass VBR at a fixed share of the
source bitrate, no look-ahead or B-frames), denoising and downscaling, then
scores each copy against the clip: Y SSIM and Y PSNR, at the clip's own size
(smaller copies are upscaled the way a player would). Prints one JSON line
per run and writes OUT_DIR/results.json.

Needs ffmpeg/ffprobe with libx264, libx265 and libsvtav1. Desktop encoders
are better than phone hardware encoders, so treat the sizes as a best case;
the phone numbers come from the app's Lab.
"""
import json, os, re, subprocess, sys, time

X265_QUIET = ["-x265-params", "log-level=error"]
AV1_QUIET = ["-svtav1-params", "log=0"]


def runs():
    r = []
    for crf in (23, 26, 29):
        r.append((f"x264 crf{crf}", ["-c:v", "libx264", "-preset", "medium", "-crf", str(crf)]))
    for crf in (24, 28, 32):
        r.append((f"x265 crf{crf}", ["-c:v", "libx265", "-preset", "medium", "-crf", str(crf)] + X265_QUIET))
    for crf in (30, 38, 46):
        r.append((f"av1 crf{crf}", ["-c:v", "libsvtav1", "-preset", "8", "-crf", str(crf)] + AV1_QUIET))
    for share in (0.6, 0.4, 0.25, 0.15):
        r.append((f"hevc-hw-like {int(share * 100)}%", share))
    r.append(("x265 crf28 + denoise", ["-vf", "hqdn3d=2:2:4:4", "-c:v", "libx265", "-preset", "medium", "-crf", "28"] + X265_QUIET))
    r.append(("x265 crf28 720p", ["-vf", "scale=-2:720:flags=lanczos", "-c:v", "libx265", "-preset", "medium", "-crf", "28"] + X265_QUIET))
    r.append(("x265 crf30 540p", ["-vf", "scale=-2:540:flags=lanczos", "-c:v", "libx265", "-preset", "medium", "-crf", "30"] + X265_QUIET))
    r.append(("av1 crf40 720p", ["-vf", "scale=-2:720:flags=lanczos", "-c:v", "libsvtav1", "-preset", "8", "-crf", "40"] + AV1_QUIET))
    return r


def probe(path, entries):
    out = subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "v:0",
                                   "-show_entries", entries, "-of", "csv=p=0", path], text=True)
    return out.strip().split("\n")[0].split(",")


def score(copy, src, w, h):
    lav = (f"[0:v]scale={w}:{h}:flags=bicubic,split[a][b];[1:v]split[c][d];"
           "[a][c]ssim;[b][d]psnr")
    err = subprocess.run(["ffmpeg", "-i", copy, "-i", src, "-lavfi", lav, "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    ssim = float(re.search(r"SSIM Y:([\d.]+)", err).group(1))
    psnr = re.search(r"PSNR y:([\d.]+|inf)", err).group(1)
    return ssim, float(psnr)


def main():
    out_dir, clips = sys.argv[1], sys.argv[2:]
    os.makedirs(out_dir, exist_ok=True)
    results = []
    for src in clips:
        w, h = probe(src, "stream=width,height")
        src_bitrate = int(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=bit_rate",
                                                   "-of", "csv=p=0", src], text=True).strip())
        src_size = os.path.getsize(src)
        for name, how in runs():
            if isinstance(how, float):
                b = int(src_bitrate * how)
                how = ["-c:v", "libx265", "-preset", "ultrafast", "-b:v", str(b), "-maxrate", str(int(b * 1.5)),
                       "-bufsize", str(b * 2), "-x265-params", "log-level=error:rc-lookahead=0:bframes=0"]
            copy = os.path.join(out_dir, "copy.mp4")
            t = time.time()
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src] + how + ["-an", copy], check=True)
            seconds = time.time() - t
            ssim, psnr = score(copy, src, w, h)
            r = dict(clip=os.path.basename(src), run=name,
                     saved_pct=round(100 * (1 - os.path.getsize(copy) / src_size), 1),
                     ssim_y=round(ssim, 4), psnr_y=round(psnr, 2), encode_s=round(seconds, 1))
            print(json.dumps(r), flush=True)
            results.append(r)
            os.remove(copy)
    with open(os.path.join(out_dir, "results.json"), "w") as f:
        json.dump(results, f, indent=1)


if __name__ == "__main__":
    main()
