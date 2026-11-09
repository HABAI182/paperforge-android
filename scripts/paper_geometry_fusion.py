"""Compose learned paper geometry with distributed rules, resampling source once.

Intermediate rasters are analysis inputs only. No repeated warp of final text,
no production/default change. The independent ridge proxy is not true line GT.
"""

from pathlib import Path
import argparse
import hashlib
import json
import time

import cv2
import numpy as np

from paper_structure_lab import estimate
from scan_flat_contact import line_metric


def sample_grid(grid, positions):
    h, w = grid.shape[:2]
    return cv2.remap(
        grid,
        positions[:, :, 0] * (w - 1),
        positions[:, :, 1] * (h - 1),
        cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_REPLICATE,
    )


def render(image, grid):
    h, w = image.shape[:2]
    positions = np.stack(
        np.meshgrid(np.linspace(0, 1, w), np.linspace(0, 1, h)), -1
    ).astype("float32")
    dense = sample_grid(grid, positions)
    return cv2.remap(
        image,
        dense[:, :, 0] * (w - 1),
        dense[:, :, 1] * (h - 1),
        cv2.INTER_CUBIC,
        borderMode=cv2.BORDER_REPLICATE,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--learned", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--degree", type=int, default=4)
    parser.add_argument("--curve-penalty", type=float, default=40)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    out = args.out.resolve()
    assert out != repo and repo not in out.parents
    out.mkdir(parents=True, exist_ok=True)
    cv2.setNumThreads(1)
    report = {
        "scope": "offline grid composition and ridge proxies; not physical geometric truth or Android output",
        "parameters": {
            "verticalDegree": args.degree,
            "curvePenalty": args.curve_penalty,
        },
        "integrated": False,
        "rows": [],
    }
    for folder in sorted(
        p for p in args.learned.iterdir() if (p / "grid.npy").is_file()
    ):
        start = time.perf_counter()
        image = cv2.imread(str(folder / "source.png"))
        learned = np.load(folder / "grid.npy")
        preview = render(image, learned)
        row = {
            "id": folder.name,
            "learnedGridSha256": hashlib.sha256(
                (folder / "grid.npy").read_bytes()
            ).hexdigest(),
            "sourceRuleProxy": line_metric(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)),
            "learnedRuleProxy": line_metric(cv2.cvtColor(preview, cv2.COLOR_BGR2GRAY)),
        }
        estimate_result = estimate(
            preview, vertical_degree=args.degree, curve_penalty=args.curve_penalty
        )
        final = learned
        if estimate_result is not None:
            residual, _, _ = estimate_result
            composed = sample_grid(learned, residual)
            dx = np.diff(composed, axis=1)[:-1]
            dy = np.diff(composed, axis=0)[:, :-1]
            determinant = dx[:, :, 0] * dy[:, :, 1] - dx[:, :, 1] * dy[:, :, 0]
            accepted = bool(
                (determinant > 0).all()
                and composed.min() >= -1e-6
                and composed.max() <= 1 + 1e-6
            )
            row.update(
                residualAccepted=accepted,
                nonPositiveJacobianFraction=float(np.mean(determinant <= 0)),
            )
            if accepted:
                final = composed
        result = render(image, final)
        target = out / folder.name
        target.mkdir(exist_ok=True)
        cv2.imwrite(str(target / "source.png"), image)
        cv2.imwrite(str(target / "unwarp.png"), result)
        np.save(target / "grid.npy", final)
        row.update(
            finalRuleProxy=line_metric(cv2.cvtColor(result, cv2.COLOR_BGR2GRAY)),
            finalImageResamples=1,
            analysisAndRenderMs=(time.perf_counter() - start) * 1000,
        )
        report["rows"].append(row)
        print(row, flush=True)
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
