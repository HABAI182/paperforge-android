# Upstream reference matrix

Inspected 2026-10-23. Project HEAD/base: `4d56d556aab3241c96f1584b869e947b1f330bb9`. Repository visibility independently read as public; no visibility change made. Source copies used for research are outside the checkout in `work/upstream`.

| Project | Fixed source commit | License inspected | Files investigated | Decision / target |
|---|---|---|---|---|
| [OSS-DocumentScanner](https://github.com/ossappscollective/OSS-DocumentScanner/tree/ff05fc52b601d1cccb4076f3f6c3318f74e72f0c) | `ff05fc52b601d1cccb4076f3f6c3318f74e72f0c` | MIT | `cpp/src/DocumentDetector.cpp`<br>`cpp/src/WhitePaperTransform.cpp`<br>`cpp/src/WhitePaperTransform2.cpp`<br>`cpp/src/ColorSimplificationTransform.cpp`<br>`plugin-nativeprocessor/platforms/android/java/com/akylas/documentscanner/AutoScanHandler.kt` | Ideas initially; any adapted source will carry SHA/license header and copied notice. Imaging/camera implementation |
| [PDF-Doc-Scan](https://github.com/LittleTrickster/PDF-Doc-Scan/tree/8e0253f7b5445992751787ddbbc5bd5062fc440a) | `8e0253f7b5445992751787ddbbc5bd5062fc440a` | BSD-3-Clause | `app/src/main/java/com/littletrickster/scanner/opencvHelpers.kt`<br>`app/src/main/java/com/littletrickster/scanner/ScanActivity.kt` | Interaction and memory ownership comparison; keep in-process pipeline |
| [open-android-doc-scanner](https://github.com/phillip9933/open-android-doc-scanner/tree/3ba5c50a64f9be8eaeec47d263b360488e58835b) | `3ba5c50a64f9be8eaeec47d263b360488e58835b` | Apache-2.0 | `scanner-processing-opencv/src/main/kotlin/dev/offlinescan/processing/OpenCvProcessor.kt`<br>`scanner-processing-opencv/src/main/kotlin/dev/offlinescan/processing/AutoEnhancement.kt`<br>`scanner-camera/src/main/kotlin/dev/offlinescan/camera/AutoCaptureGate.kt`<br>`scanner-camera/src/main/kotlin/dev/offlinescan/camera/CaptureQualityPolicy.kt`<br>`scanner-camera/src/main/kotlin/dev/offlinescan/camera/OutlineTracker.kt` | Ideas initially; any adapted source will carry SHA/license header and copied notice. Imaging/camera implementation |
| [zynkware](https://github.com/zynkware/Document-Scanning-Android-SDK/tree/f6e20370deb5ad30aa5742cf8e9f4838926067d3) | `f6e20370deb5ad30aa5742cf8e9f4838926067d3` | MIT text in README; no standalone root LICENSE | `README.md` | Interaction concepts only; no old binaries |
| [ImageToolbox](https://github.com/t8rin/ImageToolbox/tree/7b81a244f2f1a69ddfa4b1b6d96393eec54d3324) | `7b81a244f2f1a69ddfa4b1b6d96393eec54d3324` | Apache-2.0 | `README.md` | UX ideas only; no UI replacement |
| [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android/tree/acf64258dd2ce575fea8ac4e51f57cca7f4945d7) | `acf64258dd2ce575fea8ac4e51f57cca7f4945d7` | Apache-2.0 | `library/src/main/java/com/tom_roush/pdfbox/pdmodel/graphics/image/JPEGFactory.java`<br>`library/src/main/java/com/tom_roush/pdfbox/pdmodel/graphics/image/CCITTFactory.java`<br>`library/src/main/java/com/tom_roush/pdfbox/pdmodel/graphics/image/PDImageXObject.java` | Retain pinned artifact; verify encoding/resource APIs against AAR |
| [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf) | `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf` | Apache-2.0 | `docs/version3.x/inference_deployment/cross_platform/android_deployment.en.md` | Candidate only; optional arm64 probe requires device/model provenance, no model added |
| [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android/tree/15c534717b1cb58261b58d4e4c1200c7f81f668c) | `15c534717b1cb58261b58d4e4c1200c7f81f668c` | Apache-2.0 | `README.md` | Comparator only; no new bridge or traineddata added |

## Findings before implementation

- OSS detector tests thresholds/channels, angle priors and optional Hough intersections. It also rejects border-near candidates; that rejection will not be copied because the close framing cases fail it. WhitePaperTransform2 builds per-channel percentile LUTs; independent tile-percentile flat-field correction will be measured for color preservation and large fills.
- Native SDK keeps raw capture decisions separate from display smoothing, evaluates fixed-scale sharpness/detail tiles, and uses relative quality. Its AutoEnhancement may recommend grayscale, so PaperForge will keep its explicit Auto Color requirement and protect saturated ink instead.
- LittleTrickster demonstrates a small Compose/Intent wrapper around an OpenCV scanner, not a native replacement runtime. No vendored OpenCV will be imported.
- PdfBox-Android JPEGFactory creates alpha masks whenever Bitmap.hasAlpha is true. The CCITT Bitmap method's implementation must be checked: its presence alone does not prove a real Group 4 encoder.
- All source/model reuse remains license-specific. No copied GPL/AGPL/MPL, proprietary model or competitor binary enters the shippable branch. Commercial app comparisons are unavailable until corresponding installed apps/device images can actually be run; none are claimed.

Measured adoption decisions and before/after data are linked from `docs/quality/QUALITY-REPORT.md` as experiments finish.



## 0.4 direct adoption

- MakeACopy: https://github.com/egdels/makeacopy/tree/01bebd394b9dd6f3a692f28aea7c0638085eb4da . Inspected Java corner adapter, ORT runner, letterbox/score/postprocessor, training guide, LICENSE/NOTICE. Directly adapted three postprocessor Java classes and bundled the unmodified 13,404,960-byte DocQuadNet model, with complete source headers, changed-package notice, licence, model provenance and hashes. No app UI, OpenCV binary or Paddle assets copied. Acceptance: offline model executed, fixed geometry did not regress; no overall superiority claim.
- ONNX Runtime Android1.24.1, official Maven CPU runtime. MIT and third-party texts; pinned source revision470ae16099a74fe05e31f2530489332c0525edb5, artifact hashes in assets/licenses/ONNXRuntime-provenance.json.
- User-provided CamScanner7.27.5 APK: static study only; exact hash and findings in CAMSCANNER-APK-ANALYSIS.md. Protected DEX/native code was not represented as recovered full source. No competitor binary/asset/runtime is shipped.
