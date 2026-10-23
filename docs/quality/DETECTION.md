# Detection experiment v0.3

Baseline: 4d56d55/v0.2.0. Fixed synthetic-v1: 65 positive pages, 8 no-document negatives; OCR orientation and resolution probes excluded from detection aggregate. Variants 0–3 were used for development; variants 4–7 were observed during tuning, so this is **not an unseen test set**. No real-photo or physical-camera accuracy claim.

| Metric | Frozen v0.2 | v0.3 Android API35 AOSP emulator |
|---|---:|---:|
| Mean IoU | 0.6365 desktop | 0.9971 |
| Mean corner error / long edge | 29.657% desktop | 0.079% |
| IoU < .90 / misses | 31/65 desktop | 0/65 |
| Negative false positives | 0/8 desktop | 0/8 |
| Detection p50 / p95 | 6.07 / 6.90 ms desktop | 136.5 / 182.3 ms emulator |

Desktop v0.3: see desktop-summary.json; compare desktop timing with desktop baseline, not emulator timing. New multi-channel processing is slower. Preview limits to 720px, at most three channels, no Hough/refinement, and CameraX keeps only latest frame. Preview throughput needs actual-phone measurement.

Method: Lab/saturation/weak-luma and border-distance candidates, convex quad or rectangular hull, edge/inside-outside band/shape/paper/area scoring, bounded line fallback. Still refinement fits four narrow edge bands at decoded resolution. Original capture is retained, and uncertain/absent detection leaves manual crop available. Preview coordinates are never substituted for still-image detection. Confidence is a heuristic evidence score, **not a calibrated probability**. Bins in summary.json show observed corpus counts, not population reliability.

Parameters: app/src/main/assets/scan_params.json. Python mirrors candidate/scoring logic; Kotlin uses histogram quantiles, a coarser bounded interior grid, and adds high-resolution refinement. Their predictions are measured independently, rather than assumed byte-identical. Reproduce with scripts/bench/benchmark.ps1, benchMode=current and benchStage=detect. results.json contains per-case confidence, method and coordinates. Contact sheets show Python prediction (red), truth (green).

References inspected before implementation: docs/research/UPSTREAM_REFERENCE_MATRIX.md. Independent implementation; no external runtime/model added. Low-contrast near-frame paper, curved pages, multiple sheets, real glare and texture negatives remain physical/private-photo acceptance work.
