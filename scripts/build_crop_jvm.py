"""Compile production Mat crop stages into an isolated desktop JVM probe.

Copies production algorithms unchanged, with separate Android asset/clock/log
shims. The configured PageNet branch of LearnedDocumentDetector is transcribed
as a selection shim; unavailable legacy/DocQuad fallback branches are excluded.
No APK, asset replacement or Gradle build is performed. OpenCV 4.9 on Windows
differs from Android's 4.12 and timings here are desktop diagnostics only.
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path


RUNNER = r"""
package cn.paperforge.app.imaging
import android.content.Context
import cn.paperforge.app.model.*
import org.json.JSONObject
import org.json.JSONArray
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.*

object CropJvm {
    private fun quad(q: Quad?) = q?.let { JSONArray(it.points.map { p -> listOf(p.x,p.y) }) }
    fun crop(frame: Mat): Pair<DetectionResult,JSONObject> {
        val stages=JSONObject(); var mark=System.nanoTime()
        fun lap(name: String) { val now=System.nanoTime();stages.put(name,(now-mark)/1e6);mark=now }
        var result=LearnedDocumentDetector.detect(frame,false);lap("pagenetAndSourceRefineMs")
        stages.put("topSheetSubstages",JSONObject(TopSheetRules.times.get()))
        stages.put("coverNotes",CoverOutline.notes.get())
        val first=result.quad
        if(first!=null) EdgeQuad.check(frame,first)?.let { result=result.copy(quad=it,method=result.method+"+"+EdgeQuad.VERSION) }
        lap("outlineMs")
        val q=result.quad
        if(q!=null) {
            val outlined=result.method.contains(EdgeQuad.VERSION)
            val ruled=RuleFrame.refine(frame,q);lap("ruleFrameMs")
            if(ruled!=null) result=result.copy(quad=ruled.quad,method=result.method+"+"+RuleFrame.VERSION,
                notes=result.notes+mapOf("ruleFrameLeft" to ruled.left,"ruleFrameRight" to ruled.right))
            else {
                val sheet=if(outlined) null else SheetEdge.refine(frame,q);lap("sheetEdgeMs")
                if(sheet!=null) result=result.copy(quad=sheet.quad,method=result.method+"+"+SheetEdge.VERSION,
                    notes=result.notes+mapOf("sheetEdgeSides" to sheet.sides))
            }
        }
        stages.put("quad",quad(result.quad)).put("method",result.method).put("confidence",result.confidence)
            .put("notes",JSONObject(result.notes)).put("diagnostics",JSONObject(result.diagnostics))
        return result to stages
    }
}

fun main(args: Array<String>) {
    System.load(File(args[0]).absolutePath)
    Core.setNumThreads(1)
    ScanConfig.values=JSONObject(File(args[1]).readText())
    PageNetDetector.initialize(Context(File(args[2])))
    val manifest=JSONArray(File(args[3]).readText());val results=JSONArray()
    val started=System.nanoTime()
    repeat(2) {
        val warm=Mat(720,540,CvType.CV_8UC4,Scalar(230.0,230.0,230.0,255.0))
        try { CropJvm.crop(warm) } finally {warm.release()}
    }
    val warmMs=(System.nanoTime()-started)/1e6
    if(args.getOrNull(5)=="live-contract") {
        val checks=JSONArray()
        for(i in 0 until min(3,manifest.length())) {
            val row=manifest.getJSONObject(i);val bgr=Imgcodecs.imread(row.getString("source"))
            val small=Mat();val rgba=Mat()
            try {
                val scale=min(1.0,720.0/max(bgr.cols(),bgr.rows()))
                Imgproc.resize(bgr,small,Size((bgr.cols()*scale).roundToInt().toDouble(),(bgr.rows()*scale).roundToInt().toDouble()),0.0,0.0,Imgproc.INTER_AREA)
                Imgproc.cvtColor(small,rgba,Imgproc.COLOR_BGR2RGBA)
                repeat(2) {
                    LearnedDocumentDetector.calls=0
                    val live=EdgeQuad.liveSteady(rgba)
                    val calls=LearnedDocumentDetector.calls
                    check(calls==1) {"Live detector made $calls calls"}
                    val expected=CropJvm.crop(rgba).first
                    check(live.quad==expected.quad) {"Live frame differs from canonical Mat path"}
                    checks.put(JSONObject().put("file",row.getString("file")).put("repetition",it)
                        .put("learnedDetectorCalls",calls).put("exactSameCanonicalQuad",true))
                }
            } finally {bgr.release();small.release();rgba.release()}
        }
        File(args[4]).writeText(JSONObject().put("passed",true).put("checks",checks)
            .put("scope","Actual EdgeQuad.liveSteady + canonical Kotlin Mat path, configured PageNet shim; no Android camera/Bitmap")
            .put("opencv",Core.VERSION).toString(2))
        println("Live crop contract passed: "+checks.length()+" checks")
        return
    }
    for(i in 0 until manifest.length()) {
        val row=manifest.getJSONObject(i); val begin=System.nanoTime()
        val image=Imgcodecs.imread(row.getString("source"));require(!image.empty())
        val decoded=(System.nanoTime()-begin)/1e6
        val result=JSONObject().put("set",row.getString("set")).put("file",row.getString("file"))
            .put("source",row.getString("source")).put("decodeMs",decoded)
        for((mode,edge) in listOf("preview720" to 720,"still2000" to 2000)) {
            val frame=Mat();val rgba=Mat()
            try {
                val scale=min(1.0,edge.toDouble()/max(image.cols(),image.rows()))
                Imgproc.resize(image,frame,Size((image.cols()*scale).roundToInt().toDouble(),(image.rows()*scale).roundToInt().toDouble()),0.0,0.0,Imgproc.INTER_AREA)
                Imgproc.cvtColor(frame,rgba,Imgproc.COLOR_BGR2RGBA)
                val t=System.nanoTime(); val (detection,stages)=CropJvm.crop(rgba)
                stages.put("elapsedMs",(System.nanoTime()-t)/1e6)
                if(mode=="preview720") {
                    val quick=LearnedDocumentDetector.detect(rgba,true)
                    stages.put("quickQuad",quick.quad?.let { JSONArray(it.points.map { p -> listOf(p.x,p.y) }) })
                }
                result.put(mode,stages)
            } finally { frame.release();rgba.release() }
        }
        result.put("opencv",Core.VERSION).put("warmupMs",warmMs)
        results.put(result);File(args[4]).writeText(results.toString(2))
        println(JSONObject().put("completed",i+1).put("total",manifest.length()).put("file",row.getString("file"))
            .put("stillMs",result.getJSONObject("still2000").getDouble("elapsedMs")).toString());System.out.flush()
        image.release();System.gc()
    }
}
"""

SHIMS = r"""
package cn.paperforge.app.imaging
import org.json.JSONObject
import org.opencv.core.Mat
import cn.paperforge.app.model.*
import kotlin.math.min
object ScanConfig {
    var values=JSONObject()
    fun int(name:String,fallback:Int)=values.optInt(name,fallback)
    fun float(name:String,fallback:Float)=values.optDouble(name,fallback.toDouble()).toFloat()
}
// Only the production configured PageNet branch. No fake legacy model fallback.
object LearnedDocumentDetector {
    var calls=0
    fun detect(source:Mat,preview:Boolean=false):DetectionResult {
        calls++
        val found=PageNetDetector.detect(source,preview) ?: return DetectionResult(null,0f,"pagenet-unavailable")
        val quad=found.quad ?: return found
        val result=if(preview || ScanConfig.int("pageNetRefine",1)==0) found
            else found.copy(quad=DocumentDetector.refine(source,quad),method=found.method+"+refined")
        if(result.quad?.hugsFrame()!=true) return result
        return DetectionResult(null,min(result.confidence,.2f),result.method+"-frame-only",result.diagnostics+mapOf("frameOnly" to 1f),result.elapsedMs)
    }
}
object ScanProcessor {
    fun detectFrame(frame:Mat)=CropJvm.crop(frame).first
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--deps", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--build-only", action="store_true")
    parser.add_argument("--live-contract", action="store_true")
    parser.add_argument(
        "--recovery-size",
        type=int,
        choices=(224, 320),
        help="Private copy-only recovery preset ablation; all other constants stay unchanged",
    )
    parser.add_argument(
        "--recovery-iterations",
        type=int,
        choices=(1, 2, 3),
        help="Private copy-only GrabCut iteration ablation; source dimensions/kernels stay unchanged",
    )
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    work = repo.parents[1] / "work"
    out, deps = args.out.resolve(), args.deps.resolve()
    assert out != repo and repo not in out.parents
    src = out / "src"
    src.mkdir(parents=True, exist_ok=True)
    production = repo / "app/src/main/java/cn/paperforge/app"
    provenance = {}
    iteration_config_override = None
    paths = [
        production / "imaging" / (name + ".kt")
        for name in (
            "PageNetDetector",
            "CoverOutline",
            "TopSheetRules",
            "EdgeQuad",
            "RuleFrame",
            "RuleRectify",
            "SheetEdge",
            "DocumentDetector",
        )
    ]
    paths.extend(
        production / "model" / (name + ".kt")
        for name in ("Geometry", "ImageGeometry", "CaptureReference")
    )
    for source in paths:
        shutil.copyfile(source, src / source.name)
        provenance[str(source)] = hashlib.sha256(source.read_bytes()).hexdigest()
    if args.recovery_size is not None or args.recovery_iterations is not None:
        copied = src / "PageNetDetector.kt"
        original_bytes = copied.read_bytes()
        replaced_bytes = original_bytes
        if args.recovery_size is not None:
            anchor = b"private const val RECOVER = 320"
            assert replaced_bytes.count(anchor) == 1
            replaced_bytes = replaced_bytes.replace(
                anchor,
                f"private const val RECOVER = {args.recovery_size}".encode("ascii"),
            )
        if args.recovery_iterations is not None:
            anchor = b"mat(), mat(), 3, Imgproc.GC_INIT_WITH_MASK"
            if replaced_bytes.count(anchor) == 1:
                replaced_bytes = replaced_bytes.replace(
                    anchor,
                    f"mat(), mat(), {args.recovery_iterations}, Imgproc.GC_INIT_WITH_MASK".encode(
                        "ascii"
                    ),
                )
            else:
                assert (
                    replaced_bytes.count(
                        b'ScanConfig.int("pageNetRecoveryIterations", 2).coerceIn(1, 5)'
                    )
                    == 1
                ), "Unknown recovery implementation"
                iteration_config_override = args.recovery_iterations
        copied.write_bytes(replaced_bytes)
        before, after = original_bytes.decode("utf-8"), replaced_bytes.decode("utf-8")
        patch = "".join(
            difflib.unified_diff(
                before.splitlines(keepends=True),
                after.splitlines(keepends=True),
                fromfile="production/PageNetDetector.kt",
                tofile="private-probe/PageNetDetector.kt",
            )
        )
        (out / "private-source-override.patch").write_bytes(patch.encode("utf-8"))
        provenance["privateCopyOverride"] = {
            "recoverySize": args.recovery_size or 320,
            "recoveryIterations": args.recovery_iterations or 3,
            "otherConstantsChanged": False,
            "kernelSizeUnchanged": 9,
            "copySha256": hashlib.sha256(copied.read_bytes()).hexdigest(),
            "scope": "Overall recovery preset ablation, not scale-equivalent processing",
        }
    shims = {
        "CropJvm.kt": RUNNER,
        "Shims.kt": SHIMS,
        "Context.kt": 'package android.content\nimport java.io.File\nclass AssetManager(val model:File) { fun open(name:String):java.io.InputStream { require(name=="pagenet/pagenet.onnx");return model.inputStream() } }\nclass Context(model:File) { val assets=AssetManager(model);val applicationContext:Context get()=this }\n',
        "Clock.kt": "package android.os\nobject SystemClock { fun elapsedRealtime()=System.nanoTime()/1000000 }\n",
        "Log.kt": 'package android.util\nobject Log { fun w(tag:String,value:String,error:Throwable?=null):Int {System.err.println("$tag: $value");return 0};fun d(tag:String,value:String):Int=0 }\n',
        "Bitmap.kt": "package android.graphics\nclass Bitmap(val width:Int=0,val height:Int=0)\n",
        "Utils.kt": 'package org.opencv.android\nimport android.graphics.Bitmap\nimport org.opencv.core.Mat\nobject Utils { fun bitmapToMat(bitmap:Bitmap,mat:Mat):Unit=error("Bitmap path is outside Mat-only probe") }\n',
    }
    for name, text in shims.items():
        (src / name).write_text(text, encoding="utf-8")
    params = repo / "app/src/main/assets/scan_params.json"
    shutil.copyfile(params, out / "scan_params.json")
    if iteration_config_override is not None:
        before = (out / "scan_params.json").read_bytes().decode("utf-8")
        after, count = re.subn(
            r'("pageNetRecoveryIterations"\s*:\s*)\d+',
            lambda match: match[1] + str(iteration_config_override),
            before,
            count=1,
        )
        assert count == 1
        (out / "scan_params.json").write_bytes(after.encode("utf-8"))
        patch = "".join(
            difflib.unified_diff(
                before.splitlines(keepends=True),
                after.splitlines(keepends=True),
                fromfile="production/scan_params.json",
                tofile="private-probe/scan_params.json",
            )
        )
        (out / "private-config-override.patch").write_bytes(patch.encode("utf-8"))
        provenance["privateConfigOverride"] = {
            "pageNetRecoveryIterations": iteration_config_override,
            "copySha256": hashlib.sha256(after.encode("utf-8")).hexdigest(),
        }
    compiler = work / "toolchain/gradle-8.13/lib"
    jars = [
        deps / "opencv-4.9.0-0.jar",
        deps / "json-20250517.jar",
        deps / "onnxruntime-1.22.0.jar",
        compiler / "kotlin-stdlib-2.0.21.jar",
        compiler / "annotations-24.0.1.jar",
    ]
    for path in jars:
        assert path.is_file(), path
        provenance[str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
    native = next(deps.rglob("opencv_java490.dll"))
    provenance[str(native)] = hashlib.sha256(native.read_bytes()).hexdigest()
    provenance[str(params)] = hashlib.sha256(params.read_bytes()).hexdigest()
    provenance[str(args.model.resolve())] = hashlib.sha256(
        args.model.read_bytes()
    ).hexdigest()
    (out / "source-provenance.json").write_text(
        json.dumps(provenance, indent=2), encoding="utf-8"
    )
    classpath = ";".join(map(str, jars))
    jar = out / "crop-probe.jar"
    command = [
        "java",
        "-cp",
        str(compiler / "*"),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib",
        "-no-reflect",
        "-jvm-target",
        "17",
        "-classpath",
        classpath,
        "-d",
        str(jar),
        *map(str, src.glob("*.kt")),
    ]
    subprocess.run(command, check=True)
    if args.build_only:
        return
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    manifest = [
        {k: row[k] for k in ("set", "file", "source")} for row in baseline["rows"]
    ]
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    command = [
        "java",
        "-Xmx4g",
        "-cp",
        str(jar) + ";" + classpath,
        "cn.paperforge.app.imaging.CropJvmKt",
        str(native),
        str(out / "scan_params.json"),
        str(args.model.resolve()),
        str(out / "manifest.json"),
        str(out / "results.json"),
    ]
    if args.live_contract:
        command.append("live-contract")
    subprocess.run(command, check=True)


if __name__ == "__main__":
    main()
