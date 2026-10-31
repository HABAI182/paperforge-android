package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.pdf.PdfWatermarks
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #28: watermark removal measured on the owner's real CamScanner exports. The samples live on the
 * device (files/samples), never in the repository, because they contain private documents.
 */
@RunWith(AndroidJUnit4::class)
class PdfWatermarkSampleTest {
    private val regionLeft = .70f
    private val regionTop = .93f

    private fun render(file: File, scale: Float = 2f): Bitmap {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap =
                        Bitmap.createBitmap(
                            (page.width * scale).toInt(),
                            (page.height * scale).toInt(),
                            Bitmap.Config.ARGB_8888,
                        )
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    return bitmap
                }
            }
        }
    }

    private fun darkFraction(bitmap: Bitmap): Double {
        var dark = 0
        var total = 0
        val x0 = (bitmap.width * regionLeft).toInt()
        val y0 = (bitmap.height * regionTop).toInt()
        for (y in y0 until bitmap.height) {
            for (x in x0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val luma = .299 * Color.red(pixel) + .587 * Color.green(pixel) + .114 * Color.blue(pixel)
                if (luma < 200) dark++
                total++
            }
        }
        return if (total == 0) 0.0 else dark.toDouble() / total
    }

    private fun outsideDiff(before: Bitmap, after: Bitmap): Double {
        var sum = 0.0
        var count = 0
        val yLimit = (before.height * regionTop).toInt()
        for (y in 0 until yLimit) {
            for (x in 0 until before.width) {
                val a = before.getPixel(x, y)
                val b = after.getPixel(x, y)
                sum +=
                    (kotlin.math.abs(Color.red(a) - Color.red(b)) +
                        kotlin.math.abs(Color.green(a) - Color.green(b)) +
                        kotlin.math.abs(Color.blue(a) - Color.blue(b))) / 3.0
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }

    @Test
    fun removesSampleWatermarksWithoutTouchingContent() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val samples = File(app.filesDir, "samples")
        val pdfs = samples.listFiles { f -> f.extension.lowercase() == "pdf" }?.sortedBy { it.name }
            ?: error("缺少设备端样本目录")
        assertTrue("没有样本", !pdfs.isNullOrEmpty())
        val output = File(app.filesDir, "watermark-proof").apply { mkdirs() }
        val rows = JSONArray()
        for (source in pdfs) {
            val cleaned = File(output, "clean-" + source.name)
            if (cleaned.exists()) cleaned.delete()
            val report = PdfWatermarks.remove(source, cleaned)
            val before = render(source)
            val after = render(cleaned)
            try {
                val beforeDark = darkFraction(before)
                val afterDark = darkFraction(after)
                val untouched = outsideDiff(before, after)
                rows.put(
                    JSONObject()
                        .put("file", source.name)
                        .put("imagesRemoved", report.imagesRemoved)
                        .put("textCovers", report.textCovers)
                        .put("pages", report.pages)
                        .put("cornerDarkBefore", beforeDark)
                        .put("cornerDarkAfter", afterDark)
                        .put("untouchedMeanDiff", untouched)
                )
                File(output, source.name.replace(".pdf", "-before.png")).outputStream().use {
                    before.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                File(output, source.name.replace(".pdf", "-after.png")).outputStream().use {
                    after.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                assertTrue("${source.name}: 水印没有被移除 ($beforeDark -> $afterDark)",
                    afterDark <= beforeDark * .2 || afterDark < .005)
                assertTrue("${source.name}: 正文区域被改动 $untouched", untouched < 1.0)
            } finally {
                before.recycle()
                after.recycle()
            }
        }
        File(output, "metrics.json").writeText(
            JSONObject().put("synthetic", false).put("ownerSamples", true).put("rows", rows).toString(2)
        )
    }
}
