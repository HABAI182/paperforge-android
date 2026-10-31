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
                "AUTO" to "自然",
                "TEXT" to "清晰文字",
                "WORKBOOK" to "练习册",
                "CLEAN" to "强力去背景",
                "GRAY" to "灰度",
                "BW" to "柔和黑白",
                "COLOR" to "彩色",
                "WHITEBOARD" to "白板",
            )
        assertEquals(expected.keys, ScanProcessor.filters.toSet())
        expected.forEach { (filter, label) -> assertEquals(label, scanFilterName(filter)) }
    }

    @Test
    fun pdfEnhancementOffersTheSamePresets() {
        val presets = PdfImageEnhancement.presets
        assertTrue(presets.containsKey("WORKBOOK"))
        assertEquals("练习册", presets["WORKBOOK"])
        assertEquals("自然", presets["AUTO"])
        assertEquals("清晰文字", presets["TEXT"])
        assertEquals("强力去背景", presets["CLEAN"])
    }
}
