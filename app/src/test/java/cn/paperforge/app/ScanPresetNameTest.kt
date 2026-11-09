package cn.paperforge.app

import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.pdf.PdfImageEnhancement
import cn.paperforge.app.ui.scanFilterName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #22: the plain-language preset names are shared by the editor chips, the default-filter setting,
 * the PDF image-enhancement dialog and the offline guide. Pinning the map keeps them from drifting.
 */
class ScanPresetNameTest {
    @Test
    fun editorLabelsMatchTheAgreedNames() {
        val expected =
            mapOf(
                "ORIGINAL" to "原图",
                "WORKBOOK" to "练习册",
                "CLEAN" to "强力去背景",
                "GRAY" to "灰度",
            )
        assertEquals(expected.keys, ScanProcessor.filters.toSet())
        expected.forEach { (filter, label) -> assertEquals(label, scanFilterName(filter)) }
    }

    @Test
    fun pdfEnhancementOffersTheSamePresets() {
        val presets = PdfImageEnhancement.presets
        // PDF enhancement keeps the broader photo set (自然/柔和黑白) that the editor no longer shows.
        assertEquals(listOf("AUTO", "WORKBOOK", "BW", "CLEAN", "GRAY"), presets.keys.toList())
        assertEquals("自然", presets["AUTO"])
        assertEquals("练习册", presets["WORKBOOK"])
        assertEquals("柔和黑白", presets["BW"])
        assertEquals("强力去背景", presets["CLEAN"])
        assertEquals("灰度", presets["GRAY"])
    }
}
