"""Eye-patch features, written so the Java port is line-for-line (no library image ops)."""
import numpy as np, math
PW, PH = 24, 16          # patch size
SPAN_W, SPAN_H = 0.75, 0.5   # patch covers this many eye-distances
CELL = 4; BINS = 8

def luma(rgb):           # rgb uint8 HxWx3 -> float luma, same integer formula as the app
    r=rgb[...,0].astype(np.int32); g=rgb[...,1].astype(np.int32); b=rgb[...,2].astype(np.int32)
    return ((299*r+587*g+114*b)//1000).astype(np.float64)

def sample(L, x, y):     # bilinear, clamped
    h,w=L.shape
    x=min(max(x,0.0),w-1.0); y=min(max(y,0.0),h-1.0)
    x0=int(math.floor(x)); y0=int(math.floor(y)); x1=min(x0+1,w-1); y1=min(y0+1,h-1)
    fx=x-x0; fy=y-y0
    return (L[y0,x0]*(1-fx)+L[y0,x1]*fx)*(1-fy)+(L[y1,x0]*(1-fx)+L[y1,x1]*fx)*fy

def patch(L, cx, cy, d, mirror):
    """PWxPH patch centred on an eye; each pixel = mean of 3x3 bilinear sub-samples (anti-aliasing)."""
    P=np.zeros((PH,PW))
    sw=SPAN_W*d/PW; sh=SPAN_H*d/PH
    for j in range(PH):
        for i in range(PW):
            ii = (PW-1-i) if mirror else i
            acc=0.0
            for sy in range(3):
                for sx in range(3):
                    x=cx+((ii+(sx+0.5)/3.0)-PW/2.0)*sw* (1)
                    y=cy+((j+(sy+0.5)/3.0)-PH/2.0)*sh
                    acc+=sample(L,x,y)
            P[j,i]=acc/9.0
    return P

def features(P):
    m=P.mean(); s=P.std()
    N=(P-m)/(s+4.0)
    f=[]
    # gradient orientation histograms per 4x4 cell (unsigned, 8 bins, linear bin interpolation)
    for cy in range(PH//CELL):
        for cx in range(PW//CELL):
            hst=[0.0]*BINS
            for y in range(cy*CELL,cy*CELL+CELL):
                for x in range(cx*CELL,cx*CELL+CELL):
                    gx=N[y,min(x+1,PW-1)]-N[y,max(x-1,0)]
                    gy=N[min(y+1,PH-1),x]-N[max(y-1,0),x]
                    mag=math.sqrt(gx*gx+gy*gy)
                    ang=math.atan2(gy,gx)
                    if ang<0: ang+=math.pi
                    if ang>=math.pi: ang-=math.pi
                    pos=ang/math.pi*BINS-0.5
                    b0=int(math.floor(pos)); fr=pos-b0
                    hst[b0%BINS]+=mag*(1-fr); hst[(b0+1)%BINS]+=mag*fr
            f.extend(hst)
    g=np.array(f); g=g/(math.sqrt((g*g).sum())+1e-3)
    # coarse intensity layout 12x8 (2x2 means)
    I=[]
    for y in range(0,PH,2):
        for x in range(0,PW,2):
            I.append((N[y,x]+N[y,x+1]+N[y+1,x]+N[y+1,x+1])/4.0)
    # row profile: darkness by row (eyelid line vs iris disc)
    R=[N[y].min() for y in range(PH)]
    return np.concatenate([g, np.array(I)*0.25, np.array(R)*0.25])
