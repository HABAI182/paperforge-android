# WORKBOOK intelligence implementation

Task book: `PaperForge-Workbook-Intelligence-Codex-Prompt.md`, SHA256 `67e3350b596c2e5e5e9b3190f09156dfd46622839b2f84847d97ecb501292392`. This is the received part 1; no additional part 2 text was provided. The code is integrated in the existing application, not a separate Python enhancement demo.

## Production changes

| Files (under `app/src/main` unless qualified) | Behaviour |
|---|---|
| `java/cn/paperforge/app/imaging/WorkbookStructure.kt` | Existing OpenCV LSD, normal contrast, angle/intercept clustering of interrupted rules, length-weighted consensus over distributed horizontal/vertical structures. Two long opposite paper boundaries can support the vertical axis. Bounded residual rotation/shear fits the complete source canvas. Weak evidence returns identity. |
| `imaging/TextLineDewarp.kt` (same Java package prefix) | Reuse the existing robust text-ridge fit as an orientation hint only when sufficiently distributed, straight and consistent. Do not introduce another text-line warper. |
| `imaging/FlatDocument.kt`, `imaging/PageDewarp.kt` | Analyze a thumbnail of the existing ruled-grid result; compose residual axis correction with that grid before one full-resolution residual remap. Coarse perspective cropping is still a separate preceding stage. Metadata records confidence, chosen map, fallback and configuration fingerprint. Automatic arbitrary A4 stretching is removed; explicit output aspect remains available. |
| `imaging/WorkbookContent.kt`, `imaging/InkComponents.kt` | Overlapping print-candidate, faint/handwriting, horizontal, vertical/table, figure, background, suspected show-through and uncertainty flags. Reuse OpenCV components to protect complete strokes and tiny punctuation. Rule-separated components avoid connecting every faint background mark through the table grid. These are heuristic evidence labels, **not semantic segmentation truth**. |
| `cpp/paper_pixels.cpp`, `imaging/PaperPixelEngine.kt` | Native component scoring and source guard. Broad weak unsupported residuals are attenuated, never deleted by area. Valid/uncertain source contrast and source colours constrain the candidate. No OCR rewriting or generative stroke completion. A low model score alone is insufficient evidence to erase ink. |
| `WorkbookStrokePolish` in `WorkbookContent.kt` | Small edge-aware smoothing on dark print candidates only; skips faint/uncertain/figure/rule pixels. Maximum change 3 grey levels by default. Each pixel stays in its 32-level bucket, preserving binary connectivity/holes exactly at thresholds 32,64,...,224. This does not prove topology at every possible threshold or improve all real printed fonts. |
| `imaging/EnhancePipeline.kt` | New route behind existing WORKBOOK/CLEAN interfaces. Reuses local paper field, rule evidence, old tone candidate and existing small-model inference. Bypasses both old destructive component/faint cleaners; blends user strength once. ORIGINAL/AUTO/TEXT/GRAY/BW/COLOR remain available. |
| `imaging/NeuralPaper.kt` | Existing DocRes restoration is retained. Final appearance is constrained by the same source guard. No new weights or new inference backend. Source geometry and original bitmap are not overwritten. |
| `imaging/DewarpTrace.kt`, `imaging/ScanProcessor.kt` | Replay the retained raw input through its immutable saved grid for later filter edits. Never estimate a second warp or apply another filter to the already enhanced image. Preserve the flat original as the unedited result and keep later crop/rotation parameters. Compose manual quarter-turn and small-angle deskew into the crop homography rather than rotating an already rendered page again. Fix pixel-centre extent accounting so repeated full-page quarter-turns do not shrink the image by one pixel each time. |
| `imaging/ScanParams.kt`, `assets/scan_params.json` | Versioned algorithm/config fingerprint, conservative defaults, internal strategy parameters, source-mask diagnostics and legacy rollback switches. No database migration. |
| `pdf/ScanPageStages.kt`, `pdf/PdfImageEnhancement.kt`, `pdf/RetouchJobs.kt`, `imaging/ScanRetouch.kt` | Export/retouch caches include the new fingerprint and detail metadata. Imported native PDF overlays, forms, annotations and text keep their geometry; only raster appearance uses shared enhancement. Raster geometry cannot be silently moved underneath native PDF text. |

The image-processing increment leaves the workspace/editor visual layout, icons and symmetry unchanged. A separately requested V7 root-gesture carrier is documented in `../quality/MOTION-V7-2026-11-02.md`.

## Conservative strategy and honest fallback

The requested policies are `CONSERVATIVE`, `STANDARD`, `STRONG`. A page containing unclassified faint source components automatically falls back from the latter two to the conservative floor. Requested/effective policy, ambiguous-pixel count and reason are available in `Decision`, diagnostics and benchmark JSON. The experiment switch `workbookAllowAmbiguousStrong=1` disables that safeguard; it is **off** in the review build. Preliminary stronger runs damaged faint-pencil masks and were rejected. Consequently the three requested policies may produce identical outputs on thin-paper pages. They must not be presented as three independently validated semantic cleanup models.

The existing user strength slider remains meaningful. Conservative cleanup intentionally leaves some ambiguous grey material. A front pencil stroke and a rear printed stroke with indistinguishable single-image evidence cannot be reliably separated by these heuristics. The private real-photo corpus has no masks proving that separation. No claim of 本地样本应用 parity or 100% valid-content recall on real photographs is made.

Rollback for development/benchmark: set `workbookIntelligence=0` for old appearance, `workbookIntelligentGeometry=0` for the old auto geometry. Existing explicit PageDewarp/model/manual controls are retained. This is a developer/config rollback, not a newly added user-facing switch. Existing originals, immutable grid resources, source geometry, undo and redo remain the recovery path.

Key defaults and diagnostics are in `scan_params.json`. `workbookDiagnostics=1` logs counts/decisions only; it does not log document text or upload images. No new network path exists.

## Tests and evidence tooling

- `app/src/androidTest/.../quality/WorkbookEnhancementTest.kt`: existing assertions retained; add the legacy route and the same fixture without injected show-through for a local contrast measurement. Compare fresh same-sized legacy/new outputs. The initial pre-change baseline used the previous one-pixel-shrinking transform and is historical audit evidence, not the final matched-resolution baseline.
- `full-app/src/androidTest/.../WorkbookIntelligenceTest.kt`: faint symbols, punctuation, colour, diagrams, thin rules, all policy requests, quantized topology, known synthetic affine/grid error, detector residuals, diagram rejection, immutable grid round trip, lossless repeated quarter-turns, crop+deskew, PDF pixel consistency, real-photo sweeps, existing learned compositor, repeated-page stability.
- `full-app/build.gradle.kts`: bring the existing app quality fixture and selected geometry JVM tests into the integrated app; avoid duplicating their source.
- `app/src/test/.../GeometryTest.kt`: repair the old fallback-name assertion (`纸匠文档` → the already established `PaperForge文档`); no production naming change.
- `scripts/workbook_intelligence_report.py`: reports actual Android-generated images, reuses `scan_flat_contact.py` metrics and registration. It does not replace Android rendering with a Python implementation. Private output must be outside this repository.

Build and instrument using the repository's full-app toolchain. After extracting `files/workbook-intelligence` and `files/workbook-quality` with `adb exec-out run-as ... tar`, generate contacts:

```text
python -X utf8 scripts/workbook_intelligence_report.py --evidence <private-extraction-root> --output <private-report-directory> --reference-dir <private-full-app-embedded-JPEGs>
```

A real-photo output generated on an emulator is not physical-phone acceptance. A 50-invocation stress run cycling eight photos is not 50 unique photographs. Sampled PSS/native heap is not an exact allocation peak. SIFT-aligned reference IoU/blank-region differences and detected rule curvature are proxies, not OCR accuracy or clean-image ground truth.

## Remaining acceptance work

1. Build the 50–100-photo authorized corpus with faint-ink/bleed masks, angle annotations and OCR/formula transcripts; current samples are insufficient for physical-camera, true CER/WER, blind-review or semantic recall claims.
2. Prioritize a replaceable small segmentation/confidence model only after independent real annotations and weight/data license checks. No new training/weights are authorized or bundled here.
3. Duplex assistance requires actual paired front/back photos and a registration rejection gate; none were supplied. Do not infer a missing back side.
4. Actual vector PDF export and contour re-rendering remain separate. This increment preserves raster glyphs and does not vectorize handwriting or invent a character skeleton.

## 2026-11-03 rejected-photo follow-up

The current revision is `workbook-content-3`, `ruled-residual-7`, `flat-paper-9`, `workbook-boundary-3` and `scan-image-codec-2`. Continuous bounded gain now requires positive whole-stroke support; unsupported smooth show-through is not re-darkened by the legacy candidate. Margin extension translates unsupported header/footer regions with their nearest reliable rule and fits the complete source extents. Boundary cleanup precedes geometry and is replayed from the retained raw source; low-frequency illumination filling never synthesizes covered text. Known exterior pixels are whitened again after enhancement to avoid a new dark halo.

Default-quality workbook PDF images use exact 8-bit Flate, including baked ORIGINAL pages carrying flat-document metadata. Explicit quality below 80 retains the JPEG small-file path. Both page-stage and export fingerprints include the codec. The actual processed real-photo image and decoded default PDF image have zero differing pixels; this is pixel fidelity, not text-ground-truth accuracy. No new model, dependency or network path is introduced. Full files, rejected experiments, actual tests, adverse reference metrics and remaining limitations are in [the November 3 review](../quality/WORKBOOK-REVIEW-2026-11-03.md).

## Previous compositor follow-up

`workbook-content-2` also caps suspected smooth background to its selected retained source contrast. In v1 the old tone candidate could override that limit and make the same weak residual dark again. Protected components, punctuation, rules, figures and colour remain exempt. Maximum detail preservation forces source retention to 1, including the smooth-background path. This is bounded contrast attenuation, not a claim that an AI can identify every rear-side glyph from a single photograph.

The retained q8 DocRes integration was checked against upstream `inference.py` at the revision in the sources table: binarization uses two output channels and softmax/argmax. The existing sigmoid of the channel-logit difference is consistent with that class probability. Upstream full-page FP16/GPU inference and our q8 CPU/context tiles do not have identical context or performance. No new weights are added.
