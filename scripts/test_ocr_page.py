"""Runs the app's text-reader page (app/src/main/assets/ocr) in Chromium exactly as OcrEngine serves it:
every request answered from the assets (relaxedsimd -> simd, like serve()), pictures from /img/<id>, anything
else refused with 404. A stand-in for the Android bridge (window.Native) records ready/done/failed.
Writes the words JSON the app would receive for each picture to <out>/<n>.json.
usage: test_ocr_page.py <out dir> <picture>..."""
import asyncio, os, sys, io, json, re
from PIL import Image
from playwright.async_api import async_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); ASSETS = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'ocr')
ORIGIN = 'https://appassets.androidplatform.net'
async def main(out, pics):
    os.makedirs(out, exist_ok=True)
    async with async_playwright() as p:
        b = await p.chromium.launch(); pg = await b.new_page()
        images, refused, events = {}, [], {}
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
        await pg.evaluate('start(2)')
        print('reader ready, workers:', await asyncio.wait_for(ready, 120))
        for i, pic in enumerate(pics, 1):
            im = Image.open(pic).convert('RGB'); im.thumbnail((1600, 1600))
            buf = io.BytesIO(); im.save(buf, 'JPEG', quality=92); images[str(i)] = buf.getvalue()
            waits[i] = loop.create_future()
            await pg.evaluate(f'read({i})')
            kind, payload = await asyncio.wait_for(waits[i], 120)
            open(os.path.join(out, f'{i}.json'), 'w').write(payload if kind == 'done' else '[]')
            print(i, kind, os.path.basename(pic), (payload or '')[:120])
        print('refused requests:', refused)
        await b.close()
        if refused: sys.exit(1)
asyncio.run(main(sys.argv[1], sys.argv[2:]))
