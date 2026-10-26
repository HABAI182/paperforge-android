package cn.paperforge.app.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.File
import java.util.IdentityHashMap

/** Canonical written Unicode; tagged mode changes only fonts added by the current OCR operation. */
internal object PdfTextUnicode {
    val fontTag = COSName.getPDFName("PaperForgeUnicodeMapping")

    private fun cmap(characters: Map<Int, String>): ByteArray =
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

    fun rewrite(file: File, characters: Map<Int, String>) {
        if (characters.isNotEmpty()) rewriteFonts(file) { characters }
    }

    fun rewriteTagged(file: File, mappings: Map<String, Map<Int, String>>) {
        if (mappings.isEmpty()) return
        rewriteFonts(file) { font ->
            mappings[font.cosObject.getString(fontTag)]?.also { font.cosObject.removeItem(fontTag) }
        }
    }

    private fun rewriteFonts(file: File, mapping: (PDType0Font) -> Map<Int, String>?) {
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
                    if (font is PDType0Font && visited.add(font.cosObject)) {
                        val characters = mapping(font) ?: continue
                        font.cosObject.setItem(
                            COSName.TO_UNICODE,
                            PDStream(doc, cmap(characters).inputStream(), COSName.FLATE_DECODE),
                        )
                    }
                }
                doc.save(fixed)
            }
            check(fixed.renameTo(file)) { "无法完成字体文字映射" }
        } finally {
            fixed.delete()
        }
    }
}
