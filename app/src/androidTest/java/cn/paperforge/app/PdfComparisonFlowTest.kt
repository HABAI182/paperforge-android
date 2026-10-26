package cn.paperforge.app

import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class PdfComparisonFlowTest {
    @Test
    fun retainedOriginalsTextVisualAddedPagesAndReviewedReport() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val directory = File(app.filesDir, "diff-fixture").apply { mkdirs() }
        fun create(file: File, revised: Boolean) {
            PDDocument().use { doc ->
                repeat(if (revised) 4 else 3) { number ->
                    val page = PDPage()
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { stream ->
                        fun text(value: String, y: Float) {
                            stream.beginText()
                            stream.setFont(PDType1Font.HELVETICA, 20f)
                            stream.newLineAtOffset(60f, y)
                            stream.showText(value)
                            stream.endText()
                        }
                        if (number != 2) {
                            text(if (revised) "VERSION TWO" else "VERSION ONE", 760f)
                            text(
                                if (number == 0) "Invoice amount ${if(revised) 200 else 100}"
                                else "Stable body $number",
                                580f,
                            )
                        }
                        if (number != 1) {
                            stream.setNonStrokingColor(
                                if (revised) 0 else 255,
                                0,
                                if (revised) 255 else 0,
                            )
                            stream.addRect(90f, 300f, 100f, 80f)
                            stream.fill()
                        }
                    }
                }
                doc.save(file)
            }
        }
        val left = File(directory, "original.pdf")
        val right = File(directory, "revised.pdf")
        create(left, false)
        create(right, true)
        val originals = listOf(left, right)
        val hashes = originals.map { PdfAssembly.sha256(it) }
        val options = PdfDiffOptions(top = .12f, ignoreWhitespace = true, ignoreCase = true)
        val work =
            DocumentJobs.enqueue(
                repo,
                "CONVERT",
                JobInput(
                    JSONObject()
                        .put("kind", "PDF_DIFF")
                        .put("name", "差异验收")
                        .put("inputs", JSONArray(originals.map { it.path }))
                        .put("names", JSONArray(listOf("比较原版验收", "比较修订验收")))
                        .put("options", options.json().toString())
                ),
            )
        val info =
            withTimeout(90000) {
                WorkManager.getInstance(app).getWorkInfoByIdFlow(work).filterNotNull().first {
                    it.state.isFinished
                }
            }
        assertEquals(info.outputData.getString("error"), WorkInfo.State.SUCCEEDED, info.state)
        assertEquals("PDF_DIFF", info.outputData.getString("mode"))
        val row = checkNotNull(repo.dao.task(info.outputData.getString("taskId")!!))
        val review = PdfDiffReview.open(repo, row)
        assertEquals(4, review.pages.size)
        val first = review.pages[0]
        val text = first.differences.first { it.kind == "text" }
        assertTrue(text.before.contains("100"))
        assertTrue(text.after.contains("200"))
        assertFalse(
            first.differences.any { it.before.contains("VERSION") || it.after.contains("VERSION") }
        )
        assertTrue(first.differences.any { it.kind == "visual" })
        assertTrue(review.pages[1].differences.isEmpty())
        assertTrue(review.pages[2].notes.any { it.contains("没有可用文字层") })
        assertTrue(review.pages[2].differences.any { it.kind == "visual" })
        assertEquals("added-page", review.pages[3].differences.single().kind)
        assertNotNull(text.left)
        assertNotNull(text.right)
        assertTrue(text.left!!.top > .12f && text.left!!.bottom <= 1f)
        val revised = review.toggle(repo, first, text)
        assertTrue(revised.isIgnored(first, text))
        val reopened = PdfDiffReview.open(repo, checkNotNull(repo.dao.task(row.id)))
        assertTrue(reopened.isIgnored(first, text))
        val exported = File(repo.exports, "comparison-reviewed-proof.zip")
        PdfComparison.writeArchive(
            exported,
            reopened.directory,
            reopened.report,
            listOf("left.pdf", "right.pdf").map { File(reopened.directory, it) },
        )
        ZipFile(exported).use { zip ->
            listOf("left.pdf", "right.pdf").forEachIndexed { index, name ->
                val copy = File(app.cacheDir, "diff-$name")
                zip.getInputStream(zip.getEntry(name)).use { input ->
                    copy.outputStream().use { input.copyTo(it) }
                }
                assertEquals(hashes[index], PdfAssembly.sha256(copy))
            }
            val report =
                JSONObject(
                    zip.getInputStream(zip.getEntry("pdf-comparison.json"))
                        .bufferedReader()
                        .readText()
                )
            assertEquals(1, report.getJSONArray("ignored").length())
            val html = zip.getInputStream(zip.getEntry("index.html")).bufferedReader().readText()
            assertTrue(html.contains("href='left.pdf'"))
            assertTrue(html.contains("已忽略 1 项"))
        }
        originals.forEachIndexed { index, file ->
            assertEquals(hashes[index], PdfAssembly.sha256(file))
        }
        val leftDoc = repo.registerPdf(left, "比较原版验收")
        val rightDoc = repo.registerPdf(right, "比较修订验收")
        File(app.cacheDir, "pdf-diff-proof.json")
            .writeText(
                JSONObject()
                    .put("task", row.id)
                    .put("result", info.outputData.getString("path"))
                    .put("reviewed", exported.path)
                    .put("sourceSha256", JSONArray(hashes))
                    .put("leftDocument", leftDoc.id)
                    .put("rightDocument", rightDoc.id)
                    .put("pages", 4)
                    .put("textDifference", text.json())
                    .toString(2)
            )
    }
}
