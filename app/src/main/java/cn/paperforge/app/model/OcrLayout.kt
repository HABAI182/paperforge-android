package cn.paperforge.app.model

import org.json.JSONArray
import org.json.JSONObject

data class OcrElement(
    val text: String,
    val corners: List<PointN>,
    val confidence: Float? = null,
    val angle: Float? = null,
)

data class OcrHypothesis(val text: String, val confidence: Float?, val variant: String)

data class OcrLine(
    val text: String,
    val corners: List<PointN>,
    val confidence: Float? = null,
    val angle: Float? = null,
    val block: Int = 0,
    val paragraph: Int = 0,
    val elements: List<OcrElement> = emptyList(),
    val variant: String = "original",
    val corrected: Boolean = false,
    val hypotheses: List<OcrHypothesis> = emptyList(),
)

data class OcrLayout(
    val width: Int,
    val height: Int,
    val lines: List<OcrLine>,
    val engineId: String = "legacy",
    val engineVersion: String = "unknown",
    val sourceRevision: Int = -1,
    val orientationCorrection: Int = 0,
    val deskewDegrees: Float = 0f,
    val warnings: List<String> = emptyList(),
    val recognitionMode: String = "legacy",
    val attempts: List<String> = emptyList(),
    val rawRecognition: String? = null,
    val requestSignature: String = "",
    val engineMetrics: Map<String, Double> = emptyMap(),
    val frame: OcrFrame? = null,
    val tables: List<OcrTable> = emptyList(),
) {
    val text
        get() = lines.joinToString("\n") { it.text }

    val meanConfidence
        get() = lines.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    fun encode() =
        JSONObject()
            .put("schema", 4)
            .put("ir", OcrStructure.json(this))
            .put("width", width)
            .put("height", height)
            .put("engineId", engineId)
            .put("engineVersion", engineVersion)
            .put("requestSignature", requestSignature)
            .put("engineMetrics", JSONObject(engineMetrics))
            .put("sourceRevision", sourceRevision)
            .put("orientationCorrection", orientationCorrection)
            .put("deskewDegrees", deskewDegrees)
            .put("warnings", JSONArray(warnings))
            .put("recognitionMode", recognitionMode)
            .put("attempts", JSONArray(attempts))
            .put("rawRecognition", rawRecognition?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put(
                "lines",
                JSONArray().apply {
                    lines.forEach { line ->
                        put(
                            JSONObject()
                                .put("text", line.text)
                                .put("corners", points(line.corners))
                                .put("confidence", line.confidence ?: JSONObject.NULL)
                                .put("angle", line.angle ?: JSONObject.NULL)
                                .put("block", line.block)
                                .put("paragraph", line.paragraph)
                                .put("variant", line.variant)
                                .put("corrected", line.corrected)
                                .put(
                                    "hypotheses",
                                    JSONArray().apply {
                                        line.hypotheses.forEach { h ->
                                            put(
                                                JSONObject()
                                                    .put("text", h.text)
                                                    .put(
                                                        "confidence",
                                                        h.confidence ?: JSONObject.NULL,
                                                    )
                                                    .put("variant", h.variant)
                                            )
                                        }
                                    },
                                )
                                .put(
                                    "elements",
                                    JSONArray().apply {
                                        line.elements.forEach { e ->
                                            put(
                                                JSONObject()
                                                    .put("text", e.text)
                                                    .put("corners", points(e.corners))
                                                    .put(
                                                        "confidence",
                                                        e.confidence ?: JSONObject.NULL,
                                                    )
                                                    .put("angle", e.angle ?: JSONObject.NULL)
                                            )
                                        }
                                    },
                                )
                        )
                    }
                },
            )
            .toString()

    fun rotated(degrees: Int): OcrLayout {
        val angle = ((degrees % 360) + 360) % 360
        fun point(p: PointN) =
            when (angle) {
                90 -> PointN(1 - p.y, p.x)
                180 -> PointN(1 - p.x, 1 - p.y)
                270 -> PointN(p.y, 1 - p.x)
                else -> p
            }
        return mapped(
                if (angle % 180 == 0) width else height,
                if (angle % 180 == 0) height else width,
                ::point,
            )
            .copy(orientationCorrection = 0)
    }

    fun mapped(width: Int, height: Int, transform: (PointN) -> PointN) =
        copy(
            width = width,
            height = height,
            frame = null,
            lines =
                lines.map { line ->
                    line.copy(
                        corners = line.corners.map(transform),
                        elements =
                            line.elements.map { it.copy(corners = it.corners.map(transform)) },
                    )
                },
            tables =
                tables.map { table ->
                    table.copy(
                        cells = table.cells.map { it.copy(corners = it.corners.map(transform)) }
                    )
                },
        )

    fun withRevision(revision: Int) =
        copy(sourceRevision = revision, frame = frame?.copy(revision = revision))

    fun matches(page: cn.paperforge.app.data.PageRow) =
        sourceRevision == page.revision && (frame?.matches(page) != false)

    companion object {
        private fun points(p: List<PointN>) =
            JSONArray().apply { p.forEach { put(JSONArray(listOf(it.x, it.y))) } }

        private fun points(a: JSONArray) =
            List(a.length()) { n ->
                a.getJSONArray(n).let {
                    PointN(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())
                }
            }

        private fun number(o: JSONObject, key: String) =
            if (o.isNull(key)) null else o.optDouble(key).toFloat().takeIf { it.isFinite() }

        fun decode(json: String): OcrLayout {
            val o = JSONObject(json)
            val a = o.getJSONArray("lines")
            return OcrLayout(
                o.getInt("width"),
                o.getInt("height"),
                List(a.length()) { i ->
                    val l = a.getJSONObject(i)
                    val elements = l.optJSONArray("elements") ?: JSONArray()
                    OcrLine(
                        l.getString("text"),
                        points(l.getJSONArray("corners")),
                        number(l, "confidence"),
                        number(l, "angle"),
                        l.optInt("block"),
                        l.optInt("paragraph"),
                        List(elements.length()) { n ->
                            val e = elements.getJSONObject(n)
                            OcrElement(
                                e.getString("text"),
                                points(e.getJSONArray("corners")),
                                number(e, "confidence"),
                                number(e, "angle"),
                            )
                        },
                        l.optString("variant", "original"),
                        l.optBoolean("corrected"),
                        l.optJSONArray("hypotheses")?.let { hs ->
                            List(hs.length()) { n ->
                                val h = hs.getJSONObject(n)
                                OcrHypothesis(
                                    h.getString("text"),
                                    number(h, "confidence"),
                                    h.getString("variant"),
                                )
                            }
                        } ?: emptyList(),
                    )
                },
                o.optString("engineId", "legacy"),
                o.optString("engineVersion", "unknown"),
                o.optInt("sourceRevision", -1),
                o.optInt("orientationCorrection"),
                o.optDouble("deskewDegrees", 0.0).toFloat(),
                o.optJSONArray("warnings")?.let { w -> List(w.length()) { w.getString(it) } }
                    ?: emptyList(),
                o.optString("recognitionMode", "legacy"),
                o.optJSONArray("attempts")?.let { a -> List(a.length()) { a.getString(it) } }
                    ?: emptyList(),
                o.optJSONObject("rawRecognition")?.toString(),
                o.optString("requestSignature", ""),
                o.optJSONObject("engineMetrics")?.let { metrics ->
                    metrics
                        .keys()
                        .asSequence()
                        .mapNotNull { key ->
                            metrics.optDouble(key).takeIf { it.isFinite() }?.let { key to it }
                        }
                        .toMap()
                } ?: emptyMap(),
                o.optJSONObject("ir")
                    ?.optJSONObject("page")
                    ?.optJSONObject("frame")
                    ?.let(OcrFrame::decode),
                o.optJSONObject("ir")
                    ?.optJSONArray("tables")
                    ?.let { a -> List(a.length()) { OcrTable.decode(a.getJSONObject(it)) } }
                    .orEmpty(),
            )
        }
    }
}
