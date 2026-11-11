"""Truth-free acceptance gates for independent DocAligner crop proposals.

Reuses saved independent candidate predictions; the ground-truth polygon is read
only after selection, for scoring. A proposal must retain the PageNet high-score
core, stay near its area and improve distributed raw-material boundary evidence.
This is a single predetermined experiment, not a per-page oracle or grid sweep.
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

from benchmark_crop_candidates import metrics, ordered, resize_edge, summarize, valid
from refine_crop_edges import _sample


def source_line_evidence(image, quad):
    if not valid(quad):
        return {"mean": 0.0, "minimum": 0.0, "distributedSides": 0}
    small = resize_edge(image, 960)
    h, w = small.shape[:2]
    rgb = cv2.GaussianBlur(small.astype("float32") / 255, (0, 0), 0.8)
    gx = cv2.Sobel(rgb, cv2.CV_32F, 1, 0, ksize=3) / 8
    gy = cv2.Sobel(rgb, cv2.CV_32F, 0, 1, ksize=3) / 8
    q = ordered(quad) * [w - 1, h - 1]
    centre = q.mean(0)
    scores, distributed = [], 0
    for i in range(4):
        a, b = q[i], q[(i + 1) % 4]
        direction = (b - a) / max(1e-8, np.linalg.norm(b - a))
        normal = np.array([-direction[1], direction[0]])
        if np.dot(centre - (a + b) / 2, normal) > 0:
            normal *= -1
        points = a[None] + (b - a)[None] * np.linspace(0.08, 0.92, 96)[:, None]
        # +/- 2px tolerance is identical for baseline and candidate.
        probes = points[:, None] + normal * np.arange(-2, 3)[None, :, None]
        gradient = np.max(
            np.abs(_sample(gx, probes) * normal[0] + _sample(gy, probes) * normal[1]),
            -1,
        )
        inside = np.mean([_sample(rgb, probes - d * normal) for d in [2, 4, 7]], 0)
        outside = np.mean([_sample(rgb, probes + d * normal) for d in [2, 4, 7]], 0)
        material = np.sqrt(np.mean((inside - outside) ** 2, -1))
        strength = (np.minimum(gradient, 0.2) * np.clip(material / 0.035, 0, 1)).max(1)
        good = strength > 0.012
        scores.append(float(good.mean()))
        distributed += int(sum(s.mean() > 0.35 for s in np.array_split(good, 6)) >= 5)
    return {
        "mean": float(np.mean(scores)),
        "minimum": float(min(scores)),
        "distributedSides": distributed,
        "sides": scores,
    }


def core_coverage(quad, core):
    if not valid(quad) or core.sum() == 0:
        return 0.0
    h, w = core.shape
    mask = np.zeros_like(core, "uint8")
    cv2.fillConvexPoly(mask, (ordered(quad) * [w, h] - 0.5).round().astype("int32"), 1)
    return float(np.mean(mask[core > 0]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidates", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--baseline-name", default="production")
    parser.add_argument("--candidate-name", action="append", default=[])
    args = parser.parse_args()
    repo, out = Path(__file__).resolve().parents[1], args.out.resolve()
    assert out != repo and repo not in out.parents
    out.mkdir(parents=True, exist_ok=True)
    cv2.setNumThreads(1)
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    external = json.loads(args.candidates.read_text(encoding="utf-8"))
    indexed = {(r["set"], r["file"]): r for r in external["rows"]}
    model_sha = hashlib.sha256(args.model.read_bytes()).hexdigest()
    assert model_sha == baseline["modelProvenance"][args.baseline_name]["sha256"]
    names = args.candidate_name or ["lcnet_bgr", "fastvit_rgb"]
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(
        str(args.model), options, providers=["CPUExecutionProvider"]
    )
    report = {
        "scope": "PC truth-free fallback over saved predictions; no app integration or phone timing",
        "parameters": {
            "coreThreshold": 0.8,
            "coreMinimumCoverage": 0.98,
            "areaRatio": [0.9, 1.25],
            "sourceSupportMeanGain": 0.08,
            "minimumSideSupport": 0.35,
            "minimumMeanSupport": 0.6,
            "distributedSides": 4,
        },
        "modelSha256": model_sha,
        "candidateNames": names,
        "integrated": False,
        "rows": [],
    }
    for source_row in baseline["rows"]:
        other = indexed[(source_row["set"], source_row["file"])]
        assert source_row["sourceSha256"] == other["sourceSha256"]
        image = cv2.imread(source_row["source"])
        row = {k: v for k, v in source_row.items() if k != "models"}
        base = source_row["models"][args.baseline_name]
        row["models"] = {"baseline": base, "gated-fallback": {}}
        row["decisions"] = {}
        for mode, edge in (("preview720", 720), ("still2000", 2000)):
            frame = resize_edge(image, edge)
            start = time.perf_counter()
            rgb = cv2.cvtColor(
                cv2.resize(frame, (320, 320), interpolation=cv2.INTER_AREA),
                cv2.COLOR_BGR2RGB,
            )
            tensor = (
                np.ascontiguousarray(rgb.transpose(2, 0, 1)[None], dtype="float32")
                / 255
            )
            logits = session.run(None, {session.get_inputs()[0].name: tensor})[0][0, 0]
            core = logits > np.log(0.8 / 0.2)
            initial = base[mode]["quad"]
            base_area = (
                cv2.contourArea(np.asarray(initial, "float32")) if valid(initial) else 0
            )
            base_evidence = source_line_evidence(frame, initial)
            selected, selected_name = initial, "baseline"
            best_score = base_evidence["mean"]
            decisions = []
            for name in names:
                proposal = other["models"][name][mode]["quad"]
                candidate_valid = valid(proposal)
                area = (
                    cv2.contourArea(np.asarray(proposal, "float32"))
                    if candidate_valid
                    else 0
                )
                ratio = area / max(1e-8, base_area)
                coverage = core_coverage(proposal, core)
                evidence = source_line_evidence(frame, proposal)
                accepted = (
                    candidate_valid
                    and 0.9 <= ratio <= 1.25
                    and coverage >= 0.98
                    and evidence["mean"] >= base_evidence["mean"] + 0.08
                    and evidence["minimum"] >= 0.35
                    and evidence["mean"] >= 0.6
                    and evidence["distributedSides"] == 4
                )
                if accepted and evidence["mean"] > best_score:
                    best_score, selected, selected_name = (
                        evidence["mean"],
                        proposal,
                        name,
                    )
                decisions.append(
                    {
                        "name": name,
                        "valid": bool(candidate_valid),
                        "areaRatio": ratio,
                        "highConfidenceCoreCoverage": coverage,
                        "sourceBoundaryEvidence": evidence,
                        "accepted": bool(accepted),
                    }
                )
            elapsed = (time.perf_counter() - start) * 1000
            result = metrics(selected, source_row["truth"], image.shape)
            result.update(
                quad=selected,
                inferenceMs=base[mode]["inferenceMs"],
                postprocessMs=elapsed,
                selected=selected_name,
            )
            row["models"]["gated-fallback"][mode] = result
            row["decisions"][mode] = {
                "baseEvidence": base_evidence,
                "proposals": decisions,
            }
        first, last = (
            row["models"]["gated-fallback"][mode]["quad"]
            for mode in ("preview720", "still2000")
        )
        row["models"]["gated-fallback"]["previewStillMaxCornerShift"] = (
            float(np.linalg.norm(np.array(first) - last, axis=1).max())
            if first is not None and last is not None
            else None
        )
        report["rows"].append(row)
    report["summary"] = {"all": summarize(report["rows"])}
    for name in {r["set"] for r in report["rows"]}:
        report["summary"][name] = summarize(
            [r for r in report["rows"] if r["set"] == name]
        )
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report["summary"]["all"], indent=2), flush=True)


if __name__ == "__main__":
    main()
