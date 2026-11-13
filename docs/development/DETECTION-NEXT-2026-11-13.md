# Detection and image engine, 2026-11-13 (third pass)

Regression case: the owner's open-notebook photo `work/enhance-lab/case-notebook-20261113/photo.jpg`, together
with its exported PDF (sha256 `ef0f495c…`). The right page is the target; the left page is cut off by the
photo's frame. The export contained a strip of the left page.

## 1. Neighbouring page in open books and notebooks

### Root cause

Both page networks segment all the paper they see. Two pages meeting at a gutter, with no background between
them, are one region, so the quad ran from the photo's left edge to the right page's outer edge. Both engines
did this: PageNet at x = 0.003–0.009 on the left side, MobileNetV3 at 0.005–0.006.

None of the later steps applied:

- `BookSpineDetector` exists, but it splits spreads in the editor and searches only the middle 30–70 % of a crop.
- `TopSheetRules` stopped at its first gate: dense handwriting with long fraction bars raised its stroke
  threshold above the notebook's own rules.

### Fix: `imaging/NeighborPage.kt`

The step runs after `TopSheetRules`, for both engines, on still detections only; the live preview outline does not
run it.

1. **When it runs.** Only when exactly one of the quad's left or right sides lies on the photo's frame, within 2 %.
   Top and bottom are off by default (`neighborPageAcross`): there, the cut-off objects were long receipts, and a
   crease across one passed for a gutter, making 9 of 12 crops worse.
2. **Finding the edge.** The page is warped flat. Between the cut side and 48 % of the width, the step looks for a
   long straight line with a same-sign brightness step on at least 60 % of the rows. Text and rules step both ways
   in short runs.
3. **Checking it is another sheet.** The paper's tone must differ across the line by at least 8 levels (upper
   quartile of strips on each side), and the line must not be coloured (saturation ≤ 60), so a red margin or
   coloured border does not count.
4. **Cutting.** The quad is cut 2 px past the line and keeps the side away from the frame. The result must keep
   at least 45 % of the area.
5. **Otherwise.** Nothing changes; the editor's manual crop stays the way to choose.

**Results.** On the notebook photo both engines now crop at the page edge: left side x = 0.168–0.184, against the
gutter at about 0.17. The comparison sheet is `case-notebook-20261113/notebook-before-after.jpg`.

Over all 1,368 held-out and regression photos and both engines, the step fired 3 times outside the notebook,
none worse:

| Photo | Engine | IoU before → after |
|---|---|---|
| public `0517` | PageNet | 0.38 → 0.55 |
| SmartDoc `background05-letter002-f020` | PageNet | +0.01 |
| SmartDoc `background05-magazine004-f049` | PageNet | +0.01 |

The 2 % frame test is strict: a neighbouring page that ends just inside the frame is not handled.

## 2. Default detector

All candidates were run through the full Kotlin crop pipeline, with the current sources (including the
rule-ends trigger and the neighbour-page step). The truth sets are independent; SmartDoc is split by
source document, receipts by number, and decisions use the test halves and public167. Owner photos are
regression only.

Script: `work/topsheet-decoder/compare_candidates.py`.

| Set | PageNet | MobileNetV3 ×1.5 | PageNet + plausibility fallback | MobileNetV3 + fallback |
|---|---|---|---|---|
| SmartDoc test half (450): mean / severe / bad | 0.840 / 89 / 101 | **0.952 / 2 / 18** | 0.892 / 51 / 58 | 0.944 / 9 / 24 |
| content cut (cov < .95) / spill (excess > .1) | 109 / 85 | **55 / 39** | 83 / 64 | 60 / 42 |
| Public167 | 0.842 / 15 / 41 | **0.939 / 4 / 9** | 0.890 / 8 / 25 | 0.931 / 5 / 11 |
| cut / spill | 74 / 21 | **21 / 11** | 55 / 17 | 26 / 11 |
| Receipts test half (99) | **0.915** / 1 / 8 | 0.911 / 2 / 8 | 0.916 / 1 / 9 | 0.912 / 2 / 7 |
| Owner (54, regression) | 0.915 / 0 / 4 | **0.921 / 0 / 3** | 0.914 / 0 / 4 | 0.917 / 0 / 4 |

- **Camera stability.** Corner shift between the preview detection and the still detection, as a share of the
  frame, at P95:

  | Engine | Owner | Receipts |
  |---|---|---|
  | PageNet | 0.037 | 0.083 |
  | MobileNetV3 | **0.012** | **0.037** |

- **Cost.** Network time on the emulator is 10.3–11.7 ms for MobileNetV3 against 10.7 ms for PageNet, with
  76 MB against 70 MB of native heap (`ModelBenchmarkTest`, earlier round). Desktop pipeline medians are
  104 ms against 117 ms on the owner set. The APK is 6.7 MB larger, as before.
- **Decision.** MobileNetV3 ×1.5 is the default in this experimental build: `SettingKeys.Detector` and the
  built-in setting profiles now use `PAGENET_V2`. PageNet stays selectable as "自动识边（PageNet）", and a
  setting already stored by the user is kept.
- **Not chosen.** The plausibility fallback improves PageNet a lot but never reaches MobileNetV3. On top of
  MobileNetV3 it costs accuracy.
- **Still open.** Receipts are slightly better with PageNet (0.915 against 0.911).
- **No release.** This is a test build; no official release is made.

## 3. Training

No new training round was run. The new failure is not a network error: both networks segment the paper
correctly, and the error was choosing the target page, which the geometric step above fixes. MobileNetV3's
remaining SmartDoc failures (2 severe out of 450) and the receipt gap gave no specific hypothesis worth a round.
No new datasets were added.

## 4. Enhancement fidelity

**Pale printed labels (姓名 / 班级).** The show-through filter removes them. Colour was measured as a
possible cue and rejected:

- The labels are nearly grey: median chroma 9.
- The show-through the filter correctly removes on the notebook pages has median chroma 11–34, up to 104 under
  warm light.

Rescuing coloured marks would bring show-through back and still miss the labels. They remain a known
limitation.

**Faint pencil inside printed grids.** Unchanged; it remains a limitation. See IMAGE-ENGINE-2026-11-13.md.

## 5. Speed

`TextLevel` checks four page bands, each turned through about 50 angles; this cost 1.6–1.9 s per page on the
emulator. The bands now run side by side on the filter threads (`ParallelFilter.tasks`) and give the same
angles.

`EnhanceBenchmarkTest`: emulator, non-debuggable build, 5 runs after a warm-up, medians. Every page's output is
byte-identical to the previous round's.

| Page | Original | Previous round | Now (P95) | Text levelling | Peak native |
|---|---:|---:|---:|---:|---:|
| worksheet 1302×1973 | 15.2 s | 11.1 s | **10.5 s** (10.7) | 1.60 → 1.21 s | 289 MB |
| math-booklet 1813×2347 | 25.2 s | 19.9 s | **18.3 s** (18.7) | 1.94 → 1.40 s | 469 MB |
| shadow-notes 2032×2704 | 34.5 s | 24.1 s | **22.0 s** (22.3) | 1.85 → 1.32 s | 650 MB |
| book-fingers 1858×2800 | 43.3 s | 23.3 s | **20.9 s** (21.9) | 1.69 → 1.16 s | 655 MB |

Overall that is 27–52 % faster than before the image-engine work.

**What is left.** The largest steps now are:

- `tz.finger` (2.2 s on pages with fingers);
- `tz.ruleLevel` and `tz.inkFloor` (0.4–2.2 s);
- `paperField` (1.7–4.1 s);
- ruled-line geometry (1.3 s);
- source preparation (1.2–2.3 s).

## 6. Tests (emulator, API 35)

- **Unit tests:** 149 of 149 pass.
- **Device tests, all 20 classes pass:**
  - Detection and capture: PageNetV2Engine (2), ScanCoreWorkflow (2), PhoneCapture (3), ImportPath (2),
    CaptureCanonicalFrame (2), CaptureGeometryFlow, quality.CameraQuality.
  - Data flow: LazyScanDraft (4), BackupMissingFile (4), BackupRestoreFlow, CaptureTaskFlow, OriginalExport (2),
    SettingsProfiles, EditorEntryLayout.
  - Image pipeline: GridFidelity, EndToEndPages, Pipeline (5), RuleFrame (5), SheetFrame (2), ToneZonesRules (20).
- **Two failures on the first run, from the emulator's test library, not from this round's code.** Earlier test
  runs had left 102,432 cancelled capture tasks whose documents were deleted. Every backup writes all tasks into
  its metadata, so `BackupRestoreFlowTest` ran out of memory building a 126 MB string, and `CaptureTaskFlowTest`
  ran for 1,603 s and missed its 60 s wait. After those orphaned, cancelled rows were removed on the emulator,
  both passed unchanged: 173 s and 113 s. No assertion was changed.
- **Gap noted, not fixed this round:** a backup builds its whole metadata in memory and keeps cancelled tasks
  whose documents are gone. A real library would rarely hold that many, but it could hit the same limit.
- **Not verified:** the notebook fix and the new default on the owner's phone; preview frame pacing on a device.
