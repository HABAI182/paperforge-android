package cn.paperforge.app

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class OfficePageImagesFlowTest {
    private suspend fun finished(repo: Repository, id: String): LocalTaskRow =
        withTimeout(120000) {
            while (true) {
                val row = checkNotNull(repo.dao.task(id))
                if (row.state !in LocalTaskRow.active) {
                    assertEquals(row.error, LocalTaskState.SUCCEEDED, row.state)
                    return@withTimeout row
                }
                delay(200)
            }
            error("unreachable")
        }

    private fun result(row: LocalTaskRow) =
        File(row.resourceEntries().first { it.id == "result" }.path)

    @Test
    fun actualPptxStaticPagesCacheRetryAndImagePackageComparison() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val before = repo.dao.allTasks().map { it.id }.toSet()
            val folder =
                File(app.filesDir, "office-pages-proof-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val source = File(folder, "slides-source.pdf")
                PDDocument().use { pdf ->
                    repeat(2) { i ->
                        val p = PDPage(PDRectangle(600f, 400f))
                        pdf.addPage(p)
                        PDPageContentStream(pdf, p).use {
                            if (i == 0) it.setNonStrokingColor(255, 0, 0)
                            else it.setNonStrokingColor(0, 0, 255)
                            it.addRect(0f, 0f, 600f, 400f)
                            it.fill()
                            it.setNonStrokingColor(255, 255, 255)
                            it.beginText()
                            it.setFont(PDType1Font.HELVETICA, 30f)
                            it.newLineAtOffset(40f, 300f)
                            it.showText("Slide ${i+1} 00${i+1}23")
                            it.endText()
                        }
                    }
                    pdf.save(source)
                }
                val pptx = File(folder, "PaperForge-slides-proof.pptx")
                PhoneSlides.convert(app, source, pptx)
                ZipFile(pptx).use { zip ->
                    assertTrue(
                        zip.getInputStream(zip.getEntry("ppt/slides/slide1.xml"))
                            .bufferedReader()
                            .use { it.readText() }
                            .contains("<p:pic>")
                    )
                }
                val sourceHash = PdfAssembly.sha256(pptx)
                val batch =
                    BatchConversions.add(
                        repo,
                        listOf(Uri.fromFile(pptx)),
                        BatchPreset("OFFICE_IMAGES", "{\"format\":\"png\",\"edge\":640}"),
                    )
                val task = repo.dao.allTasks().first { BatchConversions.batchId(it) == batch }
                assertEquals(1, BatchConversions.runPending(repo, batch))
                val first = finished(repo, task.id)
                val firstZip = result(first)
                val firstHash = PdfAssembly.sha256(firstZip)
                val bytes = mutableListOf<ByteArray>()
                ZipFile(firstZip).use { zip ->
                    val manifest =
                        JSONObject(
                            zip.getInputStream(zip.getEntry("images.json")).bufferedReader().use {
                                it.readText()
                            }
                        )
                    val pages = manifest.getJSONArray("pages")
                    assertEquals(2, pages.length())
                    assertTrue(manifest.getBoolean("staticPages"))
                    repeat(2) { i ->
                        val page = pages.getJSONObject(i)
                        assertEquals(i + 1, page.getInt("page"))
                        assertFalse(page.getBoolean("reused"))
                        val raw =
                            zip.getInputStream(zip.getEntry(page.getString("file"))).use {
                                it.readBytes()
                            }
                        bytes += raw
                        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                        assertEquals(640, maxOf(bitmap.width, bitmap.height))
                        val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        if (i == 0) {
                            assertTrue(Color.red(color) > 240)
                            assertTrue(Color.blue(color) < 20)
                        } else {
                            assertTrue(Color.blue(color) > 240)
                            assertTrue(Color.red(color) < 20)
                        }
                        bitmap.recycle()
                    }
                }
                val stage = first.resourceEntries().first { it.id == "office-output" }.path
                DocumentJobs.retry(repo, first)
                val second = finished(repo, task.id)
                assertNotEquals(first.workId, second.workId)
                assertNotEquals(firstZip.path, result(second).path)
                assertEquals(firstHash, PdfAssembly.sha256(firstZip))
                assertEquals(
                    stage,
                    second.resourceEntries().first { it.id == "office-output" }.path,
                )
                ZipFile(result(second)).use { zip ->
                    val pages =
                        JSONObject(
                                zip.getInputStream(zip.getEntry("images.json"))
                                    .bufferedReader()
                                    .use { it.readText() }
                            )
                            .getJSONArray("pages")
                    repeat(2) { i ->
                        assertTrue(pages.getJSONObject(i).getBoolean("reused"))
                        assertArrayEquals(
                            bytes[i],
                            zip.getInputStream(
                                    zip.getEntry(pages.getJSONObject(i).getString("file"))
                                )
                                .use { it.readBytes() },
                        )
                    }
                }
                assertEquals(sourceHash, PdfAssembly.sha256(pptx))
                val previewRow = finished(repo, ConversionPreviews.prepare(repo, second).id)
                val preview = ConversionPreviews.load(previewRow)
                assertEquals(2, preview.left.pages)
                assertEquals(2, preview.right.pages)
                preview.left.pdf.copyTo(File(folder, "source-preview.pdf"))
                preview.right.pdf.copyTo(File(folder, "actual-images-preview.pdf"))
                result(second).copyTo(File(folder, "actual-images.zip"))
                File(folder, "proof.json")
                    .writeText(
                        JSONObject()
                            .put("sourceSha256", sourceHash)
                            .put("staticSlides", 2)
                            .put("longEdge", 640)
                            .put("sourceUnchanged", true)
                            .put("oldZipUnchanged", true)
                            .put("allPageCheckpointsReused", true)
                            .put("pageBytesIdenticalAfterRetry", true)
                            .put("sourceAndActualZipPreviewPages", 2)
                            .toString(2)
                    )
                android.util.Log.i(
                    "OfficePageImagesFlowTest",
                    "Proof: ${File(folder,"proof.json").path}",
                )
            } finally {
                repo.dao
                    .allTasks()
                    .filter { it.id !in before }
                    .forEach { repo.dao.deleteTask(it.id) }
            }
        }
}
