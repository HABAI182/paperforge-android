package cn.paperforge.app.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

/**
 * One annotation drawn on top of a scan page, in page-normalised coordinates (0..1 on both axes).
 *
 * The same model feeds the live Compose preview, the pixel bake and the history note, so what the
 * owner draws is exactly what is written into the new page version. Coordinates are normalised to
 * the page instead of the screen; [width] is a fraction of the page's shorter side, which keeps
 * stroke weight / mosaic granularity / bar thickness identical at preview and at 3000 px bake.
 */
data class PageAnnotation(
    val kind: String,
    val points: List<PointN> = emptyList(),
    val color: Int = 0xFF000000.toInt(),
    val width: Float = 0.006f,
    val text: String = "",
    val id: String = UUID.randomUUID().toString(),
) {
    val isFreehand: Boolean
        get() = kind == "pen" || kind == "highlight"

    /** Axis-aligned box for the box tools; the last point is the live drag point. */
    fun bounds(): FloatArray {
        if (points.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        val a = points.first()
        val b = points.last()
        return floatArrayOf(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }

    fun json(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("kind", kind)
            .put(
                "points",
                JSONArray().apply {
                    points.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
                },
            )
            .put("color", color)
            .put("width", width.toDouble())
            .put("text", text)

    /**
     * Returns true when a tap/drag at [point] (page-normalised) is on this annotation.
     * [tolerance] is in normalised units, so it stays the same finger distance at any zoom.
     */
    fun hit(point: PointN, tolerance: Float = .02f): Boolean {
        return when (kind) {
            "mosaic",
            "redact" -> {
                val b = bounds()
                point.x in b[0] - tolerance..b[2] + tolerance &&
                    point.y in b[1] - tolerance..b[3] + tolerance
            }
            "text" -> {
                if (points.isEmpty()) false
                else {
                    val anchor = points.first()
                    val lines = text.lines().ifEmpty { listOf("") }
                    val longest = lines.maxOfOrNull { it.length } ?: 0
                    val size = width.coerceAtLeast(.01f)
                    // Height and width are guard bands: the exact glyph box depends on the page
                    // aspect ratio, so the tap target is deliberately generous. Text is drawn with
                    // its first baseline at the anchor, so the box extends downwards.
                    val boxWidth = max(size * 1.2f, size * .62f * (longest + 1))
                    val boxHeight = size * 1.5f * lines.size
                    point.x in anchor.x - tolerance..anchor.x + boxWidth + tolerance &&
                        point.y in anchor.y - tolerance..anchor.y + boxHeight + tolerance
                }
            }
            else -> {
                if (points.isEmpty()) return false
                if (points.size == 1)
                    return hypot(points[0].x - point.x, points[0].y - point.y) <=
                        tolerance + width
                points.zipWithNext().any { (a, b) ->
                    distanceToSegment(point, a, b) <= tolerance + width * .5f
                }
            }
        }
    }

    companion object {
        fun decode(json: JSONObject): PageAnnotation {
            val array = json.optJSONArray("points") ?: JSONArray()
            val points =
                (0 until array.length()).map { index ->
                    val item = array.optJSONArray(index) ?: JSONArray()
                    PointN(item.optDouble(0, 0.0).toFloat(), item.optDouble(1, 0.0).toFloat())
                }
            return PageAnnotation(
                kind = json.optString("kind", "pen"),
                points = points,
                color = json.optInt("color", 0xFF000000.toInt()),
                width = json.optDouble("width", .006).toFloat(),
                text = json.optString("text", ""),
                id = json.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            )
        }

        fun decode(raw: String?): List<PageAnnotation> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::decode)
            }
        }

        fun encode(annotations: List<PageAnnotation>): String =
            JSONArray().apply { annotations.forEach { put(it.json()) } }.toString()

        private fun distanceToSegment(point: PointN, a: PointN, b: PointN): Float {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = dx * dx + dy * dy
            if (length <= .0000001f) return hypot(point.x - a.x, point.y - a.y)
            val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / length).coerceIn(0f, 1f)
            return hypot(point.x - a.x - t * dx, point.y - a.y - t * dy)
        }
    }
}

/**
 * Small undo/redo state for the annotation editor. Kept free of Compose gestures so a device test
 * can drive undo/redo independently of the toolbar and compare annotation lists directly.
 */
class AnnotationEditor(initial: List<PageAnnotation> = emptyList()) {
    var annotations by androidx.compose.runtime.mutableStateOf(initial)
        private set
    var canUndo by androidx.compose.runtime.mutableStateOf(false)
        private set
    var canRedo by androidx.compose.runtime.mutableStateOf(false)
        private set

    private val undoStack = ArrayDeque<List<PageAnnotation>>()
    private val redoStack = ArrayDeque<List<PageAnnotation>>()
    private val limit = 40

    fun add(annotation: PageAnnotation) = apply(annotations + annotation)

    fun removeIds(ids: Set<String>) {
        if (ids.isEmpty()) return
        apply(annotations.filterNot { it.id in ids })
    }

    fun updateText(id: String, text: String) =
        apply(annotations.map { if (it.id == id) it.copy(text = text) else it })

    fun idsAt(point: PointN, tolerance: Float = .025f): Set<String> =
        annotations.filter { it.hit(point, tolerance) }.map { it.id }.toSet()

    fun clear() {
        if (annotations.isEmpty()) return
        apply(emptyList())
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(annotations)
        annotations = previous
        updateFlags()
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(annotations)
        annotations = next
        updateFlags()
        return true
    }

    private fun apply(next: List<PageAnnotation>) {
        if (next == annotations) return
        undoStack.addLast(annotations)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        annotations = next
        updateFlags()
    }

    private fun updateFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }
}
