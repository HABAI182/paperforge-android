#!/usr/bin/env python3
"""Small coordinate-only sanity checks; no model loading or inference."""

import json

import numpy as np

from score_crop_jvm import corner_errors, quality_metrics


def main():
    width, height = 320, 480
    scale = np.array([width - 1, height - 1])
    truth = np.array([[0.1, 0.12], [0.8, 0.14], [0.86, 0.88], [0.13, 0.83]])
    same = corner_errors(truth, truth, width, height)
    assert same["pixels"]["max"] == 0
    for reverse in (False, True):
        for offset in range(4):
            indices = (offset + (-1 if reverse else 1) * np.arange(4)) % 4
            result = corner_errors(truth[indices], truth, width, height)
            assert result["pixels"]["max"] == 0
    shift = corner_errors(truth + np.array([3, 4]) / scale, truth, width, height)
    assert np.isclose(shift["pixels"]["mean"], 5)
    assert np.isclose(shift["pixels"]["p95"], 5)
    assert np.isclose(shift["pixels"]["max"], 5)
    assert np.isclose(shift["diagonalNormalized"]["mean"], 5 / np.linalg.norm(scale))
    one = truth.copy()
    one[2] += np.array([10, 0]) / scale
    sparse = corner_errors(one, truth, width, height)
    assert np.isclose(sparse["pixels"]["mean"], 2.5)
    assert np.isclose(sparse["pixels"]["p95"], 8.5)
    assert np.isclose(sparse["pixels"]["max"], 10)
    assert not corner_errors(None, truth, width, height)["valid"]
    assert not corner_errors(truth * np.nan, truth, width, height)["valid"]
    rows = []
    for name, q in [("valid", truth), ("failed", None)]:
        rows.append(
            {
                "set": "math",
                "file": name,
                "source": "not-read",
                "sourceSize": {"width": width, "height": height},
                "scored": True,
                "truth": truth,
                "actual": {"still2000": {"quad": q, "valid": q is not None}},
            }
        )
    summary = quality_metrics({"rows": rows})
    value = summary["summary"]["all"]["still2000"]
    assert value["validPages"] == 1 and value["failedOrMissingPages"] == 1
    assert value["corners"] == 4 and value["pixels"]["max"] == 0
    assert summary["measuresOrientation"] is False
    print(
        json.dumps(
            {
                "identity": "passed",
                "cyclicAndReversedCorrespondences": 8,
                "threeFourPixelShiftEuclideanError": shift["pixels"]["mean"],
                "singleCornerP95": sparse["pixels"]["p95"],
                "failedPredictionsCountedSeparately": True,
                "measuresOrientation": False,
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
