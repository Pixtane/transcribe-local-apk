"""Replaces dynamically-quantized convolutions (DynamicQuantizeLinear → ConvInteger → Cast → Mul)
with float Conv nodes. ONNX Runtime's ConvInteger kernel is far slower on CPU than its optimized
float Conv, and the «Денис» decoder spends ~2/3 of its time there.

usage: dequantize_convs.py in.onnx out.onnx
"""
import sys
import numpy as np
import onnx
from onnx import helper, numpy_helper

src, dst = sys.argv[1], sys.argv[2]
# --compact keeps int8 weights in the file behind DequantizeLinear nodes (ONNX Runtime folds them
# into float weights when the session is created), so the download stays small.
compact = "--compact" in sys.argv
m = onnx.load(src)
g = m.graph
inits = {i.name: i for i in g.initializer}
consts = {}
for n in g.node:
    if n.op_type == "Constant":
        consts[n.output[0]] = numpy_helper.to_array(n.attribute[0].t)

def value(name):
    if name in inits:
        return numpy_helper.to_array(inits[name])
    return consts.get(name)

producer = {o: n for n in g.node for o in n.output}
consumers = {}
for n in g.node:
    for i in n.input:
        consumers.setdefault(i, []).append(n)

remove, add, new_inits = set(), [], []
converted = skipped = 0
for n in g.node:
    if n.op_type != "ConvInteger":
        continue
    xq, wq = n.input[0], n.input[1]
    xzp = n.input[2] if len(n.input) > 2 else ""
    wzp = n.input[3] if len(n.input) > 3 else ""
    dql = producer.get(xq)
    w = value(wq)
    if dql is None or dql.op_type != "DynamicQuantizeLinear" or w is None:
        skipped += 1; continue
    x_float, x_scale = dql.input[0], dql.output[1]
    cast = consumers.get(n.output[0], [])
    if len(cast) != 1 or cast[0].op_type != "Cast":
        skipped += 1; continue
    cast = cast[0]
    mul = consumers.get(cast.output[0], [])
    if len(mul) != 1 or mul[0].op_type != "Mul":
        skipped += 1; continue
    mul = mul[0]
    scale_name = [i for i in mul.input if i != cast.output[0]][0]
    scale_node = producer.get(scale_name)
    # scale = x_scale * w_scale
    if scale_node is None or scale_node.op_type != "Mul" or x_scale not in scale_node.input:
        skipped += 1; continue
    w_scale = value([i for i in scale_node.input if i != x_scale][0])
    if w_scale is None:
        skipped += 1; continue
    w_zero = value(wzp) if wzp else np.zeros(1, dtype=w.dtype)
    wf = (w.astype(np.float32) - w_zero.astype(np.float32).reshape(-1, *([1] * (w.ndim - 1)))
          if w_zero.size > 1 else w.astype(np.float32) - float(w_zero.reshape(-1)[0]))
    ws = w_scale.astype(np.float32)
    wf = wf * (ws.reshape(-1, *([1] * (w.ndim - 1))) if ws.size > 1 else float(ws.reshape(-1)[0]))
    wname = wq + "_dequant"
    if compact:
        sname, zname = wq + "_scale", wq + "_zp"
        new_inits.append(numpy_helper.from_array(ws.reshape(-1).astype(np.float32), sname))
        new_inits.append(numpy_helper.from_array(w_zero.reshape(-1).astype(w.dtype), zname))
        if ws.size > 1:
            add_pre = helper.make_node("DequantizeLinear", [wq, sname, zname], [wname], axis=0, name=n.name + "_dq")
        else:
            new_inits[-2] = numpy_helper.from_array(np.array(float(ws.reshape(-1)[0]), dtype=np.float32), sname)
            new_inits[-1] = numpy_helper.from_array(np.array(w_zero.reshape(-1)[0], dtype=w.dtype), zname)
            add_pre = helper.make_node("DequantizeLinear", [wq, sname, zname], [wname], name=n.name + "_dq")
    else:
        add_pre = None
        new_inits.append(numpy_helper.from_array(wf.astype(np.float32), wname))
    conv = helper.make_node("Conv", [x_float, wname], [mul.output[0]], name=n.name + "_float")
    conv.attribute.extend(n.attribute)
    add.append((n, [add_pre, conv] if add_pre is not None else [conv]))
    remove.update([id(n), id(cast), id(mul)])
    converted += 1

keep = []
for n in g.node:
    if id(n) in remove:
        for (orig, nodes) in add:
            if orig is n:
                keep.extend(nodes)
        continue
    keep.append(n)
del g.node[:]
g.node.extend(keep)
g.initializer.extend(new_inits)

# Drop nodes/initializers that are no longer used (DynamicQuantizeLinear, scale Muls, int8 weights).
changed = True
outputs = {o.name for o in g.output}
while changed:
    used = set(outputs)
    for n in g.node:
        used.update(n.input)
    dead = [n for n in g.node if not any(o in used for o in n.output)]
    changed = bool(dead)
    for n in dead:
        g.node.remove(n)
used = {i for n in g.node for i in n.input}
alive = [i for i in g.initializer if i.name in used]
del g.initializer[:]
g.initializer.extend(alive)
onnx.save(m, dst)
print(f"converted {converted} ConvInteger nodes, skipped {skipped}")
