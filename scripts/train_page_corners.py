"""Independent PageNet corner-head study on procedural scenes only.

Reuses PageNet and page_scenes; adds four corner heatmaps alongside mask/edge.
Physical corners come from the compositor, never a detected mask or owner photo.
No real-photo gradient training and no modification of Android assets. Selects
checkpoints on newly generated, seed-disjoint synthetic validation only.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import multiprocessing
import random
import time
from pathlib import Path

import cv2
import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

import page_scenes as scenes
from train_page_net import PageNet, loss_fn
from crop_corner_geometry import basis, patch


def ordered(points):
    p = np.asarray(points, np.float32).reshape(4, 2)
    c = p.mean(0)
    p = p[np.argsort(np.arctan2(p[:, 1] - c[1], p[:, 0] - c[0]))]
    return np.roll(p, -int(np.argmin(p.sum(1))), axis=0)


def generate_chunk(job):
    root, first, last, count, seed, coco, scene_mode = job
    cv2.setNumThreads(1)
    scenes.HARD = 1
    rng = random.Random(seed + first * 139)
    photos = []
    paths = sorted(Path(coco).glob("*.jpg")) if coco else []
    for p in rng.sample(paths, min(45, len(paths))):
        im = cv2.imread(str(p))
        if im is not None and min(im.shape[:2]) >= 300:
            photos.append(im)
    # No --real path: even flat owner pages remain evaluation-only.
    pool = [scenes.make_sheet(rng, photos) for _ in range(45)]
    images = np.memmap(
        Path(root) / "images.bin", mode="r+", dtype="uint8", shape=(count, 320, 320, 3)
    )
    masks = np.memmap(
        Path(root) / "masks.bin", mode="r+", dtype="uint8", shape=(count, 160, 160)
    )
    corners = np.memmap(
        Path(root) / "corners.bin", mode="r+", dtype="float32", shape=(count, 4, 2)
    )
    patch_images = np.memmap(
        Path(root) / "patch-images.bin",
        mode="r+",
        dtype="uint8",
        shape=(count, 4, 3, 96, 96),
    )
    patch_targets = np.memmap(
        Path(root) / "patch-targets.bin",
        mode="r+",
        dtype="float32",
        shape=(count, 4, 3),
    )
    patch_masks = np.memmap(
        Path(root) / "patch-masks.bin",
        mode="r+",
        dtype="uint8",
        shape=(count, 4, 1, 48, 48),
    )
    for i in range(first, last):
        # All old chunks began at multiples of 120; first % 3 therefore never
        # varied the mode. Choose it per scene explicitly in the broad replay.
        scenes.HARD = rng.choice([0, 0, 1, 2]) if scene_mode == "mixed" else 1
        image, mask, quad = scenes.scene_sample(rng, photos, pool, with_quad=True)
        images[i] = cv2.cvtColor(
            cv2.resize(image, (320, 320), interpolation=cv2.INTER_AREA),
            cv2.COLOR_BGR2RGB,
        )
        masks[i] = cv2.resize(mask, (160, 160), interpolation=cv2.INTER_NEAREST)
        corners[i] = ordered(quad / scenes.RENDER)
        native = ordered(quad)
        gen = np.random.default_rng(seed + i * 131)
        for index in range(4):
            u, v = basis(native, index)
            radius = float(gen.uniform(40, 75))
            offset = gen.uniform(-0.45, 0.45, 2) * radius
            centre = native[index] + u * offset[0] + v * offset[1]
            present = float(
                ((native[index] > 2) & (native[index] < scenes.RENDER - 3)).all()
            )
            if gen.random() < 0.22:
                for _ in range(30):
                    candidate = gen.uniform(0, scenes.RENDER - 1, 2)
                    if np.linalg.norm(native - candidate, axis=1).min() > radius * 1.4:
                        centre = candidate
                        present = 0
                        break
            crop, _ = patch(image, centre, u, v, radius)
            mask_crop, _ = patch(mask, centre, u, v, radius, 48)
            local = np.linalg.solve(np.column_stack([u, v]), native[index] - centre)
            patch_images[i, index] = cv2.cvtColor(crop, cv2.COLOR_BGR2RGB).transpose(
                2, 0, 1
            )
            patch_targets[i, index] = np.r_[(local / radius + 1) / 2, present]
            patch_masks[i, index, 0] = mask_crop
        if i % 12 == 0:
            pool[rng.randrange(len(pool))] = scenes.make_sheet(rng, photos)
    images.flush()
    masks.flush()
    corners.flush()
    patch_images.flush()
    patch_targets.flush()
    patch_masks.flush()
    return last - first


def generate(root, count, seed, coco, workers, scene_mode="hard"):
    root.mkdir(parents=True, exist_ok=True)
    metadata = root / "scenes.json"
    if metadata.is_file():
        d = json.loads(metadata.read_text())
        assert (
            d["count"] == count
            and d["seed"] == seed
            and d.get("sceneMode", "hard") == scene_mode
        )
        assert d.get("cornersDefinition") == "post-curl-projected-pixel-centres", (
            "Old pre-curl labels cannot be reused for corner training"
        )
        return
    for name, dtype, shape in [
        ("images", "uint8", (count, 320, 320, 3)),
        ("masks", "uint8", (count, 160, 160)),
        ("corners", "float32", (count, 4, 2)),
        ("patch-images", "uint8", (count, 4, 3, 96, 96)),
        ("patch-targets", "float32", (count, 4, 3)),
        ("patch-masks", "uint8", (count, 4, 1, 48, 48)),
    ]:
        data = np.memmap(root / (name + ".bin"), mode="w+", dtype=dtype, shape=shape)
        data.flush()
        del data
    chunk = 120
    jobs = [
        (
            str(root),
            i,
            min(count, i + chunk),
            count,
            seed,
            str(coco) if coco else None,
            scene_mode,
        )
        for i in range(0, count, chunk)
    ]
    start = time.perf_counter()
    with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as pool:
        total = 0
        for n in pool.map(generate_chunk, jobs):
            total += n
            print(
                json.dumps(
                    {
                        "stage": "generate",
                        "completed": total,
                        "total": count,
                        "seconds": round(time.perf_counter() - start, 1),
                    }
                ),
                flush=True,
            )
    metadata.write_text(
        json.dumps(
            {
                "count": count,
                "seed": seed,
                "input": 320,
                "sceneMode": scene_mode,
                "cornersDefinition": "post-curl-projected-pixel-centres",
                "source": "procedural physical corners; COCO backgrounds only",
                "ownerPhotosUsed": False,
                "coco": str(coco),
            }
        ),
        encoding="utf-8",
    )


class PageCorners(PageNet):
    def __init__(self):
        super().__init__()
        self.head = nn.Conv2d(24, 6, 1)


class FrozenPageCorners(PageNet):
    """Adds a trainable spatial head without changing the shipped mask/edge.

    BatchNorm statistics in the complete old trunk remain frozen as well.
    This isolates corner learning from regressions in the existing segmentation.
    """

    def __init__(self):
        super().__init__()
        self.corner = nn.Sequential(
            nn.Conv2d(24, 32, 3, padding=1), nn.ReLU(), nn.Conv2d(32, 4, 1)
        )
        self.requires_grad_(False)
        self.corner.requires_grad_(True)

    def train(self, mode=True):
        super().train(False)
        self.corner.train(mode)
        return self

    def forward(self, x):
        with torch.no_grad():
            features = self.features(x)
            old = self.head(features)
        return torch.cat([old, self.corner(features)], 1)


def batch(data, ids, rng=None):
    image, mask, corners = data
    x = np.array(image[ids])
    m = np.array(mask[ids])
    c = np.array(corners[ids])
    # The bank stores the rendered source's pixel-index / 512. Heatmap axes
    # describe pixel centres within [0,1], so add the half pixel before any
    # 1-x mirror. Otherwise a mirror shifts a physical corner by one source px.
    c += 0.5 / scenes.RENDER
    if rng is not None:
        for i in range(len(ids)):
            if rng.random() < 0.5:
                x[i] = x[i, :, ::-1]
                m[i] = m[i, :, ::-1]
                c[i, :, 0] = 1 - c[i, :, 0]
            if rng.random() < 0.5:
                x[i] = x[i, ::-1]
                m[i] = m[i, ::-1]
                c[i, :, 1] = 1 - c[i, :, 1]
            c[i] = ordered(c[i])
            # Vary exposure but keep the physical edge target unchanged.
            if rng.random() < 0.5:
                x[i] = np.clip(
                    x[i].astype(np.float32) * rng.uniform(0.75, 1.2), 0, 255
                ).astype(np.uint8)
    edge = np.stack(
        [cv2.morphologyEx(a, cv2.MORPH_GRADIENT, np.ones((3, 3), "uint8")) for a in m]
    )
    return (
        torch.from_numpy(x.transpose(0, 3, 1, 2).copy()).cuda().float() / 255,
        torch.from_numpy(m[:, None].copy()).cuda().float(),
        torch.from_numpy(edge[:, None].copy()).cuda().float(),
        torch.from_numpy(c.copy()).cuda().float(),
    )


def corner_loss(logits, corners):
    n = logits.shape[-1]
    axis = (torch.arange(n, device=logits.device, dtype=torch.float32) + 0.5) / n
    yy, xx = torch.meshgrid(axis, axis, indexing="ij")
    d2 = (xx[None, None] - corners[:, :, 0, None, None]).square() + (
        yy[None, None] - corners[:, :, 1, None, None]
    ).square()
    target = torch.softmax((-d2 / (2 * (2 / n) ** 2)).flatten(2), -1)
    logp = torch.log_softmax(logits.float().flatten(2), -1)
    visible = ((corners >= 0) & (corners <= 1)).all(-1)
    kl = -(target * logp).sum(-1)
    p = logp.exp().reshape(logits.shape)
    coordinate = torch.stack([(p * xx).sum((2, 3)), (p * yy).sum((2, 3))], -1)
    error = (coordinate - corners).abs().sum(-1)
    return ((kl + error * 5) * visible).sum() / visible.sum().clamp_min(1), error[
        visible
    ].mean()


def dataset(root, count):
    return (
        np.memmap(
            root / "images.bin", mode="r", dtype="uint8", shape=(count, 320, 320, 3)
        ),
        np.memmap(root / "masks.bin", mode="r", dtype="uint8", shape=(count, 160, 160)),
        np.memmap(root / "corners.bin", mode="r", dtype="float32", shape=(count, 4, 2)),
    )


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--warm", type=Path, required=True)
    p.add_argument("--coco", type=Path)
    p.add_argument("--count", type=int, default=12000)
    p.add_argument("--validation-count", type=int, default=480)
    p.add_argument("--steps", type=int, default=6000)
    p.add_argument("--batch", type=int, default=16)
    p.add_argument("--workers", type=int, default=6)
    p.add_argument(
        "--data",
        type=Path,
        help="Reuse this experiment's generated train/validation data",
    )
    p.add_argument("--consistency-weight", type=float, default=0)
    p.add_argument("--frozen-trunk", action="store_true")
    p.add_argument("--scene-mode", choices=["hard", "mixed"], default="hard")
    p.add_argument("--teacher", type=Path)
    p.add_argument("--anchor-weight", type=float, default=2)
    a = p.parse_args()
    root = a.out.resolve()
    repo = Path(__file__).resolve().parents[1]
    assert root != repo and repo not in root.parents
    root.mkdir(parents=True, exist_ok=True)
    (root / "invocation.json").write_text(
        json.dumps(
            {k: str(v) if isinstance(v, Path) else v for k, v in vars(a).items()},
            indent=2,
        ),
        encoding="utf-8",
    )
    for path in [
        Path(__file__),
        Path(__file__).with_name("page_scenes.py"),
        Path(__file__).with_name("train_page_net.py"),
    ]:
        (root / ("used-" + path.name)).write_bytes(path.read_bytes())
    torch.manual_seed(20261110)
    torch.set_num_threads(4)
    cv2.setNumThreads(1)
    assert torch.cuda.is_available(), "GPU training required for this experiment"
    data_root = a.data.resolve() if a.data else root
    generate(data_root / "train", a.count, 20261110, a.coco, a.workers, a.scene_mode)
    generate(
        data_root / "validation",
        a.validation_count,
        7231331,
        a.coco,
        a.workers,
        a.scene_mode,
    )
    train = dataset(data_root / "train", a.count)
    val = dataset(data_root / "validation", a.validation_count)
    model = (FrozenPageCorners() if a.frozen_trunk else PageCorners()).cuda()
    state = torch.load(a.warm, map_location="cuda", weights_only=True)
    if a.frozen_trunk:
        missing, unexpected = model.load_state_dict(state, strict=False)
        assert all(k.startswith("corner.") for k in missing) and not unexpected
    else:
        old_weight = state.pop("head.weight")
        old_bias = state.pop("head.bias")
        model.load_state_dict(state, strict=False)
        with torch.no_grad():
            count = old_weight.shape[0]
            model.head.weight[:count].copy_(old_weight)
            model.head.bias[:count].copy_(old_bias)
            if count < 6:
                nn.init.normal_(model.head.weight[2:], 0, 0.01)
                model.head.bias[2:].zero_()
    opt = torch.optim.AdamW(
        [v for v in model.parameters() if v.requires_grad],
        lr=0.0005 if a.frozen_trunk else 0.0002,
        weight_decay=0.0001,
    )
    schedule = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.steps, eta_min=0.00001)
    scaler = torch.amp.GradScaler("cuda")
    teacher = None
    if a.teacher:
        teacher = PageNet().cuda().eval()
        teacher.load_state_dict(
            torch.load(a.teacher, map_location="cuda", weights_only=True)
        )
        teacher.requires_grad_(False)
    rng = np.random.default_rng(843199)
    best = float("inf")
    history = []
    start = time.perf_counter()
    for step in range(1, a.steps + 1):
        model.train()
        x, m, e, c = batch(train, rng.integers(a.count, size=a.batch), rng)
        with torch.amp.autocast("cuda"):
            y = model(x)
            cl, ce = corner_loss(y[:, 2:], c)
            loss = loss_fn(y[:, :2], m, e) + 0.15 * cl
            if teacher is not None:
                with torch.no_grad():
                    prior = teacher(x).sigmoid()
                loss = loss + a.anchor_weight * F.mse_loss(y[:, :2].sigmoid(), prior)
            # The same sheet viewed at a nearby analysis resolution must not
            # switch from the page boundary to a printed panel. This is a
            # training-only paired perturbation, with identical geometry/labels.
            if a.consistency_weight > 0 and step % 2 == 0:
                noisy = (x + torch.empty_like(x).uniform_(-1 / 255, 1 / 255)).clamp(
                    0, 1
                )
                other = model(noisy)
                consistency = F.mse_loss(
                    other[:, :2].sigmoid(), y[:, :2].detach().sigmoid()
                )
                cl2, _ = corner_loss(other[:, 2:], c)
                loss = loss + a.consistency_weight * (
                    0.1 * loss_fn(other[:, :2], m, e) + 0.05 * cl2 + consistency * 2
                )
        opt.zero_grad(set_to_none=True)
        scaler.scale(loss).backward()
        scaler.step(opt)
        scaler.update()
        schedule.step()
        if step % 100 == 0:
            print(
                json.dumps(
                    {
                        "stage": "train",
                        "step": step,
                        "total": a.steps,
                        "loss": float(loss.detach()),
                        "cornerError": float(ce.detach()),
                        "seconds": round(time.perf_counter() - start, 1),
                    }
                ),
                flush=True,
            )
        if step % 1000 == 0 or step == a.steps:
            model.eval()
            metrics = []
            with torch.inference_mode():
                for first in range(0, a.validation_count, a.batch):
                    x, m, e, c = batch(
                        val, np.arange(first, min(a.validation_count, first + a.batch))
                    )
                    y = model(x)
                    cl, ce = corner_loss(y[:, 2:], c)
                    metrics.append(
                        [float(loss_fn(y[:, :2], m, e) + 0.15 * cl), float(ce)]
                    )
            mean = np.mean(metrics, 0)
            row = {
                "step": step,
                "syntheticValidationLoss": float(mean[0]),
                "syntheticCornerL1": float(mean[1]),
            }
            history.append(row)
            print(json.dumps(row), flush=True)
            if mean[0] < best:
                best = float(mean[0])
                torch.save(model.cpu().state_dict(), root / "best.pt")
                model.cuda()
            (root / "history.json").write_text(
                json.dumps(history, indent=2), encoding="utf-8"
            )
    model.cpu().load_state_dict(
        torch.load(root / "best.pt", map_location="cpu", weights_only=True)
    )
    model.eval()
    torch.onnx.export(
        model,
        torch.zeros(1, 3, 320, 320),
        root / "page-corners.onnx",
        input_names=["image"],
        output_names=["logits"],
        opset_version=17,
        dynamo=False,
    )
    report = {
        "architecture": "PageNet + four physical corner heatmaps",
        "cornerCoordinates": "post-curl (pixel-index + 0.5) / 512 before mirrors",
        "frozenTrunk": a.frozen_trunk,
        "outputChannels": ["sheet mask", "boundary", "TL", "TR", "BR", "BL"],
        "consistencyWeight": a.consistency_weight,
        "ownerPhotosUsedForTraining": False,
        "checkpointSelection": "seed-disjoint synthetic validation loss only",
        "warmSha256": hashlib.sha256(a.warm.read_bytes()).hexdigest(),
        "sha256": hashlib.sha256((root / "page-corners.onnx").read_bytes()).hexdigest(),
        "parameters": sum(v.numel() for v in model.parameters()),
        "steps": a.steps,
        "seconds": time.perf_counter() - start,
        "integrated": False,
        "history": history,
    }
    (root / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
    multiprocessing.freeze_support()
    main()
