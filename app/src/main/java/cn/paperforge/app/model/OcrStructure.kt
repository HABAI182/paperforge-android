package cn.paperforge.app.model

import cn.paperforge.app.data.PageResources
import cn.paperforge.app.data.PageRow
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Coordinates always describe the processed page, after crop/rotation/dewarp, with y down. */
data class OcrFrame(
    val revision: Int,
    val geometrySignature: String,
    val imageSha256: String,
    val geometry: String,
) {
    fun matches(page: PageRow) = revision == page.revision && geometrySignature == signature(page)

    fun json() =
        JSONObject()
            .put("sourceRevision", revision)
            .put("geometrySignature", geometrySignature)
            .put("imageSha256", imageSha256)
            .put("geometry", JSONObject(geometry))

    companion object {
        private fun descriptor(page: PageRow): JSONObject {
            val steps = PageResources.metadata(page).optJSONArray("steps") ?: JSONArray()
            val dewarp = JSONArray()
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                if (step.optString("kind") == "dewarp")
                    dewarp.put(
                        JSONObject()
                            .put("algorithm", step.optString("algorithm"))
                            .put("modelSha256", step.optString("modelSha256"))
                            .put("strength", step.optDouble("strength", 1.0))
                            .put("sourceGeometry", step.optJSONObject("sourceGeometry"))
                    )
            }
            return JSONObject()
                .put("corners", page.corners)
                .put("rotation", page.rotation)
                .put("deskewDegrees", page.deskewDegrees)
                .put("outputAspect", page.outputAspect)
                .put("dewarp", dewarp)
        }

        private fun digest(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }

        fun signature(page: PageRow) = digest(descriptor(page).toString().toByteArray())

        fun bind(page: PageRow): OcrFrame {
            val hash = MessageDigest.getInstance("SHA-256")
            File(page.original).inputStream().buffered().use { input ->
                val bytes = ByteArray(65536)
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    hash.update(bytes, 0, n)
                }
            }
            return OcrFrame(
                page.revision,
                signature(page),
                hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
                descriptor(page).toString(),
            )
        }

        fun decode(json: JSONObject) =
            OcrFrame(
                json.getInt("sourceRevision"),
                json.getString("geometrySignature"),
                json.getString("imageSha256"),
                json.getJSONObject("geometry").toString(),
            )
    }
}

data class OcrCell(
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
    val corners: List<PointN>,
    val text: String,
    val lineIndices: List<Int> = emptyList(),
) {
    fun json() =
        JSONObject()
            .put("row", row)
            .put("column", column)
            .put("rowSpan", rowSpan)
            .put("columnSpan", columnSpan)
            .put("corners", OcrStructure.points(corners))
            .put("text", text)
            .put("lineIndices", JSONArray(lineIndices))

    companion object {
        fun decode(o: JSONObject) =
            OcrCell(
                o.getInt("row"),
                o.getInt("column"),
                o.getInt("rowSpan"),
                o.getInt("columnSpan"),
                OcrStructure.points(o.getJSONArray("corners")),
                o.getString("text"),
                o.optJSONArray("lineIndices")
                    ?.let { a -> List(a.length()) { a.getInt(it) } }
                    .orEmpty(),
            )
    }
}

data class OcrTable(val rows: Int, val columns: Int, val cells: List<OcrCell>, val method: String) {
    fun json() =
        JSONObject()
            .put("rows", rows)
            .put("columns", columns)
            .put("method", method)
            .put("cells", JSONArray().apply { cells.forEach { put(it.json()) } })

    companion object {
        fun decode(o: JSONObject) =
            OcrTable(
                o.getInt("rows"),
                o.getInt("columns"),
                o.getJSONArray("cells").let { a ->
                    List(a.length()) { OcrCell.decode(a.getJSONObject(it)) }
                },
                o.getString("method"),
            )
    }
}

/**
 * Generated from the authoritative flat records; corrected text cannot leave a stale second tree.
 */
object OcrStructure {
    fun points(points: List<PointN>) =
        JSONArray().apply { points.forEach { put(JSONArray(listOf(it.x, it.y))) } }

    fun points(a: JSONArray) =
        List(a.length()) { n ->
            a.getJSONArray(n).let { PointN(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
        }

    private fun envelope(lines: List<OcrLine>): JSONArray {
        val p = lines.flatMap { it.corners }
        if (p.isEmpty()) return JSONArray()
        val l = p.minOf { it.x }
        val r = p.maxOf { it.x }
        val t = p.minOf { it.y }
        val b = p.maxOf { it.y }
        return points(listOf(PointN(l, t), PointN(r, t), PointN(r, b), PointN(l, b)))
    }

    fun json(layout: OcrLayout): JSONObject {
        val indexed = layout.lines.withIndex().toList()
        return JSONObject()
            .put("format", "paperforge-ocr-ir")
            .put("version", 1)
            .put(
                "page",
                JSONObject()
                    .put("width", layout.width)
                    .put("height", layout.height)
                    .put("coordinateSystem", "processed-page-normalized-top-left")
                    .put("sourceRevision", layout.sourceRevision)
                    .put("frame", layout.frame?.json()),
            )
            .put(
                "engine",
                JSONObject()
                    .put("id", layout.engineId)
                    .put("version", layout.engineVersion)
                    .put("request", layout.requestSignature),
            )
            .put("readingOrder", JSONArray(indexed.map { "line-${it.index}" }))
            .put(
                "blocks",
                JSONArray().apply {
                    indexed
                        .groupBy { it.value.block }
                        .forEach { (block, items) ->
                            put(
                                JSONObject()
                                    .put("id", "block-$block")
                                    .put("kind", "text")
                                    .put("bounds", envelope(items.map { it.value }))
                                    .put(
                                        "paragraphs",
                                        JSONArray().apply {
                                            items
                                                .groupBy { it.value.paragraph }
                                                .forEach { (paragraph, rows) ->
                                                    put(
                                                        JSONObject()
                                                            .put(
                                                                "id",
                                                                "block-$block-paragraph-$paragraph",
                                                            )
                                                            .put(
                                                                "bounds",
                                                                envelope(rows.map { it.value }),
                                                            )
                                                            .put(
                                                                "lines",
                                                                JSONArray().apply {
                                                                    rows.forEach { (index, line) ->
                                                                        put(
                                                                            JSONObject()
                                                                                .put(
                                                                                    "id",
                                                                                    "line-$index",
                                                                                )
                                                                                .put("index", index)
                                                                                .put(
                                                                                    "text",
                                                                                    line.text,
                                                                                )
                                                                                .put(
                                                                                    "corners",
                                                                                    points(
                                                                                        line.corners
                                                                                    ),
                                                                                )
                                                                                .put(
                                                                                    "confidence",
                                                                                    line.confidence,
                                                                                )
                                                                                .put(
                                                                                    "corrected",
                                                                                    line.corrected,
                                                                                )
                                                                                .put(
                                                                                    "words",
                                                                                    JSONArray()
                                                                                        .apply {
                                                                                            if (
                                                                                                !line
                                                                                                    .corrected
                                                                                            )
                                                                                                line
                                                                                                    .elements
                                                                                                    .forEachIndexed {
                                                                                                        word,
                                                                                                        e
                                                                                                        ->
                                                                                                        put(
                                                                                                            JSONObject()
                                                                                                                .put(
                                                                                                                    "id",
                                                                                                                    "line-$index-word-$word",
                                                                                                                )
                                                                                                                .put(
                                                                                                                    "text",
                                                                                                                    e
                                                                                                                        .text,
                                                                                                                )
                                                                                                                .put(
                                                                                                                    "corners",
                                                                                                                    points(
                                                                                                                        e
                                                                                                                            .corners
                                                                                                                    ),
                                                                                                                )
                                                                                                                .put(
                                                                                                                    "confidence",
                                                                                                                    e
                                                                                                                        .confidence,
                                                                                                                )
                                                                                                        )
                                                                                                    }
                                                                                        },
                                                                                )
                                                                        )
                                                                    }
                                                                },
                                                            )
                                                    )
                                                }
                                        },
                                    )
                            )
                        }
                },
            )
            .put("tables", JSONArray().apply { layout.tables.forEach { put(it.json()) } })
    }
}
