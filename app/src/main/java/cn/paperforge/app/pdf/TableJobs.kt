package cn.paperforge.app.pdf

import android.util.AtomicFile
import androidx.room.withTransaction
import cn.paperforge.app.data.*
import cn.paperforge.app.ocr.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

object TableJobs {
    fun atomic(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray())
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    suspend fun analyze(
        repo: Repository,
        job: JobHandle,
        router: OcrEngine,
        report: suspend (Int, String) -> Unit,
    ): File {
        val source = File(job.input.getStringArray("inputs")!!.first())
        val options = JSONObject(job.input.getString("options") ?: "{}")
        val request = OcrRequest.decode(job.input.json.optJSONObject("ocrRequest"), router.id)
        val engine = request.bind(router)
        val sourceHash = PdfAssembly.sha256(source)
        val fingerprint = sourceHash + request.signature + options.toString() + PhoneTables.VERSION
        val directory = File(repo.context.filesDir, "job-items/${job.row.id}").apply { mkdirs() }
        val tables =
            PhoneTables.extract(
                source,
                engine,
                options,
                checkpoint = { index, produce ->
                    currentCoroutineContext().ensureActive()
                    DocumentJobs.checkCurrent(repo, job)
                    val key = "table-page-$index"
                    val row = checkNotNull(repo.dao.task(job.row.id))
                    val checkpoint =
                        JSONObject(row.payload).getJSONObject("checkpoint").optJSONObject(key)
                    val existing =
                        checkpoint?.optString("path")?.let(::File)?.takeIf {
                            it.isFile && checkpoint.optString("fingerprint") == fingerprint
                        }
                    if (existing != null) TableData.decode(JSONObject(existing.readText()))
                    else {
                        report(5, "识别表格 · 第 ${index+1} 页")
                        val result = produce()
                        currentCoroutineContext().ensureActive()
                        val file = File(directory, "page-$index-${job.row.workId}.json")
                        atomic(file, TableData.encode(result))
                        withContext(NonCancellable) {
                            repo.database.withTransaction {
                                DocumentJobs.checkCurrent(repo, job)
                                val latest = checkNotNull(repo.dao.task(job.row.id))
                                val payload = JSONObject(latest.payload)
                                payload
                                    .getJSONObject("checkpoint")
                                    .put(
                                        key,
                                        JSONObject()
                                            .put("fingerprint", fingerprint)
                                            .put("path", file.path),
                                    )
                                repo.dao.putTask(
                                    latest.copy(
                                        payload = payload.toString(),
                                        resources =
                                            DocumentResources.encode(
                                                    latest.resourceEntries().filterNot {
                                                        it.id == key
                                                    } +
                                                        PageResource(
                                                            key,
                                                            "job-checkpoint",
                                                            file.path,
                                                        )
                                                )
                                                .toString(),
                                    )
                                )
                            }
                        }
                        result
                    }
                },
            ) {}
        report(95, "保存表格预览")
        val output = File(repo.exports, "PaperForge-table-review-${job.row.workId}.pftables")
        atomic(
            output,
            TableData.encode(tables).put("sourceSha256", sourceHash).put("analysisOptions", options),
        )
        return output
    }

    fun export(repo: Repository, job: JobHandle): File {
        val inputs = job.input.getStringArray("inputs")!!
        val state = JSONObject(File(inputs[1]).readText())
        val tables = TableData.decode(state)
        val options = TableExportOptions.decode(JSONObject(job.input.getString("options") ?: "{}"))
        val output =
            File(
                repo.exports,
                "PaperForge-tables-${job.row.workId}.${if(options.format=="XLSX") "xlsx" else "zip"}",
            )
        PhoneTables.write(
            tables,
            output,
            options,
            JSONObject()
                .put("sourceSha256", state.optString("sourceSha256"))
                .put("analysisOptions", state.optJSONObject("analysisOptions")),
        )
        return output
    }
}
