package cn.paperforge.app.data

import androidx.room.withTransaction
import cn.paperforge.app.model.fileStemPrefix
import cn.paperforge.app.model.safeFileName
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class NamingOptions(
    val pattern: String = "{date:yyyyMMdd}-{type}-{n:03}",
    val start: Int = 1,
    val fields: Map<String, String> = linkedMapOf("项目" to ""),
) {
    fun encode() =
        JSONObject().put("pattern", pattern).put("start", start).put("fields", JSONObject(fields))

    companion object {
        fun decode(j: JSONObject) =
            NamingOptions(
                j.optString("pattern", "{date:yyyyMMdd}-{type}-{n:03}"),
                j.optInt("start", 1),
                j.optJSONObject("fields")?.let { f ->
                    f.keys().asSequence().associateWith { f.getString(it) }
                } ?: mapOf("项目" to ""),
            )
    }
}

data class NameChange(
    val documentId: String,
    val before: String,
    val after: String,
    val collision: Boolean = false,
) {
    fun encode() =
        JSONObject()
            .put("documentId", documentId)
            .put("before", before)
            .put("after", after)
            .put("collision", collision)

    companion object {
        fun decode(j: JSONObject) =
            NameChange(
                j.getString("documentId"),
                j.getString("before"),
                j.getString("after"),
                j.optBoolean("collision"),
            )
    }
}

object NameTemplate {
    private val token = Regex("\\{\\{|\\}\\}|\\{([^{}]+)\\}")

    fun render(
        doc: DocumentRow,
        options: NamingOptions,
        ordinal: Int,
        time: LocalDateTime,
    ): String {
        require(options.pattern.isNotBlank()) { "请填写命名模板" }
        require(ordinal >= 0) { "起始序号不能为负数" }
        val name =
            token.replace(options.pattern) { match ->
                if (match.value == "{{") return@replace "{"
                if (match.value == "}}") return@replace "}"
                val parts = match.groupValues[1].split(':', limit = 2)
                val argument = parts.getOrNull(1)
                when (parts[0]) {
                    "name" -> doc.title
                    "type" -> if (doc.kind == "scan") "扫描" else doc.kind.uppercase(Locale.ROOT)
                    "folder" -> doc.folder.substringAfterLast('/')
                    "date" ->
                        try {
                            time.format(
                                DateTimeFormatter.ofPattern(
                                    argument ?: "yyyy-MM-dd",
                                    Locale.getDefault(),
                                )
                            )
                        } catch (e: IllegalArgumentException) {
                            error("日期格式无效")
                        }
                    "n" -> {
                        val padding =
                            argument?.toIntOrNull()
                                ?: if (argument == null) 0 else error("序号格式应为 {n:03}")
                        require(padding in 0..8) { "序号补零位数应在 0 到 8 之间" }
                        ordinal.toString().padStart(padding, '0')
                    }
                    "field" ->
                        if (argument == null) options.fields.values.firstOrNull().orEmpty()
                        else options.fields[argument] ?: error("没有名为“$argument”的字段")
                    else -> error("不支持的变量：${parts[0]}")
                }
            }
        return safeFileName(name)
    }

    fun preview(
        selected: List<DocumentRow>,
        all: List<DocumentRow>,
        options: NamingOptions,
        time: LocalDateTime,
    ): List<NameChange> {
        require(options.start in 0..99999999) { "起始序号超出范围" }
        val ids = selected.map { it.id }.toSet()
        val used =
            all.filter { it.listed && it.trashedAt == null && it.id !in ids }
                .map { it.title.lowercase(Locale.ROOT) }
                .toMutableSet()
        return selected.mapIndexed { index, doc ->
            val base = render(doc, options, options.start + index, time)
            var candidate = base
            var count = 1
            while (!used.add(candidate.lowercase(Locale.ROOT))) {
                count++
                val suffix = " ($count)"
                candidate =
                    fileStemPrefix(
                            base,
                            200 - suffix.toByteArray(Charsets.UTF_8).size,
                            100 - suffix.length,
                        )
                        .trimEnd() + suffix
            }
            NameChange(doc.id, doc.title, candidate, count > 1)
        }
    }
}

suspend fun Repository.renameBatch(changes: List<NameChange>, options: NamingOptions): Int =
    withContext(Dispatchers.IO) {
        if (changes.isEmpty()) return@withContext 0
        val preferences = LibraryPreferences(context)
        val previous = preferences.renameUndo()
        var wroteIntent = false
        try {
            database.withTransaction {
                changes.forEach { row ->
                    require(dao.document(row.documentId)?.title == row.before) { "文档名称已变化，请查看最新预览" }
                }
                preferences.renameUndo(changes)
                wroteIntent = true
                val now = System.currentTimeMillis()
                changes.forEach { row -> dao.rename(row.documentId, row.after, now) }
            }
        } catch (e: Exception) {
            if (wroteIntent) withContext(NonCancellable) { preferences.renameUndo(previous) }
            throw e
        }
        preferences.naming(options)
        changes.forEach { OutputNames.remember(this@renameBatch, it.documentId, it.after) }
        changes.size
    }

data class RenameUndoResult(val restored: Int, val preserved: Int)

suspend fun Repository.undoBatchRename(): RenameUndoResult =
    withContext(Dispatchers.IO) {
        val preferences = LibraryPreferences(context)
        val changes = preferences.renameUndo()
        val result =
            database.withTransaction {
                var restored = 0
                var preserved = 0
                changes.forEach { row ->
                    val doc = dao.document(row.documentId)
                    if (doc?.title == row.after) {
                        dao.rename(row.documentId, row.before, System.currentTimeMillis())
                        restored++
                    } else preserved++
                }
                RenameUndoResult(restored, preserved)
            }
        preferences.renameUndo(emptyList())
        result
    }
