package cn.paperforge.app.data

import androidx.room.*
import org.json.JSONObject

enum class LocalTaskState {
    CAPTURING,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED,
}

class TaskConverters {
    @TypeConverter fun state(value: LocalTaskState) = value.name

    @TypeConverter fun state(value: String) = LocalTaskState.valueOf(value)
}

@Entity(tableName = "local_tasks", indices = [Index("documentId"), Index("state")])
data class LocalTaskRow(
    @PrimaryKey val id: String,
    val type: String,
    val documentId: String?,
    val title: String,
    val pageId: String?,
    val targetPageId: String?,
    val position: Int,
    val state: LocalTaskState,
    val progress: Int = 0,
    val detail: String = "",
    val payload: String = "{}",
    val resources: String = "[]",
    val error: String? = null,
    val workId: String? = null,
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
) {
    fun resourceEntries() = DocumentResources.decode(org.json.JSONArray(resources))

    fun encode() =
        JSONObject()
            .put("id", id)
            .put("type", type)
            .put("documentId", documentId ?: JSONObject.NULL)
            .put("title", title)
            .put("pageId", pageId ?: JSONObject.NULL)
            .put("targetPageId", targetPageId ?: JSONObject.NULL)
            .put("position", position)
            .put("state", state.name)
            .put("progress", progress)
            .put("detail", detail)
            .put("payload", JSONObject(payload))
            .put("resources", org.json.JSONArray(resources))
            .put("error", error ?: JSONObject.NULL)
            .put("workId", workId ?: JSONObject.NULL)
            .put("created", created)
            .put("updated", updated)

    companion object {
        val active = setOf(LocalTaskState.CAPTURING, LocalTaskState.QUEUED, LocalTaskState.RUNNING)

        fun decode(j: JSONObject): LocalTaskRow {
            fun string(key: String) = j.optString(key).takeUnless { it.isBlank() || it == "null" }
            return LocalTaskRow(
                j.getString("id"),
                j.getString("type"),
                string("documentId"),
                j.getString("title"),
                string("pageId"),
                string("targetPageId"),
                j.getInt("position"),
                LocalTaskState.valueOf(j.getString("state")),
                j.optInt("progress"),
                j.optString("detail"),
                j.optJSONObject("payload")?.toString() ?: "{}",
                j.optJSONArray("resources")?.toString() ?: "[]",
                string("error"),
                string("workId"),
                j.getLong("created"),
                j.getLong("updated"),
            )
        }
    }
}
