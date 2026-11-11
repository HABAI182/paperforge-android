"""Run persisted-crop contracts against compiled product classes, without an APK.

Run :app:compileDebugKotlin first. This checks real serializers, metadata and
source-resource replay using desktop org.json. Bitmap/JPEG/camera tests still
require Android; this runner must not be presented as a device acceptance test.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path


RUNNER = r'''
package cn.paperforge.app.imaging
import cn.paperforge.app.model.*
import cn.paperforge.app.data.*
import org.json.JSONObject
import java.io.File

fun main(args:Array<String>) {
    val folder=File(args[0]).apply { mkdirs() }
    val frame=Quad(listOf(PointN(.1f,.1f),PointN(.9f,.1f),PointN(.9f,.9f),PointN(.1f,.9f)))
    val scene=listOf(.1f,.2f,.8f,.7f)
    val reference=CaptureReference(frame,1000,1100,240,320,"v",5L,.9f,scene,scene,canonicalFrame=true)
    val preset=CapturePreset("ORIGINAL",0f,1f,1f,0f,"HYBRID",true)
    val spec=CaptureSpec(preset,reference).encode()
    check(CaptureSpec.decode(spec).reference==reference)
    spec.getJSONObject("reference").remove("canonicalFrame")
    check(!CaptureSpec.decode(spec).reference!!.canonicalFrame)
    check(reference.reason(240,320,scene,scene)=="matched")
    check(reference.copy(requestedAt=1600).reason(240,320,scene,scene)=="stale")
    val locked=DetectionResult(frame,.9f,"viewfinder-locked",mapOf("canonicalFrameReused" to 1f))
    val metadata=JSONObject(CaptureMetadata.encode(reference,locked))
    check(metadata.getJSONObject("capture").getBoolean("exactFrame"))
    val raw=File(folder,"raw.bin").apply { writeText("original-source") }
    val page=PageRow("p","d",0,raw.path,corners=frame.encode(),filter="ORIGINAL",processingMetadata=metadata.toString())
    check(page.hasExactCropFrame())
    check(!page.copy(processingMetadata=CaptureMetadata.encode(reference,locked.copy(diagnostics=emptyMap()))).hasExactCropFrame())
    check(!page.copy(processingMetadata="""{"notes":{"unrelated":"exactFrame"}}""").hasExactCropFrame())
    check(page.copy(processingMetadata="""{"flatDocument":{"exactFrame":true}}""").hasExactCropFrame())
    val input=File(folder,"input.bin").apply { writeText("input") }
    val grid=File(folder,"grid.bin").apply { writeText("grid") }
    val output=File(folder,"output.bin").apply { writeText("output") }
    val prepared=JSONObject(PageResources.withDewarp(page,input,grid,output,1f,"contract-probe"))
        .put("flatDocument",JSONObject().put("exactFrame",true).put("appearanceModel",2))
    val flat=page.copy(original=output.path,corners=Quad.Full.encode(),processingMetadata=prepared.toString())
    val retained=PageResources.sourceAppearance(flat)
    check(retained.original==raw.path && Quad.decode(retained.corners)==frame)
    check(!JSONObject(retained.processingMetadata).has("flatDocument"))
    check(retained.hasExactCropFrame())
    println(JSONObject().put("passed",true).put("specRoundTrip",true).put("legacyDefault",false)
        .put("staleRejected",true).put("metadataPreservedThroughDewarp",true)
        .put("scope","compiled production serializers/helpers/resource replay; no Bitmap/device execution").toString(2))
}
'''


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
    assert (classes / "cn/paperforge/app/imaging/CaptureSpec.class").is_file()
    source = out / "CaptureContractJvm.kt"
    source.write_text(RUNNER, encoding="utf-8")
    classpath = ";".join(
        map(
            str,
            [
                classes,
                args.deps / "json-20250517.jar",
                compiler / "kotlin-stdlib-2.0.21.jar",
                compiler / "annotations-24.0.1.jar",
            ],
        )
    )
    jar = out / "capture-contract.jar"
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
            str(source),
        ],
        check=True,
    )
    result = subprocess.run(
        [
            "java",
            "-cp",
            str(jar) + ";" + classpath,
            "cn.paperforge.app.imaging.CaptureContractJvmKt",
            str(out / "fixtures"),
        ],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    report = json.loads(result.stdout)
    report["classSha256"] = {
        str(p.relative_to(classes)): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in classes.rglob("*.class")
        if p.name.startswith(
            (
                "CaptureSpec",
                "CapturePreset",
                "CaptureReference",
                "CaptureMetadata",
                "CropFrame",
                "PageResources",
                "PageRow",
            )
        )
    }
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
