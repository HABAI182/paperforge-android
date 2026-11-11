"""Coarse-to-fine physical-corner study; synthetic training, real-photo eval only.

Keeps PageNet's global sheet selection and learns a small oriented source patch
around each corner. This tests a different strategy from replacing the whole
detector. No app assets are overwritten, and presence uncertainty keeps the
original proposal. Corner patches are not generated or hallucinated content.
"""

from pathlib import Path
import argparse
import hashlib
import json
import time

import cv2
import numpy as np
import torch
from torch import nn
from torch.nn import functional as F
import onnxruntime as ort

from train_page_corners import dataset, ordered
from crop_corner_geometry import basis, patch


class CornerPatch(nn.Module):
    def __init__(self):
        super().__init__()
        self.full = nn.Sequential(
            nn.Conv2d(3, 12, 5, padding=2, stride=2),
            nn.ReLU(),
            nn.Conv2d(12, 16, 3, padding=1),
            nn.ReLU(),
        )
        self.down = nn.Sequential(
            nn.Conv2d(16, 24, 3, stride=2, padding=1),
            nn.ReLU(),
            nn.Conv2d(24, 32, 3, padding=1),
            nn.ReLU(),
            nn.Conv2d(32, 32, 3, padding=1),
            nn.ReLU(),
        )
        self.detail = nn.Sequential(
            nn.Conv2d(48, 16, 3, padding=1), nn.ReLU(), nn.Conv2d(16, 2, 1)
        )
        self.present = nn.Sequential(
            nn.AdaptiveAvgPool2d(1), nn.Flatten(), nn.Linear(32, 1)
        )

    def forward(self, x):
        a = self.full(x)
        b = self.down(a)
        return self.detail(
            torch.cat(
                [
                    a,
                    F.interpolate(
                        b, size=a.shape[2:], mode="bilinear", align_corners=False
                    ),
                ],
                1,
            )
        ), self.present(b)


def samples(data, count, seed):
    rng = np.random.default_rng(seed)
    pictures, masks, corners = data
    result = []
    while len(result) < count:
        i = int(rng.integers(len(corners)))
        q = (ordered(corners[i]) + 0.5 / 512) * 320 - 0.5
        valid = np.where(((q > 5) & (q < 314)).all(1))[0]
        if not len(valid):
            continue
        index = int(rng.choice(valid))
        u, v = basis(q, index)
        radius = float(rng.uniform(22, 46))
        offset = rng.uniform(-0.5, 0.5, 2) * radius
        centre = q[index] + offset[0] * u + offset[1] * v
        present = 1.0
        if rng.random() < 0.22:
            candidate = rng.uniform(0, 319, 2)
            if np.linalg.norm(q - candidate, axis=1).min() < radius * 1.5:
                continue
            centre = candidate
            present = 0.0
        image, _ = patch(np.asarray(pictures[i]), centre, u, v, radius)
        angle = np.column_stack([u, v])
        local = np.linalg.solve(angle, q[index] - centre)
        target = (local / radius + 1) / 2
        mask, _ = patch(
            cv2.resize(
                np.asarray(masks[i]), (320, 320), interpolation=cv2.INTER_NEAREST
            ),
            centre,
            u,
            v,
            radius,
            48,
        )
        result.append(
            (
                image.transpose(2, 0, 1).copy(),
                target.astype("float32"),
                mask[None].astype("float32"),
                present,
            )
        )
    return tuple(np.stack([r[k] for r in result]) for k in range(4))


def native_samples(root, count, seed):
    metadata = json.loads((root / "scenes.json").read_text())
    assert metadata.get("cornersDefinition") == "post-curl-projected-pixel-centres"
    n = metadata["count"] * 4
    rng = np.random.default_rng(seed)
    ids = rng.integers(n, size=count)
    image = np.memmap(
        root / "patch-images.bin", mode="r", dtype="uint8", shape=(n, 3, 96, 96)
    )
    target = np.memmap(
        root / "patch-targets.bin", mode="r", dtype="float32", shape=(n, 3)
    )
    masks = np.memmap(
        root / "patch-masks.bin", mode="r", dtype="uint8", shape=(n, 1, 48, 48)
    )
    return (
        np.array(image[ids]),
        np.array(target[ids, :2]),
        np.array(masks[ids]).astype("float32"),
        np.array(target[ids, 2]),
    )


def objective(output, presence, target, mask, present):
    edge = output.shape[-1]
    axis = (torch.arange(edge, device=output.device) + 0.5) / edge
    yy, xx = torch.meshgrid(axis, axis, indexing="ij")
    dist = (xx[None] - target[:, 0, None, None]) ** 2 + (
        yy[None] - target[:, 1, None, None]
    ) ** 2
    true = torch.softmax((-dist / (2 * (1.3 / edge) ** 2)).flatten(1), -1)
    logp = torch.log_softmax(output[:, 0].float().flatten(1), -1)
    ce = (-(true * logp).sum(1) * present).sum() / present.sum().clamp_min(1)
    probability = logp.exp().reshape(output[:, 0].shape)
    point = torch.stack(
        [(probability * xx).sum((1, 2)), (probability * yy).sum((1, 2))], 1
    )
    error = (((point - target).abs().sum(1)) * present).sum() / present.sum().clamp_min(
        1
    )
    loss = (
        ce
        + error * 8
        + 0.4 * F.binary_cross_entropy_with_logits(output[:, 1:], mask)
        + F.binary_cross_entropy_with_logits(presence[:, 0], present)
    )
    return loss, error


class PatchRefiner:
    def __init__(self, path, decode="heat-peak"):
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(
            str(path), options, providers=["CPUExecutionProvider"]
        )
        self.decode = decode

    @staticmethod
    def mask_corner(logits):
        """Intersect the two supported edges of the canonical bottom-right sheet.

        A broad corner heatmap need not be forced to one arbitrary peak. Reject
        absent paper/exterior support and multiple crossings instead of making
        up an intersection. This is a separate fixed decoding experiment.
        """
        probability = 1 / (1 + np.exp(-np.clip(logits, -50, 50)))
        n = probability.shape[0]
        lines = []
        for transposed in [False, True]:
            plane = probability.T if transposed else probability
            points = []
            for row in range(round(n * 0.76), round(n * 0.95)):
                values = plane[row]
                crosses = np.where((values[:-1] < 0.5) & (values[1:] >= 0.5))[0]
                crosses = [i for i in crosses if n * 0.15 < i < n * 0.85]
                if len(crosses) != 1:
                    continue
                i = crosses[0]
                if (
                    np.mean(values[: max(1, i - 2)]) > 0.2
                    or np.mean(values[min(n - 1, i + 3) :]) < 0.8
                ):
                    continue
                x = i + (0.5 - values[i]) / max(1e-6, values[i + 1] - values[i])
                points.append((row, x))
            if len(points) < 6:
                return None
            data = np.asarray(points)
            beta = np.polyfit(data[:, 0], data[:, 1], 1)
            if np.max(np.abs(np.polyval(beta, data[:, 0]) - data[:, 1])) > 1.2:
                return None
            lines.append(beta)
        a, b = lines[0]
        c, d = lines[1]
        if abs(1 - a * c) < 0.5:
            return None
        x = (a * d + b) / (1 - a * c)
        y = c * x + d
        return float((x + 0.5) / n), float((y + 0.5) / n)

    def refine(self, image, quad):
        h, w = image.shape[:2]
        q = np.asarray(quad).reshape(4, 2) * [w - 1, h - 1]
        radius = 0.11 * min(h, w)
        tiles = []
        axes = []
        for index in range(4):
            u, v = basis(q, index)
            crop, _ = patch(image, q[index], u, v, radius)
            tiles.append(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB).transpose(2, 0, 1))
            axes.append((u, v))
        logits, present = self.session.run(
            None, {"image": np.stack(tiles).astype("float32") / 255}
        )
        proposal = q.copy()
        decisions = []
        for index, (heat, presence) in enumerate(zip(logits, present[:, 0])):
            probability = 1 / (1 + np.exp(-np.clip(presence, -50, 50)))
            plane = heat[0]
            peak = np.unravel_index(plane.argmax(), plane.shape)
            yy, xx = np.indices(plane.shape)
            local = (abs(xx - peak[1]) <= 3) & (abs(yy - peak[0]) <= 3)
            weight = np.exp(plane - plane.max()) * local
            mass = weight.sum()
            x = float((weight * (xx + 0.5)).sum() / mass / plane.shape[1])
            y = float((weight * (yy + 0.5)).sum() / mass / plane.shape[0])
            if self.decode == "mask-intersection":
                point = self.mask_corner(heat[1])
                if point is None:
                    decisions.append(
                        {
                            "corner": index,
                            "accepted": False,
                            "reason": "uncertain-local-mask",
                        }
                    )
                    continue
                x, y = point
            delta = axes[index][0] * ((x * 2 - 1) * radius) + axes[index][1] * (
                (y * 2 - 1) * radius
            )
            spread = float(np.exp(plane - plane.max()).sum())
            accepted = (
                probability >= 0.9
                and 0.15 < x < 0.85
                and 0.15 < y < 0.85
                and (self.decode == "mask-intersection" or spread < 250)
                and np.linalg.norm(delta) < 0.035 * min(h, w)
            )
            if accepted:
                proposal[index] += delta
            decisions.append(
                {
                    "corner": index,
                    "presence": float(probability),
                    "spread": spread,
                    "shiftPixels": float(np.linalg.norm(delta)),
                    "accepted": bool(accepted),
                }
            )
        result = proposal / [w - 1, h - 1]
        if not ((result >= 0) & (result <= 1)).all():
            result = np.asarray(quad).reshape(4, 2)
        return result, {"corners": decisions}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--steps", type=int, default=5000)
    p.add_argument("--samples", type=int, default=12000)
    p.add_argument("--native-bank", action="store_true")
    a = p.parse_args()
    out = a.out.resolve()
    repo = Path(__file__).resolve().parents[1]
    assert out != repo and repo not in out.parents
    out.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(4)
    torch.manual_seed(20261110)
    cv2.setNumThreads(1)
    train_count = json.loads((a.data / "train/scenes.json").read_text())["count"]
    val_count = json.loads((a.data / "validation/scenes.json").read_text())["count"]
    values = (
        native_samples(a.data / "train", a.samples, 713582)
        if a.native_bank
        else samples(dataset(a.data / "train", train_count), a.samples, 713582)
    )
    validation = (
        native_samples(a.data / "validation", 512, 9335701)
        if a.native_bank
        else samples(dataset(a.data / "validation", val_count), 512, 9335701)
    )

    def tensors(rows):
        x, target, mask, present = rows
        return (
            torch.from_numpy(x).cuda().float() / 255,
            torch.from_numpy(target).cuda(),
            torch.from_numpy(mask).cuda(),
            torch.from_numpy(present).cuda().float(),
        )

    train = tensors(values)
    val = tensors(validation)
    rng = np.random.default_rng(20261110)
    model = CornerPatch().cuda()
    opt = torch.optim.AdamW(model.parameters(), lr=0.0005)
    schedule = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.steps, eta_min=0.00003)
    start = time.perf_counter()
    history = []
    best = float("inf")
    for step in range(1, a.steps + 1):
        ids = torch.from_numpy(rng.integers(a.samples, size=32)).cuda()
        x, target, mask, present = [r[ids] for r in train]
        loss, error = objective(*model(x), target, mask, present)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        opt.step()
        schedule.step()
        if step % 250 == 0:
            print(
                json.dumps(
                    {
                        "step": step,
                        "total": a.steps,
                        "loss": float(loss.detach()),
                        "error": float(error.detach()),
                        "seconds": round(time.perf_counter() - start, 1),
                    }
                ),
                flush=True,
            )
        if step % 1000 == 0 or step == a.steps:
            with torch.inference_mode():
                measurements = []
                for first in range(0, 512, 32):
                    x, target, mask, present = [r[first : first + 32] for r in val]
                    loss, error = objective(*model(x), target, mask, present)
                    measurements.append([float(loss), float(error)])
            mean = np.mean(measurements, 0)
            row = {
                "step": step,
                "syntheticValidationLoss": float(mean[0]),
                "syntheticCornerPatchL1": float(mean[1]),
            }
            history.append(row)
            if mean[0] < best:
                best = float(mean[0])
                torch.save(model.cpu().state_dict(), out / "best.pt")
                model.cuda()
    model.cpu().load_state_dict(
        torch.load(out / "best.pt", map_location="cpu", weights_only=True)
    )
    model.eval()
    torch.onnx.export(
        model,
        torch.zeros(4, 3, 96, 96),
        out / "corner-patch.onnx",
        input_names=["image"],
        output_names=["corner_mask_logits", "present"],
        opset_version=17,
        dynamo=False,
        dynamic_axes={
            "image": {0: "corners"},
            "corner_mask_logits": {0: "corners"},
            "present": {0: "corners"},
        },
    )
    report = {
        "scope": "procedural physical corner patches, no owner photo training; real performance not yet measured",
        "input": "oriented RGB96 per corner, 0..1",
        "ownerPhotosUsedForTraining": False,
        "sha256": hashlib.sha256((out / "corner-patch.onnx").read_bytes()).hexdigest(),
        "parameters": sum(v.numel() for v in model.parameters()),
        "samples": a.samples,
        "steps": a.steps,
        "seconds": time.perf_counter() - start,
        "integrated": False,
        "history": history,
    }
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
