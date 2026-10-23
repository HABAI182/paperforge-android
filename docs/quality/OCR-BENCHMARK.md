# OCR pipeline comparison

Fixed synthetic-v1, 9 labeled mixed Chinese/English pages. API35 AOSP emulator, bundled offline models. Exact original input hashes/ground truth in fixture-manifest.json. No physical-phone or private-photo claim.

| Metric | Frozen v0.2 | v0.3 |
|---|---:|---:|
| Mean per-page CER | 14.2841% | 3.9216% |
| Chinese-only mean CER | 9.4724% | 5.8677% |
| Latin/digit mean CER | 20.2614% | 0.0000% |
| Regression repeated exact prefixes | 11/13 | 13/13 |
| OCR p50 / p95 | 764 / 1185 ms | 3026 / 4200 ms |

CER normalizes NFKC/whitespace and includes ordering errors. Language rows filter Chinese vs Latin/digits, including the numbered lines. PSS snapshots are lower bounds; different stage subsets make them unsuitable for claiming a memory reduction. Results contain confidence, angle, elements, engine/version/revision, orientation, deskew and per-line variant hypotheses.

Accepted pipeline: shared serialized recognizers; original input first; dominant text angle determines 90/180/270 correction (rotated fixtures now 270/180/90); alternate clean and scale passes for uncertain/small text; bounded 24-line batch at readable pixel height; coordinate-overlap matching and character voting; paragraph/column grouping. A conservative repeated-prefix check may select a same-line hypothesis when >=5 rows have >=80% raw prefix agreement and >=90% alternative support. This is generic page-context postprocessing, not a model-only accuracy score. It changes two regression lines to observed alternatives, recorded as page-agreement, and retains all hypotheses. It contains no benchmark phrase or character substitution dictionary. Low scores remain editable hints, not calibrated error probabilities.

The first confidence-only orientation prototype regressed to 15.50% CER and was rejected. The first accepted angle/scale prototype measured 5.29%, line batches 4.80%, then voting/context 3.92%. Repeatedly observed synthetic fixtures are development evidence, not an unseen evaluation. Similar glyphs remain wrong on other pages; 13/13 applies only to the named regression fixture.

Small deskew applies only with consistent dominant angles, >=3 lines, and preserved text/confidence. Pixel and word coordinates use one expanded-canvas transform. Corrections are derived edits with history; originals remain intact. Existing JSON decodes with defaults; Room migration 3→4 adds deskew state. Cache revision checks prevent writing stale OCR; corrected text clears obsolete word boxes. Searchable PDF places elements where available and keeps existing CJK font/ToUnicode.

PaddleOCR/Tesseract sources and artifact APIs were inspected (UPSTREAM_REFERENCE_MATRIX.md). No second-engine/model shipped: no physical ARM64 device/model-size/CER/latency comparison was available. A toggle without a validated offline engine would misrepresent capability. Current ML Kit remains first-run offline; optional engine trial is a documented follow-on experiment.
