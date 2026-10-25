package cn.paperforge.app.pdf

import cn.paperforge.app.data.LocalTaskRow
import org.json.JSONArray
import org.json.JSONObject

data class StitchSource(val id: String, val path: String, val name: String)

data class LongImageDraft(
    val taskId: String,
    val sources: List<StitchSource>,
    val options: LongImageOptions,
    val seams: Map<String, StitchSeam> = emptyMap(),
    val returnScreen: String = "convert",
    val revision: Int = 0,
    val returnDocument: String? = null,
) {
    fun key(index: Int) = "${sources[index-1].id}:${sources[index].id}"

    fun seam(index: Int) = seams[key(index)] ?: StitchSeam()

    fun orderedSeams() = (1 until sources.size).map(::seam)

    fun input() =
        JSONObject()
            .put("kind", "LONG_IMAGES")
            .put("draft", true)
            .put("name", "${sources.size} 张图片拼接")
            .put("inputs", JSONArray(sources.map { it.path }))
            .put("names", JSONArray(sources.map { it.name }))
            .put("sourceIds", JSONArray(sources.map { it.id }))
            .put("options", options.json().toString())
            .put(
                "seams",
                JSONObject().apply { seams.forEach { (key, value) -> put(key, value.json()) } },
            )
            .put("returnScreen", returnScreen)
            .put("returnDocument", returnDocument)

    companion object {
        fun decode(row: LocalTaskRow): LongImageDraft {
            val input = JSONObject(row.payload).getJSONObject("input")
            val files = input.getJSONArray("inputs")
            val names = input.optJSONArray("names")
            val ids = input.optJSONArray("sourceIds")
            val sources =
                List(files.length()) { i ->
                    StitchSource(
                        ids?.optString(i)?.takeIf { it.isNotBlank() } ?: "source-$i",
                        files.getString(i),
                        names?.optString(i) ?: "图片 ${i+1}",
                    )
                }
            val seams = mutableMapOf<String, StitchSeam>()
            input.optJSONObject("seams")?.let { map ->
                map.keys().forEach { key -> seams[key] = StitchSeam.decode(map.optJSONObject(key)) }
            }
            return LongImageDraft(
                row.id,
                sources,
                LongImageOptions.decode(JSONObject(input.optString("options", "{}"))),
                seams,
                input.optString("returnScreen", "convert"),
                returnDocument =
                    input.optString("returnDocument").takeUnless { it.isBlank() || it == "null" },
            )
        }
    }
}
