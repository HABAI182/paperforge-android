package cn.paperforge.app

import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.ui.PdfSession
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PdfTilingTest {
    @Test
    fun translatedTilesMatchFullRenderingAndNeverAllocateWholeZoomedPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "tile-reference.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA_BOLD, 24f)
                stream.newLineAtOffset(50f, 700f)
                stream.showText("TILE ALIGNMENT 00123")
                stream.endText()
            }
            doc.save(file)
        }
        try {
            PdfSession(file).use { session ->
                val full = session.render(0, 3200)
                val tile = session.tile(0, 3200, 0, 1)
                var darkFull = 0
                var darkTile = 0
                var difference = 0L
                var samples = 0
                for (y in 0 until tile.bitmap.height step 3) for (x in
                    0 until tile.bitmap.width step 3) {
                    val a = full.getPixel(tile.left + x, tile.top + y) and 255
                    val b = tile.bitmap.getPixel(x, y) and 255
                    if (a < 100) darkFull++
                    if (b < 100) darkTile++
                    difference += kotlin.math.abs(a - b)
                    samples++
                }
                assertTrue("Tile must contain the expected text", darkFull > 100 && darkTile > 100)
                assertTrue(
                    "Translated glyph coverage differs",
                    darkTile.toFloat() / darkFull in .8f..1.2f,
                )
                assertTrue(
                    "Tile matrix differs from full-page position",
                    difference.toDouble() / samples < 5,
                )
                val large = session.tile(0, 9600, 2, 3)
                assertTrue(large.virtualHeight >= 9600)
                assertTrue(large.bitmap.width <= 512 && large.bitmap.height <= 512)
                assertTrue(session.cachedBytes() <= 40 * 1024 * 1024)
            }
        } finally {
            file.delete()
        }
    }
}
