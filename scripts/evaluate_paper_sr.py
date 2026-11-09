"""Source-aligned real-photo SR checks, blank control and ONNX parity.

Known synthetic degradation scores stay separate from real photos. Real output
has no sharp ground truth, so stroke-area changes and OCR diagnostics are only
proxies. Tiled output always uses source pixels, never recognized glyphs.
"""

from pathlib import Path
import argparse
import hashlib
import json
import time

import cv2
import numpy as np
import onnxruntime as ort
import torch

from paper_sr_lab import PaperSR


def tiled(session, gray):
    h, w = gray.shape
    pad, edge, step = 8, 224, 208
    source = np.pad(gray, ((pad, pad + edge), (pad, pad + edge)), mode="edge")
    output = np.zeros((h * 3, w * 3), "float32")
    start = time.perf_counter()
    tiles = 0
    for top in range(0, h, step):
        for left in range(0, w, step):
            hh, ww = min(step, h - top), min(step, w - left)
            inp = source[top : top + edge, left : left + edge][None, None]
            prediction = session.run(None, {session.get_inputs()[0].name: inp})[0][0, 0]
            output[top * 3 : (top + hh) * 3, left * 3 : (left + ww) * 3] = prediction[
                pad * 3 : (pad + hh) * 3, pad * 3 : (pad + ww) * 3
            ]
            tiles += 1
    return output.clip(0, 1), dict(
        desktopCpuSeconds=time.perf_counter() - start, tiles=tiles
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--photo", type=Path, required=True, action="append")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    repo = Path(__file__).resolve().parents[1]
    assert out != repo and repo not in out.parents
    out.mkdir(parents=True, exist_ok=True)
    cv2.setNumThreads(1)
    torch.set_num_threads(4)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    options.log_severity_level = 3
    candidate = ort.InferenceSession(
        str(args.candidate / "paper-sr.onnx"),
        options,
        providers=["CPUExecutionProvider"],
    )
    old = ort.InferenceSession(
        "app/src/main/assets/retouch/super-resolution-10.onnx",
        options,
        providers=["CPUExecutionProvider"],
    )
    configuration = json.loads((args.candidate / "report.json").read_text())
    model = PaperSR(
        contrast_equivariant=configuration.get("contrastEquivariant", False),
        range_gate=configuration.get("uniformRangeGate", False),
        architecture=configuration.get("architecture", "subpixel"),
    )
    model.load_state_dict(torch.load(args.candidate / "paper-sr.pt", weights_only=True))
    model.eval()
    probe = np.random.default_rng(1940).random((1, 1, 67, 83), dtype=np.float32)
    with torch.inference_mode():
        expected = model(torch.from_numpy(probe)).numpy()
    actual = candidate.run(None, {"input": probe})[0]
    error = float(np.max(np.abs(expected - actual)))
    # Compare in delivered intensity units. Resize-convolution accumulates a
    # little more floating point error than subpixel export (about .007/255),
    # still far below one raster level. Also check the rounded raster directly.
    raster_error = int(np.max(np.abs(np.round(expected * 255) - np.round(actual * 255))))
    assert error * 255 < 0.02 and raster_error <= 1, (error, raster_error)
    blank = candidate.run(None, {"input": np.ones((1, 1, 67, 83), "float32")})[0]
    # ONNX cubic interpolation has about 1.6e-6 floating-point roundoff here.
    # The delivered 8-bit blank must remain exactly white, with no invented ink.
    assert np.isfinite(blank).all() and np.all(np.round(blank * 255) == 255)
    flat_controls = []
    for byte in [51, 128, 179, 230, 255]:
        value = byte / 255
        output = candidate.run(
            None, {"input": np.full((1, 1, 67, 83), value, "float32")}
        )[0]
        flat_controls.append(
            {
                "inputByte": byte,
                "maxToneError": float(np.max(np.abs(output - value))),
                "changedRasterPixels": int(np.sum(np.round(output * 255) != byte)),
                "phaseStandardDeviation": float(output[:, :, 12:-12, 12:-12].std()),
            }
        )
    report = dict(
        scope="real unlabelled source probes, blank control and dynamic ONNX conversion; not Android timing",
        candidateSha256=hashlib.sha256(
            (args.candidate / "paper-sr.onnx").read_bytes()
        ).hexdigest(),
        onnxDynamicOddShapeMaxAbsError=error,
        onnxDynamicOddShapeMaxRasterError=raster_error,
        blankNewInkPixels=int((blank < 0.999).sum()),
        flatControls=flat_controls,
        integrated=False,
        rows=[],
    )
    for i, path in enumerate(args.photo):
        gray = cv2.imread(str(path), 0)
        assert gray is not None, path
        # An unchanged source-normalized crop; this is not background cleanup.
        h, w = gray.shape
        crop = gray[
            round(h * 0.01) : round(h * 0.15), round(w * 0.02) : round(w * 0.57)
        ]
        inp = crop.astype("float32") / 255
        bicubic = cv2.resize(inp, None, fx=3, fy=3, interpolation=cv2.INTER_CUBIC).clip(
            0, 1
        )
        prior, old_time = tiled(old, inp)
        proposed, new_time = tiled(candidate, inp)
        folder = out / f"{i}-{path.stem}"
        folder.mkdir(exist_ok=True)
        board = []
        for name, image in [
            ("bicubic", bicubic),
            ("shipped-espcn", prior),
            ("paper-candidate", proposed),
        ]:
            raster = np.round(image * 255).astype("uint8")
            cv2.imwrite(str(folder / (name + ".png")), raster)
            preview = cv2.cvtColor(raster, cv2.COLOR_GRAY2BGR)
            preview = cv2.copyMakeBorder(
                preview, 30, 0, 0, 0, cv2.BORDER_CONSTANT, value=(245, 245, 245)
            )
            cv2.putText(preview, name, (4, 20), 0, 0.55, (0, 0, 0), 1)
            board.append(preview)
        cv2.imwrite(str(folder / "actual-source-contact.jpg"), np.vstack(board))
        report["rows"].append(
            dict(
                input=str(path),
                sourceSha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                cropPixels=list(crop.shape),
                oldTiming=old_time,
                candidateTiming=new_time,
                sourceDarkFraction=float(np.mean(bicubic < 0.5)),
                oldDarkAreaVsBicubic=float(
                    np.sum(prior < 0.5) / max(1, np.sum(bicubic < 0.5))
                ),
                candidateTotalInkVsBicubic=float(
                    np.sum(1 - proposed) / max(1e-6, np.sum(1 - bicubic))
                ),
                candidateDarkAreaVsBicubic=float(
                    np.sum(proposed < 0.5) / max(1, np.sum(bicubic < 0.5))
                ),
            )
        )
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
