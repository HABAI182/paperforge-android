"""Inspect real Android stages for the owner's rejected workbook photograph.

No replacement Python enhancer or generated text. Private input/output stays outside git.
All unlabelled metrics are proxies; they do not establish recall of real handwriting.
"""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageOps

import scan_flat_contact as existing


def sheet(items, target, size=(460, 660)):
    w, h = size
    result = Image.new("RGB", (w * len(items), h + 36), "#f2f2ee")
    draw = ImageDraw.Draw(result)
    for index, (label, path, crop) in enumerate(items):
        image = Image.open(path).convert("RGB")
        if crop:
            image = image.crop(tuple(round(v * (image.width if i % 2 == 0 else image.height))
                                     for i, v in enumerate(crop)))
        image = ImageOps.contain(image, (w - 12, h))
        result.paste(image, (index * w + (w - image.width) // 2, 36))
        draw.text((index * w + 8, 9), label, fill="#19332d")
    result.save(target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--rejected-output", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    root = args.evidence / "files/workbook-counterexample"
    measured = json.loads((root / "metrics.json").read_text(encoding="utf-8"))
    paths = [("Full app reference", args.reference), ("Rejected output", args.rejected_output),
             ("New / same crop", root / "rejected-crop-after-output.png"),
             ("New / original JPEG", root / "original-framing-after-output.png")]
    sheet([(label, path, None) for label, path in paths], args.output / "full-reference.png")
    # ROI images preserve each output's real pixels before display resizing. The full sheet is
    # the context; different geometries mean these are visual crops, not aligned pixel truth.
    for name, crop in [("top-formula", (0, 0, .7, .12)),
                       ("right-occlusion", (.85, .15, 1, .57)),
                       ("lower-left", (0, .85, .17, 1))]:
        sheet([(label, path, crop) for label, path in paths], args.output / f"{name}.png", (500, 340))
    reference = existing.load(args.reference, 900)
    proxies = {}
    for label, path in paths:
        gray = existing.load(path, 900)
        proxies[label] = {"ruleTracks": existing.line_metric(gray),
                          "darkPixelsBelow150": int((gray < 150).sum())}
        if path != args.reference:
            proxies[label]["referenceAlignment"] = existing.compare(reference, gray)
            proxies[label]["referenceSeededRules"] = existing.paired_rule_metric(reference, gray)
    measured["diagnosticProxies"] = proxies
    measured["metricLimitations"] = [
        "Each different geometry is registered separately to the reference; not same-transform appearance isolation.",
        "No real front-ink/show-through labels, OCR transcripts, or manual corner truth are supplied.",
        "A masked finger has no recoverable source handwriting; no strokes are synthesized there.",
        "Native ROI display and source must be reviewed; a whiter page is not sufficient acceptance.",
        "The reference has 2240x3164 pixels; the owner JPEG is 1279x1706. Upsampling is not recovered optical detail.",
    ]
    (args.output / "metrics.json").write_text(json.dumps(measured, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(proxies, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
