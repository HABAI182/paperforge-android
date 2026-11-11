"""Evaluate fixed patch decoders on saved production-Mat crop quads.

Baseline inference is not repeated. CPU patch timing includes four source
patch extractions plus the patch model and fixed decoder, not phone latency.
No truth is used for selecting decoder, accepting corners or training.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import time
from pathlib import Path

import cv2
import numpy as np

from benchmark_crop_candidates import metrics, resize_edge, summarize
from train_crop_refiner import PatchRefiner


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    assert args.out.resolve() != repo and repo not in args.out.resolve().parents
    args.out.mkdir(parents=True, exist_ok=True)
    cv2.setNumThreads(1)
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    refiners = {
        decoder: PatchRefiner(args.model, decode=decoder)
        for decoder in ("heat-peak", "mask-intersection")
    }
    report = {
        "scope": __doc__,
        "integrated": False,
        "ownerPhotosEvaluationOnly": True,
        "baseline": str(args.baseline.resolve()),
        "baselineSha256": hashlib.sha256(args.baseline.read_bytes()).hexdigest(),
        "model": str(args.model.resolve()),
        "modelSha256": hashlib.sha256(args.model.read_bytes()).hexdigest(),
        "rows": [],
    }
    for source in baseline["rows"]:
        image = cv2.imread(source["source"])
        assert image is not None
        row = {k: v for k, v in source.items() if k != "actual"}
        row["models"] = {}
        for name in ("baseline", *refiners):
            variants = {}
            for mode, edge in (("preview720", 720), ("still2000", 2000)):
                fixed = source["actual"][mode]
                quad = fixed.get("quad")
                elapsed, decisions = 0.0, None
                if quad is not None and name in refiners:
                    small = resize_edge(image, edge)
                    start = time.perf_counter()
                    quad, decisions = refiners[name].refine(small, quad)
                    elapsed = (time.perf_counter() - start) * 1000
                values = metrics(quad, source["truth"], image.shape)
                values.update(
                    quad=None if quad is None else np.asarray(quad).tolist(),
                    postprocessMs=elapsed,
                    decisions=decisions,
                    savedBaselineMs=fixed["elapsedMs"],
                )
                variants[mode] = values
            first, last = [variants[m]["quad"] for m in ("preview720", "still2000")]
            variants["previewStillMaxCornerShift"] = (
                float(np.linalg.norm(np.asarray(first) - last, axis=1).max())
                if first is not None and last is not None
                else None
            )
            row["models"][name] = variants
        report["rows"].append(row)
        overlay = resize_edge(image, 1000)
        dims = [overlay.shape[1] - 1, overlay.shape[0] - 1]
        for quad, color in (
            (source["truth"], (0, 170, 0)),
            (row["models"]["baseline"]["still2000"]["quad"], (170, 70, 0)),
            (row["models"]["heat-peak"]["still2000"]["quad"], (0, 0, 220)),
            (row["models"]["mask-intersection"]["still2000"]["quad"], (180, 0, 180)),
        ):
            if quad is not None:
                cv2.polylines(
                    overlay,
                    [(np.asarray(quad) * dims).round().astype("int32")],
                    True,
                    color,
                    2,
                )
        cv2.putText(
            overlay,
            f"{row['set']}/{row['file']} green=truth blue=base red=heat purple=mask",
            (6, 22),
            0,
            0.45,
            (0, 0, 0),
            1,
        )
        folder = args.out / source["set"]
        folder.mkdir(exist_ok=True)
        cv2.imwrite(str(folder / (Path(source["file"]).stem + ".jpg")), overlay)
    report["summary"] = {"all": summarize(report["rows"])}
    for group in {r["set"] for r in report["rows"]}:
        report["summary"][group] = summarize(
            [r for r in report["rows"] if r["set"] == group]
        )
    (args.out / "report.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8"
    )
    for name, value in report["summary"]["all"].items():
        if isinstance(value, dict) and "still2000" in value:
            print(name, json.dumps(value["still2000"]), flush=True)


if __name__ == "__main__":
    main()
