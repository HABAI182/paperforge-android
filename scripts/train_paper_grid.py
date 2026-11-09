"""Original boundary-preserving residual geometry model, offline only.

Known invertible synthetic warps of observed flat references provide geometric
supervision, NOT claims about real-photo ground truth. Real photos are holdout
probes. No reference text or predicted glyph is rendered into the result.
"""

from pathlib import Path
import argparse, hashlib, json, time
import cv2, numpy as np, torch
from torch import nn
from torch.nn import functional as F
from scan_flat_contact import line_metric


class PaperGrid(nn.Module):
    def __init__(self, structure=False, normalized=False):
        super().__init__()
        layers = []
        channels = 8 if structure else 5
        self.structure = structure
        for width in [16, 24, 40, 64]:
            if normalized:
                layers.extend(
                    [
                        nn.Conv2d(channels, width, 3, stride=2, padding=1),
                        nn.GroupNorm(4, width),
                        nn.GELU(),
                        nn.Conv2d(width, width, 3, padding=1),
                        nn.GroupNorm(4, width),
                        nn.GELU(),
                    ]
                )
            else:
                layers.extend(
                    [
                        nn.Conv2d(channels, width, 3, stride=2, padding=1),
                        nn.ReLU(),
                        nn.Conv2d(width, width, 3, padding=1),
                        nn.ReLU(),
                    ]
                )
            channels = width
        self.encoder = nn.Sequential(*layers)
        self.head = nn.Sequential(
            nn.Conv2d(64, 32, 3, padding=1),
            nn.GELU() if normalized else nn.ReLU(),
            nn.Conv2d(32, 2, 3, padding=1),
        )
        nn.init.zeros_(self.head[-1].weight)
        nn.init.zeros_(self.head[-1].bias)
        if normalized:
            nn.init.normal_(self.head[-1].weight, std=0.001)
        yy, xx = torch.meshgrid(
            torch.linspace(0, 1, 256), torch.linspace(0, 1, 256), indexing="ij"
        )
        self.register_buffer("coords", torch.stack([xx, yy])[None])
        yy, xx = torch.meshgrid(
            torch.linspace(0, 1, 32), torch.linspace(0, 1, 32), indexing="ij"
        )
        self.register_buffer("base", torch.stack([xx, yy])[None])

    def forward(self, x):
        if self.structure:
            gray = x.mean(1, keepdim=True)
            ink = (F.avg_pool2d(gray, 17, stride=1, padding=8) - gray).clamp(0, 1)
            dx = F.pad(ink[:, :, :, 2:] - ink[:, :, :, :-2], (1, 1, 0, 0))
            dy = F.pad(ink[:, :, 2:, :] - ink[:, :, :-2, :], (0, 0, 1, 1))
            x = torch.cat(
                [x, (ink * 12).clamp(0, 1), (dx * 20).tanh(), (dy * 20).tanh()], 1
            )
        features = self.encoder(
            torch.cat([x, self.coords.expand(x.shape[0], -1, -1, -1)], 1)
        )
        delta = (
            self.head(
                F.interpolate(
                    features, size=(32, 32), mode="bilinear", align_corners=True
                )
            ).tanh()
            * 0.15
        )
        taper = 4 * self.base * (1 - self.base)
        return self.base + delta * taper


def offsets(x, y, p):
    # Horizontal paper bow plus vertical bend/crease/skew, all invertible in this range.
    curve = (
        p[:, 0, None, None] * (x - 0.5)
        + p[:, 1, None, None] * (x - 0.5) ** 2
        + p[:, 2, None, None] * torch.exp(-(((x - p[:, 3, None, None]) / 0.17) ** 2))
    )
    dy = curve * 4 * y * (1 - y) + p[:, 4, None, None] * (x - 0.5) * torch.sin(
        torch.pi * y
    )
    dx = p[:, 5, None, None] * torch.sin(torch.pi * y) * 4 * x * (1 - x)
    return dx, dy


def examples(bases, batch, generator, device, clean=False):
    ids = torch.randint(len(bases), (batch,), generator=generator, device=device)
    source = bases[ids]
    p = (torch.rand(batch, 6, generator=generator, device=device) - 0.5) * torch.tensor(
        [0.16, 0.16, 0.15, 1, 0.08, 0.05], device=device
    )
    p[:, 3] = torch.rand(batch, generator=generator, device=device) * 0.8 + 0.1
    is_clean = torch.rand(batch, generator=generator, device=device) < (
        0.2 if not clean else 1.0
    )
    p[is_clean, :3] = 0
    p[is_clean, 4:] = 0
    yy, xx = torch.meshgrid(
        torch.linspace(0, 1, 256, device=device),
        torch.linspace(0, 1, 256, device=device),
        indexing="ij",
    )
    x = xx[None].expand(batch, -1, -1)
    y = yy[None].expand(batch, -1, -1)
    dx, dy = offsets(x, y, p)
    forward = torch.stack([x + dx, y + dy], -1)
    warped = F.grid_sample(
        source, forward * 2 - 1, align_corners=True, padding_mode="border"
    )
    tone = 0.78 + torch.rand(batch, 3, 1, 1, generator=generator, device=device) * 0.22
    shade = (
        1
        - torch.rand(batch, 1, 1, 1, generator=generator, device=device)
        * 0.25
        * x[:, None]
    )
    warped = (
        warped * tone * shade
        + torch.randn(warped.shape, generator=generator, device=device) * 0.004
    ).clamp(0, 1)
    yy, xx = torch.meshgrid(
        torch.linspace(0, 1, 32, device=device),
        torch.linspace(0, 1, 32, device=device),
        indexing="ij",
    )
    tx = xx[None].expand(batch, -1, -1)
    ty = yy[None].expand(batch, -1, -1)
    ix = tx.clone()
    iy = ty.clone()
    for _ in range(16):
        dx, dy = offsets(ix, iy, p)
        ix = tx - dx
        iy = ty - dy
    return warped, torch.stack([ix, iy], 1), is_clean


def load_bases(paths, device):
    data = []
    for path in paths:
        image = cv2.imread(str(path))
        assert image is not None, path
        # Exclude reference application footer/watermark; no labels are inferred from it.
        image = image[: round(image.shape[0] * 0.9)]
        image = cv2.cvtColor(cv2.resize(image, (256, 256)), cv2.COLOR_BGR2RGB)
        data.append(image.transpose(2, 0, 1).astype("float32") / 255)
    return torch.from_numpy(np.stack(data)).to(device)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--train", type=Path, action="append", required=True)
    p.add_argument("--holdout", type=Path, action="append", required=True)
    p.add_argument("--photo", type=Path, action="append", default=[])
    p.add_argument("--steps", type=int, default=6000)
    p.add_argument("--structure-features", action="store_true")
    p.add_argument("--normalized", action="store_true")
    a = p.parse_args()
    repo = Path(__file__).resolve().parents[1]
    out = a.out.resolve()
    assert out != repo and repo not in out.parents
    train_shas = [hashlib.sha256(x.read_bytes()).hexdigest() for x in a.train]
    hold_shas = [hashlib.sha256(x.read_bytes()).hexdigest() for x in a.holdout]
    assert not (set(train_shas) & set(hold_shas))
    out.mkdir(parents=True, exist_ok=True)
    cv2.setNumThreads(1)
    torch.set_num_threads(4)
    torch.manual_seed(20261107)
    gen = torch.Generator(device="cuda").manual_seed(20261107)
    bases = load_bases(a.train, "cuda")
    hold = load_bases(a.holdout, "cuda")
    model = PaperGrid(structure=a.structure_features, normalized=a.normalized).cuda()
    opt = torch.optim.AdamW(model.parameters(), lr=0.0003 if a.normalized else 0.0005)
    start = time.time()
    history = []
    for step in range(a.steps):
        source, target, clean = examples(bases, 16, gen, "cuda")
        prediction = model(source)
        mse = (prediction - target).square().mean()
        # Monotone axes guard against a new fold in the sampling map.
        fold = (
            F.relu(0.001 - torch.diff(prediction[:, 0], dim=2)).mean()
            + F.relu(0.001 - torch.diff(prediction[:, 1], dim=1)).mean()
        )
        loss = (mse + fold * 0.1) * (1000 if a.structure_features else 1)
        opt.zero_grad()
        loss.backward()
        opt.step()
        if step % 600 == 0:
            row = {
                "step": step,
                "loss": float(loss.detach()),
                "encoderGradient": float(model.encoder[0].weight.grad.abs().mean()),
                "maxDisplacement": float(
                    (prediction - model.base).detach().abs().max()
                ),
                "seconds": round(time.time() - start, 2),
            }
            history.append(row)
            print(row, flush=True)
    model.eval()
    torch.save(model.cpu().state_dict(), out / "paper-grid.pt")
    model.cuda()
    report = {
        "scope": "known synthetic invertible warp validation on held-out reference pages; actual-photo proxies separately; NOT Android output",
        "structureFeatures": a.structure_features,
        "normalizedEncoder": a.normalized,
        "trainSeconds": time.time() - start,
        "steps": a.steps,
        "parameters": sum(v.numel() for v in model.parameters()),
        "trainHashes": train_shas,
        "holdoutHashes": hold_shas,
        "trainingHistory": history,
        "integrated": False,
        "synthetic": [],
        "real": [],
    }
    val = torch.Generator(device="cuda").manual_seed(91631)
    with torch.inference_mode():
        for i in range(20):
            source, target, clean = examples(hold, 8, val, "cuda")
            prediction = model(source)
            identity = model.base
            report["synthetic"].append(
                {
                    "identityGridRmse256px": float(
                        ((identity - target).square().mean().sqrt() * 255).cpu()
                    ),
                    "candidateGridRmse256px": float(
                        ((prediction - target).square().mean().sqrt() * 255).cpu()
                    ),
                    "cleanCount": int(clean.sum()),
                    "cleanMaxDisplacement256px": float(
                        ((prediction[clean] - identity).abs().max() * 255).cpu()
                    )
                    if clean.any()
                    else None,
                }
            )
        for i, path in enumerate(a.photo):
            image = cv2.imread(str(path))
            assert image is not None
            inp = (
                cv2.cvtColor(cv2.resize(image, (256, 256)), cv2.COLOR_BGR2RGB)
                .transpose(2, 0, 1)
                .astype("float32")
                / 255
            )
            grid = model(torch.from_numpy(inp[None]).cuda())
            dense = (
                F.interpolate(
                    grid, size=image.shape[:2], mode="bilinear", align_corners=True
                )[0]
                .permute(1, 2, 0)
                .cpu()
                .numpy()
            )
            h, w = image.shape[:2]
            output = cv2.remap(
                image,
                dense[:, :, 0] * (w - 1),
                dense[:, :, 1] * (h - 1),
                cv2.INTER_CUBIC,
                borderMode=cv2.BORDER_REPLICATE,
            )
            folder = out / f"{i}-{path.stem}"
            folder.mkdir(exist_ok=True)
            cv2.imwrite(str(folder / "source.png"), image)
            cv2.imwrite(str(folder / "unwarp.png"), output)
            np.save(folder / "grid.npy", grid[0].permute(1, 2, 0).cpu().numpy())
            report["real"].append(
                {
                    "photo": str(path),
                    "before": line_metric(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)),
                    "after": line_metric(cv2.cvtColor(output, cv2.COLOR_BGR2GRAY)),
                }
            )
    model.cpu()
    torch.onnx.export(
        model,
        torch.zeros(1, 3, 256, 256),
        out / "paper-grid.onnx",
        input_names=["image"],
        output_names=["grid"],
        opset_version=17,
        dynamo=False,
    )
    report["sha256"] = hashlib.sha256(
        (out / "paper-grid.onnx").read_bytes()
    ).hexdigest()
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
