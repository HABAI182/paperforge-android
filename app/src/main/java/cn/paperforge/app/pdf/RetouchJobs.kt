package cn.paperforge.app.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.*

/** The worker owns a frozen appearance and recipe, never a live PageRow. */
object RetouchJobs {
    fun documentId(taskId: String) =
        UUID.nameUUIDFromBytes("retouch:$taskId".toByteArray()).toString()

    fun publishedDocument(row: LocalTaskRow) =
        JSONObject(row.payload).optJSONObject("checkpoint")?.optString("retouch-document")?.takeIf {
            it.isNotBlank()
        } ?: documentId(row.id)

    suspend fun resultDocument(repo: Repository, row: LocalTaskRow): DocumentRow {
        repo.dao.document(publishedDocument(row))?.let {
            return it
        }
        val job = JobHandle(row, JobInput(JSONObject(row.payload).getJSONObject("input")))
        val result = job.result ?: error("修复结果文件已移除")
        return repo.database.withTransaction {
            checkNotNull(repo.dao.document(publish(repo, job, result)))
        }
    }

    suspend fun enqueue(repo: Repository, page: PageRow, request: RetouchRequest): UUID {
        val directory =
            File(repo.context.filesDir, "retouch-inputs/${UUID.randomUUID()}").apply { mkdirs() }
        val image = File(directory, "source.png")
        val recipe = File(directory, "request.json")
        val bitmap = ScanProcessor.render(page, 2400)
        try {
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val source = repo.dao.document(page.documentId) ?: error("来源文档已移除")
        recipe.writeText(
            JSONObject()
                .put("request", request.json())
                .put("source", RetouchDrafts.sourceKey(page))
                .put("documentId", page.documentId)
                .put("pageId", page.id)
                .put("ordinal", page.position + 1)
                .put("title", source.title)
                .toString()
        )
        return DocumentJobs.enqueue(
            repo,
            "CONVERT",
            JobInput(
                JSONObject()
                    .put("kind", "SCAN_RETOUCH")
                    .put("document", page.documentId)
                    .put("page", page.id)
                    .put("name", source.title + " · 修复副本")
                    .put("inputs", JSONArray(listOf(image.path, recipe.path)))
            ),
        )
    }

    suspend fun run(
        repo: Repository,
        job: JobHandle,
        progress: suspend (Int, String) -> Unit,
    ): File {
        val inputs = job.input.getStringArray("inputs")!!.map(::File)
        val request =
            RetouchRequest.decode(JSONObject(inputs[1].readText()).getJSONObject("request"))
        val fingerprint = PdfAssembly.sha256(inputs[0]) + request.json().toString()
        val latest = checkNotNull(repo.dao.task(job.row.id))
        val cache =
            JSONObject(latest.payload).getJSONObject("checkpoint").optJSONObject("retouch-image")
        cache
            ?.optString("path")
            ?.let(::File)
            ?.takeIf {
                it.isFile && it.length() > 0 && cache.optString("fingerprint") == fingerprint
            }
            ?.let { cached ->
                if (
                    cached.length() == cache.getLong("bytes") &&
                        PdfAssembly.sha256(cached) == cache.getString("sha256")
                ) {
                    progress(98, "复用已完成的修复结果")
                    return cached
                }
            }
        val output =
            OutputNames.create(repo, job.row.id, job.input.getString("name") ?: "修复副本", "png")
        val image = BitmapFactory.decodeFile(inputs[0].path) ?: error("无法打开修复原图")
        val result =
            try {
                ScanRetouch.apply(repo.context, image, request) { percent ->
                    DocumentJobs.checkCurrent(repo, job)
                    progress(percent, "${ScanRetouch.modes[request.mode]} · 保留原件")
                }
            } finally {
                image.recycle()
            }
        val part = File(output.path + ".part")
        try {
            part.outputStream().use { check(result.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            currentCoroutineContext().ensureActive()
            DocumentJobs.checkCurrent(repo, job)
            check(part.renameTo(output)) { "无法保存修复副本" }
            DocumentJobs.checkpointFile(
                repo,
                job,
                "retouch-image",
                output,
                JSONObject()
                    .put("fingerprint", fingerprint)
                    .put("bytes", output.length())
                    .put("sha256", PdfAssembly.sha256(output)),
            )
        } finally {
            result.recycle()
            part.delete()
        }
        return output
    }

    /**
     * Called inside publication transaction. Stable identifiers prevent duplicate documents on
     * retry.
     */
    suspend fun publish(repo: Repository, job: JobHandle, result: File): String {
        val id = documentId(job.row.id)
        if (repo.dao.document(id) != null) {
            DocumentJobs.checkpoint(repo, job, "retouch-document", id)
            return id
        }
        val inputs = job.input.getStringArray("inputs")!!.map(::File)
        val recipe = JSONObject(inputs[1].readText())
        val folder = repo.folder(id)
        val original = inputs[0].copyTo(File(folder, "source.png"), overwrite = true)
        val request = inputs[1].copyTo(File(folder, "request.json"), overwrite = true)
        val processed = result.copyTo(File(folder, "修复副本.png"), overwrite = true)
        val metadata =
            JSONObject()
                .put("format", 1)
                .put(
                    "resources",
                    DocumentResources.encode(
                        listOf(
                            PageResource("retouch-source", "retouch-source", original.path),
                            PageResource("retouch-request", "retouch-request", request.path),
                        )
                    ),
                )
                .put(
                    "pageOrigins",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("documentId", job.row.documentId ?: JSONObject.NULL)
                                .put("pageId", job.row.pageId ?: JSONObject.NULL)
                                .put("title", recipe.getString("title"))
                                .put("ordinal", recipe.optInt("ordinal", 1))
                                .put("sha256", recipe.getString("source").substringBefore('|'))
                        ),
                )
                .put(
                    "retouch",
                    JSONObject()
                        .put("sourceKey", recipe.getString("source"))
                        .put("request", recipe.getJSONObject("request"))
                        .put("algorithm", ScanRetouch.VERSION),
                )
        repo.dao.putDocument(DocumentRow(id, job.input.getString("name") ?: "修复副本"))
        repo.dao.putPage(
            PageRow(
                UUID.nameUUIDFromBytes("retouch-page:$id".toByteArray()).toString(),
                id,
                0,
                processed.path,
                filter = "ORIGINAL",
                detectionMethod = "retouch",
                processingMetadata = metadata.toString(),
            )
        )
        DocumentJobs.checkpoint(repo, job, "retouch-document", id)
        return id
    }
}
