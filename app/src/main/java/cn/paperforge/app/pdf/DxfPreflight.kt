package cn.paperforge.app.pdf

import java.io.File
import kotlin.math.*

/** LOK's fallback extent calculation omits TEXT; supply missing model extents in a working copy. */
object DxfPreflight {
    const val VERSION = "dxf-missing-extents1"

    fun prepare(source: File, destination: File): File {
        if (!source.extension.equals("dxf", true)) return source
        val text = source.readBytes().toString(Charsets.ISO_8859_1)
        if (text.startsWith("AutoCAD Binary DXF")) return source
        val lines = text.split(Regex("\\r\\n|\\r|\\n")).dropLastWhile { it.isEmpty() }
        if (lines.size % 2 != 0) return source
        val groups =
            lines.chunked(2).map { (key, value) ->
                (key.trim().toIntOrNull() ?: return source) to value
            }
        if (
            groups.any { it.first == 9 && it.second.trim() == "\$EXTMIN" } &&
                groups.any { it.first == 9 && it.second.trim() == "\$EXTMAX" }
        )
            return source
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var hasText = false
        fun point(x: Double?, y: Double?) {
            if (x == null || y == null || !x.isFinite() || !y.isFinite()) return
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        var at = 0
        while (at < groups.size) {
            if (groups[at].first != 0) {
                at++
                continue
            }
            val type = groups[at].second.trim()
            val start = ++at
            while (at < groups.size && groups[at].first != 0) at++
            val entity = groups.subList(start, at)
            fun number(code: Int) =
                entity.firstOrNull { it.first == code }?.second?.trim()?.toDoubleOrNull()
            for (i in entity.indices) if (entity[i].first in 10..18)
                point(
                    entity[i].second.trim().toDoubleOrNull(),
                    entity
                        .drop(i + 1)
                        .firstOrNull { it.first == entity[i].first + 10 }
                        ?.second
                        ?.trim()
                        ?.toDoubleOrNull(),
                )
            val x = number(10)
            val y = number(20)
            if (type in setOf("CIRCLE", "ARC") && x != null && y != null)
                number(40)?.let { radius ->
                    point(x - abs(radius), y - abs(radius))
                    point(x + abs(radius), y + abs(radius))
                }
            if (type in setOf("TEXT", "MTEXT", "ATTRIB", "ATTDEF") && x != null && y != null) {
                hasText = true
                val height = abs(number(40) ?: 1.0)
                val characters =
                    entity
                        .filter { it.first == 1 || it.first == 3 }
                        .sumOf { it.second.length }
                        .coerceAtLeast(1)
                val width =
                    if (type == "MTEXT") abs(number(41) ?: (characters * height))
                    else characters * height * abs(number(41) ?: 1.0)
                val radius = hypot(width, height * 2)
                point(x - radius, y - radius)
                point(x + radius, y + radius)
            }
        }
        if (!hasText || !minX.isFinite() || maxX <= minX || maxY <= minY) return source
        val margin = max(maxX - minX, maxY - minY) * 0.02
        val header =
            listOf(
                9 to "\$EXTMIN",
                10 to (minX - margin).toString(),
                20 to (minY - margin).toString(),
                30 to "0",
                9 to "\$EXTMAX",
                10 to (maxX + margin).toString(),
                20 to (maxY + margin).toString(),
                30 to "0",
            )
        val existing = groups.indexOfFirst { it.first == 2 && it.second.trim() == "HEADER" }
        val result =
            if (existing >= 0) groups.take(existing + 1) + header + groups.drop(existing + 1)
            else listOf(0 to "SECTION", 2 to "HEADER") + header + listOf(0 to "ENDSEC") + groups
        destination.parentFile!!.mkdirs()
        destination.writeBytes(
            result
                .joinToString("\r\n", postfix = "\r\n") { "${it.first}\r\n${it.second}" }
                .toByteArray(Charsets.ISO_8859_1)
        )
        return destination
    }
}
