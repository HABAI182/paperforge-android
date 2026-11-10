# Open-source research for automatic cropping

This file covers what PaperForge learned from open-source document scanners, what was tested, and what was adopted. The source of each project was read on GitHub. No code, weights or assets were copied into PaperForge. No CamScanner code or model was used: CamScanner appears only as a black-box app, whose exported pages are the accuracy standard.

## How crops are measured

- **Truth sets:**
  - the owner's seven photos, outlined by CamScanner's own exported pages registered back onto the photos;
  - one further photo of the owner's (a stack of notebook pages with a pen), outlined the same way;
  - the owner's four new photos and seven earlier problem photos, traced by hand;
  - the public DocUNet photos (15 with reliable truth).
- **Metrics:** IoU, page coverage, largest corner error, and excess background (area outside the page, as a share of the page). Each photo is also compared one by one against the shipped build, for its worst regression. The PC experiments run on PageNet's actual output; the PC mask scores track the device within about 0.01.

## Projects examined

| Project | Licence | Technique | Relevance |
|---|---|---|---|
| [mayuce/AndroidDocumentScanner](https://github.com/mayuce/AndroidDocumentScanner) | MIT | OpenCV `squares.cpp` scheme: Canny or threshold per channel, approxPolyDP, largest 4-gon with angle check | Low: PaperForge's mask is far stronger |
| [mukyasa/MMCamScanner](https://github.com/mukyasa/MMCamScanner) | Apache-2.0 | Same scheme, largest bounding rectangle | Low |
| [jhansireddy/AndroidScannerDemo](https://github.com/jhansireddy/AndroidScannerDemo) | MIT | Same scheme in JNI | Low |
| [ctodobom/OpenNoteScanner](https://github.com/ctodobom/OpenNoteScanner) | GPLv3 | Canny 75/200, first 4-gon by area that covers the frame centre | Low |
| [andrewdcampbell/OpenCV-Document-Scanner](https://github.com/andrewdcampbell/OpenCV-Document-Scanner) | none stated (ideas only) | LSD segments to corner candidates, quads ranked by interior-angle range (< 40°) | Medium: geometric veto |
| [WeTransferArchive/WeScan](https://github.com/WeTransferArchive/WeScan) | MIT | Vision rectangles plus `RectangleFeaturesFunnel`: votes over the last 8 preview quads | Medium: preview stability |
| [fengjian0106/hed-tutorial-for-document-scanning](https://github.com/fengjian0106/hed-tutorial-for-document-scanning) | none stated (ideas only) | HED edges, Hough, and corners that must own a segment along each side | Medium: stacked sheets, printed lines |
| [KhurramJaved96/Recursive-CNNs](https://github.com/KhurramJaved96/Recursive-CNNs) | Apache-2.0 | Coarse corner regression, then recursive per-corner crops | Medium: corner precision |
| [DocsaidLab/DocAligner](https://github.com/DocsaidLab/DocAligner) | Apache-2.0 (code) | Four corner heatmaps, threshold 0.3, largest blob | Medium: an extra network head |
| [niuwagege/LDRNet](https://github.com/niuwagege/LDRNet) | MIT | Corners plus border points with a straight-line loss | Low |
| [spmallick/learnopencv](https://github.com/spmallick/learnopencv) | none at repo level (ideas only) | GrabCut and DeepLabV3 masks, padding for corners outside the frame | Low |
| [navervision/mlsd](https://github.com/navervision/mlsd) | Apache-2.0 | Learned line-segment detector | Low: a second network |
| OpenCV `createLineSegmentDetector` (LSD) | Apache-2.0 | Line segments; available in OpenCV 4.12 | Tool |
| [Dropbox: Fast and Accurate Document Detection](https://dropbox.tech/machine-learning/fast-and-accurate-document-detection-for-scanning) | article, no code | Learned edges, Hough, quads scored by edge probability along the perimeter | Medium: quad scoring |

**Licences.** MIT and Apache-2.0 code may be combined with PaperForge's AGPL-3.0-or-later if the copyright notice and licence text are kept (Apache-2.0 also needs any NOTICE file and a note of changes). GPLv3 code may be combined under AGPLv3 §13, but those parts stay GPLv3. Repositories with no licence are all rights reserved: only their ideas can be reimplemented. **Nothing was copied, so no new attribution is needed.**

## Experiments (each against build 20038, same inputs and truth)

Measured on 35 photos: the 4 new, 7 problem, 8 CamScanner-standard (7 + 1) and 15 DocUNet photos.

| Approach | Source of the idea | IoU | Worst single-photo change | Cost | Decision |
|---|---|---|---|---|---|
| 20038 (PageNet v3 mask, quad fit) | — | 0.907 | — | ~1 ms post-processing | baseline |
| Boundary snap, inward only: move each side onto the strongest ridge of PageNet's own sheet-boundary map, within 6 % | Dropbox's perimeter edge score, used per side | **0.911** | **−0.001** | under 1 ms (160×160 map) | **Adopted** |
| Boundary snap, both directions | same | 0.910 | −0.054 | same | Rejected: pushes sides out onto neighbouring pages |
| Inward-biased robust side fitting | research item T3 | 0.909 | −0.122 | ~5 ms | Rejected: one bad regression |
| Top-sheet step edge in a band inside each side (window means) | HED corner-ownership idea, simplified | 0.84–0.89 | −0.35 | ~120 ms | Rejected: printed rules and double lines pass as steps |
| Same, with window medians and 15 % slant | same | 0.911 | −0.001 | ~500 ms in Python | Rejected: safe but never fires on the stacked photo, whose lower sheets are not in shade |
| Raising the mask cut-off (0.25–0.55) | DocAligner's higher threshold | 0.877–0.898 | — | 0 | Rejected: clips margins elsewhere |
| Averaging the v2 and v3 networks | — | 0.900 | — | +40 ms | Rejected |
| GrabCut recovery on every page | learnopencv | 0.883 | −0.33 | ~1.3 s | Kept for small sheets only (< 25 % of the frame) |
| Old classical steps: rule framing and sheet edge (device) | PaperForge 2.0 | 0.83–0.89 | — | +2.5–6 s | Rejected (off by default) |

**More PageNet training on fanned piles** (lower sheets also turned a few degrees) was tested too. Overall it got worse (0.898) and did not fix the stacked photo, so it was rejected.

**On the device** (emulator), the adopted boundary snap compared with 20038:

| Photos | 20038 | With snap |
|---|---|---|
| Owner's 4 new photos | 0.960 | 0.964 |
| Owner's 7, against CamScanner's crop | 0.941 | 0.947 |
| Stacked notebook pages (CamScanner's crop) | 0.881 | 0.882 |
| 7 problem photos | 0.889 | 0.889 |
| DocUNet (18) | 0.888 | 0.888 |
| Time per 12-MP photo | 0.12–0.15 s | 0.12–0.14 s |

No photo got worse by more than 0.01. The stacked notebook pages (fanned sheets, unshaded) remain the main open case.

## Round 2: the top sheet of a pile (build 20040)

The remaining hard case was a fanned pile of the same ruled notebook paper. On that photo (photo 33), CamScanner keeps only the top sheet, while PaperForge 20039 also took in the strips of the sheets below.

PageNet's mask covers the whole pile. Its boundary map gives **no** extra signal there: the strongest inner ridge on photo 33 (0.25) is within the range of single pages (0.08–0.50). So the evidence has to come from the photo itself, and it has to be cheap.

**Rule-ends top-sheet edge (adopted, `TopSheetRules`).** This is a version of the HED tutorial's corner-ownership rule, adapted to ruled pages: *an edge belongs to the top sheet when most of the rules just inside it end there, and the paper just outside still shows rule fragments at other heights.*
- A printed margin line has the same rules running straight across it, so it fails the test.
- A curved single page fails too: its rules carry on across the line.
- Blank paper beyond a margin shows no fragments, so it fails as well.

Steps:
1. Flatten the network's quad to 750 px.
2. Map the rules: a black-hat with a 1×7 element, then an opening with a 19×1 element.
3. For every candidate line in the outer 22 % of each side, slanted up to 15 % either way, count:
   - the inside rule peaks;
   - the share of them that end at the line;
   - the rule fragments outside at other heights.
4. Accept a line only if at least 70 % of the inside rules end at it and at least 5 fragments lie outside, and take the innermost such line.

Gates:
- It runs only on still photos.
- It runs only for sheets covering ≥ 25 % of the frame. Small receipts' text lines would pass for rules.
- It runs only when the page's rule direction dominates the other by at least 4×. On the test pages, ruled paper measures 4.5–26, while hatched covers, drawings and printed exams measure 0.6–3.9.

**Boundary snap v2 (adopted).** Changes from the first version:
- the angle range grows from ±3° to ±5°;
- long moves (up to 30 % of the frame, in or out) are allowed, but only onto a boundary-map ridge that is nearly continuous (90 % of samples above 0.35) and at least 0.3 stronger than the current line.

On the edge-ink test photo, the old ±3° search could not turn the top side the 7° it needed. With ±5°, that photo's worst corner error falls from 0.072 to 0.014, inside the test's 0.03 limit.

**Measured on the device** against 20039's settings, same photos, exported quads:

| Photos | 20039 | 20040 candidate |
|---|---|---|
| Owner's 4 new photos | 0.964 | **0.978** |
| 7 problem photos | 0.896 | **0.904** |
| Owner's 7 vs CamScanner | 0.942 | **0.946** |
| Photo 33 vs CamScanner | 0.882 | **0.969** |
| DocUNet (18) | 0.888 | 0.888 |
| All 39 | 0.908 | **0.914** |

Seven photos improved and none got worse. The biggest gains were photo 33 (+0.087), notes-right (+0.040) and blue-book (+0.027).

**Speed.** The debug build compiles OpenCV unoptimised, which inflated every earlier emulator timing of OpenCV steps. So timings now use an optimised OpenCV (`-PpaperforgeOpenCvOptimised=true`). With it, the rule-ends step costs about 30 ms (flatten 3 ms, rule map 6–8 ms, search 16–22 ms).

**Tried and rejected in this round:**
- Stepping inward over printed rules with the full test (the first versions): false hits on receipts and printed exams until the size and direction gates were added.
- Deciding the rule direction from page shape alone: it sent a hatched cover down the ruled path.
- A boundary-map "inner ridge" as a pile trigger: no separation from single pages.
- Robust inward side fitting: one photo lost 0.12.
- Retraining PageNet on fanned piles: worse overall (0.898) and no help on photo 33.

**Still open: a white cover on a white pad.** The boundary map reads only 0.06 along the cover's real top edge, so PageNet's outputs do not contain that edge. Fixing it would need either training data of white covers on white paper or a dedicated image-edge check. Its hand-set truth is also marked ambiguous.

## Round 3: multi-colour covers and notebook specks (build 20041)

**Projects added.** For each, the source was read and the licence checked.

| Project | Licence | Used for |
|---|---|---|
| [DocShot](https://github.com/Artemarius/DocShot) | MIT | Ideas only: the corner-constrained "Radon" search for a missing side. It scores a line by the gradient projected onto its normal (`LsdRadonDetector.verifyGradientDensity`). It does not test whether an edge is inner or outer. |
| [unpaper](https://github.com/unpaper/unpaper) | GPL-2.0-only (not combinable with AGPL) | Ideas only: the noise filter (removes tiny clusters) and the blur filter (wipes blocks whose neighbourhood has under 1 % ink density). |
| [ScanTailor](https://github.com/leomauro/scantailor) `Despeckle.cpp` | GPL-3.0-or-later | Idea only: a speck survives if it can reach large ink within a distance that grows with its size. |
| OpenCV FastLineDetector | Apache-2.0, opencv_contrib only | Not added: contrib is not in the build, and LSD is already in OpenCV 4.12. |
| [DocRes](https://github.com/ZZZHANG-jx/DocRes) (CVPR 2024) | MIT code; weights' terms unverified | Not feasible: a Restormer of about 13–15 M parameters, taking tens of seconds per page on a phone CPU. |
| [Doxa](https://github.com/brandonmpetty/Doxa) | CC0-1.0 | Reference for local thresholding (Wolf / Sauvola). |
| [noteshrink](https://github.com/mzucker/noteshrink) | MIT | Reference for separating background and foreground colour. |

**Cover cut at a printed colour boundary (adopted, `CoverOutline`).** PageNet's mask treats a dense dark hatched band as not-paper (every PageNet version scores 0.26–0.64 on the cover photo), and its boundary map has no ridge along the cover's real bottom edge. The step works like this:
1. It runs only when the mask's quad is nearly square (short/long ≥ 0.88). Pages and books measure about 0.7, and only 2 of 40 test photos pass this gate.
2. It looks outward along the two sides that would make the quad page-shaped, in CIELAB gradients projected onto each line's normal (as in DocShot).
3. It looks for a closing edge with at least 60 % support, with both neighbouring sides continuing straight out to it (at least 50 % support each, each allowed to shift up to 4 % of the frame).
4. The result must be page-shaped (≤ 0.85) and at most 2.2 times the area.

| Result (device) | Before | After |
|---|---|---|
| New cover photo, saved crop | 0.63 | **0.983** |
| New cover photo, live outline | 0.62 | **0.983** |

Every other photo is unchanged.

**Pale cover panel flattened to white (adopted, `ColorPage.isColorful`).** A page took the colour-preserving path only when at least 25 % of it was strongly coloured (chroma > 45). A crop covering mostly the pale panel was 12 % coloured, so it took the paper path. There the panel was whitened as "paper", and the blue prints got halos.

The new rule: also take the colour path when at least 8 % is coloured and at least 75 % of that colour survives a 9×9 opening (solid printed blocks, not pen strokes).
- Covers measure 9.6–95 % coloured, with 77–98 % of it solid.
- Notebook and document pages measure 0–3 %.

On the 22 %-coloured cover crop, the share of tinted pixels whitened fell from 85 % to under 1 %. White exercise-book covers now keep their printed stripes too.

**Notebook specks (not adopted).** Three prototypes were tested on the owner's three exported pages, with the circled regions as the target:
- **Relative-distance despeckle** (ScanTailor idea): removed real full stops between digits.
- **Local ink-density filter** (unpaper idea): removed 114 of 200 circled marks, but also a "<" sign, minus signs and pieces of strokes cut where rules cross them.
- **Edge-tick filter:** removed the circled rule-end ticks, but also pieces of absolute-value bars and brackets where they cross rules.

None is safe enough to ship. On the owner's photo 33, the stage images show:
- the circled marks are real show-through in the source, faint mirror-image writing from the reverse side;
- the emulator's tone stage removes almost all of it from the available 1279-px copy, so the remaining fragments in the phone export most likely come from the full-resolution capture, which carries more ghost detail.

A reliable fix needs the source photo's evidence of faintness and blur, not the finished page.

## Not adopted yet, worth trying later

- **L-junction corner test** (HED tutorial idea): a top-sheet corner ends both of its edges, while a lower sheet's edge runs on past it. It needs LSD segments in strips along each side; this is the most promising classical answer to fanned stacks.
- **Whole-quad scoring with geometric priors** (Dropbox perimeter score; angle range < 40°; plausible page aspect from vanishing points). It may separate a cover from the white pad under it, and one page from a two-page spread.
- **Preview funnel** (WeScan, MIT): vote over the last 8 preview quads and redraw only on a real change. PaperForge already waits for steady frames and glides the outline, so this was not needed now.
- **Corner refinement crops** (Recursive-CNNs, Apache-2.0): PageNet run once on a mosaic of four corner crops for finer corners. It would need training on corner crops.
