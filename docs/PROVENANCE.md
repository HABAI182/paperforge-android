# Code and model provenance rules

## The rule

Competitor apps are a target for **visible results only**: what their exported pages look like, and how their crops behave on the same photos. PaperForge does not use their decompiled code, recovered pseudocode, disassembly, extracted models or extracted resources, and does not train on models derived from them. This replaces the policy in `docs/reviews/CLAUDE-0.6.0.md` (internal record, kept in the private archive).

Every model in the app must have a recorded licence and a recorded training origin. `scripts/build_distribution.py` refuses to package any `.mnn` file, so the removed models cannot return from a stale local copy.

Running a competitor's app on the same photos and comparing its exported pages is allowed. Unpacking it, or bypassing its protection to run it, is not. On 2026-11-06 the a commercial scanner APK crashed at launch on an emulator because of its protection layer, and that was left as it was.

## Removed on 2026-11-05

| Removed | Why |
|---|---|
| `assets/scan/border.mnn`, `dewarp.mnn`, `detail.mnn`, `provenance.json` | Weights extracted from a commercial app's native libraries |
| `ScanModel`, `NeuralEdgeDetector`, `ScanDetailModel`, `DocumentDetail`, `WorkbookRestore`, the model path in `PageDewarp`, `scan_runtime.cpp`, the MNN build | Code that existed to run those weights, including recovered preprocessing |
| `OutlineTracker` as it was | Marked as adapted from recovered code. Rewritten from the Apache-2.0 upstream version in git history (`862fe2a`) and its behaviour tests |
| Automatic removal of one vendor's watermark from shared PDFs | Targeted that vendor's brand automatically. The manual PDF tool stays, for documents the user may modify |
| `docs/research/LOCAL-SCAN-APP-ANALYSIS.md` (deleted; not archived), `docs/research/DOCUMENT-DETAIL.md` (deleted; not archived) | Records of the static analysis |

## Current models

| Model | Origin | Licence |
|---|---|---|
| PageNet (`assets/pagenet`) | Original network, trained from random initialisation by `scripts/train_page_net.py` on `scripts/page_scenes.py` scenes | Project-owned weights. COCO val2017 photos were used as training backgrounds; those photos carry Flickr's various Creative Commons terms and are not redistributed |
| PageNet v2, opt-in (`assets/pagenet/pagenet-v2.*`) | Original MobileNetV3-Small-layout U-Net (width 1.5), trained from random initialisation by `scripts/train_page_models.py` on `scripts/page_scenes.py` / `scripts/realistic_scenes.py` scenes. A quarter of the batches were photographs from Mendeley Data x3nm4cxr83, training groups only. The checkpoint was chosen on half of SmartDoc 2015 Challenge 1 (Zenodo 1230218) and half of the Auad et al. receipt photos (Zenodo 13688441) | Project-owned weights. Mendeley x3nm4cxr83, SmartDoc 2015 and the receipt set are CC BY 4.0, credited here and in `pagenet-v2.json`. COCO val2017 as for PageNet. No dataset image is redistributed |
| DocQuadNet | MakeACopy | Apache-2.0 |
| DocRes | Published research model | MIT |
| PaperForge Ink v1 | Original, procedural data | Project-owned |
| Sub-Pixel CNN | ONNX Model Zoo | Apache-2.0 |
| PaddleOCR | Official release | Apache-2.0 |

## Still open

1. **Git history, public.** Checked 2026-11-05: `HABAI182/paperforge-android` is public. `border.mnn` and `dewarp.mnn` are in its history on 13 branches and in tags v0.5.0, v0.6.0, v1.0.0, v1.0.1 and v1.1.0, and in those releases' APKs and source archives. A rewritten history with no model files is ready locally at `work/history-clean/mirror.git`, verified clean, with a backup bundle beside it. **It has not been pushed**, so everything is still public. Even after the push, GitHub keeps `refs/pull/*` and cached commit IDs, so full removal needs the repo made private or deleted, or a request to GitHub Support. The local repository must be rewritten too, or a later push would upload the models again.
2. **Builds already shared.** Any APK up to 20021 contains the three removed models, including the public release APKs above.
3. **Code written after reading recovered material.** These records say analysis was read while the code was written, though the code itself was written for this project. They cannot prove a clean-room process:
   - an internal quality record of 2026-11-02 (kept by the owner, not published): `paper_pixels.cpp`, the `coherent-ink` compositor, `RuledPageRefinement`
   - `docs/quality/FEEDBACK-CAMERA-LIBRARY-2026-11-01.md` (internal record, kept in the private archive): paper field and rule protection in `EnhancePipeline`
   - `docs/quality/FEEDBACK-2026-11-01.md` (internal record, kept in the private archive): ridge fitting and paper curves
   - `docs/quality/SCAN-EXPERIENCE-0.6.1.md` (internal record, kept in the private archive), `docs/quality/V2.0.0-STATUS.md` (internal record, kept in the private archive): mentions, not yet reviewed in detail

   For a commercial release, have a lawyer review these, or have someone who has not seen that material reimplement them from a written specification.
4. **Other non-open-source components.** Google ML Kit (OCR and translation) is proprietary but licensed for this use under Google's terms. MuPDF is AGPL-3.0, which requires offering the app's source to anyone the app is distributed to.
5. **This file is engineering record-keeping, not legal advice.**
