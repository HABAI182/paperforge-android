"""Fine-tune the existing two-channel PageNet on audited, licensed public photos.

No owner photographs enter training or checkpoint selection. A manifest must
provide upright-image, pixel-centre-normalized TL/TR/BR/BL corners and source
IDs. Source-number blocks, optional explicit groups and close content hashes stay together
in a fixed public validation split. Procedural replay and a frozen teacher
limit forgetting; BatchNorm running statistics stay at the published values.
Outputs are private research candidates, never application assets.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import time
from pathlib import Path

import cv2
import numpy as np
import torch
from PIL import Image, ImageOps
from torch import nn
from torch.nn import functional as F

from train_page_net import PageNet, loss_fn, quad_from_mask


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare(manifest, output):
    rows = json.loads(manifest.read_text(encoding="utf-8"))
    if isinstance(rows, dict):
        rows = rows["records"]
    # Manifest construction/annotation audit is separate from model inference.
    # No predicted masks are substituted for human-provided corner labels.
    images, masks, quads, records, hashes = [], [], [], [], []
    seen = set()
    for row in rows:
        path = Path(row["image"])
        sha = digest(path)
        if sha in seen:
            continue
        q = np.asarray(row["quad"], np.float32).reshape(4, 2)
        if not np.isfinite(q).all() or q.min() < 0 or q.max() > 1:
            raise ValueError(f"Unaudited corner coordinates: {row['id']}")
        if not cv2.isContourConvex(q) or cv2.contourArea(q) < 0.02:
            raise ValueError(f"Invalid document polygon: {row['id']}")
        with Image.open(path) as opened:
            rgb = np.asarray(ImageOps.exif_transpose(opened).convert("RGB"))
        height, width = rgb.shape[:2]
        if (width, height) != (row["width"], row["height"]):
            raise ValueError(f"Upright annotation dimensions do not match: {row['id']}")
        images.append(cv2.resize(rgb, (320, 320), interpolation=cv2.INTER_AREA))
        # Manifest corners are upright pixel centres divided by W-1/H-1. Resize
        # uses half-pixel centres; the existing decoder returns edge-normalized
        # coordinates, (maskPixel+.5)/160, so training and held-out truth use that
        # same convention rather than shrinking the frame with q*159.
        source = q * [width - 1, height - 1]
        edge_quad = (source + 0.5) / [width, height]
        mask = np.zeros((160, 160), np.uint8)
        cv2.fillConvexPoly(mask, np.round(edge_quad * 160 - 0.5).astype(np.int32), 1)
        masks.append(mask)
        quads.append(edge_quad.astype(np.float32))
        target = np.float32([[0, 0], [255, 0], [255, 255], [0, 255]])
        paper = cv2.warpPerspective(
            rgb,
            cv2.getPerspectiveTransform(source.astype(np.float32), target),
            (256, 256),
        )
        gray = cv2.cvtColor(paper, cv2.COLOR_RGB2GRAY)
        small = cv2.resize(gray, (17, 16), interpolation=cv2.INTER_AREA)
        hashes.append(np.packbits(small[:, 1:] > small[:, :-1]))
        records.append({**row, "imageSha256": sha})
        seen.add(sha)
    n = len(records)
    if n < 250:
        raise ValueError(
            "Too few audited public photos for a domain-training experiment"
        )
    parent = list(range(n))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def union(i, j):
        parent[find(i)] = find(j)

    group_first = {}
    for i, row in enumerate(records):
        # IDs are one-based in this source. Respect the audited manifest's
        # sourceBlock and any explicit group links before the content audit.
        block = row.get("sourceBlock", (int(row["id"]) - 1) // 20)
        keys = [("sourceBlock", str(block))]
        for field in ("group", "documentGroup"):
            if row.get(field) is not None:
                keys.append(("documentGroup", str(row[field])))
        for key in keys:
            if key in group_first:
                union(i, group_first[key])
            group_first[key] = i
    content_links = []
    bit_count = np.array([i.bit_count() for i in range(256)], np.uint8)
    h = np.stack(hashes)
    for i in range(n):
        distance = bit_count[np.bitwise_xor(h[i + 1 :], h[i])].sum(1)
        for j in np.flatnonzero(distance <= 6) + i + 1:
            union(i, int(j))
            content_links.append([i, int(j), int(distance[j - i - 1])])
    groups = {}
    for i in range(n):
        groups.setdefault(find(i), []).append(i)
    rng = np.random.default_rng(20261111)
    order = list(groups)
    rng.shuffle(order)
    validation = []
    validation_target = max(50, int(np.ceil(n * 0.15)))
    for group in order:
        members = groups[group]
        if (
            len(validation) < validation_target
            and len(members) <= n * 0.3
            and len(validation) + len(members) <= n - 200
        ):
            validation.extend(members)
    validation = np.array(sorted(validation), np.int64)
    training = np.setdiff1d(np.arange(n), validation)
    if len(validation) < 50 or len(training) < 200:
        raise ValueError(
            "Document-group split cannot support an independent validation set"
        )
    output.mkdir(parents=True, exist_ok=True)
    np.savez(
        output / "public-data.npz",
        images=np.stack(images),
        masks=np.stack(masks),
        quads=np.stack(quads),
        training=training,
        validation=validation,
    )
    report = {
        "source": "Shima Dizaj, DOI 10.17632/x3nm4cxr83.3, CC BY 4.0",
        "manifestSha256": digest(manifest),
        "records": records,
        "training": training.tolist(),
        "validation": validation.tolist(),
        "groups": list(groups.values()),
        "rectifiedContentLinks": content_links,
        "manifestQuadConvention": "upright pixel centres / (W-1,H-1)",
        "trainingQuadConvention": "edge-normalized (uprightPixel+.5)/(W,H); maskPixel=edgeQuad*160-.5",
        "nearDuplicateRule": "256-bit rectified dHash Hamming <= 6; sourceBlock or one-based ID blocks of 20; optional group/documentGroup union",
        "ownerPhotosUsed": False,
        "limits": "Grouping reduces leakage; physical document identities are not supplied by the source.",
    }
    (output / "split.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return output / "public-data.npz"


def tensors(images, masks, ids, rng=None):
    x, m = images[ids].copy(), masks[ids].copy()
    if rng is not None:
        for i in range(len(ids)):
            if rng.random() < 0.5:
                x[i], m[i] = x[i, :, ::-1], m[i, :, ::-1]
            if rng.random() < 0.5:
                x[i], m[i] = x[i, ::-1], m[i, ::-1]
            if rng.random() < 0.5:
                x[i] = np.clip(x[i].astype(np.float32) * rng.uniform(0.8, 1.15), 0, 255)
    edge = np.stack(
        [
            cv2.morphologyEx(mask, cv2.MORPH_GRADIENT, np.ones((3, 3), np.uint8))
            for mask in m
        ]
    )
    return (
        torch.from_numpy(x.transpose(0, 3, 1, 2).copy()).cuda().float() / 255,
        torch.from_numpy(m[:, None].copy()).cuda().float(),
        torch.from_numpy(edge[:, None]).cuda().float(),
    )


@torch.inference_mode()
def evaluate(model, data):
    model.eval()
    losses, overlaps, failures = [], [], 0
    for ids in np.array_split(
        data["validation"], max(1, (len(data["validation"]) + 15) // 16)
    ):
        x, mask, edge = tensors(data["images"], data["masks"], ids)
        out = model(x)
        losses.append(float(loss_fn(out, mask, edge)))
        for prediction, truth in zip(
            out[:, 0].sigmoid().cpu().numpy(), data["quads"][ids]
        ):
            flat = quad_from_mask(prediction)
            if flat is None:
                overlaps.append(0.0)
                failures += 1
                continue
            q = np.asarray(flat, np.float32).reshape(4, 2) * 1000
            gt = truth.astype(np.float32) * 1000
            intersection = cv2.intersectConvexConvex(q, gt)[0]
            union = cv2.contourArea(q) + cv2.contourArea(gt) - intersection
            overlaps.append(float(intersection / max(union, 1e-6)))
    return {
        "loss": float(np.mean(losses)),
        "maskQuadIoU": float(np.mean(overlaps)),
        "failures": failures,
        "count": len(overlaps),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--replay", type=Path, required=True)
    parser.add_argument("--warm", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument(
        "--prepared", type=Path, help="Reuse audited public-data.npz beside split.json"
    )
    parser.add_argument("--adaptation", choices=["full", "edge"], default="full")
    parser.add_argument("--steps", type=int, default=6000)
    parser.add_argument("--batch", type=int, default=16)
    args = parser.parse_args()
    root = args.out.resolve()
    repo = Path(__file__).resolve().parents[1]
    if root == repo or repo in root.parents:
        raise ValueError("Research data and weights must stay outside the repository")
    root.mkdir(parents=True, exist_ok=True)
    (root / "invocation.json").write_text(
        json.dumps(
            {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
            indent=2,
        ),
        encoding="utf-8",
    )
    (root / ("used-" + Path(__file__).name)).write_bytes(Path(__file__).read_bytes())
    (root / "used-train_page_net.py").write_bytes(
        Path(__file__).with_name("train_page_net.py").read_bytes()
    )
    torch.set_num_threads(4)
    cv2.setNumThreads(1)
    torch.manual_seed(20261111)
    if not torch.cuda.is_available():
        raise RuntimeError("This training experiment requires CUDA")
    if args.prepared:
        prepared = args.prepared.resolve()
        split = json.loads(prepared.with_name("split.json").read_text(encoding="utf-8"))
        if split["manifestSha256"] != digest(args.manifest):
            raise ValueError("Prepared public data does not match the audited manifest")
    else:
        prepared = prepare(args.manifest, root / "data")
    with np.load(prepared) as packed:
        public = {key: packed[key] for key in packed.files}
    count = json.loads((args.replay / "scenes.json").read_text())["count"]
    replay_images = np.memmap(
        args.replay / "images.bin", mode="r", dtype=np.uint8, shape=(count, 320, 320, 3)
    )
    replay_masks = np.memmap(
        args.replay / "masks.bin", mode="r", dtype=np.uint8, shape=(count, 160, 160)
    )
    model, teacher = PageNet().cuda(), PageNet().cuda().eval()
    state = torch.load(args.warm, map_location="cuda", weights_only=True)
    model.load_state_dict(state)
    teacher.load_state_dict(state)
    if args.adaptation == "edge":
        # Preserve global sheet selection and its context features. Adapt only
        # the final high-resolution decoder and the existing mask/boundary head.
        model.requires_grad_(False)
        model.up[0].requires_grad_(True)
        model.head.requires_grad_(True)
    teacher.requires_grad_(False)
    baseline = evaluate(model, public)
    print(json.dumps({"publicValidationBaseline": baseline}), flush=True)
    best_iou = baseline["maskQuadIoU"]
    torch.save(model.state_dict(), root / "best.pt")
    optimizer = torch.optim.AdamW(
        [value for value in model.parameters() if value.requires_grad],
        lr=0.00005 if args.adaptation == "full" else 0.0001,
        weight_decay=0.0001,
    )
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(
        optimizer, args.steps, eta_min=0.000005
    )
    scaler = torch.amp.GradScaler("cuda")
    rng = np.random.default_rng(20261111)
    half = args.batch // 2
    history = []
    start = time.perf_counter()
    for step in range(1, args.steps + 1):
        model.train()
        # Preserve the published population statistics while adapting convolution weights.
        for layer in model.modules():
            if isinstance(layer, nn.BatchNorm2d):
                layer.eval()
        px, pm, pe = tensors(
            public["images"], public["masks"], rng.choice(public["training"], half), rng
        )
        rx, rm, re = tensors(
            replay_images,
            replay_masks,
            rng.integers(count, size=args.batch - half),
            rng,
        )
        with torch.amp.autocast("cuda"):
            out = model(torch.cat([px, rx]))
            with torch.no_grad():
                prior = teacher(rx).sigmoid()
            loss = loss_fn(out[:half], pm, pe) + loss_fn(out[half:], rm, re)
            loss = loss + 8 * F.mse_loss(out[half:].sigmoid(), prior)
        optimizer.zero_grad(set_to_none=True)
        scaler.scale(loss).backward()
        previous_scale = scaler.get_scale()
        scaler.step(optimizer)
        scaler.update()
        if scaler.get_scale() >= previous_scale:
            scheduler.step()
        if step % 200 == 0:
            print(
                json.dumps(
                    {
                        "step": step,
                        "loss": float(loss.detach()),
                        "seconds": time.perf_counter() - start,
                    }
                ),
                flush=True,
            )
        if step % 1000 == 0 or step == args.steps:
            score = {"step": step, **evaluate(model, public)}
            history.append(score)
            print(json.dumps(score), flush=True)
            if score["maskQuadIoU"] > best_iou:
                best_iou = score["maskQuadIoU"]
                torch.save(model.state_dict(), root / "best.pt")
            (root / "history.json").write_text(
                json.dumps(history, indent=2), encoding="utf-8"
            )
    model.cpu().load_state_dict(
        torch.load(root / "best.pt", map_location="cpu", weights_only=True)
    )
    model.eval()
    frozen = {
        name for name, value in model.named_parameters() if not value.requires_grad
    }
    frozen.update(name for name, _ in model.named_buffers())
    changed_frozen = [
        name
        for name, value in model.state_dict().items()
        if name in frozen and not torch.equal(value, state[name].cpu())
    ]
    if changed_frozen:
        raise RuntimeError(
            f"Frozen population/backbone state changed: {changed_frozen}"
        )
    target = root / "pagenet-public.onnx"
    torch.onnx.export(
        model,
        torch.zeros(1, 3, 320, 320),
        target,
        input_names=["image"],
        output_names=["logits"],
        opset_version=17,
        dynamo=False,
    )
    report = {
        "architecture": "unchanged two-channel PageNet",
        "parameters": sum(x.numel() for x in model.parameters()),
        "adaptation": args.adaptation,
        "trainableParameters": sum(
            x.numel() for x in model.parameters() if x.requires_grad
        ),
        "preparedDataSha256": digest(prepared),
        "frozenStateUnchanged": not changed_frozen,
        "warmSha256": digest(args.warm),
        "modelSha256": digest(target),
        "publicValidationBaseline": baseline,
        "selectedPublicIoU": best_iou,
        "checkpointSelection": "public group-held-out mask-quad IoU only; warm baseline is eligible",
        "ownerPhotosUsedForTrainingOrSelection": False,
        "integrated": False,
        "steps": args.steps,
        "seconds": time.perf_counter() - start,
        "history": history,
    }
    (root / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
