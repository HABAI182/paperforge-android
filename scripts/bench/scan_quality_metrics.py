"""Scan-quality geometry metrics (#7). Reads the device benchmark JSON and writes summary + rows.

Usage:
  python scripts/bench/scan_quality_metrics.py <results.json> <manifest.json> <out-dir>

<results.json> comes from QualityBenchmarkTest (benchMode=current, benchStage=detect) pulled with
`adb exec-out run-as cn.paperforge.app cat files/quality-current/results.json`.
"""
import json
import math
import pathlib
import statistics
import sys

from PIL import Image, ImageDraw

RASTER = 512


def mask(poly, w, h):
    im = Image.new("1", (RASTER, RASTER), 0)
    ImageDraw.Draw(im).polygon(
        [(x / (w - 1) * (RASTER - 1), y / (h - 1) * (RASTER - 1)) for x, y in poly], fill=1
    )
    return im.load()


def raster_iou(p, q, w, h):
    a, b = mask(p, w, h), mask(q, w, h)
    inter = union = 0
    for y in range(RASTER):
        for x in range(RASTER):
            if a[x, y] or b[x, y]:
                union += 1
                if a[x, y] and b[x, y]:
                    inter += 1
    return 0.0 if union == 0 else inter / union


def poly(quad, w, h):
    return [(p[0] * (w - 1), p[1] * (h - 1)) for p in quad]


def shoelace(p):
    s = 0.0
    for i in range(len(p)):
        x1, y1 = p[i]
        x2, y2 = p[(i + 1) % len(p)]
        s += x1 * y2 - x2 * y1
    return abs(s) / 2


def main(results_path, manifest_path, out_dir):
    results_path = pathlib.Path(results_path)
    manifest = json.loads(pathlib.Path(manifest_path).read_text(encoding="utf-8"))
    rows = json.loads(results_path.read_text(encoding="utf-8"))["rows"]
    gt = {c["file"]: c for c in manifest["cases"]}
    out_dir = pathlib.Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    out = []
    for r in rows:
        case = gt.get(r["file"])
        w, h = r["decoded_width"], r["decoded_height"]
        pred = r["predicted"]
        entry = {
            "file": r["file"],
            "scene": r["scene"],
            "detect_ms": r["detect_ms"],
            "confidence": r["confidence"],
            "method": r["method"],
            "negative": case is None or case.get("quad") is None,
        }
        if entry["negative"]:
            entry["false_positive"] = pred is not None and (r["confidence"] or 0) >= 0.8
            entry["pred_area_fraction"] = shoelace(poly(pred, w, h)) / (w * h) if pred else None
            out.append(entry)
            continue
        g = poly(case["quad"], w, h)
        if pred is None:
            # No located page: the app still crops the full frame, which is what the user gets.
            entry["detected"] = False
            entry["fallback_full_frame"] = True
            p = [(0.0, 0.0), (float(w - 1), 0.0), (float(w - 1), float(h - 1)), (0.0, float(h - 1))]
        else:
            entry["detected"] = True
            p = poly(pred, w, h)
        entry["pred_area_fraction"] = shoelace(p) / (w * h)
        best = (-1.0, None)
        for cand in (p, list(reversed(p))):
            for k in range(4):
                c = cand[k:] + cand[:k]
                v = raster_iou(c, g, w, h)
                if v > best[0]:
                    best = (v, c)
        iou_v, matched = best
        diag = math.hypot(w, h)
        err = sum(math.hypot(a[0] - b[0], a[1] - b[1]) for a, b in zip(matched, g)) / 4 / diag * 100
        entry.update(iou=iou_v, corner_error_pct=err)
        out.append(entry)

    docs = [r for r in out if not r["negative"]]
    negs = [r for r in out if r["negative"]]
    lat = sorted(r["detect_ms"] for r in docs)

    def pct(v, p):
        k = (len(v) - 1) * p
        lo, hi = math.floor(k), math.ceil(k)
        return v[lo] if lo == hi else v[lo] * (hi - k) + v[hi] * (k - lo)

    scenes = {}
    for r in docs:
        scenes.setdefault(r["scene"], []).append(r)
    summary = {
        "engine": "app-current",
        "dataset": manifest["name"],
        "environment": "API35 x86_64 emulator",
        "physical_device": False,
        "detection_cases": len(docs),
        "negative_cases": len(negs),
        "mean_iou": statistics.mean(r["iou"] for r in docs),
        "median_iou": statistics.median(r["iou"] for r in docs),
        "mean_corner_error_pct": statistics.mean(r["corner_error_pct"] for r in docs),
        "failure_rate_iou_lt_070": sum(1 for r in docs if r["iou"] < 0.7) / max(1, len(docs)),
        "detection_rate": sum(1 for r in docs if r.get("detected", True)) / max(1, len(docs)),
        "full_frame_fallbacks": sum(1 for r in docs if r.get("fallback_full_frame")),
        "detect_ms_p50": pct(lat, .5),
        "detect_ms_p95": pct(lat, .95),
        "false_positive_rate_at_auto_capture_confidence": sum(1 for r in negs if r["false_positive"]) / max(1, len(negs)),
        "false_positive_rule": "negative case accepted when a quad is returned with confidence >= 0.8",
        "false_positive_files": [r["file"] for r in negs if r["false_positive"]],
        "scenes": {
            k: {
                "n": len(v),
                "mean_iou": statistics.mean(x["iou"] for x in v),
                "mean_corner_error_pct": statistics.mean(x["corner_error_pct"] for x in v),
                "failures": sum(1 for x in v if x["iou"] < 0.7),
            }
            for k, v in scenes.items()
        },
    }
    tag = results_path.stem.replace("results-", "")
    (out_dir / f"summary-{tag}.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    with (out_dir / f"rows-{tag}.csv").open("w", encoding="utf-8", newline="") as f:
        f.write("file,scene,iou,corner_error_pct,detect_ms,confidence,method,negative,false_positive,pred_area_fraction\n")
        for r in out:
            f.write(",".join([
                r["file"], r["scene"],
                f'{r.get("iou", "")}', f'{r.get("corner_error_pct", "")}',
                f'{r["detect_ms"]:.1f}',
                f'{r["confidence"] if r["confidence"] is not None else ""}',
                r["method"], str(r["negative"]), str(r.get("false_positive", "")),
                f'{r.get("pred_area_fraction", "")}',
            ]) + "\n")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: scan_quality_metrics.py <results.json> <manifest.json> <out-dir>")
    main(sys.argv[1], sys.argv[2], sys.argv[3])
