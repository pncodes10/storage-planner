"""Reads the text of every picture of a FutureNeed test phone with the app's own text-reader page
(app/src/main/assets/ocr, served as OcrEngine serves it; see tools/test_ocr_page.py) and writes
<picture>.ocr.json ({"words": [["word", conf], ...]}), which JvmPlatform.readText hands to the scanner.
Pictures are sent at 1600 px, like Sorter.TEXT_EDGE. Already-read pictures are skipped.
EDGE=800 SUFFIX=.ocr800.json ONLY=payment reads a smaller copy (of pictures whose label kind contains ONLY).
usage: ocr.py <phone dir from build.py>"""
import asyncio, io, json, os, re, sys, time
from PIL import Image
from playwright.async_api import async_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); ASSETS = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'assets', 'ocr')
ORIGIN = 'https://appassets.androidplatform.net'; WORKERS = 2
EDGE = int(os.environ.get('EDGE', '1600')); SUFFIX = os.environ.get('SUFFIX', '.ocr.json'); ONLY = os.environ.get('ONLY', '')


async def main(phone):
    labels = json.load(open(os.path.join(phone, 'labels.json')))
    todo = [r for r in sorted(labels) if ONLY in labels[r]['kind'] and not os.path.exists(os.path.join(phone, 'sdcard', r) + SUFFIX)]
    async with async_playwright() as p:
        b = await p.chromium.launch(); pg = await b.new_page()
        images, refused = {}, []

        async def handle(route):
            u = route.request.url
            if u.startswith(ORIGIN + '/img/') and u[len(ORIGIN) + 5:] in images:
                return await route.fulfill(body=images[u[len(ORIGIN) + 5:]], content_type='image/jpeg')
            if u.startswith(ORIGIN + '/ocr/'):
                name = u[len(ORIGIN) + 5:].split('?')[0].replace('relaxedsimd', 'simd')
                f = os.path.join(ASSETS, name)
                if re.fullmatch(r'[A-Za-z0-9_.-]+', name) and os.path.exists(f):
                    t = 'text/html' if name.endswith('.html') else 'application/javascript' if name.endswith('.js') else 'application/wasm' if name.endswith('.wasm') else 'application/octet-stream'
                    return await route.fulfill(path=f, content_type=t, headers={'Access-Control-Allow-Origin': ORIGIN})
            refused.append(u); return await route.fulfill(status=404, body='')
        await pg.route('**/*', handle)
        loop = asyncio.get_event_loop(); ready = loop.create_future(); waits = {}

        async def native(kind, a, b2=None):
            if kind == 'ready' and not ready.done(): ready.set_result(a)
            elif kind == 'failed' and a == -1 and not ready.done(): ready.set_exception(RuntimeError(b2))
            elif kind in ('done', 'failed') and a in waits: waits[a].set_result((kind, b2))
        await pg.expose_function('__native', native)
        await pg.add_init_script("window.Native = {ready: w => __native('ready', w), done: (i, j) => __native('done', i, j), failed: (i, m) => __native('failed', i, m)};")
        await pg.goto(ORIGIN + '/ocr/ocr.html')
        await pg.evaluate(f'start({WORKERS})')
        await asyncio.wait_for(ready, 120)
        t0 = time.time(); sem = asyncio.Semaphore(WORKERS); done = [0]

        async def one(i, rel):
            async with sem:
                path = os.path.join(phone, 'sdcard', rel)
                im = Image.open(path).convert('RGB'); im.thumbnail((EDGE, EDGE))
                buf = io.BytesIO(); im.save(buf, 'JPEG', quality=92); images[str(i)] = buf.getvalue()
                waits[i] = loop.create_future()
                await pg.evaluate(f'read({i})')
                kind, payload = await asyncio.wait_for(waits[i], 180)
                del images[str(i)]
                words = json.loads(payload) if kind == 'done' and payload else []
                json.dump({'words': words}, open(path + SUFFIX, 'w'))
                done[0] += 1
                if done[0] % 25 == 0: print(done[0], '/', len(todo), round(time.time() - t0), 's', flush=True)
        await asyncio.gather(*(one(i, r) for i, r in enumerate(todo, 1)))
        print('read', len(todo), 'pictures in', round(time.time() - t0), 's; refused requests:', refused)
        await b.close()
        if refused: sys.exit(1)

asyncio.run(main(sys.argv[1]))
