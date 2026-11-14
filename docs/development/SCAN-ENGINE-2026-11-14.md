# Retake preview, open spreads, enhancement speed, detector names: 2026-11-14

Fourth pass on the engine3 build (`2.0.1-engine3`). Everything below was measured on this branch; nothing was pushed,
merged or released.

## 1. 此页重拍: the new photo appears as soon as its paper is found

**Reproduced.** `RetakePreviewTest` holds the slow finish (`CaptureTasks.finishSlot`) and looks at the page between
"paper found" and "finished". On engine3 the page still showed the old photo there; the test failed at its first
check (`retake-before.log`).

**Cause.** A new capture writes its photo and detected crop to the page right after detection and finishes it later.
A retake wrote nothing until the whole finish (flattening and enhancement, 10–20 s on a phone) had ended, so the
editor kept the old image, with a spinner, for that long.

**Changes.**

- The capture worker writes a retake's photo and crop to the page after detection, as for a new page, and records
  the page's new revision in the task. The finish replaces it when done; undo still steps back to the old photo.
- If the user changes the page after the new photo is shown, the finish is not laid over the changes.
- A second quick retake of the same page is still kept as a new page, as before; a failed or stopped retake leaves a
  consistent page.
- Thumbnails never show the previous photo while a new photo is being drawn; a new look of the same photo still
  keeps the old picture until the new one is ready, so filter changes do not flash.
- After a retake the editor opens on the retaken page, not the document's first page.

**Tests.** `RetakePreviewTest` (4): the new photo while finishing, the finished page and undo; two quick retakes and
a failed retake; a retake stopped during the finish; and the timing. On the emulator (debug build) the new photo was
on the page 3.1 s after the shutter, the finished page after 42.4 s; before, the old photo stayed for all 42 s.
`CaptureTaskFlowTest` and `LazyScanDraftTest` pass unchanged.

## 2. Open books and notebooks

### Independent photographs

There were three real spread photos in the work area, all with only a sliver of the facing page. For this pass
185 photographs were taken from Wikimedia Commons ("Open books", "Open notebooks", "Open Bibles"; CC BY, CC BY-SA,
CC0 and public domain; list with authors and licences in `work/spread-eval/ATTRIBUTION.md`):

- 167 open spreads: printed books, manuscripts, ledgers, ruled and squared notebooks, both pages in view;
- 18 single notebook or manuscript pages, as negatives from the same kind of material.

They were split by id: even ids chose the thresholds, odd ids are held out. The single-sheet sets of earlier rounds
(SmartDoc, receipts, public167, owner) were used for false alarms, with SmartDoc's and the receipts' selection halves
for choosing and their test halves, public167 and the owner photos only for reporting. Nothing was trained.

### What the detector does with a spread

On the dev spreads (contact sheet `work/spread-eval/dev-a.jpg`) the page network's crop:

- usually covers the whole spread, which is right when both pages are wanted;
- sometimes takes one page (002, 148, 150, 196, 224);
- on a few, is a diagonal or triangular quad across both pages (010, 146, 156, 168). This is the network fitting one
  quadrilateral to two curved pages. It is not fixed here: fixing it means training on real spreads with outlines,
  which this round did not have.

### The gutter

**Baseline.** The editor's `BookSpineDetector` (自动拆分双页) looks for a straight dark or white band through both
halves. On real spreads it found 10 % of the held-out gutters (8 of 80), and it took a fold, a crease, a book cover or
a soft shading change for one on 4.2 % of SmartDoc and 6 % of public167 (`work/spread-eval/fp-base.jpg`).

**`SpreadGutter`.** The paper's own brightness dips toward a fold from both sides, the whole way down. Measured on the
paper alone (the 80th percentile of each column, so print does not count), a gutter is a column between 30 and 70 %
of the width whose paper is darker than the paper 4 to 10 % away on *both* sides, where most of eight row bands have
their darkest paper too. Two-sided matters: the edge of a dark picture is darker on one side only, and those were
the commonest false gutters of a one-sided test. Three other cues were tried and dropped: an ink-free corridor
(the gutter's own shadow counted as ink), a tilted line through band minima (it fitted the gaps between text
columns), and a dip in the paper's outline at the top and bottom (too noisy).

| Held-out | Old (`BookSpineDetector`) | Editor (`SpreadGutter`, asked) | Capture mark (strict) |
|---|---|---|---|
| Commons spreads, test (80) | 8 (10 %) | **30 (37.5 %)** | 13 (16 %) |
| Commons single pages (18) | 0 | 0 | 0 |
| SmartDoc test half (450) | 21 (4.7 %) | 15 (3.3 %) | 1 of all 900 (0.1 %) |
| Receipts test half (97) | 0 | 0 | 0 |
| Public167 | 10 | 19 | 6 (2 real magazine spreads, 4 posters) |
| Owner photos (57) | 2 | 2 | 0 |

Gutter positions on the held-out hits are right on all but two or three (`work/spread-eval/g-test.jpg`).

**Where it is used.**

- *Editor, 自动拆分双页:* `SpreadGutter` with the asked thresholds replaces the band search. Kept as a second try, the
  band search added 2 spreads and 12 wrong splits, so it is not.
- *Capture:* on a still detection whose crop is shaped like two pages side by side (width over height 1.0 to 1.9;
  the dev spreads were 1.1 to 1.85, a sheet folded once is taller than wide), the strict test marks the capture as
  a spread. The crop is not changed: either page, or both, may be wanted. The editor then shows a strip:
  "这页像是书本或笔记本的左右两页 · 保留 · 拆为两页". Splitting goes through the existing split (left page first,
  original kept, undo works); keeping removes the mark. A split's halves carry no mark.
- *Crops.* No crop changed on any set (57 owner, 167 public, 198 receipts, 900 SmartDoc, 185 Commons photos and the
  notebook case, compared quad by quad with engine3).

**Partial neighbour pages** stay with `NeighborPage` (engine3): a facing page cut by the photo's left or right edge.

**Still open.** Most spreads are still not marked (16 %): soft gutters on flat-lying books, and crops that are one
page or a broken quad. Four posters of 167 in public167 get the strip (a vertical fold and colour blocks); the cost
is one tap on 保留.

## 3. Enhancement speed

### Profiling

`EnhanceBenchmarkTest` now also records the time spent in garbage collection and the Java heap's peak per page. The
automatic flatten was profiled on the emulator with `simpleperf` (root, non-debuggable build, call graphs):

- Gaussian blurs were 42 % of all samples, 35 % through the filter threads; morphology was another 18 %.
- The largest single blur was the paper field's: sigma about 18 px on the full-size float page.
- The coloured-print mask's opening used a 34 px ellipse at full size, which costs k² per pixel.
- Garbage collection took 1.1–1.9 s of each page (about 40 collections); the Java heap peaked at 113–204 MB.

Compiling the app ahead of time (`cmd package compile -m speed`) did not change the times, so the cost is in the
work itself, not in the interpreter.

### Changes kept

| Change | Effect | Output |
|---|---|---|
| `ParallelFilter.wideGaussian`: a blur of sigma ≥ 8 on a float field is done at half or a quarter of the size and drawn back up; used for the paper field and the print weight | paper field 4.8–11.1 s → 3.1–5.0 s | within 0.7 grey level on 99.9 % of the field's pixels (mean 0.004) |
| Coloured-print mask at half size (opening, widening, feather) on pages where its kernel is 17 px or more | print opening 4.5 s → 0.3 s on the page with a cover print | identical on the bench pages |
| Source preparation: the mask threshold and the stroke veto as whole-image operations instead of per-pixel loops | 3.4–6.2 s → 2.3–4.6 s | bit-for-bit the same pixels |

### Tried and dropped

- Rule levelling and the ink floor on OpenCV matrices instead of Kotlin arrays: the same output to the bit, but
  slower on the emulator (ink floor 2.8 → 4.5 s), and about 170 MB more native memory. Reverted.
- Widening the finger strip at a quarter of the size: no change in time, since its cost was elsewhere. Reverted.

### Result

Emulator (API 35, x86_64), non-debuggable build, five runs after a warm-up, the baseline (engine3) re-run back to back
with the new build on an idle host:

| Page | Before (P95) | After (P95) | Change | Peak native | Java peak | GC |
|---|---:|---:|---:|---:|---:|---:|
| worksheet 1302×1973 | 28.4 s (29.1) | **25.7 s** (25.8) | −10 % | 289 → 289 MB | 113 → 113 MB | 1.2 → 1.1 s |
| math-booklet 1813×2347 | 47.1 s (47.3) | **38.2 s** (38.5) | −19 % | 478 → 476 MB | 178 → 178 MB | 1.5 → 1.4 s |
| shadow-notes 2032×2704 | 59.9 s (60.1) | **50.8 s** (50.9) | −15 % | 650 → 650 MB | 204 → 204 MB | 1.9 → 1.8 s |
| book-fingers 1858×2800 | 57.4 s (58.0) | **49.5 s** (49.9) | −14 % | 655 → 655 MB | 204 → 204 MB | 1.6 → 1.5 s |

These absolute times are about 2.8 times those of the engine3 report: the emulator restarted after the PC's
crash of 2026-11-13 and has run slower since (the same engine3 build measured 58 s against 21 s that morning). Only
times measured in the same session are compared here.

Output: three pages are identical; on the book page 0.03 % of the pixels changed, all one-pixel shifts at the edges
of letters (`work/enhance-lab/r4-blur-diff.png`).

**What is left.** ToneZones is now the largest part (27–32 s here on pages with fingers or shadows): the finger zone,
rule levelling, line seeds and the show-through step, 4–6 s each on this emulator.

## 4. Show-through

Not changed this round. On the owner's notebook page (`work/enhance-lab/nb-full-overlay.jpg`) the current
step already clears the mirrored writing from the back (the large faint scribbles in the middle). What is left is a
handful of grey dots and ticks the size of a decimal point or a pencil mark. The step decides by darkness and
length, tuned in earlier rounds against the reference scanner. A sharper cue was looked at: show-through is diffused
by the paper, so its edges are softer than ink on the front. It could not be shown to be safe here without a labelled
set of show-through against faint pencil, and erasing a real mark is worse than leaving a speck, so nothing was
shipped. A public bleed-through set with ground truth exists (ISOS / Trinity College Dublin, 25 recto-verso
manuscript pairs) but is grayscale manuscript scans, far from phone photos of workbooks, and states no licence.

## 5. 纸张定位 names the real models

设置 → 扫描与拍摄 → 纸张定位 now says what each engine runs, with one line under the choices for the selected one:

| Stored value | Was | Now | What it is |
|---|---|---|---|
| `PAGENET_V2` (default) | 新模型识边 | MobileNetV3 ×1.5（推荐） | PaperForge's MobileNetV3-layout U-Net (width 1.5), then edge and layout correction; DocQuadNet and the edge detector if it finds no paper |
| `HYBRID` | 自动识边（PageNet） | PageNet（旧版） | the earlier default, PaperForge's small PageNet segmentation network, same correction |
| `STANDARD` | 传统识边 | 传统边缘检测 | no network: OpenCV edges and contours |
| `INDEPENDENT` | 独立识边（试用） | DocQuadNet + 边缘（试用） | MakeACopy's DocQuadNet-256 corner network cross-checked with the edge detector |

The stored values are unchanged, so a choice made under the old names is kept, and the 重置 button still returns to
the default. `DetectorChoicesTest` checks that every stored value keeps an option, the default comes first and the
labels name the models. The licence notice now calls PageNet v2 by the same name.

## 6. Tests (emulator, API 35)

- **Unit tests:** 151 of 151 pass (two new in `DetectorChoicesTest`).
- **Device tests, all pass:**
  - New this round: RetakePreview (4), SpreadGutter (3).
  - Detection and capture: PageNetV2Engine (2), ScanCoreWorkflow (2), PhoneCapture (3), ImportPath (2),
    CaptureCanonicalFrame (2), CaptureGeometryFlow, quality.CameraQuality, SpreadSplitSample.
  - Data flow: LazyScanDraft (4), BackupMissingFile (4), BackupRestoreFlow, CaptureTaskFlow, OriginalExport (2),
    SettingsProfiles, EditorEntryLayout.
  - Image pipeline: GridFidelity, EndToEndPages, Pipeline (5), RuleFrame (5), SheetFrame (2), ToneZonesRules (20).
- **Two failures on the first run, neither from this round's code:**
  - `SpreadSplitSampleTest` looked for the menu item 拆分双页 · 自动书脊, renamed in 2.0.0; it was not in the earlier
    rounds' list. It now opens 更多 → 页面编排 → 自动拆分双页; its assertions are unchanged, and it passes with the
    new gutter search (gutter at 46 % ± 2 %, inner edges, order, original kept).
  - `GridFidelityTest` needs the grid worksheet photo in the app's files; the emulator started from a clean image
    after the PC's crash. Pushed again, it passes.
- **Earlier fixes kept:** the smooth outline (`CaptureCanonicalFrame`, `PhoneCapture`), crops independent of the
  outline's display glide, no empty drafts (`LazyScanDraft`), backup and restore reporting (`BackupMissingFile`,
  `BackupRestoreFlow`), original passthrough (`OriginalExport`), MobileNetV3 as the default (`PageNetV2Engine`), grid
  fidelity and the rule tests.
- **Not verified on a phone:** the retake timing, the spread strip and the speed-up were measured on the emulator only.
