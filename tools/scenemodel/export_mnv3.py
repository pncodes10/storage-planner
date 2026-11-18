"""Writes MobileNetV3-Small (Keras Applications ImageNet weights, Apache 2.0) as the binary core/SceneNet.java reads.

Every convolution in the network's order, with batch normalisation folded in:
  "MNV3", int32 version (1), int32 count, then per convolution:
  int32 kind (0 = normal, 1 = depthwise), k, stride, cin, cout, then float16 weights [cout][cin/groups][k][k]
  and float16 bias [cout] (zeros where the layer has none).
The order and the network's shape (which layers have squeeze-excite, which activation) are fixed in SceneNet.java.
Usage: python3 export_mnv3.py weights_mobilenet_v3_small_224_1.0_float.h5 scene.bin
"""
import struct, sys
import numpy as np
import tensorflow as tf

m = tf.keras.applications.MobileNetV3Small(input_shape=(224, 224, 3), weights=sys.argv[1], include_top=True,
                                            include_preprocessing=True)
by = {l.name: l for l in m.layers}
recs = []
for l in m.layers:
    kind = type(l).__name__
    if kind not in ('Conv2D', 'DepthwiseConv2D'):
        continue
    ws = l.get_weights()
    w = ws[0]
    k = w.shape[0]
    if kind == 'Conv2D':
        cin, cout = w.shape[2], w.shape[3]
        W = np.transpose(w, (3, 2, 0, 1)).astype(np.float64)        # cout, cin, k, k
        dw = 0
    else:
        cin = cout = w.shape[2]
        W = np.transpose(w[:, :, :, 0], (2, 0, 1))[:, None, :, :].astype(np.float64)
        dw = 1
    b = ws[1].astype(np.float64) if len(ws) > 1 else np.zeros(cout)
    bn = by.get(l.name + '_bn')
    if bn is not None:
        g, beta, mean, var = [x.astype(np.float64) for x in bn.get_weights()]
        s = g / np.sqrt(var + bn.epsilon)
        W = W * s[:, None, None, None]
        b = (b - mean) * s + beta
    recs.append((dw, k, l.strides[0], cin, cout, W, b, l.name))

with open(sys.argv[2], 'wb') as f:
    f.write(b'MNV3'); f.write(struct.pack('<ii', 1, len(recs)))
    for dw, k, s, cin, cout, W, b, name in recs:
        f.write(struct.pack('<iiiii', dw, k, s, cin, cout))
        f.write(W.astype('<f2').tobytes()); f.write(b.astype('<f2').tobytes())
print(len(recs), 'convolutions,', sum(r[5].size + r[6].size for r in recs), 'weights')
