package cn.paperforge.app.pdf

import cn.paperforge.app.data.*
import java.io.File
import org.json.*

data class PdfDiffReview(val taskId: String, val directory: File, val report: JSONObject) {
    val pages
        get(): List<PdfDiffPage> =
            report.getJSONArray("pages").let { j ->
                List(j.length()) { PdfDiffPage.decode(j.getJSONObject(it)) }
            }

    val ignored
        get(): Set<String> =
            report
                .optJSONArray("ignored")
                ?.let { j -> (0 until j.length()).map { j.getString(it) }.toSet() }
                .orEmpty()

    fun isIgnored(page: PdfDiffPage, finding: PdfDifference) =
        "${page.index}:${finding.id}" in ignored

    suspend fun toggle(repo: Repository, page: PdfDiffPage, finding: PdfDifference): PdfDiffReview {
        val ids = ignored.toMutableSet()
        val id = "${page.index}:${finding.id}"
        if (!ids.add(id)) ids.remove(id)
        val updated = JSONObject(report.toString()).put("ignored", JSONArray(ids.toList()))
        val file =
            File(repo.context.filesDir, "job-items/$taskId/diff-review.json").apply {
                parentFile!!.mkdirs()
            }
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(updated.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
        val row = checkNotNull(repo.dao.task(taskId))
        DocumentJobs.checkpointFile(
            repo,
            JobHandle(row, JobInput(JSONObject(row.payload).getJSONObject("input"))),
            "diff-review",
            file,
            JSONObject(),
        )
        return copy(report = updated)
    }

    companion object {
        suspend fun open(repo: Repository, row: LocalTaskRow): PdfDiffReview {
            val result = File(row.resourceEntries().first { it.id == "result" }.path)
            // This is our retained task output, not an untrusted imported archive.
            val directory =
                MarkupDocuments.unpack(repo.context, result, Int.MAX_VALUE, Long.MAX_VALUE)
            val saved =
                row.resourceEntries()
                    .firstOrNull { it.id == "diff-review" }
                    ?.path
                    ?.let(::File)
                    ?.takeIf { it.isFile }
            return PdfDiffReview(
                row.id,
                directory,
                JSONObject((saved ?: File(directory, "pdf-comparison.json")).readText()),
            )
        }
    }
}
