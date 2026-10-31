package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.Quad
import java.io.File
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #22 workbook acceptance. The scene is synthetic: it reproduces the failure mode (reverse-side
 * bleed through thin paper, uneven lamp light, grey base) without any user document. Real workbook
 * photos are still needed for the published acceptance note; this test only proves the algorithm.
 */
@RunWith(AndroidJUnit4::class)
class WorkbookEnhancementTest {
    private class Scene(val w: Int = 1200, val h: Int = 1600) {
        val pixels = IntArray(w * h)
        val ink = BooleanArray(w * h)
        val pencil = BooleanArray(w * h)
        val rule = BooleanArray(w * h)
        val ghost = BooleanArray(w * h)
        val paper = BooleanArray(w * h)
        private val illum = FloatArray(w * h)

        fun build(): Scene {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    // Uneven lamp light: bright on the left, vignette towards the bottom right.
                    val horizontal = 0.70f + 0.30f * x / (w - 1)
                    val vignette = 0.92f + 0.08f * (1f - y.toFloat() / h)
                    val noise = 1f + 0.012f * sin((x * 1.7 + y * 0.9).toDouble()).toFloat()
                    illum[y * w + x] = horizontal * vignette * noise
                    val base = (232f * illum[y * w + x]).toInt().coerceIn(0, 255)
                    pixels[y * w + x] = Color.rgb(base, base, (base + 3).coerceAtMost(255))
                }
            }
            // Printed workbook text: character-like blocks of thin horizontal strokes.
            for (row in 0 until 8) {
                val top = 120 + row * 170
                for (line in 0 until 3) {
                    val y = top + line * 14
                    var x = 110
                    while (x < w - 150) {
                        val width = if ((x / 110) % 5 == 0) 26 else 16
                        for (dx in 0 until width) {
                            for (dy in 0 until 3) {
                                val px = x + dx
                                val py = y + dy
                                if (px !in 0 until w || py !in 0 until h) continue
                                ink[py * w + px] = true
                                setInk(px, py, 40f)
                            }
                        }
                        x += width + 22
                    }
                }
            }
            // Pencil answers: lighter and thinner than print.
            for (line in 0 until 6) {
                val y = 300 + line * 190
                for (x in 160 until w - 220) {
                    if (x % 7 == 0) continue
                    for (dy in -1..1) {
                        val py = y + dy
                        if (py !in 0 until h) continue
                        pencil[py * w + x] = true
                        setInk(x, py, 150f)
                    }
                }
            }
            // Printed table rules.
            for (y in listOf(1350, 1400, 1450)) {
                for (x in 120 until w - 120) {
                    for (dy in -1..1) {
                        rule[(y + dy) * w + x] = true
                        setInk(x, y + dy, 118f)
                    }
                }
            }
            for (x in listOf(120, 400, 700, w - 121)) {
                for (y in 1340 until 1455) {
                    for (dx in -1..1) {
                        rule[y * w + x + dx] = true
                        setInk(x + dx, y, 118f)
                    }
                }
            }
            showThrough()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    paper[i] = !ink[i] && !pencil[i] && !rule[i] && !ghost[i]
                }
            }
            return this
        }

        private fun setInk(x: Int, y: Int, value: Float) {
            val v = (value * illum[y * w + x]).toInt().coerceIn(0, 255)
            pixels[y * w + x] = Color.rgb(v, v, v)
        }

        /** Mirrored, blurred copy of the printed side bleeding through the sheet. */
        private fun showThrough() {
            val source = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (ink[y * w + x]) source[y * w + (w - 1 - x)] = 1f
                }
            }
            val blurred = boxBlur(boxBlur(source, w, h, 5), w, h, 5)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val strength = blurred[y * w + x]
                    if (strength < .10f) continue
                    val i = y * w + x
                    if (ink[i] || pencil[i] || rule[i]) continue
                    val darken = 34f * min(1f, strength * 1.6f) * illum[i]
                    val c = pixels[i]
                    val dim = { channel: Int ->
                        (channel - darken.toInt()).coerceIn(0, 255)
                    }
                    pixels[i] = Color.rgb(dim(Color.red(c)), dim(Color.green(c)), dim(Color.blue(c)))
                    ghost[i] = true
                }
            }
        }

        private fun boxBlur(input: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
            val tmp = FloatArray(input.size)
            val out = FloatArray(input.size)
            val span = 2 * radius + 1
            for (y in 0 until h) {
                var sum = 0f
                for (x in -radius..radius) sum += input[y * w + x.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[y * w + x] = sum / span
                    sum += input[y * w + (x + radius + 1).coerceIn(0, w - 1)]
                    sum -= input[y * w + (x - radius).coerceIn(0, w - 1)]
                }
            }
            for (x in 0 until w) {
                var sum = 0f
                for (y in -radius..radius) sum += tmp[y.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    out[y * w + x] = sum / span
                    sum += tmp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                    sum -= tmp[(y - radius).coerceIn(0, h - 1) * w + x]
                }
            }
            return out
        }

        fun bitmap(): Bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)

        fun mean(bitmap: Bitmap, mask: BooleanArray): Double {
            var sum = 0.0
            var count = 0
            for (y in 0 until h) {
                val by = y * bitmap.height / h
                for (x in 0 until w) {
                    if (!mask[y * w + x]) continue
                    val pixel = bitmap.getPixel(x * bitmap.width / w, by)
                    sum += .299 * Color.red(pixel) + .587 * Color.green(pixel) + .114 * Color.blue(pixel)
                    count++
                }
            }
            return if (count == 0) 0.0 else sum / count
        }

        /**
         * Thin strokes are a few pixels wide and the warped output is one pixel shorter than the
         * source, so a plain mean can pick up blank paper along the edge. A low percentile reads the
         * stroke tone itself.
         */
        fun percentile(bitmap: Bitmap, mask: BooleanArray, fraction: Double): Double {
            val values = ArrayList<Double>()
            for (y in 0 until h) {
                val by = y * bitmap.height / h
                for (x in 0 until w) {
                    if (!mask[y * w + x]) continue
                    val pixel = bitmap.getPixel(x * bitmap.width / w, by)
                    values.add(
                        .299 * Color.red(pixel) + .587 * Color.green(pixel) + .114 * Color.blue(pixel)
                    )
                }
            }
            if (values.isEmpty()) return 0.0
            values.sort()
            return values[((values.size - 1) * fraction).toInt().coerceIn(0, values.size - 1)]
        }
    }

    @Test
    fun workbookPresetSuppressesShowThroughAndKeepsInk() {
        val scene = Scene().build()
        val source = scene.bitmap()
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val output = File(app.filesDir, "workbook-quality").apply { mkdirs() }
        val rows = JSONArray()
        fun record(mode: String, bitmap: Bitmap): JSONObject =
            JSONObject()
                .put("mode", mode)
                .put("ghost", scene.mean(bitmap, scene.ghost))
                .put("ink", scene.percentile(bitmap, scene.ink, .35))
                .put("pencil", scene.percentile(bitmap, scene.pencil, .35))
                .put("rules", scene.percentile(bitmap, scene.rule, .35))
                .put("paper", scene.mean(bitmap, scene.paper))
                .put("ink_mean", scene.mean(bitmap, scene.ink))

        try {
            File(output, "workbook-source.png").outputStream().use {
                source.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(output, "metrics.json").writeText(
                JSONObject()
                    .put("scene", "synthetic-thin-paper")
                    .put("synthetic", true)
                    .put("physical_device", false)
                    .put("source", record("ORIGINAL", source))
                    .toString(2)
            )
            val auto = ScanProcessor.transform(source, Quad.Full, "AUTO")
            val workbook = ScanProcessor.transform(source, Quad.Full, "WORKBOOK")
            try {
                for ((mode, bitmap) in listOf("AUTO" to auto, "WORKBOOK" to workbook)) {
                    rows.put(record(mode, bitmap))
                    File(output, "workbook-${mode.lowercase()}.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                val autoGhost = scene.mean(auto, scene.ghost)
                val metrics =
                    JSONObject()
                        .put("scene", "synthetic-thin-paper")
                        .put("synthetic", true)
                        .put("physical_device", false)
                        .put("source", record("ORIGINAL", source))
                        .put("results", rows)
                File(output, "metrics.json").writeText(metrics.toString(2))
                assertTrue(
                    "workbook kept show-through: ${scene.mean(workbook, scene.ghost)}",
                    scene.mean(workbook, scene.ghost) >= 230.0,
                )
                assertTrue(
                    "workbook did not beat AUTO on show-through",
                    scene.mean(workbook, scene.ghost) >= autoGhost + 10.0,
                )
                assertTrue(
                    "workbook erased print: ${scene.percentile(workbook, scene.ink, .35)}",
                    scene.percentile(workbook, scene.ink, .35) <= 90.0,
                )
                val pencil = scene.percentile(workbook, scene.pencil, .35)
                assertTrue("pencil erased or lost tone: $pencil", pencil in 120.0..238.0)
                assertTrue("table rules lost: ${scene.percentile(workbook, scene.rule, .35)}",
                    scene.percentile(workbook, scene.rule, .35) <= 205.0)
                assertTrue("paper not white: ${scene.mean(workbook, scene.paper)}",
                    scene.mean(workbook, scene.paper) >= 235.0)
            } finally {
                auto.recycle()
                workbook.recycle()
            }
        } finally {
            source.recycle()
        }
    }
}
