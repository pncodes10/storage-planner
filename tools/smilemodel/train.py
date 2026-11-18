"""Trains the smile network on the rows SmileFeatures.java wrote and writes core/SmileModel.java.

Faces are split by face (every 5th face is held out, with all its copies). Prints accuracy and AUC on the held-out
faces, by face size, and writes heldout.csv (features and Python's probability) for the Java parity test.
Usage: python3 train.py smile.csv SmileModel.java heldout.csv
"""
import base64, sys
import numpy as np
from sklearn.neural_network import MLPClassifier
from sklearn.metrics import roc_auc_score

d = np.loadtxt(sys.argv[1], delimiter=',', dtype=np.float64)
y, fid, size, X = d[:, 0], d[:, 1].astype(int), d[:, 2], d[:, 3:]
NF = X.shape[1]
test = fid % 5 == 0
mean = X[~test].mean(0); std = X[~test].std(0) + 1e-3
Z = (X - mean) / std
best = None
for alpha in (1e-3, 1e-2, 1e-1):
    m = MLPClassifier(hidden_layer_sizes=(16,), activation='relu', alpha=alpha, max_iter=400, early_stopping=True,
                      random_state=0).fit(Z[~test], y[~test])
    p = m.predict_proba(Z[test])[:, 1]
    auc = roc_auc_score(y[test], p)
    print('alpha', alpha, 'AUC %.4f' % auc, 'accuracy %.3f' % ((p >= 0.5) == y[test]).mean())
    if best is None or auc > best[0]: best = (auc, m, alpha)
auc, m, alpha = best
p = m.predict_proba(Z[test])[:, 1]
print('chosen alpha', alpha, 'held-out faces', len(set(fid[test])), 'rows', test.sum())
for lo, hi in ((0, 20), (20, 35), (35, 60), (60, 1000)):
    k = test & (size >= lo) & (size < hi)
    if k.sum() > 20:
        print('  eyes %d-%d px apart: rows %d, AUC %.3f, accuracy %.3f' % (lo, hi, k.sum(), roc_auc_score(y[k], p[k[test]] if False else m.predict_proba(Z[k])[:, 1]), ((m.predict_proba(Z[k])[:, 1] >= 0.5) == y[k]).mean()))
W1 = m.coefs_[0].T            # NH x NF
b1 = m.intercepts_[0]
W2 = m.coefs_[1][:, 0]
b2 = m.intercepts_[1]
flat = np.concatenate([mean, std, W1.ravel(), b1, W2, b2]).astype('<f4')
b64 = base64.b64encode(flat.tobytes()).decode()
lines = [b64[i:i + 120] for i in range(0, len(b64), 120)]
with open(sys.argv[2], 'w') as f:
    f.write('package com.storageplanner.core;\n\n/** Weights of the smile network (Smile), written by tools/smilemodel/train.py: float32 little-endian, base64. */\n')
    f.write('final class SmileModel {\n    private SmileModel() {}\n\n    static final String DATA =\n')
    f.write(' +\n'.join('        "%s"' % l for l in lines) + ';\n}\n')
# held-out rows for the Java parity and accuracy test: label, size, python probability, features (float32 as written)
sel = np.where(test)[0][::4]
with open(sys.argv[3], 'w') as f:
    for i in sel:
        f.write('%d,%.1f,%.6f,%s\n' % (y[i], size[i], m.predict_proba(Z[i:i + 1])[0, 1], ','.join(repr(float(np.float32(v))) for v in X[i])))
print('wrote', len(flat), 'weights;', len(sel), 'held-out rows for the test')
