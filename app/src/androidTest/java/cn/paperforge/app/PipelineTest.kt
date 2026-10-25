package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.BundledOcrEngine
import cn.paperforge.app.pdf.PdfEngine
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private fun fixture(file: File, index: Int = 1): Bitmap {
        val b = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        canvas.drawColor(Color.rgb(55, 75, 65))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRect(100f, 100f, 1100f, 1500f, paint)
        paint.color = Color.BLACK
        paint.textSize = 64f
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("PAPERFORGE", 160f, 270f, paint)
        paint.textSize = 42f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText("Offline scanner test $index", 160f, 360f, paint)
        canvas.drawText("纸匠离线扫描测试", 160f, 460f, paint)
        paint.textSize = 32f
        for (n in 0..12) canvas.drawText(
            "Page $index / Line $n : 0123456789 ABCDEFG",
            160f,
            580f + n * 56,
            paint,
        )
        file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        return b
    }

    @Test
    fun detectAndPerspectiveAndAllFilters() {
        val file = File(app.cacheDir, "synthetic.jpg")
        val original = fixture(file)
        try {
            val q = ScanProcessor.detect(original) ?: error("Synthetic paper not detected")
            assertEquals(.083f, q.points[0].x, .035f)
            assertEquals(.0625f, q.points[0].y, .035f)
            assertTrue(q.valid())
            ScanProcessor.filters.forEach { f ->
                val result = ScanProcessor.transform(original, q, f)
                try {
                    assertTrue(result.width > 900)
                    assertTrue(result.height > 1300)
                    assertTrue(Color.red(result.getPixel(20, 20)) > 100)
                } finally {
                    result.recycle()
                }
            }
        } finally {
            original.recycle()
        }
    }

    @Test
    fun tenPageDraftSurvivesReloadAndPdfRoundTrip() = runBlocking {
        val repo = app.repository
        val doc = repo.newScan()
        repo.rename(doc, "验收样本 · 10 页扫描")
        repeat(10) { i ->
            val file = repo.captureFile(doc.id)
            fixture(file, i + 1).recycle()
            repo.addCapture(doc.id, file)
        }
        val pages = repo.dao.pages(doc.id)
        assertEquals(10, pages.size)
        repo.reorder(doc.id, pages.reversed())
        assertEquals(pages.last().id, repo.dao.pages(doc.id).first().id)
        val edited = repo.dao.pages(doc.id).first().copy(rotation = 90, filter = "GRAY")
        repo.savePage(edited)
        assertEquals(90, repo.dao.pages(doc.id).first().rotation)
        val output = File(repo.exports, "acceptance-ten-pages.pdf")
        PdfEngine.scan(repo.dao.pages(doc.id), output, paper = "A4")
        PDDocument.load(output).use { pdf ->
            assertEquals(10, pdf.numberOfPages)
            assertEquals(PDRectangle.A4.width, pdf.getPage(0).mediaBox.width, .01f)
        }
        val extraction = File(repo.exports, "acceptance-selected.pdf")
        PdfEngine.extract(output, "10,1-2", extraction, 90)
        PDDocument.load(extraction).use {
            assertEquals(3, it.numberOfPages)
            assertEquals(90, it.getPage(0).rotation)
        }
        val merged = File(repo.exports, "acceptance-merged.pdf")
        PdfEngine.merge(listOf(output, extraction), merged)
        PDDocument.load(merged).use { assertEquals(13, it.numberOfPages) }
        val before = repo.dao.pdfReferences(output.path)
        val first = repo.registerPdf(output, "验收样本 · 多页 PDF")
        val second = repo.registerPdf(output, "再次预览同一份 PDF")
        assertEquals(first.id, second.id)
        assertEquals(if (before == 0) 1 else before, repo.dao.pdfReferences(output.path))
        Unit
    }

    @Test
    fun pdfPageToolsPreserveNativeText() = runBlocking {
        val source = File(app.cacheDir, "vector.pdf")
        PDDocument().use { pdf ->
            repeat(3) { i ->
                val p = PDPage(PDRectangle.A4)
                pdf.addPage(p)
                PDPageContentStream(pdf, p).use { c ->
                    c.beginText()
                    c.setFont(PDType1Font.HELVETICA, 20f)
                    c.newLineAtOffset(40f, 700f)
                    c.showText("NATIVE TEXT PAGE ${i+1}")
                    c.endText()
                }
            }
            pdf.save(source)
        }
        val output = File(app.cacheDir, "vector-selected.pdf")
        PdfEngine.extract(source, "3,1", output, 90)
        PDDocument.load(output).use { doc ->
            val text = PdfEngine.text(output)
            assertTrue("Extracted text for page 3: $text", text.contains("NATIVE TEXT PAGE 3"))
            assertTrue("Extracted text for page 1: $text", text.contains("NATIVE TEXT PAGE 1"))
            assertFalse(text.contains("NATIVE TEXT PAGE 2"))
            assertEquals(90, doc.getPage(0).rotation)
        }
        val bad = File(app.cacheDir, "bad-output.pdf")
        assertTrue(runCatching { PdfEngine.extract(source, "999", bad) }.isFailure)
        assertFalse(bad.exists())
        assertFalse(File(bad.path + ".part").exists())
        assertTrue(source.exists())
    }

    @Test
    fun bundledOcrReadsEnglishAndChineseWithoutNetworkPermission() = runBlocking {
        @Suppress("DEPRECATION")
        val permissions =
            app.packageManager
                .getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
        val file = File(app.cacheDir, "ocr.jpg")
        fixture(file).recycle()
        val page = PageRow("test", "test", 0, file.path, filter = "ORIGINAL")
        val chinese = BundledOcrEngine().recognize(page, true)
        assertTrue("Chinese OCR output: $chinese", chinese.contains("纸匠"))
        val latin = BundledOcrEngine().recognize(page, false)
        assertTrue("Latin OCR output: $latin", latin.contains("PAPERFORGE"))
    }

    @Test
    fun cancelledRecognitionCanBeRetried() = runBlocking {
        val file = File(app.cacheDir, "cancel-ocr.jpg")
        fixture(file).recycle()
        val page = PageRow("cancel", "cancel", 0, file.path, filter = "ORIGINAL")
        val pending =
            launch(start = CoroutineStart.UNDISPATCHED) { BundledOcrEngine().recognize(page, true) }
        pending.cancelAndJoin()
        assertTrue(BundledOcrEngine().recognize(page, true).contains("纸匠"))
    }
}
