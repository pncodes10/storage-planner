"""Catalogue of real (downloaded) source material and a cached downloader.

Every source item carries a URL and a licence note; build.py copies these into
manifest.json for every file derived from the item.  Only hosts that are
reachable from the build sandbox are used (storage.googleapis.com,
raw.githubusercontent.com, pip).  Downloads are cached under --cache and are
never modified in place; a sources.lock.json with sha256 of each download is
written so drift of an upstream file is detectable.
"""
import hashlib
import json
import os
import random
import shutil
import subprocess
import tarfile
import urllib.request
from concurrent.futures import ThreadPoolExecutor

GCS = "https://storage.googleapis.com"
CSD = GCS + "/cloud-samples-data"
MPA = GCS + "/mediapipe-assets"
RAW = "https://raw.githubusercontent.com"

LIC_CSD = ("Google Cloud public sample data bucket gs://cloud-samples-data, published by Google "
           "for use with Google Cloud samples/tutorials (samples repos are Apache-2.0); "
           "evaluation use only, verify before redistribution")
LIC_DOCCI = ("gs://cloud-samples-data longcap100 set; images appear to be from Google's DOCCI "
             "dataset (CC BY 4.0)")
LIC_FLOWERS = ("TensorFlow flower_photos, CC BY 2.0; per-image author attribution in "
               "flower_photos/LICENSE.txt")
LIC_MPA = "MediaPipe test assets (gs://mediapipe-assets), google-ai-edge/mediapipe, Apache-2.0"
LIC_OPENCV = "OpenCV samples/data (opencv/opencv, Apache-2.0 / BSD-3); test images"
LIC_FACEREC = ("ageitgey/face_recognition examples (MIT); official portraits of US public "
               "officials are US-government works (public domain)")
LIC_ZXING = "zxing/zxing blackbox test images, Apache-2.0"
LIC_COSMOS = "Cosmos Laundromat (Blender Institute), CC BY 4.0; clips via gs://cloud-samples-data"
LIC_NOTO = "Google Noto fonts (google/fonts), SIL Open Font License 1.1"
LIC_SKIMAGE = "scikit-image bundled sample data (skimage.data); CC0 / public domain per skimage docs"


def _items():
    it = []

    def add(sid, url, kind, lic, **kw):
        d = dict(id=sid, url=url, kind=kind, licence=lic)
        d.update(kw)
        it.append(d)

    # High resolution real photographs (camera originals)
    for i in range(1, 101):
        add(f"docci_{i:03d}", f"{CSD}/ai-platform/generative_ai/media_files/longcap100/{i}.jpeg",
            "photo", LIC_DOCCI, cat="personal_photo")
    gen = {
        "people": ["a-man-and-a-dog.png", "man-in-field.png", "person.png", "woman.jpeg", "woman.jpg",
                   "dog_man_picture.png", "cricket.jpeg", "basketball-start.png", "basketball-end.png"],
        "pets": ["320px-Felis_catus-cat_on_snow.jpg", "dog-1.jpg", "dog-2.jpg", "small-dog-pink.jpg",
                 "chair-cat.png", "image-dog.png", "penguin.jpeg", "fetch.png"],
        "food": ["chips.jpeg", "cookies-milk.png", "cookies.png", "croissant.jpeg", "latte.jpg",
                 "meal-prep.jpeg", "meal.png", "mooncake.png", "scones.jpg", "snacks.png", "fruit.png"],
        "travel_scenery": ["boats.jpeg", "city-street.png", "forest-1.png", "forest-2.png",
                           "river-1.png", "river-2.png", "sky.jpg", "sky2.jpg", "sky3.jpg",
                           "times_square.jpg", "times_square2.jpg", "times_square3.jpg", "street.png",
                           "shops.png", "mount_fuji.jpg", "hurricane-ida.jpeg"],
        "product": ["black-bag.jpg", "black-boots.jpg", "dress.jpg", "jacket.jpg", "perfume.jpg",
                    "shirt.jpg", "shoes.jpg", "sweater.jpg", "trousers.jpg", "white-pants.jpg",
                    "mug.png", "armchair.png", "suitcase.png", "luggage.png", "teacup-1.png",
                    "teacup-2.png", "pixel-tablet.jpg", "toilet_paper.jpg", "diapers.jpg",
                    "vase.png", "sofa.jpeg", "gardening-tools.jpeg"],
        "advertisement": ["dog-ad-1.png", "dog-ad-2.png", "dog-ad-3.png", "brand-guide.png",
                          "product-image.png"],
        "screenshot": ["demo-UI.png", "homework.png", "ai_performance.png", "covid_chart.png"],
        "document": ["alice_doe_resume.png", "job_application.png", "paper.png"],
        "unknown": ["blocks-1.jpg", "blocks-2.jpg", "clay.jpg", "neon.png", "room.png",
                    "living-room.png", "office-desk.jpeg", "robotic.jpeg", "doodle.png"],
    }
    for cat, names in gen.items():
        for n in names:
            add("genai_" + n.rsplit(".", 1)[0].replace("-", "_").lower(),
                f"{CSD}/generative-ai/image/{n}", "photo", LIC_CSD, cat=cat)
    vis = {
        "people": ["vision/face/faces.jpeg", "vision/face/face_no_surprise.jpg",
                   "vision/label/woman.jpg", "vision/web/carnaval.jpeg"],
        "pets": ["vision/puppies.jpg", "vision/quickstart/wakeupcat.jpg"],
        "food": ["vision/attribute_detection/food_attributes.jpg"],
        "travel_scenery": ["vision/landmark/eiffel_tower.jpg", "vision/landmark/pofa.jpg",
                           "vision/landmark/st_basils.jpeg", "vision/image_properties/bali.jpeg",
                           "vision/label/setagaya.jpeg", "vision/using_curl/shanghai.jpeg",
                           "vision/web/city.jpg", "vision/ocr/sign.jpg"],
        "photo_of_screen": ["vision/text/screen.jpg"],
        "document": ["vision/handwritten.jpg", "vision/document_parsing/form1.jpg",
                     "vision/document_understanding/image2.jpg",
                     "vision/document_understanding/image3.jpg"],
        "product": ["vision/product_search/shoes_1.jpg", "vision/product_search/shoes_2.jpg",
                    "vision/attribute_detection/apparel_attributes.jpg"],
        "unknown": ["vision/crop_hints/bubble.jpeg", "vision/duck_and_truck.jpg"],
    }
    for cat, names in vis.items():
        for n in names:
            add("vision_" + n.split("/")[-1].rsplit(".", 1)[0].lower(), f"{CSD}/{n}", "photo",
                LIC_CSD, cat=cat)
    for n in ["boat.jpg", "car.jpeg", "cat.jpeg", "dogs.jpeg", "horse.jpeg", "husky.jpg", "zebra.jpeg"]:
        add("dlvm_" + n.split(".")[0], f"{CSD}/dlvm/t4/images/{n}", "photo", LIC_CSD,
            cat="pets" if n.split(".")[0] in ("cat", "dogs", "husky") else "personal_photo")
    for i in range(5):
        add(f"vxdog_{i:02d}", f"{CSD}/vertex-ai/dataset-management/datasets/dogs/{i:02d}.jpg",
            "photo", LIC_CSD, cat="pets")
    for n in ["cat1.jpg", "cat2.jpg", "catanddog.jpg", "dog1.jpg", "dog2.jpg"]:
        add("mlcolab_" + n.split(".")[0], f"{GCS}/learning-datasets/MLColabImages/{n}", "photo",
            "gs://learning-datasets public bucket (Google ML education); evaluation use", cat="pets")
    mp = {
        "people": ["business-person.png", "portrait.jpg", "pose.jpg", "hand-woman-man.jpg",
                   "man-woman-okay.jpg", "male_full_height_hands.jpg", "woman_hands.jpg",
                   "thumb_up.jpg", "victory.jpg", "pointing_up.jpg", "face_stylizer_raw_face_demo.png",
                   "mozart_square.jpg"],
        "pets": ["cat_large.jpg", "cats_and_dogs.jpg", "cat_and_dog_2.jpg", "leopard.jpg",
                 "penguins_large.jpg"],
        "food": ["burger.jpg"],
        "travel_scenery": ["bridge-image-seg.jpg", "stars-image-seg.jpg"],
        "personal_photo": ["living_room.jpg", "chairs-image-seg.jpg", "multi_objects.jpg", "test.jpg"],
        "document": ["ocr_text.jpg"],
    }
    for cat, names in mp.items():
        for n in names:
            add("mp_" + n.rsplit(".", 1)[0].replace("-", "_"), f"{MPA}/{n}", "photo", LIC_MPA, cat=cat)
    for n in ["obama.jpg", "biden.jpg", "two_people.jpg", "obama2.jpg", "obama-480p.jpg",
              "obama-720p.jpg", "obama-1080p.jpg", "obama_small.jpg"]:
        add("facerec_" + n.rsplit(".", 1)[0].replace("-", "_"),
            f"{RAW}/ageitgey/face_recognition/master/examples/{n}", "photo", LIC_FACEREC, cat="people",
            optional=True)
    ocv = {"people": ["messi5.jpg", "basketball1.png", "basketball2.png"],
           "food": ["fruits.jpg", "orange.jpg", "apple.jpg"],
           "pets": ["baboon.jpg", "butterfly.jpg"],
           "travel_scenery": ["home.jpg", "building.jpg", "aero1.jpg"],
           "unknown": ["starry_night.jpg", "smarties.png", "board.jpg", "graf1.png"],
           "document": ["notes.png", "sudoku.png", "imageTextN.png"],
           "personal_photo": ["licenseplate_motion.jpg", "left01.jpg", "box_in_scene.png"]}
    for cat, names in ocv.items():
        for n in names:
            add("ocv_" + n.rsplit(".", 1)[0].lower(), f"{RAW}/opencv/opencv/master/samples/data/{n}",
                "photo", LIC_OPENCV, cat=cat)
    for d in (1, 2, 6):
        for i in range(1, 21):
            add(f"zxing_q{d}_{i:02d}", f"{RAW}/zxing/zxing/master/core/src/test/resources/blackbox/qrcode-{d}/{i}.png",
                "photo", LIC_ZXING, cat="qr_code", optional=True)
    # Food: vegetables (deterministic subset), products
    veg = ["Bean", "Bitter_Gourd", "Bottle_Gourd", "Brinjal", "Broccoli", "Cabbage", "Capsicum",
           "Carrot", "Cauliflower", "Cucumber", "Papaya", "Potato", "Pumpkin", "Radish", "Tomato"]
    for v in veg:
        for j in (1, 2, 3, 4):
            add(f"veg_{v.lower()}_{j}", None, "photo", LIC_CSD, cat="food", listing=(
                "ai-platform-unified/datasets/images/vegetable_images/train/" + v + "/images/", j))
    # cymbal-pets product renders (resolved from the probe listing at build time)
    add("cymbal_pets", None, "listing", LIC_CSD, cat="product",
        listing=("bigquery/tutorials/cymbal-pets/images/", 40))
    # Real documents
    docs = {
        "id_document": ["documentai/IdentityDocAI/US-Passport/2006-US-Passport.jpeg",
                        "documentai/IdentityDocAI/US-Passport/2020-Next-Gen-US-Passport.jpeg",
                        "documentai/IdentityDocAI/US-Passport/US-Passport-Card.jpeg",
                        "documentai/IdentityDocAI/US-Driver-License/dl3.jpeg",
                        "documentai/IdentityDocAI/US-Driver-License/dl.pdf",
                        "documentai/IdentityDocAI/Identity-Document-Proofing/identity_fraud_non_id.jpeg",
                        "documentai/GeneralProcessors/Quality/texas_drivers_license_dark.pdf"],
        "receipt": ["documentai/ProcurementDocAI/Expense Parser/GroceryReceipt.pdf",
                    "documentai/ProcurementDocAI/Expense Parser/office-depot-receipt2.png",
                    "documentai/ProcurementDocAI/Expense Parser/time-out market.jpg",
                    "documentai/ProcurementDocAI/Expense Parser/AngelinasReceipt.tiff",
                    "documentai/ProcurementDocAI/Expense Parser/Starbucks.tiff"],
        "financial_document": [
            "documentai/SampleDocuments/BANK_STATEMENT_PROCESSOR/lending_bankstatement.pdf",
            "documentai/SampleDocuments/FORM_W2_PROCESSOR/2020FormW-2.pdf",
            "documentai/SampleDocuments/FORM_1099DIV_PROCESSOR/2020Form1099-DIV.pdf",
            "documentai/SampleDocuments/FORM_1099INT_PROCESSOR/2020Form1099-INT.pdf",
            "documentai/SampleDocuments/FORM_1099MISC_PROCESSOR/2020Form1099-MISC.pdf",
            "documentai/SampleDocuments/INVOICE_PROCESSOR/google_invoice.pdf",
            "documentai/SampleDocuments/CONTRACT_PROCESSOR/CymbalContract.pdf",
            "documentai/SampleDocuments/FORM_PARSER_PROCESSOR/intake-form.pdf",
            "documentai/LendingDocAI/Pay Slip Parser/paystub.pdf"]
        + [f"documentai/LendingDocAI/Pay Slip Parser/{i:04d}-paystub-walmart-{'hv8' if i <= 5 else 'ar8'}.pdf"
           for i in range(1, 11)],
        "document": ["vision/document/hello_world.pdf", "vision/document/three_pages.pdf",
                     "vision/document_understanding/kafka.pdf", "vision/pdf_tiff/census2010.pdf",
                     "documentai/GeneralProcessors/Quality/document_quality_poor.pdf"],
    }
    for cat, names in docs.items():
        for n in names:
            add("doc_" + n.split("/")[-1].rsplit(".", 1)[0].replace(" ", "_").replace("-", "_").lower(),
                f"{CSD}/{urllib.request.quote(n)}", "document", LIC_CSD, cat=cat)
    add("invoices_synth", None, "listing", LIC_CSD + " (synthetic invoices generated by Google)",
        cat="financial_document", listing=("documentai/Custom/Invoices/PDF_Labeled/", 30))
    # Video
    for n in ["boat", "couple", "desert", "mountain", "plum"]:
        add(f"vid_{n}", f"{CSD}/generative-ai/evaluation/videos/{n}.mp4", "video", LIC_CSD, cat="video")
    for i in range(6):
        add(f"cosmos_{i:03d}", f"{CSD}/vertex-ai-vision/veo/cosmos_laundromat/{i:03d}.mp4", "video",
            LIC_COSMOS, cat="video")
    add("ocv_vtest", f"{RAW}/opencv/opencv/master/samples/data/vtest.avi", "video", LIC_OPENCV, cat="video")
    add("ocv_megamind", f"{RAW}/opencv/opencv/master/samples/data/Megamind.avi", "video", LIC_OPENCV, cat="video")
    add("traffic_005", f"{CSD}/ai-platform-unified/video/traffic/highway_005.mp4", "video", LIC_CSD, cat="video")
    # Audio
    for n in ["speech_16000_hz_mono.wav", "speech_48000_hz_mono.wav", "two_heads_16000_hz_mono.wav",
              "two_heads_44100_hz_mono.wav"]:
        add("mpaudio_" + n.split(".")[0], f"{MPA}/{n}", "audio", LIC_MPA, cat="audio")
    # Fonts
    add("font_deva", f"{RAW}/google/fonts/main/ofl/notosansdevanagari/NotoSansDevanagari%5Bwdth,wght%5D.ttf",
        "font", LIC_NOTO)
    add("font_sans", f"{RAW}/google/fonts/main/ofl/notosans/NotoSans%5Bwdth,wght%5D.ttf", "font", LIC_NOTO)
    add("font_anton", f"{RAW}/google/fonts/main/ofl/anton/Anton-Regular.ttf", "font", LIC_NOTO)
    add("font_mono", f"{RAW}/google/fonts/main/ofl/notosansmono/NotoSansMono%5Bwdth,wght%5D.ttf", "font", LIC_NOTO)
    # Flowers archive
    add("flowers_tgz", f"{GCS}/download.tensorflow.org/example_images/flower_photos.tgz", "archive",
        LIC_FLOWERS)
    return it


def _cache_name(url):
    h = hashlib.sha1(url.encode()).hexdigest()[:12]
    base = urllib.request.unquote(url.rsplit("/", 1)[-1]).replace(" ", "_")
    return f"{h}_{base}"


def fetch(url, cache_dir, retries=3):
    d = os.path.join(cache_dir, "dl")
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, _cache_name(url))
    if os.path.exists(p) and os.path.getsize(p) > 0:
        return p
    if os.path.exists(p + ".missing"):
        return None
    last = None
    for _ in range(retries):
        try:
            tmp = p + ".part"
            r = subprocess.run(["curl", "-sS", "-f", "-L", "--max-time", "600", "-o", tmp, url],
                               capture_output=True, text=True)
            if r.returncode == 0 and os.path.getsize(tmp) > 0:
                os.replace(tmp, p)
                return p
            last = r.stderr.strip()
            if "error: 404" in last or "error: 403" in last:
                open(p + ".missing", "w").write(last)
                return None
        except Exception as e:  # pragma: no cover
            last = str(e)
    print("  download failed:", url, last)
    return None


def _listing(cache_dir):
    """Lazily list gs://cloud-samples-data prefixes (XML API, public)."""
    cache = os.path.join(cache_dir, "listings")
    os.makedirs(cache, exist_ok=True)

    def lst(prefix):
        f = os.path.join(cache, hashlib.sha1(prefix.encode()).hexdigest()[:12] + ".json")
        if os.path.exists(f):
            return json.load(open(f))
        import re
        keys, marker = [], None
        while True:
            u = f"{CSD}?prefix={urllib.request.quote(prefix)}" + (
                f"&marker={urllib.request.quote(marker)}" if marker else "")
            x = subprocess.run(["curl", "-sS", "-f", u], capture_output=True, text=True).stdout
            ks = [k.replace("&amp;", "&") for k in re.findall(r"<Key>(.*?)</Key>", x)]
            keys += ks
            if "<IsTruncated>true" in x and ks:
                marker = ks[-1]
            else:
                break
        json.dump(keys, open(f, "w"))
        return keys
    return lst


def resolve(cache_dir):
    """Expand listing items into concrete (id, url) items."""
    out = []
    lst = _listing(cache_dir)
    for it in _items():
        if it.get("listing") and it["url"] is None:
            prefix, n = it["listing"]
            keys = sorted(k for k in lst(prefix) if k.lower().endswith((".jpg", ".jpeg", ".png", ".pdf")))
            if it["id"].startswith("veg_"):
                if len(keys) >= n:
                    k = keys[(n * 37) % len(keys)]
                    d = dict(it); d["url"] = f"{CSD}/{urllib.request.quote(k)}"; d.pop("listing")
                    out.append(d)
                continue
            rng = random.Random("listing:" + prefix)
            pick = sorted(rng.sample(keys, min(n, len(keys))))
            for k in pick:
                d = dict(it)
                stem = k.rsplit("/", 1)[-1].rsplit(".", 1)[0]
                stem = "".join(c if c.isalnum() else "_" for c in stem).lower()[:50]
                d["id"] = f"{it['id']}_{stem}"
                d["url"] = f"{CSD}/{urllib.request.quote(k)}"
                d.pop("listing")
                out.append(d)
        else:
            out.append(it)
    return out


def download_all(cache_dir, workers=12):
    items = resolve(cache_dir)
    with ThreadPoolExecutor(workers) as ex:
        paths = list(ex.map(lambda it: fetch(it["url"], cache_dir), items))
    got = {}
    for it, p in zip(items, paths):
        if p:
            it = dict(it); it["path"] = p
            got[it["id"]] = it
        elif not it.get("optional"):
            print("  WARNING missing source", it["id"])
    # flowers
    ft = got.get("flowers_tgz")
    if ft:
        fdir = os.path.join(cache_dir, "flower_photos")
        if not os.path.exists(os.path.join(fdir, ".done")):
            tmp = fdir + ".tmp"
            shutil.rmtree(tmp, ignore_errors=True)
            os.makedirs(tmp)
            with tarfile.open(ft["path"]) as tf:
                tf.extractall(tmp, filter="data")
            shutil.rmtree(fdir, ignore_errors=True)
            os.replace(tmp, fdir)
            open(os.path.join(fdir, ".done"), "w").close()
    lock = {k: {"url": v["url"], "sha256": sha256_file(v["path"])} for k, v in sorted(got.items())}
    json.dump(lock, open(os.path.join(cache_dir, "sources.lock.json"), "w"), indent=1)
    return got


def flowers(cache_dir):
    """Return sorted [(relpath, abspath, attribution)] for the TF flowers set."""
    root = os.path.join(cache_dir, "flower_photos", "flower_photos")
    attrib = {}
    lic = os.path.join(root, "LICENSE.txt")
    if os.path.exists(lic):
        for line in open(lic, encoding="utf-8", errors="replace"):
            parts = line.strip().split(" ", 1)
            if len(parts) == 2 and parts[0].endswith(".jpg"):
                attrib[parts[0]] = parts[1][:300]
    out = []
    for cls in sorted(os.listdir(root)):
        d = os.path.join(root, cls)
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            rel = f"{cls}/{f}"
            out.append((rel, os.path.join(d, f), attrib.get(rel, "see LICENSE.txt")))
    return out


def sha256_file(p, bs=1 << 20):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        while True:
            b = f.read(bs)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


if __name__ == "__main__":
    import sys
    c = sys.argv[1] if len(sys.argv) > 1 else "/home/user/sp-dataset-cache"
    g = download_all(c)
    print(len(g), "sources available")
