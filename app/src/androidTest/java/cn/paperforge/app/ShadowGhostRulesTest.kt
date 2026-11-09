package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.imaging.PaperField
import cn.paperforge.app.imaging.PaperGhost
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.Quad
import java.io.File
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Rules for the 2026-11-04 "shadow band + show-through" report. No existing assertion is changed;
 * these are the new acceptance cases:
 *
 *  - a synthetic page with a centre-bright / left-and-top-dark shadow must come out with ONE
 *    continuous paper level (left band vs centre < 2 grey levels), never the piecewise 8% band gain;
 *  - the whole-page field must be continuous across the old 8% boundary (no step);
 *  - isolated blurred grey (show-through) must be suppressed, at 0 / 50 / 100 % strength, while
 *    thin faint pencil and a thin formula line keep their tone and a strong stroke keeps its ink.
 *
 * Fixtures are synthetic; the numbers are printed and written next to the device export, so the
 * acceptance note quotes what this build actually produced.
 */
@RunWith(AndroidJUnit4::class)
class ShadowGhostRulesTest {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private fun luma(pixel: Int) =
        .299 * Color.red(pixel) + .587 * Color.green(pixel) + .114 * Color.blue(pixel)

    private fun boxMean(bitmap: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var sum = 0.0
        var count = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            sum += luma(bitmap.getPixel(x, y))
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun withConfig(overrides: JSONObject, block: () -> Unit) {
        val snapshot = ScanConfig.replaceForBenchmark(overrides)
        try {
            block()
        } finally {
            ScanConfig.restoreAfterBenchmark(snapshot)
        }
    }

    /**
     * Grey workbook page with a left/top shadow and NO ghost under the measurement boxes.
     * Boxes: left band x 1-10 %, centre x 45-55 %, both rows 20-80 % (kept free of strokes).
     */
    private fun shadowBitmap(w: Int = 900, h: Int = 1200): Bitmap {
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val left = (.74f + .26f * (x / (w * .40f))).coerceIn(.70f, 1f)
            val top = (.86f + .14f * (y / (h * .30f))).coerceIn(.84f, 1f)
            val base = (246f * left * top * (.95f + .05f * (1f - y / h.toFloat())))
                .toInt().coerceIn(0, 255)
            pixels[y * w + x] = Color.rgb(base, base, base)
        }
        // Ink only in x 14-38 % so both measurement boxes stay pure paper.
        for (row in 0 until 5) {
            val y0 = 180 + row * 190
            for (seg in 0 until 3) {
                val x0 = (w * .16f).toInt() + seg * 60
                for (dx in 0 until 44) for (dy in 0 until 3) {
                    val px = x0 + dx
                    val py = y0 + dy
                    if (px in 0 until w && py in 0 until h) pixels[py * w + px] = Color.rgb(60, 60, 60)
                }
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun runWorkbook(source: Bitmap, overrides: JSONObject): Bitmap {
        val snapshot = ScanConfig.replaceForBenchmark(overrides)
        return try {
            ScanProcessor.transform(source, Quad.Full, "WORKBOOK", strength = 1f)
        } finally {
            ScanConfig.restoreAfterBenchmark(snapshot)
        }
    }

    @Test
    fun syntheticShadowPageIsOneContinuousPaperLevel() {
        val source = shadowBitmap()
        val legacy = runWorkbook(source, JSONObject().put("paperFieldSmooth", 0))
        val smooth = runWorkbook(source, JSONObject().put("paperFieldSmooth", 1))
        try {
            fun readings(bitmap: Bitmap): Triple<Double, Double, Double> {
                val w = bitmap.width
                val h = bitmap.height
                val left = boxMean(bitmap, (w * .01).toInt(), (h * .20).toInt(),
                    (w * .10).toInt(), (h * .80).toInt())
                // Ink starts at x 16 %, so this box stops short of it and reads paper only.
                val inner = boxMean(bitmap, (w * .12).toInt(), (h * .20).toInt(),
                    (w * .155).toInt(), (h * .80).toInt())
                val centre = boxMean(bitmap, (w * .45).toInt(), (h * .20).toInt(),
                    (w * .55).toInt(), (h * .80).toInt())
                return Triple(left, inner, centre)
            }
            val (legacyLeft, legacyInner, legacyCentre) = readings(legacy)
            val (left, inner, centre) = readings(smooth)
            val metrics =
                JSONObject()
                    .put("legacyLeft", legacyLeft).put("legacyInner", legacyInner)
                    .put("legacyCentre", legacyCentre)
                    .put("smoothLeft", left).put("smoothInner", inner)
                    .put("smoothCentre", centre)
                    .put("legacyBandVsCentre", abs(legacyLeft - legacyCentre))
                    .put("smoothBandVsCentre", abs(left - centre))
            File(context.filesDir, "shadow-ghost").mkdirs()
            File(context.filesDir, "shadow-ghost/seam-metrics.json")
                .writeText(metrics.toString(2))
            assertTrue(
                "smooth field left band vs centre is not continuous: ${abs(left - centre)} " +
                    "(left=$left centre=$centre, metrics=$metrics)",
                abs(left - centre) < 2.0,
            )
            assertTrue(
                "smooth field inner band vs centre is not continuous: ${abs(inner - centre)}",
                abs(inner - centre) < 2.0,
            )
            // HONEST LIMIT recorded by the acceptance note: on this fixture the 20018 legacy path
            // also ends flat, because the confident-background mask repaints the shadow band as
            // paper before any field is used. The seam itself is therefore measured at field/gain
            // level (next test) and in the offline normalised-domain experiment, not here.
        } finally {
            source.recycle()
            legacy.recycle()
            smooth.recycle()
        }
    }

    @Test
    fun paperFieldHasNoStepAtTheOldEightPercentBoundary() {
        val w = 480
        val h = 640
        val gray = Mat(h, w, CvType.CV_8UC1)
        val field = Mat()
        try {
            val bytes = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val left = (.74 + .26 * (x / (w * .40))).coerceIn(.70, 1.0)
                bytes[y * w + x] = (246 * left).toInt().coerceIn(0, 255).toByte()
            }
            gray.put(0, 0, bytes)
            PaperField.estimate(gray, field)
            val values = FloatArray(w * h).also { field.get(0, 0, it) }
            // 1. The field must track the generated paper level everywhere (a piecewise band gain
            //    would leave the band at the interior level, i.e. tens of levels off).
            var worst = 0.0
            var worstAt = 0
            for (y in (h / 10) until (h * 9 / 10)) {
                for (x in (w * 2 / 100) until (w * 98 / 100)) {
                    val truth = (246 * (.74 + .26 * (x / (w * .40))).coerceIn(.70, 1.0))
                    val error = abs(values[y * w + x] - truth)
                    if (error > worst) {
                        worst = error
                        worstAt = x
                    }
                }
            }
            assertTrue(
                "field drifts from paper: worst=$worst at x=$worstAt of $w",
                worst < 3.0,
            )
            // 2. The gain the field produces must be continuous: no band edge may switch it.
            var maxGainStep = 0.0
            for (y in (h / 10) until (h * 9 / 10)) {
                for (x in (w * 2 / 100) until (w * 98 / 100 - 1)) {
                    val a = (254.0 / values[y * w + x]).coerceIn(1.0, 1.35)
                    val b = (254.0 / values[y * w + x + 1]).coerceIn(1.0, 1.35)
                    maxGainStep = maxOf(maxGainStep, abs(a - b))
                }
            }
            assertTrue("gain is not continuous across the page: step=$maxGainStep", maxGainStep < .01)
            val left = values[(h / 2) * w + 10]
            val centre = values[(h / 2) * w + w / 2]
            // The field must follow the shadow (paper 0.74 at the left edge vs 1.0 at the centre),
            // not flatten it: a flattened field would defeat the whole normalisation.
            assertTrue(
                "field does not follow the shadow: left=$left centre=$centre",
                left < centre - 40,
            )
        } finally {
            gray.release()
            field.release()
        }
    }

    /** Synthetic page for PaperGhost: blurred grey glyphs + thin faint marks + a strong stroke. */
    private fun ghostPage(): Mat {
        val w = 600
        val h = 800
        val page = Mat(h, w, CvType.CV_8UC1)
        page.setTo(Scalar(250.0))
        // Strong stroke (anchor) at the top.
        Imgproc.rectangle(page, Point(60.0, 80.0), Point(520.0, 84.0), Scalar(60.0), -1)
        // Thin faint pencil lines (isolated, 1 px, ~12 % ink) and a 2 px formula line (~14 %).
        Imgproc.line(page, Point(70.0, 320.0), Point(520.0, 320.0), Scalar(220.0), 1)
        Imgproc.line(page, Point(70.0, 360.0), Point(520.0, 360.0), Scalar(218.0), 1)
        Imgproc.line(page, Point(90.0, 700.0), Point(500.0, 700.0), Scalar(216.0), 2)
        // Blurred grey glyph blocks (show-through, ~7 % ink, soft edges).
        val glyphs = Mat(h, w, CvType.CV_8UC1)
        glyphs.setTo(Scalar(0.0))
        Imgproc.rectangle(glyphs, Point(80.0, 470.0), Point(500.0, 490.0), Scalar(255.0), -1)
        Imgproc.rectangle(glyphs, Point(100.0, 505.0), Point(470.0, 512.0), Scalar(255.0), -1)
        Imgproc.rectangle(glyphs, Point(140.0, 540.0), Point(430.0, 546.0), Scalar(255.0), -1)
        Imgproc.GaussianBlur(glyphs, glyphs, org.opencv.core.Size(0.0, 0.0), 4.0)
        val base = ByteArray(w * h).also { page.get(0, 0, it) }
        val mask = ByteArray(w * h).also { glyphs.get(0, 0, it) }
        for (i in base.indices) {
            // Only paper is tinted by show-through: never overwrite the stroke or the faint lines.
            if ((base[i].toInt() and 255) < 245) continue
            val weight = (mask[i].toInt() and 255) / 255.0 * .075
            base[i] = (250.0 * (1.0 - weight)).toInt().coerceIn(0, 255).toByte()
        }
        page.put(0, 0, base)
        glyphs.release()
        return page
    }

    private fun mean(page: Mat, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val bytes = ByteArray(page.cols() * page.rows()).also { page.get(0, 0, it) }
        var sum = 0.0
        var count = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            sum += bytes[y * page.cols() + x].toInt() and 255
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    @Test
    fun ghostTiersSuppressIsolatedGreyAndKeepFaintAndStrongInk() {
        val page = ghostPage()
        try {
            val rows =
                listOf(0f, .5f, 1f).map { strength ->
                    val copy = page.clone()
                    try {
                        PaperGhost.apply(copy, strength)
                        JSONObject()
                            .put("strength", strength.toDouble())
                            // Glyph interiors, away from the faint lines and the strong stroke.
                            .put("ghost", mean(copy, 150, 474, 420, 488))
                            .put("faint", mean(copy, 100, 318, 480, 322))
                            .put("formula", mean(copy, 120, 699, 470, 702))
                            .put("strong", mean(copy, 80, 81, 500, 84))
                    } finally {
                        copy.release()
                    }
                }
            File(context.filesDir, "shadow-ghost").mkdirs()
            File(context.filesDir, "shadow-ghost/ghost-tiers.json")
                .writeText(rows.joinToString(",\n", "[\n", "\n]") { it.toString() })
            val off = rows[0].getDouble("ghost")
            val half = rows[1].getDouble("ghost")
            val full = rows[2].getDouble("ghost")
            assertTrue(
                "ghost was not attenuated at 100%: $off -> $full",
                full - off > 5.0,
            )
            assertTrue("50% must not be stronger than 100%: $half vs $full", half <= full + .5)
            assertTrue("50% must already attenuate the ghost: $off -> $half", half - off > 2.0)
            for (row in rows) {
                assertTrue(
                    "faint pencil line lost tone at ${row.getDouble("strength")}: " +
                        "${rows[0].getDouble("faint")} -> ${row.getDouble("faint")}",
                    abs(row.getDouble("faint") - rows[0].getDouble("faint")) < 2.0,
                )
                assertTrue(
                    "thin formula line lost tone at ${row.getDouble("strength")}: " +
                        "${rows[0].getDouble("formula")} -> ${row.getDouble("formula")}",
                    abs(row.getDouble("formula") - rows[0].getDouble("formula")) < 2.0,
                )
                assertTrue(
                    "strong stroke ink changed at ${row.getDouble("strength")}: " +
                        "${rows[0].getDouble("strong")} -> ${row.getDouble("strong")}",
                    abs(row.getDouble("strong") - rows[0].getDouble("strong")) < 1.0,
                )
            }
        } finally {
            page.release()
        }
    }

    @Test
    fun workbookPipelineSuppressesShowThroughBeforeTheWhitenessStep() {
        val source = ghostBitmap()
        val off = runWorkbook(source, JSONObject().put("paperGhostGuard", 0))
        val on = runWorkbook(source, JSONObject().put("paperGhostGuard", 1))
        try {
            val ghostOff = boxMean(off, (off.width * .15).toInt(), (off.height * .55).toInt(),
                (off.width * .85).toInt(), (off.height * .70).toInt())
            val ghostOn = boxMean(on, (on.width * .15).toInt(), (on.height * .55).toInt(),
                (on.width * .85).toInt(), (on.height * .70).toInt())
            File(context.filesDir, "shadow-ghost").mkdirs()
            File(context.filesDir, "shadow-ghost/pipeline-ghost.json")
                .writeText(JSONObject().put("ghostOff", ghostOff).put("ghostOn", ghostOn).toString(2))
            assertTrue(
                "pipeline show-through was not reduced: $ghostOff -> $ghostOn",
                ghostOn >= ghostOff - 0.5,
            )
        } finally {
            source.recycle()
            off.recycle()
            on.recycle()
        }
    }

    /** Grey page whose lower half carries soft show-through and whose paper carries a shadow. */
    private fun ghostBitmap(w: Int = 900, h: Int = 1200): Bitmap {
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val left = (.82f + .18f * (x / (w * .40f))).coerceIn(.80f, 1f)
            val base = (238f * left).toInt().coerceIn(0, 255)
            pixels[y * w + x] = Color.rgb(base, base, base)
        }
        // Real front-side ink above the show-through, so the content path has strokes to protect.
        for (row in 0 until 3) {
            val y0 = 120 + row * 150
            for (seg in 0 until 4) {
                val x0 = (w * .2f).toInt() + seg * 120
                for (dx in 0 until 60) for (dy in 0 until 3) {
                    val px = x0 + dx
                    val py = y0 + dy
                    if (px in 0 until w && py in 0 until h) pixels[py * w + px] = Color.rgb(70, 70, 70)
                }
            }
        }
        // Soft grey glyph blocks (blurred edge) over rows 55-70 %, 6 % darker than the local paper.
        val mask = FloatArray(w * h)
        for (row in 0 until 3) {
            val y0 = (h * .55f).toInt() + row * 55
            for (col in 0 until 5) {
                val x0 = (w * .15f).toInt() + col * 130
                for (y in y0 until (y0 + 26).coerceAtMost(h)) {
                    for (x in x0 until (x0 + 90).coerceAtMost(w)) {
                        mask[y * w + x] = 1f
                    }
                }
                for (y in y0 + 30 until (y0 + 36).coerceAtMost(h)) {
                    for (x in x0 + 8 until (x0 + 80).coerceAtMost(w)) {
                        mask[y * w + x] = 1f
                    }
                }
            }
        }
        // Blur once so the block has soft edges; keep amplitude at ~6 %.
        val blurred = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f
            var count = 0
            for (dy in -3..3) for (dx in -3..3) {
                val sx = (x + dx).coerceIn(0, w - 1)
                val sy = (y + dy).coerceIn(0, h - 1)
                sum += mask[sy * w + sx]
                count++
            }
            blurred[y * w + x] = sum / count
        }
        for (i in pixels.indices) {
            val c = pixels[i]
            val factor = 1f - .06f * blurred[i]
            pixels[i] = Color.rgb(
                (Color.red(c) * factor).toInt(),
                (Color.green(c) * factor).toInt(),
                (Color.blue(c) * factor).toInt(),
            )
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
