"""Independent Apache-2.0 text-extraction CPU ROI control, no integration.
Only official cached pretrained weights are read. Owner photos are test-only.
This is one upstream stage, not its complete page/ornament/background pipeline.
"""

from pathlib import Path
import argparse, difflib, hashlib, json, time, types
import cv2
import numpy as np
import torch
from benchmark_tone_guard import panel, cap

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--source", type=Path, required=True)
p.add_argument("--new4", type=Path, required=True)
p.add_argument("--jvm", type=Path, required=True)
p.add_argument("--out", type=Path, required=True)
p.add_argument("--gpu", action="store_true")
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=True)
torch.set_num_threads(4)
cv2.setNumThreads(2)
architecture = a.source / "UNet_model.py"
original_source = architecture.read_bytes()
source = original_source.decode("utf8")
dependency = "from torchvision.transforms.functional import resize"
assert source.count(dependency) == 1
adapted = source.replace(dependency, "# 400x400 patches do not enter the resize branches.")
(a.out / "UNet_model.400.py").write_text(adapted, encoding="utf8")
(a.out / "dependency-adaptation.patch").write_text("".join(difflib.unified_diff(source.splitlines(True), adapted.splitlines(True), fromfile="cached/UNet_model.py", tofile="private/UNet_model.400.py")), encoding="utf8")
resize_calls = []

def blocked_resize(value, size, *args, **kwargs):
    resize_calls.append(dict(shape=list(value.shape), size=list(size)))
    raise RuntimeError("Unverified shape-mismatch resize branch: no torchvision layer was substituted")

module = types.ModuleType("bleed_official_400")
module.__file__ = str(architecture)
module.resize = blocked_resize
exec(compile(adapted, str(architecture), "exec"), module.__dict__)
g41 = {r["name"]: r for r in json.loads((a.jvm / "g41/manifest.json").read_text())}
cases = [
    ("book-fingers", 0.50, 0.43),
    ("exam-stack", 0.52, 0.40),
    ("math-booklet", 0.50, 0.55),
    ("notes-stack-pen", 0.30, 0.30),
    ("p72-stack", 0.45, 0.60),
    ("rotated-notes", 0.70, 0.32),
    ("shadow-notes", 0.52, 0.55),
    ("uniform-stack", 0.50, 0.45),
    ("notes-left", 0.30, 0.33),
    ("notes-right", 0.37, 0.38),
]
report = dict(
    scope=__doc__,
    source="https://github.com/AdrianoEttari/bleed-through-cleaner",
    revision="3bb8cd036fe6a221c9126236a5f07c5f2906c34e",
    license="Apache-2.0",
    integrated=False,
    ownerPhotosUsedForTraining=False,
    inputContract="RGB uint8 -> float32 [0,1] NCHW, native 400x400; no mean/std transform",
    outputContract="Sigmoid background mask: zero=text/important, one=background; foreground confidence=1-mask, not calibrated probability",
    upstreamThreshold="uint8(backgroundMask * 255) > 50, matching upstream text_detect",
    adaptation=dict(originalSourceSha256=hashlib.sha256(original_source).hexdigest(), privateSourceSha256=hashlib.sha256(adapted.encode()).hexdigest(), change="One import removed in memory; resize fail-closed; all layers and checkpoint tensors retained"),
    models=[],
)
gpu = a.gpu and torch.cuda.is_available() and torch.cuda.mem_get_info()[0] > 1_500_000_000
torch.backends.cudnn.benchmark = False
torch.backends.cudnn.allow_tf32 = False
if gpu: report["gpu"] = torch.cuda.get_device_name(0)
for variant in [
    "Residual_attention_UNet_text_extraction",
    "Residual_attention_UNet_text_extraction_finetuning",
]:
    weight = a.source / "models" / variant / "snapshot.pt"
    model = module.Residual_Attention_UNet(image_channels=3, out_dim=1, device="cpu")
    checkpoint = torch.load(weight, map_location="cpu", weights_only=True)
    load_result = model.load_state_dict(checkpoint["MODEL_STATE"], strict=True)
    model.eval()
    rows = []
    folder = a.out / variant
    folder.mkdir(exist_ok=True)
    controls = {}
    known_ink = np.zeros((400, 400), np.uint8)
    for y in (65, 125, 185, 245, 305):
        cv2.putText(known_ink, "int main(void) 1/2 x-1", (15, y), cv2.FONT_HERSHEY_SIMPLEX, .7, 255, 1, cv2.LINE_AA)
    for control, contrast in [("blackText",235), ("faintText",18), ("blank",0)]:
        gray_control = np.rint(245 - contrast * known_ink.astype(np.float32)/255).astype(np.uint8)
        tensor = torch.from_numpy(np.repeat(gray_control[None, None], 3, axis=1).astype(np.float32)/255)
        with torch.inference_mode(): background = model(tensor)[0, 0].numpy()
        ink = known_ink > 180
        hard = (background*255).astype(np.uint8)>50
        controls[control] = dict(backgroundMaskMean=float(background.mean()), maskMeanAtKnownInk=float(background[ink].mean()), maskMeanAtPaper=float(background[~ink].mean()), knownInkRejectedAtUpstreamThreshold=float(hard[ink].mean()) if contrast else None)
        panel([gray_control, np.rint(background*255).astype(np.uint8), hard.astype(np.uint8)*255], ["Known front text source", "OSS sigmoid background", "Exact upstream threshold"], width=430).save(folder/f"control-{control}.jpg",quality=96)
    for index, (name, cx, cy) in enumerate(cases):
        if name in g41:
            entry = g41[name]
            source_path = Path(entry["source"])
            tone_path = Path(entry["output"])
            field_path = Path(entry["field"] + ".f32")
        else:
            source_path = a.new4 / "productionScale" / f"{name}-source.png"
            tone_path = a.new4 / "productionScale" / f"{name}-tone.png"
            field_path = a.new4 / "productionScale" / f"{name}-field.png.f32"
        image = cv2.imread(str(source_path))
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        tone = cv2.imread(str(tone_path), 0)
        field = np.fromfile(field_path, dtype="<f4").reshape(gray.shape)
        h, w = gray.shape
        left = int(w * cx) - 200
        top = int(h * cy) - 200
        box = (slice(top, top + 400), slice(left, left + 400))
        original = image[box]
        assert original.shape == (400, 400, 3)
        source_ink = np.clip(1 - gray.astype(np.float32) / np.maximum(field, 1), 0, 1)[
            box
        ]
        rendered = (255 - tone[box].astype(np.float32)) / 255
        rgb = cv2.cvtColor(original, cv2.COLOR_BGR2RGB).astype(np.float32) / 255
        inp = torch.from_numpy(np.ascontiguousarray(rgb.transpose(2, 0, 1)[None]))
        reference = torch.from_numpy(original[:, :, ::-1].copy()).permute(2, 0, 1)[None].float()/255
        assert torch.equal(inp, reference)
        with torch.inference_mode():
            model(inp)
            begin = time.perf_counter()
            background_cpu = model(inp)[0, 0].numpy()
            score = 1 - background_cpu
            milliseconds = (time.perf_counter() - begin) * 1000
        gpu_timing = None
        gpu_difference = None
        if gpu:
            model.cuda()
            with torch.inference_mode():
                device_input = inp.cuda(); model(device_input); torch.cuda.synchronize()
                begin = time.perf_counter(); gpu_score = 1-model(device_input)[0,0]; torch.cuda.synchronize()
                gpu_timing = (time.perf_counter()-begin)*1000
                gpu_difference = float(np.max(np.abs(gpu_score.cpu().numpy()-score)))
            model.cpu()
        assert not resize_calls
        bounded = cap(source_ink, rendered, score, 0.02, 0.15)
        result = np.rint(255 * (1 - bounded)).astype(np.uint8)
        np.save(folder / f"{name}-{index}-confidence.npy", score)
        normalized = np.rint(255 * (1 - source_ink)).astype(np.uint8)
        preview = panel(
            [
                gray[box],
                normalized,
                tone[box],
                np.rint(255 * (1 - score)).astype(np.uint8),
                np.where((background_cpu*255).astype(np.uint8)>50,255,0).astype(np.uint8),
                result,
            ],
            [
                "Source",
                "Source normalized",
                "Actual v29",
                "OSS text mask only",
                "OSS upstream threshold",
                "OSS .02-.15 gain cap",
            ],
            width=480,
        )
        preview.save(folder / f"{name}-{index}.jpg", quality=97)
        positive = source_ink > 0.22
        dark = tone[box] < 160
        rows.append(
            dict(
                name=name,
                normalizedCenter=[cx, cy],
                pixels=[left, top, 400, 400],
                cpu400Ms=milliseconds,
                gpu400Ms=gpu_timing,
                cpuGpuMaximumMaskDifference=gpu_difference,
                inputMin=float(inp.min()), inputMax=float(inp.max()), inputConversionExact=True,
                sourceStrongPixels=int(positive.sum()),
                strongSourceConfidenceBelowPoint02=int(
                    (positive & (score < 0.02)).sum()
                ),
                strongSourceConfidenceBelowPoint15=int(
                    (positive & (score < 0.15)).sum()
                ),
                renderedDarkPixelsLost=int((dark & (result >= 160)).sum()),
                upstreamRejectedBaselineDarkPixels=int((dark & ((background_cpu*255).astype(np.uint8)>50)).sum()),
                baselineDarkPixels=int(dark.sum()),
                minSourceOrBaselineViolations=int((bounded+1e-6<np.minimum(source_ink,rendered)).sum()),
                maskWarning="Confidence errors and the dark-pixel change are diagnostics, not ground-truth content classification",
            )
        )
        print(variant, rows[-1], flush=True)
    report["models"].append(
        dict(
            variant=variant,
            weightSha256=hashlib.sha256(weight.read_bytes()).hexdigest(),
            parameters=sum(p.numel() for p in model.parameters()),
            stateTensors=len(checkpoint["MODEL_STATE"]), epochs=checkpoint.get("EPOCHS_RUN"), missingKeys=load_result.missing_keys, unexpectedKeys=load_result.unexpected_keys,
            resizeCallsAt400=len(resize_calls), syntheticControls=controls,
            rows=rows,
        )
    )
    report["cachedSourceUnchanged"] = original_source == architecture.read_bytes()
    (a.out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf8")
    del model
