"""Train PaperForge PageNet: finds the top sheet of paper in a photo.

Everything here is original: the network is a small U-Net written below and trained from random
initialisation; no third-party weights are loaded. Training scenes come from page_scenes.py.

Real photos are used only to choose a checkpoint (val) and report results (test), never to train.
"""

from __future__ import annotations

import argparse
import json
import queue
import threading
import time
from pathlib import Path

import cv2
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from page_scenes import INPUT

# Quads a homography visibly cannot follow (folds); excluded by eye from the truth sheets.
TRUTH_EXCLUDED = {"1_2", "9_2", "10_1", "12_1", "14_1", "17_2", "21_1", "25_1"}
# Project photos, corners read by eye (image pixels at 1279x1706). Normalised on load.
OWNER_TRUTH = {
    "repro-edge/original.jpg": [(161, 162), (1158, 212), (1201, 1525), (82, 1506)],
    "workbook-counterexample-20261103/initial-files/workbook-counterexample/original.jpg": [(262, 378), (1022, 352), (1228, 1465), (52, 1440)],
    "feedback-book-20261103/book.jpg": [(118, 100), (1098, 92), (1225, 1590), (95, 1622)],
}


# ---------------------------------------------------------------- network


class Block(nn.Module):
    """Depthwise-separable residual block."""

    def __init__(self, cin: int, cout: int, stride: int = 1):
        super().__init__()
        self.net = nn.Sequential(
            nn.Conv2d(cin, cin, 3, stride, 1, groups=cin, bias=False),
            nn.BatchNorm2d(cin),
            nn.ReLU6(inplace=True),
            nn.Conv2d(cin, cout, 1, bias=False),
            nn.BatchNorm2d(cout),
            nn.ReLU6(inplace=True),
            nn.Conv2d(cout, cout, 3, 1, 1, groups=cout, bias=False),
            nn.BatchNorm2d(cout),
        )
        self.skip = stride == 1 and cin == cout
        self.act = nn.ReLU6(inplace=True)

    def forward(self, x):
        y = self.net(x)
        return self.act(x + y if self.skip else y)


class PageNet(nn.Module):
    """320x320 RGB in [0,1] -> 160x160 logits: channel 0 top-sheet mask, channel 1 sheet boundary."""

    def __init__(self, c=(24, 40, 72, 112, 160)):
        super().__init__()
        self.stem = nn.Sequential(nn.Conv2d(3, c[0], 3, 2, 1, bias=False), nn.BatchNorm2d(c[0]), nn.ReLU6(inplace=True), Block(c[0], c[0]))
        self.down = nn.ModuleList(nn.Sequential(Block(c[i], c[i + 1], 2), Block(c[i + 1], c[i + 1]), Block(c[i + 1], c[i + 1])) for i in range(4))
        # Dilated context: which sheet is on top is decided by what surrounds it, not local texture.
        self.context = nn.Sequential(
            nn.Conv2d(c[4], c[4], 3, padding=2, dilation=2, groups=c[4], bias=False),
            nn.Conv2d(c[4], c[4], 1, bias=False),
            nn.BatchNorm2d(c[4]),
            nn.ReLU6(inplace=True),
            nn.Conv2d(c[4], c[4], 3, padding=4, dilation=4, groups=c[4], bias=False),
            nn.Conv2d(c[4], c[4], 1, bias=False),
            nn.BatchNorm2d(c[4]),
            nn.ReLU6(inplace=True),
        )
        self.up = nn.ModuleList(Block(c[i + 1] + c[i], c[i]) for i in range(4))
        self.head = nn.Conv2d(c[0], 2, 1)

    def forward(self, x):
        features = [self.stem(x)]
        for layer in self.down:
            features.append(layer(features[-1]))
        y = self.context(features[-1])
        for i in range(3, -1, -1):
            y = F.interpolate(y, size=features[i].shape[2:], mode="bilinear", align_corners=False)
            y = self.up[i](torch.cat([y, features[i]], 1))
        return self.head(y)


def loss_fn(out, mask, edge):
    m = out[:, :1]
    probs = torch.sigmoid(m)
    dice = 1 - (2 * (probs * mask).sum((1, 2, 3)) + 1) / (probs.sum((1, 2, 3)) + mask.sum((1, 2, 3)) + 1)
    return F.binary_cross_entropy_with_logits(m, mask) + dice.mean() + 0.6 * F.binary_cross_entropy_with_logits(out[:, 1:], edge, pos_weight=torch.tensor(6.0, device=out.device))


# ---------------------------------------------------------------- quad fitting (mirrored in Kotlin)


def _intersect(a, b):
    (vx1, vy1, x1, y1), (vx2, vy2, x2, y2) = a, b
    det = vx1 * vy2 - vy1 * vx2
    if abs(det) < 1e-6:
        return None
    t = ((x2 - x1) * vy2 - (y2 - y1) * vx2) / det
    return x1 + vx1 * t, y1 + vy1 * t


def quad_from_mask(prob: np.ndarray):
    """Largest confident region -> four corners -> a straight line refit along each side."""
    binary = (prob > 0.5).astype(np.uint8)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(binary, connectivity=4)
    if count < 2:
        return None
    best = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    if stats[best, cv2.CC_STAT_AREA] < 0.02 * prob.size:
        return None
    region = (labels == best).astype(np.uint8)
    contours, _ = cv2.findContours(region, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    contour = max(contours, key=cv2.contourArea)
    hull = cv2.convexHull(contour)
    perimeter = cv2.arcLength(hull, True)
    corners = None
    for eps in (0.01, 0.015, 0.02, 0.03, 0.04, 0.06, 0.08, 0.12):
        approx = cv2.approxPolyDP(hull, eps * perimeter, True)
        if len(approx) == 4:
            corners = approx.reshape(4, 2).astype(np.float32)
            break
    if corners is None:
        corners = cv2.boxPoints(cv2.minAreaRect(hull)).astype(np.float32)
    points = contour.reshape(-1, 2).astype(np.float32)
    lines = []
    for i in range(4):
        a, b = corners[i], corners[(i + 1) % 4]
        d = b - a
        length = float(np.linalg.norm(d))
        if length < 4:
            return _normalise(corners, prob.shape)
        u = d / length
        rel = points - a
        along = rel @ u
        across = np.abs(rel[:, 0] * u[1] - rel[:, 1] * u[0])
        keep = (along > 0.1 * length) & (along < 0.9 * length) & (across < max(3.0, 0.04 * length))
        if keep.sum() < 8:
            lines.append((float(u[0]), float(u[1]), float(a[0]), float(a[1])))
            continue
        vx, vy, x0, y0 = cv2.fitLine(points[keep], cv2.DIST_HUBER, 0, 0.01, 0.01).reshape(4)
        lines.append((float(vx), float(vy), float(x0), float(y0)))
    fitted = []
    for i in range(4):
        p = _intersect(lines[i - 1], lines[i])
        if p is None or np.hypot(p[0] - corners[i][0], p[1] - corners[i][1]) > 0.12 * prob.shape[0]:
            p = tuple(corners[i])
        fitted.append(p)
    return _normalise(np.float32(fitted), prob.shape)


def _normalise(points, shape):
    h, w = shape
    p = points.astype(np.float64) + 0.5
    p[:, 0] /= w
    p[:, 1] /= h
    return p.clip(0, 1).reshape(-1).tolist()


def iou(a, b, size=1000):
    if a is None or b is None:
        return 0.0
    ma, mb = np.zeros((size, size), np.uint8), np.zeros((size, size), np.uint8)
    for mask, quad in ((ma, a), (mb, b)):
        pts = np.array(quad).reshape(4, 2)
        centre = pts.mean(0)
        pts = pts[np.argsort(np.arctan2(pts[:, 1] - centre[1], pts[:, 0] - centre[0]))]
        cv2.fillPoly(mask, [(pts * size).astype(np.int32)], 1)
    return float((ma & mb).sum() / max(1, (ma | mb).sum()))


# ---------------------------------------------------------------- real-photo evaluation


def load_real(corpus: Path, truth: Path, work: Path):
    rows = {"val": [], "test": [], "owner": []}
    for row in json.loads(truth.read_text(encoding="utf-8")):
        stem = Path(row["file"]).stem
        if row["quad"] and stem not in TRUTH_EXCLUDED:
            rows["val" if int(stem.split("_")[0]) <= 12 else "test"].append((corpus / "original" / row["file"], row["quad"]))
    for name, corners in OWNER_TRUTH.items():
        rows["owner"].append((work / name, (np.float64(corners) / [1279, 1706]).reshape(-1).tolist()))
    return rows


def predict(model, path, device):
    image = cv2.imread(str(path))
    tensor = torch.from_numpy(cv2.cvtColor(cv2.resize(image, (INPUT, INPUT), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB).transpose(2, 0, 1)).float()[None].to(device) / 255
    return quad_from_mask(torch.sigmoid(model(tensor))[0, 0].cpu().numpy())


@torch.no_grad()
def evaluate(model, real, device):
    model.eval()
    report = {}
    for split, rows in real.items():
        values = [iou(predict(model, path, device), quad) for path, quad in rows]
        report[split] = {"mean": float(np.mean(values)), "good": int(np.sum(np.array(values) >= 0.9)), "bad": int(np.sum(np.array(values) < 0.5)), "n": len(values), "values": [round(v, 3) for v in values]}
    model.train()
    return report


def batches(roots: list[Path], batch: int, seed: int):
    """Random batches from generated scene sets, prepared on one background thread. Sets are drawn
    evenly, so a newer, smaller set is not outweighed by an older, larger one."""
    sets = []
    for root in roots:
        meta = json.loads((root / "scenes.json").read_text(encoding="utf-8"))
        sets.append(
            (
                meta["count"],
                np.memmap(root / "images.bin", dtype=np.uint8, mode="r", shape=(meta["count"], INPUT, INPUT, 3)),
                np.memmap(root / "masks.bin", dtype=np.uint8, mode="r", shape=(meta["count"], INPUT // 2, INPUT // 2)),
            )
        )
    ready: queue.Queue = queue.Queue(maxsize=8)
    kernel = np.ones((3, 3), np.uint8)

    def fill():
        gen = np.random.default_rng(seed)
        while True:
            image = np.empty((batch, 3, INPUT, INPUT), np.uint8)
            half = np.empty((batch, 1, INPUT // 2, INPUT // 2), np.uint8)
            edge = np.empty((batch, 1, INPUT // 2, INPUT // 2), np.uint8)
            for k in range(batch):
                count, images, masks = sets[gen.integers(len(sets))]
                i = gen.integers(count)
                picture, mask = images[i], masks[i]
                # Flips multiply the scenes without changing what a sheet looks like.
                if gen.random() < 0.5:
                    picture, mask = picture[:, ::-1], mask[:, ::-1]
                if gen.random() < 0.5:
                    picture, mask = picture[::-1], mask[::-1]
                image[k] = picture.transpose(2, 0, 1)
                half[k, 0] = mask
                edge[k, 0] = cv2.morphologyEx(np.array(mask), cv2.MORPH_GRADIENT, kernel)
            ready.put((torch.from_numpy(image), torch.from_numpy(half), torch.from_numpy(edge)))

    threading.Thread(target=fill, daemon=True).start()
    while True:
        yield ready.get()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenes", type=Path, nargs="+", required=True)
    parser.add_argument("--resume", type=Path)
    parser.add_argument("--lr", type=float, default=2e-3)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--truth", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--iters", type=int, default=1000)
    parser.add_argument("--batch", type=int, default=32)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(7)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model = PageNet().to(device)
    if args.resume:
        model.load_state_dict(torch.load(args.resume, map_location=device, weights_only=True))
    print("parameters", sum(p.numel() for p in model.parameters()), "device", device, flush=True)
    stream = batches(args.scenes, args.batch, 11)
    optimiser = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    schedule = torch.optim.lr_scheduler.OneCycleLR(optimiser, args.lr, total_steps=args.epochs * args.iters, pct_start=0.08)
    scaler = torch.amp.GradScaler(device, enabled=device == "cuda")
    real = load_real(args.corpus, args.truth, args.work)
    print({k: len(v) for k, v in real.items()}, flush=True)
    best, history = -1.0, []
    started = time.time()
    for epoch in range(1, args.epochs + 1):
        total = 0.0
        for _ in range(args.iters):
            image, mask, edge = next(stream)
            image = image.to(device, non_blocking=True).float() / 255
            mask = mask.to(device, non_blocking=True).float()
            edge = edge.to(device, non_blocking=True).float()
            optimiser.zero_grad(set_to_none=True)
            with torch.amp.autocast(device, enabled=device == "cuda"):
                loss = loss_fn(model(image), mask, edge)
            scaler.scale(loss).backward()
            scaler.step(optimiser)
            scaler.update()
            schedule.step()
            total += loss.item()
        report = evaluate(model, real, device)
        row = {"epoch": epoch, "loss": total / args.iters, "minutes": (time.time() - started) / 60, **{k: {x: report[k][x] for x in ("mean", "good", "bad", "n")} for k in report}, "owner": report["owner"]["values"]}
        history.append(row)
        print(json.dumps(row), flush=True)
        (args.out / "history.json").write_text(json.dumps(history, indent=1), encoding="utf-8")
        # Checkpoints are chosen on val and owner photos only; test stays untouched for the report.
        score = report["val"]["mean"] + 0.5 * report["owner"]["mean"]
        if score > best:
            best = score
            torch.save(model.state_dict(), args.out / "best.pt")
    print("DONE best", best, flush=True)


if __name__ == "__main__":
    main()
