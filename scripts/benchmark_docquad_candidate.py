"""Evaluate the shipped DocQuad asset with its actual Java postprocessor.

RGB 256 letterbox is sampled on the PC at fractional offsets. The Java product
decoder is freshly compiled unchanged. This excludes PrimaryPaperFrame and the
Android Canvas/Bitmap path; bridge timing includes JVM startup and is NOT an
estimate of Android postprocess or phone latency. Owner photos are eval-only.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

from benchmark_crop_candidates import metrics, ordered, resize_edge, summarize


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    assert args.out.resolve() != repo and repo not in args.out.resolve().parents
    args.out.mkdir(parents=True, exist_ok=True)
    sources = list(
        (repo / "app/src/main/java/cn/paperforge/app/imaging/docquad").glob("*.java")
    )
    sources.append(repo / "scripts/DocQuadLabBridge.java")
    classes = args.out / "java"
    classes.mkdir(exist_ok=True)
    subprocess.run(
        ["javac", "-encoding", "UTF-8", "-d", str(classes), *map(str, sources)],
        check=True,
    )
    provenance = {
        str(p): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in [*sources, args.model]
    }
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(
        str(args.model), options, providers=["CPUExecutionProvider"]
    )
    for _ in range(3):
        session.run(None, {"input": np.zeros((1, 3, 256, 256), "float32")})
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    report = {
        "scope": __doc__,
        "integrated": False,
        "provenance": provenance,
        "rows": [],
    }
    cv2.setNumThreads(1)
    binary = args.out / "planes.bin"
    for source in baseline["rows"]:
        image = cv2.imread(source["source"])
        assert image is not None
        variants = {}
        for mode, edge in (("preview720", 720), ("still2000", 2000)):
            small = resize_edge(image, edge)
            h, w = small.shape[:2]
            scale = 256 / max(w, h)
            dx, dy = (256 - w * scale) / 2, (256 - h * scale) / 2
            matrix = np.array(
                [[scale, 0, dx + (scale - 1) / 2], [0, scale, dy + (scale - 1) / 2]]
            )
            rgb = cv2.cvtColor(small, cv2.COLOR_BGR2RGB)
            canvas = cv2.warpAffine(rgb, matrix, (256, 256), flags=cv2.INTER_LINEAR)
            tensor = canvas.transpose(2, 0, 1)[None].astype("float32") / 255
            start = time.perf_counter()
            output = session.run(None, {"input": tensor})
            inference = (time.perf_counter() - start) * 1000
            named = dict(zip([o.name for o in session.get_outputs()], output))
            np.concatenate(
                [named["corner_heatmaps"].ravel(), named["mask_logits"].ravel()]
            ).astype("<f4").tofile(binary)
            start = time.perf_counter()
            response = subprocess.run(
                [
                    "java",
                    "-cp",
                    str(classes),
                    "DocQuadLabBridge",
                    str(binary),
                    str(w),
                    str(h),
                ],
                check=True,
                text=True,
                capture_output=True,
            )
            bridge_ms = (time.perf_counter() - start) * 1000
            detail = json.loads(response.stdout)
            quad = (
                ordered(np.asarray(detail["quad"]) / [w, h]) if detail["quad"] else None
            )
            if quad is not None and abs(cv2.contourArea(quad.astype("float32"))) < 0.06:
                quad = None
            values = metrics(quad, source["truth"], image.shape)
            values.update(
                quad=None if quad is None else quad.tolist(),
                inferenceMs=inference,
                bridgeWithJvmStartupMs=bridge_ms,
                decoder=detail,
            )
            variants[mode] = values
        first, last = [variants[m]["quad"] for m in ("preview720", "still2000")]
        variants["previewStillMaxCornerShift"] = (
            float(np.linalg.norm(np.asarray(first) - last, axis=1).max())
            if first is not None and last is not None
            else None
        )
        row = {k: v for k, v in source.items() if k != "models"}
        row["models"] = {"docquad": variants}
        report["rows"].append(row)
        overlay = resize_edge(image, 1000)
        dims = [overlay.shape[1] - 1, overlay.shape[0] - 1]
        for quad, color in ((source["truth"], (0, 170, 0)), (last, (0, 0, 220))):
            if quad is not None:
                cv2.polylines(
                    overlay,
                    [(np.asarray(quad) * dims).round().astype("int32")],
                    True,
                    color,
                    2,
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
    print(json.dumps(report["summary"]["all"], indent=2))


if __name__ == "__main__":
    main()
