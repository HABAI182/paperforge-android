package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.*
import cn.paperforge.app.pdf.PdfEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** Actual OpenCV output, known ink masks and a lossless PDF round trip, not a desktop replica. */
@RunWith(AndroidJUnit4::class)
class SoftMonochromeTest {
    private val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
    private val directory = File(app.getExternalFilesDir(null), "issue12").apply { mkdirs() }

    private fun process(bitmap: Bitmap, mode: String, legacy: Boolean = false): Bitmap {
        val rgba = Mat()
        val rgb = Mat()
        val out = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            if (legacy) LegacyEnhancePipeline.apply(rgb, out, mode)
            else EnhancePipeline.apply(rgb, out, mode)
            return Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(out, it)
            }
        } finally {
            rgba.release()
            rgb.release()
            out.release()
        }
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun finePrintPencilRulesSolidInkAndOtherFilters() = runBlocking {
        val w = 900
        val h = 1200
        val masks = linkedMapOf<String, Bitmap>()
        fun layer(name: String, draw: (Canvas, Paint) -> Unit) {
            masks[name] =
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).apply {
                        drawColor(Color.WHITE)
                        draw(this, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
                    }
                }
        }
        layer("fine-print") { c, p ->
            p.textSize = 18f
            repeat(7) {
                c.drawText("Fine print 0123456789 .,;:!? () [ ] / + =", 70f, 90f + it * 30, p)
            }
        }
        layer("pencil") { c, p ->
            p.textSize = 26f
            repeat(6) { c.drawText("Pencil light strokes 0123456789 .,", 70f, 380f + it * 38, p) }
        }
        layer("table-rules") { c, p ->
            p.strokeWidth = 1f
            repeat(7) { c.drawLine(70f, 670f + it * 38, 800f, 670f + it * 38, p) }
            repeat(6) { c.drawLine(70f + it * 146, 670f, 70f + it * 146, 898f, p) }
        }
        layer("solid-ink") { c, p -> c.drawRect(70f, 990f, 410f, 1110f, p) }
        val maskPixels =
            masks.mapValues { (_, b) -> IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) } }
        val metrics = JSONArray()
        var sourceForExport: Bitmap? = null
        try {
            for (scene in listOf("plain", "shadow", "noise")) {
                val pixels =
                    IntArray(w * h) { index ->
                        val x = index % w
                        val illumination = if (scene == "shadow") .48 + .52 * x / w else 1.0
                        val noise = if (scene == "noise") ((index * 31L % 7).toInt() - 3) else 0
                        var v = 240.0
                        for ((name, data) in maskPixels) {
                            val alpha = (255 - Color.red(data[index])) / 255.0
                            val ink =
                                if (name == "pencil") 209
                                else if (name == "table-rules") 170 else 22
                            v = v * (1 - alpha) + ink * alpha
                        }
                        val gray = (v * illumination + noise).toInt().coerceIn(0, 255)
                        Color.rgb(gray, gray, gray)
                    }
                val image = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                val before = process(image, "BW", true)
                val after = process(image, "BW")
                try {
                    save(image, "$scene-source")
                    save(before, "$scene-before")
                    save(after, "$scene-after")
                    for ((name, data) in maskPixels) {
                        val ink =
                            data.indices.filter {
                                Color.red(data[it]) < (if (name == "table-rules") 200 else 65)
                            }
                        fun retained(b: Bitmap) =
                            ink.count { Color.red(b.getPixel(it % w, it / w)) < 128 }.toDouble() /
                                ink.size
                        val old = retained(before)
                        val current = retained(after)
                        metrics.put(
                            JSONObject()
                                .put("scene", scene)
                                .put("feature", name)
                                .put("before", old)
                                .put("after", current)
                        )
                        assertTrue("$scene/$name retention=$current", current > .93)
                        assertTrue("$scene/$name regressed", current + .01 >= old)
                        if (name == "pencil")
                            assertTrue("Pencil should improve over old=$old", current > old + .25)
                    }
                    val background =
                        (0 until w * h step 17).filter { i ->
                            maskPixels.values.all { Color.red(it[i]) == 255 }
                        }
                    assertTrue(
                        "Background noise",
                        background
                            .count { Color.red(after.getPixel(it % w, it / w)) < 128 }
                            .toDouble() / background.size < .001,
                    )
                    if (scene == "plain") {
                        for (filter in ScanProcessor.filters.filter { it != "BW" }) {
                            val a = process(image, filter, true)
                            val b = process(image, filter)
                            try {
                                assertTrue("$filter changed", a.sameAs(b))
                            } finally {
                                a.recycle()
                                b.recycle()
                            }
                        }
                        sourceForExport = image.copy(Bitmap.Config.ARGB_8888, false)
                    }
                } finally {
                    image.recycle()
                    before.recycle()
                    after.recycle()
                }
            }
            val source = File(directory, "roundtrip.png")
            source.outputStream().use {
                sourceForExport!!.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val page = PageRow("soft-bw", "soft-bw", 0, source.path, filter = "BW")
            val expected = ScanProcessor.render(page, ScanConfig.edge("STANDARD"))
            val output = File(directory, "lossless-export.pdf")
            try {
                PdfEngine.scan(
                    listOf(page, page.copy(id = "soft-bw-2", position = 1)),
                    output,
                    lossless = true,
                )
                PDDocument.load(output).use { doc ->
                    assertEquals(2, doc.numberOfPages)
                    repeat(2) { i ->
                        val r = doc.getPage(i).resources
                        val image =
                            r.xObjectNames
                                .map { r.getXObject(it) }
                                .filterIsInstance<PDImageXObject>()
                                .single()
                                .image
                        try {
                            assertTrue("Preview/batch/export differ", expected.sameAs(image))
                        } finally {
                            image.recycle()
                        }
                    }
                }
            } finally {
                expected.recycle()
            }
        } finally {
            sourceForExport?.recycle()
            masks.values.forEach { it.recycle() }
            File(directory, "metrics.json").writeText(metrics.toString(2))
        }
    }
}
