"""Bounded, source-pixel line refinement for offline document proposals.

Uses broad material changes as well as normal gradients: a printed line inside
the page is not automatically a paper edge. A robust fit across distributed
samples rejects fingers and short objects. No labels enter this algorithm.
OpenCV supplies Sobel/remap/fitLine; the scoring and acceptance are original.
"""

from __future__ import annotations

import cv2
import numpy as np


def _sample(image, points):
    return cv2.remap(
        image,
        points[..., 0].astype("float32"),
        points[..., 1].astype("float32"),
        cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_REPLICATE,
    )


def refine(image, quad, band=0.025, edge=960):
    if quad is None:
        return None, {"decision": "no-proposal"}
    h, w = image.shape[:2]
    scale = min(1, edge / max(h, w))
    small = cv2.resize(
        image, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA
    )
    sh, sw = small.shape[:2]
    rgb = cv2.GaussianBlur(small.astype("float32") / 255, (0, 0), 0.8)
    gx = cv2.Sobel(rgb, cv2.CV_32F, 1, 0, ksize=3) / 8
    gy = cv2.Sobel(rgb, cv2.CV_32F, 0, 1, ksize=3) / 8
    q = np.asarray(quad, dtype="float64").reshape(4, 2) * [sw - 1, sh - 1]
    centre = q.mean(0)
    lines = []
    details = []
    reach = max(3, band * min(sw, sh))
    offsets = np.linspace(-reach, reach, int(reach * 4) + 1)
    for i in range(4):
        a, b = q[i], q[(i + 1) % 4]
        direction = (b - a) / max(1, np.linalg.norm(b - a))
        normal = np.array([-direction[1], direction[0]])
        mid = (a + b) / 2
        if np.dot(normal, centre - mid) > 0:
            normal = -normal
        t = np.linspace(0.08, 0.92, 96)
        baseline = a[None] + (b - a)[None] * t[:, None]
        points = baseline[:, None] + offsets[None, :, None] * normal
        gradient = np.max(
            np.abs(_sample(gx, points) * normal[0] + _sample(gy, points) * normal[1]),
            axis=-1,
        )
        inside = np.mean([_sample(rgb, points - d * normal) for d in [2, 4, 7]], axis=0)
        outside = np.mean(
            [_sample(rgb, points + d * normal) for d in [2, 4, 7]], axis=0
        )
        material = np.sqrt(np.mean((inside - outside) ** 2, axis=-1))
        evidence = np.minimum(gradient, 0.2) * np.clip(material / 0.035, 0, 1)
        score = evidence - 0.025 * (np.abs(offsets)[None] / reach) ** 2
        indices = score.argmax(1)
        strength = evidence[np.arange(len(t)), indices]
        selected = points[np.arange(len(t)), indices]
        good = strength > 0.012
        bins = np.array_split(good, 6)
        distributed = sum(v.mean() > 0.35 for v in bins) >= 5
        accepted = good.mean() >= 0.55 and distributed
        if accepted:
            fit = (
                cv2.fitLine(
                    selected[good].astype("float32"), cv2.DIST_HUBER, 0, 0.01, 0.01
                )
                .reshape(4)
                .astype("float64")
            )
            relative = selected[good] - fit[2:]
            residual = np.abs(relative[:, 0] * fit[1] - relative[:, 1] * fit[0])
            accepted = np.quantile(residual, 0.85) < max(1.5, reach * 0.25)
        if not accepted:
            fit = np.r_[direction, a]
        lines.append(fit)
        details.append(
            {
                "side": i,
                "accepted": bool(accepted),
                "support": float(good.mean()),
                "distributed": bool(distributed),
                "medianOffsetPx": float(np.median(offsets[indices][good]))
                if good.any()
                else None,
            }
        )
    result = []
    for i in range(4):
        first, second = lines[i - 1], lines[i]
        matrix = np.column_stack([first[:2], -second[:2]])
        if abs(np.linalg.det(matrix)) < 0.05:
            return np.asarray(quad).reshape(4, 2), {
                "decision": "degenerate-fit",
                "sides": details,
            }
        distance = np.linalg.solve(matrix, second[2:] - first[2:])[0]
        result.append(first[2:] + distance * first[:2])
    result = np.asarray(result) / [sw - 1, sh - 1]
    old = np.asarray(quad).reshape(4, 2)
    growth = cv2.contourArea(result.astype("float32")) / max(
        1e-9, cv2.contourArea(old.astype("float32"))
    )
    shift = np.max(np.linalg.norm(result - old, axis=1))
    valid = (
        np.isfinite(result).all()
        and ((result >= 0) & (result <= 1)).all()
        and 0.94 <= growth <= 1.08
        and shift <= 0.035
    )
    return (result if valid else old), {
        "decision": "source-lines" if valid else "proposal-kept",
        "areaRatio": float(growth),
        "maxCornerShift": float(shift),
        "sides": details,
    }
