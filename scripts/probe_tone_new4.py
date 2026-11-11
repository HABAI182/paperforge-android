"""Tone-only diagnostic on four new test-only photos, using fixed hand crop truth.

No saved pre-tone flat exists for these named pages. This explicitly skips
automatic crop and dewarp. It makes the natural perspective crop, then uses
the same cubic workSource enlargement (max 1.6) and sourceScale semantics as
FlatDocument. The mismatched-scale replay is a sensitivity diagnostic only.
"""

from pathlib import Path
import argparse, hashlib, json, subprocess
import cv2
import numpy as np
from benchmark_tone_guard import panel

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--jvm", type=Path, required=True)
p.add_argument("--out", type=Path, required=True)
a = p.parse_args()
repo = Path(__file__).resolve().parents[1]
work = repo.parents[1] / "work"
out = a.out.resolve()
out.mkdir(parents=True, exist_ok=True)
params = json.loads((repo / "app/src/main/assets/scan_params.json").read_text())
edge = params["flatWorkEdge"]
manifest = []
metadata = []
for row in json.loads((work / "page-net/truth/hand-1110.json").read_text()):
    path = work / "page-net/phone-1110" / row["file"]
    image = cv2.imread(str(path))
    h, w = image.shape[:2]
    points = np.array(row["quad"], np.float32) * [w - 1, h - 1]
    nw = round(
        max(
            np.linalg.norm(points[0] - points[1]), np.linalg.norm(points[3] - points[2])
        )
        + 1
    )
    nh = round(
        max(
            np.linalg.norm(points[0] - points[3]), np.linalg.norm(points[1] - points[2])
        )
        + 1
    )
    transform = cv2.getPerspectiveTransform(
        points.astype(np.float32),
        np.float32([[0, 0], [nw - 1, 0], [nw - 1, nh - 1], [0, nh - 1]]),
    )
    natural = cv2.warpPerspective(
        image,
        transform,
        (nw, nh),
        flags=cv2.INTER_CUBIC,
        borderMode=cv2.BORDER_REPLICATE,
    )
    factor = min(edge / max(nw, nh), params["captureUpscale"])
    factor = factor if factor > 1.02 else 1
    up = (
        cv2.resize(
            natural,
            (round(nw * factor), round(nh * factor)),
            interpolation=cv2.INTER_CUBIC,
        )
        if factor > 1
        else natural
    )
    scale = up.shape[1] / nw
    stem = path.stem
    for tag, img, ss in [
        ("natural", natural, 1),
        ("productionScale", up, scale),
        ("wrongScale1", up, 1),
    ]:
        folder = out / tag
        folder.mkdir(exist_ok=True)
        source = folder / f"{stem}-source.png"
        cv2.imwrite(str(source), img)
        manifest.append(
            dict(
                name=stem + "-" + tag,
                source=str(source),
                output=str(folder / f"{stem}-tone.png"),
                field=str(folder / f"{stem}-field.png"),
                scale=ss,
                exact=False,
                stack=False,
            )
        )
    metadata.append(
        dict(
            name=stem,
            original=str(path),
            sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            quad=row["quad"],
            naturalShape=list(natural.shape),
            productionShape=list(up.shape),
            productionSourceScale=scale,
        )
    )
(out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf8")
(out / "inputs.json").write_text(
    json.dumps(dict(scope=__doc__, rows=metadata), indent=2), encoding="utf8"
)
lib = work / "toolchain/gradle-8.13/lib"
native = next(a.jvm.rglob("opencv_java490.dll"))
cp = ";".join(
    map(
        str,
        [
            a.jvm / "tone-probe.jar",
            a.jvm / "opencv-4.9.0-0.jar",
            a.jvm / "json-20250517.jar",
            lib / "kotlin-stdlib-2.0.21.jar",
        ],
    )
)
subprocess.run(
    [
        "java",
        "-Xmx4g",
        "-cp",
        cp,
        "cn.paperforge.app.imaging.ToneJvmKt",
        str(native.resolve()),
        str((a.jvm / "scan_params.json").resolve()),
        str(out / "manifest.json"),
        str(out / "jvm-results.json"),
    ],
    check=True,
)
for r in metadata:
    n = r["name"]
    source = cv2.imread(str(out / "productionScale" / f"{n}-source.png"), 0)
    base = cv2.imread(str(out / "productionScale" / f"{n}-tone.png"), 0)
    natural = cv2.imread(str(out / "natural" / f"{n}-tone.png"), 0)
    wrong = cv2.imread(str(out / "wrongScale1" / f"{n}-tone.png"), 0)
    h, w = source.shape
    field = np.fromfile(
        out / "productionScale" / f"{n}-field.png.f32", dtype="<f4"
    ).reshape(source.shape)
    source_ink = np.clip(1 - source.astype(np.float32) / np.maximum(field, 1), 0, 1)
    normalized = np.rint(255 * (1 - source_ink)).astype(np.uint8)
    nres = cv2.resize(natural, (w, h), interpolation=cv2.INTER_CUBIC)
    panel(
        [source, normalized, base, nres, wrong],
        [
            "Hand-crop source",
            "Source normalized",
            "v29 correct sourceScale",
            "Natural no enlargement",
            "Diagnostic wrong scale=1",
        ],
        width=450,
    ).save(out / f"{n}-contact.jpg", quality=94)
    # Fixed grid crops expose source and rendered marks together, with no semantic labels.
    for yi in range(4):
        for xi in range(2):
            box = (
                slice(int(h * (0.05 + yi * 0.22)), int(h * (0.27 + yi * 0.22))),
                slice(int(w * (0.05 + xi * 0.45)), int(w * (0.5 + xi * 0.45))),
            )
            panel(
                [im[box] for im in [source, normalized, base, wrong]],
                [
                    "Hand-crop source",
                    "Source normalized",
                    "v29 correct sourceScale",
                    "Diagnostic wrong scale=1",
                ],
                width=520,
            ).save(out / f"{n}-grid-{yi}-{xi}.jpg", quality=96)
