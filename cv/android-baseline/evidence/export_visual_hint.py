"""Export a fixed-vocabulary, experimental inspection segmenter on Windows CPU."""
import argparse
import hashlib
from pathlib import Path

import onnx
import onnxruntime as ort
from ultralytics import YOLOE

CLASSES = (
    "wet oil stain",
    "liquid puddle",
    "shaft coupling guard",
    "frayed electrical cable",
    "exposed copper wire",
    "motor cooling fins",
    "green yellow ground wire",
    "discolored metal",
)
CHECKPOINT_SHA256 = "1741c1f8da3cea47e2c01829c334a50dc0b9bbd05e685b90a3ce84fae32c8c1b"
ENCODER_SHA256 = "35d7f213e4d75f38514e4656ad3cb91158bd33e3805d8ac349f23b186f66982f"


def verify(path: Path, expected: str) -> None:
    with path.open("rb") as data:
        actual = hashlib.file_digest(data, "sha256").hexdigest()
    if actual != expected:
        raise ValueError(f"Unexpected hash for {path}: {actual}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("inspection-regions-26n.onnx"))
    args = parser.parse_args()
    checkpoint = Path("yoloe-26n-seg.pt")
    verify(checkpoint, CHECKPOINT_SHA256)
    verify(Path("mobileclip2_b.ts"), ENCODER_SHA256)
    model = YOLOE(str(checkpoint))
    model.set_classes(list(CLASSES))
    model.model.export = False
    exported = Path(model.export(format="onnx", imgsz=416, opset=17, simplify=False,
                                 dynamic=False, device="cpu", end2end=True))
    exported.replace(args.output)
    onnx.checker.check_model(args.output)
    session = ort.InferenceSession(str(args.output), providers=["CPUExecutionProvider"])
    with args.output.open("rb") as data:
        print("sha256", hashlib.file_digest(data, "sha256").hexdigest())
    print("bytes", args.output.stat().st_size)
    print("inputs", [(x.name, x.shape, x.type) for x in session.get_inputs()])
    print("outputs", [(x.name, x.shape, x.type) for x in session.get_outputs()])
    print("labels", CLASSES)
    print("metadata", session.get_modelmeta().custom_metadata_map)


if __name__ == "__main__":
    main()
