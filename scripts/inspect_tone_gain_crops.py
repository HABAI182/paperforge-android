"""Find real weak-source gain crops without claiming they are show-through.
The chosen boxes come only from source and baseline; reference is evaluation-only.
"""

from pathlib import Path
import argparse, json
import cv2
import numpy as np
from benchmark_tone_guard import panel

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--jvm", type=Path, required=True)
p.add_argument("--out", type=Path, required=True)
a = p.parse_args()
rows = []
for r in json.loads((a.jvm / "g41/manifest.json").read_text()):
    n = r["name"]
    raw = cv2.imread(r["source"], 0)
    h, w = raw.shape
    field = np.fromfile(r["field"] + ".f32", dtype="<f4").reshape(raw.shape)
    source = np.clip(1 - raw.astype(np.float32) / np.maximum(field, 1), 0, 1)
    base = cv2.imread(r["output"], 0)
    ink = (255 - base.astype(np.float32)) / 255
    masks = a.jvm / "g41-instrumented"
    read = lambda suffix: cv2.imread(str(masks / f"{n}-{suffix}.png"), 0) > 0
    rules = (
        cv2.dilate(
            (read("rules") | read("drawn-rules")).astype(np.uint8),
            np.ones((9, 9), np.uint8),
        )
        > 0
    )
    weak = (source > 0.005) & (source < 0.25) & ~rules
    weak[: int(h * 0.08)] = False
    weak[int(h * 0.92) :] = False
    weak[:, : int(w * 0.08)] = False
    weak[:, int(w * 0.92) :] = False
    gain = np.maximum(0, ink - source - 0.08) * weak
    summed = cv2.boxFilter(gain, -1, (160, 160), normalize=False)
    y, x = np.unravel_index(np.argmax(summed), summed.shape)
    box = (
        slice(max(0, y - 100), min(h, y + 100)),
        slice(max(0, x - 160), min(w, x + 160)),
    )
    imgs = [np.rint(255 * (1 - source)).astype(np.uint8)[box], base[box]]
    labels = ["Source normalized", "Actual Kotlin v29"]
    for method in ["nn", "nnsoft", "nnsoftveto"]:
        f = a.out / f"{n}-{method}.png"
        if f.exists():
            imgs.append(cv2.imread(str(f), 0)[box])
            labels.append(method)
    panel(imgs, labels, width=480).save(
        a.out / f"{n}-strongest-weak-gain.jpg", quality=97
    )
    cropweak = weak[box]
    g = ink[box] - source[box]
    rows.append(
        dict(
            name=n,
            x=int(x),
            y=int(y),
            gainWindowSum=float(summed[y, x]),
            weakPixelsWithGainAbovePoint1=int((cropweak & (g > 0.1)).sum()),
            weakPixelMaxGain=float(g[cropweak].max()) if cropweak.any() else None,
            warning="These may be real faint strokes; do not label as ghosts without visual evidence",
        )
    )
    ref = cv2.imread(str(a.out / f"{n}-aligned-reference.png"), 0)
    if ref is not None:
        near = cv2.dilate((ref < 200).astype(np.uint8), np.ones((15, 15), np.uint8))
        near = cv2.resize(near, (w, h), interpolation=cv2.INTER_NEAREST) > 0
        residue = (source > 0.005) & (source < 0.4) & ~rules & ~near & (base < 225)
        residue[: int(h * 0.1)] = False
        residue[int(h * 0.9) :] = False
        residue[:, : int(w * 0.1)] = False
        residue[:, int(w * 0.9) :] = False
        gains = np.maximum(0, ink - source - 0.05) * residue
        sums = cv2.boxFilter(gains, -1, (160, 160), normalize=False)
        yy, xx = np.unravel_index(np.argmax(sums), sums.shape)
        box = (
            slice(max(0, yy - 100), min(h, yy + 100)),
            slice(max(0, xx - 160), min(w, xx + 160)),
        )
        if sums[yy, xx] > 0:
            ims = [np.rint(255 * (1 - source)).astype(np.uint8)[box], base[box]]
            labs = ["Source normalized", "Actual Kotlin v29"]
            for method in ["nn", "nnsoft", "nnsoftveto"]:
                f = a.out / f"{n}-{method}.png"
                if f.exists():
                    ims.append(cv2.imread(str(f), 0)[box])
                    labs.append(method)
            ims.append(cv2.resize(ref, (w, h), interpolation=cv2.INTER_LINEAR)[box])
            labs.append("Scanner reference")
            panel(ims, labs, width=400).save(
                a.out / f"{n}-unmatched-gain.jpg", quality=97
            )
        rows[-1]["unmatchedGainWindowSum"] = float(sums[yy, xx])
        rows[-1]["unmatchedXY"] = [int(xx), int(yy)]
(a.out / "weak-gain-crops.json").write_text(json.dumps(rows, indent=2), encoding="utf8")
print(json.dumps(rows, indent=2))
