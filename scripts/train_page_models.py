"""Train top-sheet segmentation candidates under PageNet's contract and compare them fairly.

Architectures (all from random initialisation; no third-party weights):
  pagenet      the production architecture (train_page_net.PageNet)
  mbv3unet     MobileNetV3-Small encoder (inverted residuals, squeeze-excite, hard-swish) + depthwise U-Net decoder
  liteseg      PP-LiteSeg-style: slim STDC-like encoder, simple pyramid pooling (SPPM), unified attention fusion
               (UAFM), predicted at 1/8 and upsampled
Contract: input "image" 1x3x320x320 RGB in [0,1] -> output "logits" 1x2x160x160 (top-sheet mask, sheet boundary).

Data: synthetic scene sets (memmaps), and the audited public photos (Mendeley x3nm4cxr83, CC BY 4.0) from
public-data.npz - only their training split; a group-disjoint slice of it is the validation set. The 167
held-out public photos and all owner photos are never read here.

Checkpoints are chosen on: public validation slice + held-back realistic synthetic scenes, with severe failures
(quad IoU < .5) penalised. The production decoder (train_page_net.quad_from_mask) turns masks into quads.
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

from train_page_net import PageNet, loss_fn
from train_page_net import quad_from_mask as _quad_from_mask


def quad_from_mask(prob, cut=0.5):
    """train_page_net's decoder at a chosen threshold. Thresholding first keeps this independent of whether that
    decoder takes a cut argument: it binarises at .5, which a 0/1 map passes unchanged."""
    return _quad_from_mask((prob > cut).astype(np.float32))

# ---------------------------------------------------------------- architectures


class ConvBN(nn.Sequential):
    def __init__(self, cin, cout, k=3, s=1, groups=1, act=nn.ReLU):
        super().__init__(nn.Conv2d(cin, cout, k, s, k // 2, groups=groups, bias=False), nn.BatchNorm2d(cout), act() if act else nn.Identity())


class SE(nn.Module):
    def __init__(self, c, r=4):
        super().__init__()
        self.fc1 = nn.Conv2d(c, max(8, c // r), 1)
        self.fc2 = nn.Conv2d(max(8, c // r), c, 1)

    def forward(self, x):
        s = x.mean((2, 3), keepdim=True)
        return x * F.hardsigmoid(self.fc2(F.relu(self.fc1(s))))


class InvertedResidual(nn.Module):
    def __init__(self, cin, exp, cout, k, s, se, act):
        super().__init__()
        layers = [] if exp == cin else [ConvBN(cin, exp, 1, act=act)]
        layers += [ConvBN(exp, exp, k, s, groups=exp, act=act)]
        if se:
            layers += [SE(exp)]
        layers += [ConvBN(exp, cout, 1, act=None)]
        self.block = nn.Sequential(*layers)
        self.skip = s == 1 and cin == cout

    def forward(self, x):
        y = self.block(x)
        return x + y if self.skip else y


class UpBlock(nn.Module):
    """Upsample, concatenate the skip, depthwise-separable fuse."""

    def __init__(self, cin, cskip, cout):
        super().__init__()
        self.fuse = nn.Sequential(ConvBN(cin + cskip, cin + cskip, 3, groups=cin + cskip, act=nn.ReLU6), ConvBN(cin + cskip, cout, 1, act=nn.ReLU6))

    def forward(self, x, skip):
        x = F.interpolate(x, size=skip.shape[2:], mode="bilinear", align_corners=False)
        return self.fuse(torch.cat([x, skip], 1))


class MobileNetV3UNet(nn.Module):
    """MobileNetV3-Small layout (Howard et al. 2019), trained from scratch. width scales every channel count
    (rounded to multiples of 8, as MobileNetV3 does); width 1.0 is the round 1-3 model, parameter for parameter."""

    def __init__(self, width=1.0):
        super().__init__()
        HS, RE = nn.Hardswish, nn.ReLU

        def c(n):
            return n if width == 1.0 else max(8, int(n * width + 4) // 8 * 8)

        self.stem = ConvBN(3, c(16), 3, 2, act=HS)  # 1/2
        cfg = [  # cin, exp, cout, k, s, se, act
            [(16, 16, 16, 3, 2, True, RE)],  # 1/4
            [(16, 72, 24, 3, 2, False, RE), (24, 88, 24, 3, 1, False, RE)],  # 1/8
            [(24, 96, 40, 5, 2, True, HS), (40, 240, 40, 5, 1, True, HS), (40, 240, 40, 5, 1, True, HS),
             (40, 120, 48, 5, 1, True, HS), (48, 144, 48, 5, 1, True, HS)],  # 1/16
            [(48, 288, 96, 5, 2, True, HS), (96, 576, 96, 5, 1, True, HS), (96, 576, 96, 5, 1, True, HS)],  # 1/32
        ]
        cfg = [[(c(a), c(e), c(o), k, st, se, act) for a, e, o, k, st, se, act in stage] for stage in cfg]
        self.stages = nn.ModuleList(nn.Sequential(*[InvertedResidual(*x) for x in stage]) for stage in cfg)
        self.context = ConvBN(c(96), c(96), 1, act=HS)
        self.up = nn.ModuleList([UpBlock(c(96), c(48), c(48)), UpBlock(c(48), c(24), c(32)), UpBlock(c(32), c(16), c(24)),
                                 UpBlock(c(24), c(16), c(16))])
        self.head = nn.Conv2d(c(16), 2, 1)

    def forward(self, x):
        feats = [self.stem(x)]
        for stage in self.stages:
            feats.append(stage(feats[-1]))
        y = self.context(feats[-1])
        for i, up in enumerate(self.up):
            y = up(y, feats[-2 - i])
        return self.head(y)  # 1/2 = 160


class STDCBlock(nn.Module):
    """Short-term dense concatenation (Fan et al. 2021), slimmed: 4 steps, halving widths."""

    def __init__(self, cin, cout, stride):
        super().__init__()
        c = [cout // 2, cout // 4, cout // 8, cout // 8]
        self.c1 = ConvBN(cin, c[0], 1)
        self.down = ConvBN(c[0], c[0], 3, 2, groups=c[0], act=None) if stride == 2 else None
        self.c2 = ConvBN(c[0], c[1], 3)
        self.c3 = ConvBN(c[1], c[2], 3)
        self.c4 = ConvBN(c[2], c[3], 3)
        self.skip_pool = nn.AvgPool2d(3, 2, 1) if stride == 2 else None

    def forward(self, x):
        a = self.c1(x)
        b = self.c2(self.down(a) if self.down is not None else a)
        c = self.c3(b)
        d = self.c4(c)
        a = self.skip_pool(a) if self.skip_pool is not None else a
        return torch.cat([a, b, c, d], 1)


class SPPM(nn.Module):
    """Simple pyramid pooling: bins 1, 2, 5 (exportable on a 10x10 map), summed, then fused."""

    def __init__(self, cin, cmid, cout):
        super().__init__()
        self.bins = (1, 2, 5)
        self.convs = nn.ModuleList(ConvBN(cin, cmid, 1) for _ in self.bins)
        self.out = ConvBN(cmid, cout, 3)

    def forward(self, x):
        h, w = x.shape[2:]
        y = 0
        for b, conv in zip(self.bins, self.convs):
            p = F.avg_pool2d(x, (h // b, w // b))
            y = y + F.interpolate(conv(p), size=(h, w), mode="bilinear", align_corners=False)
        return self.out(y)


class UAFM(nn.Module):
    """Unified attention fusion, spatial variant: alpha from mean/max of both inputs mixes high and low."""

    def __init__(self, clow, chigh, cout):
        super().__init__()
        self.low = ConvBN(clow, chigh, 3)
        self.att = nn.Sequential(ConvBN(4, 2, 3), ConvBN(2, 1, 3, act=None))
        self.out = ConvBN(chigh, cout, 3)

    def forward(self, high, low):
        high = F.interpolate(high, size=low.shape[2:], mode="bilinear", align_corners=False)
        low = self.low(low)
        stats = torch.cat([high.mean(1, keepdim=True), high.amax(1, keepdim=True), low.mean(1, keepdim=True), low.amax(1, keepdim=True)], 1)
        a = torch.sigmoid(self.att(stats))
        return self.out(high * a + low * (1 - a))


class LiteSeg(nn.Module):
    def __init__(self):
        super().__init__()
        self.stem = nn.Sequential(ConvBN(3, 16, 3, 2), ConvBN(16, 32, 3, 2))  # 1/4
        self.s8 = nn.Sequential(STDCBlock(32, 64, 2), STDCBlock(64, 64, 1))  # 1/8
        self.s16 = nn.Sequential(STDCBlock(64, 128, 2), STDCBlock(128, 128, 1))  # 1/16
        self.s32 = nn.Sequential(STDCBlock(128, 192, 2), STDCBlock(192, 192, 1))  # 1/32
        self.sppm = SPPM(192, 64, 64)
        self.f16 = UAFM(128, 64, 48)
        self.f8 = UAFM(64, 48, 32)
        self.head = nn.Sequential(ConvBN(32, 32, 3), nn.Conv2d(32, 2, 1))

    def forward(self, x):
        x4 = self.stem(x)
        x8 = self.s8(x4)
        x16 = self.s16(x8)
        x32 = self.s32(x16)
        y = self.sppm(x32)
        y = self.f16(y, x16)
        y = self.f8(y, x8)
        y = self.head(y)
        return F.interpolate(y, size=(160, 160), mode="bilinear", align_corners=False)


def build(arch):
    return {"pagenet": PageNet, "mbv3unet": MobileNetV3UNet, "liteseg": LiteSeg,
            "mbv3unet-w15": lambda: MobileNetV3UNet(1.5)}[arch]()


# ---------------------------------------------------------------- data


def scene_sets(roots):
    sets = []
    for root in roots:
        meta = json.loads((root / "scenes.json").read_text(encoding="utf-8"))
        n = meta["count"]
        sets.append((root.name, n, np.memmap(root / "images.bin", np.uint8, "r", shape=(n, 320, 320, 3)),
                     np.memmap(root / "masks.bin", np.uint8, "r", shape=(n, 160, 160))))
    return sets


def public_split(npz, split_json, val_count, seed=5):
    """Training ids of the audited split, minus a group-disjoint validation slice taken from them."""
    data = np.load(npz)
    split = json.loads(split_json.read_text(encoding="utf-8"))
    training = set(int(i) for i in data["training"])
    rng = np.random.default_rng(seed)
    groups = [g for g in split["groups"] if all(i in training for i in g)]
    rng.shuffle(groups)
    val = []
    for g in groups:
        if len(val) >= val_count:
            break
        val += [int(i) for i in g]
    train = sorted(training - set(val))
    return data["images"], data["masks"], data["quads"], np.array(train), np.array(sorted(val))


def batches(sets, holdout, public, shares, batch, seed):
    """shares: fraction of each batch from (public photos, realistic set, other synthetic sets)."""
    images, masks, _, train_ids, _ = public
    ready: queue.Queue = queue.Queue(maxsize=8)
    kernel = np.ones((3, 3), np.uint8)
    realistic = [s for s in sets if s[0].startswith("scenes-real")]
    other = [s for s in sets if not s[0].startswith("scenes-real")]

    def fill(worker):
        gen = np.random.default_rng(seed + 1000 * worker)
        while True:
            x = np.empty((batch, 320, 320, 3), np.uint8)
            m = np.empty((batch, 160, 160), np.uint8)
            for k in range(batch):
                r = gen.random()
                if r < shares[0]:
                    i = train_ids[gen.integers(len(train_ids))]
                    pic, mask = images[i], masks[i]
                    if gen.random() < 0.6:  # photos vary in exposure and white balance
                        pic = np.clip(pic.astype(np.float32) * gen.uniform(0.75, 1.2, 3), 0, 255).astype(np.uint8)
                else:
                    pool = realistic if (r < shares[0] + shares[1] and realistic) else other
                    name, n, ims, ms = pool[gen.integers(len(pool))]
                    limit = n - holdout if name.startswith("scenes-real") else n
                    i = gen.integers(limit)
                    pic, mask = ims[i], ms[i]
                if gen.random() < 0.5:
                    pic, mask = pic[:, ::-1], mask[:, ::-1]
                if gen.random() < 0.5:
                    pic, mask = pic[::-1], mask[::-1]
                x[k], m[k] = pic, mask
            edge = np.stack([cv2.morphologyEx(mk, cv2.MORPH_GRADIENT, kernel) for mk in m])
            ready.put((torch.from_numpy(x.transpose(0, 3, 1, 2).copy()), torch.from_numpy(m[:, None].copy()), torch.from_numpy(edge[:, None])))

    for worker in range(4):
        threading.Thread(target=fill, args=(worker,), daemon=True).start()
    while True:
        yield ready.get()


# ---------------------------------------------------------------- evaluation


def quad_iou_mask(quad, mask):
    """IoU of a normalised quad (x,y in 0..1, edge convention) with a 160x160 0/1 mask."""
    poly = np.zeros_like(mask)
    cv2.fillPoly(poly, [np.round(np.asarray(quad).reshape(4, 2) * 160 - 0.5).astype(np.int32)], 1)
    u = np.logical_or(poly, mask).sum()
    return float(np.logical_and(poly, mask).sum() / u) if u else 0.0


def quad_iou(a, b):
    q = np.asarray(a, np.float32).reshape(4, 2) * 1000
    g = np.asarray(b, np.float32).reshape(4, 2) * 1000
    inter = cv2.intersectConvexConvex(q, g)[0]
    return float(inter / max(cv2.contourArea(q) + cv2.contourArea(g) - inter, 1e-6))


@torch.inference_mode()
def probabilities(model, images, device, size=32):
    out = []
    for i in range(0, len(images), size):
        x = torch.from_numpy(np.ascontiguousarray(images[i:i + size]).transpose(0, 3, 1, 2)).to(device).float() / 255
        out.append(torch.sigmoid(model(x)[:, 0]).float().cpu().numpy())
    return np.concatenate(out)


def evaluate(model, device, public, synth_val, cuts=(0.15, 0.3, 0.5), real=None):
    model.eval()
    images, masks, quads, _, val_ids = public
    pp = probabilities(model, images[val_ids], device)
    sp = probabilities(model, synth_val[0], device)
    rp = probabilities(model, real[0], device) if real is not None else None
    report = {}
    for cut in cuts:
        pub = []
        for p, q in zip(pp, quads[val_ids]):
            f = quad_from_mask(p, cut=cut)
            pub.append(0.0 if f is None else quad_iou(f, q))
        syn = []
        for p, m in zip(sp, synth_val[1]):
            f = quad_from_mask(p, cut=cut)
            syn.append(0.0 if f is None else quad_iou_mask(f, m))
        pub, syn = np.array(pub), np.array(syn)
        report[str(cut)] = {
            "publicMean": float(pub.mean()), "publicSevere": int((pub < 0.5).sum()), "publicBad": int((pub < 0.8).sum()),
            "synthMean": float(syn.mean()), "synthSevere": int((syn < 0.5).sum()), "synthBad": int((syn < 0.8).sum()),
            # Severe failures cost far more than a small mean change.
            "score": float(pub.mean() + syn.mean() - 0.01 * ((pub < 0.5).sum() + (syn < 0.5).sum() * len(pub) / len(syn))),
        }
        if rp is not None:
            rv = []
            for p, q in zip(rp, real[1]):
                f = quad_from_mask(p, cut=cut)
                rv.append(0.0 if f is None else quad_iou(f, q))
            rv = np.array(rv)
            n = len(rv)
            report[str(cut)].update({
                "realMean": float(rv.mean()), "realSevere": int((rv < 0.5).sum()), "realBad": int((rv < 0.8).sum()),
                # Independent real photos lead; Mendeley and synthetic count half. Severe failures per set are
                # scaled to the real set's size.
                "score": float(rv.mean() + 0.5 * (pub.mean() + syn.mean()) - 0.01 * (
                    (rv < 0.5).sum() + 0.5 * (pub < 0.5).sum() * n / len(pub) + 0.5 * (syn < 0.5).sum() * n / len(syn))),
            })
    model.train()
    return report


def export(model, path):
    model = model.cpu().eval().to(memory_format=torch.contiguous_format)
    torch.onnx.export(model, torch.zeros(1, 3, 320, 320), str(path), input_names=["image"], output_names=["logits"], opset_version=17, dynamo=False)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--arch", choices=["pagenet", "mbv3unet", "liteseg", "mbv3unet-w15"], required=True)
    ap.add_argument("--scenes", type=Path, nargs="+", required=True)
    ap.add_argument("--public", type=Path, required=True, help="public-data.npz (split.json beside it)")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--warm", type=Path)
    ap.add_argument("--steps", type=int, default=24000)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--shares", type=float, nargs=2, default=(0.25, 0.4), help="public photos, realistic set")
    ap.add_argument("--holdout", type=int, default=1000, help="last scenes of each realistic set, kept for validation")
    ap.add_argument("--eval-every", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=7, help="weight init seed; the loader seed is derived from it (7 -> 11, as before)")
    ap.add_argument("--real-val", type=Path, help="real-photo validation npz (build_real_val.py); leads checkpoint selection")
    ap.add_argument("--precision", choices=["fp16", "bf16"], default="fp16",
                    help="autocast type; bf16 + --channels-last measured 1.16-1.38x faster on an RTX 5060 (perf/profile-1.log)")
    ap.add_argument("--channels-last", action="store_true", help="NHWC memory format; only fast together with bf16")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "invocation.json").write_text(json.dumps({k: str(v) for k, v in vars(args).items()}, indent=1), encoding="utf-8")
    torch.manual_seed(args.seed)
    device = "cuda"
    model = build(args.arch).to(device)
    if args.warm:
        model.load_state_dict(torch.load(args.warm, map_location=device, weights_only=True))
    dtype = torch.bfloat16 if args.precision == "bf16" else torch.float16
    layout = torch.channels_last if args.channels_last else torch.contiguous_format
    model = model.to(memory_format=layout)
    torch.backends.cudnn.benchmark = args.channels_last
    params = sum(p.numel() for p in model.parameters())
    sets = scene_sets(args.scenes)
    public = public_split(args.public, args.public.with_name("split.json"), 100)
    realistic = [s for s in sets if s[0].startswith("scenes-real")]
    synth_val = (np.concatenate([np.array(s[2][s[1] - args.holdout:]) for s in realistic]),
                 np.concatenate([np.array(s[3][s[1] - args.holdout:]) for s in realistic]))
    print(json.dumps({"arch": args.arch, "parameters": params, "publicTrain": len(public[3]), "publicVal": len(public[4]),
                      "synthVal": len(synth_val[0]), "sets": [s[0] for s in sets]}), flush=True)
    real = None
    if args.real_val:
        rv = np.load(args.real_val)
        real = (rv["images"], rv["quads"])
    stream = batches(sets, args.holdout, public, args.shares, args.batch, args.seed + 4)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, args.lr, total_steps=args.steps, pct_start=0.06 if not args.warm else 0.02)
    scaler = torch.amp.GradScaler(device, enabled=dtype == torch.float16)  # bf16 needs no loss scaling
    history, best, started, total = [], -1e9, time.time(), 0.0
    if args.warm:
        row = {"step": 0, **evaluate(model, device, public, synth_val, real=real)}
        history.append(row); print(json.dumps(row), flush=True)
    for step in range(1, args.steps + 1):
        x, m, e = next(stream)
        x = (x.to(device, non_blocking=True).float() / 255).contiguous(memory_format=layout)
        m = m.to(device, non_blocking=True).float()
        e = e.to(device, non_blocking=True).float()
        opt.zero_grad(set_to_none=True)
        with torch.amp.autocast(device, dtype=dtype):
            loss = loss_fn(model(x), m, e)
        scaler.scale(loss).backward()
        scaler.step(opt)
        scaler.update()
        sched.step()
        total += loss.item()
        if step % args.eval_every == 0 or step == args.steps:
            report = evaluate(model, device, public, synth_val, real=real)
            cut, best_here = max(report.items(), key=lambda kv: kv[1]["score"])
            row = {"step": step, "loss": total / args.eval_every, "minutes": round((time.time() - started) / 60, 1), "bestCut": cut, **report}
            total = 0.0
            history.append(row)
            print(json.dumps(row), flush=True)
            (args.out / "history.json").write_text(json.dumps(history, indent=1), encoding="utf-8")
            if best_here["score"] > best:
                best = best_here["score"]
                torch.save(model.state_dict(), args.out / "best.pt")
                (args.out / "best.json").write_text(json.dumps({"step": step, "cut": float(cut), **best_here}, indent=1), encoding="utf-8")
    model.load_state_dict(torch.load(args.out / "best.pt", map_location=device, weights_only=True))
    export(model, args.out / "model.onnx")
    print(json.dumps({"done": True, "best": best, "parameters": params}), flush=True)


if __name__ == "__main__":
    main()
