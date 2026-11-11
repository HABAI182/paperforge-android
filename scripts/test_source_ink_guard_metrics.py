#!/usr/bin/env python3
"""Check metric semantics without importing Torch, training, or exporting a model.

Pass --report PATH --migrate to reorganize a legacy report in place. The original
JSON is retained as report.metrics-v1.json; adjacent ONNX/PT files stay unchanged.
"""

import argparse
import ast
import hashlib
import json
from pathlib import Path

import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--migrate", action="store_true")
    args = parser.parse_args()
    source = Path(__file__).with_name("train_source_ink_guard.py")
    tree = ast.parse(source.read_text(encoding="utf-8"), filename=str(source))
    names = {"limit_gain", "summarize_metrics", "migrate_report_metrics"}
    functions = ast.Module(
        body=[n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names],
        type_ignores=[],
    )
    namespace = {"np": np, "json": json}
    exec(compile(functions, str(source), "exec"), namespace)
    bound = namespace["limit_gain"]
    migrate = namespace["migrate_report_metrics"]

    source_ink = np.array([0.2, 0.02])
    existing_tone = np.array([0.1, 0.1])
    score = np.array([0.0, 0.84])
    bounded = bound(source_ink, existing_tone, score)
    invariant = bool((bounded >= np.minimum(source_ink, existing_tone) - 1e-6).all())
    source_preserved = bool((bounded >= source_ink - 1e-6).all())
    assert invariant and not source_preserved
    assert score[1] < 0.85 and bounded[1] - source_ink[1] > 0.079

    legacy = {"sha256": "unchanged-model-sha", "thresholds": [], "rows": []}
    for threshold, front, reverse in [(0.35, 80, 40), (0.85, 60, 80)]:
        legacy["thresholds"].append(
            {"threshold": threshold, "frontRecall": front / 100,
             "reverseNotEnhanced": reverse / 100, "gainBoundPreservedSource": True,
             "reverseDarkeningBefore": 0.08, "reverseDarkeningAfter": 0.02}
        )
        legacy["rows"].append(
            {"threshold": threshold, "faint": True, "positivePixels": 100,
             "negativePixels": 100, "frontRetained": front,
             "reverseNotEnhanced": reverse, "sourceRetentionByGainBound": True,
             "reverseDarkeningBefore": 0.08, "reverseDarkeningAfter": 0.02}
        )
    migrated = migrate(legacy)
    assert migrated["sha256"] == legacy["sha256"]
    assert "metricsSchemaVersion" not in legacy  # Migration never mutates its input.
    assert migrate(migrated) == migrated
    assert len(migrated["gainBound"]["rows"]) == 1
    assert migrated["gainBound"]["parameters"] == {"frontCut": 0.35, "fullGain": 0.85}
    assert migrated["gainBound"]["constructionInvariant"]
    assert migrated["gainBound"]["notSemanticRetention"]
    assert migrated["gainBound"]["pixelGainCounts"]["status"] == "unavailable"
    assert all("reverseDarkeningAfter" not in r for r in migrated["thresholds"])
    assert [r["reverseBelowFrontThreshold"] for r in migrated["thresholds"]] == [0.4, 0.8]
    assert "gainBoundPreservedSource" not in json.dumps(migrated)
    fresh_gain = dict(migrated["gainBound"]["rows"][0])
    fresh_gain.update(reversePixelsWithPositiveGainBefore=100, reversePixelsWithPositiveGainAfter=25)
    fresh = namespace["summarize_metrics"](migrated["rows"], [fresh_gain])
    assert fresh["gainBound"]["pixelGainCounts"] == {
        "status": "available", "epsilon": 1e-6, "before": 100, "after": 25,
    }
    result = {
        "syntax": "passed",
        "mathematicalCounterexamples": {
            "lowerBoundCanHoldWhileSourceContrastFalls": invariant and not source_preserved,
            "scoreBelow0_85CanStillDarkenReversePixel": float(bounded[1] - source_ink[1]),
        },
        "migrationConservesValuesAndIsIdempotent": True,
    }
    if args.report:
        report_path = args.report.resolve()
        before = report_path.read_bytes()
        report = json.loads(before.decode("utf-8-sig"))
        model_hashes = {
            str(p): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in report_path.parent.iterdir() if p.suffix in (".onnx", ".pt")
        }
        upgraded = migrate(report)
        assert upgraded["sha256"] == report["sha256"]
        if args.migrate and report.get("metricsSchemaVersion") != 2:
            backup = report_path.with_name("report.metrics-v1.json")
            assert not backup.exists() or backup.read_bytes() == before
            if not backup.exists():
                backup.write_bytes(before)
            upgraded["metricsMigration"]["originalReportSha256"] = hashlib.sha256(before).hexdigest()
            report_path.write_text(json.dumps(upgraded, indent=2), encoding="utf-8")
        assert all(hashlib.sha256(Path(p).read_bytes()).hexdigest() == h for p, h in model_hashes.items())
        result["report"] = {
            "path": str(report_path), "written": args.migrate,
            "thresholdRows": len(upgraded["rows"]),
            "fixedGainRows": len(upgraded["gainBound"]["rows"]),
            "pixelGainCounts": upgraded["gainBound"]["pixelGainCounts"]["status"],
            "modelSha256Unchanged": model_hashes,
        }
    elif args.migrate:
        parser.error("--migrate requires --report")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
