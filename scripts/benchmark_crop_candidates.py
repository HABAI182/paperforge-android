"""Offline crop comparison on the four existing real-photo truth sets.

Mirrors PageNetDetector's RGB/area input, mask fit and boundary snap (v3),
including pixel-centre coordinates and half-pixel/whole-pixel search steps.
This is NOT the full Android pipeline: CoverOutline, TopSheetRules, EdgeQuad,
RuleFrame, SheetEdge, camera tracking, decode and capture decisions are absent.
Owner photographs are evaluation-only and no gradients or checkpoints are made.

Also supports independent DocAligner heatmap candidates rehosted by quadscan:
256 RGB, LCNet [0,1] or FastViT [-1,1], four 128-square sigmoid heatmaps.
The decoder matches the published radius-4 centroid / 0.3 peak-ratio / 0.1
confidence settings. These scores are separate from PageNet mask fitting.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

from train_page_net import quad_from_mask


SETS = (
    ("camscanner7", "camscanner-1107.json", "phone-2026-11-07"),
    ("new4", "hand-1110.json", "phone-1110"),
    ("reg7", "hand-reg.json", "bench-reg"),
    ("docunet", "docunet-truth.json", "docunet-20"),
)
# Claude's crop-standard note records these registrations as unreliable.
UNRELIABLE = {"1_2", "9_2", "10_1"}


def ordered(quad):
    points = np.asarray(quad, dtype=np.float64).reshape(4, 2)
    centre = points.mean(0)
    points = points[
        np.argsort(np.arctan2(points[:, 1] - centre[1], points[:, 0] - centre[0]))
    ]
    return np.roll(points, -int(np.argmin(points.sum(1))), axis=0)


def valid(quad):
    if quad is None:
        return False
    q = np.asarray(quad).reshape(4, 2)
    d = np.roll(q, -1, axis=0) - q
    turns = d[:, 0] * np.roll(d[:, 1], -1) - d[:, 1] * np.roll(d[:, 0], -1)
    return bool(
        np.isfinite(q).all()
        and ((q >= 0) & (q <= 1)).all()
        and cv2.contourArea(q.astype("float32")) > 0.015
        and (turns > 0.0001).all()
    )


def _samples(field, a, b):
    """Exact bilinear sampling; OpenCV remap rounds to 1/32-pixel phases."""
    n = field.shape[0]
    t = np.linspace(0.1, 0.9, 48)
    points = a[..., None, :] + (b - a)[..., None, :] * t[:, None]
    x, y = points[..., 0], points[..., 1]
    inside = (x >= 0) & (y >= 0) & (x <= n - 1) & (y <= n - 1)
    xx = np.floor(x).astype("int32").clip(0, n - 2)
    yy = np.floor(y).astype("int32").clip(0, n - 2)
    fx, fy = (x - xx).clip(0, 1), (y - yy).clip(0, 1)
    v = (
        field[yy, xx] * (1 - fx) * (1 - fy)
        + field[yy, xx + 1] * fx * (1 - fy)
        + field[yy + 1, xx] * (1 - fx) * fy
        + field[yy + 1, xx + 1] * fx * fy
    )
    count = inside.sum(-1)
    mean = np.where(count >= 24, (v * inside).sum(-1) / np.maximum(1, count), -1)
    continuity = np.where(
        count >= 24, ((v > 0.35) & inside).sum(-1) / np.maximum(1, count), 0
    )
    return mean, continuity


def boundary_snap(quad, edge, params):
    """Original Python port of the app's current PageNet boundary search."""
    if quad is None:
        return None
    n = edge.shape[0]
    field = cv2.GaussianBlur(edge.astype("float32"), (3, 3), 0)
    points = ordered(quad) * n - 0.5
    centre = points.mean(0)
    far = float(params.get("pageNetEdgeSnapFar", 0.3)) * n
    reach = float(params.get("pageNetEdgeSnapReach", 0.06)) * n
    penalty = float(params.get("pageNetEdgeSnapPenalty", 0.15))
    gain = float(params.get("pageNetEdgeSnapFarGain", 0.3))
    continuous = float(params.get("pageNetEdgeSnapFarContinuity", 0.9))
    angle = int(params.get("pageNetEdgeSnapAngle", 5))
    offsets = np.arange(-far, far + 1e-6, 0.5)
    near = (offsets >= 0) & (offsets <= reach)
    offsets = offsets[near | (np.abs(offsets - np.round(offsets)) <= 1e-6)]
    angles = np.radians(np.arange(-angle, angle + 1))
    lines = []
    for i in range(4):
        a, b = points[i], points[(i + 1) % 4]
        direction = (b - a) / max(1e-6, np.linalg.norm(b - a))
        normal = np.array([-direction[1], direction[0]])
        midpoint = (a + b) / 2
        if np.dot(centre - midpoint, normal) < 0:
            normal *= -1
        here = float(_samples(field, a, b)[0])
        ux = direction[0] * np.cos(angles) - direction[1] * np.sin(angles)
        uy = direction[0] * np.sin(angles) + direction[1] * np.cos(angles)
        half_vectors = np.stack([ux, uy], -1) * np.linalg.norm(b - a) * 0.6
        mids = midpoint + offsets[:, None] * normal
        aa = mids[:, None, :] - half_vectors[None]
        bb = mids[:, None, :] + half_vectors[None]
        scores, continuity = _samples(field, aa, bb)
        best, line = here, np.stack([a, b])
        far_best, far_line = -1.0, None
        # Sequential near-score update is intentional: preserve Kotlin's rule.
        for j, offset in enumerate(offsets):
            for k in range(len(angles)):
                score = scores[j, k]
                if 0 <= offset <= reach and score > best + penalty * offset / max(
                    reach, 1e-6
                ):
                    best, line = score, np.stack([aa[j, k], bb[j, k]])
                if (
                    far > 0
                    and score >= here + gain
                    and score > far_best
                    and continuity[j, k] >= continuous
                ):
                    far_best, far_line = score, np.stack([aa[j, k], bb[j, k]])
        lines.append(far_line if far_line is not None and far_best > best else line)
    corners = []
    for i in range(4):
        p, q = lines[i - 1], lines[i]
        u, v = p[1] - p[0], q[1] - q[0]
        det = u[0] * v[1] - u[1] * v[0]
        if abs(det) < 1e-9:
            return ordered(quad)
        z = q[0] - p[0]
        corners.append(p[0] + u * (z[0] * v[1] - z[1] * v[0]) / det)
    result = ordered(((np.array(corners) + 0.5) / n).clip(0, 1))
    old = ordered(quad)
    return (
        result
        if valid(result)
        and cv2.contourArea(result.astype("float32"))
        >= 0.4 * cv2.contourArea(old.astype("float32"))
        else old
    )


def metrics(quad, truth, shape):
    truth = ordered(truth).astype("float32")
    area = cv2.contourArea(truth)
    if not valid(quad):
        return dict(
            valid=False,
            iou=0.0,
            coverage=0.0,
            excess=0.0,
            cornerMaxNormalized=None,
            cornerMeanPixels=None,
        )
    q = ordered(quad).astype("float32")
    # OpenCV's geometric epsilon is absolute. Unit-square coordinates can make
    # nearly parallel page edges produce impossible intersections (IoU > 1).
    # Use the same 1000px coordinate scale as Claude's score_std.py.
    intersection = (
        float(cv2.intersectConvexConvex(q * 1000, truth * 1000)[0]) / 1_000_000
    )
    qa = cv2.contourArea(q)
    delta = q - truth
    return dict(
        valid=True,
        iou=intersection / max(qa + area - intersection, 1e-8),
        coverage=intersection / max(area, 1e-8),
        excess=max(0.0, qa - intersection) / max(area, 1e-8),
        cornerMaxNormalized=float(np.linalg.norm(delta, axis=1).max()),
        cornerMeanPixels=float(
            np.linalg.norm(delta * [shape[1] - 1, shape[0] - 1], axis=1).mean()
        ),
    )


def resize_edge(image, edge):
    h, w = image.shape[:2]
    scale = min(1, edge / max(h, w))
    return cv2.resize(
        image, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA
    )


def heatmap_corners(
    maps, logits=False, expectation=False, minimum_confidence=0.1, frame_shape=None
):
    """Decode each corner independently; never choose coordinates using truth."""
    points, peaks = [], [float(field.max()) for field in maps]
    for field in maps:
        h, w = field.shape
        if logits:
            probability = np.exp(field.astype("float64") - field.max())
            probability /= probability.sum()
        else:
            probability = field.astype("float64")
        py, px = np.unravel_index(probability.argmax(), probability.shape)
        peak = float(probability[py, px])
        if not logits and peak < minimum_confidence:
            return None, peaks
        if expectation:
            yy, xx = np.indices(probability.shape)
            weights = probability
        else:
            x0, x1, y0, y1 = (
                max(0, px - 4),
                min(w, px + 5),
                max(0, py - 4),
                min(h, py + 5),
            )
            yy, xx = np.mgrid[y0:y1, x0:x1]
            weights = probability[y0:y1, x0:x1].copy()
            weights[weights < peak * 0.3] = 0
        total = max(1e-12, weights.sum())
        # Our synthetic training labels use pixel centres. The independent
        # DocAligner browser decoder scales its raw grid coordinate without +.5.
        offset = 0.5 if logits else 0
        points.append(
            [
                (float((weights * xx).sum()) / total + offset) / w,
                (float((weights * yy).sum()) / total + offset) / h,
            ]
        )
    points = np.array(points)
    if frame_shape is not None:
        # Centred synthetic labels describe edge-normalized pixel centres.
        # Convert to this frame's pixel coordinates before Quad endpoint units.
        fh, fw = frame_shape[:2]
        points = (points * [fw, fh] - 0.5) / [fw - 1, fh - 1]
    return ordered(points.clip(0, 1)), peaks


def docaligner_official(maps, shape):
    """Official DocAligner threshold/contour decoder, implemented with OpenCV.

    Mirrors DocAligner@3275b0f... heatmap_reg/infer.py and Capybara's documented
    Otsu imbinarize. Unlike the quadscan wrapper it resizes each map to the
    source dimensions before thresholding and uses polygon area centroids.
    """
    h, w = shape[:2]
    points, peaks = [], [float(m.max()) for m in maps]
    for field in maps:
        prediction = cv2.resize(field, (w, h), interpolation=cv2.INTER_LINEAR)
        prediction[prediction < 0.3] = 0
        byte = (prediction * 255).astype("uint8")
        _, binary = cv2.threshold(byte, 0, 255, cv2.THRESH_OTSU | cv2.THRESH_BINARY)
        contours, _ = cv2.findContours(
            binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE
        )
        if not contours:
            return None, peaks
        contour = max(contours, key=cv2.contourArea)
        moments = cv2.moments(contour)
        if moments["m00"] <= 0:
            return None, peaks
        points.append(
            [moments["m10"] / moments["m00"] / w, moments["m01"] / moments["m00"] / h]
        )
    return ordered(np.array(points).clip(0, 1)), peaks


def summarize(rows):
    selected = [r for r in rows if r["scored"]]
    result = {
        "scored": len(selected),
        "unreliableTruthExcluded": len(rows) - len(selected),
    }
    for model in sorted({name for r in rows for name in r["models"]}):
        by_size = {}
        for mode in ("preview720", "still2000"):
            values = [r["models"][model][mode] for r in selected]
            summary = {"failed": sum(not v["valid"] for v in values)}
            for key in (
                "iou",
                "coverage",
                "excess",
                "cornerMaxNormalized",
                "cornerMeanPixels",
                "inferenceMs",
                "postprocessMs",
            ):
                vals = [v[key] for v in values if v.get(key) is not None]
                if vals:
                    summary[key] = {
                        "mean": float(np.mean(vals)),
                        "p95": float(np.percentile(vals, 95)),
                        "min": float(np.min(vals)),
                        "max": float(np.max(vals)),
                    }
            by_size[mode] = summary
        shifts = [
            r["models"][model]["previewStillMaxCornerShift"]
            for r in selected
            if r["models"][model]["previewStillMaxCornerShift"] is not None
        ]
        by_size["previewStillMaxCornerShift"] = (
            {"mean": float(np.mean(shifts)), "max": float(np.max(shifts))}
            if shifts
            else None
        )
        result[model] = by_size
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument(
        "--onnx", action="append", required=True, help="NAME=PATH, repeat to compare"
    )
    parser.add_argument(
        "--params",
        type=Path,
        default=Path(__file__).resolve().parents[1]
        / "app/src/main/assets/scan_params.json",
    )
    parser.add_argument("--no-snap", action="store_true")
    parser.add_argument(
        "--official-docaligner",
        action="append",
        default=[],
        metavar="NAME",
        help="Use original DocAligner /255 and contour decoder, not quadscan wrapper",
    )
    parser.add_argument(
        "--bgr",
        action="append",
        default=[],
        metavar="NAME",
        help="Preserve OpenCV BGR input for this independently evaluated model",
    )
    parser.add_argument(
        "--source-refine",
        action="store_true",
        help="Also score source-normal edge refinement as a separate PageNet variant",
    )
    parser.add_argument(
        "--relative-docaligner",
        action="append",
        default=[],
        metavar="NAME",
        help="Experimental /255 relative-peak centroid decoder with min confidence .01; distinct from official",
    )
    parser.add_argument(
        "--centred-corners",
        action="append",
        default=[],
        metavar="NAME",
        help="Decode the corner head's edge-normalized cell centres into frame endpoint units",
    )
    parser.add_argument(
        "--docunet",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "bench-private/docunet/original",
        help="Full corpus fallback when work/docunet-20 contains only a subset",
    )
    args = parser.parse_args()
    repo, out = Path(__file__).resolve().parents[1], args.out.resolve()
    assert out != repo and repo not in out.parents, (
        "Private images/results must stay outside the repository"
    )
    out.mkdir(parents=True, exist_ok=True)
    cv2.setNumThreads(1)
    params = json.loads(args.params.read_text(encoding="utf-8"))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    sessions, provenance, configs = {}, {}, {}
    for argument in args.onnx:
        name, filename = argument.split("=", 1)
        assert re.fullmatch(r"[A-Za-z0-9_-]+", name) and name not in sessions
        path = Path(filename)
        session = ort.InferenceSession(
            str(path), options, providers=["CPUExecutionProvider"]
        )
        shape = session.get_inputs()[0].shape
        assert shape[1:] in ([3, 320, 320], [3, 256, 256]), (name, shape)
        family = "pagenet" if shape[-1] == 320 else "docaligner"
        official = name in args.official_docaligner or name in args.relative_docaligner
        signed = (
            family == "docaligner" and "fastvit" in path.name.lower() and not official
        )
        configs[name] = {
            "family": family,
            "signed": signed,
            "edge": shape[-1],
            "official": official,
            "bgr": name in args.bgr,
            "relative": name in args.relative_docaligner,
            "centredCorners": name in args.centred_corners,
        }
        sessions[name] = session
        for _ in range(3):
            session.run(
                None,
                {
                    session.get_inputs()[0].name: np.zeros(
                        (1, 3, shape[-1], shape[-1]), "float32"
                    )
                },
            )
        colour = "BGR" if name in args.bgr else "RGB"
        provenance[name] = {
            "path": str(path.resolve()),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "input": shape,
            "family": family,
            "normalization": colour + ("/127.5-1" if signed else "/255"),
            "resize": "area" if family == "pagenet" else "linear",
            "decoder": "relative-centroid-min001"
            if name in args.relative_docaligner
            else "official-contour-otsu"
            if official
            else "pagenet-mask-snap"
            if family == "pagenet"
            else "quadscan-local-centroid",
            "cornerCoordinateConvention": "edge-normalized centres -> frame pixel -> Quad endpoints"
            if name in args.centred_corners
            else "legacy direct normalized coordinates",
        }
    report = {
        "scope": "PC PageNet mask fit + boundary snap only; NOT complete Android detection or phone timing",
        "integrated": False,
        "ownerPhotosEvaluationOnly": True,
        "modelProvenance": provenance,
        "parameters": params,
        "rows": [],
        "missing": [],
        "excludedDocunetStems": sorted(UNRELIABLE),
    }
    for set_name, truth_name, photos in SETS:
        truth_file = args.work / "truth" / truth_name
        for truth_row in json.loads(truth_file.read_text(encoding="utf-8")):
            path = args.work / photos / truth_row["file"]
            if (
                set_name == "docunet"
                and not path.is_file()
                and (args.docunet / truth_row["file"]).is_file()
            ):
                path = args.docunet / truth_row["file"]
            if truth_row.get("quad") is None:
                report["missing"].append(
                    {"set": set_name, "file": str(path), "reason": "no-truth"}
                )
                continue
            image = cv2.imread(str(path))
            if image is None:
                report["missing"].append(
                    {"set": set_name, "file": str(path), "reason": "no-photo"}
                )
                continue
            truth = ordered(truth_row["quad"])
            row = {
                "set": set_name,
                "file": path.name,
                "source": str(path.resolve()),
                "sourceSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "scored": set_name != "docunet" or path.stem not in UNRELIABLE,
                "truth": truth.tolist(),
                "models": {},
            }
            overlay = resize_edge(image, 1100)
            dims = np.array([overlay.shape[1] - 1, overlay.shape[0] - 1])
            cv2.polylines(
                overlay, [(truth * dims).round().astype("int32")], True, (0, 180, 0), 3
            )
            for name, session in sessions.items():
                config = configs[name]
                variants = {}
                for mode, edge in (("preview720", 720), ("still2000", 2000)):
                    small = resize_edge(image, edge)
                    interpolation = (
                        cv2.INTER_AREA
                        if config["family"] == "pagenet"
                        else cv2.INTER_LINEAR
                    )
                    resized = cv2.resize(
                        small,
                        (config["edge"], config["edge"]),
                        interpolation=interpolation,
                    )
                    rgb = (
                        resized
                        if config["bgr"]
                        else cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
                    )
                    tensor = np.ascontiguousarray(
                        rgb.transpose(2, 0, 1)[None], dtype="float32"
                    )
                    tensor = tensor / 127.5 - 1 if config["signed"] else tensor / 255
                    start = time.perf_counter()
                    output = session.run(None, {session.get_inputs()[0].name: tensor})[
                        0
                    ][0]
                    inference = (time.perf_counter() - start) * 1000
                    start = time.perf_counter()
                    answers = {}
                    if config["family"] == "pagenet":
                        probability = 1 / (1 + np.exp(-np.clip(output[:2], -80, 80)))
                        found = quad_from_mask(
                            (
                                probability[0] > float(params.get("pageNetCut", 0.15))
                            ).astype("float32")
                        )
                        quad = ordered(found) if found is not None else None
                        if (
                            not args.no_snap
                            and params.get("pageNetEdgeSnap", 1)
                            and quad is not None
                        ):
                            quad = boundary_snap(quad, probability[1], params)
                        answers[name] = (
                            quad,
                            None,
                            (time.perf_counter() - start) * 1000,
                        )
                        if args.source_refine:
                            from refine_crop_edges import refine

                            begin = time.perf_counter()
                            refined, detail = refine(small, quad)
                            answers[name + "_source-lines"] = (
                                refined,
                                detail,
                                answers[name][2] + (time.perf_counter() - begin) * 1000,
                            )
                        if output.shape[0] >= 6:
                            for expectation, suffix in (
                                (False, "corners-local"),
                                (True, "corners-expected"),
                            ):
                                begin = time.perf_counter()
                                decoded, peaks = heatmap_corners(
                                    output[2:6],
                                    logits=True,
                                    expectation=expectation,
                                    frame_shape=small.shape
                                    if config["centredCorners"]
                                    else None,
                                )
                                answers[name + "_" + suffix] = (
                                    decoded,
                                    peaks,
                                    (time.perf_counter() - begin) * 1000,
                                )
                    else:
                        if config["relative"]:
                            decoded, peaks = heatmap_corners(
                                output, minimum_confidence=0.01
                            )
                        elif config["official"]:
                            decoded, peaks = docaligner_official(output, small.shape)
                        else:
                            decoded, peaks = heatmap_corners(output)
                        answers[name] = (
                            decoded,
                            peaks,
                            (time.perf_counter() - start) * 1000,
                        )
                    for variant, (quad, peaks, post_ms) in answers.items():
                        measurements = metrics(quad, truth, image.shape)
                        measurements.update(
                            inferenceMs=inference,
                            postprocessMs=post_ms,
                            quad=None if quad is None else quad.tolist(),
                            cornerPeaks=peaks,
                        )
                        variants.setdefault(variant, {})[mode] = measurements
                for variant, measurements in variants.items():
                    first, last = (
                        measurements[m]["quad"] for m in ("preview720", "still2000")
                    )
                    measurements["previewStillMaxCornerShift"] = (
                        float(np.linalg.norm(np.array(first) - last, axis=1).max())
                        if first is not None and last is not None
                        else None
                    )
                    row["models"][variant] = measurements
            for index, (name, measurements) in enumerate(row["models"].items()):
                last = measurements["still2000"]["quad"]
                color = ((0, 0, 230), (220, 80, 0), (180, 0, 170), (0, 170, 200))[
                    index % 4
                ]
                if last is not None:
                    cv2.polylines(
                        overlay,
                        [(np.array(last) * dims).round().astype("int32")],
                        True,
                        color,
                        2,
                    )
                cv2.putText(
                    overlay,
                    f"{name}: IoU {measurements['still2000']['iou']:.3f}",
                    (8, 52 + 24 * index),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.6,
                    color,
                    2,
                )
            cv2.putText(
                overlay,
                f"{set_name}/{path.name}: green=reference",
                (8, 25),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.6,
                (0, 80, 0),
                2,
            )
            folder = out / set_name
            folder.mkdir(exist_ok=True)
            cv2.imwrite(str(folder / f"{path.stem}-quads.jpg"), overlay)
            report["rows"].append(row)
        grouped = summarize([r for r in report["rows"] if r["set"] == set_name])
        print(
            set_name,
            {
                k: {
                    "iou": round(v["still2000"]["iou"]["mean"], 5),
                    "coverage": round(v["still2000"]["coverage"]["mean"], 5),
                    "failed": v["still2000"]["failed"],
                }
                for k, v in grouped.items()
                if isinstance(v, dict)
            },
            flush=True,
        )
    report["summary"] = {"all": summarize(report["rows"])}
    report["summary"].update(
        {s[0]: summarize([r for r in report["rows"] if r["set"] == s[0]]) for s in SETS}
    )
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report["summary"]["all"], indent=2), flush=True)


if __name__ == "__main__":
    main()
