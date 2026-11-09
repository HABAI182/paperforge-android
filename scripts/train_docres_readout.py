"""Tune a separate foreground readout of the pinned MIT DocRes backbone.

The backbone stays frozen and unchanged. Page-disjoint weak reference masks plus
procedural faint strokes supervise a small replacement foreground head. It is an
offline candidate, not a replacement for the app's multi-task model. Saves no
owner photo or weight into the repository.
"""

from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import time

import cv2
import numpy as np
import torch
from torch import nn

from paper_restoration_lab import prompt, WEIGHT_SHA
from train_paper_context import fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--steps", type=int, default=2500)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    out = args.out.resolve()
    assert out != repo and repo not in out.parents
    out.mkdir(parents=True, exist_ok=True)
    cv2.setNumThreads(1)
    torch.set_num_threads(4)
    torch.manual_seed(20261109)
    rng = np.random.default_rng(20261109)
    spec = importlib.util.spec_from_file_location(
        "readout_docres", args.source / "restormer_arch.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    checkpoint = args.source / "docres.pkl"
    assert hashlib.sha256(checkpoint.read_bytes()).hexdigest() == WEIGHT_SHA
    model = module.Restormer(
        inp_channels=6,
        out_channels=3,
        dim=48,
        num_blocks=[2, 3, 3, 4],
        num_refinement_blocks=4,
        heads=[1, 2, 4, 8],
        ffn_expansion_factor=2.66,
        bias=False,
        LayerNorm_type="WithBias",
        dual_pixel_task=True,
    )
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)["model_state"]
    model.load_state_dict({k.removeprefix("module."): v for k, v in state.items()})
    model.eval().cuda().half()
    model.requires_grad_(False)
    captured = []
    hook = model.output.register_forward_pre_hook(
        lambda _, inputs: captured.append(inputs[0])
    )

    def encode(image):
        h, w = image.shape[:2]
        data = (
            np.concatenate([image, prompt(image, "foreground")], 2).astype("float32")
            / 255
        )
        data = np.pad(data, ((0, (-h) % 8), (0, (-w) % 8), (0, 0)), mode="edge")
        with torch.inference_mode():
            logits = model(
                torch.from_numpy(data.transpose(2, 0, 1)[None]).cuda().half()
            )
        features = captured.pop()[0, :, :h, :w].clone()
        original = (
            (logits[0, 0, :h, :w] - logits[0, 1, :h, :w])
            .sigmoid()
            .float()
            .cpu()
            .numpy()
        )
        return features, original

    rows = json.loads((args.corpus / "dataset.json").read_text())["rows"]
    cache = []
    real = []
    start = time.time()
    for row in rows:
        folder = args.corpus / row["id"]
        gray = cv2.imread(str(folder / "source.png"), 0)
        weak = cv2.imread(str(folder / "weak-labels.png"), 0)
        image = cv2.imread(row["source"])
        image = cv2.resize(image, gray.shape[::-1])
        encoded, original = encode(image)
        y = (weak == 255).astype("float32")
        weights = (weak == 255).astype("float32") * 16 + (weak == 0).astype(
            "float32"
        ) * 4
        real.append(
            dict(
                id=row["id"],
                split=row["split"],
                x=encoded,
                y=y,
                w=weights,
                weak=weak,
                old=original,
            )
        )
        if row["split"] == "train":
            cache.append(real[-1])
        print(
            "encoded",
            row["id"],
            tuple(encoded.shape),
            round(time.time() - start, 2),
            flush=True,
        )
    for i in range(96):
        x, y, weights, _, contrast = fixture(rng, sparse=True)
        ink = x[0]
        image = np.repeat(np.round(255 * (1 - ink))[:, :, None], 3, axis=2).astype(
            "uint8"
        )
        encoded, _ = encode(image)
        if contrast < 0.12:
            weights = 0.1 + y * 60
        cache.append(dict(x=encoded, y=y[0], w=weights[0]))
    hook.remove()
    head = nn.Conv2d(96, 1, 3, padding=1, bias=False).cuda()
    head.weight.data.copy_((model.output.weight[:1] - model.output.weight[1:2]).float())
    optimizer = torch.optim.AdamW(head.parameters(), lr=0.00015)
    criterion = nn.BCEWithLogitsLoss(reduction="none")
    history = []
    for step in range(args.steps):
        patches, labels, weights = [], [], []
        for _ in range(12):
            row = (
                cache[int(rng.integers(len(cache)))]
                if rng.random() < 0.5
                else cache[int(rng.integers(2))]
            )
            h, w = row["y"].shape
            positive = np.argwhere(row["w"] > 0)
            cy, cx = positive[int(rng.integers(len(positive)))]
            top, left = (
                int(np.clip(cy - 48, 0, h - 96)),
                int(np.clip(cx - 48, 0, w - 96)),
            )
            patches.append(row["x"][:, top : top + 96, left : left + 96])
            labels.append(row["y"][None, top : top + 96, left : left + 96])
            weights.append(row["w"][None, top : top + 96, left : left + 96])
        x = torch.stack(patches).float()
        y = torch.from_numpy(np.stack(labels)).cuda()
        weight = torch.from_numpy(np.stack(weights)).cuda()
        logits = head(x)
        loss = (criterion(logits, y) * weight).mean()
        optimizer.zero_grad()
        loss.backward()
        optimizer.step()
        if step % 500 == 0:
            history.append(
                dict(step=step, loss=float(loss.detach()), seconds=time.time() - start)
            )
            print(history[-1], flush=True)
    head.eval()
    report = dict(
        scope="weak reference supervision and procedural faint positives, not manually annotated character truth",
        source="https://github.com/ZZZHANG-jx/DocRes",
        revision="d3e3a18c7c7ad10e4615ca2eb6a10f83128d8560",
        license="MIT; source checkpoint unchanged",
        backboneSha256=WEIGHT_SHA,
        parameters=sum(p.numel() for p in head.parameters()),
        steps=args.steps,
        seconds=time.time() - start,
        history=history,
        integrated=False,
        real=[],
    )
    with torch.inference_mode():
        for row in real:
            score = head(row["x"][None].float()).sigmoid()[0, 0].cpu().numpy()
            weak = row["weak"]
            metrics = []
            for threshold in [0.005, 0.01, 0.02, 0.05, 0.1, 0.5]:
                metrics.append(
                    dict(
                        threshold=threshold,
                        weakPositiveRetention=float(
                            (score[weak == 255] >= threshold).mean()
                        ),
                        weakNegativeRejected=float(
                            (score[weak == 0] < threshold).mean()
                        ),
                        originalWeakPositiveRetention=float(
                            (row["old"][weak == 255] >= threshold).mean()
                        ),
                        originalWeakNegativeRejected=float(
                            (row["old"][weak == 0] < threshold).mean()
                        ),
                    )
                )
            folder = out / row["id"]
            folder.mkdir(exist_ok=True)
            np.save(folder / "score.npy", score)
            cv2.imwrite(
                str(folder / "foreground.png"),
                np.round(255 * (1 - score)).astype("uint8"),
            )
            report["real"].append(
                dict(id=row["id"], split=row["split"], thresholds=metrics)
            )
    head.cpu()
    torch.save(head.state_dict(), out / "foreground-readout.pt")
    torch.onnx.export(
        nn.Sequential(head, nn.Sigmoid()),
        torch.zeros(1, 96, 73, 101),
        out / "foreground-readout.onnx",
        input_names=["docres_features"],
        output_names=["foreground"],
        opset_version=13,
        dynamo=False,
        dynamic_axes={
            "docres_features": {2: "height", 3: "width"},
            "foreground": {2: "height", 3: "width"},
        },
    )
    report["sha256"] = hashlib.sha256(
        (out / "foreground-readout.onnx").read_bytes()
    ).hexdigest()
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print([(r["id"], r["thresholds"][2]) for r in report["real"]], flush=True)


if __name__ == "__main__":
    main()
