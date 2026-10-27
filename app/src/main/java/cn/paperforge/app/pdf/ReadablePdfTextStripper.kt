package cn.paperforge.app.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/** Keep original PDF bytes while excluding demonstrably broken Unicode chunks from extraction. */
internal open class ReadablePdfTextStripper(private val document: PDDocument) : PDFTextStripper() {
    private var broken = emptySet<COSDictionary>()

    override fun startPage(page: PDPage) {
        broken = NativeTextCoverage.brokenFonts(document, currentPageNo - 1)
        super.startPage(page)
    }

    override fun processTextPosition(text: TextPosition) {
        // Filter before line grouping: damaged text may occupy the same line as repaired OCR.
        if (NativeTextCoverage.valid(text) && text.font.cosObject !in broken)
            super.processTextPosition(text)
    }
}
