"""Independent audit of the built APK (does not trust any build tool).

Checks: v2 signature, manifest (package, SDKs, exact permission set, no INTERNET), zip alignment,
that the dex contains only our own classes, and every external API the code can call --
flagging network, deletion, process execution, dynamic code loading and reflection.
usage: audit_apk.py <apk> <compiled_classes_dir>
"""
import sys, os, struct, zipfile, subprocess, re, json, logging
logging.disable(logging.CRITICAL)
from loguru import logger; logger.remove()
from androguard.core.apk import APK
from androguard.core.dex import DEX

apk_path, classes_dir = sys.argv[1:3]
report = {}; ok = True
def check(name, cond, detail=""):
    global ok
    report[name] = {"pass": bool(cond), "detail": detail}
    ok &= bool(cond)

# 1) signature (apksigtool = independent implementation)
v = subprocess.run(["apksigtool", "verify", apk_path], capture_output=True, text=True)
check("v2 signature verifies (apksigtool)", "v2 verified" in v.stdout + v.stderr, (v.stdout + v.stderr).strip().splitlines()[-1])

# 2) manifest
a = APK(apk_path)
perms = sorted(a.get_permissions())
expected = sorted(["android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE",
                   "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.PACKAGE_USAGE_STATS",
                   "android.permission.QUERY_ALL_PACKAGES",
                   # 1.2: background analysis (foreground service) + its notification
                   "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                   "android.permission.POST_NOTIFICATIONS", "android.permission.WAKE_LOCK"])
check("package / SDK", a.get_package() == "com.storageplanner" and a.get_min_sdk_version() == "26" and a.get_target_sdk_version() == "34",
      f"{a.get_package()} min {a.get_min_sdk_version()} target {a.get_target_sdk_version()} v{a.get_androidversion_name()}")
check("permissions exactly as declared", perms == expected, ", ".join(p.split(".")[-1] for p in perms))
check("no INTERNET permission", "android.permission.INTERNET" not in perms)
ANDROID = "{http://schemas.android.com/apk/res/android}"
mx = a.get_android_manifest_xml()
app_el = mx.find("application")
def attr(el, name):
    v = el.get(ANDROID + name)
    return None if v is None else str(v)
comps = [(c.tag, attr(c, "name")) for c in app_el if isinstance(c.tag, str)]
acts = [c for c in app_el if c.tag in ("activity", "activity-alias")]
svcs = [c for c in app_el if c.tag == "service"]
launchers = [attr(c, "name") for c in acts
             if any(attr(x, "name") == "android.intent.action.MAIN" for f in c.findall("intent-filter") for x in f.findall("action"))
             and any(attr(x, "name") == "android.intent.category.LAUNCHER" for f in c.findall("intent-filter") for x in f.findall("category"))]
check("single exported launcher activity", [attr(c, "name") for c in acts] == ["com.storageplanner.app.MainActivity"]
      and launchers == ["com.storageplanner.app.MainActivity"] and a.get_activities() == ["com.storageplanner.app.MainActivity"],
      str(a.get_activities()))
fgs = attr(svcs[0], "foregroundServiceType") if len(svcs) == 1 else None
provs = [c for c in app_el if c.tag == "provider"]
check("exactly one non-exported dataSync service, no receivers",
      [attr(c, "name") for c in svcs] == ["com.storageplanner.app.AnalysisService"]
      and a.get_services() == ["com.storageplanner.app.AnalysisService"]
      and attr(svcs[0], "exported") in ("false", "0")
      and not svcs[0].findall("intent-filter")                        # nothing else can address it implicitly
      and fgs is not None and fgs.lower() in ("datasync", "1", "0x1", "0x00000001")
      and not a.get_receivers()
      and sorted(set(t for t, _ in comps)) == ["activity", "provider", "service"],
      f"services {[attr(c, 'name') for c in svcs]} exported={attr(svcs[0], 'exported') if svcs else None} "
      f"foregroundServiceType={fgs}; receivers {a.get_receivers()}")
# 1.3: one private, read-only file provider for "Open with" / "Share" (per-URI grants only)
check("exactly one private file provider (not exported, per-URI read grants, no permission-less paths)",
      [attr(c, "name") for c in provs] == ["com.storageplanner.app.FileAccessProvider"]
      and attr(provs[0], "authorities") == "com.storageplanner.files"
      and attr(provs[0], "exported") in ("false", "0")
      and attr(provs[0], "grantUriPermissions") in ("true", "1", "-1")
      and not provs[0].findall("intent-filter") and attr(provs[0], "readPermission") is None and attr(provs[0], "writePermission") is None,
      f"providers {[attr(c, 'name') for c in provs]} authority={attr(provs[0], 'authorities') if provs else None} "
      f"exported={attr(provs[0], 'exported') if provs else None} grantUriPermissions={attr(provs[0], 'grantUriPermissions') if provs else None}")

# 3) zip layout
with zipfile.ZipFile(apk_path) as z:
    names = sorted(z.namelist())
    raw = open(apk_path, "rb").read()
    arsc = z.getinfo("resources.arsc")
    off = arsc.header_offset + 30 + len(arsc.filename.encode()) + struct.unpack("<H", raw[arsc.header_offset + 28:arsc.header_offset + 30])[0]
# 1.6: the offline text reader's files, and nothing else, under assets/ocr/ (checked against the npm releases' hashes)
OCR_ASSETS = {"assets/ocr/ocr.html", "assets/ocr/tesseract.min.js", "assets/ocr/worker.min.js", "assets/ocr/tesseract-core-simd-lstm.wasm.js",
              "assets/ocr/tesseract-core-lstm.wasm.js", "assets/ocr/eng.traineddata", "assets/ocr/LICENSE-tesseract.js.md", "assets/ocr/LICENSE-tesseract.js-core"}
# 1.8: the face detector's weights (YuNet, converted by tools/facemodel/export_yunet.py) and its licence
FACE_ASSETS = {"assets/faces/yunet.bin", "assets/faces/LICENSE-yunet.txt"}
other = [n for n in names if n not in ("AndroidManifest.xml", "classes.dex", "resources.arsc")]
check("zip entries", set(names) >= {"AndroidManifest.xml", "classes.dex", "resources.arsc"}
      and all(n.startswith("res/") or n in OCR_ASSETS or n in FACE_ASSETS for n in other) and OCR_ASSETS <= set(names) and FACE_ASSETS <= set(names),
      ", ".join(n for n in names if not n.startswith("assets/")) + " + " + str(len([n for n in names if n.startswith("assets/ocr/")])) + " text-reader files + "
      + str(len([n for n in names if n.startswith("assets/faces/")])) + " face-detector files")
import hashlib
pinned = {}
pin_file = os.path.join(os.path.dirname(__file__), "ocr_assets.sha256")
for line in open(pin_file):
    h, n = line.split(); pinned["assets/ocr/" + n] = h
with zipfile.ZipFile(apk_path) as z:
    bad = [n for n in pinned if hashlib.sha256(z.read(n)).hexdigest() != pinned[n]]
check("text-reader files are the pinned, unmodified releases (tesseract.js 7.0.0, core 7.0.0, tessdata_fast eng)", not bad, "; ".join(bad))
fpinned = {}
for line in open(os.path.join(os.path.dirname(__file__), "face_assets.sha256")):
    h, n = line.split(); fpinned["assets/faces/" + n] = h
with zipfile.ZipFile(apk_path) as z:
    fbad = [n for n in fpinned if hashlib.sha256(z.read(n)).hexdigest() != fpinned[n]]
check("face-detector files are the pinned conversion of face_detection_yunet_2023mar.onnx", not fbad, "; ".join(fbad))
check("resources.arsc stored + 4-byte aligned", arsc.compress_type == zipfile.ZIP_STORED and off % 4 == 0, f"offset {off}")

# 4) dex: classes and every external reference
d = DEX(zipfile.ZipFile(apk_path).read("classes.dex"))
dex_classes = sorted(c.get_name()[1:-1].replace("/", ".") for c in d.get_classes())
compiled = sorted(os.path.relpath(os.path.join(r, f), classes_dir)[:-6].replace("/", ".")
                  for r, _, fs in os.walk(classes_dir) for f in fs if f.endswith(".class"))
extra = [c for c in dex_classes if c not in compiled]
check("dex contains exactly the compiled classes", not extra and set(compiled) <= set(dex_classes),
      f"{len(dex_classes)} classes; extra: {extra}")

refs = set()
for m in (em for c in d.get_classes() for em in c.get_methods()):
    code = m.get_code()
    if not code: continue
    for ins in code.get_bc().get_instructions():
        if ins.get_name().startswith("invoke"):
            s = ins.get_output().split(",")[-1].strip()
            refs.add(s)
ext = sorted(r for r in refs if not r.startswith("Lcom/storageplanner/"))
BAD = {
    "network": r"Ljava/net/|Ljavax/net/|okhttp|HttpURLConnection|Landroid/net/http|Landroid/net/wifi|WebView;->loadUrl\(Ljava/lang/String;Ljava/util/Map|WebView;->postUrl|WebView;->loadDataWithBaseURL",
    "deletes / renames files": r"Ljava/io/File;->delete\(|DocumentsContract;->deleteDocument|MediaStore.*->delete|->deleteOnExit|->renameTo|Ljava/nio/file/Files;->(delete|move)|ContentResolver;->delete|PackageInstaller|->uninstall",
    "runs processes": r"Ljava/lang/Runtime;->exec|ProcessBuilder",
    "loads code / reflection": r"DexClassLoader|PathClassLoader|InMemoryDexClassLoader|Ljava/lang/Class;->forName|Ljava/lang/reflect/Method;->invoke|System;->load",
}
# 1.6: the only WebView is the text reader; it may load exactly one address (the bundled page) and must answer
# every request itself (shouldInterceptRequest), so nothing is fetched from anywhere else
loads = sorted(set(r for r in ext if "WebView;->loadUrl" in r))
check("WebView only loads the bundled text-reader page", loads == ["Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V"], "; ".join(loads))
web_users = sorted(set(c.get_name() for c in d.get_classes() for m in c.get_methods() if m.get_code()
                       for ins in m.get_code().get_bc().get_instructions() if ins.get_name().startswith("invoke") and "Landroid/webkit/WebView;" in ins.get_output()
                       and "getCurrentWebViewPackage" not in ins.get_output()))   # reading the WebView version for the Lab log is allowed
check("WebView used only by OcrEngine", all(n.startswith("Lcom/storageplanner/app/OcrEngine") for n in web_users), ", ".join(web_users))
for k, pat in BAD.items():
    hits = [r for r in ext if re.search(pat, r)]
    check(f"no code that {k}", not hits, "; ".join(hits[:5]))
writes = [r for r in ext if re.search(r"FileOutputStream;-><init>|RandomAccessFile;-><init>", r)]
report["file writes (expected: report export only)"] = {"pass": True, "detail": "; ".join(writes)}
report["external API calls"] = {"pass": True, "detail": f"{len(ext)} distinct"}
json.dump({"ok": ok, "checks": report, "external_api": ext}, open(os.path.splitext(apk_path)[0] + ".audit.json", "w"), indent=1)
for k, v in report.items(): print(("PASS " if v["pass"] else "FAIL ") + k + (": " + v["detail"] if v["detail"] else ""))
print("AUDIT", "OK" if ok else "FAILED")
sys.exit(0 if ok else 1)
