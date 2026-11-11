"""Readback comparison for the fixed real printed-book crop; no training or OCR truth.
OCR agreement is a smoke check only. The saved crop must also be visually read.
"""

from pathlib import Path
import argparse, json, time, difflib
import cv2
import numpy as np
from rapidocr_onnxruntime import RapidOCR

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--dir", type=Path, required=True)
p.add_argument("--jvm", type=Path, required=True)
a = p.parse_args()
entry = next(
    r
    for r in json.loads((a.jvm / "g41/manifest.json").read_text())
    if r["name"] == "book-fingers"
)
raw = cv2.imread(entry["source"], 0)
h, w = raw.shape
field = np.fromfile(entry["field"] + ".f32", dtype="<f4").reshape(raw.shape)
normalized = np.rint(
    np.clip(raw.astype(np.float32) / np.maximum(field, 1), 0, 1) * 255
).astype(np.uint8)
crop = (slice(int(h * 0.31), int(h * 0.63)), slice(int(w * 0.1), int(w * 0.92)))
ocr = RapidOCR(intra_op_num_threads=4, inter_op_num_threads=1, det_limit_side_len=1800)
images = {"sourceNormalized": normalized}
for name in ["baseline", "nn", "nnsoft", "nnsoftveto", "nnveto", "local"]:
    path = a.dir / f"book-fingers-{name}.png"
    if path.exists():
        images[name] = cv2.imread(str(path), 0)
results = {}
for name, image in images.items():
    start = time.perf_counter()
    items, elapsed = ocr(image[crop])
    items = items or []
    text = "\n".join(r[1] for r in items)
    results[name] = dict(
        lines=len(items),
        characters=len(text),
        text=text,
        meanConfidence=float(np.mean([r[2] for r in items])) if items else 0,
        elapsedMs=(time.perf_counter() - start) * 1000,
    )
    print(name, results[name], flush=True)
baseline = results.get("baseline", {}).get("text", "")
for name, row in results.items():
    row["agreementWithBaseline"] = difflib.SequenceMatcher(
        None, baseline, row["text"]
    ).ratio()
(a.dir / "book-ocr.json").write_text(
    json.dumps(dict(scope=__doc__, results=results), ensure_ascii=False, indent=2),
    encoding="utf8",
)
