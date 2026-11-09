"""Original offline context-model experiment. Never updates Android assets/defaults.

Training: generated glyphs/rules/curves only. Independent seeded validation is
synthetic, NOT evidence of real document accuracy. Owner photos are evaluation
inputs only. Output directory must be outside the repository.
"""

from pathlib import Path
import argparse, hashlib, json, time
import cv2
import numpy as np
import torch
from torch import nn
from torch.nn import functional as F
from train_paper_ink import layer


class ContextInk(nn.Module):
    """Two-scale spatial context plus full-resolution skip for tiny punctuation."""

    def __init__(self, channels=3, dilation=1):
        super().__init__()

        def block(a, b):
            return nn.Sequential(
                nn.Conv2d(a, b, 3, padding=1),
                nn.ReLU(),
                nn.Conv2d(b, b, 3, padding=1),
                nn.ReLU(),
            )

        self.full = block(channels, 12)
        self.encoder_half = block(12, 20)
        self.context = (
            block(20, 28)
            if dilation == 1
            else nn.Sequential(
                nn.Conv2d(20, 28, 3, padding=dilation, dilation=dilation),
                nn.ReLU(),
                nn.Conv2d(28, 28, 3, padding=dilation, dilation=dilation),
                nn.ReLU(),
            )
        )
        self.uphalf = block(48, 20)
        self.upfull = block(32, 12)
        self.output = nn.Conv2d(12, 1, 1)

    def forward(self, x):
        a = self.full(x)
        b = self.encoder_half(F.avg_pool2d(a, 2))
        c = self.context(F.avg_pool2d(b, 2))
        d = self.uphalf(
            torch.cat(
                [
                    b,
                    F.interpolate(
                        c, size=b.shape[2:], mode="bilinear", align_corners=False
                    ),
                ],
                1,
            )
        )
        return self.output(
            self.upfull(
                torch.cat(
                    [
                        a,
                        F.interpolate(
                            d, size=a.shape[2:], mode="bilinear", align_corners=False
                        ),
                    ],
                    1,
                )
            )
        )


class Export(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x):
        return self.model(x).sigmoid()


def features(ink):
    return np.stack(
        [ink, cv2.GaussianBlur(ink, (0, 0), 0.8), cv2.GaussianBlur(ink, (0, 0), 2)]
    ).astype("float32")


def fixture(rng, n=96, heldout=False, sparse=False, font_layers=None):
    front = (
        font_layers(rng, n, heldout)
        if font_layers is not None and rng.random() < 0.65
        else layer(rng, n)
    )
    if heldout:
        # Different scale/rotation and isolated math punctuation at validation time.
        m = cv2.getRotationMatrix2D(
            (n / 2, n / 2), float(rng.uniform(-6, 6)), float(rng.uniform(0.75, 1.2))
        )
        front = cv2.warpAffine(front, m, (n, n))
        glyph = np.zeros((n, n), "uint8")
        cv2.putText(
            glyph,
            str(rng.choice([".,:=", "x-1", "(a+b)/2"])),
            (3, n - 8),
            cv2.FONT_HERSHEY_COMPLEX_SMALL,
            0.65,
            255,
            1,
            cv2.LINE_AA,
        )
        front = np.maximum(front, glyph.astype("float32") / 255)
    contrast = float(np.exp(rng.uniform(np.log(0.035), np.log(0.95))))
    front = cv2.GaussianBlur(front, (0, 0), float(rng.uniform(0.25, 0.65))) * contrast
    if rng.random() < 0.4:
        for y in range(int(rng.integers(3, 15)), n, int(rng.integers(13, 25))):
            cv2.line(
                front,
                (0, y),
                (n - 1, y),
                float(rng.uniform(0.035, 0.4)),
                1,
                cv2.LINE_AA,
            )
    truth = front > max(0.004, contrast * 0.10)
    if sparse and rng.random() < 0.30:
        front[:] = 0
        truth[:] = False
    back = (
        font_layers(rng, n, heldout)
        if font_layers is not None and rng.random() < 0.65
        else layer(rng, n)
    )[:, ::-1].copy()
    back = cv2.GaussianBlur(back, (0, 0), float(rng.uniform(0.8, 3.4))) * float(
        rng.uniform(0.02, 0.25)
    )
    noise = (rng.random((n, n)) < 0.014).astype("float32") * rng.uniform(0.01, 0.12)
    noise = cv2.GaussianBlur(noise, (0, 0), 0.65)
    yy, xx = np.mgrid[:n, :n] / n
    shade = (
        rng.uniform(0, 0.07) * (xx - 0.5) ** 2
        + rng.uniform(0, 0.05) * np.exp(-(((yy - rng.random()) / 0.12) ** 2))
    ).astype("float32")
    ink = np.clip(
        front + back + noise + shade + rng.normal(0, 0.0025, (n, n)), 0, 1
    ).astype("float32")
    neg = (~truth) & (back > 0.012)
    weight = (
        0.12
        + truth.astype("float32") * (12 if sparse else 8)
        + neg.astype("float32") * (8 if sparse else 2)
    )
    return (
        features(ink),
        truth[None].astype("float32"),
        weight[None].astype("float32"),
        neg,
        contrast,
    )


def evaluate(model, seed, count, font_layers=None):
    rng = np.random.default_rng(seed)
    positive = []
    negative = []
    faint = []
    model.eval()
    with torch.inference_mode():
        for _ in range(count):
            x, y, _, neg, contrast = fixture(rng, heldout=True, font_layers=font_layers)
            pred = model(torch.from_numpy(x[None])).sigmoid()[0, 0].numpy()
            positive.append(pred[y[0] > 0])
            negative.append(pred[neg])
            if contrast < 0.12:
                faint.append(pred[y[0] > 0])
    return {
        str(t): {
            "frontRetention": float(np.mean(np.concatenate(positive) >= t)),
            "faintRetention": float(np.mean(np.concatenate(faint) >= t)),
            "bleedRejected": float(np.mean(np.concatenate(negative) < t)),
        }
        for t in [0.02, 0.05, 0.1, 0.2, 0.3, 0.5]
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--steps", type=int, default=1800)
    ap.add_argument("--samples", type=int, default=640)
    ap.add_argument("--photo", type=Path, action="append", default=[])
    ap.add_argument("--resume-evaluation", action="store_true")
    ap.add_argument("--sparse-blank", action="store_true")
    args = ap.parse_args()
    out = args.out.resolve()
    repo = Path(__file__).resolve().parents[1]
    if out == repo or repo in out.parents:
        raise SystemExit("Keep experiment weights and owner photos outside Git")
    out.mkdir(parents=True, exist_ok=True)
    cv2.setNumThreads(1)
    torch.set_num_threads(4)
    torch.manual_seed(20261104)
    rng = np.random.default_rng(20261104)
    data = [fixture(rng, sparse=args.sparse_blank) for _ in range(args.samples)]
    x = torch.tensor(np.stack([a[0] for a in data]))
    y = torch.tensor(np.stack([a[1] for a in data]))
    w = torch.tensor(np.stack([a[2] for a in data]))
    model = ContextInk()
    opt = torch.optim.AdamW(model.parameters(), lr=0.001, weight_decay=0.0001)
    if args.resume_evaluation:
        model.load_state_dict(torch.load(out / "context-ink.pt", weights_only=True))
    criterion = nn.BCEWithLogitsLoss(reduction="none")
    start = time.time()
    for step in range(0 if args.resume_evaluation else args.steps):
        model.train()
        idx = torch.from_numpy(rng.integers(len(data), size=12))
        pred = model(x[idx])
        loss = (criterion(pred, y[idx]) * w[idx]).mean()
        opt.zero_grad()
        loss.backward()
        opt.step()
        if step % 200 == 0:
            print(
                json.dumps(
                    {
                        "step": step,
                        "loss": float(loss.detach()),
                        "seconds": time.time() - start,
                    }
                ),
                flush=True,
            )
    model.eval()
    torch.save(model.state_dict(), out / "context-ink.pt")
    report = {
        "scope": "offline experiment; synthetic training and independent procedural seed validation only",
        "architecture": "original 2-scale encoder/decoder, 3 existing ink planes, full-res skip",
        "seed": 20261104,
        "steps": args.steps,
        "samples": args.samples,
        "sparseBlank": args.sparse_blank,
        "evaluationResumedFromCheckpoint": args.resume_evaluation,
        "parameters": sum(v.numel() for v in model.parameters()),
        "validation": evaluate(model, 811735, 100),
        "promote": False,
        "seconds": time.time() - start,
        "blockers": [
            "no held-out real foreground masks",
            "no Android/physical-phone latency acceptance",
        ],
    }
    torch.onnx.export(
        Export(model),
        torch.zeros(1, 3, 96, 96),
        out / "context-ink.onnx",
        input_names=["ink_features"],
        output_names=["foreground"],
        opset_version=13,
        dynamo=False,
        dynamic_axes={
            "ink_features": {2: "height", 3: "width"},
            "foreground": {2: "height", 3: "width"},
        },
    )
    report["sha256"] = hashlib.sha256(
        (out / "context-ink.onnx").read_bytes()
    ).hexdigest()
    for i, path in enumerate(args.photo):
        raw = cv2.imread(str(path), 0)
        if raw is None:
            raise ValueError(path)
        scale = min(1, 960 / max(raw.shape))
        small = cv2.resize(
            raw, (round(raw.shape[1] * scale), round(raw.shape[0] * scale))
        )
        win = max(7, int(min(small.shape) * 0.035) | 1)
        field = cv2.medianBlur(cv2.dilate(small, np.ones((3, 3), "uint8")), win)
        ink = np.clip(
            1 - small.astype("float32") / np.maximum(20, field.astype("float32")), 0, 1
        )
        with torch.inference_mode():
            score = model(torch.from_numpy(features(ink)[None])).sigmoid()[0, 0].numpy()
        cv2.imwrite(
            str(out / f"photo-{i}-score.png"), np.round(score * 255).astype("uint8")
        )
        # Experimental visualization only, not the Android enhancer. Keep observed dark cores.
        keep = np.maximum(score, np.clip((ink - 0.2) / 0.15, 0, 1))
        cv2.imwrite(
            str(out / f"photo-{i}-offline-proposal.png"),
            np.round(255 * (1 - ink * keep)).astype("uint8"),
        )
        report.setdefault("photoInputs", []).append(
            {
                "path": str(path),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "label": "unlabelled real evaluation; offline proposal, NOT APK output",
            }
        )
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
