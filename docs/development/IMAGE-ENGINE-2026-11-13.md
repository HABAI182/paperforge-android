# Image engine pass, 2026-11-13: line fidelity, live outline, speed

Owner report (OnePlus Ace 5, MobileNetV3 x1.5 detector):

1. The live detection outline flickers.
2. Enhancement invents straight lines and carries grids into blank paper. On the engineering worksheet,
   page 1 was destroyed, pages 3–4 had lines extended, and page 2 was skewed.
3. Enhancement is slow.

Everything below was measured on the owner's worksheet photo plus the eight g41 regression pages
(notebooks, a book held with fingers, exam and paper stacks). Owner photos are used for evaluation only.

## 1. Invented and extended lines

### Pipeline traced

Capture/import → `CaptureTasks` → `FlatDocument.prepare(automatic = true)` → `process()`:

1. `sourcePaper`;
2. `geometry` (RuleRectify → WorkbookStructure → TextLineDewarp / RuledPageRefinement → GridStraighten →
   TextLevel, one final remap);
3. `EnhancePipeline.apply("WORKBOOK")` → `lightFirstPage`, which runs `PaperField.edgePreserving`,
   `PageNetDetector.exterior`, `ToneZones`, `PaperEdgeGuard` and `ColorPage.keepPrints`.

Export (`ScanProcessor.render`) reuses that baked page.

### Root causes

- **ToneZones redrew rules from a model, not from the photo.**
  - `ruleTracks` carried every track on to the median reach of the page's rules and out to the crop frame.
  - `clear()` then wiped a band along the whole track and `draw()` redrew a synthetic line there.
  - On a page that is mostly grid this produced three faults:
    - grid lines ran on into the blank drawing panel;
    - a horizontal was drawn straight through the isometric drawings;
    - band clearing erased drawing strokes where they crossed a track.
  - Vertical tracks were also extended past the bottom of the grid.
- **RuledPageRefinement fitted a full-width curve to a ridge seen only partly.** The dynamic-programming
  path is forced across the whole width. Where the ridge was really seen over only part of the page, the
  rest of the curve followed noise and the drawings, which bent them (the skewed page 2).
- **`dropFrameLines` erased real printed lines near the crop edge.** This removed the grid's right
  border and bottom row. It was a known, documented trade-off from the stack-edge work.

### Fixes (all behind `scan_params` flags, defaults on)

| Change | Where | Flag |
|---|---|---|
| A rule track is drawn or cleared only where the photo shows it. Evidence is a pixel at or below the track's own level within ±1 px, or the rule mask within ±(2·scale+1) px. Gaps are bridged only when short (6·scale px), when faint along the whole reach, or when covered by a finger or warm occluder. A piece shorter than 8·scale px is left to the ordinary ink path. | `ToneZones.supported` | `toneZonesRuleEvidence*`, `toneZonesRuleWeak*`, `toneZonesRuleUnderOccluder` |
| A track is extended to the frame only if it already reaches the frame and the photo still shows a faint line there, or a finger covers it. | same | same |
| A line component near the crop frame is kept when it has paper on both sides at the side offset (relative ≥ 0.9) and a dark core (median ≤ 0.65). Stack edges and shadows fail that test and still go. | `ToneZones.dropFrameLines` | `toneZonesFrameLinePrinted`, `toneZonesFrameLinePaper`, `toneZonesFrameLineCore` |
| …and only when at least 3 of the page's own lines meet it from the page side (`toneZonesFrameLineCrossings`). A stack edge has paper on both sides too; a printed border has the grid running into it. | same | `toneZonesFrameLineCrossings`, `toneZonesFrameLineCrossingDark` |
| A ruled-line curve is used only if its ridge is observed (strength ≥ max(2, 0.25 × seed peak) and slope ≤ 0.15·step) over at least 45 % of the width, with gaps of up to 3 columns bridged. Outside the observed run the curve is held flat instead of following noise. | `RuledPageRefinement.estimate` | `ruledEvidenceSpan`, `ruledEvidenceMinimumSpan`, `ruledEvidenceSlope`, `ruledEvidenceGapColumns` |

The rule above treats **"dark output pixel with no darkening in the source within 3 px"** as invented
structure. "Dark" means below 128; "darkening" means at least 5 % below a 31 px closing-plus-blur estimate
of the paper.

| Page | Before | After | Pixels changed (>8 levels) |
|---|---:|---:|---:|
| Worksheet, device flatten (`-4-flat` vs geometry) | 3,129 | **0** | — |
| Worksheet, device tone step | 1,237 | **0** | — |
| math-booklet | 1,970 | 493 * | 0.094 % |
| shadow-notes | 1,451 | 105 | 0.128 % |
| uniform-stack | 1,124 | 0 | 0.101 % |
| p72-stack | 734 | 0 | 0.076 % |
| notes-stack-pen | 618 | 44 | 0.059 % |
| book-fingers, exam-stack, rotated-notes | 0 | 0 | ≤ 0.003 % |

\* The 493 px are a ruled line carried across the finger that covers the page. This is the one deliberate
bridge: the rule is seen on both sides and the finger is removed as not-page. Setting
`toneZonesRuleUnderOccluder` to 0 leaves a gap there instead.

On the worksheet the invented row at y ≈ 1472 is gone (that row's mean went from 119 to 223). The drawings
come out undistorted, and the grid's right border and bottom row are kept.

### Regression test

`GridFidelityTest` (androidTest) imports the photos in `files/grid-case` exactly as the gallery does. It
re-runs geometry and tone separately, saves every stage, and **fails** when the flatten or the tone step
has more than 2 unsupported dark pixels per 10,000. It passes on the emulator (0 and 0). The old build
fails it (3,129).

### Tried and not kept

- **Faint pencil inside the grid.** The worksheet's pencil sketches are only 6–10 % darker than the paper
  (ratio 0.90–0.94), and the grid cuts them into short pieces. A band-pass "long faint line" pass (depth
  0.03, length ≥ 30·scale, kept-ink subtracted, frame strip excluded) restored only 668 px of them. On the
  notebooks it added grey marks: a finger outline on math-booklet and streaks on shadow-notes and
  notes-stack-pen. It was removed. **Known limitation:** pencil this faint on a printed grid is still
  lost. Recovering it needs oriented grouping across grid crossings, not a lower threshold.
- **Pale printed labels near the page edge.** The light-blue 姓名 and 班级 labels in the worksheet's name
  box are lost in the tone step. They were already lost before this work.
  - Their darkest pixels pass the seed cut, but only in fragments smaller than the minimum seed (16 px).
  - Adding a "fragments of one text-sized mark" seeding rule recovered the seeds, but the show-through
    filter then dropped the characters as short pale marks: span < 20·scale and < 25 % of pixels below
    0.70.
  - That filter was tuned against show-through on thin notebook paper, so it was not loosened.
  - **Known limitation.** The likely way forward is a colour cue: the labels are as blue as the grid, while
    show-through is grey.

## 2. Live outline flicker

**Root cause.** The viewfinder drew `OutlineTracker.updateCurrent`, the outline accepted for capture. That
is null on a frame where any of these happen:

- the detector misses once;
- confidence is under 0.55;
- the detector proposes a different quad and the switch is still waiting for confirmation.

So each such frame took the outline off the screen and the next put it back. The MobileNetV3 engine's
confidence on a held page moves around 0.55, and the live path alternates between the detector quad and
the EdgeQuad/RuleFrame-refined quad, so the outline blinked. The outline colour also flipped at exactly
0.72.

**Fix (no extra smoothing):**

- `OutlineTracker.track` returns two outlines:
  - `shown` is held through one missed frame, a pending page switch, or a dip to ≥ 0.45 while the quad
    still matches the one on screen;
  - `accepted` is the current frame's confirmed outline, exactly as before.
- Capture still uses only `accepted`, so what is saved is unchanged.
- The colour turns green at 0.72 and returns to white only below 0.64.
- `OutlineTrackerTest` covers the new behaviour; the old tests are unchanged.

**Limitation:** not yet verified on the owner's phone camera.

## 3. Speed

**Method.** `EnhanceBenchmarkTest` (androidTest) runs `FlatDocument.process(automatic = true)` on the
pages in `<external files>/bench/in`: one warm-up run, then 5 measured runs. It reports the median, P90 and
P95 for every stage and the peak native heap.

The build is `-PpaperforgeDebuggable=false`. A debuggable build ran the Kotlin pixel loops about 75×
slower than the desktop JVM, so its timings say little about the phone. The emulator is x86_64 with 8
cores, so absolute numbers are higher than on a Snapdragon phone, but the before/after ratio is what
counts.

**Changes.** Each one leaves every pixel the same; that was checked byte for byte on all nine regression
pages on the desktop lab, and on the device by comparing the benchmark's saved pages.

1. `ColorPage.keepPrints`: the colour-print opening (an ellipse 25–35 px across), the dilation and the
   weight blur now run on `ParallelFilter` strips. It also returns before the opening when the coloured
   pixels are already under the share the opening's result must reach; an opening never adds pixels.
2. ToneZones' two per-pixel loops (tone mapping and colour restore) run in 8 contiguous parts via
   `ParallelFilter.ranges`.
3. Per-step timings were added for geometry (`g.*`) and colour prints (`cp.*`).

Tried and reverted: computing ToneZones' paper ratio and threshold masks with OpenCV `divide`/`compare`.
The output was identical, but those steps were 16–35 % slower on the device than the Kotlin loops.

Emulator, non-debuggable, 5 runs after one warm-up, median (P95) in ms. Every page's output was
byte-identical between the two builds.

| Page | Total before | Total after | Δ | Appearance before → after | Colour prints | ToneZones | Peak native |
|---|---:|---:|---:|---|---|---|---|
| worksheet 1302×1973 | 15,228 (15,343) | 11,113 (11,502) | −27 % | 9,278 → 5,594 (−40 %) | 3,486 → 269 | 3,655 → 3,419 | 289 → 299 MB |
| math-booklet 1813×2347 | 25,190 (33,620) | 19,861 (20,213) | −21 % | 17,773 → 12,681 (−29 %) | 7,730 → 2,176 | 6,324 → 6,680 | 478 → 486 MB |
| shadow-notes 2032×2704 | 34,517 (36,008) | 24,076 (24,221) | −30 % | 26,960 → 16,300 (−40 %) | 11,889 → 601 | 10,558 → 11,089 | 650 → 671 MB |
| book-fingers 1858×2800 | 43,332 (48,096) | 23,301 (23,493) | −46 % | 36,676 → 17,665 (−52 %) | 16,690 → 602 | 14,555 → 12,750 | 655 → 675 MB |

Within ToneZones, the two parallel loops went from 71–253 ms to 17–79 ms (about −70 %). The ToneZones
total barely moved, because its remaining time is in other steps.

**What is left, measured after the changes:**

| Step | Time |
|---|---:|
| `tz.finger` (pages with a finger) | 2.2–2.3 s |
| `tz.ruleLevel` | 0.7–2.2 s |
| `tz.ghosts` | 0.6–1.6 s |
| `paperField` | 1.7–4.1 s |
| Geometry: `g.textLevel` | 1.6–1.9 s |
| Geometry: `g.ruled` | 1.3 s |
| Geometry: `g.ruleRectify` | 0.2–0.9 s |
| Source preparation | 1.2–2.3 s |

These are the next targets. `TextLevel` re-analyses a remapped preview that the earlier stages have already
analysed, and `ruleLevel` and `inkFloor` build several full-size float arrays in Kotlin before blurring them
at quarter size.

**Preview versus final.** The live viewfinder already runs only the detector (`ScanProcessor.detectFrame`
on 1280×960 analysis frames). The flatten and enhancement run once per page in the capture task, under
`CaptureTasks.finishSlot`, and that task checks for cancellation at every progress step. Export re-renders
the baked page and does not repeat the enhancement. No separate fast preview path was added. The work above
shortens the one final path instead, so the page you see is the page you export.

## Tests

- Unit tests: 146 of 146 pass.
- Device tests (emulator, API 35, 22 classes) pass: GridFidelity, ToneZones20020, ShadowGhostRules,
  ShadowGhost20019, EdgeInkRules, RuleFrame, SheetFrame, Pipeline, ScanCoreWorkflow, PageNetV2Engine,
  ImportPath, PhoneCapture, CaptureCanonicalFrame, CaptureGeometryFlow, EndToEndPages, CameraQuality,
  Enhancement, EditorEntryLayout, SettingsProfiles.
- **ToneZonesRules** — the "line along the frame" case failed with the first version of the printed
  frame-line rule: a synthetic stack edge has paper on both sides. Fixed by the crossings requirement above.
  On the regression pages, that rule changed only one 40 px stub on rotated-notes, back to its original
  output; the worksheet border is still kept.
- **WorkbookEnhancement** — `workbookPresetSuppressesShowThroughAndKeepsInk` fails on the committed code
  as well (pencil p35 = 35 at HEAD and here, reproduced in the desktop lab).
  - Its synthetic "pencil" is six long, evenly spaced, 3 px horizontals at the darkness of a rule.
  - Since ToneZones (2.0) these are drawn as lines rather than kept as grey pencil.
  - **Pre-existing; not changed.**
- **CaptureTaskFlow** — `recoverRawDraftsPreserveEditsRetryAndRestoreTasks` fails twice, in
  `BackupStore.backup`: an export PDF recorded by an earlier test run on the same emulator is no longer on
  disk, and the backup opens every recorded file.
  - Not related to this work; the data layer is unchanged.
  - It is a real robustness gap, though: one missing export aborts the whole backup. Follow-up.

## Sources and licences

- No third-party code, models or datasets were added in this pass, and nothing was trained. Every fault
  traced back to rule logic (track extension, curve fitting, frame-line clearing, display state), not to a
  learned component, so a model would not have addressed it.
- OpenCV (Apache-2.0) supplies the morphology, connected components, compare and divide.
- `OutlineTracker` keeps its existing attribution to phillip9933/open-android-doc-scanner (Apache-2.0).

## Files

- `imaging/ToneZones.kt`: evidence-gated tracks, printed frame lines, parallel loops, OpenCV ratio and
  thresholds.
- `imaging/RuledPageRefinement.kt`: observed-span gate for the curve.
- `imaging/ColorPage.kt`, `imaging/ParallelFilter.kt`, `imaging/FlatDocument.kt`: speed and timings.
- `model/OutlineTracker.kt`, `ui/CameraScreen.kt`: flicker.
- Tests: `GridFidelityTest.kt` and `EnhanceBenchmarkTest.kt` (androidTest), `OutlineTrackerTest.kt` (unit).
- Build: `app/build.gradle.kts` gains the opt-in `paperforgeDebuggable` property, which affects debug builds
  only.
