import json, numpy as np, cv2, math, random, sys
sys.path.insert(0,'/tmp/claude-0/eyes')
from feat import *
rng=np.random.default_rng(7); random.seed(7)
def fast_patch(L,cx,cy,d,mirror):
    h,w=L.shape
    sw=SPAN_W*d/PW; sh=SPAN_H*d/PH
    ii=np.arange(PW); jj=np.arange(PH)
    if mirror: ii=PW-1-ii
    sub=(np.arange(3)+0.5)/3.0
    X=cx+((ii[None,:,None,None]+sub[None,None,None,:])-PW/2.0)*sw + 0*jj[:,None,None,None]
    Y=cy+((jj[:,None,None,None]+sub[None,None,:,None])-PH/2.0)*sh + 0*ii[None,:,None,None]
    X=np.clip(X,0,w-1.0); Y=np.clip(Y,0,h-1.0)
    x0=np.floor(X).astype(int); y0=np.floor(Y).astype(int); x1=np.minimum(x0+1,w-1); y1=np.minimum(y0+1,h-1)
    fx=X-x0; fy=Y-y0
    v=(L[y0,x0]*(1-fx)+L[y0,x1]*fx)*(1-fy)+(L[y1,x0]*(1-fx)+L[y1,x1]*fx)*fy
    return v.sum(axis=(2,3))/9.0
def augment(im, re, le, label, strong):
    re=np.array(re,float); le=np.array(le,float)
    h,w=im.shape[:2]
    if label==1 and strong:   # match the roll spread of in-the-wild faces
        ang=rng.normal(0,8)
        M=cv2.getRotationMatrix2D((w/2,h/2),ang,1.0)
        im=cv2.warpAffine(im,M,(w,h),borderMode=cv2.BORDER_REPLICATE)
        re=M@np.array([re[0],re[1],1]); le=M@np.array([le[0],le[1],1])
    d=float(np.hypot(*(re-le)))
    if strong:
        t=math.exp(rng.uniform(math.log(16),math.log(110)))
        if d>t:
            s=t/d; im=cv2.resize(im,None,fx=s,fy=s,interpolation=cv2.INTER_AREA); re=re*s; le=le*s; d=t
        q=int(rng.uniform(40,95)); ok,buf=cv2.imencode('.jpg',im,[cv2.IMWRITE_JPEG_QUALITY,q]); im=cv2.imdecode(buf,1)
        if rng.random()<0.5:
            im=np.clip(im+rng.normal(0,rng.uniform(0,4),im.shape),0,255).astype(np.uint8)
    mid=(re+le)/2
    jd=d*rng.normal(1,0.06) if strong else d
    mid=mid+(rng.normal(0,0.06*d,2) if strong else 0)
    return im, mid, jd, d
def eye_feats(L, mid, d):
    # app convention: image-left eye = mid - d/2 (the subject's right eye), mirrored so both look alike
    a=features(fast_patch(L,mid[0]-d/2,mid[1],d,False)); b=features(fast_patch(L,mid[0]+d/2,mid[1],d,True))
    return a,b
if __name__=='__main__':
    o=[x for x in json.load(open('/tmp/claude-0/eyes/landmarks.json')) if x['ok']]
    closed=[x for x in o if x['y']==0]; opn=[x for x in o if x['y']==1]
    random.shuffle(opn); opn=opn[:2400]
    rows=[]
    for gid,x in enumerate(closed+opn):
        im=cv2.imread(x['p'])[:,:,::-1].copy()   # RGB
        reps=4 if x['y']==0 else 2
        for r in range(reps):
            a,mid,d,dn=augment(im,x['re'],x['le'],x['y'],strong=True)
            L=luma(a)
            fa,fb=eye_feats(L,mid,d)
            rows.append((gid,x['y'],dn,fa,fb))
        if gid%500==0: print(gid,flush=True)
    np.savez('/tmp/claude-0/eyes/ds.npz',gid=np.array([r[0] for r in rows]),y=np.array([r[1] for r in rows]),d=np.array([r[2] for r in rows]),
             fa=np.array([r[3] for r in rows]),fb=np.array([r[4] for r in rows]))
    print('rows',len(rows))
