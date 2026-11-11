"""Locate amplified weak source marks in new hand-crop tone-only probes.
No region is called a ghost by a metric; source/rule/core context is saved.
"""

from pathlib import Path
import argparse, json
import cv2
import numpy as np
from benchmark_tone_guard import panel

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--dir", type=Path, required=True)
a = p.parse_args()
out = a.dir
rows = []
for meta in json.loads((out / "inputs.json").read_text())["rows"]:
    n = meta["name"]
    folder = out / "productionScale"
    raw = cv2.imread(str(folder / f"{n}-source.png"), 0)
    base = cv2.imread(str(folder / f"{n}-tone.png"), 0)
    h, w = raw.shape
    field = np.fromfile(folder / f"{n}-field.png.f32", dtype="<f4").reshape(raw.shape)
    source = np.clip(1 - raw.astype(np.float32) / np.maximum(field, 1), 0, 1)
    ink = (255 - base.astype(np.float32)) / 255
    read = lambda suffix: cv2.imread(str(folder / f"{n}-{suffix}.png"), 0) > 0
    rules = (
        cv2.dilate(
            (read("rules") | read("drawn-rules")).astype(np.uint8),
            np.ones((9, 9), np.uint8),
        )
        > 0
    )
    core_dist = cv2.distanceTransform((source < 0.3).astype(np.uint8), cv2.DIST_L2, 3)
    interior = np.zeros(raw.shape, bool)
    interior[int(h * 0.05) : int(h * 0.95), int(w * 0.05) : int(w * 0.95)] = True
    row = dict(
        name=n,
        scope="Fixed hand crop, no automatic crop/dewarp; no reference truth",
        sourceScale=meta["productionSourceScale"],
        bins=[],
    )
    for lo, hi in [(0, 0.05), (0.05, 0.1), (0.1, 0.15), (0.15, 0.25), (0.25, 0.4)]:
        m = interior & ~rules & (source >= lo) & (source < hi)
        row["bins"].append(
            dict(
                low=lo,
                high=hi,
                pixels=int(m.sum()),
                renderedDark=int((m & (base < 160)).sum()),
                gainAbovePoint1=int((m & (ink - source > 0.1)).sum()),
                isolatedDark=int((m & (base < 160) & (core_dist > 8)).sum()),
            )
        )
    for category, mask in [
        ("weak-near-core", (source > 0.005) & (source < 0.15) & (core_dist <= 8)),
        ("weak-away-core", (source > 0.005) & (source < 0.15) & (core_dist > 8)),
        ("faint-component", (source > 0.005) & (source < 0.25) & read("faint")),
    ]:
        mask &= interior & ~rules
        gain = np.maximum(0, ink - source - 0.05) * mask
        sums = cv2.boxFilter(gain, -1, (128, 128), normalize=False)
        y, x = np.unravel_index(np.argmax(sums), sums.shape)
        box = (
            slice(max(0, y - 100), min(h, y + 100)),
            slice(max(0, x - 150), min(w, x + 150)),
        )
        cv2.imwrite(str(out / f"{n}-{category}-mask.png"), mask.astype(np.uint8) * 255)
        wrong = cv2.imread(str(out / "wrongScale1" / f"{n}-tone.png"), 0)
        im = panel(
            [
                im[box]
                for im in [
                    raw,
                    np.rint(255 * (1 - source)).astype(np.uint8),
                    base,
                    wrong,
                ]
            ],
            [
                "Hand-crop source",
                "Source normalized",
                "Actual v29 correct scale",
                "Diagnostic wrong scale=1",
            ],
            width=600,
        )
        im.save(out / f"{n}-{category}.jpg", quality=97)
        row[category] = dict(
            maxGainWindow=float(sums[y, x]),
            xy=[int(x), int(y)],
            darkPixels=int((mask & (base < 160)).sum()),
            gainAbovePoint1=int((mask & (ink - source > 0.1)).sum()),
        )
    # SourceScale sensitivity only, same enlarged input pixel for pixel.
    wrong = cv2.imread(str(out / "wrongScale1" / f"{n}-tone.png"), 0)
    delta = base.astype(np.float32) - wrong
    row["scaleSensitivity"] = dict(
        differentPixels=int((base != wrong).sum()),
        meanAbsoluteDifference=float(np.abs(delta).mean()),
        maximumAbsoluteDifference=float(np.abs(delta).max()),
    )
    rows.append(row)
(out / "gain-summary.json").write_text(json.dumps(rows, indent=2), encoding="utf8")
print(json.dumps(rows, indent=2))
