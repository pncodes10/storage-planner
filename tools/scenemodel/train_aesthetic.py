"""Trains the aesthetic score (core/Aesthetic.java): a linear layer on SceneNet's 1024 features that copies NIMA.

Teacher: NIMA (Talebi & Milanfar 2018), MobileNet aesthetic model by idealo (github.com/idealo/image-quality-assessment,
Apache 2.0), trained on the AVA set of 255,000 photos rated 1-10 by photographers. Its mean rating is the target.
Pictures: Imagenette, the TensorFlow flower photos, Caltech-101 and Oxford-IIIT Pets (33,598), plus 8,399 spoiled
copies (blurred, shaken, dark, washed out, crushed JPEG, pixelated, tilted, hazy, pocket shots) so the score sees bad
photos too. Features are SceneNet's, computed by the Java code (tools/scenemodel/SceneDump.java). Every 5th picture,
with its spoiled copy, is held out.
Usage: python3 train_aesthetic.py F.npy nima.tsv all.txt deg.txt Aesthetic.java heldout.tsv list.txt
"""
import base64, sys
import numpy as np
from scipy.stats import pearsonr, spearmanr
from sklearn.linear_model import Ridge

F = np.load(sys.argv[1]); X = F[:, :1024].astype(np.float64)
y = np.array([float(l.split('\t')[0]) for l in open(sys.argv[2])])
n_all = sum(1 for _ in open(sys.argv[3]))
deg = [l.rstrip('\n').split('\t') for l in open(sys.argv[4])]
orig = np.arange(len(X)); orig[n_all:] = [int(d[1]) for d in deg]
kind = np.array(['original'] * n_all + [d[2] for d in deg])
ok = ~np.isnan(X[:, 0])
test = ok & (orig % 5 == 0); train = ok & ~test
mu = X[train].mean(0); sd = X[train].std(0) + 1e-3
r = Ridge(alpha=100).fit((X[train] - mu) / sd, y[train])
w = r.coef_ / sd; b = r.intercept_ - (r.coef_ * mu / sd).sum()
p = X @ w + b
print('held-out pictures %d: Pearson %.3f, Spearman %.3f, mean error %.2f points' % (test.sum(), pearsonr(p[test], y[test])[0], spearmanr(p[test], y[test])[0], np.abs(p[test] - y[test]).mean()))
for k in sorted(set(kind)):
    m = test & (kind == k)
    print('  %-9s n=%5d  NIMA %.2f  ours %.2f' % (k, m.sum(), y[m].mean(), p[m].mean()))
flat = np.concatenate([w, [b]]).astype('<f4')
b64 = base64.b64encode(flat.tobytes()).decode()
lines = [b64[i:i + 120] for i in range(0, len(b64), 120)]
src = open(sys.argv[5]).read()
start = src.index('    static final String DATA =')
end = src.index(';', start) + 1
src = src[:start] + '    static final String DATA =\n' + ' +\n'.join('        "%s"' % l for l in lines) + ';' + src[end:]
open(sys.argv[5], 'w').write(src)
paths = [l.strip() for l in open(sys.argv[7])]
with open(sys.argv[6], 'w') as f:                       # for the Java test: picture, Python's score from Java's features, NIMA
    for i in np.where(test)[0][::40]: f.write('%s\t%.5f\t%.4f\n' % (paths[i], p[i], y[i]))
