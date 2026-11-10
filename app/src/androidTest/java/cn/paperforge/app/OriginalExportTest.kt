package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import cn.paperforge.app.pdf.OriginalPhoto
import cn.paperforge.app.pdf.PdfEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An Original page that is still the camera's photo is exported as the camera saved it, and a cropped Original page
 * keeps the photo's own resolution rather than the export preset's (2572 px for STANDARD).
 */
@RunWith(AndroidJUnit4::class)
class OriginalExportTest {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    /** A 4000 x 3000 JPEG with fine detail, like a 12-megapixel capture. */
    private fun photo(): File {
        val file = File(context.cacheDir, "original-export-test.jpg")
        val bitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = android.graphics.Paint().apply { color = Color.BLACK; strokeWidth = 1f }
        for (x in 0 until 4000 step 3) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 3000f, paint)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it)) }
        bitmap.recycle()
        return file
    }

    private fun page(file: File, quad: Quad) =
        PageRow(id = "original-export", documentId = "t", position = 0, original = file.path, corners = quad.encode(), filter = "ORIGINAL")

    @Test
    fun anUntouchedOriginalPageIsTheCamerasJpeg() = runBlocking {
        val file = photo()
        val page = page(file, Quad.Full)
        assertTrue("an unedited full-frame Original page counts as untouched", OriginalPhoto.untouched(page))
        val pdf = File(context.cacheDir, "original-export.pdf")
        PdfEngine.scan(listOf(page), pdf, resolution = "STANDARD")
        PDFBoxResourceLoader.init(context)
        PDDocument.load(pdf).use { doc ->
            val res = doc.getPage(0).resources
            val image = res.xObjectNames.map { res.getXObject(it) }.filterIsInstance<PDImageXObject>().single()
            assertEquals("full width, not the 2572-px preset", 4000, image.width)
            assertEquals(3000, image.height)
            val embedded = image.cosObject.createRawInputStream().use { it.readBytes() }
            assertEquals(COSName.DCT_DECODE, image.cosObject.filters)
            assertArrayEquals("the camera's own JPEG bytes, not a second encoding", file.readBytes(), embedded)
        }
    }

    @Test
    fun aCroppedOriginalPageKeepsThePhotosResolution() = runBlocking {
        val file = photo()
        val quad = Quad.ordered(listOf(PointN(.1f, .1f), PointN(.9f, .1f), PointN(.9f, .9f), PointN(.1f, .9f)))
        val page = page(file, quad)
        val pdf = File(context.cacheDir, "original-export-crop.pdf")
        PdfEngine.scan(listOf(page), pdf, resolution = "STANDARD")
        PDFBoxResourceLoader.init(context)
        PDDocument.load(pdf).use { doc ->
            val res = doc.getPage(0).resources
            val image = res.xObjectNames.map { res.getXObject(it) }.filterIsInstance<PDImageXObject>().single()
            // 80% of 4000 x 3000: about 3200 x 2400, well over the preset's 2572-px long edge.
            assertTrue("crop kept at the photo's resolution, was ${image.width}", image.width >= 3100)
        }
    }
}
