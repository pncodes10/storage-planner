import json, numpy as np, cv2, sys, base64, struct
sys.path.insert(0,'/tmp/claude-0/eyes')
from feat import *
from build_ds import fast_patch
raw=base64.b64decode(open('/tmp/claude-0/eyes/eye_mlp.b64').read()); w=np.array(struct.unpack('<%df'%(len(raw)//4),raw),dtype=np.float32).astype(np.float64)
NF,NH=304,16
W1=w[:NH*NF].reshape(NH,NF); b1=w[NH*NF:NH*NF+NH]; W2=w[NH*NF+NH:NH*NF+2*NH]; b2=w[-1]
def pred(f): h=W1@f+b1; z=b2+(np.maximum(h,0)*W2).sum(); return 1/(1+np.exp(-z))
o=[x for x in json.load(open('/tmp/claude-0/eyes/landmarks.json')) if x['ok']]
closed=[x for x in o if x['y']==0]; import random; random.seed(7)
opn=[x for x in o if x['y']==1]; random.shuffle(opn); opn=opn[:2400]
allx=closed+opn
test=set(json.load(open('/tmp/claude-0/eyes/split.json'))['test_gids'])
out=[]
import os; os.makedirs('/tmp/claude-0/eyes/pgm',exist_ok=True)
for gid,x in enumerate(allx):
    if gid not in test: continue
    im=cv2.imread(x['p'])[:,:,::-1].copy(); L=luma(im)
    re=np.array(x['re']); le=np.array(x['le']); mid=(re+le)/2; d=float(np.hypot(*(re-le)))
    pa=pred(features(fast_patch(L,mid[0]-d/2,mid[1],d,False))); pb=pred(features(fast_patch(L,mid[0]+d/2,mid[1],d,True)))
    pg='/tmp/claude-0/eyes/pgm/%d.pgm'%gid
    h,ww=L.shape; open(pg,'wb').write(b'P5\n%d %d\n255\n'%(ww,h)+L.astype(np.uint8).tobytes())
    out.append({'gid':gid,'y':x['y'],'img':x['p'],'pgm':pg,'mx':mid[0],'my':mid[1],'d':d,'pa':pa,'pb':pb,'score':x['score']})
json.dump(out,open('/tmp/claude-0/eyes/heldout.json','w'))
ys=np.array([r['y'] for r in out]); p=np.array([(r['pa']+r['pb'])/2 for r in out])
from sklearn.metrics import roc_auc_score
print(len(out),'held-out faces (clean, exact landmarks): AUC %.4f acc %.4f closed-recall %.3f open-recall %.3f'%(roc_auc_score(ys,p),((p>0.5)==ys).mean(),(p[ys==0]<0.5).mean(),(p[ys==1]>=0.5).mean()))
