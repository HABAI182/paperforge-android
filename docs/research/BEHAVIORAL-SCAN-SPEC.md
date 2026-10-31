# Independent scan behaviour - paired-photo experiment

## Evidence boundary

Three owner-supplied JPEG photos and one three-page owner-supplied reference export were inspected visually. Pairing is by handwritten content, not directory order: the algebra page maps to reference page 1, the longer trigonometry derivation to page 2, and the short limit calculation to page 3. Exact hashes, file names and the private images stay under `work/behavioral-v1/`, outside Git.

Only these observable inputs and exported outputs inform the behaviour notes below. No reference implementation, recovered model, resource or reverse-engineered algorithm is an input to the new independent engine. Existing engines remain installed and remain the default at the owner's request; the independent engine is opt-in.

## Short feature specifications

| Feature | Observed output | Independent implementation target | Unobserved / limitation |
|---|---|---|---|
| Crop | Each export contains one notebook page; keyboard, desk and the neighbouring page are excluded. Foreground handwriting remains inside the exported bounds. | Find the intended paper quadrilateral; preserve a small safety margin; retain editable corners and the original photo. Evaluate automatic selection separately from annotated-crop isolation. | The export cannot reveal whether the reference crop was selected automatically or manually. Manual corner annotations are approximate, especially at curved/occluded book corners. |
| Auto-edge / snap | The selected region follows the outer page rather than the printed rules or keyboard rectangle. | DocQuadNet/OpenCV consensus; original narrow-band OpenCV edge fitting; full-resolution recheck and non-blocking manual correction. Parameters live in `scan_params.json` / `ScanParams.kt`. | Static exports do not reveal drag snap thresholds, temporal smoothing or hysteresis. No such thresholds are claimed as observed reference behaviour. |
| Dewarp | Ruled notebook lines that curve near the spine in the photos appear substantially straighter in the exports. | An original OpenCV estimator groups dark ridges, robustly fits quadratic vertical curvature, interpolates a backward grid and remaps once. Keep a monotone map and a reversible grid; insufficient evidence leaves the original unchanged. | This first independent engine estimates vertical line curvature, not a complete 3D page surface or horizontal glyph-width reconstruction. Multi-column text, dense formula crossings and fingers can defeat grouping. |
| Auto-enhance | Paper is close to white, broad shadows/grey base and reverse-side writing are strongly reduced; foreground writing and ruled lines remain dark. | Reuse the independently written flat-field correction, soft monochrome ink ramp, workbook soft knee and restrained luminance sharpening. Sweep the paper/ink parameters against the same reference pages. | The reference filter name, strength and intermediate stages are unknown. Three monochrome examples say nothing about colour-photo enhancement. |

These are observed appearance targets and explicit implementation choices, not claims to have reconstructed private algorithms.

## Existing interfaces and switching

- Settings > Scan > paper detection: **Independent edge detection (trial)** uses `IndependentDocumentDetector`, with the permissively licensed DocQuadNet detector and the existing OpenCV detector. It never calls the old neural edge runtime.
- Settings > Scan > dewarp engine: **Independent text-line dewarp (trial)** uses `TextLineDewarp` / `IndependentPageDewarp` through `PageDewarp.process`. The existing engine remains the default; saved preferences and old assets are retained.
- `DetectionResult`, `PageDewarp.Result`, `DewarpGrid`, preview, apply and undo stay compatible. Metadata records the selected implementation fingerprint.
- Benchmark overrides are temporary snapshots via `ScanConfig.replaceForBenchmark` and are restored after each sweep. The Kotlin implementation is run directly on-device rather than being approximated by a Python port.

## Parameters for the first sweep

| Parameters | Purpose |
|---|---|
| `detectorConsensusShift`, `detectorSemanticConfidence` | Resolve geometric versus semantic detector agreement |
| `refineBandFraction`, `refineMinimumAreaRatio`, `cropSafetyMargin` | Snap search band and safe crop extent |
| `dewarpWorkEdge`, `dewarpThresholdWindow`, `dewarpThresholdBias` | Analysis resolution and ink extraction |
| `dewarpJoinFraction`, `dewarpMinimumLineSpan`, `dewarpMinimumLines` | Line grouping and evidence floor |
| `dewarpMaximumDisplacement`, `dewarpMinimumCurve`, `dewarpGridSide` | Curvature bounds and remap grid |
| `illuminationTilePercentile`, `illuminationStrength` | Paper illumination correction |
| `workbookGhostKnee`, `workbookInkGain` | Reverse-side attenuation versus faint-ink retention |
| `softMonoInkScale`, `textContrastBlend`, `textSharpenAmount` | Monochrome look and edge contrast |

## Benchmark and interpretation

`BehavioralScanBenchmarkTest` renders all modes and writes actual detected corners, confidence, method, dewarp evidence and timings. `scripts/bench/behavioral_scan.py` prepares private paired inputs, runs the focused device checks, pulls outputs, creates reference/crop/dewarp/enhancement contact sheets and reports gaps.

- Corner error and polygon IoU are against approximate owner-photo page annotations, with annotation uncertainty recorded. They are not dense dewarp ground truth.
- Visual differences use a content-registered homography proxy, report registration coverage/inliers and ink recall/precision proxies. They cannot prove OCR correctness or equivalent nonlinear geometry; failed registration remains visible in the report.
- The `isolation` profile uses the approximate manual crop to separate enhancement/dewarp limitations from automatic page selection. It is clearly marked and is not credited as an automatic detection result.
- User photos, reference exports and their contact sheets are private review artefacts. Only non-content numerical summaries are suitable for the repository.

## OSS actually reused

- OpenCV 4.12.0: image processing APIs and existing runtime, tracked by `opencv-mobile/UPSTREAM.json`. Backward-map semantics: https://docs.opencv.org/4.12.0/da/d54/group__imgproc__transform.html
- DocQuadNet / MakeACopy at `01bebd394b9dd6f3a692f28aea7c0638085eb4da`: the already-vendored inference model, letterbox and postprocessor. Its model-specific Apache-2.0 notice and hashes are retained in the DocQuad assets and `provenance.json`: https://github.com/egdels/makeacopy/blob/01bebd394b9dd6f3a692f28aea7c0638085eb4da/NOTICE
- Existing detector and enhancement components retain their existing source headers and OSS notices. The new ridge estimator, least-squares fit, interpolation and benchmark orchestration are original PaperForge code; no additional third-party dewarp source was copied.
