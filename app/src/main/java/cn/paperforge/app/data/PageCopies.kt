package cn.paperforge.app.data

import androidx.room.withTransaction
import cn.paperforge.app.model.OcrLayout
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

data class PageCopyResult(
    val document: DocumentRow,
    val firstPosition: Int,
    val firstPageId: String,
    val pages: Int,
)

data class PageOrigin(
    val documentId: String?,
    val pageId: String?,
    val title: String,
    val ordinal: Int,
    val sha256: String,
)

fun PageRow.origins(): List<PageOrigin> {
    val rows = PageResources.metadata(this).optJSONArray("pageOrigins") ?: return emptyList()
    fun JSONObject.optional(key: String) =
        optString(key).takeUnless { it.isBlank() || it == "null" }
    return (0 until rows.length()).map {
        rows.getJSONObject(it).let { j ->
            PageOrigin(
                j.optional("documentId"),
                j.optional("pageId"),
                j.optString("title"),
                j.optInt("ordinal"),
                j.optString("sha256"),
            )
        }
    }
}

suspend fun Repository.copyPages(
    sourceId: String,
    pageIds: Set<String>,
    targetId: String?,
    newTitle: String = "",
    move: Boolean = false,
): PageCopyResult =
    withContext(Dispatchers.IO) {
        require(pageIds.isNotEmpty()) { "请选择页面" }
        require(sourceId != targetId) { "请选择其他文档" }
        fun relevantTask(it: LocalTaskRow) =
            it.type == "CAPTURE" &&
                it.state in LocalTaskRow.active &&
                (targetId != null && it.documentId == targetId ||
                    it.documentId == sourceId &&
                        (move || it.pageId in pageIds || it.targetPageId in pageIds))
        dao.observeTasks().first { tasks -> tasks.none(::relevantTask) }
        val source = dao.document(sourceId) ?: error("来源文档不存在")
        require(source.kind == "scan" && source.trashedAt == null) { "来源扫描文档已移除" }
        val sourcePages = dao.pages(sourceId)
        val originals = sourcePages.filter { it.id in pageIds }
        require(originals.size == pageIds.size) { "所选页面已变化，请重新选择" }
        val existing = targetId?.let { dao.document(it) ?: error("目标文档不存在") }
        require(existing == null || existing.kind == "scan" && existing.trashedAt == null) {
            "请选择扫描文档"
        }
        val proposed =
            existing
                ?: DocumentRow(
                        UUID.randomUUID().toString(),
                        newTitle.ifBlank { source.title + " 副本" },
                        lastOpened = System.currentTimeMillis(),
                    )
                    .let { row ->
                        row.copy(
                            title =
                                NameTemplate.preview(
                                        listOf(row),
                                        dao.allDocuments(),
                                        NamingOptions("{name}"),
                                        LocalDateTime.now(),
                                    )
                                    .single()
                                    .after
                        )
                    }
        val directory = folder(proposed.id)
        val files = mutableListOf<File>()
        val paths = mutableMapOf<String, String>()
        val hashes = mutableMapOf<String, String>()
        var committed = false
        try {
            fun copy(path: String): String =
                paths.getOrPut(path) {
                    val input = File(path)
                    val target =
                        File(directory, "${UUID.randomUUID()}.${input.extension.ifBlank {"bin"}}")
                    files += target
                    val digest = MessageDigest.getInstance("SHA-256")
                    DigestInputStream(input.inputStream(), digest).use { stream ->
                        FileOutputStream(target).use { output ->
                            stream.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    hashes[path] =
                        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    target.path
                }
            val prepared =
                originals.map { page ->
                    currentCoroutineContext().ensureActive()
                    val remapped = PageResources.remap(page, ::copy)
                    val json = PageResources.metadata(remapped)
                    json.remove("captureJob")
                    val origins =
                        json.optJSONArray("pageOrigins")
                            ?: JSONArray().also { json.put("pageOrigins", it) }
                    origins.put(
                        JSONObject()
                            .put("documentId", source.id)
                            .put("pageId", page.id)
                            .put("title", source.title)
                            .put("ordinal", sourcePages.indexOfFirst { it.id == page.id } + 1)
                            .put("sha256", hashes.getValue(page.original))
                            .put("operation", if (move) "move" else "copy")
                            .put("created", System.currentTimeMillis())
                    )
                    val ocr =
                        page.ocrLayout?.let(OcrLayout::decode)?.takeIf {
                            it.sourceRevision == page.revision
                        }
                    remapped.copy(
                        id = UUID.randomUUID().toString(),
                        documentId = proposed.id,
                        revision = 0,
                        ocrLayout = ocr?.copy(sourceRevision = 0)?.encode(),
                        processingMetadata = json.toString(),
                    )
                }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val result =
                    database.withTransaction {
                        require(
                            dao.document(sourceId)?.trashedAt == null &&
                                dao.document(sourceId) != null
                        ) {
                            "来源文档已移除"
                        }
                        require(dao.allTasks().none(::relevantTask)) { "页面正在处理，请稍后重试" }
                        val latestSource = dao.pages(sourceId)
                        require(
                            originals.all { old ->
                                latestSource.firstOrNull { it.id == old.id } == old
                            }
                        ) {
                            "来源页已修改，请重新选择"
                        }
                        val doc =
                            if (existing == null) proposed.also { dao.putDocument(it) }
                            else
                                dao.document(existing.id)?.takeIf {
                                    it.trashedAt == null && it.kind == "scan"
                                } ?: error("目标文档已变化")
                        val current = dao.pages(doc.id)
                        current.forEachIndexed { index, page ->
                            if (page.position != index) dao.updatePage(page.copy(position = index))
                        }
                        val first = current.size
                        val added =
                            prepared.mapIndexed { index, page ->
                                page.copy(position = first + index)
                            }
                        dao.clearRedo(doc.id)
                        val before =
                            HistoryState.delta(
                                if (move)
                                    originals.map { old ->
                                        old.copy(
                                            position = latestSource.indexOfFirst { it.id == old.id }
                                        )
                                    }
                                else emptyList(),
                                move,
                            )
                        val after = HistoryState.delta(added, move)
                        val historyKey =
                            if (move) "transfer:${UUID.randomUUID()}" else added.first().id
                        if (move) {
                            dao.clearRedo(sourceId)
                            dao.addHistory(
                                EditHistory(
                                    documentId = sourceId,
                                    pageId = historyKey,
                                    beforeJson = before,
                                    afterJson = after,
                                )
                            )
                            originals.forEach { dao.deletePage(it.id) }
                            latestSource
                                .filter { it.id !in pageIds }
                                .forEachIndexed { index, page ->
                                    dao.putPage(page.copy(position = index))
                                }
                            dao.touch(sourceId, System.currentTimeMillis())
                        }
                        dao.addHistory(
                            EditHistory(
                                documentId = doc.id,
                                pageId = historyKey,
                                beforeJson = before,
                                afterJson = after,
                            )
                        )
                        added.forEach { dao.putPage(it) }
                        val now = System.currentTimeMillis()
                        dao.touch(doc.id, now)
                        dao.opened(doc.id, now)
                        PageCopyResult(
                            doc.copy(modified = now, lastOpened = now),
                            first,
                            added.first().id,
                            added.size,
                        )
                    }
                committed = true
                result
            }
        } finally {
            if (!committed)
                withContext(NonCancellable) {
                    files.forEach { it.delete() }
                    if (existing == null) directory.delete()
                }
        }
    }
