package cn.paperforge.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LibraryFilter(
    val name: String = "",
    val collection: String = "ALL",
    val sort: String = "MODIFIED",
    val folder: String = "",
    val tags: String = "",
    val favorites: Boolean = false,
    val query: String = "",
) {
    fun encode() =
        JSONObject()
            .put("name", name)
            .put("collection", collection)
            .put("sort", sort)
            .put("folder", folder)
            .put("tags", tags)
            .put("favorites", favorites)
            .put("query", query)

    companion object {
        fun decode(j: JSONObject) =
            LibraryFilter(
                j.optString("name"),
                j.optString("collection", "ALL"),
                j.optString("sort", "MODIFIED"),
                j.optString("folder"),
                j.optString("tags"),
                j.optBoolean("favorites"),
                j.optString("query"),
            )
    }
}

class LibraryPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("library", 0)

    fun naming() = NamingOptions.decode(JSONObject(prefs.getString("naming", "{}") ?: "{}"))

    fun naming(value: NamingOptions) {
        prefs.edit().putString("naming", value.encode().toString()).apply()
    }

    fun renameUndo(): List<NameChange> {
        val rows = JSONArray(prefs.getString("renameUndo", "[]"))
        return (0 until rows.length()).map { NameChange.decode(rows.getJSONObject(it)) }
    }

    fun renameUndo(rows: List<NameChange>) {
        check(
            prefs
                .edit()
                .putString(
                    "renameUndo",
                    JSONArray().apply { rows.forEach { put(it.encode()) } }.toString(),
                )
                .commit()
        ) {
            "无法保存命名撤销记录"
        }
    }

    fun view() =
        runCatching { LibraryFilter.decode(JSONObject(prefs.getString("view", "{}") ?: "{}")) }
            .getOrDefault(LibraryFilter())

    fun view(value: LibraryFilter) {
        prefs.edit().putString("view", value.encode().toString()).apply()
    }

    fun saved(): List<LibraryFilter> =
        runCatching {
                val a = JSONArray(prefs.getString("saved", "[]"))
                (0 until a.length()).map { LibraryFilter.decode(a.getJSONObject(it)) }
            }
            .getOrDefault(emptyList())

    fun save(value: LibraryFilter) {
        prefs
            .edit()
            .putString(
                "saved",
                JSONArray()
                    .apply {
                        (saved().filter { it.name != value.name } + value).forEach {
                            put(it.encode())
                        }
                    }
                    .toString(),
            )
            .apply()
    }

    fun remove(name: String) {
        prefs
            .edit()
            .putString(
                "saved",
                JSONArray()
                    .apply { saved().filter { it.name != name }.forEach { put(it.encode()) } }
                    .toString(),
            )
            .apply()
    }

    fun export() =
        JSONObject()
            .put("view", view().encode())
            .put("saved", JSONArray().apply { saved().forEach { put(it.encode()) } })
            .put("naming", naming().encode())
            .put("renameUndo", JSONArray().apply { renameUndo().forEach { put(it.encode()) } })

    fun restore(j: JSONObject, documentIds: Map<String, String>? = null) {
        j.optJSONObject("naming")?.let { naming(NamingOptions.decode(it)) }
        j.optJSONArray("renameUndo")?.let { rows ->
            renameUndo(
                (0 until rows.length()).mapNotNull {
                    val row = NameChange.decode(rows.getJSONObject(it))
                    if (documentIds == null) row
                    else documentIds[row.documentId]?.let { id -> row.copy(documentId = id) }
                }
            )
        }
        j.optJSONObject("view")?.let { view(LibraryFilter.decode(it)) }
        j.optJSONArray("saved")?.let { a ->
            (0 until a.length()).forEach { save(LibraryFilter.decode(a.getJSONObject(it))) }
        }
    }
}
