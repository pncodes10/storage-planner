import asyncio, os, json, io, mimetypes, base64, sys, time
from PIL import Image
from playwright.async_api import async_playwright
WWW='/tmp/claude-0/ocr/www'; ORIGIN='https://appassets.androidplatform.net'; C='/tmp/claude-0/corpus'
EDGE=int(os.environ.get('EDGE','2000')); SUFFIX=os.environ.get('SUFFIX','.ocr.json'); ONLY=os.environ.get('ONLY','')
async def main():
    labels=json.load(open(C+'/labels.json'))
    async with async_playwright() as p:
        b=await p.chromium.launch(); pg=await b.new_page(); blocked=[]
        imgs={}
        async def handle(route):
            u=route.request.url
            if u.startswith(ORIGIN+'/img/'):
                return await route.fulfill(body=imgs[u[len(ORIGIN)+5:]], content_type='image/jpeg')
            if u.startswith(ORIGIN+'/'):
                f=os.path.join(WWW,u[len(ORIGIN)+1:].split('?')[0].replace('relaxedsimd','simd'))
                if os.path.exists(f):
                    return await route.fulfill(path=f, content_type='application/wasm' if f.endswith('.wasm') else (mimetypes.guess_type(f)[0] or 'application/octet-stream'))
            blocked.append(u); await route.abort()
        await pg.route('**/*', handle)
        await pg.goto(ORIGIN+'/ocr.html'); await pg.evaluate('init()')
        t0=time.time(); n=0
        for rel in sorted(labels):
            out=os.path.join(C,rel)+SUFFIX
            if ONLY and ONLY not in rel: continue
            if os.path.exists(out): continue
            im=Image.open(os.path.join(C,rel)).convert('RGB'); im.thumbnail((EDGE,EDGE))
            buf=io.BytesIO(); im.save(buf,'JPEG',quality=92); key=str(n); imgs[key]=buf.getvalue()
            r=await pg.evaluate('u => ocr(u)', ORIGIN+'/img/'+key); del imgs[key]
            json.dump({'text':r['text'],'conf':r['conf'],'ms':r['ms'],'words':r['words']},open(out,'w')); n+=1
            if n%25==0: print(n, round(time.time()-t0), flush=True)
        print('done', n, 'blocked', blocked)
        await b.close()
asyncio.run(main())
