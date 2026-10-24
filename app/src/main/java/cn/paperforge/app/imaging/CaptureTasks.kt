package cn.paperforge.app.imaging

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import androidx.work.*
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Raw photos and task intent are durable before CameraX starts; workers never own the only copy.
 */
object CaptureTasks {
    val decodeSlot = Mutex()

    private fun marker(repo: Repository, row: LocalTaskRow) =
        AtomicFile(
            File(
                File(repo.context.filesDir, "documents/${checkNotNull(row.documentId)}"),
                "${row.id}.capture.json",
            )
        )

    private fun writeMarker(repo: Repository, row: LocalTaskRow) {
        val storage = marker(repo, row)
        val stream = storage.startWrite()
        try {
            stream.write(row.encode().toString().toByteArray(Charsets.UTF_8))
            storage.finishWrite(stream)
        } catch (e: Exception) {
            storage.failWrite(stream)
            throw e
        }
    }

    fun raw(row: LocalTaskRow) = File(row.resourceEntries().first { it.id == "raw" }.path)

    suspend fun prepare(
        repo: Repository,
        documentId: String,
        reference: CaptureReference?,
        target: String? = null,
        file: File = repo.captureFile(documentId),
    ): LocalTaskRow =
        withContext(Dispatchers.IO) {
            repo.database.withTransaction {
                val doc = repo.dao.document(documentId) ?: error("来源文档已移除")
                val pages = repo.dao.pages(documentId)
                val existing = pages.firstOrNull { it.id == target }
                val position =
                    existing?.position
                        ?: (maxOf(
                            pages.maxOfOrNull { it.position } ?: -1,
                            repo.dao.reservedCapturePosition(documentId) ?: -1,
                        ) + 1)
                val spec =
                    CaptureSpec(
                        CapturePreset.current(existing),
                        reference,
                        existing?.revision ?: -1,
                    )
                val row =
                    LocalTaskRow(
                        UUID.randomUUID().toString(),
                        "CAPTURE",
                        documentId,
                        doc.title,
                        UUID.randomUUID().toString(),
                        existing?.id,
                        position,
                        LocalTaskState.CAPTURING,
                        detail = "等待照片保存",
                        payload = spec.encode().toString(),
                        resources =
                            DocumentResources.encode(
                                    listOf(PageResource("raw", "capture-original", file.path))
                                )
                                .toString(),
                    )
                writeMarker(repo, row)
                repo.dao.putTask(row)
                row
            }
        }

    suspend fun accept(repo: Repository, id: String, retry: Boolean = false) =
        withContext(Dispatchers.IO) {
            val prepared = repo.dao.task(id) ?: return@withContext
            if (
                prepared.state == LocalTaskState.SUCCEEDED ||
                    (!retry &&
                        prepared.state in
                            setOf(
                                LocalTaskState.CANCELLED,
                                LocalTaskState.FAILED,
                                LocalTaskState.PARTIAL,
                            ))
            )
                return@withContext
            val file = raw(prepared)
            if (!file.isFile || file.length() == 0L) {
                fail(repo, id, "没有保存成功的照片，请重新拍摄")
                return@withContext
            }
            val queued =
                repo.database.withTransaction {
                    val row = repo.dao.task(id) ?: return@withTransaction null
                    if (row.state in setOf(LocalTaskState.QUEUED, LocalTaskState.RUNNING))
                        return@withTransaction if (row.state == LocalTaskState.QUEUED) row else null
                    if (
                        row.state == LocalTaskState.SUCCEEDED ||
                            (!retry && row.state != LocalTaskState.CAPTURING)
                    )
                        return@withTransaction null
                    val docId = row.documentId
                    if (docId == null || repo.dao.document(docId) == null) {
                        repo.dao.putTask(
                            row.copy(
                                state = LocalTaskState.CANCELLED,
                                resources = "[]",
                                error = "来源文档已移除",
                                updated = System.currentTimeMillis(),
                            )
                        )
                        return@withTransaction null
                    }
                    val spec = CaptureSpec.decode(JSONObject(row.payload))
                    if (
                        row.targetPageId == null &&
                            repo.dao.pages(docId).none { it.id == row.pageId }
                    ) {
                        repo.dao.putPage(
                            spec.preset
                                .page(checkNotNull(row.pageId), docId, row.position, file.path)
                                .copy(
                                    processingMetadata =
                                        JSONObject()
                                            .put("format", 1)
                                            .put("captureJob", id)
                                            .toString()
                                )
                        )
                        repo.dao.touch(docId, System.currentTimeMillis())
                    }
                    val retrySpec =
                        if (retry)
                            spec.copy(
                                expectedDraftRevision =
                                    repo.dao
                                        .pages(docId)
                                        .firstOrNull {
                                            it.id == row.pageId && it.original == file.path
                                        }
                                        ?.revision ?: spec.expectedDraftRevision
                            )
                        else spec
                    val next =
                        row.copy(
                            state = LocalTaskState.QUEUED,
                            progress = 0,
                            detail = "等待识边",
                            error = null,
                            payload = retrySpec.encode().toString(),
                            workId =
                                if (retry) UUID.randomUUID().toString()
                                else row.workId ?: UUID.randomUUID().toString(),
                            updated = System.currentTimeMillis(),
                        )
                    repo.dao.putTask(next)
                    next
                } ?: return@withContext
            schedule(repo.context, queued, replace = retry)
            marker(repo, queued).delete()
        }

    fun schedule(context: Context, row: LocalTaskRow, replace: Boolean = false) {
        val request =
            OneTimeWorkRequestBuilder<CaptureProcessWorker>()
                .setId(UUID.fromString(checkNotNull(row.workId)))
                .setInputData(workDataOf("task" to row.id))
                .addTag("paperforge-task")
                .addTag("capture")
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "paperforge-task:${row.id}",
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
    }

    suspend fun fail(repo: Repository, id: String, error: String) {
        repo.database.withTransaction {
            val row = repo.dao.task(id) ?: return@withTransaction
            if (row.state == LocalTaskState.CANCELLED || row.documentId == null)
                return@withTransaction
            repo.dao.taskState(id, LocalTaskState.FAILED, 0, "照片保留，可重试或重拍", error)
        }
        repo.dao.task(id)?.takeIf { it.documentId != null }?.let { marker(repo, it).delete() }
    }

    suspend fun cancel(repo: Repository, row: LocalTaskRow) {
        val cancelled =
            repo.database.withTransaction {
                val latest = repo.dao.task(row.id) ?: return@withTransaction false
                if (latest.state !in LocalTaskRow.active) return@withTransaction false
                repo.dao.taskState(
                    row.id,
                    LocalTaskState.CANCELLED,
                    latest.progress,
                    "已停止处理，原图保留",
                    null,
                )
                true
            }
        if (!cancelled) return
        row.workId?.let {
            WorkManager.getInstance(repo.context).cancelWorkById(UUID.fromString(it))
        }
        if (row.documentId != null) marker(repo, row).delete()
    }

    suspend fun recover(repo: Repository) =
        withContext(Dispatchers.IO) {
            val folders =
                File(repo.context.filesDir, "documents").listFiles().orEmpty().filter {
                    it.isDirectory
                }
            for (folder in folders) for (file in
                folder
                    .listFiles()
                    .orEmpty()
                    .filter {
                        it.name.endsWith(".capture.json") || it.name.endsWith(".capture.json.bak")
                    }
                    .map { File(it.path.removeSuffix(".bak")) }
                    .distinct()) {
                runCatching {
                    val row =
                        LocalTaskRow.decode(
                            JSONObject(
                                AtomicFile(file).openRead().bufferedReader().use { it.readText() }
                            )
                        )
                    val existing = repo.dao.task(row.id)
                    if (
                        row.documentId == folder.name &&
                            repo.dao.document(folder.name) != null &&
                            existing == null
                    )
                        repo.dao.putTask(row)
                    else if (existing != null && existing.state !in LocalTaskRow.active)
                        AtomicFile(file).delete()
                }
            }
            for (row in
                repo.dao.allTasks().filter {
                    it.type == "CAPTURE" && it.state in LocalTaskRow.active
                }) {
                if (row.state == LocalTaskState.CAPTURING) {
                    accept(repo, row.id)
                    continue
                }
                val info =
                    row.workId?.let {
                        WorkManager.getInstance(repo.context)
                            .getWorkInfoById(UUID.fromString(it))
                            .get()
                    }
                if (info != null && info.state.isFinished) {
                    repo.dao.taskState(
                        row.id,
                        if (info.state == WorkInfo.State.CANCELLED) LocalTaskState.CANCELLED
                        else LocalTaskState.FAILED,
                        row.progress,
                        "上次处理已中断，可重试",
                        info.outputData.getString("error"),
                    )
                } else schedule(repo.context, row)
            }
        }
}

class CaptureProcessWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val repo = (applicationContext as PaperForgeApp).repository
            val id = inputData.getString("task") ?: return@withContext Result.failure()
            val start = repo.dao.task(id) ?: return@withContext Result.success()
            if (start.state == LocalTaskState.CANCELLED || start.state == LocalTaskState.SUCCEEDED)
                return@withContext Result.success()
            try {
                CaptureTasks.decodeSlot.withLock {
                    val row =
                        repo.database.withTransaction {
                            val row = repo.dao.task(id) ?: return@withTransaction null
                            if (
                                row.state == LocalTaskState.CANCELLED ||
                                    row.workId != this@CaptureProcessWorker.id.toString()
                            )
                                return@withTransaction null
                            repo.dao.taskState(id, LocalTaskState.RUNNING, 15, "正在检测纸边", null)
                            row
                        } ?: return@withLock
                    val document = row.documentId ?: error("来源文档已移除")
                    check(repo.dao.document(document) != null) { "来源文档已移除" }
                    val spec = CaptureSpec.decode(JSONObject(row.payload))
                    val file = CaptureTasks.raw(row)
                    val result =
                        ScanProcessor.detectFile(
                            file,
                            spec.reference?.quad,
                            spec.reference,
                            spec.preset.detector,
                            spec.preset.lockCrop,
                        )
                    currentCoroutineContext().ensureActive()
                    repo.database.withTransaction {
                        val current = repo.dao.task(id)
                        if (
                            current?.state == LocalTaskState.CANCELLED ||
                                current?.workId != this@CaptureProcessWorker.id.toString() ||
                                repo.dao.document(document) == null
                        )
                            return@withTransaction
                        val pages = repo.dao.pages(document)
                        val target = pages.firstOrNull { it.id == row.targetPageId }
                        val original = pages.firstOrNull { it.id == row.pageId }
                        val metadata =
                            JSONObject(CaptureMetadata.encode(spec.reference, result))
                                .put("captureJob", id)
                                .toString()
                        var detail = if (result.quad == null) "未找到完整纸边，已保留全图" else "纸张已保存"
                        var state =
                            if (result.quad == null) LocalTaskState.PARTIAL
                            else LocalTaskState.SUCCEEDED
                        if (
                            row.targetPageId != null &&
                                target?.revision == spec.expectedTargetRevision
                        ) {
                            repo.savePage(
                                target.copy(
                                    original = file.path,
                                    corners = (result.quad ?: Quad.Full).encode(),
                                    rotation = 0,
                                    deskewDegrees = 0f,
                                    processingMetadata = metadata,
                                    detectionConfidence = result.confidence,
                                    detectionMethod = result.method,
                                )
                            )
                        } else if (original != null) {
                            if (
                                original.revision == spec.expectedDraftRevision &&
                                    original.original == file.path
                            )
                                repo.dao.updatePage(
                                    original.copy(
                                        corners = (result.quad ?: Quad.Full).encode(),
                                        revision = original.revision + 1,
                                        ocrText = null,
                                        ocrLayout = null,
                                        processingMetadata = metadata,
                                        detectionConfidence = result.confidence,
                                        detectionMethod = result.method,
                                    )
                                )
                            else {
                                state = LocalTaskState.SUCCEEDED
                                detail = "已保留你对本页的修改"
                            }
                        } else if (row.targetPageId != null) {
                            val position = (pages.maxOfOrNull { it.position } ?: -1) + 1
                            repo.dao.putPage(
                                spec.preset
                                    .page(checkNotNull(row.pageId), document, position, file.path)
                                    .copy(
                                        corners = (result.quad ?: Quad.Full).encode(),
                                        processingMetadata = metadata,
                                        detectionConfidence = result.confidence,
                                        detectionMethod = result.method,
                                    )
                            )
                            detail = "原页已更改，重拍照片已另存为新页"
                        } else {
                            state = LocalTaskState.SUCCEEDED
                            detail = "本页已移除，原图仍可从任务中查看"
                        }
                        repo.dao.touch(document, System.currentTimeMillis())
                        repo.dao.taskState(id, state, 100, detail, null)
                    }
                }
                Result.success()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    repo.database.withTransaction {
                        val row = repo.dao.task(id)
                        if (
                            row?.state != LocalTaskState.CANCELLED &&
                                row?.workId == this@CaptureProcessWorker.id.toString()
                        )
                            repo.dao.taskState(id, LocalTaskState.QUEUED, 0, "等待系统继续处理", null)
                    }
                }
                throw e
            } catch (e: Exception) {
                repo.database.withTransaction {
                    if (repo.dao.task(id)?.workId == this@CaptureProcessWorker.id.toString())
                        CaptureTasks.fail(repo, id, e.message ?: "处理失败，原图已保留")
                }
                Result.failure(workDataOf("error" to (e.message ?: "处理失败")))
            }
        }
}
