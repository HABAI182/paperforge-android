"""Score the production-Mat JVM crop probe against frozen real-photo labels.

The probe includes the actual Kotlin crop stages, but not Android camera state,
Bitmap/JPEG decode, still-reference decisions, render-time ContentCrop or UI
smoothing. Native OpenCV 4.9 differs from Android 4.12; timings are not phone
latency. Reference registrations and manually traced quads are imperfect labels.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

from benchmark_crop_candidates import metrics, resize_edge


def corner_errors(quad, truth, width, height):
    """Measure shape localization after eight cyclic/reversed correspondences."""
    if quad is None:
        return {"valid": False, "reason": "missing-prediction"}
    try:
        q = np.asarray(quad, np.float64).reshape(4, 2)
        gt = np.asarray(truth, np.float64).reshape(4, 2)
    except (TypeError, ValueError):
        return {"valid": False, "reason": "invalid-corner-array"}
    if (
        width <= 1
        or height <= 1
        or not np.isfinite(q).all()
        or not np.isfinite(gt).all()
    ):
        return {"valid": False, "reason": "nonfinite-corners-or-invalid-image-size"}
    scale = np.array([width - 1, height - 1], np.float64)
    diagonal = float(np.linalg.norm(scale))
    choices = []
    for direction in (1, -1):
        for offset in range(4):
            indices = (offset + direction * np.arange(4)) % 4
            delta = (q[indices] - gt) * scale
            distance = np.linalg.norm(delta, axis=1)
            choices.append(
                (
                    float(distance.mean()),
                    float(distance.max()),
                    direction,
                    offset,
                    indices,
                    delta,
                    distance,
                )
            )
    _, _, direction, offset, indices, delta, distance = min(
        choices, key=lambda row: row[:2]
    )
    normalized = distance / diagonal

    def stats(values):
        return {
            "mean": float(values.mean()),
            "p95": float(np.percentile(values, 95)),
            "max": float(values.max()),
        }

    return {
        "valid": True,
        "alignment": {
            "cyclicOffset": offset,
            "reversed": direction == -1,
            "predictionIndices": indices.tolist(),
        },
        "pixels": stats(distance),
        "diagonalNormalized": stats(normalized),
        "corners": [
            {
                "truthIndex": i,
                "predictionIndex": int(indices[i]),
                "truthNormalized": gt[i].tolist(),
                "predictionNormalized": q[indices[i]].tolist(),
                "deltaPixels": delta[i].tolist(),
                "pixels": float(distance[i]),
                "diagonalNormalized": float(normalized[i]),
            }
            for i in range(4)
        ],
    }


def quality_metrics(report):
    """Re-score saved coordinates only; no detector, rendering or new labels."""
    rows = []
    for source in report["rows"]:
        row = {key: source[key] for key in ("set", "file", "source", "scored")}
        row["actual"] = {}
        if not source["scored"]:
            row["reason"] = "excluded-or-unavailable-frozen-truth"
            rows.append(row)
            continue
        size = source.get("sourceSize")
        if size is None:
            with Image.open(source["source"]) as image:
                width, height = image.size
                if image.getexif().get(274, 1) in (5, 6, 7, 8):
                    width, height = height, width
        else:
            width, height = size["width"], size["height"]
        row["sourceSize"] = {"width": width, "height": height}
        row["pixelCentreDiagonal"] = float(np.hypot(width - 1, height - 1))
        for mode, actual in source["actual"].items():
            row["actual"][mode] = (
                corner_errors(actual.get("quad"), source["truth"], width, height)
                if actual.get("valid", actual.get("quad") is not None)
                else {"valid": False, "reason": "detector-invalid-or-missing"}
            )
        rows.append(row)

    def summarize_quality(selected):
        scored = [r for r in selected if r["scored"]]
        summary = {"pages": len(scored)}
        for mode in sorted({m for r in scored for m in r["actual"]}):
            valid = [
                r["actual"][mode]
                for r in scored
                if r["actual"].get(mode, {}).get("valid")
            ]
            result = {
                "validPages": len(valid),
                "failedOrMissingPages": len(scored) - len(valid),
                "corners": len(valid) * 4,
            }
            for unit in ("pixels", "diagonalNormalized"):
                values = [c[unit] for r in valid for c in r["corners"]]
                result[unit] = (
                    {
                        "mean": float(np.mean(values)),
                        "p95": float(np.percentile(values, 95)),
                        "max": float(np.max(values)),
                    }
                    if values
                    else None
                )
            summary[mode] = result
        return summary

    return {
        "scope": "Saved-quad versus frozen-GT shape localization; no neural inference. Label registration/tracing error is included.",
        "measuresOrientation": False,
        "alignment": "Minimum mean pixel distance among four cyclic shifts in each of two winding directions; a single correspondence is used for all four corners.",
        "pixelConvention": "dx=(predX-gtX)*(W-1), dy=(predY-gtY)*(H-1), in upright source-image pixels.",
        "normalization": "Euclidean pixel distance / hypot(W-1,H-1); multiply by 100 for percent of image diagonal.",
        "summaryAggregation": "All four corners of every valid scored page; failed/missing predictions are counted separately, never assigned zero error.",
        "summary": {
            "all": summarize_quality(rows),
            **{
                group: summarize_quality([r for r in rows if r["set"] == group])
                for group in sorted({r["set"] for r in rows})
            },
        },
        "rows": rows,
    }


def difference(first, last):
    if first is None or last is None:
        return None
    return float(np.linalg.norm(np.asarray(first) - last, axis=1).max())


def summarize(rows):
    rows = [r for r in rows if r["scored"]]
    result = {"pages": len(rows)}
    for mode in ("preview720", "still2000"):
        selected = [r["actual"][mode] for r in rows]
        values = {"failed": sum(not r["valid"] for r in selected)}
        for key in (
            "iou",
            "coverage",
            "excess",
            "cornerMeanPixels",
            "elapsedMs",
            "pagenetAndSourceRefineMs",
            "outlineMs",
            "ruleFrameMs",
            "sheetEdgeMs",
        ):
            vals = [r[key] for r in selected if r.get(key) is not None]
            if vals:
                values[key] = {
                    "mean": float(np.mean(vals)),
                    "p50": float(np.median(vals)),
                    "p95": float(np.percentile(vals, 95)),
                    "min": float(np.min(vals)),
                    "max": float(np.max(vals)),
                }
        result[mode] = values
    for key in ("previewStillMaxCornerShift", "quickFullPreviewMaxCornerShift"):
        vals = [r[key] for r in rows if r.get(key) is not None]
        result[key] = (
            {
                "mean": float(np.mean(vals)),
                "p95": float(np.percentile(vals, 95)),
                "max": float(np.max(vals)),
            }
            if vals
            else None
        )
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--results", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument(
        "--report",
        type=Path,
        help="Only re-score saved corners into a separate quality-metrics.json",
    )
    args = parser.parse_args()
    if args.report is not None:
        original = args.report.read_bytes()
        quality = quality_metrics(json.loads(original.decode("utf-8")))
        quality["sourceReport"] = {
            "path": str(args.report.resolve()),
            "sha256": hashlib.sha256(original).hexdigest(),
        }
        output = (args.out or args.report.parent) / "quality-metrics.json"
        if output.resolve() == args.report.resolve():
            parser.error("The independent metrics must not overwrite the source report")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(quality, indent=2), encoding="utf-8")
        print(json.dumps(quality["summary"], indent=2), flush=True)
        return
    if args.baseline is None or args.results is None or args.out is None:
        parser.error(
            "Provide --baseline, --results and --out, or use --report for coordinate-only scoring"
        )
    repo = Path(__file__).resolve().parents[1]
    assert args.out.resolve() != repo and repo not in args.out.resolve().parents
    args.out.mkdir(parents=True, exist_ok=True)
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    raw = json.loads(args.results.read_text(encoding="utf-8"))
    lookup = {(r["set"], r["file"]): r for r in raw}
    assert len(lookup) == len(baseline["rows"]), "Probe is not complete"
    report = {
        "scope": __doc__,
        "integrated": False,
        "nativeVersions": sorted({r["opencv"] for r in raw}),
        "rows": [],
    }
    for source in baseline["rows"]:
        row = {k: v for k, v in source.items() if k != "models"}
        image = cv2.imread(row["source"])
        row["sourceSize"] = {"width": image.shape[1], "height": image.shape[0]}
        entry = lookup[(row["set"], row["file"])]
        actual = {}
        for mode in ("preview720", "still2000"):
            actual[mode] = dict(entry[mode])
            actual[mode].update(
                metrics(entry[mode].get("quad"), row["truth"], image.shape)
            )
        row["actual"] = actual
        row["previewStillMaxCornerShift"] = difference(
            actual["preview720"].get("quad"), actual["still2000"].get("quad")
        )
        row["quickFullPreviewMaxCornerShift"] = difference(
            actual["preview720"].get("quickQuad"), actual["preview720"].get("quad")
        )
        report["rows"].append(row)
        overlay = resize_edge(image, 1000)
        dims = [overlay.shape[1] - 1, overlay.shape[0] - 1]
        for quad, color in (
            (row["truth"], (0, 180, 0)),
            (actual["preview720"].get("quad"), (220, 80, 0)),
            (actual["still2000"].get("quad"), (0, 0, 230)),
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
            overlay, f"{row['set']}/{row['file']}", (6, 22), 0, 0.6, (0, 0, 0), 2
        )
        cv2.putText(
            overlay,
            f"green=label blue=720 red=2000 IoU={actual['still2000']['iou']:.3f}",
            (6, 45),
            0,
            0.5,
            (0, 0, 0),
            1,
        )
        folder = args.out / row["set"]
        folder.mkdir(exist_ok=True)
        cv2.imwrite(str(folder / (Path(row["file"]).stem + "-quads.jpg")), overlay)
    report["summary"] = {"all": summarize(report["rows"])}
    for group in {r["set"] for r in report["rows"]}:
        report["summary"][group] = summarize(
            [r for r in report["rows"] if r["set"] == group]
        )
    (args.out / "report.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8"
    )
    (args.out / "quality-metrics.json").write_text(
        json.dumps(quality_metrics(report), indent=2), encoding="utf-8"
    )
    print(json.dumps(report["summary"], indent=2), flush=True)


if __name__ == "__main__":
    main()
