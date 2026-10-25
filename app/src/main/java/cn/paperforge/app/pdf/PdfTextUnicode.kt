package cn.paperforge.app.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.File
import java.util.IdentityHashMap

/** For standalone single-font text PDFs only: preserve written Unicode rather than cmap aliases. */
internal object PdfTextUnicode {
    fun rewrite(file: File, characters: Map<Int, String>) {
        if (characters.isEmpty()) return
        val cmap =
            buildString {
                    append(
                        "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n/CMapName /PaperForgeCanonicalUnicode def\n/CMapType 2 def\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n"
                    )
                    characters.entries.chunked(100).forEach { group ->
                        append("${group.size} beginbfchar\n")
                        group.forEach { (code, text) ->
                            append(
                                "<${code.toString(16).padStart(4,'0')}> <${text.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02x".format(it) }}>\n"
                            )
                        }
                        append("endbfchar\n")
                    }
                    append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
                }
                .toByteArray(Charsets.US_ASCII)
        val fixed = File(file.path + ".unicode")
        try {
            // Font subsetting builds its own alias-based ToUnicode map during save. Correct it
            // after that step, using a fresh document that will not subset the font again.
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val visited =
                    java.util.Collections.newSetFromMap(
                        IdentityHashMap<com.tom_roush.pdfbox.cos.COSDictionary, Boolean>()
                    )
                for (page in doc.pages) for (name in page.resources.fontNames) {
                    val font = page.resources.getFont(name)
                    if (font is PDType0Font && visited.add(font.cosObject))
                        font.cosObject.setItem(
                            COSName.TO_UNICODE,
                            PDStream(doc, cmap.inputStream(), COSName.FLATE_DECODE),
                        )
                }
                doc.save(fixed)
            }
            check(fixed.renameTo(file)) { "无法完成字体文字映射" }
        } finally {
            fixed.delete()
        }
    }
}
