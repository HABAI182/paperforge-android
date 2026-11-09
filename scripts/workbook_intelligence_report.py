"""Report actual Android WORKBOOK outputs; never substitutes a Python enhancement pipeline.

Input: adb run-as tar extraction containing files/workbook-intelligence and workbook-quality.
Optional reference-dir: three JPEGs extracted from the owner's full 本地样本应用 PDF.
Private photos and outputs must stay outside the public repository.
"""
import argparse
import hashlib
import json
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageOps
import scan_flat_contact as existing


def image(path):
    return Image.open(path).convert("RGB")


def sheet(items, output, width=360, height=600):
    canvas = Image.new("RGB", (len(items) * width, height + 40), "#f2f2ee")
    draw = ImageDraw.Draw(canvas)
    for index, (label, path) in enumerate(items):
        im = image(path)
        im.thumbnail((width - 16, height))
        canvas.paste(im, (index * width + (width - im.width) // 2, 35))
        draw.text((index * width + 9, 10), label, fill="#19332d")
    canvas.save(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reference-dir", type=Path)
    args = parser.parse_args()
    evidence = args.evidence / "files" / "workbook-intelligence"
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    real = json.loads((evidence / "real-metrics.json").read_text())
    results = {"scope": "real user photos processed on Android emulator; no physical phone acceptance",
               "cleanGroundTruth": False, "uniquePhotos": real["uniquePhotos"], "photos": [], "timing": {}}
    policies = [(-2, "AUTO"), (-1, "Legacy WORKBOOK"), (0, "Conservative"), (1, "Standard"), (2, "Strong")]
    for source in sorted(evidence.glob("real-*-source.png")):
        name = source.name[len("real-"):-len("-source.png")]
        files = [(label, evidence / f"real-{name}-policy-{policy}.png") for policy, label in policies]
        sheet([("Source crop", source)] + files, out / f"{name}-all.png")
        row = {"input": name, "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(), "appearance": {}}
        gray = cv2.cvtColor(np.asarray(image(source)), cv2.COLOR_RGB2GRAY)
        for label, path in files:
            candidate = cv2.cvtColor(np.asarray(image(path)), cv2.COLOR_RGB2GRAY)
            # Explicit proxies, not pixel-labelled ink recall, PSNR/SSIM truth, or OCR accuracy.
            row["appearance"][label] = {"paperMean": float(candidate.mean()),
                "darkPixelsBelow150": int((candidate < 150).sum()),
                "ruleProxy": existing.line_metric(candidate)}
        geometry = evidence / f"real-{name}-geometry.png"
        if geometry.exists():
            sheet([("Source crop", source), ("Structure corrected", geometry)], out / f"{name}-geometry.png", 500, 760)
            row["geometryRuleProxy"] = existing.line_metric(existing.load(geometry, 900))
        if args.reference_dir and name.startswith("old-"):
            ref = args.reference_dir / f"page-{name[-1]}.jpeg"
            if ref.exists():
                combined_before=evidence / f"real-{name}-combined-0.png"
                combined_after=evidence / f"real-{name}-combined-1.png"
                if combined_before.exists() and combined_after.exists():
                    ref_before=("Legacy look / new geometry",combined_before)
                    ref_after=("New look / new geometry",combined_after)
                else:
                    ref_before,ref_after=files[1],files[2]
                sheet([("Full-app reference", ref), ref_before, ref_after], out / f"{name}-full-reference.png", 500, 760)
                target = existing.load(ref, 900)
                baseline = existing.load(ref_before[1], 900)
                current = existing.load(ref_after[1], 900)
                row["referenceProxy"] = existing.shared_geometry_visual(target, baseline, current)
                row["referenceScope"] = "same photo, full-app embedded JPEG; SIFT/RANSAC; shared geometry/homography; legacy appearance on new geometry, not complete old pipeline; not clean truth"
        results["photos"].append(row)
    for policy, label in policies:
        times = [row["milliseconds"] for row in real["rows"] if row.get("policy") == policy]
        results["timing"][label] = {"count": len(times), "p50ms": float(np.median(times)), "p95ms": float(np.percentile(times, 95))}
    results["timingScope"] = "1600px maximum; ordered emulator runs, model/JIT cache not reset between policies; enhancement only, not total capture latency"
    results["stress"] = real["stress"]
    quality = args.evidence / "files" / "workbook-quality"
    results["originalSyntheticAcceptance"] = json.loads((quality / "metrics.json").read_text())
    for filename in ["synthetic-metrics.json", "geometry-metrics.json"]:
        results[filename] = json.loads((evidence / filename).read_text())
    sheet([("Source", quality / "workbook-source.png"), ("AUTO", quality / "workbook-auto.png"),
           ("New WORKBOOK", quality / "workbook-workbook.png")], out / "synthetic-original-acceptance.png", 500, 760)
    sheet([("Synthetic source", evidence / "synthetic-source.png")] +
          [(label, evidence / f"synthetic-policy-{policy}.png") for policy, label in policies if policy >= -1],
          out / "synthetic-protected-content.png")
    unsafe=evidence / "synthetic-policy-3.png"
    if unsafe.exists():
        failures=out / "failures";failures.mkdir(exist_ok=True)
        sheet([("Source",evidence / "synthetic-source.png"),("Conservative safeguard",evidence / "synthetic-policy-0.png"),
               ("Unsafe standard: safeguard OFF",unsafe)],failures / "unsafe-faint-ink.png",500,760)
        (failures / "README.md").write_text("Negative control: experimental safeguard OFF. Read unsafeAblation=true in synthetic-metrics.json. This is a retained failed experiment, not an accepted shipping policy.\n",encoding="utf-8")
    sheet([("Known synthetic skew/shear", evidence / "geometry-source.png"),
           ("Composed correction", evidence / "geometry-intelligent.png")], out / "synthetic-geometry.png", 500, 760)
    (out / "metrics.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = ["# WORKBOOK measured outputs", "", results["scope"], "",
             "No clean real-photo ink masks, OCR transcripts, manual angle annotations or blind review are available.",
             "Dark pixel count / rule tracks / reference alignment are diagnostic proxies, not semantic recall.", "",
             "| Enhancement | P50 ms | P95 ms |", "|---|---:|---:|"]
    for label, timing in results["timing"].items():
        lines.append(f"| {label} | {timing['p50ms']:.1f} | {timing['p95ms']:.1f} |")
    lines += ["", results["timingScope"], "", f"Stress: {real['stress']['invocations']} repeated invocations over {real['uniquePhotos']} unique photos, 800px.",
              "Sampled native heap is neither total process PSS nor a true allocation peak.", "",
              "Read each -all.png at full size, compare faint symbols and figures before judging whiteness.",
              "Use -full-reference.png for the three paired full-app reference pages. Regressions must remain visible."]
    (out / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"photos": len(results["photos"]), "timing": results["timing"], "output": str(out)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
