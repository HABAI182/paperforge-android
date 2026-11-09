"""Scores PageNet's crop on real photos, for coverage as well as IoU.

Coverage is the share of the true page inside the quad. IoU alone hides under-cropping, and a crop that
loses part of the page is the worse failure, so settings are judged on both. `recover` mirrors
`PageNetDetector.recover`: the mask seeds a GrabCut on the photo, so blank paper the mask dropped comes back.

Example:
  python scripts/eval_page_crop.py --checkpoint best.pt --corpus bench-private/docunet \
      --truth work/page-net/truth/docunet-truth.json --work work
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).parent))
import train_page_net as t  # noqa: E402

WORK = 320
OUTPUT = 160


def order(quad):
    p = np.array(quad, dtype=float).reshape(4, 2)
    c = p.mean(0)
    return p[np.argsort(np.arctan2(p[:, 1] - c[1], p[:, 0] - c[0]))]


def fill(quad, size=1000):
    m = np.zeros((size, size), np.uint8)
    cv2.fillPoly(m, [(order(quad) * size).astype(np.int32)], 1)
    return m


def scores(quad, truth):
    if quad is None:
        return 0.0, 0.0
    a, b = fill(quad), fill(truth)
    inter = float((a & b).sum())
    return inter / max(1, (a | b).sum()), inter / max(1, b.sum())


def recover(image, mask, cut, reach):
    small = cv2.resize(image, (WORK, WORK), interpolation=cv2.INTER_AREA)
    prob = cv2.resize(mask, (WORK, WORK), interpolation=cv2.INTER_LINEAR)
    seed = (prob > cut).astype(np.uint8)
    size = max(3, int(reach * WORK)) | 1
    near = cv2.dilate(seed, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (size, size)))
    core = cv2.erode((prob > .5).astype(np.uint8), np.ones((9, 9), np.uint8))
    labels = np.full((WORK, WORK), cv2.GC_BGD, np.uint8)
    labels[near > 0] = cv2.GC_PR_BGD
    labels[seed > 0] = cv2.GC_PR_FGD
    labels[core > 0] = cv2.GC_FGD
    if (labels == cv2.GC_FGD).sum() < 50 or (labels == cv2.GC_BGD).sum() < 50:
        return None
    cv2.setRNGSeed(0)  # as in the app: GrabCut starts from a random clustering
    cv2.grabCut(small, labels, None, np.zeros((1, 65)), np.zeros((1, 65)), 3, cv2.GC_INIT_WITH_MASK)
    sheet = ((labels == cv2.GC_FGD) | (labels == cv2.GC_PR_FGD)).astype(np.uint8)
    sheet = cv2.morphologyEx(sheet, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
    return cv2.resize(sheet, (OUTPUT, OUTPUT), interpolation=cv2.INTER_AREA).astype(np.float32)


def crop(image, mask, cut, reach, keep):
    base = t.quad_from_mask((mask > cut).astype(np.float32))
    sheet = recover(image, mask, cut, reach)
    found = t.quad_from_mask(sheet) if sheet is not None else None
    # A much larger answer is the sheet underneath or the desk, not a recovered margin.
    if base is not None and found is not None and fill(found).sum() <= keep * fill(base).sum():
        return found
    return base


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--truth", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--cut", type=float, default=.15)
    parser.add_argument("--reach", type=float, default=.15)
    parser.add_argument("--keep", type=float, default=1.6)
    args = parser.parse_args()
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model = t.PageNet().to(device)
    model.load_state_dict(torch.load(args.checkpoint, map_location=device, weights_only=True))
    model.eval()
    for split, rows in t.load_real(args.corpus, args.truth, args.work).items():
        mask_only, recovered = [], []
        for path, truth in rows:
            image = cv2.imread(str(path))
            x = cv2.cvtColor(cv2.resize(image, (320, 320), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB)
            x = torch.from_numpy(x.transpose(2, 0, 1)).float()[None].to(device) / 255
            with torch.no_grad():
                mask = torch.sigmoid(model(x))[0, 0].cpu().numpy()
            mask_only.append(scores(t.quad_from_mask((mask > args.cut).astype(np.float32)), truth))
            recovered.append(scores(crop(image, mask, args.cut, args.reach, args.keep), truth))
        for label, values in (("mask", mask_only), ("recovered", recovered)):
            v = np.array(values)
            print(f"{split:6s} {label:10s} IoU {v[:, 0].mean():.3f} coverage {v[:, 1].mean():.3f} under .9: {(v[:, 1] < .9).sum()}/{len(v)}")


if __name__ == "__main__":
    main()
