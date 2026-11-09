package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.imaging.FlatDocument
import cn.paperforge.app.imaging.LineStraightness
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.imaging.ScanProcessor
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measures the actual Android geometry chain with the straightness stage off and on.
 *
 * DocUNet photos are cropped by the shipped detector/transform first; the private corpus has no
 * dense geometry truth, so this reports line-fit proxies, not pixel accuracy. The two owner photo
 * crops are already current-chain outputs and are measured as an after-the-fact A/B.
 */
class GeometryStraightnessProbeTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun compareGeometryChainBeforeAndAfterLineConstraint() {
        val out = File(context.filesDir, "geometry-straight-probe").apply { mkdirs() }
        val rows = JSONArray()
        deviceCrops(out, rows)
        docunetCorpus(out, rows)
        File(out, "metrics.json").writeText(
            JSONObject()
                .put("scope", "Android geometry chain; DocUNet crops by shipped detector; no dense truth")
                .put("rows", rows)
                .toString(2)
        )
        assertTrue("expected at least one measured page", rows.length() > 0)
    }

    private fun deviceCrops(out: File, rows: JSONArray) {
        for (name in listOf("math-crop.png", "book-crop.png")) {
            val file = File(context.filesDir, "feedback-20010/$name")
            if (!file.isFile) continue
            val bitmap = BitmapFactory.decodeFile(file.path) ?: continue
            try {
                measureAndWrite(bitmap, out, rows, "device-$name", baseline = null)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun docunetCorpus(out: File, rows: JSONArray) {
        val corpus = File(context.filesDir, "docunet")
        val photos =
            corpus
                .listFiles { file -> file.isFile && file.extension.lowercase() in setOf("jpg", "jpeg", "png") }
                ?.sortedBy { it.name }
                .orEmpty()
        var selected = 0
        for (file in photos) {
            if (selected >= 8) break
            val decoded =
                try {
                    ScanProcessor.decode(file, 1800)
                } catch (_: Exception) {
                    continue
                }
            try {
                val detection = ScanProcessor.detectResult(decoded)
                val quad = detection.quad ?: continue
                val crop =
                    try {
                        ScanProcessor.transform(decoded, quad, "ORIGINAL", targetEdge = 1800)
                    } catch (_: Exception) {
                        continue
                    }
                try {
                    val pre = measure(crop)
                    if (pre.horizontal.lines < 3 && pre.vertical.lines < 3 && pre.text.lines < 5) {
                        continue
                    }
                    val snapshot =
                        ScanConfig.replaceForBenchmark(JSONObject().put("gridStraighten", 0))
                    val baseline =
                        try {
                            FlatDocument.geometry(crop, crop)
                        } finally {
                            ScanConfig.restoreAfterBenchmark(snapshot)
                        }
                    val afterSnapshot =
                        ScanConfig.replaceForBenchmark(JSONObject().put("gridStraighten", 1))
                    val improved =
                        try {
                            FlatDocument.geometry(crop, crop)
                        } finally {
                            ScanConfig.restoreAfterBenchmark(afterSnapshot)
                        }
                    try {
                        measureAndWrite(
                            crop,
                            out,
                            rows,
                            "docunet-${file.nameWithoutExtension}",
                            baseline = baseline.bitmap to improved.bitmap,
                        )
                        rows.getJSONObject(rows.length() - 1)
                            .put("algorithm", improved.algorithm)
                            .put("baselineNote", baseline.note)
                            .put("improvedNote", improved.note)
                            .put("gridStrength", improved.grid.strength.toDouble())
                        improved.bitmap.recycle()
                    } finally {
                        baseline.bitmap.recycle()
                    }
                    selected++
                } finally {
                    crop.recycle()
                }
            } finally {
                decoded.recycle()
            }
        }
    }

    private fun measureAndWrite(
        source: Bitmap,
        out: File,
        rows: JSONArray,
        tag: String,
        baseline: Pair<Bitmap, Bitmap>?,
    ) {
        val pre = measure(source)
        if (baseline != null) {
            val before = measure(baseline.first)
            val after = measure(baseline.second)
            File(out, "$tag-baseline.png").outputStream().use {
                baseline.first.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(out, "$tag-improved.png").outputStream().use {
                baseline.second.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            rows.put(
                JSONObject()
                    .put("file", tag)
                    .put("kind", "docunet-full-chain")
                    .put("sourceShape", jsonShape(source))
                    .put("pre", json(pre))
                    .put("baseline", json(before))
                    .put("improved", json(after))
                    .put(
                        "content",
                        JSONObject()
                            .put("inkRatio", after.inkMass / max(1e-6, before.inkMass))
                            .put(
                                "horizontalSpacingRatio",
                                after.horizontal.spacingMedian /
                                    max(1e-6, before.horizontal.spacingMedian),
                            )
                    )
            )
            return
        }
        val result = LineStraightness.estimate(grayscale(source), source.width, source.height)
        val row = JSONObject().put("file", tag).put("kind", "device-crop").put("before", json(pre))
        if (result == null) {
            row.put("accepted", false).put("reason", "no-estimate")
        } else {
            val corrected = LineStraightness.apply(
                grayscale(source),
                source.width,
                source.height,
                result.correction,
            )
            val image = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            for (y in 0 until source.height) for (x in 0 until source.width) {
                val value = corrected[y * source.width + x].toInt() and 255
                image.setPixel(x, y, (0xff shl 24) or (value shl 16) or (value shl 8) or value)
            }
            File(out, "$tag-before.png").outputStream().use {
                source.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(out, "$tag-after.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
            row.put("accepted", result.guard.accepted)
                .put("after", json(result.after))
                .put("guard", jsonGuard(result.guard))
                .put("correction", jsonCorrection(result.correction))
        }
        rows.put(row)
    }

    private fun measure(bitmap: Bitmap): LineStraightness.Metrics =
        LineStraightness.measure(grayscale(bitmap), bitmap.width, bitmap.height)

    private fun grayscale(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return ByteArray(pixels.size) {
            val value = pixels[it]
            val r = (value shr 16) and 255
            val g = (value shr 8) and 255
            val b = value and 255
            ((r + 2 * g + b) / 4).toByte()
        }
    }

    private fun json(metrics: LineStraightness.Metrics): JSONObject =
        JSONObject()
            .put("horizontal", axis(metrics.horizontal))
            .put("vertical", axis(metrics.vertical))
            .put("text", axis(metrics.text))
            .put("glyphHeight", metrics.glyphHeight)
            .put("ink", metrics.inkMass)

    private fun axis(axis: LineStraightness.AxisMetric): JSONObject =
        JSONObject()
            .put("lines", axis.lines)
            .put("rms", axis.rmsMedian)
            .put("rmsP95", axis.rmsP95)
            .put("tiltDeg", axis.tiltMedianDeg)
            .put("tiltAbsDeg", axis.tiltAbsMedianDeg)
            .put("fwhm", axis.fwhmMedian)
            .put("span", axis.spanMedian)
            .put("spacing", axis.spacingMedian)

    private fun jsonGuard(guard: LineStraightness.Guard): JSONObject =
        JSONObject()
            .put("accepted", guard.accepted)
            .put("reason", guard.reason)
            .put("inkRetention", guard.inkRetention)
            .put("glyphHeightRatio", guard.glyphHeightRatio)
            .put("spacingRatio", guard.spacingRatio)

    private fun jsonCorrection(correction: LineStraightness.Correction): JSONObject =
        JSONObject()
            .put("angleDeg", correction.angleDeg)
            .put("maxShift", correction.maxShift)
            .put("linesH", correction.linesH)
            .put("linesV", correction.linesV)

    private fun jsonShape(bitmap: Bitmap): JSONArray =
        JSONArray().put(bitmap.width).put(bitmap.height)
}
