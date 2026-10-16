import numpy as np, json, base64, struct
from sklearn.metrics import roc_auc_score
from sklearn.neural_network import MLPClassifier
z=np.load('/tmp/claude-0/eyes/ds.npz'); gid=z['gid']; y=z['y']; fa=z['fa']; fb=z['fb']
rng=np.random.default_rng(0); ug=np.unique(gid); rng.shuffle(ug); test=sorted(int(g) for g in ug[:len(ug)//5])
te=np.isin(gid,test); tr=~te
X=np.concatenate([fa[tr],fb[tr]]); Y=np.concatenate([y[tr],y[tr]])
m=MLPClassifier((16,),alpha=1e-2,max_iter=800,random_state=0).fit(X,Y)
W1=m.coefs_[0].astype(np.float32); b1=m.intercepts_[0].astype(np.float32); W2=m.coefs_[1][:,0].astype(np.float32); b2=np.float32(m.intercepts_[1][0])
print(W1.shape, b1.shape, W2.shape)
blob=W1.T.ravel().tolist()+b1.tolist()+W2.tolist()+[float(b2)]     # W1 stored row per hidden unit
raw=struct.pack('<%df'%len(blob),*blob)
open('/tmp/claude-0/eyes/eye_mlp.b64','w').write(base64.b64encode(raw).decode())
json.dump({'test_gids':test},open('/tmp/claude-0/eyes/split.json','w'))
# float32 forward pass (what Java does) vs sklearn
def fwd(f):
    h=np.maximum(0,(W1.T.astype(np.float64)@f+b1)); z=h@W2+b2; return 1/(1+np.exp(-z))
f=fa[te][:50]; print('fwd parity', max(abs(fwd(v)-m.predict_proba(v[None])[0,1]) for v in f))
print('b64 chars',len(base64.b64encode(raw)))
