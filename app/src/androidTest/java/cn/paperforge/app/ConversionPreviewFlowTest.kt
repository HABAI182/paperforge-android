package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class ConversionPreviewFlowTest {
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

    private fun pdf(file: File, vararg text: String) {
        PDDocument().use { d ->
            text.forEach { s ->
                val p = PDPage()
                d.addPage(p)
                PDPageContentStream(d, p).use {
                    it.beginText()
                    it.setFont(PDType1Font.HELVETICA, 24f)
                    it.newLineAtOffset(40f, 700f)
                    it.showText(s)
                    it.endText()
                }
            }
            d.save(file)
        }
    }

    private suspend fun fixtureTask(repo: Repository, source: File, output: File): LocalTaskRow {
        val row =
            DocumentJobs.prepare(
                repo,
                "CONVERT",
                JobInput(
                    JSONObject()
                        .put("kind", "PREVIEW_FIXTURE")
                        .put("name", "Different-file preview fixture")
                        .put("inputs", JSONArray(listOf(source.path)))
                ),
            )
        DocumentJobs.complete(
            repo,
            JobHandle(row, JobInput(JSONObject(row.payload).getJSONObject("input"))),
            output,
        )
        return repo.dao.task(row.id)!!
    }

    @Test
    fun realOfficeResultsNativeDifferencesAndPortablePreviewResources() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val before = repo.dao.allTasks().map { it.id }.toSet()
            val folder =
                File(app.filesDir, "conversion-preview-proof-${UUID.randomUUID()}").apply {
                    mkdirs()
                }
            val proof = JSONObject()
            try {
                val source = File(folder, "original.docx")
                InstrumentationRegistry.getInstrumentation()
                    .context
                    .assets
                    .open("office-job-input.docx")
                    .use { input -> source.outputStream().use { input.copyTo(it) } }
                val hash = PdfAssembly.sha256(source)
                val office =
                    DocumentJobs.prepare(
                        repo,
                        "CONVERT",
                        JobInput(
                            JSONObject()
                                .put("kind", "OFFICE")
                                .put("name", "Office preview proof.docx")
                                .put("inputs", JSONArray(listOf(source.path)))
                                .put("options", "{\"format\":\"odt\"}")
                        ),
                    )
                DocumentJobs.schedule(repo, office)
                val parent = finished(repo, office.id)
                val actual = File(parent.resourceEntries().first { it.id == "result" }.path)
                val resultHash = PdfAssembly.sha256(actual)
                val first = ConversionPreviews.prepare(repo, parent)
                val row = finished(repo, first.id)
                val preview = ConversionPreviews.load(row)
                assertNotEquals(preview.left.pdf.path, preview.right.pdf.path)
                assertNotEquals(source.path, preview.left.pdf.path)
                assertEquals(actual.path, preview.right.original.path)
                for ((label, side) in
                    listOf("office-left" to preview.left, "office-right" to preview.right)) {
                    assertEquals(2, side.pages)
                    PDDocument.load(side.pdf).use {
                        assertTrue(PDFTextStripper().getText(it).contains("00123"))
                    }
                    side.pdf.copyTo(File(folder, "$label.pdf"))
                }
                assertEquals(hash, PdfAssembly.sha256(source))
                assertEquals(resultHash, PdfAssembly.sha256(actual))
                val reused = ConversionPreviews.prepare(repo, parent)
                assertEquals(row.id, reused.id)
                assertEquals(row.workId, reused.workId)
                proof.put(
                    "office",
                    JSONObject()
                        .put("leftPages", 2)
                        .put("rightPages", 2)
                        .put("actualOutputFormat", actual.extension)
                        .put("originalSha256", hash)
                        .put("outputSha256", resultHash)
                        .put("samePreparedTaskReused", true),
                )

                val left = File(folder, "native-original.pdf")
                val right = File(folder, "native-actual-result.pdf")
                pdf(left, "ORIGINAL 00123", "ORIGINAL PAGE TWO")
                pdf(right, "ACTUAL RESULT 00456")
                val nativeParent = fixtureTask(repo, left, right)
                val nativeRow = finished(repo, ConversionPreviews.prepare(repo, nativeParent).id)
                val native = ConversionPreviews.load(nativeRow)
                assertEquals(2, native.left.pages)
                assertEquals(1, native.right.pages)
                assertEquals(left.canonicalPath, native.left.pdf.canonicalPath)
                assertEquals(right.canonicalPath, native.right.pdf.canonicalPath)
                PDDocument.load(native.right.pdf).use {
                    val t = PDFTextStripper().getText(it)
                    assertTrue(t.contains("00456"))
                    assertFalse(t.contains("ORIGINAL"))
                }
                val manifest = File(nativeRow.resourceEntries().first { it.id == "result" }.path)
                assertFalse(manifest.readText().contains(folder.path))
                val moved = File(folder, "relocated").apply { mkdirs() }
                val replacements =
                    nativeRow
                        .resourceEntries()
                        .map { it.path }
                        .distinct()
                        .associateWith { old ->
                            File(old)
                                .copyTo(File(moved, "${UUID.randomUUID()}.${File(old).extension}"))
                                .path
                        }
                val relocated =
                    ConversionPreviews.load(
                        nativeRow.copy(
                            resources =
                                DocumentResources.encode(
                                        nativeRow.resourceEntries().map {
                                            it.copy(path = replacements.getValue(it.path))
                                        }
                                    )
                                    .toString()
                        )
                    )
                assertEquals(replacements[left.path], relocated.left.pdf.path)
                assertEquals(replacements[right.path], relocated.right.pdf.path)
                proof.put(
                    "native",
                    JSONObject()
                        .put("leftPages", 2)
                        .put("rightPages", 1)
                        .put("actualResultDiffersFromOriginal", true)
                        .put("manifestHasOnlyResourceIds", true)
                        .put("relocatedResourceGraphLoads", true),
                )

                val png = File(folder, "source-image.png")
                val webp = File(folder, "result-image.webp")
                val image = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
                image.eraseColor(Color.BLUE)
                png.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.eraseColor(Color.RED)
                webp.outputStream().use {
                    image.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it)
                }
                image.recycle()
                val imageRow =
                    finished(
                        repo,
                        ConversionPreviews.prepare(repo, fixtureTask(repo, png, webp)).id,
                    )
                val imagePreview = ConversionPreviews.load(imageRow)
                imagePreview.left.pdf.copyTo(File(folder, "image-left.pdf"))
                imagePreview.right.pdf.copyTo(File(folder, "image-right.pdf"))
                proof.put(
                    "image",
                    JSONObject()
                        .put("source", "blue PNG")
                        .put("actualResult", "red WebP")
                        .put("pages", 1),
                )
                File(folder, "proof.json").writeText(proof.toString(2))
                android.util.Log.i(
                    "ConversionPreviewFlowTest",
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
