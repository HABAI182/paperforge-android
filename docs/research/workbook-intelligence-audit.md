# Workbook intelligence: audit (2026-11-02)

Scope: incremental WORKBOOK work on `9f029da`; #22 stays closed, #21/#7/#23 stay open. No new model, remote inference, issue closure or release is implied.

| Existing module | Observed behaviour / evidence | Change selected |
|---|---|---|
| `EnhancePipeline.localWorkbook` | Local illumination + native tone curve + existing tiny model. `InkComponents.cleanFaint` can whiten an isolated faint stroke based on low model confidence. `clean` can discard punctuation by area. | Protect source evidence before suppression; bypass both destructive cleaners in the new route. Keep a benchmark rollback switch. |
| `RuledPageRefinement` / `FlatDocument.geometry` | Horizontal ridge tracking; no vertical-frame consensus. Low-confidence ruled failure falls through to a learned grid. | Reuse ridge tracking, add distributed horizontal/vertical constraints, compose maps once; uncertain intelligent geometry returns identity. Explicit legacy/manual dewarp remains available. |
| `ScanProcessor` | Perspective, optional resize, then a separate arbitrary-angle rotation. | Audit and compose compatible geometry where metadata permits; avoid changing OCR coordinates silently. |
| `NeuralPaper` | Existing DocRes foreground + source compositor. A model probability is not semantic ground truth. | Reuse inference and route final source protection through the shared module; no new weights. |
| `PdfImageEnhancement` | Copy-on-write raster XObjects; native text, forms, annotations and masks retained. | Share content processing, version cache signatures. Do not move image pixels under native PDF overlays by silently adding geometry. |
| `ScanPageStages` | Resumable page cache misses enhancement algorithm/config version. | Include the algorithm/config fingerprint and detail metadata. |
| Existing quality test | Full-app did not include app's `quality/WorkbookEnhancementTest.kt`. | Reuse via generated test source; retain original assertions. |

Fresh baseline: Android API 35 x86_64 emulator, JDK 17, full-app debug. Build passed. Original synthetic acceptance **failed**: pencil percentile 35 = 87 (expected 120–238); print 0, rules 46, ghost mean 244.237, paper 254.266. This is excessive darkening, not proof that real pencil is erased. The audit's deletion risks are separate code findings. Preserve the failure and compare the same fixture after implementation.

Private corpus currently contains eight user photographs (seven prior crops plus the open notebook photo), three paired embedded JPEGs from the full 本地样本应用 reference PDF, and one screenshot reference. It is not a 50–100 photo corpus and has no pixel-perfect ink truth, manual angle annotation, duplex pair, OCR transcript or independent blind review. Real-photo processing on an emulator does not constitute physical-phone acceptance.

Acceptance priority: valid content and topology, geometry, bleed reduction, edge appearance, then speed. Heuristic masks must be labelled as evidence, not claimed to be an AI semantic segmentation model. Uncertain content stays visible. A cleaner white background alone cannot pass acceptance.
