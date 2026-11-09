"""Owner book photo: actual Android outputs, no replacement enhancer or generated text.
Reference/rejected pages are cropped owner screenshots, not clean-image ground truth.
Private evidence must stay outside the checkout.
"""
import argparse
import json
from pathlib import Path

import scan_flat_contact as metrics
from workbook_counterexample_report import sheet


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    args = parser.parse_args()
    root = args.folder
    actual = root / "actual/files/november-feedback"
    output = root / "report"
    output.mkdir(exist_ok=True)
    variants = [("Reference / screenshot", root / "reference-page.png"),
                ("Rejected app / screenshot", root / "rejected-page.png"),
                ("Legacy tone / new geometry", actual / "legacy-tone.png"),
                ("New automatic / Android", actual / "new-automatic.png")]
    sheet([(name, path, None) for name, path in variants], output / "contact-full.png")
    for name, roi in [("upper-code", (.08, .025, .94, .21)),
                      ("finger-edge", (0, .27, .26, .61)),
                      ("background", (.28, .23, .9, .42))]:
        sheet([(label, path, roi) for label, path in variants], output / (name + ".png"), (440, 430))
    ref = metrics.load(variants[0][1], 800)
    comparisons = {label: metrics.compare(ref, metrics.load(path, 800))
                   for label, path in variants[1:]}
    report = {"scope": "real owner book, Android emulator; screenshot registration proxies",
              "cleanInkTruth": False, "physicalPhone": False,
              "limitations": ["Different source/crop/geometry registered separately",
                              "No manual corners, ink masks or formula transcript",
                              "Legacy-tone ablation uses NEW geometry and boundary cleanup",
                              "Ordered timings include warm cache effects; do not rank cold devices"],
              "run": json.loads((actual / "metrics.json").read_text(encoding="utf-8")),
              "referenceProxies": comparisons}
    (output / "metrics.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(comparisons, indent=2))


if __name__ == "__main__":
    main()
