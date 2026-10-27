package cn.paperforge.app.data

import androidx.room.withTransaction
import androidx.work.*
import cn.paperforge.app.pdf.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.*

class JobInput(val json: JSONObject) {
    fun getString(key: String): String? =
        json.optString(key).takeUnless { it.isBlank() || it == "null" }

    fun getInt(key: String, fallback: Int) = json.optInt(key, fallback)

    fun getBoolean(key: String, fallback: Boolean) = json.optBoolean(key, fallback)

    fun getStringArray(key: String): Array<String>? =
        json.optJSONArray(key)?.let { a -> Array(a.length()) { a.getString(it) } }

    companion object {
        fun from(data: Data) =
            JobInput(
                JSONObject().apply {
                    data.keyValueMap.forEach { (key, value) -> put(key, JSONObject.wrap(value)) }
                }
            )
    }
}

data class JobHandle(val row: LocalTaskRow, val input: JobInput, val completed: Boolean = false) {
    val result: File?
        get() =
            row.resourceEntries()
                .firstOrNull { it.id == "result" }
                ?.path
                ?.let(::File)
                ?.takeIf { it.isFile }
}

/** Room owns intent/checkpoints; WorkManager receives only the stable task identifier. */
object DocumentJobs {
    val types = setOf("PDF", "SCAN_EXPORT", "OCR", "SAVE", "CONVERT", "AUTO_EXPORT")

    private fun title(type: String, input: JobInput, source: String) =
        when (type) {
            "AUTO_EXPORT" -> "$source · 自动导出"
            "SCAN_EXPORT" -> "$source · 导出 ${input.getString("format") ?: "PDF"}"
            "OCR" -> "$source · 全文识别"
            "SAVE" -> "$source · 保存到所选位置"
            "CONVERT" ->
                "$source · ${when(input.getString("kind")) { "TEXT_PDF" -> "文字转 PDF"
 "TABLE_ANALYZE" -> "识别表格"
 "TABLE_EXPORT" -> "导出表格"
 "PDF_DIFF" -> "PDF 差异比较"
 "OFFICE" -> "手机 Office 转换"
 "OFFICE_IMAGES" -> "Office / 幻灯片图片包"
 "RECIPE" -> "工作流配方"
 "TABLE_REVIEW" -> "表格草稿"
 "BATCH_ITEM" -> "批量转换待提交"
 "CONVERSION_PREVIEW" -> "原件 / 结果对照"
 "LAYOUT" -> "OFD / DjVu 转换"
                            "MARKUP" -> if (JSONObject(input.getString("options") ?: "{}").optString("target") == "EPUB") "生成 EPUB 电子书" else "文档结构转换"
 else -> "图片转换" }}"
            else -> "$source · ${modeName(input.getString("mode"))}"
        }

    private fun modeName(mode: String?) =
        mapOf(
            "assemble" to "页面组合",
            "merge" to "合并 PDF",
            "extract" to "抽取页面",
            "organize" to "整理页面",
            "crop" to "裁边",
            "blank" to "插入空白页",
            "nup2" to "双页合一",
            "nup4" to "四页合一",
            "watermark" to "添加水印",
            "number" to "页码",
            "metadata" to "文档属性",
            "optimize" to "无损优化",
            "images-jpg" to "导出 JPG",
            "images-png" to "导出 PNG",
            "images-webp" to "导出 WebP",
            "long-image" to "导出长图",
            "extract-images" to "提取图片",
            "searchable" to "添加文字层",
            "docx-layout" to "转换 Word",
            "pdf-pptx" to "转换 PowerPoint",
            "pdf-pptx-native" to "PowerPoint 原生对象",
            "txt" to "提取文字",
            "md" to "导出 Markdown",
            "html" to "导出 HTML",
            "docx" to "导出 Word 正文",
            "docx-image" to "Word 外观副本",
            "compress" to "压缩 PDF",
            "flatten" to "整页重绘",
            "split-each" to "逐页拆分",
            "marks" to "保存批注",
            "redact-semantic" to "语义文字脱敏",
            "redact-raster" to "整份栅格脱敏",
        )[mode] ?: "PDF 处理"

    suspend fun prepare(
        repo: Repository,
        type: String,
        input: JobInput,
        workId: UUID = UUID.randomUUID(),
    ): LocalTaskRow {
        val needsOcr =
            type == "AUTO_EXPORT" ||
                type == "OCR" ||
                type == "SCAN_EXPORT" && input.getBoolean("searchable", false) ||
                type == "PDF" && input.getString("mode") == "searchable" ||
                type == "CONVERT" &&
                    (input.getString("kind") == "RECIPE" ||
                        input.getString("kind") == "TABLE_ANALYZE" ||
                        input.getString("kind") == "MARKUP" &&
                            JSONObject(input.getString("options") ?: "{}").optString("target") ==
                                "EPUB")
        if (needsOcr && !input.json.has("ocrRequest")) {
            val app = repo.context.applicationContext as cn.paperforge.app.PaperForgeApp
            val request =
                cn.paperforge.app.ocr.OcrRequest.current(
                    app.ocrEngine.id,
                    input.getBoolean(
                        "chinese",
                        cn.paperforge.app.UserSettings.string("ocrLanguage", "CHINESE") != "LATIN",
                    ),
                )
            input.json.put("ocrRequest", request.json())
        }
        if (type == "SCAN_EXPORT")
            input.json.put(
                "resolution",
                "DPI:${cn.paperforge.app.imaging.ScanConfig.dpi(input.getString("resolution") ?: "STANDARD")}",
            )
        val doc = input.getString("document")?.let { repo.dao.document(it) }
        val files = mutableListOf<PageResource>()
        fun resource(id: String, path: String?) {
            if (!path.isNullOrBlank() && File(path).isFile)
                files += PageResource(id, "job-input", path)
        }
        resource("source", doc?.pdfPath)
        resource("save-source", input.getString("path"))
        input.getStringArray("inputs")?.forEachIndexed { index, path ->
            resource("input-$index", path)
        }
        input.getStringArray("sources")?.forEachIndexed { index, path ->
            resource("source-$index", path)
        }
        val options = JSONObject(input.getString("options") ?: "{}")
        resource("manifest", options.optString("manifest"))
        options
            .optString("manifest")
            .takeIf { it.isNotBlank() }
            ?.let { path ->
                val plan = AssemblyPlan.decode(JSONObject(File(path).readText()))
                plan.sources.forEachIndexed { index, source ->
                    resource(
                        "assembly-source-$index",
                        repo.dao.document(source.documentId)?.pdfPath,
                    )
                }
            }
        resource("marks", options.optString("marksFile"))
        val row =
            LocalTaskRow(
                UUID.randomUUID().toString(),
                type,
                doc?.id,
                title(
                    type,
                    input,
                    doc?.title
                        ?: input.getString("name")
                        ?: input.getString("path")?.let { File(it).nameWithoutExtension }
                        ?: options.optString("title", "独立任务"),
                ),
                null,
                null,
                0,
                LocalTaskState.QUEUED,
                detail = "等待处理",
                payload =
                    JSONObject()
                        .put("input", input.json)
                        .put("checkpoint", JSONObject())
                        .toString(),
                resources = DocumentResources.encode(files).toString(),
                workId = workId.toString(),
            )
        repo.dao.putTask(row)
        return row
    }

    suspend fun enqueue(repo: Repository, type: String, input: Data): UUID =
        enqueue(repo, type, JobInput.from(input))

    suspend fun enqueue(repo: Repository, type: String, input: JobInput): UUID =
        withContext(Dispatchers.IO) {
            val row = prepare(repo, type, input)
            schedule(repo, row)
            UUID.fromString(row.workId)
        }

    fun schedule(repo: Repository, row: LocalTaskRow) {
        val id = UUID.fromString(checkNotNull(row.workId))
        val data = workDataOf("task" to row.id)
        val request =
            when (row.type) {
                "PDF" ->
                    OneTimeWorkRequestBuilder<PdfJobWorker>().setId(id).setInputData(data).build()
                "SCAN_EXPORT" ->
                    OneTimeWorkRequestBuilder<ExportWorker>().setId(id).setInputData(data).build()
                "OCR" ->
                    OneTimeWorkRequestBuilder<OcrDocumentWorker>()
                        .setId(id)
                        .setInputData(data)
                        .build()
                "SAVE" ->
                    OneTimeWorkRequestBuilder<SaveDocumentWorker>()
                        .setId(id)
                        .setInputData(data)
                        .build()
                "CONVERT" ->
                    OneTimeWorkRequestBuilder<ConversionJobWorker>()
                        .setId(id)
                        .setInputData(data)
                        .build()
                "AUTO_EXPORT" ->
                    OneTimeWorkRequestBuilder<AutoExportWorker>()
                        .setId(id)
                        .setInputData(data)
                        .setInitialDelay(
                            JSONObject(row.payload)
                                .getJSONObject("input")
                                .optInt("delaySeconds", 5)
                                .toLong(),
                            java.util.concurrent.TimeUnit.SECONDS,
                        )
                        .build()
                else -> error("未知任务类型")
            }
        WorkManager.getInstance(repo.context)
            .enqueueUniqueWork(
                "paperforge-document-job:${row.id}",
                ExistingWorkPolicy.KEEP,
                request,
            )
    }

    suspend fun start(repo: Repository, type: String, legacy: Data, workId: UUID): JobHandle? =
        repo.database.withTransaction {
            val taskId = legacy.getString("task")
            val row =
                if (taskId != null) repo.dao.task(taskId) ?: error("任务记录不存在")
                else
                    repo.dao.allTasks().firstOrNull { it.workId == workId.toString() }
                        ?: prepare(repo, type, JobInput.from(legacy), workId)
            if (row.workId != workId.toString() || row.state == LocalTaskState.CANCELLED)
                return@withTransaction null
            val input = JobInput(JSONObject(row.payload).getJSONObject("input"))
            val handle = JobHandle(row, input, row.state == LocalTaskState.SUCCEEDED)
            if (handle.completed && handle.result != null) return@withTransaction handle
            repo.dao.taskState(row.id, LocalTaskState.RUNNING, row.progress, "正在处理", null)
            handle.copy(completed = false)
        }

    suspend fun checkCurrent(repo: Repository, handle: JobHandle) {
        val row = repo.dao.task(handle.row.id)
        if (row == null || row.workId != handle.row.workId || row.state == LocalTaskState.CANCELLED)
            throw CancellationException("任务已停止")
    }

    suspend fun progress(
        repo: Repository,
        handle: JobHandle,
        percent: Int,
        detail: String = "正在处理",
    ) {
        repo.database.withTransaction {
            checkCurrent(repo, handle)
            repo.dao.taskState(
                handle.row.id,
                LocalTaskState.RUNNING,
                percent.coerceIn(0, 100),
                detail,
                null,
            )
        }
    }

    suspend fun checkpoint(repo: Repository, handle: JobHandle, key: String, value: Any) =
        repo.database.withTransaction {
            checkCurrent(repo, handle)
            val row = checkNotNull(repo.dao.task(handle.row.id))
            val payload = JSONObject(row.payload)
            payload.getJSONObject("checkpoint").put(key, value)
            repo.dao.putTask(
                row.copy(payload = payload.toString(), updated = System.currentTimeMillis())
            )
        }

    suspend fun checkpointFile(
        repo: Repository,
        handle: JobHandle,
        key: String,
        file: File,
        metadata: JSONObject,
    ) =
        repo.database.withTransaction {
            checkCurrent(repo, handle)
            val row = checkNotNull(repo.dao.task(handle.row.id))
            val payload = JSONObject(row.payload)
            payload.getJSONObject("checkpoint").put(key, metadata.put("path", file.path))
            repo.dao.putTask(
                row.copy(
                    payload = payload.toString(),
                    resources =
                        DocumentResources.encode(
                                row.resourceEntries().filterNot { it.id == key } +
                                    PageResource(key, "conversion-checkpoint", file.path)
                            )
                            .toString(),
                    updated = System.currentTimeMillis(),
                )
            )
        }

    suspend fun complete(
        repo: Repository,
        handle: JobHandle,
        file: File,
        detail: String = "已完成",
        state: LocalTaskState = LocalTaskState.SUCCEEDED,
    ) {
        checkCurrent(repo, handle)
        val row = checkNotNull(repo.dao.task(handle.row.id))
        repo.dao.putTask(
            row.copy(
                state = state,
                progress = 100,
                detail = detail,
                error = null,
                resources =
                    DocumentResources.encode(
                            row.resourceEntries().filterNot { it.id == "result" } +
                                PageResource("result", "job-result", file.path)
                        )
                        .toString(),
                updated = System.currentTimeMillis(),
            )
        )
    }

    suspend fun fail(
        repo: Repository,
        handle: JobHandle?,
        failure: Throwable,
        cancelled: Boolean = false,
    ) =
        withContext(NonCancellable) {
            if (handle == null) return@withContext
            repo.database.withTransaction {
                val row = repo.dao.task(handle.row.id) ?: return@withTransaction
                if (
                    row.workId != handle.row.workId ||
                        row.state in setOf(LocalTaskState.SUCCEEDED, LocalTaskState.CANCELLED)
                )
                    return@withTransaction
                repo.dao.taskState(
                    row.id,
                    if (cancelled) LocalTaskState.CANCELLED else LocalTaskState.FAILED,
                    row.progress,
                    "已完成阶段保留，可重试",
                    failure.message,
                )
            }
        }

    suspend fun cancel(repo: Repository, row: LocalTaskRow) {
        repo.database.withTransaction {
            val latest = repo.dao.task(row.id) ?: return@withTransaction
            if (latest.state in LocalTaskRow.active)
                repo.dao.taskState(
                    row.id,
                    LocalTaskState.CANCELLED,
                    latest.progress,
                    "已停止，原件与检查点保留",
                    null,
                )
        }
        row.workId?.let {
            WorkManager.getInstance(repo.context).cancelWorkById(UUID.fromString(it))
        }
    }

    suspend fun retry(repo: Repository, row: LocalTaskRow): UUID =
        withContext(Dispatchers.IO) {
            val next =
                repo.database.withTransaction {
                    val latest = repo.dao.task(row.id) ?: error("任务记录已移除")
                    require(latest.state !in LocalTaskRow.active) { "任务正在处理" }
                    latest
                        .copy(
                            state = LocalTaskState.QUEUED,
                            error = null,
                            detail = "等待重试",
                            workId = UUID.randomUUID().toString(),
                            updated = System.currentTimeMillis(),
                        )
                        .also { repo.dao.putTask(it) }
                }
            // A new work ID prevents late completion of the old worker from publishing over this
            // retry.
            WorkManager.getInstance(repo.context)
                .cancelUniqueWork("paperforge-document-job:${row.id}")
                .result
                .get()
            schedule(repo, next)
            UUID.fromString(next.workId)
        }

    suspend fun recover(repo: Repository) {
        for (row in
            repo.dao.allTasks().filter {
                it.type in types &&
                    (it.state in LocalTaskRow.active ||
                        it.state == LocalTaskState.PARTIAL && it.detail == "上次处理已中断，检查点保留")
            }) {
            val info =
                row.workId?.let {
                    WorkManager.getInstance(repo.context).getWorkInfoById(UUID.fromString(it)).get()
                }
            reconcile(repo, row, info)
        }
    }

    internal suspend fun reconcile(repo: Repository, observed: LocalTaskRow, info: WorkInfo?) {
        if (info != null && !info.state.isFinished) return
        repo.database.withTransaction {
            val current = repo.dao.task(observed.id) ?: return@withTransaction
            if (
                current.workId != observed.workId ||
                    (current.state !in LocalTaskRow.active &&
                        !(current.state == LocalTaskState.PARTIAL &&
                            current.detail == "上次处理已中断，检查点保留"))
            )
                return@withTransaction
            val result =
                current.resourceEntries().firstOrNull { it.id == "result" }?.path?.let(::File)
            if (info?.state == WorkInfo.State.SUCCEEDED && result?.isFile == true) {
                val partial =
                    info.outputData.getBoolean("partial", false) ||
                        (info.outputData.getLong("targetBytes", 0) > 0 &&
                            !info.outputData.getBoolean("targetAchieved", false))
                repo.dao.taskState(
                    current.id,
                    if (partial) LocalTaskState.PARTIAL else LocalTaskState.SUCCEEDED,
                    100,
                    if (partial) "部分完成，可重试失败项" else "已完成",
                    null,
                )
            } else
                repo.dao.taskState(
                    current.id,
                    LocalTaskState.PARTIAL,
                    current.progress,
                    "上次处理已中断，检查点保留",
                    info?.outputData?.getString("error"),
                )
        }
    }

    suspend fun forget(repo: Repository, taskId: String) {
        val removed =
            repo.database.withTransaction {
                val row = repo.dao.task(taskId) ?: return@withTransaction null
                require(row.type in types) { "此记录由扫描草稿保留" }
                require(row.state !in LocalTaskRow.active) { "请先停止任务" }
                repo.dao.deleteTask(taskId)
                row
            } ?: return
        val retained = LibraryStorage(repo).report().protectedPaths
        val root = repo.context.filesDir.canonicalPath + File.separator
        removed
            .resourceEntries()
            .map { File(it.path).canonicalFile }
            .distinct()
            .forEach { file ->
                if (file.path.startsWith(root) && file.path !in retained) file.delete()
            }
        val items = File(repo.context.filesDir, "job-items/${removed.id}").canonicalFile
        if (items.parentFile == File(repo.context.filesDir, "job-items").canonicalFile)
            items.walkBottomUp().forEach { file ->
                if (file.isDirectory || file.canonicalPath !in retained) file.delete()
            }
    }

    fun remapPayload(payload: String, replacements: Map<String, String>): String {
        fun visit(value: Any?): Any? =
            when (value) {
                is JSONObject ->
                    JSONObject().apply {
                        value.keys().forEach { key ->
                            put(replacements[key] ?: key, visit(value.get(key)))
                        }
                    }
                is JSONArray ->
                    JSONArray().apply { for (i in 0 until value.length()) put(visit(value.get(i))) }
                is String ->
                    replacements[value]
                        ?: run {
                            if (value.trimStart().startsWith("{"))
                                runCatching { visit(JSONObject(value)).toString() }
                                    .getOrDefault(value)
                            else value
                        }
                else -> value
            }
        return visit(JSONObject(payload)).toString()
    }
}
