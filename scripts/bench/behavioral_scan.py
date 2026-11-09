"""Run paired, private photo comparisons against the actual Kotlin scanner.

Input manifest: cases[{id,input,referencePdf,referencePage,quadPixels?,annotationUncertaintyPx?}],
sweeps[{id,engine?,crop?,params?}]. Paths are relative to the input manifest.
No reverse-engineering directories, binaries or models are read by this tool.
"""
import argparse
import hashlib
import io
import json
import math
import shutil
import subprocess
import tarfile
from pathlib import Path

import cv2
import numpy as np
import pymupdf
from PIL import Image, ImageDraw, ImageOps


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(manifest_path, work):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    device = work / "device"
    device.mkdir(parents=True, exist_ok=True)
    cases = []
    for case in manifest["cases"]:
        case = dict(case)
        source = (manifest_path.parent / case["input"]).resolve()
        pdf = (manifest_path.parent / case["referencePdf"]).resolve()
        name = case["id"] + source.suffix.lower()
        shutil.copyfile(source, device / name)
        with pymupdf.open(pdf) as doc:
            page = doc[case["referencePage"] - 1]
            pix = page.get_pixmap(matrix=pymupdf.Matrix(1.5, 1.5), alpha=False)
            pix.save(str(work / (case["id"] + "-reference.png")))
        with Image.open(source) as image:
            w, h = image.size
        case.update(input=name, sourceSha256=sha(source), referenceSha256=sha(pdf), width=w, height=h)
        if "quadPixels" in case:
            case["manualQuad"] = [[x / (w - 1), y / (h - 1)] for x, y in case["quadPixels"]]
        cases.append(case)
    ready = dict(manifest, cases=cases)
    (work / "manifest.json").write_text(json.dumps(ready, indent=2, ensure_ascii=False), encoding="utf-8")
    (device / "manifest.json").write_text(json.dumps(ready, ensure_ascii=False), encoding="utf-8")


def run_device(adb, package, work, classes):
    def call(*args, **kwargs):
        return subprocess.run([adb, *args], check=True, **kwargs)
    for file in (work / "device").iterdir():
        remote = "/data/local/tmp/behavior-" + file.name
        call("push", str(file), remote, stdout=subprocess.DEVNULL)
        call("shell", "chmod", "644", remote)
        call("shell", "run-as", package, "mkdir", "-p", "files/behavioral-v1")
        call("shell", f"run-as {package} sh -c 'cat {remote} > files/behavioral-v1/{file.name}'")
    result = call("shell", "am", "instrument", "-w", "-r", "-e", "class",
                  classes,
                  package + ".test/androidx.test.runner.AndroidJUnitRunner", capture_output=True)
    log = result.stdout.decode("utf-8", "replace")
    (work / "instrumentation.txt").write_text(log, encoding="utf-8")
    print(log)
    archive = call("exec-out", "run-as", package, "tar", "-cf", "-", "files/behavioral-v1/output", "files/filter-regression", capture_output=True).stdout
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar:
            if member.isfile():
                path = Path(member.name)
                if path.is_absolute() or ".." in path.parts:
                    raise ValueError("Unsafe archive path")
                target = work / "readback" / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(tar.extractfile(member).read())
    if "FAILURES!!!" in log or f"OK ({len(classes.split(','))} test{'' if len(classes.split(',')) == 1 else 's'})" not in log:
        raise RuntimeError("Focused checks did not all pass; evidence was collected in readback/")


def geometry(prediction, truth, w, h):
    p = np.asarray(prediction, np.float32) * [w - 1, h - 1]
    t = np.asarray(truth, np.float32)
    options = [np.roll(q, i, axis=0) for q in (t, t[::-1]) for i in range(4)]
    error = min(float(np.linalg.norm(p - q, axis=1).mean()) for q in options)
    p = cv2.convexHull(p.astype(np.float32)); t = cv2.convexHull(t)
    a, b = cv2.contourArea(p), cv2.contourArea(t)
    intersection = float(cv2.intersectConvexConvex(p, t)[0])
    return {"cornerErrorPx": error, "cornerErrorDiagonalPercent": 100 * error / math.hypot(w, h),
            "quadIoU": intersection / max(1, a + b - intersection)}


def visual(reference, ours):
    # Register content, not UI/watermark/margins. A registration failure is a measured gap.
    ref = cv2.imread(str(reference), cv2.IMREAD_GRAYSCALE)
    ref = cv2.resize(ref, (640, round(ref.shape[0] * 640 / ref.shape[1])))
    ref = ref[:round(ref.shape[0] * .91)]
    image = cv2.imread(str(ours), cv2.IMREAD_GRAYSCALE)
    image = cv2.resize(image, (ref.shape[1], ref.shape[0]))
    sift = cv2.SIFT_create(nfeatures=1800)
    ka, da = sift.detectAndCompute(image, None); kb, db = sift.detectAndCompute(ref, None)
    if da is None or db is None:
        return {"registration": "insufficient-features"}
    matches = [pair[0] for pair in cv2.BFMatcher().knnMatch(da, db, k=2) if len(pair) == 2 and pair[0].distance < .72 * pair[1].distance]
    if len(matches) < 10:
        return {"registration": "insufficient-matches", "matches": len(matches)}
    h, inliers = cv2.findHomography(np.float32([ka[m.queryIdx].pt for m in matches]),
                                    np.float32([kb[m.trainIdx].pt for m in matches]), cv2.RANSAC, 3)
    if h is None or int(inliers.sum()) < 10:
        return {"registration": "unstable", "matches": len(matches)}
    aligned = cv2.warpPerspective(image, h, (ref.shape[1], ref.shape[0]), borderValue=255)
    mask = cv2.warpPerspective(np.ones_like(image, np.uint8), h, (ref.shape[1], ref.shape[0]), flags=cv2.INTER_NEAREST) > 0
    # Report coverage so a tiny matching patch cannot masquerade as full-page fidelity.
    ink_ref = (ref < 160) & mask
    ink_ours = (aligned < 160) & mask
    near_ref = cv2.dilate(ink_ref.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
    near_ours = cv2.dilate(ink_ours.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
    return {"registration": "homography-proxy", "inliers": int(inliers.sum()), "coverage": float(mask.mean()),
            "registeredMAE255": float(np.abs(ref.astype(float) - aligned)[mask].mean()),
            "inkRecallProxy": float((ink_ref & near_ours).sum() / max(1, ink_ref.sum())),
            "inkPrecisionProxy": float((ink_ours & near_ref).sum() / max(1, ink_ours.sum()))}


def report(work):
    manifest = json.loads((work / "manifest.json").read_text(encoding="utf-8"))
    folder = work / "readback/files/behavioral-v1/output"
    rows = json.loads((folder / "results.json").read_text(encoding="utf-8"))["cases"]
    samples = {c["id"]: c for c in manifest["cases"]}
    for row in rows:
        sample = samples[row["id"]]
        if "quadPixels" in sample:
            row["geometryAgainstApproximateManualCorners"] = geometry(row["quad"], sample["quadPixels"], sample["width"], sample["height"])
        row["visual"] = {}
        reference = work / (row["id"] + "-reference.png")
        names = ["crop", "dewarp"] + [x.lower() for x in row.get("modes", ["AUTO", "TEXT", "BW", "WORKBOOK"])]
        paths = [reference] + [folder / (row["id"] + "-" + row["sweep"] + "-" + n + ".png") for n in names]
        canvas = Image.new("RGB", (350 * len(paths), 565), "#eef0ed")
        draw = ImageDraw.Draw(canvas)
        for i, (name, path) in enumerate(zip(["REFERENCE"] + names, paths)):
            with Image.open(path) as image:
                image = ImageOps.contain(image.convert("RGB"), (334, 505))
                canvas.paste(image, (350 * i + (350 - image.width) // 2, 42))
            draw.text((350 * i + 12, 12), name.upper(), fill="#183e36")
            if i >= 3:
                row["visual"][name] = visual(reference, path)
        canvas.save(work / (row["id"] + "-" + row["sweep"] + "-contact.jpg"), quality=92)
    (work / "gaps.json").write_text(json.dumps({"limitations": ["Three paired user photos; not a population benchmark", "Manual corners are approximate and include occlusion uncertainty", "Registration metrics are proxies, not text correctness or pixel-equivalent geometry"], "rows": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(rows, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["prepare", "run", "report"])
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--classes", default="cn.paperforge.app.BehavioralScanBenchmarkTest,cn.paperforge.app.LibraryFolderRecoveryTest,cn.paperforge.app.quality.FilterRegressionSampleTest")
    parser.add_argument("--package", default="cn.paperforge.app")
    args = parser.parse_args(); args.work.mkdir(parents=True, exist_ok=True)
    if args.mode == "prepare": prepare(args.manifest.resolve(), args.work)
    elif args.mode == "run": run_device(args.adb, args.package, args.work, args.classes)
    else: report(args.work)
