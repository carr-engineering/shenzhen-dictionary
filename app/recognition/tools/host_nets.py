"""Development harness: serves the recognition graphs to the JVM unit tests over stdin/stdout.

The Android build runs the graphs with LiteRT on the device. On the host there is no JVM LiteRT, so
the optional host pipeline tests (HostPipelineTest) start this script with a Python that has
`ai_edge_litert` and `numpy` (for example PYTHONPATH=<workspace>/web-ocr/research/python python3.12).

Protocol (binary, one request at a time):
  detect <w> <h>\n + w*h bytes            -> <rows> <cols> <scale> <zero>\n + rows*cols*7 bytes
  seq <script|hani|latn> <w>\n + w*24 b   -> <frames> <classes> <scale> <zero>\n + frames*classes bytes
  ink <frames>\n + frames*10 float32 LE   -> <frames> <classes>\n + frames*classes float32 LE
"""
import sys
import numpy as np
from ai_edge_litert.interpreter import Interpreter, OpResolverType

root = sys.argv[1]


def load(path):
    return Interpreter(model_path=f"{root}/{path}", experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES)


detector = load("ocr/detector.tflite")
lines = {}
ink = None
stdin, stdout = sys.stdin.buffer, sys.stdout.buffer


def read(n):
    data = stdin.read(n)
    if len(data) != n:
        raise EOFError
    return data


while True:
    header = stdin.readline()
    if not header:
        break
    parts = header.decode().split()
    if parts[0] == "detect":
        w, h = int(parts[1]), int(parts[2])
        image = np.frombuffer(read(w * h), np.uint8).reshape(1, h, w, 1)
        index = detector.get_input_details()[0]["index"]
        detector.resize_tensor_input(index, image.shape)
        detector.allocate_tensors()
        detector.set_tensor(index, image)
        detector.invoke()
        detail = detector.get_output_details()[0]
        out = detector.get_tensor(detail["index"])
        scale, zero = detail["quantization"]
        stdout.write(f"{out.shape[1]} {out.shape[2]} {scale!r} {zero}\n".encode() + out.tobytes())
    elif parts[0] == "seq":
        name, w = parts[1], int(parts[2])
        if name not in lines:
            decoder = load(f"ocr/{name}-decoder.tflite")
            decoder.allocate_tensors()
            lines[name] = (load(f"ocr/{name}-encoder.tflite"), decoder)
        encoder, decoder = lines[name]
        image = np.frombuffer(read(w * 24), np.uint8).reshape(1, w, 24, 1)
        encoder.resize_tensor_input(0, image.shape)
        encoder.allocate_tensors()
        encoder.set_tensor(0, image)
        encoder.invoke()
        features = encoder.get_tensor(encoder.get_output_details()[0]["index"]).reshape(-1, 384)
        ins, outs = decoder.get_input_details(), decoder.get_output_details()
        result = outs[0] if name == "script" else outs[5]
        states = [np.full(d["shape"], d["quantization"][1], d["dtype"]) for d in ins[1:]]
        rows = []
        for feature in features:
            decoder.set_tensor(ins[0]["index"], feature.reshape(1, 384))
            for d, v in zip(ins[1:], states):
                decoder.set_tensor(d["index"], v)
            decoder.invoke()
            states = [decoder.get_tensor(d["index"]) for d in outs[1:5]]
            rows.append(decoder.get_tensor(result["index"])[0].copy())
        out = np.array(rows, np.uint8)
        scale, zero = result["quantization"]
        stdout.write(f"{out.shape[0]} {out.shape[1]} {scale!r} {zero}\n".encode() + out.tobytes())
    elif parts[0] == "ink":
        frames = int(parts[1])
        if ink is None:
            ink = load("ink/chinese_lstm_4x192.tflite")
        x = np.frombuffer(read(frames * 40), "<f4").reshape(frames, 1, 10)
        ink.resize_tensor_input(0, x.shape)
        ink.allocate_tensors()
        ink.reset_all_variables()
        ink.set_tensor(0, x)
        ink.invoke()
        out = ink.get_tensor(ink.get_output_details()[0]["index"]).reshape(frames, -1).astype("<f4")
        stdout.write(f"{out.shape[0]} {out.shape[1]}\n".encode() + out.tobytes())
    else:
        raise ValueError(header)
    stdout.flush()
