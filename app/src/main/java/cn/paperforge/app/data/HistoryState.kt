package cn.paperforge.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Reads legacy single-page edits and document snapshots without changing the Room schema. */
object HistoryState {
    fun snapshot(pages: List<PageRow>): String =
        JSONObject()
            .put(
                "documentSnapshot",
                JSONArray().apply { pages.forEach { put(DocumentCodec.page(it)) } },
            )
            .toString()

    fun isSnapshot(value: String) = JSONObject(value).has("documentSnapshot")

    fun delta(pages: List<PageRow>, transfer: Boolean = false) =
        JSONObject()
            .put("pageDelta", JSONArray().apply { pages.forEach { put(DocumentCodec.page(it)) } })
            .apply { if (transfer) put("pageTransfer", true) }
            .toString()

    fun isDelta(value: String) = JSONObject(value).has("pageDelta")

    fun isTransfer(value: String) = JSONObject(value).optBoolean("pageTransfer")

    fun ordered(pages: List<PageRow>, order: List<String>) =
        JSONObject(delta(pages)).put("pageOrder", JSONArray(order)).toString()

    fun order(value: String): List<String>? =
        JSONObject(value).optJSONArray("pageOrder")?.let { a ->
            (0 until a.length()).map { a.getString(it) }
        }

    fun sameContent(first: PageRow?, second: PageRow?) =
        first?.copy(position = 0) == second?.copy(position = 0)

    fun pages(value: String): List<PageRow> {
        val json = JSONObject(value)
        val array =
            json.optJSONArray("documentSnapshot")
                ?: json.optJSONArray("pageDelta")
                ?: return listOf(DocumentCodec.page(json))
        return (0 until array.length()).map { DocumentCodec.page(array.getJSONObject(it)) }
    }

    fun map(value: String, transform: (PageRow) -> PageRow): JSONObject =
        remap(value, transform) { it }

    fun remap(
        value: String,
        transform: (PageRow) -> PageRow,
        mapId: (String) -> String,
    ): JSONObject {
        val mapped = pages(value).map(transform)
        return if (isSnapshot(value)) JSONObject(snapshot(mapped))
        else if (isDelta(value))
            JSONObject(delta(mapped, isTransfer(value))).apply {
                order(value)?.let { put("pageOrder", JSONArray(it.map(mapId))) }
            }
        else DocumentCodec.page(mapped.single())
    }
}
