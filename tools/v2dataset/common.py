"""Shared helpers: deterministic RNG, dates/filenames, the Builder that writes
files and records ground truth for manifest.json."""
import datetime as _dt
import hashlib
import io
import json
import os
import random
import shutil
import zipfile

from PIL import Image

TIERS = ["SAFE", "NO_LOSS", "YOUR_CALL", "REVIEW", "PROTECTED", "KEEP"]
REL_TYPES = {"EXACT_DUPLICATE", "VISUALLY_SIMILAR", "EDITED_FROM", "COMPRESSED_FROM", "BACKED_UP_BY",
             "CONTAINED_IN", "COMPANION_OF", "VERSION_OF", "THUMBNAIL_OF"}
IMPORTANCE = ["critical", "high", "medium", "low", "disposable"]
# Tiers a scorer must never see for a must_never_delete file.
FORBIDDEN_IF_MND = ["SAFE", "NO_LOSS", "YOUR_CALL"]

IST = _dt.timezone(_dt.timedelta(hours=5, minutes=30))
START = _dt.datetime(2021, 1, 4, tzinfo=IST)
END = _dt.datetime(2024, 12, 28, tzinfo=IST)
FIXED_ZIP_TIME = (2024, 1, 1, 0, 0, 0)


def rng(*key):
    return random.Random(":".join(str(k) for k in key))


def rand_dt(*key, start=START, end=END, daytime=True):
    r = rng("dt", *key)
    span = int((end - start).total_seconds())
    d = start + _dt.timedelta(seconds=r.randrange(span))
    if daytime:
        d = d.replace(hour=r.randint(7, 22))
    return d


def cam_name(d, prefix="IMG", ext="jpg"):
    return f"{prefix}_{d:%Y%m%d_%H%M%S}.{ext}"


def pxl_name(d, ext="jpg"):
    return f"PXL_{d:%Y%m%d_%H%M%S}{d.microsecond // 1000:03d}.{ext}"


def wa_name(d, n, kind="IMG", ext="jpg"):
    return f"{kind}-{d:%Y%m%d}-WA{n:04d}.{ext}"


def shot_name(d, app=None, ext="png"):
    return f"Screenshot_{d:%Y%m%d-%H%M%S}" + (f"_{app}" if app else "") + f".{ext}"


class Builder:
    def __init__(self, out, cache, sources, quick=False):
        self.out = out
        self.cache = cache
        self.src = sources
        self.quick = quick
        self.entries = {}
        self.extra = {}  # sidecar json files written at the end
        self._wa_counter = {}

    # ---------- paths ----------
    def abs(self, rel):
        return os.path.join(self.out, rel)

    def unique(self, rel):
        """Bump a timestamped filename until it is unused."""
        if rel not in self.entries and not os.path.lexists(self.abs(rel)):
            return rel
        base, ext = os.path.splitext(rel)
        i = 1
        while True:
            cand = f"{base}({i}){ext}"
            if cand not in self.entries and not os.path.lexists(self.abs(cand)):
                return cand
            i += 1

    def wa_next(self, d, kind="IMG"):
        k = (d.strftime("%Y%m%d"), kind)
        self._wa_counter[k] = self._wa_counter.get(k, -1) + 1
        return self._wa_counter[k]

    def _prep(self, rel):
        p = self.abs(rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        return p

    # ---------- writers ----------
    def write_bytes(self, rel, data, **meta):
        p = self._prep(rel)
        with open(p, "wb") as f:
            f.write(data)
        return self.record(rel, **meta)

    def copy(self, rel, src_abs, **meta):
        p = self._prep(rel)
        shutil.copyfile(src_abs, p)
        return self.record(rel, **meta)

    def save_image(self, rel, img, fmt=None, quality=90, exif=None, **meta):
        p = self._prep(rel)
        fmt = fmt or {"jpg": "JPEG", "jpeg": "JPEG", "png": "PNG", "webp": "WEBP",
                      "gif": "GIF", "bmp": "BMP", "heic": "HEIF"}[rel.rsplit(".", 1)[-1].lower()]
        kw = {}
        if fmt == "JPEG":
            img = img.convert("RGB")
            kw = dict(quality=quality, optimize=False)
            if exif:
                kw["exif"] = exif
        elif fmt == "WEBP":
            kw = dict(quality=quality, method=4)
        elif fmt == "PNG":
            kw = dict(optimize=False, compress_level=6)
        img.save(p, fmt, **kw)
        return self.record(rel, **meta)

    def image_bytes(self, img, fmt="JPEG", quality=90, exif=None):
        b = io.BytesIO()
        if fmt == "JPEG":
            img = img.convert("RGB")
            img.save(b, fmt, quality=quality, **({"exif": exif} if exif else {}))
        else:
            img.save(b, fmt)
        return b.getvalue()

    def symlink(self, rel, target, **meta):
        p = self._prep(rel)
        if os.path.lexists(p):
            os.remove(p)
        os.symlink(target, p)
        meta.setdefault("category", "symlink")
        return self.record(rel, symlink_target=target, **meta)

    # ---------- ground truth ----------
    def record(self, rel, category, tier="KEEP", importance="medium", mnd=False, scenarios=(),
               rels=(), synthetic=True, source=None, date=None, acceptable=None, notes=None,
               symlink_target=None, hard_negative_group=None, subcategory=None, **extra):
        assert tier in TIERS, tier
        assert importance in IMPORTANCE, importance
        if mnd:
            assert tier not in FORBIDDEN_IF_MND, (rel, tier)
        for r in rels:
            assert r["type"] in REL_TYPES, r
        if date is None:
            date = rand_dt("mtime", rel)
        e = {
            "path": rel,
            "category": category,
            "subcategory": subcategory,
            "scenarios": sorted(set(scenarios)),
            "relationships": list(rels),
            "importance": importance,
            "expected_tier": tier,
            "acceptable_tiers": sorted(set(acceptable or []) | {tier}, key=TIERS.index),
            "must_never_delete": bool(mnd),
            "synthetic": bool(synthetic),
            "source": source,
            "mtime": int(date.timestamp()),
            "mtime_iso": date.isoformat(),
        }
        if hard_negative_group:
            e["hard_negative_group"] = hard_negative_group
        if symlink_target is not None:
            e["symlink_target"] = symlink_target
        if notes:
            e["notes"] = notes
        e.update(extra)
        self.entries[rel] = e
        return rel

    def rel(self, typ, target, **kw):
        d = {"type": typ, "target": target}
        d.update(kw)
        return d

    def add_rel(self, rel_path, typ, target, **kw):
        self.entries[rel_path]["relationships"].append(self.rel(typ, target, **kw))

    def src_meta(self, sid, transform=None):
        s = self.src[sid]
        d = {"id": sid, "url": s["url"], "licence": s["licence"]}
        if transform:
            d["transform"] = transform
        return d

    # ---------- finalize ----------
    def finalize(self):
        from sources import sha256_file
        for rel, e in sorted(self.entries.items()):
            p = self.abs(rel)
            if "symlink_target" in e:
                e["size"] = None
                e["sha256"] = None
                try:
                    os.utime(p, (e["mtime"], e["mtime"]), follow_symlinks=False)
                except (OSError, NotImplementedError):
                    pass
                continue
            e["size"] = os.path.getsize(p)
            e["sha256"] = sha256_file(p)
            os.utime(p, (e["mtime"], e["mtime"]))


def normalize_zip(path, date_time=FIXED_ZIP_TIME):
    """Rewrite an OOXML/zip file with fixed timestamps & order for determinism."""
    with zipfile.ZipFile(path) as z:
        items = [(i, z.read(i.filename)) for i in z.infolist()]
    tmp = path + ".norm"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for info, data in items:
            ni = zipfile.ZipInfo(info.filename, date_time=date_time)
            ni.compress_type = zipfile.ZIP_STORED if info.compress_type == zipfile.ZIP_STORED else zipfile.ZIP_DEFLATED
            ni.external_attr = 0o644 << 16
            z.writestr(ni, data)
    os.replace(tmp, path)


def zip_write(z, name, data, stored=False, date_time=FIXED_ZIP_TIME):
    zi = zipfile.ZipInfo(name, date_time=date_time)
    zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
    zi.external_attr = 0o644 << 16
    z.writestr(zi, data)


def jdump(obj, path):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=1, ensure_ascii=False, sort_keys=False)
        f.write("\n")


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def load_rgb(path, max_side=None):
    im = Image.open(path)
    im.load()
    if im.mode in ("RGBA", "LA", "P"):
        im = im.convert("RGBA")
        bg = Image.new("RGB", im.size, (255, 255, 255))
        bg.paste(im, mask=im.split()[-1])
        im = bg
    im = im.convert("RGB")
    if max_side and max(im.size) > max_side:
        im.thumbnail((max_side, max_side), Image.LANCZOS)
    return im
