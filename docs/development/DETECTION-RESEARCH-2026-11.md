# Document detection research, November 2026

This file records what the detection research found, and how to reproduce it. The production PageNet
(`app/src/main/assets/pagenet/pagenet.onnx`, SHA-256 `2b2a4b63…57bba`) is unchanged.

Labels used throughout:
- **Measured:** run through the desktop JVM build of the real Kotlin crop pipeline.
- **Assumption:** not measured.

## Evaluation data

| Set | Size | Source and licence | Role |
|---|---:|---|---|
| public167 | 167 | Held-out groups of Mendeley x3nm4cxr83 (CC BY 4.0) | Same collection as the training photos, so in-distribution |
| owner | 54 | The owner's photos | Regression set only. Two failure types first seen here shaped the training scenes, so it is not blind. |
| receipts | 198 | Zenodo 10.5281/zenodo.13688441 (CC BY 4.0), hand-held receipt photos | Independent |
| SmartDoc 2015 Challenge 1 | 900 | Zenodo 10.5281/zenodo.1230218 (CC BY 4.0): 150 videos × 6 frames, 5 backgrounds | Independent. Background 5 is a cluttered desk with cables and pens across the page and sheets underneath. |

**Splits.** The independent sets are split once:
- **Selection half:** SmartDoc documents at even positions of the sorted document list, and receipts with odd numbers (`scripts/build_real_val.py`). This half is used for checkpoint selection and for fitting selectors.
- **Test half:** the other SmartDoc documents and the even-numbered receipts. This half, public167 and the owner set are never used to choose anything.

**Scoring.** Scores use the app's behaviour: when no quad is returned, the app keeps the full frame (`LearnedDocumentDetector.frameOnly`).

## Main findings

1. **Production fails on cluttered desks.**
   - On SmartDoc background 5 (white page on white desk, cables and pens across it), production fails 158 of 180 frames (IoU < 0.5).
   - The cause is the network's own mask, not the pipeline. The raw-mask IoU there is 0.35: it spills onto the desk and breaks where objects cross the page.
2. **Architecture matters.**
   - Trained on the same data, every MobileNetV3-Small U-Net cut SmartDoc severe failures from 178 to 38–48.
   - PageNet and PP-LiteSeg-style variants trained on that data reached only 115–135.
   - The 1/32-resolution encoder's wide context is the most likely reason (assumption).
3. **Checkpoint selection on Mendeley photos misleads.**
   - Gains on public167 did not transfer to the receipts or the owner set.
   - Selecting on the real-photo selection half (`train_page_models.py --real-val`), together with cluttered-desk and crossing-object scenes (`realistic_scenes.py --cases cluttered-desk --crossers`), is what made round 4 work.
4. **Round 4, MobileNetV3 ×1.5 (`models-r4/mbv3w15`, 1.95 M parameters)** compared with production (mean IoU / severe / bad):

   | Set | Production | ×1.5 |
   |---|---|---|
   | SmartDoc test half | 0.840 / 89 / 101 | **0.953 / 2 / 19** |
   | Public167 | 0.836 / 15 / 45 | **0.937 / 4 / 11** |
   | Owner | 0.915 / 0 / 5 | **0.921 / 0 / 4** |
   | Receipts test half | **0.891 / 2 / 15** | 0.882 / 3 / 16 |

   Remaining owner gaps are book covers: `blue-book` 0.97 → 0.92, `calc-cover` 0.87 → 0.82, `cover-on-book` 0.99 → 0.96.
5. **The rule-ends step (`TopSheetRules`) misfires on receipts.**
   - It reads printed text rows as notebook ruling.
   - Turning it off (`ruleEnds=0`) raises receipts from 0.856 to 0.878 (bad crops 40 → 26) with production's network, and from 0.867 to 0.882 with ×1.5.
   - It does help on notebooks and some public photos (×1.5 public 0.937 → 0.929 without it).
   - **Next step:** sharpen its trigger (real ruling spans the page at regular spacing), tuned on development data, not on the receipts.
6. **A run-time selector shows the models are complementary.**
   - The selector is a logistic model on signals both pipelines produce: confidence, method path, quad area, the two models' agreement. It chooses per photo between production and a MobileNetV3 model.
   - With r3 MobileNetV3, fitted on the selection half, on held-out data:
     - SmartDoc severe 89 → 23–28;
     - public severe 15 → 3;
     - owner unchanged;
     - receipts level.
   - These results are stable across swapped splits and thresholds (`work/topsheet-decoder/select_experiment.py`).
   - Superseded by the stronger single model in round 4, but still an option if a candidate regresses somewhere specific.

## On-device cost

Measured with `ModelBenchmarkTest` on the API 35 x86_64 emulator, using ORT with 2 threads and ALL_OPT. These are two runs while a training job used the GPU. It is not a phone's ARM CPU; time on a real phone before any promotion.

| Model | File | Median | p90 | Native heap Δ |
|---|---:|---:|---:|---:|
| Production PageNet | 1.1 MB | 10.7 ms | 12.1–12.8 ms | 70 MB |
| MobileNetV3 (r4 mbv3-ft4) | 3.6 MB | 6.9–7.4 ms | 8.2–8.6 ms | 71 MB |
| MobileNetV3 ×1.5 (r4 mbv3w15) | 7.8 MB | 10.3–11.7 ms | 12.3–15.4 ms | 76 MB |

## Training performance (RTX 5060 Laptop, measured with `scripts/profile_page_training.py`)

- **GPU-bound.** Training is limited by GPU compute. The thread loader supplies 112 batches/s, 3–11× what the GPU uses. An evaluation costs about 9 s per 2000 steps.
- **Faster precision setting.** bf16 autocast with channels-last is 1.16× (PageNet) to 1.38× (MobileNetV3) faster than fp16, and reached the same accuracy in a 6000-step A/B run. It is the `--precision bf16 --channels-last` option.
- **A setting to avoid.** fp16 with channels-last is 4–5× slower.
- **Not available.** `torch.compile` needs Triton, which this Windows environment lacks.
- **Scheduling rule.** CPU-heavy pipeline evaluations starve the training loader: the GPU dropped to 35% with three JVM evaluations running. Run them apart from training.

## Reproducing

| Step | Where |
|---|---|
| Scenes | `scripts/realistic_scenes.py`. Every chunk is a pure function of (seed, chunk start). Sets made before 2026-11-12 rebuild exactly with `--regen-table` (`scripts/scene_regen_table.py`). |
| Real validation set | `scripts/build_real_val.py` |
| SmartDoc frames | `scripts/prepare_smartdoc_eval.py` |
| Training | `scripts/train_page_models.py`; round configurations in `work/models-r*/run_all.sh` |
| Pipeline evaluation | `work/topsheet-decoder/run_jvm.py` (real Kotlin sources), `work/independent-eval/eval_set.sh`; comparisons in `work/topsheet-decoder/compare_real.py`, `compare_all.py` and `select_experiment.py` |
| On-device timing | `ModelBenchmarkTest` (androidTest) |
