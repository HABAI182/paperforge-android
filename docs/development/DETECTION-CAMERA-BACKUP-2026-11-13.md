# Detection, live outline, empty drafts, backup: 2026-11-13 (second pass)

## 1. Detection accuracy

### Plausibility checks

**What was measured.** Geometric plausibility of every final still quad of both engines, on the existing
held-out sets (SmartDoc test half, receipts test half, public167, owner), with the selection half used only
for choosing thresholds. The features were:

- convexity;
- interior angles;
- the focal length implied by the quad as a rectangle seen in perspective (Zhang & He, *Whiteboard scanning
  and image enhancement*, 2007);
- the aspect of that rectangle;
- the share of each side with an intensity step across it.

The checks never squeeze a valid perspective trapezoid into a rectangle; they only score it.

The script is `work/topsheet-decoder/plausibility.py`, and the evaluation is `plausibility_eval.py`.

| Use | Test half: mean IoU / severe / bad |
|---|---|
| MobileNetV3 ×1.5 alone | 0.940 / 5 / 35 |
| MobileNetV3, production when its quad looks implausible | 0.934–0.936 / 9–12 / 38–41 |
| Production PageNet alone | 0.849 / 91 / 116 |
| Production, MobileNetV3 when its quad looks implausible | **0.893 / 51 / 74** |

**Decision.**

- **MobileNetV3:** its remaining failures do not look geometrically odd, and the check fires on many of
  its good crops (about 1 in 5 flags is a real failure). Swapping on it makes MobileNetV3 worse, so it is
  **not used to change crops**.
- **Production:** the fallback is a large gain on the default engine. But it runs the second network and
  changes the default detector's behaviour, so it is **left for the owner's approval**, not switched on.
- **The bigger step:** promoting MobileNetV3 (0.940 / 5 severe) is the larger gain than the fallback.

### Ruled-sheet trigger (`TopSheetRules`)

**Where MobileNetV3's failures are.** Of its 50 crops under 0.8 IoU on held-out data, 12 were receipts that
the top-sheet step cut down to a block of text.

**Cause.** The step's gate counted rows of horizontal stroke energy in the middle fifth of the page, and
printed text rows pass that.

**Fix.** A ruled sheet now also needs at least 6 lines that each carry rule energy over 60 % of the page's
inner width, at even spacing: the median absolute deviation of the gaps must be at most 25 % of the median
gap. The flags are `ruleEndsLongLines`, `ruleEndsLineCoverage`, `ruleEndsMinimumLines` and
`ruleEndsSpacingSpread`. The values were set before scoring and not tuned on any set.

**Results** (desktop runner on the app's Kotlin sources; mean IoU / severe / bad):

| Engine, set | Before | After |
|---|---|---|
| MobileNetV3, receipts test half | 0.883 / 3 / 16 | **0.911 / 2 / 8** |
| MobileNetV3, receipts selection half | 0.880 / 3 / 20 | 0.911 / 1 / 11 |
| MobileNetV3, public167 | 0.936 / 4 / 11 | 0.939 / 4 / 9 |
| MobileNetV3, owner | 0.921 / 0 / 3 | 0.921 / 0 / 3 |
| Production, public167 | 0.836 / 15 / 45 | 0.841 / 16 / 41 |
| Production, owner | 0.915 / 0 / 4 | 0.915 / 0 / 4 (no crop changed) |

The one owner photo that changed is `p72-stack` with MobileNetV3, 0.93 → 0.92. The step no longer fires
there, so a thin strip of the sheet below stays at the left edge.

Visual comparison: `work/topsheet-decoder/rule-ends-trigger.jpg`.

**Runner pitfall found on the way.** `run_jvm.py` keys its network-output cache by the `--model` argument.
A MobileNetV3 run without `--model pagenet-v2.onnx` silently reuses the production network's cached
outputs. Always pass the model.

### Not done

- New real photographs of the hard cases (stacked sheets, covers, low contrast). None were captured.
- No retraining: there was no evidence that it was needed.
- The alternative open-source detectors are not re-surveyed here; see `DETECTION-RESEARCH-2026-11.md`.
- The default detector is unchanged.

## 2. Live outline: smooth again, never stale for capture

### Root causes

1. **The corner glide was removed.** A critically damped spring on each corner was taken out in `b605db4`
   (2026-11-11) so the displayed outline would equal the persisted crop. Since then the outline stepped
   about six times a second, once per analysis frame (160 ms).
2. **The average was too strong.** The tracker averaged each frame a third of the way toward the
   detection, which kept a slowly moved page about half a second behind.
3. **Fast moves froze, then jumped.** A shift over 4 % of the frame is treated as a possible page switch and
   needs two agreeing frames. During a fast move no two frames agree, so the outline froze until the hand
   slowed down, then jumped.

### Changes

- **Display-only glide.** Every display frame, each corner closes the gap to the latest outline with a
  time constant of 90 ms, or 45 ms when it is more than 5 % of the screen away. A new outline appears in
  place, and a lost one goes at once.
- **Adaptive averaging.** The full average applies only to jitter (under 0.6 % of the frame) and fades out
  by 2.5 %, so a real move is followed.
- **Moves are followed.** The same shape moving on in the same direction confirms a move. A confirmed fast
  move is followed frame by frame while it keeps its shape and direction. A one-frame jump to a different
  shape is still ignored.
- **Capture is unchanged.** Capture uses only the tracker's accepted outline for the current frame,
  never the glide's in-between corners.

**Tests.** `OutlineTrackerTest` covers slow moves, fast moves, wrong-shape jumps, and the earlier flicker
cases. Two earlier assertions expected a 3 % move to be averaged to a third; they now use a jitter-sized
move, which keeps what they check, because following a 3 % move is the intended change.

## 3. No empty scan drafts

**Cause.** Opening the camera called `Repository.newScan()`, which wrote the document at once, so leaving
without a photo left an empty "0 页" document.

**Change.**

- The camera and the gallery import now start from `Repository.draftScan()`, which keeps the row in memory.
- `ensureDocument` writes it inside the transaction that saves its first capture task
  (`CaptureTasks.prepare`) or page (`addCapture`, library import). A rolled-back save writes nothing.
- A failed import leaves neither a document nor an empty folder.
- Existing documents, retries and other callers of `newScan()` are unchanged.
- Empty drafts already in a library are not deleted.

**Test.** `LazyScanDraftTest` covers leaving without a photo, the first capture, a failed and a successful
gallery import, and existing documents.

## 4. Backup

**Base fix.** Cherry-picked from `fix/backup-missing-files` (`2b24954` → `ecffdb3`): a missing export no
longer aborts a backup, skipped files are listed in `paperforge.json` `missing`, and restore drops their
stale export and resource records.

**Gap found in review.** A page whose own image file was missing was skipped like any side file, and
restore brought the page back pointing at nothing. Now:

- **Backup:** such pages are recorded in `missingPages` and reported separately ("N 页的原图已丢失，这些页无法从此备份恢复").
- **Restore:** the pages are left out and counted ("N 页的原图在备份时已丢失，未恢复"), through the new
  `BackupStore.restoreReport`; `restore` keeps its old signature.
- **Damaged archives are still rejected:** an image missing from the archive without being listed, or one
  with altered bytes.
- **Older archives still restore:** format-3 archives without the new lists restore as before.

**Tests.** `BackupMissingFileTest` covers:

- a vanished export;
- a missing page image;
- a damaged archive, with nothing left behind;
- a format-3 archive without the lists.

## Tests (emulator, API 35)

- **Unit tests:** 149 of 149 pass.
- **Device tests, all pass:**
  - Data and capture flow: BackupMissingFile (4), LazyScanDraft (4), CaptureTaskFlow, BackupRestoreFlow,
    HistoryBackup, DewarpResource (3).
  - Live outline and capture: CaptureCanonicalFrame (2), CameraQuality, PhoneCapture (3), ImportPath (2).
  - Detection pipeline: ScanCoreWorkflow (2), PageNetV2Engine (2), RuleFrame (5), SheetFrame (2),
    EndToEndPages, GridFidelity, Pipeline (5).
- **Fixed during the run:** the first run of the format-3 backup test failed because the emulator's library
  still listed exports from earlier test runs whose files were gone. The backup rightly listed them as
  skipped, so the stripped archive was rightly rejected as damaged. The test now clears those dangling export
  records first, and skips itself, rather than passing, if anything else is missing; it did not skip.
- **Not verified:** smoothness on the owner's phone; preview frame pacing was not measured on the device.

