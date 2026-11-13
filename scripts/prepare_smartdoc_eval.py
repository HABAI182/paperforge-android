"""Independent evaluation set from SmartDoc 2015 Challenge 1 (ICDAR'15, Zenodo 10.5281/zenodo.1230218, CC BY 4.0).

150 tablet videos (6 document types x 5 documents x 5 backgrounds; background05 is cluttered), every frame annotated
with the document's corners. This takes `--per-video` evenly spaced, non-rejected frames from each video, writes
them as JPEGs and builds a crop-pipeline manifest plus a truth report (normalised TL, TR, BR, BL) in the format
score_crop_jvm.py --baseline expects. Evaluation only: never training, checkpoint selection or thresholds.

usage: prepare_smartdoc_eval.py <testDataset dir> <out dir> [--per-video 6]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path

import cv2


def frames(gt: Path):
    out = {}
    for f in ET.parse(gt).getroot().iter("frame"):
        if f.get("rejected") == "true":
            continue
        p = {q.get("name"): (float(q.get("x")), float(q.get("y"))) for q in f.iter("point")}
        if set(p) == {"tl", "tr", "br", "bl"}:
            out[int(f.get("index"))] = [p["tl"], p["tr"], p["br"], p["bl"]]
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("root", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--per-video", type=int, default=6)
    args = ap.parse_args()
    images = args.out / "frames"
    images.mkdir(parents=True, exist_ok=True)
    manifest, rows = [], []
    for video in sorted(args.root.glob("background*/*.avi")):
        truth = frames(video.with_suffix(".gt.xml"))
        if not truth:
            continue
        indices = sorted(truth)
        picks = [indices[round(k * (len(indices) - 1) / max(1, args.per_video - 1))] for k in range(args.per_video)]
        cap = cv2.VideoCapture(str(video))
        wanted, index = set(picks), 0
        while wanted:
            ok, frame = cap.read()
            if not ok:
                break
            index += 1  # SmartDoc frame indices start at 1
            if index not in wanted:
                continue
            wanted.discard(index)
            h, w = frame.shape[:2]
            name = f"{video.parent.name}-{video.stem}-f{index:03d}.jpg"
            path = images / name
            cv2.imwrite(str(path), frame, [cv2.IMWRITE_JPEG_QUALITY, 95])
            source = str(path.resolve())
            quad = [[x / w, y / h] for x, y in truth[index]]
            manifest.append({"set": "smartdoc15", "file": name, "source": source})
            rows.append({"set": "smartdoc15", "file": name, "source": source,
                         "sourceSha256": hashlib.sha256(path.read_bytes()).hexdigest(), "scored": True, "truth": quad,
                         "background": video.parent.name, "documentType": video.stem.rstrip("0123456789")})
        cap.release()
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=1), encoding="utf-8")
    (args.out / "input-report.json").write_text(json.dumps({
        "scope": "Independent evaluation only, never training or selection: SmartDoc 2015 Challenge 1 test set "
                 "(ICDAR'15, Zenodo 10.5281/zenodo.1230218, CC BY 4.0). Truth = the challenge's per-frame corners.",
        "perVideo": args.per_video, "rows": rows}, indent=1), encoding="utf-8")
    print(json.dumps({"frames": len(rows), "videos": len({r["file"].rsplit("-f", 1)[0] for r in rows})}))


if __name__ == "__main__":
    main()
