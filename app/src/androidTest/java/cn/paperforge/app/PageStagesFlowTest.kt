package cn.paperforge.app

import android.graphics.*
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class PageStagesFlowTest {
    @Test
    fun interruptedScanBackupImageArchiveAndTextLayerResumeOnlyMissingPages() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val database = Room.inMemoryDatabaseBuilder(app, PaperDatabase::class.java).build()
            val repo = Repository(app, database)
            val proof =
                File(app.filesDir, "page-stages-proof-${UUID.randomUUID()}").apply { mkdirs() }
            val oldDpi = UserSettings.number("customDpi", 300f)
            val metrics = JSONObject()
            suspend fun job(type: String, input: JSONObject): JobHandle {
                val row = DocumentJobs.prepare(repo, type, JobInput(input))
                return JobHandle(row, JobInput(JSONObject(row.payload).getJSONObject("input")))
            }
            suspend fun resume(handle: JobHandle): JobHandle {
                val row =
                    checkNotNull(repo.dao.task(handle.row.id))
                        .copy(state = LocalTaskState.RUNNING, workId = UUID.randomUUID().toString())
                repo.dao.putTask(row)
                return JobHandle(row, JobInput(JSONObject(row.payload).getJSONObject("input")))
            }
            suspend fun interrupted(action: suspend () -> Unit) {
                var stopped = false
                try {
                    action()
                } catch (_: CancellationException) {
                    stopped = true
                }
                assertTrue("The first attempt must actually stop", stopped)
            }
            try {
                val doc = repo.newScan()
                repeat(3) { index ->
                    val image = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(image)
                    canvas.drawColor(Color.WHITE)
                    val paint =
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = listOf(Color.RED, Color.BLUE, Color.GREEN)[index]
                        }
                    canvas.drawRect(30f, 30f, 370f, 130f, paint)
                    paint.color = Color.BLACK
                    paint.textSize = 38f
                    canvas.drawText("SOURCE ${index+1}", 45f, 250f, paint)
                    val file = repo.captureFile(doc.id)
                    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                    repo.dao.putPage(
                        PageRow(
                            UUID.randomUUID().toString(),
                            doc.id,
                            index,
                            file.path,
                            filter = "ORIGINAL",
                        )
                    )
                }
                val originalHashes =
                    repo.dao.pages(doc.id).map { PdfAssembly.sha256(File(it.original)) }
                UserSettings.set("customDpi", 180f)
                val scanJob =
                    job(
                        "SCAN_EXPORT",
                        JSONObject()
                            .put("document", doc.id)
                            .put("format", "PDF")
                            .put("resolution", "CUSTOM"),
                    )
                assertEquals("DPI:180", scanJob.input.getString("resolution"))
                UserSettings.set("customDpi", 300f)
                val first = JobFileStages(repo, scanJob)
                interrupted {
                    ScanPageStages.scan(
                        app,
                        repo.dao.pages(doc.id),
                        File(proof, "interrupted.pdf"),
                        first,
                        "Auto",
                        90,
                        false,
                        scanJob.input.getString("resolution")!!,
                        null,
                        true,
                    ) {
                        if (it < 100)
                            throw CancellationException(
                                "authored interruption after a completed source page"
                            )
                    }
                }
                assertEquals(1, first.generated)
                assertFalse(File(proof, "interrupted.pdf").exists())
                DocumentJobs.fail(
                    repo,
                    scanJob,
                    CancellationException("authored interruption"),
                    true,
                )
                val backup = File(proof, "checkpoint-backup.zip")
                BackupStore.backup(repo, backup)
                BackupStore.restore(repo, Uri.fromFile(backup))
                val restoredRow =
                    repo.dao.allTasks().single {
                        it.id != scanJob.row.id && it.type == "SCAN_EXPORT"
                    }
                val restored =
                    resume(
                        JobHandle(
                            restoredRow,
                            JobInput(JSONObject(restoredRow.payload).getJSONObject("input")),
                        )
                    )
                val restoredPages = repo.dao.pages(restored.row.documentId!!)
                val continued = JobFileStages(repo, restored)
                val output = File(proof, "resumed-scan.pdf")
                ScanPageStages.scan(
                    app,
                    restoredPages,
                    output,
                    continued,
                    "Auto",
                    90,
                    false,
                    restored.input.getString("resolution")!!,
                    null,
                    true,
                ) {}
                assertEquals(1, continued.reused)
                assertEquals(2, continued.generated)
                PDDocument.load(output).use { pdf ->
                    assertEquals(3, pdf.numberOfPages)
                    assertNull(
                        "Chunking must not give every output page label 1",
                        pdf.documentCatalog.pageLabels,
                    )
                    assertEquals(output.nameWithoutExtension, pdf.documentInformation.title)
                }
                metrics.put(
                    "scanAfterBackup",
                    JSONObject()
                        .put("reused", continued.reused)
                        .put("generated", continued.generated)
                        .put("stages", continued.entries),
                )
                val direct = File(proof, "direct-scan.pdf")
                PdfEngine.scan(
                    repo.dao.pages(doc.id),
                    direct,
                    context = app,
                    resolution = "DPI:180",
                    lossless = true,
                )
                val idJob =
                    job(
                        "SCAN_EXPORT",
                        JSONObject().put("document", doc.id).put("format", "PDF").put("paper", "ID"),
                    )
                val idStages = JobFileStages(repo, idJob)
                ScanPageStages.scan(
                    app,
                    repo.dao.pages(doc.id),
                    File(proof, "id-before.pdf"),
                    idStages,
                    "ID",
                    90,
                    false,
                    "DPI:150",
                    null,
                    true,
                ) {}
                assertEquals(2, idStages.generated)
                val changed =
                    repo.dao.pages(doc.id).mapIndexed { index, p ->
                        if (index == 1) p.copy(brightness = .15f) else p
                    }
                val idChanged = JobFileStages(repo, idJob)
                ScanPageStages.scan(
                    app,
                    changed,
                    File(proof, "id-after.pdf"),
                    idChanged,
                    "ID",
                    90,
                    false,
                    "DPI:150",
                    null,
                    true,
                ) {}
                assertEquals(1, idChanged.reused)
                assertEquals(1, idChanged.generated)
                PDDocument.load(File(proof, "id-after.pdf")).use {
                    assertEquals(2, it.numberOfPages)
                }
                metrics.put(
                    "idPairEdit",
                    JSONObject()
                        .put("reused", idChanged.reused)
                        .put("generated", idChanged.generated),
                )
                val imageJob =
                    job(
                        "PDF",
                        JSONObject()
                            .put("mode", "images-png")
                            .put("inputs", JSONArray(listOf(output.path))),
                    )
                val imageFirst = JobFileStages(repo, imageJob)
                interrupted {
                    PdfTools.images(
                        output,
                        File(proof, "interrupted.zip"),
                        "",
                        "png",
                        600,
                        stages = imageFirst,
                    ) {
                        if (it < 100) throw CancellationException("after a completed image")
                    }
                }
                val imageRetry = resume(imageJob)
                val imageNext = JobFileStages(repo, imageRetry)
                val archive = File(proof, "resumed-images.zip")
                PdfTools.images(output, archive, "", "png", 600, stages = imageNext) {}
                assertEquals(1, imageNext.reused)
                assertEquals(2, imageNext.generated)
                ZipFile(archive).use { zip ->
                    val index =
                        JSONObject(
                                zip.getInputStream(zip.getEntry("images.json"))
                                    .bufferedReader()
                                    .readText()
                            )
                            .getJSONArray("pages")
                    assertEquals(3, index.length())
                    for (i in 0 until index.length()) assertNotNull(
                        zip.getEntry(index.getJSONObject(i).getString("file"))
                    )
                    assertEquals(3, zip.entries().asSequence().count { it.name.endsWith(".png") })
                }
                val previewJob =
                    job(
                        "CONVERT",
                        JSONObject()
                            .put("kind", "CONVERSION_PREVIEW")
                            .put("inputs", JSONArray(listOf(output.path, archive.path)))
                            .put(
                                "hashes",
                                JSONArray(
                                    listOf(PdfAssembly.sha256(output), PdfAssembly.sha256(archive))
                                ),
                            ),
                    )
                val previewManifest = ConversionPreviews.run(repo, previewJob) { _, _ -> }
                val previewJson = JSONObject(previewManifest.readText())
                val previewId = previewJson.getJSONArray("sides").getJSONObject(1).getString("pdf")
                val previewRight =
                    File(
                        repo.dao
                            .task(previewJob.row.id)!!
                            .resourceEntries()
                            .single { it.id == previewId }
                            .path
                    )
                PDDocument.load(previewRight).use { assertEquals(3, it.numberOfPages) }
                previewRight.copyTo(File(proof, "image-preview.pdf"))
                metrics.put(
                    "imagesAfterRestart",
                    JSONObject()
                        .put("reused", imageNext.reused)
                        .put("generated", imageNext.generated),
                )
                val textJob =
                    job(
                        "PDF",
                        JSONObject()
                            .put("mode", "searchable")
                            .put("inputs", JSONArray(listOf(output.path))),
                    )
                var ocrCalls = 0
                val engine =
                    object : OcrEngine {
                        override val id = "page-checkpoint-supplied-layout-fixture"

                        override suspend fun layout(
                            page: PageRow,
                            chinese: Boolean,
                            options: OcrOptions,
                        ): OcrLayout = error("bitmap path expected")

                        override suspend fun recognizeBitmap(
                            source: Bitmap,
                            chinese: Boolean,
                            options: OcrOptions,
                        ): OcrLayout {
                            ocrCalls++
                            return OcrLayout(
                                source.width,
                                source.height,
                                listOf(
                                    OcrLine(
                                        "OCR PAGE $ocrCalls",
                                        listOf(
                                            PointN(.1f, .35f),
                                            PointN(.85f, .35f),
                                            PointN(.85f, .45f),
                                            PointN(.1f, .45f),
                                        ),
                                    )
                                ),
                                id,
                            )
                        }
                    }
                val textFirst = JobFileStages(repo, textJob)
                interrupted {
                    PdfSearchable.convert(
                        app,
                        output,
                        File(proof, "interrupted-text.pdf"),
                        engine,
                        textFirst,
                    ) {
                        if (it < 100) throw CancellationException("after a completed OCR page")
                    }
                }
                val textNext = JobFileStages(repo, resume(textJob))
                val searchable = File(proof, "resumed-searchable.pdf")
                PdfSearchable.convert(app, output, searchable, engine, textNext) {}
                assertEquals(3, ocrCalls)
                assertEquals(1, textNext.reused)
                assertEquals(2, textNext.generated)
                assertTrue(PdfEngine.text(searchable).contains("OCR PAGE 1"))
                assertTrue(PdfEngine.text(searchable).contains("OCR PAGE 3"))
                metrics.put(
                    "textAfterRestart",
                    JSONObject()
                        .put("reused", textNext.reused)
                        .put("generated", textNext.generated)
                        .put("totalOcrCalls", ocrCalls),
                )
                assertEquals(
                    originalHashes,
                    repo.dao.pages(doc.id).map { PdfAssembly.sha256(File(it.original)) },
                )
                metrics
                    .put("sourceUnchanged", true)
                    .put("frozenResolution", scanJob.input.getString("resolution"))
                File(proof, "proof.json").writeText(metrics.toString(2))
                val review = File(proof, "review").apply { mkdirs() }
                listOf(
                        "proof.json",
                        "resumed-scan.pdf",
                        "direct-scan.pdf",
                        "id-before.pdf",
                        "id-after.pdf",
                        "resumed-images.zip",
                        "resumed-searchable.pdf",
                        "image-preview.pdf",
                    )
                    .forEach { File(proof, it).copyTo(File(review, it)) }
                android.util.Log.i("PageStagesFlowTest", "Proof: ${proof.path}")
            } finally {
                UserSettings.set("customDpi", oldDpi)
                for (row in repo.dao.allTasks()) {
                    repo.dao.putTask(row.copy(state = LocalTaskState.CANCELLED))
                    DocumentJobs.forget(repo, row.id)
                }
                for (doc in repo.dao.allDocuments()) repo.deleteDocument(doc)
                database.close()
            }
        }
}
