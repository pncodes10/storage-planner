"""Expected SceneNet numbers for SceneTest: Keras's MobileNetV3Small given exactly the weights in scene.bin.

The folded 16-bit weights from scene.bin are put back into Keras (batch normalisation set to "add the bias"), the
network is run on a fixed made-up input (the same formula as SceneTest.pattern), and the top-5 classes and some
outputs are printed for the test.
Usage: python3 parity.py scene.bin
"""
import struct, sys
import numpy as np
import tensorflow as tf

m = tf.keras.applications.MobileNetV3Small(input_shape=(224, 224, 3), weights=None, include_top=True,
                                            include_preprocessing=False, classifier_activation=None)
b = open(sys.argv[1], 'rb').read()
assert b[:4] == b'MNV3'
n = struct.unpack('<i', b[8:12])[0]; o = 12
convs = [l for l in m.layers if type(l).__name__ in ('Conv2D', 'DepthwiseConv2D')]
assert len(convs) == n
by = {l.name: l for l in m.layers}
for l in convs:
    dw, k, s, cin, cout = struct.unpack('<iiiii', b[o:o + 20]); o += 20
    nw = cout * (1 if dw else cin) * k * k
    W = np.frombuffer(b, '<f2', nw, o).astype(np.float32); o += 2 * nw
    B = np.frombuffer(b, '<f2', cout, o).astype(np.float32); o += 2 * cout
    if dw: Wk = W.reshape(cout, k, k).transpose(1, 2, 0)[:, :, :, None]
    else: Wk = W.reshape(cout, cin, k, k).transpose(2, 3, 1, 0)
    bn = by.get(l.name + '_bn')
    if bn is not None:
        l.set_weights([Wk])
        bn.set_weights([np.ones(cout, np.float32), B, np.zeros(cout, np.float32), np.full(cout, 1 - bn.epsilon, np.float32)])
    else:
        l.set_weights([Wk, B])
yy, xx = np.mgrid[0:224, 0:224].astype(np.float64)
x = np.stack([np.sin(0.05 * xx * (c + 1) + 0.3 * c) * np.cos(0.07 * yy - 0.2 * c) * 0.9 for c in range(3)], -1)[None].astype(np.float32)
logits = m.predict(x, verbose=0)[0]
feat = tf.keras.Model(m.input, m.get_layer('dropout').input)(x).numpy().reshape(-1)
print('top5', list(np.argsort(-logits)[:5]))
print('logits', ', '.join('%.4ff' % logits[i] for i in np.argsort(-logits)[:5]))
print('features', ', '.join('%.4ff' % v for v in feat[:8]))
