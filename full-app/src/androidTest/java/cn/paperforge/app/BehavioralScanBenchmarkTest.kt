package cn.paperforge.app

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * One on-device implementation for default runs and parameter sweeps; private inputs stay private.
 */
class BehavioralScanBenchmarkTest {
    @Test
    fun renderPairedPhotosAndParameterSweeps() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val directory = File(app.filesDir, "behavioral-v1")
        val manifest = JSONObject(File(directory, "manifest.json").readText())
        val cases = manifest.getJSONArray("cases")
        val sweeps =
            manifest.optJSONArray("sweeps") ?: JSONArray().put(JSONObject().put("id", "default"))
        val results = JSONArray()
        val output = File(directory, "output").apply { mkdirs() }
        for (s in 0 until sweeps.length()) {
            val sweep = sweeps.getJSONObject(s)
            val saved =
                ScanConfig.replaceForBenchmark(sweep.optJSONObject("params") ?: JSONObject())
            try {
                for (i in 0 until cases.length()) {
                    val sample = cases.getJSONObject(i)
                    val id = sample.getString("id") + "-" + sweep.getString("id")
                    val sourceFile = File(directory, sample.getString("input"))
                    val started = System.nanoTime()
                    val detected =
                        ScanProcessor.detectFile(
                            sourceFile,
                            detectorOverride = sweep.optString("engine", "INDEPENDENT"),
                            lockReference = false,
                        )
                    val row =
                        JSONObject()
                            .put("id", sample.getString("id"))
                            .put("sweep", sweep.getString("id"))
                            .put("method", detected.method)
                            .put("confidence", detected.confidence)
                            .put("detectMs", (System.nanoTime() - started) / 1e6)
                            .put(
                                "quad",
                                JSONArray(
                                    (detected.quad ?: Quad.Full).points.map {
                                        JSONArray(listOf(it.x, it.y))
                                    }
                                ),
                            )
                    val manual = sample.optJSONArray("manualQuad")
                    val crop =
                        if (sweep.optString("crop") == "manual" && manual != null) {
                            Quad(
                                (0 until manual.length()).map {
                                    val p = manual.getJSONArray(it)
                                    PointN(p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
                                }
                            )
                        } else detected.quad ?: Quad.Full
                    row.put(
                        "cropSource",
                        if (sweep.optString("crop") == "manual") "approximate-manual-isolation"
                        else "automatic",
                    )
                    val source = ScanProcessor.decode(sourceFile, 1800)
                    val semantic = LearnedDocumentDetector.detect(source)
                    row.put("semanticConfidence", semantic.confidence)
                        .put("semanticMethod", semantic.method)
                    semantic.quad?.let { q ->
                        row.put(
                            "semanticQuad",
                            JSONArray(q.points.map { JSONArray(listOf(it.x, it.y)) }),
                        )
                    }
                    try {
                        val cropped =
                            ScanProcessor.transform(source, crop, "ORIGINAL", targetEdge = 1800)
                        try {
                            val dewarp =
                                PageDewarp.process(
                                    cropped,
                                    sweep.optDouble("dewarpStrength", .85).toFloat(),
                                )
                            row.put("dewarpLines", dewarp.lines)
                                .put("dewarpNote", dewarp.note)
                                .put("dewarpApplied", dewarp.grid.strength > 0f)
                            try {
                                save(cropped, File(output, "$id-crop.png"))
                                save(dewarp.bitmap, File(output, "$id-dewarp.png"))
                                val modes =
                                    sweep.optJSONArray("modes")
                                        ?: JSONArray(listOf("AUTO", "TEXT", "BW", "WORKBOOK"))
                                row.put("modes", modes)
                                for (m in 0 until modes.length()) {
                                    val mode = modes.getString(m)
                                    val image =
                                        ScanProcessor.transform(
                                            dewarp.bitmap,
                                            Quad.Full,
                                            mode,
                                            strength = .85f,
                                            targetEdge = 1800,
                                        )
                                    try {
                                        save(image, File(output, "$id-${mode.lowercase()}.png"))
                                    } finally {
                                        image.recycle()
                                    }
                                }
                            } finally {
                                dewarp.bitmap.recycle()
                            }
                        } finally {
                            cropped.recycle()
                        }
                    } finally {
                        source.recycle()
                    }
                    results.put(row)
                }
            } finally {
                ScanConfig.restoreAfterBenchmark(saved)
            }
        }
        File(output, "results.json").writeText(JSONObject().put("cases", results).toString(2))
        assertEquals(cases.length() * sweeps.length(), results.length())
    }

    private fun save(bitmap: Bitmap, target: File) {
        target.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
