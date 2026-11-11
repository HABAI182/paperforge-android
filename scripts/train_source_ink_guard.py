"""Source-evidence confidence for tone gain, with no character generation.

Synthetic recto/verso compositing only; owner photos never enter training.
Low front confidence limits extra darkening. The existing tone can still
lighten a source stroke, so the gain bound is not a semantic retention metric.
Real reference alignment is evaluation-only and must not be used as a label.
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
from PIL import Image, ImageDraw, ImageFont

from train_paper_context import ContextInk, Export
from train_paper_ink import layer


def evidence(ink):
    half = cv2.GaussianBlur(ink, (0, 0), 0.6)
    middle = cv2.GaussianBlur(ink, (0, 0), 1.5)
    broad = cv2.GaussianBlur(ink, (0, 0), 4)
    peak = cv2.dilate(ink, np.ones((3, 3), "uint8"))
    return np.stack([ink, half, middle, broad, ink - half, peak]).astype("float32")


def glyphs(rng, font_path, n=128):
    image = Image.new("L", (n, n), 0)
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(str(font_path), int(rng.integers(9, 23)))
    words = [
        "概率统计练习",
        "保留淡字标点",
        "输入数组函数",
        "int main(void)",
        "x-1 .,:; 2.14",
        "sin x + 1/2",
        "P(X>2)=1-e",
    ]
    gap = int(font.size * rng.uniform(1.7, 2.8))
    for y in range(int(rng.integers(-15, 5)), n, gap):
        draw.text(
            (int(rng.integers(-40, 6)), y), str(rng.choice(words)), font=font, fill=255
        )
    return np.asarray(image).astype("float32") / 255


def fixture(rng, font_path, n=128, heldout=False):
    front = glyphs(rng, font_path, n) if rng.random() < 0.7 else layer(rng, n)
    back = glyphs(rng, font_path, n) if rng.random() < 0.7 else layer(rng, n)
    back = np.ascontiguousarray(back[:, ::-1])
    front = cv2.GaussianBlur(front, (0, 0), float(rng.uniform(0.2, 1.05)))
    back = cv2.GaussianBlur(back, (0, 0), float(rng.uniform(0.65, 3.5)))
    front_contrast = float(
        rng.choice([0.04, 0.07, 0.12, 0.2, 0.45, 0.8])
    ) * rng.uniform(0.75, 1.2)
    f = front * front_contrast
    if rng.random() < 0.3:
        f *= 0
    b = back * float(rng.uniform(0.006, 0.25))
    if rng.random() < 0.15:
        b *= 0
    noise = np.maximum(0, rng.normal(0, rng.uniform(0, 0.004), (n, n))).astype(
        "float32"
    )
    observed = np.clip(f + b * (1 - f) + noise, 0, 1)
    if heldout:
        # Different handedness/scales are not new real-page ground truth.
        angle = float(rng.uniform(-8, 8))
        m = cv2.getRotationMatrix2D((n / 2, n / 2), angle, 1)
        observed = cv2.warpAffine(observed, m, (n, n))
        f = cv2.warpAffine(f, m, (n, n))
        b = cv2.warpAffine(b, m, (n, n))
    target = (f > max(0.002, front_contrast * 0.08)).astype("float32")
    support = observed > 0.004
    weight = np.where(target > 0, 32 if front_contrast < 0.15 else 12, 4).astype(
        "float32"
    )
    weight[~support] = 0.03
    return evidence(observed), target[None], weight[None], f, b, front_contrast


def limit_gain(source_ink, rendered_ink, score, front_cut=0.35, full_gain=0.85):
    """Bound darkening while retaining the existing tone's possible lightening.

    At low confidence the existing tone may clean a pixel, but cannot darken it
    past source contrast. Model confidence is not a semantic guarantee.
    """
    t = np.clip((score - front_cut) / max(1e-6, full_gain - front_cut), 0, 1)
    t = t * t * (3 - 2 * t)
    return np.minimum(rendered_ink, source_ink + (1 - source_ink) * t)


def summarize_metrics(rows, gain_rows, front_cut=0.35, full_gain=0.85):
    """Separate score classification thresholds from one fixed rendering bound."""
    assert rows and gain_rows
    aggregates = []
    for cut in sorted({r["threshold"] for r in rows}):
        selected = [r for r in rows if r["threshold"] == cut]
        aggregates.append(
            {
                "threshold": cut,
                "frontRecall": sum(r["frontRetained"] for r in selected)
                / max(1, sum(r["positivePixels"] for r in selected)),
                "reverseBelowFrontThreshold": sum(
                    r["reverseBelowFrontThreshold"] for r in selected
                )
                / max(1, sum(r["negativePixels"] for r in selected)),
            }
        )
    pixel_counts_available = all(
        "reversePixelsWithPositiveGainBefore" in r
        and "reversePixelsWithPositiveGainAfter" in r
        for r in gain_rows
    )
    pixel_counts = (
        {
            "status": "available",
            "epsilon": 1e-6,
            "before": sum(r["reversePixelsWithPositiveGainBefore"] for r in gain_rows),
            "after": sum(r["reversePixelsWithPositiveGainAfter"] for r in gain_rows),
        }
        if pixel_counts_available
        else {
            "status": "unavailable",
            "reason": "The original report stored means and score-threshold counts, not pixelwise gain counts.",
        }
    )
    return {
        "metricsSchemaVersion": 2,
        "metricSemantics": {
            "thresholds": "Classification of synthetic front/reverse labels by confidence score; not rendered stroke retention.",
            "frontRetained": "Count of positive-label pixels with score >= threshold.",
            "reverseBelowFrontThreshold": "Count or fraction of negative-label pixels with score < threshold; not absence of rendered darkening.",
        },
        "thresholds": aggregates,
        "rows": rows,
        "gainBound": {
            "parameters": {"frontCut": front_cut, "fullGain": full_gain},
            "existingToneExample": "clip(sourceInk * 5, 0, 1)",
            "constructionInvariant": True,
            "notSemanticRetention": True,
            "lowerBoundDefinition": "boundedInk >= min(sourceInk, existingToneInk) - 1e-6 on positive-label pixels",
            "sourceOrExistingToneLowerBoundSatisfied": all(
                r["sourceOrExistingToneLowerBoundSatisfied"] for r in gain_rows
            ),
            "reverseDarkeningAggregation": "Mean of per-scene reverse-pixel mean positive gain; scenes without reverse pixels contribute zero.",
            "reverseDarkeningBefore": float(
                np.mean([r["reverseDarkeningBefore"] for r in gain_rows])
            ),
            "reverseDarkeningAfter": float(
                np.mean([r["reverseDarkeningAfter"] for r in gain_rows])
            ),
            "pixelGainCounts": pixel_counts,
            "rows": gain_rows,
        },
    }


def migrate_report_metrics(report):
    """Reorganize existing metrics without inference or invented pixel counts."""
    result = json.loads(json.dumps(report))
    if result.get("metricsSchemaVersion") == 2:
        return result
    legacy_rows = result["rows"]
    cuts = [r["threshold"] for r in result["thresholds"]]
    assert cuts and len(legacy_rows) % len(cuts) == 0
    rows, gain_rows = [], []
    for first in range(0, len(legacy_rows), len(cuts)):
        group = legacy_rows[first : first + len(cuts)]
        assert [r["threshold"] for r in group] == cuts
        base = group[0]
        shared = (
            "faint", "positivePixels", "negativePixels",
            "sourceRetentionByGainBound", "reverseDarkeningBefore", "reverseDarkeningAfter",
        )
        assert all(all(r[k] == base[k] for k in shared) for r in group)
        gain_rows.append(
            {
                "sampleIndex": first // len(cuts),
                "faint": base["faint"],
                "positivePixels": base["positivePixels"],
                "negativePixels": base["negativePixels"],
                "sourceOrExistingToneLowerBoundSatisfied": base["sourceRetentionByGainBound"],
                "reverseDarkeningBefore": base["reverseDarkeningBefore"],
                "reverseDarkeningAfter": base["reverseDarkeningAfter"],
            }
        )
        for r in group:
            rows.append(
                {
                    "sampleIndex": first // len(cuts),
                    "threshold": r["threshold"],
                    "faint": r["faint"],
                    "positivePixels": r["positivePixels"],
                    "negativePixels": r["negativePixels"],
                    "frontRetained": r["frontRetained"],
                    "reverseBelowFrontThreshold": r["reverseNotEnhanced"],
                }
            )
    metrics = summarize_metrics(rows, gain_rows)
    # Preserve every previously reported number; only its scope/name changes.
    for before, after in zip(result["thresholds"], metrics["thresholds"]):
        assert before["frontRecall"] == after["frontRecall"]
        assert before["reverseNotEnhanced"] == after["reverseBelowFrontThreshold"]
        assert before["reverseDarkeningBefore"] == metrics["gainBound"]["reverseDarkeningBefore"]
        assert before["reverseDarkeningAfter"] == metrics["gainBound"]["reverseDarkeningAfter"]
    result.update(metrics)
    result["metricsMigration"] = {
        "fromSchemaVersion": 1,
        "retrained": False,
        "modelReexported": False,
        "pixelwiseStatisticsRecomputed": False,
    }
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--font", type=Path, required=True)
    p.add_argument("--validation-font", type=Path, required=True)
    p.add_argument("--steps", type=int, default=5000)
    p.add_argument("--samples", type=int, default=2048)
    a = p.parse_args()
    out = a.out.resolve()
    repo = Path(__file__).resolve().parents[1]
    assert out != repo and repo not in out.parents
    out.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(4)
    cv2.setNumThreads(1)
    torch.manual_seed(20261110)
    rng = np.random.default_rng(20261110)
    samples = [fixture(rng, a.font)[:3] for _ in range(a.samples)]
    x, y, w = [
        torch.from_numpy(np.stack([r[k] for r in samples])).cuda() for k in range(3)
    ]
    model = ContextInk(channels=6, dilation=2).cuda()
    opt = torch.optim.AdamW(model.parameters(), lr=0.0005)
    fn = nn.BCEWithLogitsLoss(reduction="none")
    started = time.perf_counter()
    for step in range(1, a.steps + 1):
        ids = torch.from_numpy(rng.integers(a.samples, size=16)).cuda()
        loss = (fn(model(x[ids]), y[ids]) * w[ids]).mean()
        opt.zero_grad(set_to_none=True)
        loss.backward()
        opt.step()
        if step % 250 == 0:
            print(
                json.dumps(
                    {
                        "step": step,
                        "total": a.steps,
                        "loss": float(loss.detach()),
                        "seconds": round(time.perf_counter() - started, 1),
                    }
                ),
                flush=True,
            )
    model.eval().cpu()
    torch.save(model.state_dict(), out / "source-ink.pt")
    val = np.random.default_rng(234731)
    rows = []
    gain_rows = []
    with torch.inference_mode():
        for sample_index in range(160):
            x, y, w, front, back, contrast = fixture(
                val, a.validation_font, heldout=True
            )
            score = model(torch.from_numpy(x[None])).sigmoid()[0, 0].numpy()
            positive = y[0] > 0
            negative = (back > 0.005) & ~positive
            # Existing steep tone, only for a controlled darkening counterexample.
            source = x[0]
            old = np.clip(source * 5, 0, 1)
            bounded = limit_gain(source, old, score)
            gain_rows.append(
                {
                    "sampleIndex": sample_index,
                    "faint": contrast < 0.15,
                    "positivePixels": int(positive.sum()),
                    "negativePixels": int(negative.sum()),
                    "sourceOrExistingToneLowerBoundSatisfied": bool(
                        (bounded[positive] >= np.minimum(source, old)[positive] - 1e-6).all()
                    ),
                    "reverseDarkeningBefore": float(np.maximum(0, old - source)[negative].mean())
                    if negative.any() else 0,
                    "reverseDarkeningAfter": float(np.maximum(0, bounded - source)[negative].mean())
                    if negative.any() else 0,
                    "reversePixelsWithPositiveGainBefore": int(((old - source > 1e-6) & negative).sum()),
                    "reversePixelsWithPositiveGainAfter": int(((bounded - source > 1e-6) & negative).sum()),
                }
            )
            for cut in [0.02, 0.1, 0.35, 0.5, 0.85]:
                rows.append(
                    {
                        "sampleIndex": sample_index,
                        "threshold": cut,
                        "faint": contrast < 0.15,
                        "positivePixels": int(positive.sum()),
                        "negativePixels": int(negative.sum()),
                        "frontRetained": int(((score >= cut) & positive).sum()),
                        "reverseBelowFrontThreshold": int(((score < cut) & negative).sum()),
                    }
                )
    torch.onnx.export(
        Export(model),
        torch.zeros(1, 6, 73, 101),
        out / "source-ink.onnx",
        input_names=["source_evidence"],
        output_names=["front_confidence"],
        opset_version=17,
        dynamo=False,
        dynamic_axes={
            "source_evidence": {2: "height", 3: "width"},
            "front_confidence": {2: "height", 3: "width"},
        },
    )
    metrics = summarize_metrics(rows, gain_rows)
    report = {
        "scope": "procedural recto/verso; real-photo accuracy unmeasured; confidence bounds gain, not hard erasure",
        "ownerPhotosUsedForTraining": False,
        "parameters": sum(p.numel() for p in model.parameters()),
        "steps": a.steps,
        "samples": a.samples,
        "seconds": time.perf_counter() - started,
        "integrated": False,
        "trainingFontSha256": hashlib.sha256(a.font.read_bytes()).hexdigest(),
        "validationFontSha256": hashlib.sha256(
            a.validation_font.read_bytes()
        ).hexdigest(),
        "sha256": hashlib.sha256((out / "source-ink.onnx").read_bytes()).hexdigest(),
        **metrics,
    }
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(metrics["thresholds"]), flush=True)


if __name__ == "__main__":
    main()
