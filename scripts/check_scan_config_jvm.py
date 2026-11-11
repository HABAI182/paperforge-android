"""Compile actual ScanParams.kt and verify crop/appearance fingerprint isolation.

Uses existing app classes for production dependencies and desktop org.json;
the ScanConfig implementation under test is copied unchanged, never stubbed.
No APK, neural inference or Android device is needed for these pure contracts.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path


RUNNER = r"""
package cn.paperforge.app.imaging
import org.json.JSONObject
import java.io.File
fun main(args:Array<String>) {
    val base=JSONObject(File(args[0]).readText()).apply { remove("pageNetRecoveryIterations") }
    ScanConfig.restoreAfterBenchmark(base)
    val without=ScanConfig.fingerprint()
    if(args.getOrNull(1)=="legacy") { println(JSONObject().put("fingerprint",without));return }
    check(ScanConfig.int("pageNetRecoveryIterations",2)==2)
    ScanConfig.replaceForBenchmark(JSONObject().put("pageNetRecoveryIterations",2))
    val two=ScanConfig.fingerprint()
    check(ScanConfig.int("pageNetRecoveryIterations",2)==2)
    ScanConfig.replaceForBenchmark(JSONObject().put("pageNetRecoveryIterations",3))
    val three=ScanConfig.fingerprint()
    check(ScanConfig.int("pageNetRecoveryIterations",2)==3)
    ScanConfig.replaceForBenchmark(JSONObject().put("toneZonesRidgeDepth",base.getDouble("toneZonesRidgeDepth")+.1))
    val appearance=ScanConfig.fingerprint()
    check(without==two && two==three) { "Crop speed setting invalidated appearance" }
    check(appearance!=without) { "Appearance setting failed to invalidate appearance" }
    println(JSONObject().put("passed",true).put("withoutNewKey",without).put("iterations2",two)
        .put("iterations3",three).put("changedAppearance",appearance).toString(2))
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--deps", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    out = args.out.resolve()
    assert out != repo and repo not in out.parents
    out.mkdir(parents=True, exist_ok=True)
    work = repo.parents[1] / "work"
    compiler = work / "toolchain/gradle-8.13/lib"
    classes = repo / "app/build/tmp/kotlin-classes/debug"
    production = repo / "app/src/main/java/cn/paperforge/app/imaging/ScanParams.kt"
    copied = out / "ScanParams.kt"
    shutil.copyfile(production, copied)
    runner = out / "ScanConfigFingerprintJvm.kt"
    runner.write_text(RUNNER, encoding="utf-8")
    params = repo / "app/src/main/assets/scan_params.json"
    config = json.loads(params.read_text(encoding="utf-8"))
    assert (
        type(config["pageNetRecoveryIterations"]) is int
        and config["pageNetRecoveryIterations"] == 2
    )
    android = work / "toolchain/android-sdk/platforms/android-36/android.jar"
    classpath = ";".join(
        map(
            str,
            [
                classes,
                args.deps / "json-20250517.jar",
                compiler / "kotlin-stdlib-2.0.21.jar",
                compiler / "annotations-24.0.1.jar",
                android,
            ],
        )
    )
    jar = out / "scan-config-contract.jar"
    subprocess.run(
        [
            "java",
            "-cp",
            str(compiler / "*"),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib",
            "-no-reflect",
            "-jvm-target",
            "17",
            "-Xfriend-paths=" + str(classes),
            "-classpath",
            classpath,
            "-d",
            str(jar),
            str(copied),
            str(runner),
        ],
        check=True,
    )

    def run(selected, *extra):
        value = subprocess.run(
            [
                "java",
                "-cp",
                str(selected) + ";" + classpath,
                "cn.paperforge.app.imaging.ScanConfigFingerprintJvmKt",
                str(params),
                *extra,
            ],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        return json.loads(value.stdout)

    report = run(jar)
    # Same runner, but omit the freshly compiled ScanConfig so the last real app
    # build's implementation executes. This proves old baked hashes still match.
    legacy = out / "compiled-app-config-contract.jar"
    with zipfile.ZipFile(jar) as source, zipfile.ZipFile(legacy, "w") as dest:
        for item in source.infolist():
            if not item.filename.endswith("/ScanConfig.class"):
                dest.writestr(item, source.read(item.filename))
    report["compiledAppFingerprint"] = run(legacy, "legacy")["fingerprint"]
    assert report["compiledAppFingerprint"] == report["withoutNewKey"]
    report["sourceSha256"] = hashlib.sha256(production.read_bytes()).hexdigest()
    report["assetSha256"] = hashlib.sha256(params.read_bytes()).hexdigest()
    report["compiledAppScanConfigSha256"] = hashlib.sha256(
        (classes / "cn/paperforge/app/imaging/ScanConfig.class").read_bytes()
    ).hexdigest()
    report["scope"] = __doc__
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
