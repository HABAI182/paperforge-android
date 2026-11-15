package cn.paperforge.app.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import java.util.WeakHashMap

/**
 * A tolerant reader for a font's /ToUnicode CMap. Several common writers emit `bfrange` entries whose destination
 * runs past the last byte (e.g. `<3c00> <3cdb> <67ec>`); PDFBox then maps those codes to wrong characters, while
 * other readers carry into the next byte. This reader carries, accepts odd-length destinations as code points, and
 * returns null where it has no entry so the caller can fall back to PDFBox.
 */
object PdfToUnicode {
    private val cache = WeakHashMap<Any, Map<Int, String>>()
    private val hex = Regex("<([0-9A-Fa-f\\s]*)>")

    fun lookup(font: PDFont, code: Int): String? = map(font)[code]

    fun map(font: PDFont): Map<Int, String> =
        synchronized(cache) {
            cache.getOrPut(font.cosObject) { runCatching { parse(font) }.getOrDefault(emptyMap()) }
        }

    private fun decode(digits: String): String? {
        val h = digits.filterNot { it.isWhitespace() }
        if (h.isEmpty()) return null
        if (h.length % 4 == 0) {
            val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
            return String(chars)
        }
        // Odd or 2-digit destinations: read as one code point.
        val cp = h.toLongOrNull(16) ?: return null
        return if (cp in 0..0x10FFFF) String(Character.toChars(cp.toInt())) else null
    }

    private fun parse(font: PDFont): Map<Int, String> {
        val stream = font.cosObject.getDictionaryObject(COSName.TO_UNICODE) as? COSStream ?: return emptyMap()
        val text = stream.createInputStream().use { it.readBytes() }.toString(Charsets.ISO_8859_1)
        val out = HashMap<Int, String>()
        Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { block ->
            val values = hex.findAll(block.groupValues[1]).map { it.groupValues[1] }.toList()
            var i = 0
            while (i + 1 < values.size) {
                val code = values[i].filterNot { it.isWhitespace() }.toIntOrNull(16)
                val dest = decode(values[i + 1])
                if (code != null && dest != null) out[code] = dest
                i += 2
            }
        }
        Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { block ->
            // Entries: <lo> <hi> <dst>  or  <lo> <hi> [<d0> <d1> ...]
            val entry = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(\\[[^\\]]*\\]|<[0-9A-Fa-f\\s]*>)")
            entry.findAll(block.groupValues[1]).forEach { m ->
                val lo = m.groupValues[1].toIntOrNull(16) ?: return@forEach
                val hi = m.groupValues[2].toIntOrNull(16) ?: return@forEach
                if (hi < lo || hi - lo > 0xFFFF) return@forEach
                val dst = m.groupValues[3]
                if (dst.startsWith("[")) {
                    hex.findAll(dst).map { it.groupValues[1] }.forEachIndexed { k, d ->
                        if (lo + k <= hi) decode(d)?.let { out[lo + k] = it }
                    }
                } else {
                    val digits = dst.trim('<', '>').filterNot { it.isWhitespace() }
                    if (digits.length == 4 || digits.length % 4 != 0) {
                        val base = digits.toLongOrNull(16) ?: return@forEach
                        for (c in lo..hi) {
                            val cp = base + (c - lo)
                            if (cp in 0..0x10FFFF && !(cp in 0xD800..0xDFFF)) out[c] = String(Character.toChars(cp.toInt()))
                        }
                    } else {
                        // Multi-unit destination (ligatures, surrogate pairs): only the last unit increments.
                        val units = digits.chunked(4).map { it.toInt(16) }
                        for (c in lo..hi) {
                            val last = units.last() + (c - lo)
                            if (last > 0xFFFF) continue
                            out[c] = String((units.dropLast(1) + last).map { it.toChar() }.toCharArray())
                        }
                    }
                }
            }
        }
        return out
    }
}
