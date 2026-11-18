"""Spoiled copies of a quarter of the pictures in all.txt (one kind each), for the aesthetic score; writes deg/ and deg.txt."""
import os, random, sys
from PIL import Image, ImageFilter, ImageEnhance
import numpy as np
random.seed(3); np.random.seed(3)
src = [l.strip() for l in open('all.txt')]
os.makedirs('deg', exist_ok=True)
out = open('deg.txt', 'w')
pick = random.sample(range(len(src)), len(src) // 4)
for n, i in enumerate(pick):
    p = src[i]
    try: im = Image.open(p).convert('RGB')
    except Exception: continue
    im.thumbnail((400, 400))
    kind = random.choice(['blur', 'motion', 'dark', 'bright', 'jpeg', 'lowres', 'tilt', 'haze', 'pocket'])
    if kind == 'blur': im = im.filter(ImageFilter.GaussianBlur(random.uniform(2.5, 8)))
    elif kind == 'motion':
        a = np.asarray(im).astype(np.float32); k = random.randint(9, 25); o = np.zeros_like(a)
        for s in range(k): o += np.roll(a, s - k // 2, axis=random.choice([0, 1]) if s == 0 else 1)
        im = Image.fromarray((o / k).clip(0, 255).astype(np.uint8))
    elif kind == 'dark':
        a = np.asarray(im).astype(np.float32) * random.uniform(0.06, 0.25)
        a += np.random.normal(0, random.uniform(2, 8), a.shape); im = Image.fromarray(a.clip(0, 255).astype(np.uint8))
    elif kind == 'bright':
        a = np.asarray(im).astype(np.float32) * random.uniform(2.2, 4) + 40; im = Image.fromarray(a.clip(0, 255).astype(np.uint8))
    elif kind == 'jpeg':
        import io; b = io.BytesIO(); im.save(b, 'JPEG', quality=random.randint(3, 8)); im = Image.open(b).convert('RGB')
    elif kind == 'lowres':
        w, h = im.size; f = random.uniform(0.08, 0.18); im = im.resize((max(8, int(w * f)), max(8, int(h * f)))).resize((w, h), Image.NEAREST)
    elif kind == 'tilt':
        im = im.rotate(random.uniform(15, 40) * random.choice([-1, 1]), expand=False, fillcolor=(0, 0, 0))
    elif kind == 'haze':
        im = ImageEnhance.Contrast(im).enhance(random.uniform(0.15, 0.35))
    elif kind == 'pocket':
        w, h = im.size; a = np.asarray(im.resize((8, 8)).resize((w, h), Image.BILINEAR)).astype(np.float32) * random.uniform(0.05, 0.4)
        a += np.random.normal(0, 6, a.shape); im = Image.fromarray(a.clip(0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(4))
    q = 'deg/%06d_%s.jpg' % (i, kind); im.save(q, quality=92); out.write('%s\t%d\t%s\n' % (os.path.abspath(q), i, kind))
out.close()
