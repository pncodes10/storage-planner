import numpy as np
from sklearn.metrics import roc_auc_score
from sklearn.neural_network import MLPClassifier
z=np.load('/tmp/claude-0/eyes/ds.npz'); gid=z['gid']; y=z['y']; d=z['d']; fa=z['fa']; fb=z['fb']
rng=np.random.default_rng(0); ug=np.unique(gid); rng.shuffle(ug); test=set(ug[:len(ug)//5])
te=np.array([g in test for g in gid]); tr=~te
X=np.concatenate([fa[tr],fb[tr]]); Y=np.concatenate([y[tr],y[tr]])
for h,al in (((16,),1e-2),((32,),3e-2),((32,),1e-1)):
    m=MLPClassifier(h,alpha=al,max_iter=800,random_state=0).fit(X,Y)
    pa=m.predict_proba(fa[te])[:,1]; pb=m.predict_proba(fb[te])[:,1]; p=(pa+pb)/2
    print(h,al,'AUC %.4f acc %.4f'%(roc_auc_score(y[te],p),((p>0.5)==y[te]).mean()))
    # strata: closed faces by native eye distance vs all open
    for lo,hi in ((0,35),(35,70),(70,1e9)):
        sel=te&((y==1)|((d>=lo)&(d<hi)))
        q=(fa[sel],fb[sel]); pp=(m.predict_proba(q[0])[:,1]+m.predict_proba(q[1])[:,1])/2
        print('   closed d in [%d,%d): n_closed=%d AUC %.4f closed-acc %.3f'%(lo,min(hi,999),(sel&(y==0)).sum(),roc_auc_score(y[sel],pp),(pp[y[sel]==0]<0.5).mean()))
