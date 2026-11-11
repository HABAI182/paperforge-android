"""Joint mask/boundary proposal scoring instead of first-four-vertices wins.

One changed hull pixel can move approxPolyDP to a different epsilon and make
the old fit jump. Evaluate the small set of complete quads together against
soft sheet occupancy, high-confidence coverage and the network's edge map.
No corner labels, source-dependent thresholds or per-photo choices are used.
"""

import cv2
import numpy as np


def _order(points):
    p = np.asarray(points, dtype="float32").reshape(4, 2)
    centre = p.mean(0)
    p = p[np.argsort(np.arctan2(p[:, 1] - centre[1], p[:, 0] - centre[0]))]
    return np.roll(p, -int(np.argmin(p.sum(1))), axis=0)


def _fit(points, proposal):
    lines = []
    for i in range(4):
        a, b = proposal[i], proposal[(i + 1) % 4]
        d = b - a
        length = np.linalg.norm(d)
        if length < 4:
            return proposal
        u = d / length
        relative = points - a
        along = relative @ u
        across = np.abs(relative[:, 0] * u[1] - relative[:, 1] * u[0])
        support = (
            (along > length * 0.1)
            & (along < length * 0.9)
            & (across < max(3, 0.045 * length))
        )
        line = (
            cv2.fitLine(points[support], cv2.DIST_HUBER, 0, 0.01, 0.01).reshape(4)
            if support.sum() >= 8
            else np.r_[u, a]
        )
        lines.append(line.astype("float64"))
    corners = []
    for i in range(4):
        a, b = lines[i - 1], lines[i]
        matrix = np.column_stack([a[:2], -b[:2]])
        if abs(np.linalg.det(matrix)) < 0.05:
            return proposal
        t = np.linalg.solve(matrix, b[2:] - a[2:])[0]
        p = a[2:] + t * a[:2]
        corners.append(p if np.linalg.norm(p - proposal[i]) < 12 else proposal[i])
    return _order(corners)


def fit(mask, boundary):
    h, w = mask.shape
    smooth = cv2.GaussianBlur(mask.astype("float32"), (0, 0), 0.55)
    n, ids, stats, _ = cv2.connectedComponentsWithStats(
        (smooth > 0.15).astype("uint8"), connectivity=4
    )
    if n <= 1:
        return None, {"decision": "no-region"}
    best = 1 + int(stats[1:, cv2.CC_STAT_AREA].argmax())
    if stats[best, cv2.CC_STAT_AREA] < h * w * 0.02:
        return None, {"decision": "small-region"}
    region = (ids == best).astype("uint8")
    contours, _ = cv2.findContours(region, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    contour = max(contours, key=cv2.contourArea)
    hull = cv2.convexHull(contour)
    points = contour.reshape(-1, 2).astype("float32")
    perimeter = cv2.arcLength(hull, True)
    proposals = [cv2.boxPoints(cv2.minAreaRect(hull))]
    for epsilon in [
        0.003,
        0.005,
        0.008,
        0.01,
        0.015,
        0.02,
        0.025,
        0.03,
        0.04,
        0.06,
        0.08,
        0.12,
    ]:
        q = cv2.approxPolyDP(hull, epsilon * perimeter, True)
        if len(q) == 4:
            proposals.append(q.reshape(4, 2))
    unique = []
    for q in proposals:
        q = _fit(points, _order(q))
        q[:, 0] = q[:, 0].clip(0, w - 1)
        q[:, 1] = q[:, 1].clip(0, h - 1)
        if cv2.contourArea(q) < 0.02 * h * w:
            continue
        if any(np.max(np.linalg.norm(q - p, axis=1)) < 0.3 for p in unique):
            continue
        unique.append(q)
    target = np.clip((smooth - 0.05) / 0.45, 0, 1) * cv2.dilate(
        region, np.ones((3, 3), "uint8")
    )
    core = (mask >= 0.8) & (cv2.dilate(region, np.ones((3, 3), "uint8")) > 0)
    records = []
    winner = None
    highest = -1e9
    for q in unique:
        canvas = np.zeros((h, w), "uint8")
        cv2.fillConvexPoly(canvas, q.round().astype("int32"), 1)
        inside = canvas > 0
        intersection = float(target[inside].sum())
        union = float(target.sum() + inside.sum() - intersection)
        coverage = float(inside[core].mean()) if core.any() else 1.0
        support = []
        for i in range(4):
            t = np.linspace(0.1, 0.9, 64)[:, None]
            p = q[i] + (q[(i + 1) % 4] - q[i]) * t
            values = cv2.remap(
                boundary.astype("float32"),
                p[:, 0, None].astype("float32"),
                p[:, 1, None].astype("float32"),
                cv2.INTER_LINEAR,
                borderMode=cv2.BORDER_REPLICATE,
            )
            support.append(float(values.mean()))
        score = (
            intersection / max(1e-6, union)
            - 2 * (1 - coverage)
            + 0.08 * np.mean(support)
        )
        records.append(
            {
                "softIou": intersection / max(1e-6, union),
                "coreCoverage": coverage,
                "edgeSupport": support,
                "score": float(score),
            }
        )
        if score > highest:
            highest = score
            winner = q
    if winner is None:
        return None, {"decision": "no-valid-quad"}
    return (winner + 0.5) / [w, h], {
        "decision": "joint-soft-mask-boundary",
        "proposals": len(unique),
        "scores": records,
    }
