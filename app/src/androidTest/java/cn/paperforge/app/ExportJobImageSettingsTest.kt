package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import cn.paperforge.app.data.DocumentJobs
import cn.paperforge.app.data.JobInput
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.Quad
import cn.paperforge.app.pdf.PdfEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image settings reach the PDF through the real export job (DocumentJobs → ExportWorker → ScanPageStages →
 * PdfEngine), not only through PdfEngine directly: the job path once rewrote every resolution to "DPI:n", which
 * turned 原始 into a 300 dpi cap.
 */
class ExportJobImageSettingsTest {
    private val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private data class Image(val filter: String, val width: Int, val height: Int)

    private fun image(pdf: File): Image {
        PDFBoxResourceLoader.init(app)
        return PDDocument.load(pdf).use { doc ->
            val res = doc.getPage(0).resources
            val image = res.xObjectNames.map { res.getXObject(it) }.filterIsInstance<PDImageXObject>().single()
            Image((image.cosObject.filters as COSName).name, image.width, image.height)
        }
    }

    @Test
    fun resolutionQualityAndLosslessReachTheExportedFile() = runBlocking {
        val repo = app.repository
        val doc = repo.newScan()
        repo.rename(doc, "图像设置任务验收")
        val source = File(repo.folder(doc.id), "job-settings-source.jpg")
        // 27 MP, larger than any cap the export ever had (2572 standard, ~5490 heap-based): 原始 must keep all of it.
        Bitmap.createBitmap(4500, 6000, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(Color.rgb(236, 233, 226))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 34); textSize = 40f }
            val canvas = Canvas(bitmap)
            for (row in 0 until 75) canvas.drawText("第 $row 行 export settings ∫ f(x) dx 0.123456789", 150f, 200f + row * 76f, paint)
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            bitmap.recycle()
        }
        val page = PageRow(UUID.randomUUID().toString(), doc.id, 0, source.path, corners = Quad.Full.encode(), filter = "GRAY")
        repo.dao.putPage(page)

        suspend fun export(resolution: String, quality: Int, lossless: Boolean): File {
            val work =
                DocumentJobs.enqueue(
                    repo,
                    "SCAN_EXPORT",
                    JobInput.from(
                        workDataOf(
                            "document" to doc.id,
                            "title" to "图像设置任务验收",
                            "paper" to "Auto",
                            "quality" to quality,
                            "lossless" to lossless,
                            "searchable" to false,
                            "format" to "PDF",
                            "resolution" to resolution,
                            "fileName" to "job-$resolution-$quality-$lossless",
                        )
                    ),
                )
            val info =
                withTimeout(180000) {
                    WorkManager.getInstance(app).getWorkInfoByIdFlow(work).filterNotNull().first { it.state.isFinished }
                }
            assertEquals(info.outputData.getString("error"), WorkInfo.State.SUCCEEDED, info.state)
            return File(info.outputData.getString("path")!!)
        }

        // The page's own pixels, as PdfEngine gives them directly.
        val direct = File(app.cacheDir, "job-settings-direct.pdf")
        PdfEngine.scan(listOf(repo.dao.pages(doc.id).single()), direct, quality = 85, context = app, resolution = "ORIGINAL")
        val own = image(direct)
        assertEquals("原始 is the source's own pixels", 4500, own.width)
        assertEquals(6000, own.height)
        // A cropped page of the same 27 MP photo goes through the region decoder: also never sampled down.
        val cropped = File(app.cacheDir, "job-settings-cropped.pdf")
        val inset = Quad.ordered(listOf(cn.paperforge.app.model.PointN(.05f, .05f), cn.paperforge.app.model.PointN(.95f, .05f), cn.paperforge.app.model.PointN(.95f, .95f), cn.paperforge.app.model.PointN(.05f, .95f)))
        PdfEngine.scan(listOf(page.copy(corners = inset.encode())), cropped, quality = 85, context = app, resolution = "ORIGINAL")
        val crop = image(cropped)
        assertTrue("cropped 原始 keeps ~90% of 4500 px: ${crop.width}x${crop.height}", crop.width in 3950..4150 && crop.height in 5300..5500)

        val original = image(export("ORIGINAL", 85, false))
        assertEquals("原始 keeps the page's own width through the job", own.width, original.width)
        assertEquals(own.height, original.height)
        assertEquals("DCTDecode", original.filter)

        val high = image(export("HIGH", 85, false))
        assertTrue("高 caps at 3508: ${high.height}", maxOf(high.width, high.height) <= 3509)
        val small = image(export("SMALL", 85, false))
        assertTrue("小 caps at 1754: ${small.height}", maxOf(small.width, small.height) <= 1755)
        // Legacy name still works through the job.
        val standard = image(export("STANDARD", 85, false))
        assertTrue("标准 = 中 caps at 2572: ${standard.height}", maxOf(standard.width, standard.height) <= 2573)

        val q30 = export("ORIGINAL", 30, false)
        val q95 = export("ORIGINAL", 95, false)
        assertTrue("quality changes the file: ${q30.length()} < ${q95.length()}", q30.length() < q95.length())
        assertEquals(own.width, image(q30).width)

        val lossless = image(export("SMALL", 30, true))
        assertEquals("无损保存 is Flate", "FlateDecode", lossless.filter)
        assertEquals("无损保存 keeps the page's own pixels", own.width, lossless.width)
    }
}
