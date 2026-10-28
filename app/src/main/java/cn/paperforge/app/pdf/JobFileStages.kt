package cn.paperforge.app.pdf

import cn.paperforge.app.data.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Durable, referenced intermediate files. A new attempt reads the current Room checkpoint. */
class JobFileStages(private val repo: Repository, private val job: JobHandle) {
    val entries = JSONArray()
    var reused = 0
        private set

    var generated = 0
        private set

    /** Read without recording consumption, so a renderer can skip an already encoded segment. */
    suspend fun cached(key: String, signature: String): File? = verified(key, signature)?.first

    private suspend fun verified(key: String, signature: String): Pair<File, String>? {
        currentCoroutineContext().ensureActive()
        DocumentJobs.checkCurrent(repo, job)
        val checkpointKey = "page-stage-${digest("$key|$signature").take(32)}"
        val record =
            JSONObject(checkNotNull(repo.dao.task(job.row.id)).payload)
                .getJSONObject("checkpoint")
                .optJSONObject(checkpointKey)
                ?.takeIf { it.optString("signature") == signature } ?: return null
        val path = record.optString("path").takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        if (!file.isFile || file.length() != record.getLong("bytes")) return null
        val hash = PdfAssembly.sha256(file)
        return if (hash == record.getString("sha256")) file to hash else null
    }

    suspend fun file(
        key: String,
        signature: String,
        extension: String,
        create: suspend (File) -> Unit,
    ): File {
        currentCoroutineContext().ensureActive()
        DocumentJobs.checkCurrent(repo, job)
        val version = digest("$key|$signature")
        val checkpointKey = "page-stage-${version.take(32)}"
        val found = verified(key, signature)
        var hash = found?.second.orEmpty()
        val ready = found?.first
        val output =
            ready
                ?: File(
                        repo.context.filesDir,
                        "job-items/${job.row.id}/page-stages/$version.$extension",
                    )
                    .also { file ->
                        file.parentFile!!.mkdirs()
                        create(file)
                        currentCoroutineContext().ensureActive()
                        require(file.isFile && file.length() > 0) { "页面阶段没有生成文件" }
                        hash = PdfAssembly.sha256(file)
                        DocumentJobs.checkpointFile(
                            repo,
                            job,
                            checkpointKey,
                            file,
                            JSONObject()
                                .put("key", key)
                                .put("signature", signature)
                                .put("bytes", file.length())
                                .put("sha256", hash),
                        )
                    }
        if (ready != null) reused++ else generated++
        entries.put(
            JSONObject()
                .put("key", key)
                .put("reused", ready != null)
                .put("bytes", output.length())
                .put("sha256", hash)
        )
        return output
    }

    companion object {
        fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
