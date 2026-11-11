"""Exports golden vectors for the host unit tests from the reference implementations.

Run with the reverse-engineering workspace's Python (LiteRT, numpy, Pillow) and Node on PATH:

  PYTHONPATH=$SRC/web-ocr/research/python python3.12 tools/export_golden.py $SRC

Writes:
  src/androidInstrumentedTest/assets/testdata/   reference test images and ink drawings (+ reference results)
  src/androidUnitTest/resources/golden/          raw network outputs with the reference decoders' answers
"""
import gzip, json, subprocess, sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from ai_edge_litert.interpreter import Interpreter, OpResolverType

src = Path(sys.argv[1])
here = Path(__file__).resolve().parent
models = here.parent / "src/androidMain/assets/models"
data = here.parent / "src/androidInstrumentedTest/assets/testdata"
golden = here.parent / "src/androidUnitTest/resources/golden"
data.mkdir(parents=True, exist_ok=True)
golden.mkdir(parents=True, exist_ok=True)


def load(path):
    return Interpreter(model_path=str(path), experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES)


# ---- test images -------------------------------------------------------------------------------
for name in ["你好世界", "pipeline-columns", "pipeline-mixed", "pipeline-shaded", "pipeline-upside-down", "pipeline-tilted",
             "pipeline-vertical", "pipeline-inverted", "pipeline-blank", "pipeline-page"]:
    Image.open(src / "web-ocr/tests" / f"{name}.png").save(data / ("hello-world-zh.png" if name == "你好世界" else f"{name}.png"), optimize=True)

# A line of look-alike characters: the case the per-character alternatives exist for.
font = ImageFont.truetype("/System/Library/Fonts/STHeiti Medium.ttc", 42)
similar = Image.new("RGB", (640, 180), "white")
draw = ImageDraw.Draw(similar)
draw.text((30, 25), "我已经吃过了", font=font, fill="#202523")
draw.text((30, 100), "未来的土地很大", font=font, fill="#202523")
similar.save(data / "similar.png", optimize=True)

# ---- ink: reference drawings, reference features and reference candidates --------------------
sys.path.insert(0, str(src / "native-ink"))
from features import encode  # noqa: E402
from recognizer import single_character_scores, is_han  # noqa: E402

labels = json.loads((src / "native-ink/models/labels.json").read_text())
han = np.array([i for i, c in enumerate(labels) if is_han(c)])
ink = load(src / "native-ink/models/chinese_lstm_4x192.tflite")


def infer(features):
    x = np.asarray(features, np.float32).reshape(-1, 1, 10)
    ink.resize_tensor_input(0, x.shape)
    ink.allocate_tensors()
    ink.reset_all_variables()
    ink.set_tensor(0, x)
    ink.invoke()
    return ink.get_tensor(ink.get_output_details()[0]["index"]).reshape(len(x), -1)


fixtures = json.loads((src / "native-ink/tests/fixtures.json").read_text())
with open(data / "ink-fixtures.txt", "w", encoding="utf-8") as out:
    for f in fixtures:
        features = encode(f["strokes"])
        logits = infer(features)
        scores = single_character_scores(logits)
        ranked = han[np.argsort(scores[han])[-10:][::-1]]
        out.write(f"case {f['expected']} {len(f['strokes'])} {len(features)}\n")
        for stroke in f["strokes"]:
            out.write("stroke " + " ".join(f"{v:g}" for p in stroke for v in p) + "\n")
        out.write("features " + " ".join(repr(float(v)) for v in features.reshape(-1)) + "\n")
        out.write("top " + " ".join(labels[i] for i in ranked) + "\n")
        out.write("scores " + " ".join(repr(float(scores[i])) for i in ranked) + "\n")
        if f["expected"] == "十":
            (golden / "ink-logits.bin.gz").write_bytes(gzip.compress(logits.astype("<f4").tobytes(), mtime=0))
            order = np.argsort(scores)[-40:][::-1]
            (golden / "ink-logits.txt").write_text(
                f"{logits.shape[0]} {logits.shape[1]}\n" + "\n".join(f"{i} {float(scores[i])!r}" for i in order) + "\n", encoding="utf-8")

# ---- OCR line recogniser: raw logits for rendered lines + reference greedy text --------------
hani_labels = json.loads((src / "web-ocr/models/hani-labels.json").read_text())
enc = load(models / "ocr/hani-encoder.tflite")
dec = load(models / "ocr/hani-decoder.tflite")
dec.allocate_tensors()
di, do = dec.get_input_details(), dec.get_output_details()
big = ImageFont.truetype("/System/Library/Fonts/STHeiti Medium.ttc", 48)
lines = []
for index, text in enumerate(["我已经吃过了。", "学习汉字繁體中文", "未来的土地 Hello 123"]):
    bbox = big.getbbox(text)
    im = Image.new("L", (bbox[2] - bbox[0], bbox[3] - bbox[1]), 255)
    ImageDraw.Draw(im).text((-bbox[0], -bbox[1]), text, font=big, fill=0)
    iw = round(im.width * 20 / im.height)
    width = -(-(iw + 16) // 4) * 4
    strip = Image.new("L", (width, 24), 255)
    strip.paste(im.resize((iw, 20), Image.Resampling.BOX), (6, 2))
    gray = np.array(strip)
    a = np.ascontiguousarray(gray.T).reshape(1, width, 24, 1)
    enc.resize_tensor_input(0, a.shape)
    enc.allocate_tensors()
    enc.set_tensor(0, a)
    enc.invoke()
    features = enc.get_tensor(enc.get_output_details()[0]["index"]).reshape(-1, 384)
    states = [np.full(d["shape"], d["quantization"][1], d["dtype"]) for d in di[1:]]
    probs, logits = [], []
    for f in features:
        dec.set_tensor(di[0]["index"], f.reshape(1, 384))
        for d, v in zip(di[1:], states):
            dec.set_tensor(d["index"], v)
        dec.invoke()
        r = [dec.get_tensor(d["index"]) for d in do]
        states = r[1:5]
        probs.append(r[0][0].copy())
        logits.append(r[5][0].copy())
    probs, logits = np.array(probs), np.array(logits)
    # The reference decoder (worker.js): greedy collapse over the softmax output.
    best = probs.argmax(1)
    greedy = "".join(hani_labels[v] for i, v in enumerate(best) if v < len(hani_labels) and (i == 0 or best[i - 1] != v)).strip()
    (golden / f"hani-line-{index}.bin.gz").write_bytes(gzip.compress(logits.tobytes() + gray.T.tobytes(), mtime=0))
    scale, zero = do[5]["quantization"]
    lines.append(f"{index}\t{logits.shape[0]}\t{logits.shape[1]}\t{scale!r}\t{zero}\t{width}\t{text}\t{greedy}")
(golden / "hani-lines.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")

# ---- detector: raw head output + regions from the reference geometry.js ---------------------
det = load(models / "ocr/detector.tflite")
page = Image.open(src / "web-ocr/tests/pipeline-columns.png").convert("L")
w, h = page.size
dw, dh = -(-w // 32) * 32, -(-h // 32) * 32
image = np.array(page.resize((dw, dh), Image.Resampling.BILINEAR)).reshape(1, dh, dw, 1)
det_in = det.get_input_details()[0]["index"]
det.resize_tensor_input(det_in, image.shape)
det.allocate_tensors()
det.set_tensor(det_in, image)
det.invoke()
detail = det.get_output_details()[0]
raw = det.get_tensor(detail["index"])
scale, zero = detail["quantization"]
(golden / "detector-columns.bin.gz").write_bytes(gzip.compress(raw.tobytes(), mtime=0))
node = f"""
const G=require({json.dumps(str(src / 'web-ocr/geometry.js'))});
const raw=new Uint8Array(require('zlib').gunzipSync(require('fs').readFileSync({json.dumps(str(golden / 'detector-columns.bin.gz'))})));
const p=G.decodeDetector(raw,[1,{dh // 16},{dw // 16},7],{scale!r},{zero},{w}/{dw},{h}/{dh});
const r=G.mergeProposals(p).filter(r=>r.w>4&&r.h>5&&r.h<Math.max({w},{h})*1.3);
const v=G.verticalColumns(r,false);
console.log([p.length,r.length,v.length].join(' '));
for(const x of r)console.log([x.cx,x.cy,x.w,x.h,x.angle,x.score,x.proposals].join(' '));
"""
regions = subprocess.run(["node", "-e", node], check=True, capture_output=True, text=True).stdout
(golden / "detector-columns.txt").write_text(f"{dh // 16} {dw // 16} {scale!r} {zero} {w} {h} {dw} {dh}\n" + regions, encoding="utf-8")
print("golden vectors written")
