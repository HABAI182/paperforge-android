package cn.paperforge.app

import cn.paperforge.app.pdf.PdfImageEncoder
import cn.paperforge.app.pdf.PdfImageSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Migration of stored export resolutions, the JPEG-quality estimate, and the tone levels of grey document pages. */
class PdfImageSettingsTest {
    @Test
    fun oldResolutionNamesKeepTheirPixelSize() {
        assertEquals("ORIGINAL", PdfImageSettings.normalize(null))
        assertEquals("SMALL", PdfImageSettings.normalize("DRAFT"))
        assertEquals("MEDIUM", PdfImageSettings.normalize("STANDARD"))
        assertEquals("HIGH", PdfImageSettings.normalize("HIGH"))
        // A custom density is the user's own choice: kept as it is.
        assertEquals("CUSTOM", PdfImageSettings.normalize("CUSTOM"))
        assertEquals("DPI:200", PdfImageSettings.normalize("DPI:200"))
        assertEquals("ORIGINAL", PdfImageSettings.normalize("UNKNOWN"))
        PdfImageSettings.resolutions.forEach { assertEquals(it, PdfImageSettings.normalize(it)) }
        assertEquals(PdfImageSettings.edgeAt(220), PdfImageSettings.PAGE_EDGE)
    }

    // Standard luminance table (Annex K), zig-zag order, as libjpeg scales it for a quality.
    private val luma = intArrayOf(
        16, 11, 12, 14, 12, 10, 16, 14, 13, 14, 18, 17, 16, 19, 24, 40, 26, 24, 22, 22, 24, 49, 35, 37, 29, 40, 58, 51, 61, 60, 57, 51,
        56, 55, 64, 72, 92, 78, 64, 68, 87, 69, 55, 56, 80, 109, 81, 87, 95, 98, 103, 104, 103, 62, 77, 113, 121, 112, 100, 120, 92, 101, 103, 99,
    )

    /** The start of a JPEG file as libjpeg writes it at [quality]: SOI, APP0, a DQT for table 0, then SOS. */
    private fun header(quality: Int): ByteArray {
        val scale = if (quality < 50) 5000 / quality else 200 - quality * 2
        val table = luma.map { ((it * scale + 50) / 100).coerceIn(1, 255).toByte() }
        val app0 = byteArrayOf(0xff.toByte(), 0xe0.toByte(), 0, 16, 74, 70, 73, 70, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0)
        val dqt = byteArrayOf(0xff.toByte(), 0xdb.toByte(), 0, 67, 0) + table.toByteArray()
        return byteArrayOf(0xff.toByte(), 0xd8.toByte()) + app0 + dqt + byteArrayOf(0xff.toByte(), 0xda.toByte(), 0, 2)
    }

    @Test
    fun jpegQualityIsReadFromTheQuantisationTable() {
        for (q in listOf(30, 50, 75, 86, 90, 95, 97)) {
            val estimate = PdfImageSettings.jpegQuality(header(q))
            assertTrue("quality $q estimated as $estimate", estimate != null && kotlin.math.abs(estimate - q) <= 2)
        }
        assertEquals(null, PdfImageSettings.jpegQuality(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun lowerQualityKeepsFewerToneLevelsOnDocumentPages() {
        val levels = listOf(100, 95, 90, 80, 70, 60, 50, 40, 30).map { PdfImageEncoder.toneLevels(it) }
        assertEquals(256, levels[0])
        assertEquals(256, levels[1])
        for (i in 1 until levels.size) assertTrue("non-increasing $levels", levels[i] <= levels[i - 1])
        assertTrue(levels.last() >= 8)
    }
}
