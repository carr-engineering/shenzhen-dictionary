"""Packs the PP-OCRv6 Small recogniser for the app and writes its label table.

  uv run --no-project --with onnx==1.23.2 --with numpy==2.5.4 --with protobuf==7.36.2 \
      pack_ocr2_recogniser.py <PP-OCRv6_rec_small.onnx> <rec.onnx> <rec-labels.txt>

The weights are stored as float16 and cast back to float32 when the graph is loaded (a `Cast` in
front of every initialiser), which halves the file; operators, inputs and outputs stay float32.
This is the artefact that was evaluated (ocr-baseline/tools/pack_onnx_fp16_weights.py): with the
pinned package versions above the output is byte-identical, which fetch_ocr2_models.sh verifies.

The label table is the model's own `character` metadata, one symbol per line, in class order
without the CTC blank (class 0) and without the trailing space class, exactly as RapidOCR reads it.
Derived from Apache-2.0 PaddleOCR weights; the modification notice is recorded in the model metadata.
"""
import hashlib
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

# The notice recorded in the evaluated artefact, kept verbatim so the output is byte-identical.
NOTICE = (
    "PaddleOCR weights rounded to FP16 for storage by Shenzhen Dictionary evaluation; "
    "original weights copyright Baidu/PaddleOCR, Apache-2.0"
)


def pack(source: Path, destination: Path) -> onnx.ModelProto:
    model = onnx.load(source)
    nodes = []
    tensors = []
    names = {v.name for v in model.graph.initializer}
    names.update(output for n in model.graph.node for output in n.output)
    for tensor in model.graph.initializer:
        if tensor.data_type != TensorProto.FLOAT:
            tensors.append(tensor)
            continue
        values = numpy_helper.to_array(tensor)
        if not np.isfinite(values).all() or np.max(np.abs(values), initial=0) > 65504:
            tensors.append(tensor)
            continue
        stored_name = tensor.name + "__stored_fp16"
        if stored_name in names:
            raise ValueError(f"Name collision: {stored_name}")
        names.add(stored_name)
        tensors.append(numpy_helper.from_array(values.astype(np.float16), stored_name))
        nodes.append(helper.make_node("Cast", [stored_name], [tensor.name], to=TensorProto.FLOAT))
    original_nodes = list(model.graph.node)
    del model.graph.node[:]
    model.graph.node.extend(nodes + original_nodes)
    del model.graph.initializer[:]
    model.graph.initializer.extend(tensors)
    metadata = {p.key: p.value for p in model.metadata_props}
    metadata["szdict.weight_storage"] = "FP16 initializers with FP32 Cast; original operators and IO stay FP32"
    metadata["szdict.source_sha256"] = hashlib.sha256(source.read_bytes()).hexdigest()
    metadata["szdict.modification_notice"] = NOTICE
    helper.set_model_props(model, metadata)
    onnx.checker.check_model(model)
    destination.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, destination)
    return model


def main():
    source, destination, labels = (Path(p) for p in sys.argv[1:4])
    model = pack(source, destination)
    characters = {p.key: p.value for p in model.metadata_props}["character"].splitlines()
    assert characters and not any("\n" in c or c == "" for c in characters)
    labels.write_text("\n".join(characters) + "\n", encoding="utf-8", newline="")
    print(f"{destination.name}: {destination.stat().st_size} bytes, {len(characters)} labels")


if __name__ == "__main__":
    main()
