"""Profiles the «Денис» model and tries speed-ups (run in CI)."""
import json, os, sys, time, collections
import numpy as np, onnx, onnxruntime as ort

pack = sys.argv[1]
SENT = "ʊˈt͡ʃɔrɐ ˈʋːɛt͡ʃerʲi mɪ ˈdɔu̯ɦɔ rɔzmɔu̯ˈlʲaɫɪ prɔ mɐi̯ˈbutʲnʲe: prɔ nɐu̯ˈt͡ʃanʲːɐ ʋ ʊnʲiʋersɪˈtɛtʲi, prɔ ˈpɔdɔrɔʒʲi tɐ prɔ te, kɪm ˈkɔʒen iz nɐs ˈxɔt͡ʃe ˈstatɪ ˈt͡ʃɛrez ˈdɛsʲɐtʲ ˈrɔkʲiʋ."
tok = {}
for line in open(os.path.join(pack, "tokens.txt"), encoding="utf-8"):
    line = line.rstrip("\n")
    parts = line.rsplit(" ", 1)
    if len(parts) == 1: tok[" "] = int(parts[0])
    else: tok[parts[0]] = int(parts[1])
ids = np.array([[0] + [tok[c] for c in SENT if c in tok] + [0]], dtype=np.int64)
style = np.fromfile(os.path.join(pack, "voices.bin"), dtype=np.float32)[:256].reshape(1, 256)
feeds = {"x": ids, "style": style, "speed_in": np.array([1.0], dtype=np.float32)}

m = onnx.load(os.path.join(pack, "model.onnx"))
ops = collections.Counter(n.op_type for n in m.graph.node)
print("nodes:", len(m.graph.node), ops.most_common(25))
types = collections.Counter(TensorProtoName for TensorProtoName in (i.data_type for i in m.graph.initializer))
print("initializer dtypes:", types, "count", len(m.graph.initializer))

def run(path, threads, label, profile=False):
    so = ort.SessionOptions(); so.intra_op_num_threads = threads; so.inter_op_num_threads = 1
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    if profile: so.enable_profiling = True
    s = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
    s.run(None, feeds)  # warm-up
    t0 = time.time(); out = s.run(None, feeds)[0]; dt = time.time() - t0
    audio = out.size / 24000
    print(f"{label:<28} threads={threads}  {dt:6.2f}s for {audio:5.2f}s audio  RTF {dt/audio:.3f}  {len(SENT)/dt:5.1f} chars/s")
    if profile:
        prof = s.end_profiling()
        ev = json.load(open(prof))
        by_op = collections.Counter(); by_name = collections.Counter()
        for e in ev:
            if e.get("cat") == "Node" and e.get("name", "").endswith("_kernel_time"):
                by_op[e["args"].get("op_name", "?")] += e["dur"]
                name = e["name"].replace("_kernel_time", "")
                by_name[name.split("/")[1] if name.startswith("/") and "/" in name[1:] else name[:30]] += e["dur"]
        tot = sum(by_op.values())
        print("  time by op:", [(k, f"{v/tot:.0%}") for k, v in by_op.most_common(12)])
        print("  time by module:", [(k, f"{v/tot:.0%}") for k, v in by_name.most_common(12)])
    return out

base = os.path.join(pack, "model.onnx")
run(base, 1, "fp32 baseline", profile=True)
run(base, 2, "fp32")
run(base, 4, "fp32")

from onnxruntime.quantization import quantize_dynamic, QuantType
q1 = "/tmp/q_matmul.onnx"
quantize_dynamic(base, q1, weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gemm", "LSTM"])
print("int8 MatMul/Gemm/LSTM size MB:", os.path.getsize(q1) // 2**20)
out_q1 = run(q1, 1, "int8 MatMul+LSTM", profile=True)
q2 = "/tmp/q_all.onnx"
quantize_dynamic(base, q2, weight_type=QuantType.QUInt8, op_types_to_quantize=["MatMul", "Gemm", "LSTM", "Conv"])
print("int8 +Conv size MB:", os.path.getsize(q2) // 2**20)
out_q2 = run(q2, 1, "int8 MatMul+LSTM+Conv")

import soundfile as sf
os.makedirs("out", exist_ok=True)
sf.write("out/denys_fp32.wav", run(base, 1, "fp32 (for wav)"), 24000)
sf.write("out/denys_int8_matmul.wav", out_q1, 24000)
sf.write("out/denys_int8_all.wav", out_q2, 24000)
os.system("cp /tmp/q_matmul.onnx out/ 2>/dev/null")
