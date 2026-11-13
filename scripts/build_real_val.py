"""Real-photo validation set for checkpoint selection (train_page_models.py --real-val).

Selecting checkpoints on held-out Mendeley photos alone kept rewarding that collection's look; independent real
photos did not improve with it. This packs a validation half of independent real sets, preprocessed exactly as
PageNetDetector does (longest side <= 2000, then INTER_AREA to 320x320, RGB), with truth quads:

  SmartDoc 2015: documents at even positions of the sorted document list (all five backgrounds)
  receipts (Zenodo 13688441): odd image numbers
The other halves, public167 and the owner photos stay test-only (see topsheet-decoder/select_experiment.py, which
uses the same split).

usage: build_real_val.py --smartdoc <input-report.json> --receipts <input-report.json> --out <real-val.npz>
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def preprocess(path):
    image = cv2.cvtColor(cv2.imread(str(path)), cv2.COLOR_BGR2RGB)
    h, w = image.shape[:2]
    s = min(1.0, 2000 / max(h, w))
    if s < 1:
        image = cv2.resize(image, (round(w * s), round(h * s)), interpolation=cv2.INTER_AREA)
    return cv2.resize(image, (320, 320), interpolation=cv2.INTER_AREA)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--smartdoc", type=Path, required=True)
    ap.add_argument("--receipts", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    smart = json.loads(args.smartdoc.read_text(encoding="utf-8"))["rows"]
    docs = sorted({r["file"].split("-")[1] for r in smart})
    keep_docs = set(docs[::2])
    rows = [r for r in smart if r["file"].split("-")[1] in keep_docs]
    rows += [r for r in json.loads(args.receipts.read_text(encoding="utf-8"))["rows"] if int(r["file"][:4]) % 2]
    images = np.stack([preprocess(r["source"]) for r in rows])
    quads = np.array([r["truth"] for r in rows], np.float32)
    np.savez_compressed(args.out, images=images, quads=quads, files=np.array([r["file"] for r in rows]))
    (args.out.with_suffix(".json")).write_text(json.dumps({
        "count": len(rows), "smartdocDocuments": sorted(keep_docs), "receipts": "odd image numbers",
        "use": "checkpoint selection only; the other halves, public167 and owner photos are test-only"}, indent=1), encoding="utf-8")
    print(json.dumps({"count": len(rows), "smartdoc": len(rows) - sum(1 for r in rows if r["file"].endswith("receipt.jpg"))}))


if __name__ == "__main__":
    main()
