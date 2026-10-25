package cn.paperforge.app.data

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class DocumentFamily(
    val id: String,
    val name: String,
    val members: List<String>,
    val preferred: String? = null,
) {
    fun encode() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("members", JSONArray(members))
            .put("preferred", preferred ?: JSONObject.NULL)

    companion object {
        fun decode(j: JSONObject): DocumentFamily {
            val members =
                j.getJSONArray("members").let { a ->
                    (0 until a.length()).map { a.getString(it) }.distinct()
                }
            return DocumentFamily(
                j.getString("id"),
                j.getString("name"),
                members,
                j.optString("preferred").takeIf { it in members },
            )
        }

        fun create(name: String, members: List<String>) =
            DocumentFamily(
                UUID.randomUUID().toString(),
                name,
                members.distinct(),
                members.firstOrNull(),
            )
    }
}
