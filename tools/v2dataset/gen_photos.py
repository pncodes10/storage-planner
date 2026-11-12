"""Real-image derived content: camera originals, WhatsApp copies, bursts,
meaningful edits, thumbnails, conversions, hard negatives, blurry family photos."""
import datetime
import os

import cv2
import numpy as np
import piexif
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont, ImageOps

import sources
from common import cam_name, load_rgb, pxl_name, rand_dt, rng, wa_name

CAMS = [(b"samsung", b"SM-A515F"), (b"Google", b"Pixel 6a"), (b"Xiaomi", b"Redmi Note 10 Pro"),
        (b"OnePlus", b"CPH2449"), (b"motorola", b"moto g(40) fusion")]
WA = "WhatsApp/Media/WhatsApp Images"
CAM = "DCIM/Camera"


def exif_for(d, key, w, h, gps=False):
    r = rng("exif", key)
    make, model = CAMS[r.randrange(len(CAMS))]
    ts = d.strftime("%Y:%m:%d %H:%M:%S").encode()
    z = {piexif.ImageIFD.Make: make, piexif.ImageIFD.Model: model,
         piexif.ImageIFD.Software: b"v2dataset", piexif.ImageIFD.DateTime: ts}
    ex = {piexif.ExifIFD.DateTimeOriginal: ts, piexif.ExifIFD.DateTimeDigitized: ts,
          piexif.ExifIFD.PixelXDimension: w, piexif.ExifIFD.PixelYDimension: h,
          piexif.ExifIFD.FNumber: (18, 10), piexif.ExifIFD.ISOSpeedRatings: r.choice([50, 100, 200, 400, 800]),
          piexif.ExifIFD.ExposureTime: (1, r.choice([30, 60, 120, 250, 500]))}
    g = {}
    if gps:
        lat, lon = 18.4 + r.random() * 1.2, 72.8 + r.random() * 1.2  # around Mumbai/Pune

        def dms(v):
            dd = int(v); m = int((v - dd) * 60); s = int(((v - dd) * 60 - m) * 6000)
            return ((dd, 1), (m, 1), (s, 100))
        g = {piexif.GPSIFD.GPSLatitudeRef: b"N", piexif.GPSIFD.GPSLatitude: dms(lat),
             piexif.GPSIFD.GPSLongitudeRef: b"E", piexif.GPSIFD.GPSLongitude: dms(lon)}
    return piexif.dump({"0th": z, "Exif": ex, "GPS": g, "1st": {}, "thumbnail": None})


def cameraize(im, max_side=4032):
    if max(im.size) > max_side:
        im = im.copy(); im.thumbnail((max_side, max_side), Image.LANCZOS)
    return im


def wa_compress(im):
    im = im.copy()
    if max(im.size) > 1600:
        im.thumbnail((1600, 1600), Image.LANCZOS)
    return im


def burst_variant(im, key, i):
    r = rng("burst", key, i)
    w, h = im.size
    dx, dy = int(w * r.uniform(-0.025, 0.025)), int(h * r.uniform(-0.025, 0.025))
    m = int(min(w, h) * 0.03)
    box = (m + dx, m + dy, w - m + dx, h - m + dy)
    v = im.crop(box).resize((w, h), Image.LANCZOS)
    v = ImageEnhance.Brightness(v).enhance(r.uniform(0.9, 1.1))
    if r.random() < 0.3:
        v = v.rotate(r.uniform(-1.2, 1.2), resample=Image.BICUBIC)
    return v


def font(B, size, which="sans"):
    p = B.src.get("font_" + which, {}).get("path")
    try:
        f = ImageFont.truetype(p, size)
        if which == "sans":
            try:
                f.set_variation_by_name("Bold")
            except Exception:
                pass
        return f
    except Exception:
        return ImageFont.load_default(size)


def edit(B, im, kind, key):
    r = rng("edit", key, kind)
    w, h = im.size
    if kind == "annotated":
        im = im.copy(); d = ImageDraw.Draw(im)
        cx, cy, rad = int(w * r.uniform(.3, .7)), int(h * r.uniform(.3, .7)), int(min(w, h) * .15)
        d.ellipse((cx - rad, cy - rad, cx + rad, cy + rad), outline=(255, 30, 30), width=max(4, w // 150))
        d.line((cx + rad, cy + rad, cx + 2 * rad, cy + 2 * rad), fill=(255, 30, 30), width=max(4, w // 150))
        d.text((min(cx + 2 * rad, w - w // 3), min(cy + 2 * rad, h - h // 10)),
               r.choice(["this one!", "crack here", "our seats", "look", "meet here"]),
               fill=(255, 30, 30), font=font(B, max(20, h // 18)))
        return im
    if kind == "cropped":
        cw, ch = int(w * r.uniform(.4, .6)), int(h * r.uniform(.4, .6))
        x, y = r.randrange(0, w - cw), r.randrange(0, h - ch)
        return im.crop((x, y, x + cw, y + ch))
    if kind == "text_added":
        im = im.copy(); d = ImageDraw.Draw(im)
        bh = h // 7
        d.rectangle((0, h - bh, w, h), fill=(0, 0, 0))
        d.text((w // 30, h - bh + bh // 5), r.choice(["Happy Birthday Aai!", "Goa trip 2023", "Best day ever",
                                                       "Sunday family lunch", "Graduation day"]),
               fill=(255, 255, 255), font=font(B, bh // 2))
        return im
    if kind == "filtered":
        k = r.choice(["sepia", "bw", "vivid"])
        if k == "bw":
            return ImageOps.grayscale(im).convert("RGB")
        if k == "vivid":
            return ImageEnhance.Contrast(ImageEnhance.Color(im).enhance(1.8)).enhance(1.2)
        g = ImageOps.grayscale(im)
        return ImageOps.colorize(g, (40, 20, 0), (255, 235, 200))
    raise ValueError(kind)


def collage(ims, size=1600):
    c = Image.new("RGB", (size, size), (250, 250, 250))
    s = size // 2 - 12
    for i, im in enumerate(ims[:4]):
        t = ImageOps.fit(im, (s, s), Image.LANCZOS)
        c.paste(t, (8 + (i % 2) * (s + 16), 8 + (i // 2) * (s + 16)))
    return c


def blur_family(im, key):
    r = rng("blur", key)
    a = np.asarray(im).astype(np.float32)
    if r.random() < 0.5:
        k = r.randint(15, 31); ker = np.zeros((k, k), np.float32); ker[k // 2, :] = 1.0 / k
        M = cv2.getRotationMatrix2D((k / 2, k / 2), r.uniform(0, 180), 1)
        ker = cv2.warpAffine(ker, M, (k, k)); ker /= ker.sum()
        a = cv2.filter2D(a, -1, ker)
    else:
        a = cv2.GaussianBlur(a, (0, 0), r.uniform(4, 8))
    a = a * r.uniform(0.45, 0.8) + np.random.default_rng(r.randrange(1 << 30)).normal(0, 6, a.shape)
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def build(B):
    S = B.src
    stats = {}
    ids = sorted(S)
    docci = [i for i in ids if i.startswith("docci_")]
    people = [i for i in ids if S[i].get("cat") == "people" and S[i]["kind"] == "photo"]
    pets = [i for i in ids if S[i].get("cat") == "pets"]
    food = [i for i in ids if S[i].get("cat") == "food" and S[i]["kind"] == "photo"]
    scenery = [i for i in ids if S[i].get("cat") == "travel_scenery"]
    product = [i for i in ids if S[i].get("cat") == "product" and S[i]["kind"] == "photo"]
    misc = [i for i in ids if S[i].get("cat") in ("unknown", "personal_photo") and S[i]["kind"] == "photo"
            and not i.startswith("docci_")]
    if B.quick:
        docci = docci[:30]

    cache = {}

    def img(sid, max_side=4032):
        k = (sid, max_side)
        if k not in cache:
            cache.clear()
            cache[k] = load_rgb(S[sid]["path"], max_side)
        return cache[k]

    originals = []  # (rel, sid) camera originals usable for thumbnails/dups

    def camera(sid, cat, key, importance="medium", tier="KEEP", mnd=False, scen=(), im=None,
               transform="re-encoded as camera JPEG with synthetic EXIF", gps=False, pixel=False,
               acceptable=None, folder=CAM, notes=None):
        d = rand_dt("cam", key)
        im = im if im is not None else cameraize(img(sid))
        rel = B.unique(f"{folder}/" + (pxl_name(d) if pixel else cam_name(d)))
        ex = exif_for(d, key, im.size[0], im.size[1], gps)
        B.save_image(rel, im, quality=92, exif=ex, category=cat, importance=importance, tier=tier, mnd=mnd,
                     scenarios=scen, synthetic=False, source=B.src_meta(sid, transform), date=d,
                     acceptable=acceptable, notes=notes)
        return rel, d

    def wa_copy(orig_rel, im, d0, key, sent, cat, tier, importance, mnd, scen, sid, acceptable=None, notes=None):
        d = rand_dt("wa", key, start=d0, end=d0 + datetime.timedelta(days=20))
        n = B.wa_next(d)
        folder = f"{WA}/Sent" if sent else WA
        rel = B.unique(f"{folder}/" + wa_name(d, n))
        rels = [B.rel("COMPRESSED_FROM", orig_rel, transform="whatsapp: long edge 1600, JPEG q75, EXIF stripped")] if orig_rel else []
        B.save_image(rel, wa_compress(im), quality=75, category=cat, tier=tier, importance=importance, mnd=mnd,
                     scenarios=scen, rels=rels, synthetic=False,
                     source=B.src_meta(sid, "whatsapp-style recompression (1600px, q75, no EXIF)"), date=d,
                     acceptable=acceptable, notes=notes)
        return rel

    # 1. WhatsApp copies where the camera original still exists (S05 negative / S03-like)
    for i, sid in enumerate(docci[:30]):
        rel, d = camera(sid, "personal_photo", sid, gps=i % 3 == 0, pixel=i % 2 == 0)
        originals.append((rel, sid))
        wa_copy(rel, img(sid), d, sid, sent=True, cat="personal_photo", tier="SAFE", importance="low",
                mnd=False, scen=["S05"], sid=sid, acceptable=["YOUR_CALL"],
                notes="WhatsApp-sent copy; full-quality camera original still present")
    # 2. WhatsApp copy is the ONLY copy (S05)
    wa_only = docci[30:45] + people[:6]
    for sid in wa_only:
        cat = "people_family" if sid in people else "personal_photo"
        wa_copy(None, cameraize(img(sid)), rand_dt("waonly", sid), sid, sent=False, cat=cat, tier="PROTECTED",
                importance="high", mnd=True, scen=["S05"], sid=sid, acceptable=["KEEP", "REVIEW"],
                notes="WhatsApp copy whose camera original no longer exists; unique")
    # 3. Bursts / near-duplicates (VISUALLY_SIMILAR); cover KEEP, rest REVIEW
    for g, sid in enumerate(docci[45:60]):
        base = cameraize(img(sid), 3000)
        d = rand_dt("burst", sid)
        cover = None
        n = 3 + g % 4
        for k in range(n):
            dk = d.replace(microsecond=0) + datetime.timedelta(milliseconds=350 * k)
            tag = "_COVER" if k == 0 else ""
            rel = B.unique(f"{CAM}/IMG_{dk:%Y%m%d_%H%M%S}_BURST{k + 1:03d}{tag}.jpg")
            im = base if k == 0 else burst_variant(base, sid, k)
            B.save_image(rel, im, quality=90, exif=exif_for(dk, sid + str(k), *im.size),
                         category="personal_photo", subcategory="burst",
                         tier="KEEP" if k == 0 else "REVIEW", acceptable=None if k == 0 else ["YOUR_CALL"],
                         importance="medium" if k == 0 else "low",
                         rels=[] if k == 0 else [B.rel("VISUALLY_SIMILAR", cover, transform="burst frame: small shift/crop/exposure")],
                         synthetic=False, source=B.src_meta(sid, "burst variant (shift/crop/exposure)"), date=dk)
            cover = cover or rel
    # 4. Meaningful edits (S04): not interchangeable with the original
    kinds = ["annotated", "cropped", "text_added", "filtered"]
    edit_bases = docci[60:75] + people[6:10] + pets[:4]
    for i, sid in enumerate(edit_bases):
        cat = "people_family" if sid in people else "pets" if sid in pets else "personal_photo"
        rel, d = camera(sid, cat, "edit" + sid, gps=False, pixel=i % 2 == 1)
        originals.append((rel, sid))
        for kind in ([kinds[i % 4]] + ([kinds[(i + 1) % 4]] if i % 3 == 0 else [])):
            e = edit(B, cameraize(img(sid), 3000), kind, sid)
            de = d + datetime.timedelta(minutes=rng("ed", sid, kind).randint(2, 600))
            folder = {"annotated": "Pictures/Markup", "cropped": CAM, "text_added": "Pictures/Snapseed",
                      "filtered": "Pictures/Edited"}[kind]
            base = os.path.basename(rel).rsplit(".", 1)[0]
            erel = B.unique(f"{folder}/{base}~2.jpg" if kind == "cropped" else f"{folder}/{base}_{kind}.jpg")
            B.save_image(erel, e, quality=90, category=cat, subcategory="meaningful_edit", tier="KEEP",
                         acceptable=["REVIEW"], importance="medium", scenarios=["S04"],
                         rels=[B.rel("EDITED_FROM", rel, transform=kind)], synthetic=False,
                         source=B.src_meta(sid, f"meaningful edit: {kind}"), date=de,
                         notes="near-duplicate with a meaningful edit; NOT interchangeable with original")
    # collages
    for c in range(4):
        members = [o for o in originals[c * 4 + 2:(c + 1) * 4 + 2]]
        if len(members) < 4:
            break
        ims = [load_rgb(B.abs(m[0]), 1200) for m in members]
        d = rand_dt("collage", c)
        rel = B.unique(f"Pictures/Collage/collage_{d:%Y%m%d_%H%M%S}.jpg")
        B.save_image(rel, collage(ims), quality=90, category="personal_photo", subcategory="collage",
                     tier="KEEP", acceptable=["REVIEW"], importance="medium", scenarios=["S04"],
                     rels=[B.rel("EDITED_FROM", m[0], transform="collage member") for m in members],
                     synthetic=False, source={"id": "derived", "licence": "derived from listed members",
                                               "members": [m[1] for m in members]}, date=d)
    # 5. Plain camera originals of everything else; some exact duplicates
    plain = docci[75:] + people[10:] + pets[4:] + food + scenery + misc
    for i, sid in enumerate(plain):
        cat = {"people": "people_family", "pets": "pets", "food": "food", "travel_scenery": "travel_scenery",
               "unknown": "unknown", "personal_photo": "personal_photo"}.get(S[sid].get("cat"), "personal_photo")
        if sid.startswith("veg_"):
            # small dataset images: WhatsApp-received food pictures
            d = rand_dt("veg", sid)
            rel = B.unique(f"{WA}/" + wa_name(d, B.wa_next(d)))
            B.save_image(rel, img(sid), quality=80, category="food", tier="KEEP", acceptable=["YOUR_CALL"],
                         importance="low", synthetic=False, source=B.src_meta(sid, "re-encoded q80"), date=d)
            continue
        imp = "high" if cat == "people_family" else "medium"
        rel, d = camera(sid, cat, "plain" + sid, importance=imp, pixel=i % 3 == 0, gps=i % 4 == 0)
        originals.append((rel, sid))
    # Exact duplicates (S03): byte-identical copies, original preserved
    dup_targets = originals[::4][:30]
    for j, (rel, sid) in enumerate(dup_targets):
        r = rng("dup", rel)
        d0 = rand_dt("dupd", rel)
        folder = r.choice(["Pictures/Saved", "Download", "DCIM/Camera", "Pictures/Gallery/Owner/Family",
                           "Documents/Photos backup"])
        name = os.path.basename(rel)
        if folder == "DCIM/Camera":
            name = name.replace(".jpg", "(1).jpg")
        elif folder == "Download":
            name = f"image ({r.randint(1, 9)}).jpg"
        drel = B.unique(f"{folder}/{name}")
        e = B.entries[rel]
        B.copy(drel, B.abs(rel), category=e["category"], tier="SAFE", importance="disposable",
               scenarios=["S03"], rels=[B.rel("EXACT_DUPLICATE", rel)], synthetic=False, source=e["source"],
               date=d0, notes="byte-identical copy; original kept at target")
        B.entries[rel]["scenarios"] = sorted(set(B.entries[rel]["scenarios"]) | {"S03"})
    # 6. Thumbnails, resized copies, format conversions, rotated/mirrored
    for j, (rel, sid) in enumerate(originals[:80]):
        im = load_rgb(B.abs(rel), 320)
        trel = f"DCIM/.thumbnails/{1700000000 + j * 7919}.jpg"
        B.save_image(trel, im, quality=70, category="thumbnail_cache", tier="SAFE", importance="disposable",
                     rels=[B.rel("THUMBNAIL_OF", rel)], synthetic=False, source=B.entries[rel]["source"])
    conv = originals[1::5][:36]
    for j, (rel, sid) in enumerate(conv):
        e = B.entries[rel]; base = os.path.basename(rel).rsplit(".", 1)[0]
        kind = ["resized", "png", "webp", "rotated", "mirrored", "resized"][j % 6]
        im = load_rgb(B.abs(rel))
        common = dict(category=e["category"], importance="low", synthetic=False)
        if kind == "resized":
            im.thumbnail((1024, 1024), Image.LANCZOS)
            B.save_image(B.unique(f"Pictures/Resized/{base}_1024.jpg"), im, quality=85, tier="SAFE",
                         acceptable=["YOUR_CALL"], rels=[B.rel("COMPRESSED_FROM", rel, transform="resize 1024px")],
                         source=dict(e["source"], transform="resized copy"), **common)
        elif kind in ("png", "webp"):
            im.thumbnail((2048, 2048), Image.LANCZOS)
            B.save_image(B.unique(f"Pictures/Converted/{base}.{kind}"), im, quality=80, tier="SAFE",
                         acceptable=["YOUR_CALL", "REVIEW"],
                         rels=[B.rel("VERSION_OF", rel, transform=f"format conversion jpeg->{kind}")],
                         source=dict(e["source"], transform=f"converted to {kind}"), **common)
        else:
            im2 = im.rotate(90, expand=True) if kind == "rotated" else ImageOps.mirror(im)
            B.save_image(B.unique(f"Pictures/Edited/{base}_{kind}.jpg"), im2, quality=90, tier="REVIEW",
                         acceptable=["SAFE", "YOUR_CALL"],
                         rels=[B.rel("EDITED_FROM", rel, transform=kind)],
                         source=dict(e["source"], transform=kind), **common)
    # PNG screenshot/source converted to JPEG (lossy) -- PNG original kept
    # 7. Hard negatives: different photos of the same scene (must not be dup-matched)
    hn = [["genai_times_square", "genai_times_square2", "genai_times_square3"],
          ["genai_sky", "genai_sky2", "genai_sky3"], ["genai_forest_1", "genai_forest_2"],
          ["genai_river_1", "genai_river_2"], ["genai_blocks_1", "genai_blocks_2"],
          ["genai_basketball_start", "genai_basketball_end"], ["genai_teacup_1", "genai_teacup_2"],
          ["genai_dog_1", "genai_dog_2"], ["genai_dog_ad_1", "genai_dog_ad_2", "genai_dog_ad_3"]]
    for g, grp in enumerate(hn):
        for sid in grp:
            for rel, e in B.entries.items():
                if e.get("source") and e["source"].get("id") == sid and not e["relationships"] \
                        and e["path"].startswith(CAM):
                    e["hard_negative_group"] = f"HN_scene_{g:02d}"
                    e["notes"] = "different photo of the same scene as other members of hard_negative_group"
                    break
            else:
                if sid in S:
                    rel, _ = camera(sid, "travel_scenery", "hn" + sid)
                    B.entries[rel]["hard_negative_group"] = f"HN_scene_{g:02d}"
    # 8. Blurry but important family photographs, only copy (S01)
    fam = [s for s in ["mp_portrait", "facerec_two_people", "genai_woman", "vision_faces", "mp_hand_woman_man",
                       "genai_a_man_and_a_dog", "mp_man_woman_okay", "facerec_obama", "vision_carnaval"] if s in S]
    for i, sid in enumerate(fam):
        im = blur_family(cameraize(img(sid), 2400), sid)
        rel, _ = camera(sid, "people_family", "blur" + sid, importance="critical", tier="PROTECTED", mnd=True,
                        scen=["S01"], im=im, acceptable=["KEEP", "REVIEW"],
                        transform="motion/gaussian blur + low light (simulated bad capture)",
                        notes="blurry/low-light but the only copy of a family moment; must not be suggested as junk",
                        folder=CAM if i % 3 else "DCIM/Camera")
        B.entries[rel]["start_date_old"] = True
    # Distractors: genuinely useless blurry shots (pocket shots) — synthetic
    for k in range(8):
        r = rng("pocket", k)
        a = np.random.default_rng(k).normal(r.uniform(5, 40), 8, (1500, 2000, 3))
        im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(25))
        d = rand_dt("pocket", k)
        B.save_image(B.unique(f"{CAM}/{cam_name(d)}"), im, quality=85, exif=exif_for(d, "p" + str(k), 2000, 1500),
                     category="unknown", subcategory="accidental_pocket_shot", tier="YOUR_CALL",
                     acceptable=["SAFE", "REVIEW"], importance="disposable", synthetic=True,
                     source={"id": "synthetic", "licence": "generated (CC0)"}, date=d,
                     notes="near-black blurred accidental shot")
    # 9. Flowers (TF flower_photos): received/old/social media pictures
    fl = sources.flowers(B.cache)
    r = rng("flowers")
    fl = r.sample(fl, min(len(fl), 300 if B.quick else 900))
    fsrc = lambda relp, att, t: {"id": "tf_flowers:" + relp, "url": S["flowers_tgz"]["url"], "licence":
                                  "CC BY 2.0 — " + att, "transform": t}
    fdirs = [(WA, 0.35), ("Android/media/org.telegram.messenger/Telegram/Telegram Images", 0.12),
             ("Pictures/Instagram", 0.12), (CAM, 0.25), ("Download", 0.06), ("Pictures/Facebook", 0.10)]
    wa_recv = []
    for k, (relp, absp, att) in enumerate(fl):
        x = r.random(); acc = 0
        for folder, p in fdirs:
            acc += p
            if x < acc:
                break
        d = rand_dt("fl", relp)
        im = load_rgb(absp)
        if folder == WA:
            rel = B.unique(f"{WA}/" + wa_name(d, B.wa_next(d)))
            B.copy(rel, absp, category="travel_scenery", subcategory="flowers", tier="KEEP",
                   acceptable=["YOUR_CALL"], importance="low", synthetic=False, source=fsrc(relp, att, "verbatim"),
                   date=d)
            wa_recv.append(rel)
        elif folder == CAM:
            im = im.resize((im.size[0] * 3, im.size[1] * 3), Image.LANCZOS)
            rel = B.unique(f"{CAM}/{cam_name(d)}")
            B.save_image(rel, im, quality=90, exif=exif_for(d, relp, *im.size), category="travel_scenery",
                         subcategory="flowers", tier="KEEP", importance="medium", synthetic=False,
                         source=fsrc(relp, att, "upscaled x3, camera EXIF"), date=d)
            originals.append((rel, None))
        else:
            ext = "jpg"
            if "Telegram" in folder:
                name = f"{k:03d}_{d:%Y-%m-%d}_{d:%H-%M-%S}.jpg"
            elif "Instagram" in folder:
                name = f"IMG_{d:%Y%m%d_%H%M%S}_{k:03d}.jpg"
            elif "Facebook" in folder:
                name = f"FB_IMG_{int(d.timestamp() * 1000)}.jpg"
            else:
                name = os.path.basename(relp)
            rel = B.unique(f"{folder}/{name}")
            B.copy(rel, absp, category="travel_scenery", subcategory="flowers", tier="KEEP",
                   acceptable=["YOUR_CALL"], importance="low", synthetic=False, source=fsrc(relp, att, "verbatim"),
                   date=d)
    # forwarded-to-many: exact duplicates among WhatsApp received + Sent
    for k, rel in enumerate(wa_recv[:40]):
        d = rand_dt("fwd", rel)
        drel = B.unique(f"{WA}/Sent/" + wa_name(d, B.wa_next(d)))
        e = B.entries[rel]
        B.copy(drel, B.abs(rel), category=e["category"], subcategory="forwarded", tier="SAFE",
               importance="disposable", scenarios=["S03"], rels=[B.rel("EXACT_DUPLICATE", rel)], synthetic=False,
               source=e["source"], date=d, notes="re-forwarded image: byte-identical to received copy")
    # 10. Consecutive video frames (near-duplicates) from real clips
    for vid in ["cosmos_001", "ocv_vtest", "cosmos_003", "vid_couple"]:
        if vid not in S:
            continue
        cap = cv2.VideoCapture(S[vid]["path"])
        cap.set(cv2.CAP_PROP_POS_FRAMES, 30)
        d = rand_dt("frames", vid)
        first = None
        for f in range(5):
            ok, fr = cap.read()
            if not ok:
                break
            for _ in range(2):
                cap.read()
            im = Image.fromarray(cv2.cvtColor(fr, cv2.COLOR_BGR2RGB))
            rel = B.unique(f"Pictures/Screenshots/{'Screenshot_'}{d:%Y%m%d-%H%M%S}_Video Player.jpg")
            B.save_image(rel, im, quality=90, category="screenshot", subcategory="video_frame",
                         tier="KEEP" if f == 0 else "REVIEW", acceptable=None if f == 0 else ["YOUR_CALL", "SAFE"],
                         importance="low", rels=[] if f == 0 else [B.rel("VISUALLY_SIMILAR", first,
                                                                          transform="consecutive video frame")],
                         synthetic=False, source=B.src_meta(vid, f"frame grab #{f}"), date=d)
            first = first or rel
            d = d + datetime.timedelta(seconds=2)
        cap.release()
    # 11. Other real categories: products, ads, screenshots, documents-as-photos, QR, photo of screen
    for sid in product:
        d = rand_dt("prod", sid)
        im = img(sid, 1600)
        if sid.startswith("cymbal"):
            rel = B.unique(f"Download/{sid.split('cymbal_pets_')[-1][:40]}.webp")
            B.save_image(rel, im, quality=80, category="product", tier="KEEP", acceptable=["YOUR_CALL"],
                         importance="low", synthetic=False, source=B.src_meta(sid, "converted to webp, 1600px"), date=d)
        else:
            rel = B.unique(f"{WA}/" + wa_name(d, B.wa_next(d)))
            B.save_image(rel, wa_compress(im), quality=75, category="product", tier="KEEP",
                         acceptable=["YOUR_CALL"], importance="low", synthetic=False,
                         source=B.src_meta(sid, "whatsapp recompression"), date=d)
    for sid in ids:
        c = S[sid].get("cat")
        if S[sid]["kind"] != "photo":
            continue
        d = rand_dt("misc", sid)
        if c == "advertisement":
            B.save_image(B.unique(f"{WA}/" + wa_name(d, B.wa_next(d))), wa_compress(img(sid)), quality=75,
                         category="advertisement", tier="YOUR_CALL", acceptable=["SAFE"], importance="disposable",
                         synthetic=False, source=B.src_meta(sid, "whatsapp recompression"), date=d)
        elif c == "screenshot":
            B.save_image(B.unique("Pictures/Screenshots/" + f"Screenshot_{d:%Y%m%d-%H%M%S}_Chrome.png"), img(sid),
                         category="screenshot", tier="YOUR_CALL", acceptable=["KEEP"], importance="low",
                         synthetic=False, source=B.src_meta(sid, "png"), date=d)
        elif c == "photo_of_screen":
            rel, _ = camera(sid, "photo_of_screen", "pos" + sid, importance="low", tier="KEEP",
                            acceptable=["YOUR_CALL"])
        elif c == "qr_code":
            rel, _ = camera(sid, "qr_code", "qr" + sid, importance="low", tier="YOUR_CALL",
                            acceptable=["KEEP", "SAFE"], transform="camera photo of QR code (verbatim pixels)")
        elif c == "document" and S[sid]["kind"] == "photo":
            rel, _ = camera(sid, "document", "doc" + sid, importance="medium", tier="KEEP", acceptable=["REVIEW"])
    # photos of screens (synthetic composite from real photos)
    for k, (rel0, sid) in enumerate(originals[100:112]):
        src = load_rgb(B.abs(rel0), 1200)
        canvas = Image.new("RGB", (2400, 1800), (35, 30, 28))
        a = np.asarray(src.resize((1600, 1000))).astype(np.float32)
        yy = np.arange(a.shape[0])[:, None]; xx = np.arange(a.shape[1])[None, :]
        moire = 0.85 + 0.15 * np.sin((xx * 0.9 + yy * 0.35) * 0.8) * np.sin(yy * 0.7)
        a = a * moire[..., None] * 0.9 + 18
        scr = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
        canvas.paste((10, 10, 10), (340, 340, 2060, 1460)); canvas.paste(scr, (400, 400))
        M = cv2.getPerspectiveTransform(np.float32([[0, 0], [2400, 0], [2400, 1800], [0, 1800]]),
                                        np.float32([[60 + k * 5, 40], [2380, 120], [2300, 1760], [0, 1700]]))
        warped = cv2.warpPerspective(np.asarray(canvas), M, (2400, 1800), borderValue=(60, 50, 40))
        im = Image.fromarray(warped).filter(ImageFilter.GaussianBlur(1.2))
        d = rand_dt("pos", k)
        B.save_image(B.unique(f"{CAM}/{cam_name(d)}"), im, quality=88, exif=exif_for(d, "pos" + str(k), *im.size),
                     category="photo_of_screen", tier="YOUR_CALL", acceptable=["KEEP", "REVIEW"], importance="low",
                     synthetic=True, source={"id": "composite", "licence": "derived; base: " + str(
                         B.entries[rel0]["source"].get("id")), "base": rel0},
                     notes="photo of a monitor showing another gallery photo (moire); not a duplicate of base",
                     hard_negative_group="HN_photo_of_screen")
    return originals
