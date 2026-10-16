import cv2, glob, json, os, numpy as np
D='/tmp/claude-0/ds/jesusmartinoza_Open-eyes-using-Pix2Pix'
det=cv2.FaceDetectorYN.create('/tmp/claude-0/models/yunet.onnx','',(320,320),0.6,0.3,5000)
out=[]
for label,pat in [(0,'closed_eyes_dataset/*.jpg'),(1,'regular_faces/*.jpg')]:
    for p in sorted(glob.glob(os.path.join(D,pat))):
        im=cv2.imread(p)
        if im is None: continue
        h,w=im.shape[:2]
        pad=max(h,w)//2
        big=cv2.copyMakeBorder(im,pad,pad,pad,pad,cv2.BORDER_REPLICATE)
        s=320/max(big.shape[:2]); 
        sm=cv2.resize(big,None,fx=s,fy=s,interpolation=cv2.INTER_AREA if s<1 else cv2.INTER_LINEAR)
        det.setInputSize((sm.shape[1],sm.shape[0]))
        _,f=det.detect(sm)
        if f is None or len(f)==0: out.append({'p':p,'y':label,'w':w,'h':h,'ok':False}); continue
        f=f[np.argmax(f[:,14])]
        pts=(f[:14].reshape(-1,2)/s-pad)
        out.append({'p':p,'y':label,'w':w,'h':h,'ok':True,'box':pts[0:2].ravel().tolist(),'re':pts[2].tolist(),'le':pts[3].tolist(),
                    'nose':pts[4].tolist(),'score':float(f[14])})
json.dump(out,open('/tmp/claude-0/eyes/landmarks.json','w'))
import collections
c=collections.Counter((o['y'],o['ok']) for o in out); print(c)
