"""Export a trained PageNet checkpoint to the ONNX asset the app runs, and verify it against PyTorch."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnx
import onnxruntime
import torch

from train_page_net import INPUT, PageNet


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="asset directory")
    parser.add_argument("--training-note", default="")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    model = PageNet()
    model.load_state_dict(torch.load(args.checkpoint, map_location="cpu", weights_only=True))
    model.eval()
    target = args.out / "pagenet.onnx"
    example = torch.rand(1, 3, INPUT, INPUT)
    torch.onnx.export(model, (example,), target, input_names=["image"], output_names=["logits"], opset_version=17, dynamo=False)
    onnx.checker.check_model(onnx.load(target))
    session = onnxruntime.InferenceSession(str(target), providers=["CPUExecutionProvider"])
    with torch.no_grad():
        expected = model(example).numpy()
    actual = session.run(None, {"image": example.numpy()})[0]
    error = float(np.abs(expected - actual).max())
    if error > 1e-3:
        raise SystemExit(f"ONNX output differs from PyTorch by {error}")
    data = target.read_bytes()
    (args.out / "pagenet.json").write_text(
        json.dumps(
            {
                "name": "PaperForge PageNet",
                "origin": "Original architecture trained from random initialisation; no third-party weights.",
                "trainingData": "Procedural sheets over COCO val2017 photographs or procedural surfaces. Real photographs were used only to choose a checkpoint and report results.",
                "note": args.training_note,
                "bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
                "input": {"name": "image", "shape": [1, 3, INPUT, INPUT], "range": "RGB 0..1"},
                "output": {"name": "logits", "shape": [1, 2, INPUT // 2, INPUT // 2], "channels": ["top-sheet mask", "sheet boundary"]},
                "maxError": error,
            },
            indent=1,
        ),
        encoding="utf-8",
    )
    print(target, len(data), "bytes; max error", error)


if __name__ == "__main__":
    main()
