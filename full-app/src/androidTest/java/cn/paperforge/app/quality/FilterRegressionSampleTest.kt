package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.Quad
import java.io.File
import kotlin.math.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.2.0 filter-regression evidence for AUTO / TEXT / BW / WORKBOOK.
 *
 * Two sources:
 * - `local-sample-3.pdf` page 1 from the device (owner-provided scanner sample, kept private)
 *   for the before/after PNG pair and the automatic speck / shadow / faint-stroke metrics.
 * - A deterministic synthetic sheet with known masks for ink, light print, pencil, shadowed paper
 *   and salt specks, so stroke retention and paper speck rate have ground truth.
 *
 * The test only writes into the app's private files dir; the acceptance page pulls the PNGs. It
 * asserts only the direction required by the user report, not an aesthetic "score".
 */
@RunWith(AndroidJUnit4::class)
class FilterRegressionSampleTest {
    private val modes = listOf("AUTO", "TEXT", "BW", "WORKBOOK")

    // ---------- deterministic synthetic sheet ----------

    private class Sheet(val w: Int = 1000, val h: Int = 1400) {
        val pixels = IntArray(w * h)
        val paper = BooleanArray(w * h)
        val paperInterior = BooleanArray(w * h)
        val text = BooleanArray(w * h)
        val light = BooleanArray(w * h)
        val pencil = BooleanArray(w * h)
        val speck = BooleanArray(w * h)
        val shadowPaper = BooleanArray(w * h)
        private val illum = FloatArray(w * h)

        fun build(): Sheet {
            val noise = java.util.Random(20261031)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                // Uneven lamp light plus a strong shadow band on the right.
                val horizontal = .58f + .42f * x / (w - 1)
                val vignette = .93f + .07f * (1f - y.toFloat() / h)
                illum[i] = horizontal * vignette * (1f + (noise.nextFloat() - .5f) * .012f)
                val base = (globals.paperTarget * illum[i]).toInt().coerceIn(0, 255)
                pixels[i] = Color.rgb(base, base, base)
            }
            // Printed body text (solid dark, thin strokes).
            var y = 90
            while (y < 760) {
                for (line in 0 until 4) {
                    val py = y + line * 15
                    for (x in 80 until w - 120) {
                        if ((x / 19) % 4 == 3) continue
                        for (dy in 0 until 3) {
                            val yy = py + dy
                            if (yy !in 0 until h) continue
                            mark(x, yy, 38f, text)
                        }
                    }
                }
                y += 140
            }
            // Light print ("浅字"), still readable, on its own band.
            for (line in 0 until 5) {
                val py = 880 + line * 34
                for (x in 90 until w - 330) {
                    for (dy in 0 until 2) mark(x, py + dy, 132f, light)
                }
            }
            // Pencil answers: thin, light, slightly broken.
            for (line in 0 until 4) {
                val py = 1080 + line * 34
                for (x in 420 until w - 90) {
                    if (x % 9 == 0) continue
                    for (dy in -1..1) mark(x, py + dy, 158f, pencil)
                }
            }
            // Salt noise on blank paper: 1–2 px dark specks. This is the BW complaint.
            var added = 0
            while (added < 260) {
                val x = noise.nextInt(w - 40) + 20
                val yy = noise.nextInt(h - 40) + 20
                val i = yy * w + x
                if (text[i] || light[i] || pencil[i]) continue
                mark(x, yy, 28f, speck)
                if (noise.nextBoolean()) mark(x + 1, yy, 28f, speck)
                if (noise.nextBoolean()) mark(x, yy + 1, 28f, speck)
                added++
            }
            for (yy in 0 until h) for (x in 0 until w) {
                val i = yy * w + x
                val lit = illum[i] * globals.paperTarget
                paper[i] = !text[i] && !light[i] && !pencil[i] && !speck[i] && lit >= 170f
                shadowPaper[i] = !text[i] && !pencil[i] && lit in 110f..150f
            }
            // The perspective renderer resamples 1000x1400 to 999x1399. Nearest-neighbour
            // evaluation at a stroke boundary counts antialiased ink as a new paper speck.
            // Keep that raw metric, but evaluate background noise on known paper at least
            // two source pixels from ink/noise masks. Do not relax the noise threshold.
            for (yy in 2 until h - 2) for (x in 2 until w - 2) {
                val i = yy * w + x
                if (!paper[i]) continue
                var clear = true
                for (dy in -2..2) for (dx in -2..2) {
                    val j = (yy + dy) * w + x + dx
                    if (text[j] || light[j] || pencil[j] || speck[j]) clear = false
                }
                paperInterior[i] = clear
            }
            return this
        }

        private fun mark(x: Int, y: Int, value: Float, mask: BooleanArray) {
            if (x !in 0 until w || y !in 0 until h) return
            val i = y * w + x
            mask[i] = true
            paper[i] = false
            val v = (value * illum[i]).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(v, v, v)
        }

        fun bitmap(): Bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)

        private fun luma(p: Int) =
            .299 * Color.red(p) + .587 * Color.green(p) + .114 * Color.blue(p)

        private fun sample(
            bitmap: Bitmap,
            mask: BooleanArray,
            test: ((Double) -> Boolean)?,
        ): Double {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var sum = 0.0
            var hit = 0
            var n = 0
            for (y in 0 until h) {
                val by = y * bitmap.height / h
                for (x in 0 until w) {
                    if (!mask[y * w + x]) continue
                    val value = luma(pixels[by * bitmap.width + x * bitmap.width / w])
                    n++
                    sum += value
                    if (test != null && test(value)) hit++
                }
            }
            return when {
                test != null && n > 0 -> hit.toDouble() / n
                n > 0 -> sum / n
                else -> 0.0
            }
        }

        fun mean(bitmap: Bitmap, mask: BooleanArray): Double = sample(bitmap, mask, null)

        fun fraction(bitmap: Bitmap, mask: BooleanArray, test: (Double) -> Boolean): Double =
            sample(bitmap, mask, test)

        fun record(bitmap: Bitmap): JSONObject =
            JSONObject()
                .put("paperTone", mean(bitmap, paper))
                .put("paperDarkRate", fraction(bitmap, paper) { it < 120 })
                .put("textTone", mean(bitmap, text))
                .put("lightTone", mean(bitmap, light))
                .put("lightRetained", fraction(bitmap, light) { it < 205 })
                .put("pencilTone", mean(bitmap, pencil))
                .put("pencilRetained", fraction(bitmap, pencil) { it < 232 })
                .put("shadowPaperTone", mean(bitmap, shadowPaper))
                .put("speckRate", fraction(bitmap, paper) { it < 110 })
                .put("paperInteriorDarkRate", fraction(bitmap, paperInterior) { it < 110 })
                .put("sourceSpeckRetention", fraction(bitmap, speck) { it < 110 })
    }

    /** `paperTarget` is the synthetic paper base before illumination (global for the helper). */
    private object globals {
        const val paperTarget = 238f
    }

    // ---------- automatic metrics on a real page ----------

    private class Stats(val w: Int, val h: Int) {
        val gray = DoubleArray(w * h)

        fun put(bitmap: Bitmap) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (y in 0 until h) {
                val by = y * bitmap.height / h
                for (x in 0 until w) {
                    val p = pixels[by * bitmap.width + x * bitmap.width / w]
                    gray[y * w + x] =
                        .299 * Color.red(p) + .587 * Color.green(p) + .114 * Color.blue(p)
                }
            }
        }
    }

    /** Box mean over a square window, edge-clamped; used to estimate local paper level. */
    private fun boxMean(values: DoubleArray, w: Int, h: Int, radius: Int): DoubleArray {
        val out = DoubleArray(values.size)
        val tmp = DoubleArray(values.size)
        val span = 2 * radius + 1
        for (y in 0 until h) {
            var sum = 0.0
            for (x in -radius..radius) sum += values[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = sum / span
                sum += values[y * w + (x + radius + 1).coerceIn(0, w - 1)]
                sum -= values[y * w + (x - radius).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var sum = 0.0
            for (y in -radius..radius) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / span
                sum += tmp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                sum -= tmp[(y - radius).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    /** Counts dark connected components of at most 4 pixels: isolated specks, not glyphs. */
    private fun specks(bitmap: Bitmap): Pair<Int, Int> {
        val w = bitmap.width
        val h = bitmap.height
        val dark = BooleanArray(w * h)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val l = .299 * Color.red(p) + .587 * Color.green(p) + .114 * Color.blue(p)
            if (l < 96) dark[i] = true
        }
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var specks = 0
        var speckPixels = 0
        for (i in dark.indices) {
            if (!dark[i] || seen[i]) continue
            var head = 0
            var tail = 0
            queue[tail++] = i
            seen[i] = true
            var area = 0
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            while (head < tail) {
                val q = queue[head++]
                area++
                val x = q % w
                val y = q / w
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (dark[ni] && !seen[ni]) {
                        seen[ni] = true
                        queue[tail++] = ni
                    }
                }
            }
            if (area <= 4 && maxX - minX <= 2 && maxY - minY <= 2) {
                specks++
                speckPixels += area
            }
        }
        return specks to speckPixels
    }

    private fun pageMetrics(bitmap: Bitmap, source: DoubleArray, w: Int, h: Int): JSONObject {
        val out = Stats(w, h).apply { put(bitmap) }
        val sourceMean = boxMean(source, w, h, 7)
        val outMean = boxMean(out.gray, w, h, 7)
        // Flat source regions: low local deviation from the local paper level.
        val flat = BooleanArray(w * h)
        var flatCount = 0
        var faintCount = 0
        var faintRetained = 0
        var shadowCount = 0
        var shadowSum = 0.0
        var paperSum = 0.0
        var paperDark = 0
        for (i in 0 until w * h) {
            val src = source[i]
            val paperLevel = sourceMean[i]
            val residual = paperLevel - src
            val isFlat = abs(residual) < 6 && paperLevel > 170
            if (isFlat) {
                flat[i] = true
                flatCount++
                paperSum += out.gray[i]
                if (out.gray[i] < 120) paperDark++
            }
            // Faint ink: 10–50 below the local paper level, on a reasonably lit sheet.
            if (residual in 10.0..50.0 && paperLevel > 150 && outMean[i] - out.gray[i] >= 10) {
                faintRetained++
            }
            if (residual in 10.0..50.0 && paperLevel > 150) faintCount++
            // Shadowed paper: source local paper level is low but locally flat.
            if (paperLevel in 90.0..168.0 && abs(residual) < 5) {
                shadowCount++
                shadowSum += out.gray[i]
            }
        }
        val (speckCount, speckPixels) = specks(bitmap)
        return JSONObject()
            .put("paperTone", if (flatCount == 0) 0.0 else paperSum / flatCount)
            .put("paperDarkRate", if (flatCount == 0) 0.0 else paperDark.toDouble() / flatCount)
            .put(
                "faintRetention",
                if (faintCount == 0) 0.0 else faintRetained.toDouble() / faintCount,
            )
            .put("shadowTone", if (shadowCount == 0) 0.0 else shadowSum / shadowCount)
            .put("shadowPixels", shadowCount)
            .put("speckCount", speckCount)
            .put("speckPixels", speckPixels)
            .put("speckRatePerMegapixel", speckPixels * 1_000_000.0 / (w * h))
    }

    private fun renderSamplePage(app: PaperForgeApp): Bitmap? {
        val file = File(app.filesDir, "samples/local-sample-3.pdf")
        if (!file.exists()) return null
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val scale = 1200f / max(page.width, page.height)
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    return bitmap
                }
            }
        }
    }

    @Test
    fun presetsKeepTextAndSuppressPaperSpecks() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val output = File(app.filesDir, "filter-regression").apply { mkdirs() }
        val report = JSONObject()
        // Pages saved by the app render with `enhanceStrength` 0.85 (SettingKeys default); measure
        // the same value instead of the "full strength" that only a manual slider to 100% reaches.
        val strength = 0.85f

        // --- synthetic ground truth ---
        val sheet = Sheet().build()
        val sheetSource = sheet.bitmap()
        val synthetic = JSONObject().put("strength", strength)
        try {
            sheetSource.compress(
                Bitmap.CompressFormat.PNG,
                100,
                File(output, "synthetic-source.png").outputStream(),
            )
            synthetic.put("source", sheet.record(sheetSource))
            for (mode in modes) {
                val result =
                    ScanProcessor.transform(sheetSource, Quad.Full, mode, strength = strength)
                try {
                    synthetic.put(mode, sheet.record(result))
                    result.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        File(output, "synthetic-${mode.lowercase()}.png").outputStream(),
                    )
                } finally {
                    result.recycle()
                }
            }
        } finally {
            sheetSource.recycle()
        }
        val detailSource = sheet.bitmap()
        val reduced =
            ScanProcessor.transform(
                detailSource,
                Quad.Full,
                "WORKBOOK",
                strength = strength,
                workbookDetail = 0f,
            )
        val retained =
            ScanProcessor.transform(
                detailSource,
                Quad.Full,
                "WORKBOOK",
                strength = strength,
                workbookDetail = 1f,
            )
        try {
            synthetic.put("WORKBOOK_DETAIL_0", sheet.record(reduced))
            synthetic.put("WORKBOOK_DETAIL_1", sheet.record(retained))
            assertTrue(
                "detail control did not preserve faint strokes",
                sheet.mean(retained, sheet.light) < sheet.mean(reduced, sheet.light) - 5,
            )
        } finally {
            detailSource.recycle()
            reduced.recycle()
            retained.recycle()
        }
        report.put("synthetic", synthetic)

        // --- real device sample, before/after image + automatic metrics ---
        val sample = renderSamplePage(app)
        if (sample != null) {
            val w = sample.width
            val h = sample.height
            val srcStats = Stats(w, h).apply { put(sample) }
            val sampleReport = JSONObject().put("strength", strength)
            try {
                sample.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    File(output, "sample-source.png").outputStream(),
                )
                sampleReport.put("source", pageMetrics(sample, srcStats.gray, w, h))
                for (mode in modes) {
                    val result =
                        ScanProcessor.transform(sample, Quad.Full, mode, strength = strength)
                    try {
                        sampleReport.put(mode, pageMetrics(result, srcStats.gray, w, h))
                        result.compress(
                            Bitmap.CompressFormat.PNG,
                            100,
                            File(output, "sample-${mode.lowercase()}.png").outputStream(),
                        )
                    } finally {
                        result.recycle()
                    }
                }
            } finally {
                sample.recycle()
            }
            report.put("sample", sampleReport)
        } else {
            report.put(
                "sample",
                JSONObject().put("missing", "files/samples/local-sample-3.pdf"),
            )
        }

        File(output, "metrics.json").writeText(report.toString(2))

        // Direction-only assertions on the known synthetic masks. Tolerances are intentionally
        // loose; the acceptance page records the actual numbers.
        val bw = synthetic.getJSONObject("BW")
        val workbook = synthetic.getJSONObject("WORKBOOK")
        val text = synthetic.getJSONObject("TEXT")
        assertTrue("BW kept paper specks", bw.getDouble("paperInteriorDarkRate") <= .01)
        assertTrue("BW erased print", bw.getDouble("textTone") <= 90)
        assertTrue("WORKBOOK erased light print", workbook.getDouble("lightRetained") >= .8)
        assertTrue("WORKBOOK paper too dark", workbook.getDouble("paperTone") >= 220)
        assertTrue("WORKBOOK lost pencil", workbook.getDouble("pencilRetained") >= .5)
        assertTrue(
            "WORKBOOK shadow paper stayed dark",
            workbook.getDouble("shadowPaperTone") >= 210,
        )
        assertTrue("TEXT paper too dark", text.getDouble("paperTone") >= 210)
    }
}
