"""Offline full-resolution source gain cap on the exact production ToneZones JVM output.

Owner photos and scanner exports are evaluation-only. Scanner masks are a noisy
black-box comparison, not training labels or proof of faint-pencil correctness.
Tiled full-resolution inference retains input pixel scale; it does not thumbnail
the page. Android exterior, edge guard and colour-print preservation are absent
from the JVM stage, and desktop timings are not phone timings.
"""

from __future__ import annotations
import argparse
import hashlib
import json
import time
from pathlib import Path
import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw


def features(ink):
    half = cv2.GaussianBlur(ink, (0, 0), 0.6)
    return np.stack(
        [
            ink,
            half,
            cv2.GaussianBlur(ink, (0, 0), 1.5),
            cv2.GaussianBlur(ink, (0, 0), 4),
            ink - half,
            cv2.dilate(ink, np.ones((3, 3), np.uint8)),
        ]
    ).astype("float32")


def tiled(evidence, run, tile=512, halo=64):
    _, h, w = evidence.shape
    padded = np.pad(
        evidence, ((0, 0), (halo, halo + (-h) % 4), (halo, halo + (-w) % 4))
    )
    ph, pw = padded.shape[1] - 2 * halo, padded.shape[2] - 2 * halo
    result = np.empty((ph, pw), np.float32)
    start = time.perf_counter()
    calls = 0
    for y in range(0, ph, tile):
        for x in range(0, pw, tile):
            hh, ww = min(tile, ph - y), min(tile, pw - x)
            block = np.ascontiguousarray(
                padded[None, :, y : y + hh + 2 * halo, x : x + ww + 2 * halo]
            )
            v = run(block)[0, 0]
            result[y : y + hh, x : x + ww] = v[halo : halo + hh, halo : halo + ww]
            calls += 1
    return result[:h, :w], (time.perf_counter() - start) * 1000, calls


def cap(source, rendered, confidence, low=0.35, high=0.85):
    t = np.clip((confidence - low) / (high - low), 0, 1)
    t = t * t * (3 - 2 * t)
    return np.minimum(rendered, source + (1 - source) * t)


def kept(source, output):
    evidence = (source > 0.22).astype(np.uint8)
    h, w = evidence.shape
    s = max(h, w) / 1375
    evidence[: int(0.03 * h)] = 0
    evidence[int(0.97 * h) :] = 0
    evidence[:, : int(0.03 * w)] = 0
    evidence[:, int(0.97 * w) :] = 0
    n, labels, st, _ = cv2.connectedComponentsWithStats(evidence, 8)
    area = st[:, cv2.CC_STAT_AREA]
    big = (area >= 20 * s * s) & (np.arange(n) > 0)
    erased = np.bincount(labels.ravel(), weights=(output >= 235).ravel(), minlength=n)
    lost = big & (erased / np.maximum(area, 1) >= 0.8)
    return dict(
        strokes=int(big.sum()),
        kept=int(big.sum() - lost.sum()),
        fraction=float(1 - lost.sum() / max(1, big.sum())),
        sourcePixels=int(evidence.sum()),
        sourcePixelsKept=float(
            ((evidence > 0) & (output < 235)).sum() / max(1, evidence.sum())
        ),
    )


def register(baseline, reference):
    # Both images are at the same width before SIFT, including the small-print book.
    sift = cv2.SIFT_create(7000)
    best = None
    h, w = baseline.shape
    a = cv2.resize(baseline, (1000, round(h * 1000 / w)), interpolation=cv2.INTER_AREA)
    ka, da = sift.detectAndCompute(a, None)
    for rotation in range(4):
        ref = np.ascontiguousarray(np.rot90(reference, rotation))
        rh, rw = ref.shape
        b = cv2.resize(ref, (1000, round(rh * 1000 / rw)), interpolation=cv2.INTER_AREA)
        kb, db = sift.detectAndCompute(b, None)
        if da is None or db is None:
            continue
        good = [
            m
            for m, n in cv2.BFMatcher().knnMatch(db, da, k=2)
            if m.distance < 0.75 * n.distance
        ]
        if len(good) < 8:
            continue
        H, mask = cv2.findHomography(
            np.float32([kb[m.queryIdx].pt for m in good]),
            np.float32([ka[m.trainIdx].pt for m in good]),
            cv2.USAC_MAGSAC,
            3,
        )
        inliers = int(mask.sum()) if mask is not None else 0
        supported = (
            np.float32(
                [ka[m.trainIdx].pt for m, keep in zip(good, mask.ravel()) if keep]
            )
            if mask is not None
            else np.empty((0, 2), np.float32)
        )
        if best is None or inliers > best[0]:
            best = (inliers, rotation, H, b, supported)
    if best is None or best[0] < 40:
        raise ValueError(f"weak registration: {best[0] if best else 0}")
    inliers, rotation, H, b, supported = best
    aligned = cv2.warpPerspective(b, H, (a.shape[1], a.shape[0]), borderValue=255)
    valid = cv2.warpPerspective(np.ones(b.shape, np.uint8), H, (a.shape[1], a.shape[0]))
    # Fixed baseline-derived flow, shared by every candidate; cannot favour a cap.
    flow = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM).calc(
        cv2.GaussianBlur(a, (0, 0), 2), cv2.GaussianBlur(aligned, (0, 0), 2), None
    )
    reverse = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM).calc(
        cv2.GaussianBlur(aligned, (0, 0), 2), cv2.GaussianBlur(a, (0, 0), 2), None
    )
    gx, gy = np.meshgrid(
        np.arange(a.shape[1], dtype=np.float32), np.arange(a.shape[0], dtype=np.float32)
    )
    mx, my = gx + flow[..., 0], gy + flow[..., 1]
    backward = cv2.remap(
        reverse,
        mx,
        my,
        cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT,
        borderValue=(999, 999),
    )
    cycle = np.sqrt(((flow + backward) ** 2).sum(2))
    aligned = cv2.remap(aligned, mx, my, cv2.INTER_LINEAR, borderValue=255)
    valid = cv2.remap(valid, mx, my, cv2.INTER_NEAREST)
    valid = cv2.erode(valid, np.ones((25, 25), np.uint8)) > 0
    mh, mw = int(a.shape[0] * 0.03), int(a.shape[1] * 0.03)
    valid[:mh] = False
    valid[-mh:] = False
    valid[:, :mw] = False
    valid[:, -mw:] = False
    residual = np.sqrt((flow**2).sum(2))
    geometric_valid = valid.copy()
    seeds = np.ones(a.shape, np.uint8)
    for x, y in supported:
        ix, iy = round(float(x)), round(float(y))
        if 0 <= iy < seeds.shape[0] and 0 <= ix < seeds.shape[1]:
            seeds[iy, ix] = 0
    support_distance = cv2.distanceTransform(seeds, cv2.DIST_L2, 3)
    # The 7px foreground exclusion radius downstream covers <=4px displacement
    # + <=1px cycle error + 2px antialiasing. Far blank only, not close-ink truth.
    valid &= (cycle <= 1) & (residual <= 4) & (support_distance <= 120)
    return (
        aligned,
        valid,
        dict(
            inliers=inliers,
            rotation=rotation,
            geometricValidPixels=int(geometric_valid.sum()),
            reliablePixels=int(valid.sum()),
            reliableFraction=float(valid.sum() / max(1, geometric_valid.sum())),
            flowMedian=float(np.median(residual[geometric_valid])),
            flowP95=float(np.percentile(residual[geometric_valid], 95)),
            cycleP95=float(np.percentile(cycle[geometric_valid], 95)),
            acceptedFlowMaximum=4,
            acceptedCycleMaximum=1,
            maximumDistanceToSiftInlier=120,
            warning="Filtered far-blank proxy only; reference masks are not ground truth",
        ),
    )


def reference_metrics(source, output, ref, valid):
    size = (ref.shape[1], ref.shape[0])
    source = cv2.resize(source, size, interpolation=cv2.INTER_AREA)
    out = cv2.resize(output, size, interpolation=cv2.INTER_AREA)
    ink = (255 - out.astype(np.float32)) / 255
    near = cv2.dilate((ref < 200).astype(np.uint8), np.ones((15, 15), np.uint8)) > 0
    back = valid & ~near & (source > 0.004) & (source < 0.15)
    front = valid & (ref < 160)
    dark = out < 160
    recall = (
        front & (cv2.dilate(dark.astype(np.uint8), np.ones((7, 7), np.uint8)) > 0)
    ).sum() / max(1, front.sum())
    extra = valid & dark & ~near
    n, _, st, _ = cv2.connectedComponentsWithStats(extra.astype(np.uint8), 8)
    bins = []
    for low, high in [
        (0, 0.05),
        (0.05, 0.1),
        (0.1, 0.15),
        (0.15, 0.25),
        (0.25, 0.4),
        (0.4, 1.01),
    ]:
        m = valid & ~near & (source >= low) & (source < high)
        bins.append(
            dict(
                sourceLow=low,
                sourceHigh=high,
                pixels=int(m.sum()),
                darkPixels=int((m & dark).sum()),
                meanDarkening=float(np.maximum(0, ink - source)[m].mean())
                if m.any()
                else None,
            )
        )
    return dict(
        backgroundCandidatePixels=int(back.sum()),
        backgroundMeanDarkening=float(np.maximum(0, ink - source)[back].mean())
        if back.any()
        else None,
        backgroundDarkPixels=int((back & dark).sum()),
        extraDarkPixels=int(extra.sum()),
        extraComponents=int((st[1:, cv2.CC_STAT_AREA] >= 4).sum()),
        referenceInkRecall=float(recall),
        backgroundSourceBins=bins,
    )


def panel(images, labels, width=720):
    thumbs = []
    for arr, label in zip(images, labels):
        im = Image.fromarray(arr)
        im.thumbnail((width, 1100))
        c = Image.new("RGB", (width, max(80, im.height + 34)), "white")
        c.paste(im, ((width - im.width) // 2, 34))
        ImageDraw.Draw(c).text((12, 9), label, fill="black")
        thumbs.append(c)
    canvas = Image.new(
        "RGB", (sum(q.width for q in thumbs), max(q.height for q in thumbs)), "#ccc"
    )
    at = 0
    for t in thumbs:
        canvas.paste(t, (at, 0))
        at += t.width
    return canvas


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--jvm", type=Path, required=True)
    p.add_argument("--model", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--gpu", action="store_true")
    p.add_argument("--reuse", action="store_true")
    p.add_argument("--score-dir", type=Path)
    p.add_argument("--names", nargs="*")
    a = p.parse_args()
    repo = Path(__file__).resolve().parents[1]
    work = repo.parents[1] / "work"
    out = a.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    cv2.setNumThreads(2)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(
        str(a.model), options, providers=["CPUExecutionProvider"]
    )
    name = session.get_inputs()[0].name
    run = lambda block: session.run(None, {name: block})[0]
    gpu_model = None
    if a.gpu:
        import torch
        from train_paper_context import ContextInk, Export

        torch.set_num_threads(4)
        gpu_model = Export(ContextInk(channels=6, dilation=2))
        gpu_model.model.load_state_dict(
            torch.load(
                a.model.with_suffix(".pt"), map_location="cpu", weights_only=True
            )
        )
        gpu_model.eval().cuda()

        @torch.inference_mode()
        def run_gpu(block):
            return gpu_model(torch.from_numpy(block).cuda()).cpu().numpy()

    pairs = {
        Path(r["file"]).stem: work / "phone-compare-20029" / r["page"]
        for r in json.loads((work / "page-net/truth/camscanner-1107.json").read_text())
    }
    pairs["notes-stack-pen"] = work / "page-net/cmp-33/camscanner-p1.png"
    rows = []
    manifest = json.loads((a.jvm / "g41/manifest.json").read_text())
    for entry in manifest:
        n = entry["name"]
        if a.names and n not in a.names:
            continue
        print("START", n, flush=True)
        source_path = Path(entry["source"])
        raw = cv2.imread(str(source_path), 0)
        baseline = cv2.imread(entry["output"], 0)
        field_path = Path(entry["field"] + ".f32")
        field = np.fromfile(field_path, dtype="<f4").reshape(raw.shape)
        source = np.clip(1 - raw.astype(np.float32) / np.maximum(field, 1), 0, 1)
        begin = time.perf_counter()
        evidence = features(source)
        feature_ms = (time.perf_counter() - begin) * 1000
        score_path = (a.score_dir or out) / f"{n}-confidence.npy"
        timings = {}
        timing_file = (a.score_dir or out) / "inference-timings.json"
        if timing_file.exists():
            timings = json.loads(timing_file.read_text()).get(n, {})
        if a.reuse and score_path.exists():
            confidence = np.load(score_path)
        else:
            # Single 256px tile timing is a real measurement, not scaled to phone.
            test = np.ascontiguousarray(evidence[None, :, :256, :256])
            run(test)
            t = time.perf_counter()
            run(test)
            timings["onnxCpu256Ms"] = (time.perf_counter() - t) * 1000
            confidence, cpu_ms, calls = tiled(evidence, run)
            timings.update(onnxCpuFullTiledMs=cpu_ms, tiles=calls)
            if gpu_model is not None:
                run_gpu(test)
                t = time.perf_counter()
                run_gpu(test)
                timings["torchGpu256Ms"] = (time.perf_counter() - t) * 1000
                gpu_conf, gpu_ms, _ = tiled(evidence, run_gpu)
                timings["torchGpuFullTiledMs"] = gpu_ms
                timings["cpuGpuMaxAbsDifference"] = float(
                    np.max(np.abs(gpu_conf - confidence))
                )
            np.save(score_path, confidence)
        rendered = (255 - baseline.astype(np.float32)) / 255
        nn = cap(source, rendered, confidence)
        # Isolated weak evidence only; this contrast-only comparator has no model.
        dist = cv2.distanceTransform((source < 0.22).astype(np.uint8), cv2.DIST_L2, 3)
        isolated = (source < 0.15) & (dist > 2)
        local = np.where(isolated, np.minimum(rendered, source), rendered)
        nnweak = np.where(isolated, nn, rendered)
        outputs = {
            "baseline": baseline,
            "nn": np.rint(255 * (1 - nn)).astype(np.uint8),
            "local": np.rint(255 * (1 - local)).astype(np.uint8),
            "nnweak": np.rint(255 * (1 - nnweak)).astype(np.uint8),
        }
        nnsoft = cap(source, rendered, confidence, 0.02, 0.15)
        outputs["nnsoft"] = np.rint(255 * (1 - nnsoft)).astype(np.uint8)
        mask_dir = a.jvm / "g41-instrumented"
        if (mask_dir / f"{n}-keep.png").exists():
            assert (
                hashlib.sha256(Path(entry["output"]).read_bytes()).digest()
                == hashlib.sha256((mask_dir / f"{n}-tone.png").read_bytes()).digest()
            ), "Instrumented output must be byte identical"
            read = lambda suffix: cv2.imread(str(mask_dir / f"{n}-{suffix}.png"), 0) > 0
            protect = (
                read("rules")
                | read("drawn-rules")
                | read("source-ridges")
                | (read("keep") & ~read("faint"))
            )
            protect = (
                cv2.dilate(protect.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
            )
            outputs["nnsoftveto"] = np.rint(
                255 * (1 - np.where(protect, rendered, nnsoft))
            ).astype(np.uint8)
            outputs["nnveto"] = np.rint(
                255 * (1 - np.where(protect, rendered, nn))
            ).astype(np.uint8)
            cv2.imwrite(
                str(out / f"{n}-production-veto.png"), protect.astype(np.uint8) * 255
            )
        saved_final = cv2.imread(str(source_path.with_name(n + "-3-final.png")), 0)
        if saved_final is not None and saved_final.shape == raw.shape:
            outputs["androidFinal"] = saved_final
        row = dict(
            name=n,
            source=str(source_path),
            shape=list(raw.shape),
            timings=timings,
            featureMs=feature_ms,
            methods={},
            sourceOnlyInput=True,
        )
        ref = cv2.imread(str(pairs[n]), 0) if pairs.get(n) else None
        aligned = valid = None
        if ref is not None:
            try:
                aligned, valid, registration = register(baseline, ref)
                row["registration"] = registration
                cv2.imwrite(str(out / f"{n}-aligned-reference.png"), aligned)
                cv2.imwrite(
                    str(out / f"{n}-reference-reliable.png"),
                    valid.astype(np.uint8) * 255,
                )
            except ValueError as ex:
                row["registrationError"] = str(ex)
        for method, result in outputs.items():
            cv2.imwrite(str(out / f"{n}-{method}.png"), result)
            m = kept(source, result)
            if aligned is not None:
                m.update(reference_metrics(source, result, aligned, valid))
            ink = (255 - result.astype(np.float32)) / 255
            m["violatesMinSourceOrBaselinePixels"] = int(
                (ink + 1 / 255 + 1e-6 < np.minimum(source, rendered)).sum()
            )
            m["changedPixels"] = int((result != baseline).sum())
            row["methods"][method] = m
        # Full page comparison plus fixed small-print crop, never selected by model output.
        normalized = np.rint(255 * (1 - source)).astype(np.uint8)
        panel(
            [normalized, baseline, outputs["nn"], outputs["local"]],
            [
                "Source normalized",
                "Actual Kotlin v29",
                "ONNX confidence cap",
                "Isolated weak cap",
            ],
            width=420,
        ).save(out / f"{n}-contact.jpg", quality=91)
        if n == "book-fingers":
            h, w = raw.shape
            box = (
                slice(int(h * 0.31), int(h * 0.63)),
                slice(int(w * 0.1), int(w * 0.92)),
            )
            panel(
                [
                    im[box]
                    for im in [normalized, baseline, outputs["nn"], outputs["local"]]
                ],
                [
                    "Source normalized",
                    "Actual Kotlin v29",
                    "ONNX confidence cap",
                    "Isolated weak cap",
                ],
                width=720,
            ).save(out / "book-small-print.jpg", quality=96)
            if "nnsoftveto" in outputs:
                panel(
                    [
                        im[box]
                        for im in [
                            normalized,
                            baseline,
                            outputs["nnsoft"],
                            outputs["nnsoftveto"],
                            outputs["nnveto"],
                        ]
                    ],
                    [
                        "Source normalized",
                        "Actual Kotlin v29",
                        "ONNX .02-.15",
                        "ONNX .02-.15 + veto",
                        "ONNX .35-.85 + veto",
                    ],
                    width=720,
                ).save(out / "book-small-print-veto.jpg", quality=96)
        if aligned is not None:
            size = (aligned.shape[1], aligned.shape[0])
            base = cv2.resize(baseline, size, interpolation=cv2.INTER_AREA)
            near = (
                cv2.dilate(
                    (aligned < 200).astype(np.uint8), np.ones((15, 15), np.uint8)
                )
                > 0
            )
            residue = (valid & ~near & (base < 225)).astype(np.uint8)
            count, lab, st, _ = cv2.connectedComponentsWithStats(residue, 8)
            candidates = sorted(
                range(1, count), key=lambda i: st[i, cv2.CC_STAT_AREA], reverse=True
            )[:3]
            for ci, i in enumerate(candidates):
                x, y, w, h, area = st[i]
                pad = 22
                if area < 4:
                    continue
                box = (
                    slice(max(0, y - pad), min(base.shape[0], y + h + pad)),
                    slice(max(0, x - pad), min(base.shape[1], x + w + pad)),
                )
                ims = [
                    cv2.resize(im, size, interpolation=cv2.INTER_AREA)[box]
                    for im in [normalized, baseline, outputs["nn"], outputs["local"]]
                ] + [aligned[box]]
                panel(
                    ims,
                    [
                        "Source normalized",
                        "Actual Kotlin v29",
                        "ONNX confidence cap",
                        "Isolated weak cap",
                        "Scanner reference",
                    ],
                    width=320,
                ).save(out / f"{n}-residual-{ci}.jpg", quality=96)
        rows.append(row)
        (out / "results.json").write_text(json.dumps(rows, indent=2), encoding="utf8")
        print(json.dumps(row), flush=True)
    report = dict(
        scope=__doc__,
        modelSha256=hashlib.sha256(a.model.read_bytes()).hexdigest(),
        ownerPhotosUsedForTraining=False,
        opencv=cv2.__version__,
        ort=ort.__version__,
        providers=session.get_providers(),
        rows=rows,
    )
    if a.gpu:
        report["gpu"] = torch.cuda.get_device_name(0)
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf8")


if __name__ == "__main__":
    main()
