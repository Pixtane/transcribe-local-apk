"""Builds the «Денис» Ukrainian voice pack for Holos from the StyleTTS2 Ukrainian ONNX export.

Input:  directory with model.onnx (+ model.onnx.data), style.bin, vocab.txt, stress.sqlite.gz
Output: directory with model.onnx (single file, sherpa-onnx Kokoro-compatible I/O and metadata),
        voices.bin, tokens.txt, stress.sqlite.gz and license files.

sherpa-onnx's Kokoro runner feeds (tokens[1,N], style[1,256], speed[1]) positionally, while the
export takes (tokens[N], speed[], s_prev[1,256]); a few Squeeze/Identity nodes bridge the two.
"""
import os, shutil, sys
import numpy as np
import onnx
from onnx import TensorProto, helper

src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)

m = onnx.load(os.path.join(src, "model.onnx"))
g = m.graph
assert {i.name for i in g.input} == {"tokens", "speed", "s_prev"}
del g.input[:]
g.input.extend([
    helper.make_tensor_value_info("x", TensorProto.INT64, [1, "N"]),
    helper.make_tensor_value_info("style", TensorProto.FLOAT, [1, 256]),
    helper.make_tensor_value_info("speed_in", TensorProto.FLOAT, [1]),
])
g.initializer.append(helper.make_tensor("axes0", TensorProto.INT64, [1], [0]))
g.node.insert(0, helper.make_node("Squeeze", ["x", "axes0"], ["tokens"]))
g.node.insert(1, helper.make_node("Squeeze", ["speed_in", "axes0"], ["speed"]))
g.node.insert(2, helper.make_node("Identity", ["style"], ["s_prev"]))
del m.metadata_props[:]
for k, v in {
    "sample_rate": "24000", "n_speakers": "1", "version": "1", "has_espeak": "0", "voice": "uk",
    "style_dim": "510,1,256", "model_type": "kokoro",
    "comment": "StyleTTS2 Ukrainian multispeaker (patriotyk, MIT), speaker Denys, wrapped for sherpa-onnx",
}.items():
    m.metadata_props.add(key=k, value=v)
onnx.save(m, os.path.join(dst, "model.onnx"))

style = np.fromfile(os.path.join(src, "style.bin"), dtype=np.float32).reshape(1, 256)
np.tile(style, (510, 1)).astype(np.float32).tofile(os.path.join(dst, "voices.bin"))

symbols = open(os.path.join(src, "vocab.txt"), encoding="utf-8").read().rstrip("\n")
table = {}
for i, ch in enumerate(symbols):
    table[ch] = i  # StyleTTS2 TextCleaner semantics: later duplicates win
with open(os.path.join(dst, "tokens.txt"), "w", encoding="utf-8") as f:
    for ch, idx in sorted(table.items(), key=lambda kv: kv[1]):
        f.write(f"{idx}\n" if ch == " " else f"{ch} {idx}\n")

for name in ["stress.sqlite.gz", "LICENSE-styletts2-ukrainian.txt", "LICENSE-ukrainian-word-stress.txt"]:
    shutil.copy(os.path.join(src, name), os.path.join(dst, name))
for name in sorted(os.listdir(dst)):
    print(f"{os.path.getsize(os.path.join(dst, name)):>12}  {name}")
