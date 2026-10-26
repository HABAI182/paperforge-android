package cn.paperforge.app

import android.content.Context
import org.json.JSONObject

/** Reading progress belongs to a document, and never changes its PDF or edit history. */
data class ReaderPosition(
    val page: Int = 0,
    val horizontal: Boolean = true,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val offset: Int = 0,
    val tone: Int = 0,
)

data class ReflowPosition(
    val page: Int = 0,
    val index: Int = 0,
    val offset: Int = 0,
    val fontSize: Float = 20f,
    val lineSpacing: Float = 1.6f,
    val columns: Int = 0,
) {
    fun json() =
        JSONObject()
            .put("page", page)
            .put("index", index)
            .put("offset", offset)
            .put("fontSize", fontSize)
            .put("lineSpacing", lineSpacing)
            .put("columns", columns)

    companion object {
        fun decode(o: JSONObject) =
            ReflowPosition(
                o.optInt("page", 0).coerceAtLeast(0),
                o.optInt("index", 0).coerceAtLeast(0),
                o.optInt("offset", 0).coerceAtLeast(0),
                o.optDouble("fontSize", 20.0).toFloat().coerceIn(14f, 36f),
                o.optDouble("lineSpacing", 1.6).toFloat().coerceIn(1.2f, 2f),
                o.optInt("columns", 0).coerceIn(0, 2),
            )
    }
}

class ReaderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private val viewPrefs = context.getSharedPreferences("reader-view", Context.MODE_PRIVATE)

    fun reflow(id: String) =
        runCatching {
                ReflowPosition.decode(JSONObject(prefs.getString("reflow:$id", "{}") ?: "{}"))
            }
            .getOrDefault(ReflowPosition())

    fun reflow(id: String, state: ReflowPosition) {
        prefs.edit().putString("reflow:$id", state.json().toString()).apply()
    }

    fun hasPosition(id: String) = prefs.contains(id)

    fun view(id: String) =
        runCatching {
                cn.paperforge.app.reader.ReaderViewOptions.decode(
                    JSONObject(viewPrefs.getString(id, "{}") ?: "{}")
                )
            }
            .getOrDefault(cn.paperforge.app.reader.ReaderViewOptions())

    fun view(id: String, value: cn.paperforge.app.reader.ReaderViewOptions) {
        viewPrefs.edit().putString(id, value.encode().toString()).apply()
    }

    fun load(id: String): ReaderPosition =
        runCatching {
                val json = JSONObject(prefs.getString(id, "{}") ?: "{}")
                ReaderPosition(
                    json.optInt("page", 0).coerceAtLeast(0),
                    json.optBoolean("horizontal", prefs.getBoolean("horizontal", true)),
                    json.optDouble("zoom", 1.0).toFloat().coerceIn(1f, 5f),
                    json.optDouble("x", 0.0).toFloat().coerceIn(-2f, 2f),
                    json.optDouble("y", 0.0).toFloat().coerceIn(-2f, 2f),
                    json.optInt("offset", 0).coerceAtLeast(0),
                    json.optInt("tone", prefs.getInt("tone", 0)).coerceIn(0, 2),
                )
            }
            .getOrDefault(ReaderPosition())

    fun bookmarks(id: String): Set<Int> =
        prefs
            .getStringSet("marks:$id", emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .filter { it >= 0 }
            .toSet()

    fun bookmarks(id: String, pages: Set<Int>) {
        prefs.edit().putStringSet("marks:$id", pages.map { it.toString() }.toSet()).apply()
    }

    fun export(ids: List<String>): JSONObject =
        JSONObject().apply {
            ids.forEach { id ->
                put(
                    id,
                    JSONObject()
                        .put("position", JSONObject(prefs.getString(id, "{}") ?: "{}"))
                        .put("bookmarks", org.json.JSONArray(bookmarks(id).sorted()))
                        .put("view", view(id).encode())
                        .put("reflow", reflow(id).json()),
                )
            }
        }

    fun restore(data: JSONObject, ids: Map<String, String>) {
        val edit = prefs.edit()
        ids.forEach { (old, new) ->
            data.optJSONObject(old)?.let { state ->
                state.optJSONObject("position")?.let { edit.putString(new, it.toString()) }
                state.optJSONObject("reflow")?.let { reflow(new, ReflowPosition.decode(it)) }
                state.optJSONObject("view")?.let {
                    view(new, cn.paperforge.app.reader.ReaderViewOptions.decode(it))
                }
                state.optJSONArray("bookmarks")?.let { marks ->
                    edit.putStringSet(
                        "marks:$new",
                        (0 until marks.length()).map { marks.getInt(it).toString() }.toSet(),
                    )
                }
            }
        }
        edit.apply()
    }

    fun save(id: String, state: ReaderPosition) {
        prefs
            .edit()
            .putBoolean("horizontal", state.horizontal)
            .putInt("tone", state.tone)
            .putString(
                id,
                JSONObject()
                    .put("page", state.page)
                    .put("horizontal", state.horizontal)
                    .put("zoom", state.zoom)
                    .put("x", state.panX)
                    .put("y", state.panY)
                    .put("offset", state.offset)
                    .put("tone", state.tone)
                    .toString(),
            )
            .apply()
    }
}
