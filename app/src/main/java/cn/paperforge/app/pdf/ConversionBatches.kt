package cn.paperforge.app.pdf

import android.net.Uri
import androidx.room.withTransaction
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.*
import cn.paperforge.app.ocr.OcrRequest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class BatchPreset(val kind: String = "TEXT_PDF", val options: String = "{}") {
    val label: String
        get() = kinds[kind] ?: kind

    fun json() = JSONObject().put("kind", kind).put("options", JSONObject(options))

    fun summary(): String {
        val j = JSONObject(options)
        return when (kind) {
            "OFFICE" -> "Office → ${j.optString("format", "pdf").uppercase()}"
            "OFFICE_IMAGES" ->
                "幻灯片 / Office → ${j.optString("format", "png").uppercase()} 页包 · ${j.optInt("edge",2200)} px"
            "LAYOUT" ->
                "特殊文档 → ${if(j.optString("target", "PDF") == "IMAGES") "PNG 页包" else "PDF"} · ${j.optInt("dpi", 200)} DPI"
            "MARKUP" -> "结构文本 → ${j.optString("target", "HTML")}"
            "TABLE_ANALYZE" -> "表格 → 校对 → ${if(j.optBoolean("outputCsv")) "CSV" else "Excel"}"
            "PDF_TOOL" -> "PDF → ${pdfModes[j.optString("mode", "txt")] ?: "文字"}"
            "IMAGES" ->
                "图片 → ${j.optString("format", "jpg").uppercase()} · ${j.optInt("edge", 4096)} px"
            else ->
                "文字 → PDF · ${j.optString("paper", "A4")} · ${j.optDouble("fontSize", 12.0).toInt()} pt"
        }
    }

    companion object {
        val kinds =
            linkedMapOf(
                "TEXT_PDF" to "文字排版",
                "PDF_TOOL" to "PDF 转换",
                "IMAGES" to "图片转换",
                "OFFICE" to "Office",
                "OFFICE_IMAGES" to "幻灯片图片包",
                "LAYOUT" to "特殊文档 / 漫画",
                "MARKUP" to "结构文本 / EPUB",
                "TABLE_ANALYZE" to "表格识别",
            )
        val pdfModes =
            linkedMapOf(
                "txt" to "TXT",
                "md" to "Markdown",
                "html" to "HTML",
                "docx-layout" to "Word 版面",
                "docx" to "Word 正文",
                "docx-image" to "Word 外观",
                "pdf-pptx" to "PowerPoint",
                "pdf-pptx-native" to "PowerPoint 原生对象",
                "images-jpg" to "JPG 页包",
                "images-png" to "PNG 页包",
                "images-webp" to "WebP 页包",
                "compress" to "压缩 PDF",
                "optimize" to "无损优化",
                "searchable" to "可搜索 PDF",
                "flatten" to "整页重绘",
                "split-each" to "逐页 PDF 包",
                "extract-images" to "提取嵌图",
            )

        fun from(input: JSONObject) =
            BatchPreset(
                input.optString("batchKind", input.optString("kind", "TEXT_PDF")),
                input.optString("options", "{}"),
            )
    }
}

/** Each member is a normal durable conversion task; grouping adds no competing worker. */
object BatchConversions {
    fun input(row: LocalTaskRow) = JSONObject(row.payload).getJSONObject("input")

    fun batchId(row: LocalTaskRow): String =
        if (row.type in setOf("CONVERT", "PDF")) input(row).optString("batchId") else ""

    fun member(row: LocalTaskRow) =
        batchId(row).isNotBlank() && !input(row).optBoolean("batchRelated")

    fun draft(row: LocalTaskRow) = input(row).optString("kind") == "BATCH_ITEM"

    fun source(row: LocalTaskRow): File? =
        input(row)
            .optJSONArray("inputs")
            ?.optString(0)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile }

    fun editable(row: LocalTaskRow) =
        row.state !in LocalTaskRow.active && row.state != LocalTaskState.SUCCEEDED

    fun retryable(row: LocalTaskRow) =
        !draft(row) &&
            row.state in
                setOf(LocalTaskState.FAILED, LocalTaskState.CANCELLED, LocalTaskState.PARTIAL)

    suspend fun add(
        repo: Repository,
        uris: List<Uri>,
        preset: BatchPreset,
        batch: String = UUID.randomUUID().toString(),
    ): String {
        require(preset.kind in BatchPreset.kinds)
        val app = repo.context.applicationContext as PaperForgeApp
        val request = OcrRequest.current(app.ocrEngine.id).json()
        val offset = repo.dao.allTasks().filter { member(it) && batchId(it) == batch }.size
        for ((index, uri) in uris.withIndex()) {
            currentCoroutineContext().ensureActive()
            var retained: Pair<File, String>? = null
            var error: String? = null
            try {
                // Per-file retention: a provider failure cannot delete earlier successful inputs.
                retained = ConversionInputs.retain(repo, listOf(uri)).single()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.message ?: "无法读取文件"
            }
            val name =
                retained?.second
                    ?: runCatching { repo.displayName(uri) }.getOrDefault("无法读取的文件 ${index + 1}")
            val input =
                JSONObject()
                    .put("kind", "BATCH_ITEM")
                    .put("draft", true)
                    .put("batchId", batch)
                    .put("batchIndex", offset + index)
                    .put("batchKind", preset.kind)
                    .put("name", name)
                    .put("options", preset.options)
                    .put("ocrRequest", JSONObject(request.toString()))
                    .put("inputs", JSONArray(listOfNotNull(retained?.first?.path)))
                    .put("names", JSONArray(listOfNotNull(retained?.second)))
            val row = DocumentJobs.prepare(repo, "CONVERT", JobInput(input))
            repo.dao.putTask(
                row.copy(
                    state = if (error == null) LocalTaskState.PARTIAL else LocalTaskState.FAILED,
                    detail = if (error == null) "待提交" else "请重新选择原文件",
                    error = error,
                )
            )
        }
        return batch
    }

    suspend fun configure(repo: Repository, id: String, preset: BatchPreset) =
        repo.database.withTransaction {
            require(preset.kind in BatchPreset.kinds)
            val row = checkNotNull(repo.dao.task(id))
            require(editable(row)) { "处理中或已完成的项目保留原参数" }
            val payload = JSONObject(row.payload)
            val input = payload.getJSONObject("input")
            input
                .put("kind", "BATCH_ITEM")
                .put("draft", true)
                .put("batchKind", preset.kind)
                .put("options", preset.options)
            input.remove("document")
            repo.dao.putTask(
                row.copy(
                    type = "CONVERT",
                    documentId = null,
                    payload = payload.toString(),
                    state =
                        if (source(row) == null) LocalTaskState.FAILED else LocalTaskState.PARTIAL,
                    error = if (source(row) == null) row.error else null,
                    detail = if (source(row) == null) "请重新选择原文件" else "待提交",
                    updated = System.currentTimeMillis(),
                )
            )
        }

    suspend fun replace(repo: Repository, id: String, uri: Uri) {
        val old = checkNotNull(repo.dao.task(id))
        require(editable(old)) { "请先停止该项目" }
        val retained = ConversionInputs.retain(repo, listOf(uri)).single()
        try {
            repo.database.withTransaction {
                val row = checkNotNull(repo.dao.task(id))
                require(editable(row)) { "项目已开始处理" }
                val payload = JSONObject(row.payload)
                val input = payload.getJSONObject("input")
                input
                    .put("kind", "BATCH_ITEM")
                    .put("draft", true)
                    .put("name", retained.second)
                    .put("inputs", JSONArray(listOf(retained.first.path)))
                    .put("names", JSONArray(listOf(retained.second)))
                input.remove("edited")
                input.remove("document")
                payload.put("checkpoint", JSONObject())
                // Retain old originals/checkpoints as explicit history resources, even on
                // replacement.
                val history =
                    row.resourceEntries().map {
                        it.copy(id = "prior-${UUID.randomUUID()}", role = "conversion-prior-input")
                    }
                repo.dao.putTask(
                    row.copy(
                        type = "CONVERT",
                        documentId = null,
                        title = "${retained.second} · 批量转换",
                        payload = payload.toString(),
                        resources =
                            DocumentResources.encode(
                                    history +
                                        PageResource("input-0", "job-input", retained.first.path)
                                )
                                .toString(),
                        state = LocalTaskState.PARTIAL,
                        error = null,
                        progress = 0,
                        detail = "已换原件，待提交",
                        workId = UUID.randomUUID().toString(),
                        updated = System.currentTimeMillis(),
                    )
                )
            }
        } catch (failure: Throwable) {
            retained.first.delete()
            throw failure
        }
    }

    suspend fun submit(repo: Repository, id: String) {
        val row = checkNotNull(repo.dao.task(id))
        require(draft(row) && editable(row)) { "该项目已提交" }
        val original = source(row) ?: error("原件不可用，请重新选择该文件")
        val payload = JSONObject(row.payload)
        val input = payload.getJSONObject("input")
        val kind = input.getString("batchKind")
        require(kind in BatchPreset.kinds)
        val resources = row.resourceEntries().toMutableList()
        if (kind == "TEXT_PDF") {
            val prior =
                input
                    .optString("edited")
                    .takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?.takeIf { it.isFile }
            val frozen =
                prior
                    ?: File(original.parentFile, "${UUID.randomUUID()}.txt").also {
                        val text =
                            LocalConversions.readText(
                                repo.context,
                                Uri.fromFile(original),
                                input.getString("name"),
                            )
                        val atomic = android.util.AtomicFile(it)
                        val out = atomic.startWrite()
                        try {
                            out.write(text.toByteArray(Charsets.UTF_8))
                            atomic.finishWrite(out)
                        } catch (failure: Throwable) {
                            atomic.failWrite(out)
                            throw failure
                        }
                    }
            input
                .put("edited", frozen.path)
                .put("inputs", JSONArray(listOf(original.path, frozen.path)))
                .put("names", JSONArray(listOf(input.getString("name"), "frozen.txt")))
            resources.removeAll { it.id == "input-1" }
            resources += PageResource("input-1", "job-input", frozen.path)
        }
        input.put("kind", kind).put("draft", false)
        val pdfDocument =
            if (kind == "PDF_TOOL") {
                val mode = JSONObject(input.getString("options")).optString("mode", "txt")
                require(mode in BatchPreset.pdfModes) { "请选择 PDF 转换方式" }
                input.put("mode", mode)
                repo.registerPdf(original, input.getString("name").substringBeforeLast('.')).also {
                    input.put("document", it.id)
                }
            } else null
        val next =
            repo.database.withTransaction {
                val latest = checkNotNull(repo.dao.task(id))
                require(latest.payload == row.payload && latest.state !in LocalTaskRow.active) {
                    "参数已变化，请再次提交"
                }
                latest
                    .copy(
                        type = if (kind == "PDF_TOOL") "PDF" else "CONVERT",
                        documentId = pdfDocument?.id,
                        title =
                            "${input.getString("name")} · ${BatchPreset(kind, input.getString("options")).summary()}",
                        payload = payload.toString(),
                        resources = DocumentResources.encode(resources).toString(),
                        state = LocalTaskState.QUEUED,
                        progress = 0,
                        detail = "等待处理",
                        error = null,
                        workId = UUID.randomUUID().toString(),
                        updated = System.currentTimeMillis(),
                    )
                    .also { repo.dao.putTask(it) }
            }
        DocumentJobs.schedule(repo, next)
    }

    /** Process failures independently and leave already completed members untouched. */
    suspend fun runPending(repo: Repository, batch: String, failedOnly: Boolean = false): Int {
        var count = 0
        val rows =
            repo.dao
                .allTasks()
                .filter { member(it) && batchId(it) == batch }
                .sortedBy { input(it).optInt("batchIndex") }
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            val ready = draft(row) && editable(row) && source(row) != null
            if (
                if (failedOnly) retryable(row) || (row.state == LocalTaskState.FAILED && ready)
                else ready
            ) {
                try {
                    if (draft(row)) submit(repo, row.id) else DocumentJobs.retry(repo, row)
                    count++
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    repo.dao.taskState(
                        row.id,
                        LocalTaskState.FAILED,
                        row.progress,
                        "该项未提交，可单独处理",
                        failure.message,
                    )
                }
            }
        }
        return count
    }

    fun copyGroup(row: LocalTaskRow, target: JSONObject) =
        target.apply {
            val source = input(row)
            if (source.optString("batchId").isNotBlank()) {
                put("batchId", source.getString("batchId")).put("batchRelated", true)
                put("batchSourceTask", source.optString("batchSourceTask").ifBlank { row.id })
                source.optJSONObject("ocrRequest")?.let {
                    put("ocrRequest", JSONObject(it.toString()))
                }
            }
        }
}

class ConversionBatches(
    private val repo: Repository,
    private val scope: CoroutineScope,
    private val message: suspend (String) -> Unit,
) {
    val selected = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    private val lock = Mutex()

    private fun action(block: suspend () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                lock.withLock { block() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                message(failure.message ?: "批量操作未完成")
            } finally {
                busy.value = false
            }
        }
    }

    fun add(uris: List<Uri>, preset: BatchPreset) = action {
        if (uris.isEmpty()) return@action
        selected.value =
            BatchConversions.add(
                repo,
                uris,
                preset,
                selected.value.ifBlank { UUID.randomUUID().toString() },
            )
    }

    fun configure(ids: List<String>, preset: BatchPreset) = action {
        ids.forEach { BatchConversions.configure(repo, it, preset) }
    }

    fun replace(id: String, uri: Uri) = action { BatchConversions.replace(repo, id, uri) }

    fun run(failedOnly: Boolean = false) = action {
        val count = BatchConversions.runPending(repo, selected.value, failedOnly)
        message("已提交 $count 项，结果会逐项保留")
    }

    fun retry(id: String) = action {
        val row = checkNotNull(repo.dao.task(id))
        if (BatchConversions.draft(row)) BatchConversions.submit(repo, id)
        else DocumentJobs.retry(repo, row)
    }
}
