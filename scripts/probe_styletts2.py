"""Diagnostics for the StyleTTS2 Ukrainian ONNX voice pack (run in CI)."""
import gzip, json, os, sqlite3, sys, time
import numpy as np

def inspect(d):
    print(open(os.path.join(d, "manifest.json")).read())
    vocab = open(os.path.join(d, "vocab.txt"), encoding="utf-8").read()
    print("VOCAB repr:", repr(vocab))
    import onnx
    m = onnx.load(os.path.join(d, "model.onnx"), load_external_data=False)
    print("opset:", [(o.domain, o.version) for o in m.opset_import])
    print("metadata:", {p.key: p.value for p in m.metadata_props})
    for i in m.graph.input:
        print("INPUT", i.name, i.type)
    for o in m.graph.output:
        print("OUTPUT", o.name, o.type)
    style = np.fromfile(os.path.join(d, "style.bin"), dtype=np.float32)
    print("style", style.shape, style[:5])
    raw = gzip.decompress(open(os.path.join(d, "stress.sqlite.gz"), "rb").read())
    open("/tmp/stress.sqlite", "wb").write(raw)
    db = sqlite3.connect("/tmp/stress.sqlite")
    for (name, sql) in db.execute("select name, sql from sqlite_master"):
        print("TABLE", name, sql)
        try:
            for row in db.execute(f"select * from {name} limit 8"):
                print("   ", row)
            print("    count:", db.execute(f"select count(*) from {name}").fetchone())
        except Exception as e:
            print("   ", e)

if __name__ == "__main__":
    cmd, d = sys.argv[1], sys.argv[2]
    if cmd == "inspect":
        inspect(d)
    else:
        print("render: implemented after inspection")
        os.makedirs(sys.argv[3], exist_ok=True)
        open(os.path.join(sys.argv[3], "placeholder.txt"), "w").write("ok")
