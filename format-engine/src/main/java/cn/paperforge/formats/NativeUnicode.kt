package cn.paperforge.formats

import com.artifex.mupdf.fitz.PDFDocument
import java.math.BigInteger

/** Canonical bfchar mappings for newly generated PDFs. Original source PDFs are never changed. */
object NativeUnicode {
    fun normalize(pdf: PDFDocument) {
        val visited = mutableSetOf<Int>()
        for (index in 0 until pdf.countPages()) {
            val page = pdf.findPage(index)
            val resources = page.getInheritable("Resources")
            val fonts = resources.get("Font")
            try {
                for (key in fonts) {
                    val font = fonts.get(key)
                    val mapping = font.get("ToUnicode")
                    try {
                        if (mapping.isStream && visited.add(mapping.asIndirect())) {
                            canonical(mapping.readStream().toString(Charsets.US_ASCII))?.let {
                                mapping.writeStream(it)
                            }
                        }
                    } finally {
                        mapping.destroy()
                        font.destroy()
                        key.destroy()
                    }
                }
            } finally {
                fonts.destroy()
                resources.destroy()
                page.destroy()
            }
        }
    }

    private fun canonical(source: String): String? {
        val mappings = linkedMapOf<String, String>()
        fun destination(hex: String): String =
            if (hex.length in 5..7) {
                String(Character.toChars(hex.toInt(16)))
                    .toByteArray(Charsets.UTF_16BE)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
            } else hex.padStart(hex.length + hex.length % 2, '0')
        val hex = "[0-9a-fA-F]+"
        val blocks = Regex("\\d+\\s+beginbf(char|range)(.*?)endbf\\1", RegexOption.DOT_MATCHES_ALL)
        for (block in blocks.findAll(source)) {
            val body = block.groupValues[2]
            if (block.groupValues[1] == "char") {
                Regex("<($hex)>\\s*<($hex)>").findAll(body).forEach { match ->
                    mappings[match.groupValues[1].lowercase()] = destination(match.groupValues[2])
                }
            } else {
                Regex("<($hex)>\\s*<($hex)>\\s*<($hex)>").findAll(body).forEach { match ->
                    val start = match.groupValues[1].toInt(16)
                    val end = match.groupValues[2].toInt(16)
                    val target = match.groupValues[3]
                    val base = BigInteger(target, 16)
                    for (code in start..end) mappings[
                        code.toString(16).padStart(match.groupValues[1].length, '0')] =
                        destination(
                            base
                                .add(BigInteger.valueOf((code - start).toLong()))
                                .toString(16)
                                .padStart(target.length, '0')
                        )
                }
                Regex("<($hex)>\\s*<($hex)>\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
                    .findAll(body)
                    .forEach { match ->
                        val start = match.groupValues[1].toInt(16)
                        Regex("<($hex)>").findAll(match.groupValues[3]).forEachIndexed {
                            offset,
                            value ->
                            mappings[
                                (start + offset)
                                    .toString(16)
                                    .padStart(match.groupValues[1].length, '0')] =
                                destination(value.groupValues[1])
                        }
                    }
            }
        }
        if (mappings.isEmpty()) return null
        val space =
            Regex("\\d+\\s+begincodespacerange.*?endcodespacerange", RegexOption.DOT_MATCHES_ALL)
                .find(source)
                ?.value ?: return null
        return buildString {
            append(
                "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n/CMapName /PaperForgeNativeUnicode def\n/CMapType 2 def\n$space\n"
            )
            mappings.entries.chunked(100).forEach { group ->
                append("${group.size} beginbfchar\n")
                group.forEach { (code, value) -> append("<$code> <$value>\n") }
                append("endbfchar\n")
            }
            append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
        }
    }
}
