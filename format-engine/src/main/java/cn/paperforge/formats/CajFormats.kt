package cn.paperforge.formats

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray

/** PDF-backed CAJ/KDH containers; HN/C8 image-codec containers remain explicitly unsupported. */
object CajFormats {
    data class Prepared(val file: File, val expectedPages: Int?, val warnings: JSONArray)

    fun prepare(source: File, folder: File): Prepared {
        val bytes = source.readBytes()
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val warnings =
            JSONArray().put("CAJ/KDH 仅转换内含 PDF 的容器；目录与原始签章保留在原件，未迁移。HN / C8 / TEB 图像容器尚不支持。")
        val result = File(folder, "container.pdf")
        if (raw.startsWith("%PDF")) {
            result.writeBytes(bytes)
            return Prepared(result, null, warnings)
        }
        if (raw.startsWith("KDH ")) {
            require(bytes.size > 254) { "KDH 容器不完整" }
            val key = "FZHMEI".toByteArray()
            val decoded =
                ByteArray(bytes.size - 254) {
                    (bytes[it + 254].toInt() xor key[it % key.size].toInt()).toByte()
                }
            val end = decoded.toString(Charsets.ISO_8859_1).lastIndexOf("%%EOF")
            require(end >= 0) { "此 KDH 未找到内含 PDF；原件保留" }
            result.writeBytes(decoded.copyOf(end + 5))
            return Prepared(result, null, warnings)
        }
        require(raw.startsWith("CAJ\u0000")) { "此 CAJ 属于 HN / C8 / TEB 或未知容器，当前引擎不能转换；原件保留" }
        fun littleInt(offset: Int): Int {
            require(offset >= 0 && offset.toLong() + 4 <= bytes.size) { "CAJ 容器索引不完整" }
            return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }
        val expected = littleInt(0x10)
        val start = littleInt(littleInt(0x14))
        val end = raw.lastIndexOf("endobj") + 6
        require(expected > 0 && start >= 0 && start < end && end <= bytes.size) {
            "CAJ 内含 PDF 数据不完整"
        }
        val content = raw.substring(start, end)
        val headers = Regex("(?:^|[\\r\\n])\\s*(\\d+)\\s+0\\s+obj\\b").findAll(content).toList()
        require(headers.isNotEmpty()) { "CAJ 内含 PDF 对象不可用" }
        data class Node(
            val id: Int,
            val dictionary: String,
            val parent: Int?,
            val page: Boolean,
            val pages: Boolean,
        )
        val nodes =
            headers
                .mapIndexed { index, header ->
                    val objectText =
                        content.substring(
                            header.range.last + 1,
                            headers.getOrNull(index + 1)?.range?.first ?: content.length,
                        )
                    val dictionary = objectText.substringBefore("stream").substringBefore("endobj")
                    Node(
                        header.groupValues[1].toInt(),
                        dictionary,
                        Regex("/Parent\\s+(\\d+)\\s+0\\s+R")
                            .find(dictionary)
                            ?.groupValues
                            ?.get(1)
                            ?.toInt(),
                        Regex("/Type\\s*/Page\\b").containsMatchIn(dictionary),
                        Regex("/Type\\s*/Pages\\b").containsMatchIn(dictionary),
                    )
                }
                .associateBy { it.id }
                .values
                .toList()
        val ids = nodes.map { it.id }.toMutableSet()
        val extra = StringBuilder()
        fun fresh(): Int =
            (generateSequence(1) { it + 1 }.first { it !in ids }).also { ids.add(it) }
        val missing =
            nodes
                .filter { (it.page || it.pages) && it.parent != null && it.parent !in ids }
                .groupBy { it.parent!! }
        ids.addAll(missing.keys)
        fun pages(id: Int, kids: List<Int>, count: Int) {
            extra.append(
                "\r\n$id 0 obj\n<< /Type /Pages /Kids [${kids.joinToString(" ") { "$it 0 R" }}] /Count $count >>\nendobj\n"
            )
        }
        for ((id, children) in missing) pages(
            id,
            children.map { it.id },
            children.sumOf {
                if (it.page) 1
                else
                    Regex("/Count\\s+(\\d+)")
                        .find(it.dictionary)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull() ?: 0
            },
        )
        val roots = nodes.filter { it.pages && it.parent == null }.map { it.id } + missing.keys
        val root =
            if (roots.size == 1) roots.single()
            else
                fresh().also {
                    pages(it, roots.ifEmpty { nodes.filter { it.page }.map { it.id } }, expected)
                }
        val catalog = fresh()
        extra.append(
            "\n$catalog 0 obj\n<< /Type /Catalog /Pages $root 0 R >>\nendobj\ntrailer\n<< /Root $catalog 0 R /Size ${(ids.maxOrNull() ?: 0) + 1} >>\nstartxref\n0\n%%EOF\n"
        )
        result.outputStream().use {
            it.write("%PDF-1.7\r\n".toByteArray())
            it.write(bytes, start, end - start)
            it.write(extra.toString().toByteArray(Charsets.ISO_8859_1))
        }
        return Prepared(result, expected, warnings)
    }
}
