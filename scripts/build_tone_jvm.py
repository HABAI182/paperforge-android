"""Compile exact production tone sources for an offline desktop-only JVM probe.

Android loading/diagnostic shims are deliberately separate from source copies.
No APK/Gradle task is invoked. The native OpenCV version differs from Android.
"""

from pathlib import Path
import argparse
import hashlib
import json
import difflib
import shutil
import subprocess


RUNNER = r"""
package cn.paperforge.app.imaging
import org.json.JSONObject
import org.json.JSONArray
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
fun main(args: Array<String>) {
    System.load(File(args[0]).absolutePath)
    ScanConfig.values = JSONObject(File(args[1]).readText())
    val manifest = JSONArray(File(args[2]).readText())
    val results = JSONArray()
    for (i in 0 until manifest.length()) {
        val row = manifest.getJSONObject(i)
        ProbeDump.prefix = row.getString("output").removeSuffix("-tone.png")
        val image = Imgcodecs.imread(row.getString("source"))
        require(!image.empty()) { row.getString("source") }
        Imgproc.cvtColor(image, image, Imgproc.COLOR_BGR2RGB)
        val gray = Mat(); Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGB2GRAY)
        val begin = System.nanoTime()
        val original = image.clone()
        val colourRoute = !row.optBoolean("toneOnly", true) && ColorPage.isColorful(image)
        val (stats, times) = StepTimes.record {
            val field = StepTimes.time("paperField") { PaperField.edgePreserving(gray) }
            try {
                val field8 = Mat(); field.convertTo(field8, CvType.CV_8U)
                Imgcodecs.imwrite(row.getString("field"), field8); field8.release()
                val fv = FloatArray(field.rows()*field.cols()); field.get(0,0,fv)
                val bytes = java.nio.ByteBuffer.allocate(fv.size*4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                bytes.asFloatBuffer().put(fv); File(row.getString("field")+".f32").writeBytes(bytes.array())
                if (colourRoute) {
                    StepTimes.time("colorPage") { ColorPage.enhance(original, image) }
                    JSONObject().put("applied",true).put("reason","colorPage")
                } else ToneZones.withExactFrame(row.optBoolean("exact", false)) {
                    ToneZones.withStackFrame(row.optBoolean("stack", false)) {
                        ToneZones.withSourceScale(row.optDouble("scale", 1.0).toFloat()) {
                            val result = StepTimes.time("toneZones") { ToneZones.apply(image, field) }
                            if (!row.optBoolean("toneOnly", true)) {
                                StepTimes.time("edgeGuard") { PaperEdgeGuard.apply(image) }
                                StepTimes.time("colorPrints") { ColorPage.keepPrints(original, image) }
                            }
                            result
                        }
                    }
                }
            } finally { field.release() }
        }
        Imgproc.cvtColor(image, image, Imgproc.COLOR_RGB2BGR)
        Imgcodecs.imwrite(row.getString("output"), image)
        val result = JSONObject().put("name", row.getString("name"))
            .put("version", ToneZones.VERSION).put("opencv", Core.VERSION)
            .put("source", row.getString("source")).put("output", row.getString("output"))
            .put("width", image.cols()).put("height", image.rows())
            .put("sourceScale", row.optDouble("scale", 1.0))
            .put("toneOnly", row.optBoolean("toneOnly", true)).put("colorRoute", colourRoute)
            .put("exact", row.optBoolean("exact", false)).put("stack", row.optBoolean("stack", false))
            .put("elapsedMs", (System.nanoTime()-begin)/1e6).put("steps", times)
            .put("stats", stats)
        results.put(result); println(result.toString()); System.out.flush()
        image.release(); gray.release(); original.release(); System.gc()
    }
    File(args[3]).writeText(results.toString(2))
}
"""

SHIMS = r"""
package cn.paperforge.app.imaging
import org.json.JSONObject
object ScanConfig {
    var values = JSONObject()
    fun int(name: String, fallback: Int) = values.optInt(name, fallback)
    fun float(name: String, fallback: Float) = values.optDouble(name, fallback.toDouble()).toFloat()
}
object ProbeDump {
    var prefix = ""
    fun mask(name: String, image: org.opencv.core.Mat) {
        org.opencv.imgcodecs.Imgcodecs.imwrite(prefix+"-"+name+".png", image)
    }
    fun bool(name: String, values: BooleanArray, width: Int, height: Int) {
        val image = org.opencv.core.Mat(height,width,org.opencv.core.CvType.CV_8UC1)
        image.put(0,0,ByteArray(values.size) { if(values[it]) 255.toByte() else 0.toByte() })
        mask(name,image); image.release()
    }
}
"""


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--source-tag", default="g41")
    p.add_argument("--build-only", action="store_true")
    p.add_argument("--instrument", action="store_true")
    a = p.parse_args()
    repo = Path(__file__).resolve().parents[1]
    work = repo.parents[1] / "work"
    out = a.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    src = out / "src"
    src.mkdir(exist_ok=True)
    provenance = {}
    for name in (
        "ToneZones",
        "PaperField",
        "ParallelFilter",
        "StepTimes",
        "ColorPage",
        "PaperEdgeGuard",
    ):
        source = repo / f"app/src/main/java/cn/paperforge/app/imaging/{name}.kt"
        target = src / source.name
        shutil.copyfile(source, target)
        provenance[str(source)] = hashlib.sha256(source.read_bytes()).hexdigest()
        if name == "ToneZones" and a.instrument:
            original = source.read_text(encoding="utf8")
            injected = """            ProbeDump.mask("rules", rules)
            ProbeDump.mask("grown", grown)
            ProbeDump.mask("keep", keep)
            ProbeDump.bool("faint", faintMarks, width, height)
            ProbeDump.bool("source-ridges", BooleanArray(relative.size) { i -> ridgeOn && !faintMarks[i] && grownBytes[i] != 0.toByte() && (ridgePath == null || ridgePath[i]) && ridge(i) < targetPaper }, width, height)
            ProbeDump.bool("drawn-rules", BooleanArray(relative.size).also { mask -> for (index in lineIndex) mask[index] = true }, width, height)
            if (lineSeed != null) ProbeDump.mask("line-seeds", lineSeed.mask)
"""
            instrumented = original.replace(
                '            lap("drawRules")',
                injected + '            lap("drawRules")',
            )
            assert instrumented != original
            target.write_text(instrumented, encoding="utf8")
            (out / "instrumentation-diff.patch").write_text(
                "".join(
                    difflib.unified_diff(
                        original.splitlines(True),
                        instrumented.splitlines(True),
                        fromfile="production/ToneZones.kt",
                        tofile="private-instrumented/ToneZones.kt",
                    )
                ),
                encoding="utf8",
            )
    (src / "ToneJvm.kt").write_text(RUNNER, encoding="utf8")
    (src / "Shims.kt").write_text(SHIMS, encoding="utf8")
    (src / "Log.kt").write_text(
        "package android.util\nobject Log { fun d(tag: String, value: String): Int = 0 }\n",
        encoding="utf8",
    )
    config = repo / "app/src/main/assets/scan_params.json"
    shutil.copyfile(config, out / "scan_params.json")
    provenance[str(config)] = hashlib.sha256(config.read_bytes()).hexdigest()
    compiler = work / "toolchain/gradle-8.13/lib"
    jars = [
        out / "opencv-4.9.0-0.jar",
        out / "json-20250517.jar",
        compiler / "kotlin-stdlib-2.0.21.jar",
        compiler / "annotations-24.0.1.jar",
    ]
    classpath = ";".join(str(q) for q in jars if q.exists())
    for jar in jars[:2]:
        provenance[str(jar)] = hashlib.sha256(jar.read_bytes()).hexdigest()
    native = next(out.rglob("opencv_java490.dll"))
    provenance[str(native)] = hashlib.sha256(native.read_bytes()).hexdigest()
    (out / "source-provenance.json").write_text(
        json.dumps(provenance, indent=2), encoding="utf8"
    )
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
            "-classpath",
            classpath,
            "-d",
            str(out / "tone-probe.jar"),
            *map(str, src.glob("*.kt")),
        ],
        check=True,
    )
    if a.build_only:
        return
    folder = work / "page-net/phone-out" / a.source_tag
    run_data = json.loads((folder / "runs.json").read_text(encoding="utf8"))
    lookup = {Path(r["file"]).stem: r for r in run_data}
    rendered = out / (a.source_tag + ("-instrumented" if a.instrument else ""))
    rendered.mkdir(exist_ok=True)
    manifest = []
    for path in sorted(folder.glob("*-2-flat.png")):
        name = path.name.removesuffix("-2-flat.png")
        row = lookup.get(name, {})
        diag = json.loads(row.get("diagnostics", "{}"))
        # Match current FlatDocument/PhoneCaptureTest call scopes exactly. Old
        # diagnostic names such as rule-ends are not SheetEdge contexts.
        manifest.append(
            dict(
                name=name,
                source=str(path),
                output=str(rendered / f"{name}-tone.png"),
                field=str(rendered / f"{name}-field.png"),
                scale=diag.get("sourceScale", 1),
                exact="rule-frame" in row.get("method", ""),
                stack="sheet-edge" in row.get("method", ""),
            )
        )
    (rendered / "manifest.json").write_text(
        json.dumps(manifest, indent=2), encoding="utf8"
    )
    subprocess.run(
        [
            "java",
            "-Xmx4g",
            "-cp",
            str(out / "tone-probe.jar") + ";" + classpath,
            "cn.paperforge.app.imaging.ToneJvmKt",
            str(native),
            str(out / "scan_params.json"),
            str(rendered / "manifest.json"),
            str(rendered / "results.json"),
        ],
        check=True,
    )


if __name__ == "__main__":
    main()
