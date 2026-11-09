"""Derive page corners for DocUNet photos by registering each flatbed scan to its camera photo.

This is the crop benchmark's truth: it comes only from the public dataset's own photo/scan pairs and
owes nothing to any detector. A homography cannot follow a strongly curved page, so low-inlier pairs
are dropped and the contact sheet must be checked by eye.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def fit(image: np.ndarray, edge: int) -> tuple[np.ndarray, float]:
    scale = min(1.0, edge / max(image.shape[:2]))
    if scale < 1:
        image = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    return image, scale


def register(scan: np.ndarray, photo: np.ndarray, sift) -> tuple[np.ndarray | None, int, int]:
    ks, ds = sift.detectAndCompute(scan, None)
    kp, dp = sift.detectAndCompute(photo, None)
    if ds is None or dp is None or len(ks) < 8 or len(kp) < 8:
        return None, 0, 0
    pairs = cv2.BFMatcher(cv2.NORM_L2).knnMatch(ds, dp, k=2)
    good = [m for m, n in pairs if m.distance < 0.75 * n.distance]
    if len(good) < 12:
        return None, len(good), 0
    src = np.float32([ks[m.queryIdx].pt for m in good])
    dst = np.float32([kp[m.trainIdx].pt for m in good])
    matrix, mask = cv2.findHomography(src, dst, cv2.USAC_MAGSAC, 4.0, maxIters=20000, confidence=0.9999)
    return matrix, len(good), int(mask.sum()) if mask is not None else 0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--min-inliers", type=int, default=60)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    sift = cv2.SIFT_create(nfeatures=12000)
    rows, tiles = [], []
    for path in sorted((args.corpus / "original").glob("*.jpg"), key=lambda p: [int(x) for x in p.stem.split("_")]):
        scan_path = args.corpus / "scan" / f"{path.stem.split('_')[0]}.png"
        if not scan_path.exists():
            continue
        photo, photo_scale = fit(cv2.imread(str(path)), 1600)
        scan, scan_scale = fit(cv2.imread(str(scan_path)), 1600)
        matrix, matches, inliers = register(cv2.cvtColor(scan, cv2.COLOR_BGR2GRAY), cv2.cvtColor(photo, cv2.COLOR_BGR2GRAY), sift)
        quad = None
        if matrix is not None and inliers >= args.min_inliers:
            h, w = scan.shape[:2]
            corners = cv2.perspectiveTransform(np.float32([[[0, 0], [w, 0], [w, h], [0, h]]]), matrix)[0]
            normalized = corners / [photo.shape[1], photo.shape[0]]
            if normalized.min() > -0.02 and normalized.max() < 1.02 and cv2.isContourConvex(corners.astype(np.int32)):
                quad = normalized.clip(0, 1).round(5).reshape(-1).tolist()
        rows.append({"file": path.name, "matches": matches, "inliers": inliers, "quad": quad})
        print(path.name, matches, inliers, "ok" if quad else "dropped", flush=True)
        tile = cv2.resize(photo, None, fx=320 / max(photo.shape[:2]), fy=320 / max(photo.shape[:2]))
        canvas = np.full((340, 320, 3), 255, np.uint8)
        canvas[20:20 + tile.shape[0], :tile.shape[1]] = tile
        if quad:
            cv2.polylines(canvas, [(np.array(quad).reshape(4, 2) * [tile.shape[1], tile.shape[0]] + [0, 20]).astype(np.int32)], True, (0, 220, 0), 2, cv2.LINE_AA)
        cv2.putText(canvas, f"{path.stem} in={inliers}", (2, 14), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 0), 1, cv2.LINE_AA)
        tiles.append(canvas)
    (args.out / "docunet-truth.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    for start in range(0, len(tiles), 25):
        chunk = tiles[start:start + 25]
        chunk += [np.full_like(tiles[0], 255)] * ((5 - len(chunk) % 5) % 5)
        cv2.imwrite(str(args.out / f"truth-sheet-{start // 25}.jpg"), np.vstack([np.hstack(chunk[i:i + 5]) for i in range(0, len(chunk), 5)]), [cv2.IMWRITE_JPEG_QUALITY, 80])
    print("kept", sum(1 for r in rows if r["quad"]), "of", len(rows))


if __name__ == "__main__":
    main()
