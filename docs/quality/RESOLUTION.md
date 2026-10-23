# Resolution and geometry experiment (T1)

Baseline source 4d56d55 is recorded in BASELINE.md. The former PDF path decoded a 3024×4032 input to half size and embedded a 1481×1975 crop. An explicit old render(3508) did retain the source pixels; therefore the loss was in sampling/quality coupling, not the JPEG slider itself.

Implementation: pure `ImageGeometry.decodePlan`, EXIF inverse/forward mapping, region decoding around the source quadrilateral before allocating a full image, and a bounded perspective warp followed by INTER_AREA reduction. Decode sample size retains the requested crop pixels where its 18 MP allocation budget permits. Smaller source crops are not upscaled. A centered-pinhole homography aspect estimate is accepted only with a plausible, well-conditioned focal estimate; otherwise opposite-edge geometry remains the fallback.

Draft/Standard/High are 150/220/300 dpi at A4 long-edge budgets, separate from JPEG quality. Auto page size now follows the scan aspect at the chosen DPI. Fixed paper sizes keep proportional fit and a 12pt margin. A source-limited crop may not reach the requested DPI; the code preserves actual pixels.

Validation: 11 JVM tests (8 existing + 3 sampling/EXIF/aspect tests) passed. API35 no-GMS instrumented resolution case passed: the 12 MP source renders at a 3508 px long edge for High, and at exactly 1200 px for preview. Preset/export UI and application assembleDebug compiled; final PDF dimensions are included in the end-to-end quality report after the encoding stage.

References inspected: native SDK OpenCvProcessor at 3ba5c50a and the v0.2.0 PaperForge pipeline. The new geometry/decoder is independent PaperForge code. Physical camera intrinsics, EXIF camera files and 50 MP peak resource use remain device-pending.
