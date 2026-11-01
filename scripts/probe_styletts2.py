"""Diagnostics for the StyleTTS2 Ukrainian ONNX voice pack (run in CI).

inspect: print model I/O and dictionary format.
render:  stress (dictionary) -> IPA (ipa-uk) -> StyleTTS2, plus a sherpa-onnx-compatible
         rewrap of the model, and Supertonic 3 on the same sentences for comparison.
"""
import gzip, json, os, re, sqlite3, sys, time, unicodedata
import numpy as np

ACUTE = "́"
SENTENCES = [
    "Добрий вечір! Мене звати Денис, і я читатиму ваші тексти українською мовою.",
    "Замок стоїть на горі, а на дверях висить новий замок.",
    "Учора ввечері ми довго розмовляли про майбутнє: про навчання в університеті, про подорожі та про те, ким кожен із нас хоче стати через десять років.",
    "Найкращі книжки завжди залишають після себе більше запитань, ніж відповідей.",
    "Чи знаєте ви, що Київ — одне з найстаріших міст Європи?",
]


def open_dict(d):
    path = "/tmp/stress.sqlite"
    if not os.path.exists(path):
        open(path, "wb").write(gzip.decompress(open(os.path.join(d, "stress.sqlite.gz"), "rb").read()))
    return sqlite3.connect(path)


def stress_word(db, word):
    low = word.lower()
    try:
        key = low.replace("'", "’").encode("cp1251")
    except UnicodeEncodeError:
        return word
    row = db.execute("select stress from prefer where word=?", (key,)).fetchone() or \
        db.execute("select stress from stress where word=?", (key,)).fetchone()
    if row is None:
        key2 = low.replace("’", "'").encode("cp1251", errors="ignore")
        row = db.execute("select stress from stress where word=?", (key2,)).fetchone()
    if row is None:
        return word
    blob = list(row[0])
    if 254 in blob:
        # Heteronyms: records "positions… 254 tags… 255"; without context take the first record.
        blob = blob[:blob.index(254)]
    positions = sorted(set(blob), reverse=True)
    out = word
    for p in positions:
        if 0 < p <= len(out):
            out = out[:p] + ACUTE + out[p:]
    return out


def stressify(db, text):
    return re.sub(r"[А-Яа-яЄєІіЇїҐґ'’ʼ]+", lambda m: stress_word(db, m.group(0)), text)


def inspect(d):
    print(open(os.path.join(d, "manifest.json")).read())
    db = open_dict(d)
    for w in ["мама", "замок", "український", "говорити", "університеті", "читатиму", "Київ", "найстаріших"]:
        row = db.execute("select stress from stress where word=?", (w.lower().encode("cp1251"),)).fetchone()
        print(w, list(row[0]) if row else None, "->", stress_word(db, w))
    for s in SENTENCES:
        print(stressify(db, s))


def load_vocab(d):
    symbols = open(os.path.join(d, "vocab.txt"), encoding="utf-8").read()
    if symbols.endswith("\n"):
        symbols = symbols[:-1]
    table = {}
    for i, ch in enumerate(symbols):
        table[ch] = i  # StyleTTS2 TextCleaner: later duplicates win
    return symbols, table


def render(d, out):
    import onnx, onnxruntime as ort, soundfile as sf
    from ipa_uk import ipa
    os.makedirs(out, exist_ok=True)
    db = open_dict(d)
    symbols, table = load_vocab(d)
    style = np.fromfile(os.path.join(d, "style.bin"), dtype=np.float32).reshape(1, 256)
    so = ort.SessionOptions(); so.intra_op_num_threads = 1
    sess = ort.InferenceSession(os.path.join(d, "model.onnx"), so)
    report = []
    total_chars = 0; total_time = 0.0; total_audio = 0.0
    for i, s in enumerate(SENTENCES, 1):
        stressed = stressify(db, s)
        ps = ipa(unicodedata.normalize("NFC", stressed).replace("ʼ", "'"))
        missing = sorted(set(c for c in ps if c not in table))
        ids = [0] + [table[c] for c in ps if c in table] + [0]
        t0 = time.time()
        wav = sess.run(None, {"tokens": np.array(ids, dtype=np.int64), "speed": np.array(1.0, dtype=np.float32), "s_prev": style})[0]
        dt = time.time() - t0
        sr = 24000
        sf.write(os.path.join(out, f"styletts2_{i}.wav"), wav, sr)
        total_chars += len(s); total_time += dt; total_audio += len(wav) / sr
        report.append(f"[{i}] {s}\n    stressed: {stressed}\n    ipa: {ps}\n    missing symbols: {missing}\n    {len(wav)/sr:.2f}s audio in {dt:.2f}s")
    report.append(f"StyleTTS2 (1 thread): {total_chars / total_time:.0f} chars/s, RTF {total_time / total_audio:.3f}")

    # sherpa-onnx compatible rewrap: inputs (tokens[1,N], style[1,256], speed[1]) + Kokoro metadata.
    from onnx import helper, TensorProto
    m = onnx.load(os.path.join(d, "model.onnx"))
    g = m.graph
    old_inputs = {i.name for i in g.input}
    assert old_inputs == {"tokens", "speed", "s_prev"}, old_inputs
    del g.input[:]
    g.input.extend([
        helper.make_tensor_value_info("x", TensorProto.INT64, [1, "N"]),
        helper.make_tensor_value_info("style", TensorProto.FLOAT, [1, 256]),
        helper.make_tensor_value_info("speed_in", TensorProto.FLOAT, [1]),
    ])
    axes0 = helper.make_tensor("axes0", TensorProto.INT64, [1], [0])
    g.initializer.append(axes0)
    g.node.insert(0, helper.make_node("Squeeze", ["x", "axes0"], ["tokens"]))
    g.node.insert(1, helper.make_node("Squeeze", ["speed_in", "axes0"], ["speed"]))
    g.node.insert(2, helper.make_node("Identity", ["style"], ["s_prev"]))
    meta = {"sample_rate": "24000", "n_speakers": "1", "version": "1", "has_espeak": "0",
            "voice": "uk", "style_dim": "510,1,256", "model_type": "kokoro",
            "comment": "StyleTTS2 Ukrainian (patriotyk, MIT) wrapped for sherpa-onnx"}
    del m.metadata_props[:]
    for k, v in meta.items():
        m.metadata_props.add(key=k, value=v)
    pack = os.path.join(out, "holos-uk-styletts2")
    os.makedirs(pack, exist_ok=True)
    onnx.save(m, os.path.join(pack, "model.onnx"))  # single file (< 2 GB)
    np.tile(style, (510, 1)).astype(np.float32).tofile(os.path.join(pack, "voices.bin"))
    with open(os.path.join(pack, "tokens.txt"), "w", encoding="utf-8") as f:
        for ch, idx in sorted(table.items(), key=lambda kv: kv[1]):
            f.write(f"{idx}\n" if ch == " " else f"{ch} {idx}\n")

    import sherpa_onnx
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
            model=os.path.join(pack, "model.onnx"), voices=os.path.join(pack, "voices.bin"),
            tokens=os.path.join(pack, "tokens.txt")),
        num_threads=1))
    tts = sherpa_onnx.OfflineTts(cfg)
    for i, s in enumerate(SENTENCES[:2], 1):
        ps = ipa(unicodedata.normalize("NFC", stressify(db, s)))
        g2 = sherpa_onnx.GenerationConfig()
        g2.phoneme_codepoints = [[ord(c) for c in ps]]
        a = tts.generate("", g2)
        sf.write(os.path.join(out, f"sherpa_styletts2_{i}.wav"), np.array(a.samples), a.sample_rate)
        report.append(f"sherpa wrap [{i}]: {len(a.samples)/max(a.sample_rate,1):.2f}s audio, sr={a.sample_rate}")

    # Piper "Serhiy" (stress-trained VITS) fed with the same IPA through sherpa-onnx.
    pj = json.load(open("serhiy/serhiy.onnx.json", encoding="utf-8"))
    pmap = {k: v[0] for k, v in pj["phoneme_id_map"].items()}
    with open("serhiy/tokens.txt", "w", encoding="utf-8") as f:
        for k, v in sorted(pmap.items(), key=lambda kv: kv[1]):
            f.write(f"{v}\n" if k == " " else f"{k} {v}\n")
    pm = onnx.load("serhiy/serhiy.onnx")
    for k, v in {"sample_rate": "22050", "n_speakers": "1", "language": "Ukrainian", "voice": "uk",
                 "comment": "piper", "model_type": "vits", "add_blank": "0", "has_espeak": "0"}.items():
        pm.metadata_props.add(key=k, value=v)
    onnx.save(pm, "serhiy/serhiy-meta.onnx")
    ptts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model="serhiy/serhiy-meta.onnx", tokens="serhiy/tokens.txt"),
        num_threads=1)))
    pt = 0.0; pa = 0.0; pc = 0
    for i, s_ in enumerate(SENTENCES, 1):
        ps = ipa(unicodedata.normalize("NFC", stressify(db, s_)))
        missing = sorted(set(c for c in ps if c not in pmap))
        g4 = sherpa_onnx.GenerationConfig()
        g4.phoneme_codepoints = [[ord(c) for c in ps if c in pmap]]
        t0 = time.time(); a = ptts.generate("", g4); dt = time.time() - t0
        pt += dt; pa += len(a.samples) / a.sample_rate; pc += len(s_)
        sf.write(os.path.join(out, f"piper_serhiy_{i}.wav"), np.array(a.samples), a.sample_rate)
        report.append(f"piper [{i}] missing symbols vs Serhiy map: {missing}")
    report.append(f"Piper Serhiy (1 thread): {pc / pt:.0f} chars/s, RTF {pt / pa:.3f}")

    # Coqui uk-mai VITS with '+' before the stressed vowel (its symbol set contains '+').
    ctts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model="coqui/model.onnx", tokens="coqui/tokens.txt"), num_threads=1)))
    for i, s_ in enumerate(SENTENCES, 1):
        plus = re.sub(r"(.)" + ACUTE, r"+\1", unicodedata.normalize("NFD", stressify(db, s_))).lower()
        plus = unicodedata.normalize("NFC", plus)
        a = ctts.generate(plus, sid=0, speed=1.0)
        sf.write(os.path.join(out, f"coqui_stressed_{i}.wav"), np.array(a.samples), a.sample_rate)
        if i == 1: report.append(f"coqui input: {plus}")

    # Supertonic 3 (current default) on the same sentences, voice F2 (sid 1), 5 steps.
    st = "supertonic"
    stcfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
            duration_predictor=f"{st}/duration_predictor.int8.onnx", text_encoder=f"{st}/text_encoder.int8.onnx",
            vector_estimator=f"{st}/vector_estimator.int8.onnx", vocoder=f"{st}/vocoder.int8.onnx",
            tts_json=f"{st}/tts.json", unicode_indexer=f"{st}/unicode_indexer.bin", voice_style=f"{st}/voice.bin"),
        num_threads=1))
    stt = sherpa_onnx.OfflineTts(stcfg)
    for i, s in enumerate(SENTENCES, 1):
        g3 = sherpa_onnx.GenerationConfig(); g3.sid = 1; g3.num_steps = 5; g3.extra = {"lang": "uk"}
        a = stt.generate(s, g3)
        sf.write(os.path.join(out, f"supertonic_F2_{i}.wav"), np.array(a.samples), a.sample_rate)
    open(os.path.join(out, "report.txt"), "w", encoding="utf-8").write("\n".join(report))
    print("\n".join(report))


if __name__ == "__main__":
    cmd, d = sys.argv[1], sys.argv[2]
    inspect(d) if cmd == "inspect" else render(d, sys.argv[3])
