# Book mode, neighbour pages, show-through, CLEAN merge: 2026-11-14 (second pass)

Fifth pass on the engine4 build (`2.0.1-engine4`). Everything below was measured on this branch; nothing was pushed,
merged or released.

## 1. Book mode: 左页 / 右页 / 两页, never a silent centre cut

**Requirement.** Keep the Left / Right / Both workflow, but an uncertain gutter must not be cut at the centre; the
user places the split, sees what is kept, and confirms. Validated on real off-centre and curved spreads.

**What the camera's 书本 mode does now** (`BookPages`, `CaptureTasks`):

- After detection the crop is examined for a gutter in four ways, strongest first: the paper's own shadow along the
  fold (`SpreadGutter` strict) → a weaker shadow (`SpreadGutter` asked) → a straight band (`BookSpineDetector`) →
  nothing (the middle).
- Only the strict shadow is trusted to cut on its own. Then 左页 / 右页 keep that half and 两页 makes two finished
  pages, left first (`Repository.insertAfter`, undoable).
- Any weaker answer cuts **nothing**: the page is kept whole and unflattened, marked `bookPending` with the suggested
  line, and the editor shows a strip "书本：没有找到确定的书脊，照片还没分割" with [保留整页] [确认分割].
- 确认分割 opens the split editor (`BookSplitDialog`) at the suggested line: both ends of the spine are dragged
  along the page's top and bottom edges (curved/tilted folds), the half that will be dropped is shaded, and nothing
  is cut until 分割并保留原图. The photo is kept; undo restores the page.
- The same dialog is reachable for any page from the editor's spread strip (左页 / 右页 / 两页 / 保留整页) and from
  「书籍双页与书脊」.

**Tests.** `BookCaptureTest` (4 tests, all pass, default detector MobileNetV3 ×1.5):

- A synthetic spread with a fold shadow gives 左页, 右页, and 两页 (two finished pages, left first, undo takes the
  right one out). A single sheet in 书本 mode is never cut.
- A spread whose gutter casts no shadow stays whole and pending. The user's split (as the dialog calls it) then
  keeps the left page, and undo restores the spread.
- **14 real spreads**: the owner's 4 (11-13 notebook, 11-14 spread, grid sheet, right page) and 10 held-out Commons
  photographs (printed books, manuscripts, a notebook, curved and off-centre). 书本 · 左页 on each:

| result | photos |
|---|---|
| cut at a certain fold shadow (left page correct by eye) | commons 007, 297, 409 |
| kept whole, split waits for the user (suggested line 0.46–0.54) | commons 065, 117, 193, 251, 339, 453, 507 |
| one page in the crop (the neighbour-page cut already removed the facing page), left whole | owner notebook 11-13, owner spread 11-14 |
| a single sheet, left whole | owner grid 11-14, owner right page 11-14 |

No photo was cut at a guessed centre, and no page content was dropped.

An earlier run used the old PageNet left behind in the emulator by another test (`detector=HYBRID`). It was
pinned to the default for these results. With the old detector, two synthetic cases failed, because their crop
lost the left quarter of the spread.

## 2. Neighbour-page leakage on curved edges (`NeighborPage` v2)

The owner's 11-14 spread: the crop ran into the facing page because the boundary between pages was a curved shadow,
not a straight line. A seam search (dynamic programming, ±1 px per row, both polarities) now finds a curved boundary
when no straight one has enough support; the cut is fitted through it and moved to the neighbour's side, so no page
content is lost. A seam is accepted only if it is a single step (not a stroke pair, paired share ≤ .40) and little ink
lies on it (≤ .15): without the ink guard, four receipts lost a strip of print.

Crop regressions (JVM runner, production model): owner 0/57 changed, public167 0, receipts 0/198, SmartDoc 0/900,
11-13 notebook 0; Commons spreads: one photo changed (a cat, not a document). The owner's spread is now cut at the
gutter (support .59, paired .33, ink .09).

## 3. Show-through: not solved — what was measured

**Independent benchmark** (`work/showthrough-bench`): 8 crops, each labelled by eye with boxes for reverse-side bleed
(must clear), faint or small real front marks — pencil, punctuation, pale print, construction lines — (must keep),
and page-edge artifacts. dev-* crops choose methods, test-* judge them. Scores: bleed residue per 1000 px, keep as a
share of the baseline, edge residue.

Baseline: grid 11-13 [3.5, 6.2, 42.1], grid 11-14 [13.3, 11.2], spread edge 99.8, notebook/shadow/math ≈ 0.

**What separates bleed from faint front content, measured per mark:**

| cue | bleed | faint front | separates? |
|---|---|---|---|
| darkness (depth below paper) | 12–58, median 18 | pale printed labels 13–23 | no, on printed sheets |
| edge sharpness for its depth | .25 | .27 | no |
| colour shift vs paper (Lab) | −.12 b/L on grid 11-14 | −.14 | no (the back is the same blue print) |
| isolation (no ink within 12 px) | 0/41 isolated | — | no (bleed sits between grid lines) |

**Candidate: grid-twin gate** (`toneZonesStraightGrid`, off). On a sheet whose dark ink forms an evenly spaced grid,
faint straight lines in the grid's own directions are no longer rescued as "construction lines". On the benchmark it
cleared the reverse-side rectangle on the 11-14 grid sheet (13.3 → 10.3, 4 449 px removed, none added, keep ≥ .999)
and changed nothing on the drawing or spreads. On 103 wider photos it fired on 54 and removed a short piece of a real
construction line on the owner's drawing photo, so it fails the content rule and stays **off**.

Research directions not finished (literature: single-sided show-through removal mostly relies on learned priors or
on registering the back page): a small learned classifier needs labelled real pages of both kinds; the benchmark
above is the start of that set.

## 4. 强力去背景 merged into 练习册

It took the same tone route as 练习册 on light pages, and only its PDF pages were greyer (grey JPEG instead of
练习册's encoding). The two filters are now one:

- Saved pages with filter `CLEAN` render through the 练习册 path and are labelled 练习册.
- They are exported with 练习册's encoding.
- The camera's 去透页 mode is gone.
- A stored `filter=CLEAN` or `captureMode=clean` moves to 练习册 or 扫描文档 once (`cleanMergedV1`). Later choices are
  left alone.

Pages saved with a finished appearance (`flatDocument.bakedFilter=CLEAN`) keep the pixels they were finished with,
so an old document looks exactly as it did.

**Tests.** `CleanPresetMigrationTest` (3, pass):

- a saved CLEAN page renders pixel-identical to WORKBOOK (0 differing pixels at 1400 px);
- its PDF image has the same size, filters, and colour space as WORKBOOK's;
- the stored choices migrate once, and other values are untouched.

## 5. Tests

Device (emulator, debug build): BookCaptureTest 4, CleanPresetMigrationTest 3, SpreadSplitSampleTest 1,
RetakePreviewTest 4, PageNetV2EngineTest 2, ScanCoreWorkflowTest 2, PhoneCaptureTest 3, ImportPathTest 2,
CaptureCanonicalFrameTest 2, CaptureGeometryFlowTest 1, LazyScanDraftTest 4, BackupMissingFileTest 4,
BackupRestoreFlowTest 1, CaptureTaskFlowTest 1, GridFidelityTest 1, EndToEndPagesTest 1, PipelineTest 5,
RuleFrameTest 5, SheetFrameTest 2, SettingsProfilesTest 1, EditorEntryLayoutTest 1, quality.CameraQualityTest 1,
OriginalExportTest 2, ToneZonesRulesTest 20: all pass. Unit tests: 151, all pass.

Speed: this pass changed no hot path. The round-4 measurements stand (−10 to −19 % per page). Profiling (simpleperf
on the rootable emulator) showed the remaining ToneZones time is Kotlin loop self time. An earlier vectorised
attempt was slower and used more native memory, so it was not kept.
