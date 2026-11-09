# OCR and workbook cleanup, 2026-11-03

This iteration uses the existing Android render/OCR interfaces. Owner photos and reference screenshots stay outside Git. No new network service, model download, binary dependency or upstream source file is added.

## Primary references and provenance

- [ML Kit input guidelines](https://developers.google.com/ml-kit/vision/text-recognition/v2/android#input_image_guidelines): character sampling, focus, avoiding unnecessary image size. The existing bundled Chinese/Latin 16.0.1 recognizers are retained; ML Kit is a Google SDK, not an open-source model. This iteration does not describe it as OSS.
- [PaddleOCR pipeline](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/OCR.en.md) and [Apache-2.0 license](https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE), inspected 2026-11-03: orientation, geometry preparation, detection and recognition are separate stages. The existing `ocr-paddle` adapter/runtime remains pinned at its previously recorded source revision. The optional installed PP-OCRv5 pack is reused for comparison; no new weights are bundled. Pack detection/recognition hashes and source revisions remain in its manifest/provenance.
- [Tesseract quality guide](https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html): illumination, sampling and deskew are input problems, and aggressive morphological operations can change characters. Research only; no Tesseract source or traineddata copied.
- [OpenCV GrabCut](https://docs.opencv.org/4.x/d8/d83/tutorial_py_grabcut.html), [OpenCV 4.12.0 Apache-2.0 license](https://github.com/opencv/opencv/blob/4.12.0/LICENSE): the already bundled implementation supplies graph-cut refinement. Original PaperForge code supplies the bounded ROI and mask seeds; no tutorial source code copied. Existing OpenCV notices remain applicable.

## Implementation decisions

1. OCR receives pixels in the same geometry as the displayed/exported page. An original filter receives the retained camera crop; a flattened workbook receives sensor tone replayed through its saved grid. Brightness/filter tone does not become OCR truth. OCR request fingerprints invalidate older results.
2. Shared `OcrLineFusion` retains observed whole-line hypotheses and can include newly recovered non-overlapping rows. The previous merge visited only first-pass rows and discarded entire rows recovered later. Overlapping fragments/duplicates are excluded; Chinese text cannot be replaced by a Latin-only hypothesis. Geometry and element boxes are retained.
3. `OcrInputPreparation` is a separate illumination-only view: existing bounded paper-field estimation plus OpenCV division. There is no glyph reconstruction, stroke dilation, erasure or generative repainting. Its result supplements, rather than replaces, the unmodified view.
4. A real code-book comparison rejected an automatic whole-page Latin supplement: it did not improve this photograph. A whole-page paper-only alternative also lost anchors. Code pages instead use bounded uncertain-line tiles at a model-appropriate height, retaining the original recognition as the first hypothesis. Both rejected experiments remain in local evidence.
5. The optional Paddle engine reuses the illumination view and the same line fusion when the selected pack has low-confidence results. It remains optional and its model identity is pinned for the operation.
   Automatic selection is the default for unconfigured preferences: use an installed language-matching pack, otherwise use bundled ML Kit. Explicit ML Kit/Paddle choices are respected. No model is downloaded implicitly or newly bundled by this change.
6. Hand refinement starts only from an accepted broad, edge-connected skin component. GrabCut sees at most 180,000 coarse pixels, two default iterations and a narrow neighbourhood. A one-pixel contour allowance includes neutral antialias/shadow rims. This removes observed occluder pixels; it cannot recover text physically hidden by a hand.
7. Strong background cleanup has its own negative-evidence thresholds. Ruled lines, colour, figures and supported strokes retain their guards. An experimental relaxation of weak anchors is compared separately and is not automatically enabled.
8. Geometry analysis uses the cleaned probe, while the final remap samples original colour. Erasing skin before sampling had deprived final edge cleanup of its input evidence. The saved-grid filter path shares the raw-colour sampler; zero-strength grids bypass coarse interpolation and are true identities.

## Evidence boundaries

The real source photograph is 1279 × 1706. This iteration's OCR view is a saved 927 × 1400 crop. Reference-app outputs are screenshots, not pixel-aligned clean ground truth. Partial manually transcribed anchors do not measure full-page CER. Synthetic faint/punctuation masks verify only the generated fixture, not arbitrary pencil writing. Emulator timing is not physical-phone latency. The existing tiny `paper-ink-v1` classifier is procedural-data trained and is not a calibrated semantic front/back text classifier.

Build, test, image comparison and unresolved results are recorded separately after actual runs.

See [the measured iteration record](../quality/OCR-WORKBOOK-2026-11-03.md). A further diffuse-patch/figure-guard exception was tested and removed: it did not materially improve the selected real patch. It is not a shipping cleanup decision.
