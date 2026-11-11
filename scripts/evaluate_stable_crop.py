"""Evaluate joint quad fitting from cached PageNet masks, without retraining.

The first run fills missing mask/boundary cache entries only. Later fits reuse
those tensors; all real inputs and quads are frozen by the earlier baseline
report. This omits Android's source-pixel crop refinement stages.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

from benchmark_crop_candidates import metrics, ordered, resize_edge, summarize
from stable_quad_fit import fit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--baseline-name", default="production")
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    for path in (args.cache, args.out):
        assert path.resolve() != repo and repo not in path.resolve().parents
        path.mkdir(parents=True, exist_ok=True)
    cv2.setNumThreads(1)
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    model_sha = hashlib.sha256(args.model.read_bytes()).hexdigest()
    assert model_sha == baseline["modelProvenance"][args.baseline_name]["sha256"]
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = None
    report = {
        "scope": "PC cached mask+boundary joint fitting; not Android pipeline or camera timing",
        "integrated": False,
        "ownerPhotosEvaluationOnly": True,
        "modelSha256": model_sha,
        "cacheMisses": 0,
        "rows": [],
    }
    for record in baseline["rows"]:
        image = cv2.imread(record["source"])
        assert (
            hashlib.sha256(Path(record["source"]).read_bytes()).hexdigest()
            == record["sourceSha256"]
        )
        row = {k: v for k, v in record.items() if k != "models"}
        base = record["models"][args.baseline_name]
        row["models"] = {"baseline": base, "joint-fit": {}}
        for mode, edge in (("preview720", 720), ("still2000", 2000)):
            frame = resize_edge(image, edge)
            rgb = cv2.cvtColor(
                cv2.resize(frame, (320, 320), interpolation=cv2.INTER_AREA),
                cv2.COLOR_BGR2RGB,
            )
            tensor = (
                np.ascontiguousarray(rgb.transpose(2, 0, 1)[None], dtype="float32")
                / 255
            )
            key = hashlib.sha256(model_sha.encode() + tensor.tobytes()).hexdigest()
            cache = args.cache / (key + ".npz")
            if cache.is_file():
                stored = np.load(cache)
                mask, boundary = stored["mask"], stored["boundary"]
            else:
                if session is None:
                    session = ort.InferenceSession(
                        str(args.model), options, providers=["CPUExecutionProvider"]
                    )
                logits = session.run(None, {session.get_inputs()[0].name: tensor})[0][
                    0, :2
                ]
                probability = 1 / (1 + np.exp(-np.clip(logits, -80, 80)))
                mask, boundary = probability
                np.savez_compressed(cache, mask=mask, boundary=boundary)
                report["cacheMisses"] += 1
            started = time.perf_counter()
            quad, diagnostics = fit(mask, boundary)
            elapsed = (time.perf_counter() - started) * 1000
            quad = ordered(quad) if quad is not None else None
            result = metrics(quad, record["truth"], image.shape)
            result.update(
                quad=quad.tolist() if quad is not None else None,
                postprocessMs=elapsed,
                inferenceMs=base[mode]["inferenceMs"],
                diagnostics=diagnostics,
                cacheKey=key,
            )
            row["models"]["joint-fit"][mode] = result
        first, last = (
            row["models"]["joint-fit"][m]["quad"] for m in ("preview720", "still2000")
        )
        row["models"]["joint-fit"]["previewStillMaxCornerShift"] = (
            float(np.linalg.norm(np.array(first) - last, axis=1).max())
            if first is not None and last is not None
            else None
        )
        report["rows"].append(row)
        overlay = resize_edge(image, 1000)
        dims = [overlay.shape[1] - 1, overlay.shape[0] - 1]
        for quad, color in (
            (record["truth"], (0, 180, 0)),
            (base["still2000"]["quad"], (220, 80, 0)),
            (last, (0, 0, 230)),
        ):
            if quad is not None:
                cv2.polylines(
                    overlay,
                    [(np.array(quad) * dims).round().astype("int32")],
                    True,
                    color,
                    2,
                )
        cv2.putText(
            overlay, f"{record['set']}/{record['file']}", (5, 22), 0, 0.6, (0, 0, 0), 2
        )
        folder = args.out / record["set"]
        folder.mkdir(exist_ok=True)
        cv2.imwrite(str(folder / (Path(record["file"]).stem + "-quads.jpg")), overlay)
    report["summary"] = {"all": summarize(report["rows"])}
    for name in {r["set"] for r in report["rows"]}:
        report["summary"][name] = summarize(
            [r for r in report["rows"] if r["set"] == name]
        )
    (args.out / "report.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8"
    )
    print(json.dumps(report["summary"]["all"], indent=2), flush=True)


if __name__ == "__main__":
    main()
