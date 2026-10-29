package cn.paperforge.network.intelligence

import android.content.Context
import android.util.AtomicFile
import cn.paperforge.network.NetworkPreferences
import cn.paperforge.network.lan.LanLedger
import java.io.File
import java.util.UUID
import org.json.*

data class SourcePage(val page: Int, val text: String) {
    fun json() = JSONObject().put("page", page).put("text", text)
}

data class SourceBundle(val title: String, val documentId: String, val pages: List<SourcePage>) {
    fun json() =
        JSONObject()
            .put("format", 1)
            .put("title", title)
            .put("documentId", documentId)
            .put("pages", JSONArray(pages.map { it.json() }))

    val sha256
        get() = LanLedger.digest(json().toString().toByteArray(Charsets.UTF_8))

    companion object {
        fun decode(j: JSONObject): SourceBundle {
            val array = j.getJSONArray("pages")
            val pages =
                List(array.length()) {
                    array.getJSONObject(it).let { p ->
                        SourcePage(p.getInt("page"), p.getString("text"))
                    }
                }
            require(
                pages.all { it.page > 0 } && pages.map { it.page }.distinct().size == pages.size
            ) {
                "原文页码不正确"
            }
            require(pages.sumOf { it.text.length } <= 2_000_000) { "本次文字过多，请分批选择页面" }
            return SourceBundle(j.optString("title", "文字片段"), j.optString("documentId"), pages)
        }
    }
}

data class IntelligenceResult(
    val id: String,
    val source: SourceBundle,
    val mode: String,
    val operation: String,
    val question: String,
    val answer: String,
    val pages: List<Int>,
    val unknownPages: List<Int>,
    val model: String = "",
    val endpoint: String = "",
    val created: Long = System.currentTimeMillis(),
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("source", source.json())
            .put("sourceSha256", source.sha256)
            .put("mode", mode)
            .put("operation", operation)
            .put("question", question)
            .put("answer", answer)
            .put("pages", JSONArray(pages))
            .put("unknownPages", JSONArray(unknownPages))
            .put("model", model)
            .put("endpoint", endpoint)
            .put("created", created)

    fun markdown() = buildString {
        append("# ${source.title} · $operation\n\n$answer\n\n")
        append("模式：$mode\n\n原文快照 SHA256：${source.sha256}\n\n")
        source.pages.forEach { page ->
            append("## 原文 · 第 ${page.page} 页\n\n${page.text}\n\n")
            if (source.documentId.isNotEmpty())
                append("[查看当前原页](paperforge://document/${source.documentId}?page=${page.page})\n\n")
        }
    }

    companion object {
        fun decode(j: JSONObject): IntelligenceResult {
            fun ints(key: String) =
                j.optJSONArray(key)?.let { a -> List(a.length()) { a.getInt(it) } } ?: emptyList()
            val frozen = SourceBundle.decode(j.getJSONObject("source"))
            require(j.optString("sourceSha256", frozen.sha256) == frozen.sha256) { "原文快照校验未通过" }
            return IntelligenceResult(
                j.getString("id"),
                frozen,
                j.getString("mode"),
                j.getString("operation"),
                j.optString("question"),
                j.getString("answer"),
                ints("pages"),
                ints("unknownPages"),
                j.optString("model"),
                j.optString("endpoint"),
                j.getLong("created"),
            )
        }
    }
}

class IntelligencePrefs(context: Context) {
    private val secure = NetworkPreferences(context)
    val store = secure.store
    var enabled: Boolean
        get() = store.getBoolean("intelligenceEnabled", false)
        set(value) {
            store.edit().putBoolean("intelligenceEnabled", value).commit()
        }

    var endpoint: String
        get() = store.getString("intelligenceEndpoint", "")!!
        set(value) {
            store.edit().putString("intelligenceEndpoint", value.trim()).apply()
        }

    var model: String
        get() = store.getString("intelligenceModel", "")!!
        set(value) {
            store.edit().putString("intelligenceModel", value.trim()).apply()
        }

    var instruction: String
        get() = store.getString("intelligenceInstruction", "")!!
        set(value) {
            store.edit().putString("intelligenceInstruction", value).apply()
        }

    fun key() = secure.secret("intelligenceKey")

    fun key(value: String) = secure.secret("intelligenceKey", value)
}

data class IntelligenceHistory(
    val id: String,
    val title: String,
    val operation: String,
    val created: Long,
)

object IntelligenceFiles {
    private fun root(context: Context) =
        File(context.filesDir, "intelligence/results").apply { mkdirs() }

    fun save(context: Context, result: IntelligenceResult) {
        val file = AtomicFile(File(root(context), "${result.id}.json"))
        val stream = file.startWrite()
        try {
            stream.write(result.json().toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Throwable) {
            file.failWrite(stream)
            throw e
        }
    }

    fun history(context: Context) =
        root(context)
            .listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
            .mapNotNull {
                runCatching {
                        val j = JSONObject(it.readText())
                        IntelligenceHistory(
                            j.getString("id"),
                            j.getJSONObject("source").getString("title"),
                            j.getString("operation"),
                            j.getLong("created"),
                        )
                    }
                    .getOrNull()
            }
            .sortedByDescending { it.created }

    fun read(context: Context, id: String): IntelligenceResult {
        require(UUID.fromString(id).toString() == id)
        return IntelligenceResult.decode(JSONObject(File(root(context), "$id.json").readText()))
    }

    fun remove(context: Context, id: String) {
        require(UUID.fromString(id).toString() == id)
        AtomicFile(File(root(context), "$id.json")).delete()
    }
}
