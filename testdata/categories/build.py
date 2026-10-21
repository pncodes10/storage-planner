"""Builds the categorisation test corpus. Real images where real data exists (receipts, invoices,
document pages, everyday photos, faces); synthetic (clearly marked) where it does not (UPI payment,
OTP, ticket and chat screenshots, greeting forwards, accidental shots, QR codes in scenes)."""
import os, glob, random, json, io, math, shutil, subprocess
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageOps
import qrcode
R = random.Random(21); NR = np.random.default_rng(21)
OUT = '/tmp/claude-0/corpus'; DS = '/tmp/claude-0/ds'; REAL = '/home/claude/phoneopt/realpool'
shutil.rmtree(OUT, ignore_errors=True)
labels = {}
def save(img, label, name, area, src, faces=None, fmt='jpg', q=88):
    d = os.path.join(OUT, area); os.makedirs(d, exist_ok=True)
    p = os.path.join(d, name + '.' + fmt)
    img = img.convert('RGB')
    if fmt == 'jpg': img.save(p, quality=q)
    else: img.save(p)
    if faces is not None:
        with open(p + '.faces', 'w') as f:
            for (x, y, e, c) in faces: f.write(f'{x} {y} {e} {c}\n')
    labels[os.path.relpath(p, OUT)] = {'label': label, 'source': src}
def fit(img, edge):
    img = img.copy(); img.thumbnail((edge, edge), Image.LANCZOS); return img
backs = [ImageOps.exif_transpose(Image.open(f)).convert('RGB') for f in sorted(glob.glob(REAL + '/*/*.jpg'))]
backs = [fit(b, 1600) for b in backs]

def as_photo(page, scale=0.75):
    """A page photographed on a table: perspective, uneven light, slight blur, JPEG."""
    bg = R.choice(backs).resize((1600, 1200))
    bg = bg.filter(ImageFilter.GaussianBlur(6))
    pw, ph = page.size; s = min(1600 * scale / pw, 1200 * 0.92 / ph) * R.uniform(0.85, 1.0)
    page = page.resize((max(1, int(pw * s)), max(1, int(ph * s))))
    a = np.array(bg); pg = np.array(page.convert('RGB'))
    h, w = pg.shape[:2]; cx, cy = 800 + R.randint(-80, 80), 600 + R.randint(-50, 50)
    src = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    jit = lambda: R.uniform(-0.06, 0.06) * w
    dst = np.float32([[cx - w / 2 + jit(), cy - h / 2 + jit()], [cx + w / 2 + jit(), cy - h / 2 + jit()],
                      [cx + w / 2 + jit(), cy + h / 2 + jit()], [cx - w / 2 + jit(), cy + h / 2 + jit()]])
    M = cv2.getPerspectiveTransform(src, dst)
    warped = cv2.warpPerspective(pg, M, (1600, 1200)); mask = cv2.warpPerspective(np.ones((h, w), np.uint8) * 255, M, (1600, 1200))
    a[mask > 0] = warped[mask > 0]
    gx = np.linspace(R.uniform(0.7, 1.0), R.uniform(0.9, 1.15), 1600)[None, :, None]
    a = np.clip(a * gx, 0, 255).astype(np.uint8)
    img = Image.fromarray(a).filter(ImageFilter.GaussianBlur(R.uniform(0.3, 0.9)))
    buf = io.BytesIO(); img.save(buf, 'JPEG', quality=R.randint(70, 90)); return Image.open(buf)

# ---------------- real: receipts (SROIE, photographed/scanned receipts)
sro = sorted(glob.glob(DS + '/sroie/data/img/*.jpg')); R.shuffle(sro)
for i, f in enumerate(sro[:60]):
    save(fit(Image.open(f), 2000), 'receipt', f'receipt_{i:03d}', 'DCIM/Camera', 'SROIE (real)')
# ---------------- real: invoices / bills (invoice2data test PDFs), as screenshots-of-PDF and as photos
invs = sorted(glob.glob(DS + '/inv2d/tests/compare/*.pdf'))
for i, f in enumerate(invs):
    tmp = '/tmp/claude-0/inv_page'
    subprocess.run(['pdftoppm', '-r', '110', '-f', '1', '-l', '1', '-png', f, tmp], check=True)
    pg = Image.open(sorted(glob.glob(tmp + '*.png'))[0]); [os.remove(x) for x in glob.glob(tmp + '*.png')]
    base = os.path.basename(f)[:-4]
    save(pg, 'bill', f'bill_{base}_page', 'Download', 'invoice2data (real invoice)', fmt='png')
    for k in range(2): save(as_photo(pg), 'bill', f'bill_{base}_photo{k}', 'DCIM/Camera', 'invoice2data (real invoice, photo simulated)')
# ---------------- real: document / text pages (tesseract + OCRmyPDF test pages)
docs = [f for f in sorted(glob.glob(DS + '/tesstest/testing/*')) if f.lower().endswith(('.tif', '.png', '.jpg'))
        and not os.path.basename(f)[0].isdigit() and not any(k in f for k in ('arabic', 'hebrew', 'heb', 'viet', 'deva', 'raaj', 'line', 'Hello', 'rotated'))]
docs += [DS + '/ocrmypdf/tests/resources/' + n for n in ('linn.png', 'typewriter.png', 'crom.png')]
for n in ('cardinal', 'lichtenstein', 'c03-29', 'epson', 'francais', 'skew'):
    p = DS + '/ocrmypdf/tests/resources/' + n + '.pdf'
    if os.path.exists(p):
        subprocess.run(['pdftoppm', '-r', '100', '-f', '1', '-l', '1', '-png', p, '/tmp/claude-0/doc_' + n], check=False)
        docs += sorted(glob.glob('/tmp/claude-0/doc_' + n + '*.png'))
for i, f in enumerate(docs):
    try: pg = Image.open(f).convert('RGB')
    except Exception: continue
    save(fit(pg, 1800), 'document', f'doc_{i:02d}_scan', 'Download', 'tesseract/OCRmyPDF test page (real)')
    save(as_photo(fit(pg, 1600)), 'document', f'doc_{i:02d}_photo', 'DCIM/Camera', 'tesseract/OCRmyPDF test page (real, photo simulated)')
# ---------------- real: everyday photos (no text) and people (real faces on real backgrounds)
for i, b in enumerate(backs):
    lab = 'photo'
    if i == 23: lab = 'people'          # selfie with a clock
    if i == 42: lab = 'document'        # photo of a monitor showing a web page
    if i == 0: lab = 'photo|accidental' # blank wall
    save(b, lab, f'photo_{i:02d}', 'DCIM/Camera', 'real phone photo (realpool)')
held = [l.rstrip('\n').split('\t') for l in open('/tmp/claude-0/eyes/heldout.tsv') if l.strip()]
opens = [h for h in held if h[1] == '1']
for i in range(30):
    bg = R.choice(backs).resize((1600, 1200)); nf = R.randint(1, 3); faces = []
    for k in range(nf):
        h = R.choice(opens); fi = Image.open(h[8]).convert('RGB'); ed = R.uniform(40, 90); sc = ed / float(h[5])
        x = (k + 0.5) * 1600 / nf; y = 1200 * R.uniform(0.3, 0.5)
        fi = fi.resize((int(fi.width * sc), int(fi.height * sc)))
        bg.paste(fi, (int(x - float(h[3]) * sc), int(y - float(h[4]) * sc)))
        faces.append((x / 1600, y / 1200, ed / 1600, R.uniform(0.5, 0.9)))
    save(bg, 'people', f'people_{i:02d}', 'DCIM/Camera', 'real faces on real photos (composited)', faces=faces)

# ---------------- QR codes and barcodes (synthetic)
payloads = ['upi://pay?pa=shop.%d@okaxis&pn=Store&am=%d' % (R.randint(100, 999), R.randint(10, 999)),
            'https://example.com/event/%d' % R.randint(1000, 9999), 'WIFI:T:WPA;S:Home_%d;P:pass%d;;' % (R.randint(1, 99), R.randint(1000, 9999)),
            'https://maps.example.com/?q=%d' % R.randint(1, 999), 'Ticket #%d valid for one entry' % R.randint(10000, 99999)]
for i in range(36):
    pl = payloads[i % len(payloads)].replace('%d', str(R.randint(1, 999)))
    q = qrcode.make(pl, box_size=10, border=4).convert('RGB')
    if i % 3 == 0:     # screenshot of a QR code in an app
        s = Image.new('RGB', (1080, 2400), 'white'); d = ImageDraw.Draw(s)
        s.paste(q.resize((700, 700)), (190, 700)); d.rectangle((0, 0, 1080, 90), fill=(30, 30, 30))
        save(s, 'qr', f'qr_{i:02d}_screen', 'Pictures/Screenshots', 'synthetic QR (screenshot)', fmt='png')
    else:              # QR printed on a poster / counter, photographed
        poster = Image.new('RGB', (900, 1200), (R.randint(200, 255), R.randint(200, 255), R.randint(200, 255)))
        poster.paste(q.resize((620, 620)), (140, 300))
        ImageDraw.Draw(poster).text((140, 120), 'SCAN TO PAY', fill='black', font=ImageFont.load_default(size=70))
        save(as_photo(poster, scale=R.uniform(0.35, 0.6)), 'qr', f'qr_{i:02d}_photo', 'DCIM/Camera', 'synthetic QR on a real background (photo simulated)')

# ---------------- greetings / forwards (synthetic: real photo + big greeting text), accidental shots (from real photos)
greet = ['Good Morning', 'Good Night', 'Happy Diwali', 'Have a blessed day', 'Happy Sunday', 'Good Morning\nStay blessed',
         'Happy Birthday', 'Happy Holi', 'Shubh Prabhat', 'Sweet Dreams', 'Happy New Year', 'Good Evening']
fonts = []
for f in glob.glob('/usr/share/fonts/**/*.ttf', recursive=True):
    try: ImageFont.truetype(f, 120).getbbox('Good Morning'); fonts.append(f)
    except Exception: pass
for i in range(36):
    b = R.choice(backs).copy().resize((1080, 1080)); d = ImageDraw.Draw(b)
    fnt = ImageFont.truetype(R.choice(fonts), R.randint(90, 150)) if fonts else ImageFont.load_default(size=110)
    txt = R.choice(greet); y = R.randint(80, 600)
    d.multiline_text((60, y), txt, font=fnt, fill=R.choice(['white', 'yellow', (255, 220, 120), 'red']), stroke_width=6, stroke_fill='black')
    save(b, 'greeting', f'greet_{i:02d}', 'Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images', 'synthetic greeting forward', q=70)
for i in range(24):
    b = np.array(R.choice(backs).resize((1600, 1200))).astype(float)
    kind = i % 3
    if kind == 0: b = b * R.uniform(0.02, 0.06) + NR.normal(0, 3, b.shape)                    # dark / lens covered
    elif kind == 1: b = cv2.GaussianBlur(b * R.uniform(0.15, 0.35), (0, 0), 25) + NR.normal(0, 4, b.shape)   # pocket: dark smear
    else:
        k = np.zeros((61, 61)); k[30, :] = 1 / 61; M = cv2.getRotationMatrix2D((30, 30), R.uniform(0, 180), 1); k = cv2.warpAffine(k, M, (61, 61)); k /= k.sum()
        b = cv2.filter2D(b, -1, k)                                                                 # heavy shake
    save(Image.fromarray(np.clip(b, 0, 255).astype(np.uint8)), 'accidental', f'accident_{i:02d}', 'DCIM/Camera', 'real photo made into an accidental shot (synthetic)')
json.dump(labels, open(OUT + '/labels.json', 'w'), indent=0)
print(len(labels), 'images')
