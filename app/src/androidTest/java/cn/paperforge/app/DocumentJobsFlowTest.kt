package cn.paperforge.app

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentJobsFlowTest {
    @Test
    fun retainedAssemblyInputSurvivesSourceDeletionAndBackup() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val before = repo.dao.allDocuments().map { it.id }.toSet()
            val oldDraft = AssemblyDraft.read(app)
            val folder =
                File(app.cacheDir, "job-source-refs-${System.nanoTime()}").apply { mkdirs() }
            val files = mutableListOf<File>()
            val jobs = mutableListOf<String>()
            try {
                suspend fun source(label: String): DocumentRow {
                    val file = File(repo.exports, "$label-${System.nanoTime()}.pdf")
                    files += file
                    PDDocument().use { pdf ->
                        val page = PDPage()
                        pdf.addPage(page)
                        PDPageContentStream(pdf, page).use { c ->
                            c.beginText()
                            c.setFont(PDType1Font.HELVETICA, 18f)
                            c.newLineAtOffset(50f, 600f)
                            c.showText(label)
                            c.endText()
                        }
                        pdf.save(file)
                    }
                    return repo.registerPdf(file, label)
                }
                val first = source("KEPT_SOURCE")
                val second = source("REMOVED_SOURCE_STILL_NEEDED")
                val manifest = File(repo.folder(first.id), "referenced-plan.json")
                val plan =
                    AssemblyPlan(
                        listOf(first, second).map {
                            AssemblySource(
                                it.id,
                                it.title,
                                1,
                                PdfAssembly.sha256(File(it.pdfPath!!)),
                            )
                        },
                        listOf(first, second).map { AssemblyPage(sourceId = it.id, page = 0) },
                    )
                manifest.writeText(plan.json().toString())
                val row =
                    DocumentJobs.prepare(
                        repo,
                        "PDF",
                        JobInput.from(
                            workDataOf(
                                "mode" to "assemble",
                                "document" to first.id,
                                "options" to JSONObject().put("manifest", manifest.path).toString(),
                            )
                        ),
                    )
                jobs += row.id
                repo.deleteDocument(second)
                assertTrue(File(second.pdfPath!!).isFile)
                val archive = File(folder, "backup.zip")
                BackupStore.backup(repo, archive)
                BackupStore.restore(repo, Uri.fromFile(archive))
                val restored =
                    repo.dao.allTasks().single {
                        it.title == row.title &&
                            it.id != row.id &&
                            it.resourceEntries().any { r -> r.id == "manifest" }
                    }
                jobs += restored.id
                val input = JSONObject(restored.payload).getJSONObject("input")
                val restoredManifest =
                    File(JSONObject(input.getString("options")).getString("manifest"))
                assertEquals(
                    2,
                    AssemblyPlan.decode(JSONObject(restoredManifest.readText())).sources.size,
                )
                val id = DocumentJobs.retry(repo, restored)
                val info =
                    withTimeout(60000) {
                        WorkManager.getInstance(app).getWorkInfoByIdFlow(id).filterNotNull().first {
                            it.state.isFinished
                        }
                    }
                assertEquals(
                    info.outputData.getString("error"),
                    WorkInfo.State.SUCCEEDED,
                    info.state,
                )
                val result = File(info.outputData.getString("path")!!)
                assertTrue(PdfEngine.text(result).contains("REMOVED_SOURCE_STILL_NEEDED"))
                DocumentJobs.forget(repo, restored.id)
                assertTrue(result.isFile)
                assertNull(repo.dao.task(restored.id))
            } finally {
                jobs.forEach { id ->
                    repo.dao.task(id)?.let { row ->
                        if (row.state in LocalTaskRow.active) DocumentJobs.cancel(repo, row)
                        DocumentJobs.forget(repo, id)
                    }
                }
                repo.dao
                    .allDocuments()
                    .filter { it.id !in before }
                    .forEach { repo.deleteDocument(it) }
                files.forEach { it.delete() }
                AssemblyDraft.write(app, oldDraft)
                folder.listFiles()?.forEach { it.delete() }
                folder.delete()
            }
        }

    @Test
    fun workerRetryCheckpointBackupAndVerifiedSave() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val before = repo.dao.allDocuments().map { it.id }.toSet()
            val oldDraft = AssemblyDraft.read(app)
            val folder = File(app.cacheDir, "document-jobs-${System.nanoTime()}").apply { mkdirs() }
            val work = WorkManager.getInstance(app)
            suspend fun finished(id: UUID): WorkInfo =
                withTimeout(60000) {
                    work.getWorkInfoByIdFlow(id).filterNotNull().first { it.state.isFinished }
                }
            suspend fun task(id: UUID) = repo.dao.allTasks().single { it.workId == id.toString() }
            val kept = mutableSetOf<String>()
            try {
                val source = File(repo.exports, "jobs-proof-${System.nanoTime()}.pdf")
                PDDocument().use { pdf ->
                    repeat(2) { index ->
                        val page = PDPage(PDRectangle.A4)
                        pdf.addPage(page)
                        PDPageContentStream(pdf, page).use { c ->
                            c.beginText()
                            c.setFont(PDType1Font.HELVETICA, 20f)
                            c.newLineAtOffset(50f, 600f)
                            c.showText("DURABLE PAGE ${index+1}")
                            c.endText()
                        }
                    }
                    pdf.save(source)
                }
                val doc = repo.registerPdf(source, "任务中心验收 ${System.nanoTime()}")
                kept += doc.id
                val id =
                    DocumentJobs.enqueue(
                        repo,
                        "PDF",
                        workDataOf(
                            "mode" to "extract",
                            "document" to doc.id,
                            "options" to JSONObject().put("range", "2").toString(),
                        ),
                    )
                assertEquals(WorkInfo.State.SUCCEEDED, finished(id).state)
                val row = task(id)
                assertEquals(LocalTaskState.SUCCEEDED, row.state)
                val output = File(row.resourceEntries().single { it.id == "result" }.path)
                assertTrue(PdfEngine.text(output).contains("DURABLE PAGE 2"))
                val duplicate = DocumentJobs.start(repo, "PDF", workDataOf("task" to row.id), id)!!
                assertTrue(duplicate.completed)
                assertEquals(output, duplicate.result)

                val badId =
                    DocumentJobs.enqueue(
                        repo,
                        "PDF",
                        workDataOf(
                            "mode" to "extract",
                            "document" to doc.id,
                            "options" to JSONObject().put("range", "999").toString(),
                        ),
                    )
                assertEquals(WorkInfo.State.FAILED, finished(badId).state)
                val bad = task(badId)
                assertEquals(LocalTaskState.FAILED, bad.state)
                assertTrue(source.isFile)
                val payload = JSONObject(bad.payload)
                payload
                    .getJSONObject("input")
                    .put("options", JSONObject().put("range", "1").toString())
                repo.dao.putTask(bad.copy(payload = payload.toString()))
                val retry = DocumentJobs.retry(repo, bad)
                assertNull(DocumentJobs.start(repo, "PDF", workDataOf("task" to bad.id), badId))
                assertEquals(WorkInfo.State.SUCCEEDED, finished(retry).state)
                assertEquals(bad.id, task(retry).id)

                val scan = repo.newScan()
                repeat(2) { n ->
                    val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    val paint =
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.BLACK
                            textSize = 40f
                        }
                    canvas.drawText("PAPERFORGE", 50f, 150f, paint)
                    canvas.drawText("ONLY NEW PAGE", 50f, 230f, paint)
                    val file = repo.captureFile(scan.id)
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    bitmap.recycle()
                    repo.addCapture(scan.id, file)
                }
                val first = repo.dao.pages(scan.id).first()
                repo.cacheOcr(
                    first,
                    OcrLayout(
                        600,
                        800,
                        listOf(
                            OcrLine(
                                "PREVIOUS_COMPLETED",
                                listOf(
                                    PointN(.1f, .1f),
                                    PointN(.8f, .1f),
                                    PointN(.8f, .2f),
                                    PointN(.1f, .2f),
                                ),
                            )
                        ),
                        app.ocrEngine.id,
                        sourceRevision = first.revision,
                    ),
                )
                val cached = repo.dao.pages(scan.id).first()
                val recordsBefore = PageResources.paths(cached).toSet()
                val ocr =
                    DocumentJobs.prepare(
                        repo,
                        "OCR",
                        JobInput.from(workDataOf("document" to scan.id, "chinese" to false)),
                    )
                repo.dao.putTask(ocr.copy(state = LocalTaskState.RUNNING, progress = 50))
                DocumentJobs.recover(repo)
                assertEquals(LocalTaskState.PARTIAL, repo.dao.task(ocr.id)!!.state)
                val resumed = DocumentJobs.retry(repo, ocr)
                assertEquals(WorkInfo.State.SUCCEEDED, finished(resumed).state)
                val ocrDone = task(resumed)
                assertEquals(2, JSONObject(ocrDone.payload).getJSONObject("checkpoint").length())
                assertEquals(
                    recordsBefore,
                    PageResources.paths(repo.dao.pages(scan.id).first()).toSet(),
                )
                assertTrue(
                    File(ocrDone.resourceEntries().single { it.id == "result" }.path)
                        .readText()
                        .contains("PREVIOUS_COMPLETED")
                )

                val saveId =
                    DocumentJobs.enqueue(
                        repo,
                        "SAVE",
                        workDataOf(
                            "path" to output.path,
                            "uri" to "file:///proc/paperforge-unwritable.pdf",
                        ),
                    )
                assertEquals(WorkInfo.State.FAILED, finished(saveId).state)
                val failedSave = task(saveId)
                assertEquals(
                    output.path,
                    failedSave.resourceEntries().single { it.id == "save-source" }.path,
                )
                assertTrue(output.isFile)
                val target = File(folder, "saved.pdf")
                val savePayload = JSONObject(failedSave.payload)
                savePayload.getJSONObject("input").put("uri", Uri.fromFile(target).toString())
                repo.dao.putTask(failedSave.copy(payload = savePayload.toString()))
                assertEquals(
                    WorkInfo.State.SUCCEEDED,
                    finished(DocumentJobs.retry(repo, failedSave)).state,
                )
                assertEquals(PdfAssembly.sha256(output), PdfAssembly.sha256(target))

                // A provider can accept writes but return truncated data: this must remain a
                // failure.
                var truncate = true
                val provider =
                    object : ContentProvider() {
                        override fun onCreate() = true

                        override fun getType(uri: Uri) = "application/pdf"

                        override fun query(
                            uri: Uri,
                            projection: Array<out String>?,
                            selection: String?,
                            selectionArgs: Array<out String>?,
                            sortOrder: String?,
                        ): Cursor? = null

                        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

                        override fun update(
                            uri: Uri,
                            values: ContentValues?,
                            selection: String?,
                            selectionArgs: Array<out String>?,
                        ) = 0

                        override fun delete(
                            uri: Uri,
                            selection: String?,
                            selectionArgs: Array<out String>?,
                        ) = 0

                        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                            val file =
                                if (mode.contains('w') || !truncate) target
                                else File(folder, "truncated.pdf").apply { writeText("x") }
                            return ParcelFileDescriptor.open(
                                file,
                                ParcelFileDescriptor.parseMode(mode),
                            )
                        }
                    }
                provider.attachInfo(app, ProviderInfo().apply { authority = "paperforge-proof" })
                val resolver = ContentResolver.wrap(provider)
                val wrapped =
                    object : ContextWrapper(app) {
                        override fun getContentResolver(): ContentResolver = resolver
                    }
                val uri = Uri.parse("content://paperforge-proof/output")
                val verificationRepo = Repository(wrapped, repo.database)
                assertTrue(runCatching { verificationRepo.saveOutput(output, uri) }.isFailure)
                assertNotEquals(uri.toString(), repo.dao.output(output.path)?.externalUri)
                truncate = false
                verificationRepo.saveOutput(output, uri)
                assertEquals(uri.toString(), repo.dao.output(output.path)?.externalUri)

                val manifest = File(repo.folder(doc.id), "pending-assembly.json")
                manifest.writeText(
                    AssemblyPlan(
                            listOf(
                                AssemblySource(doc.id, doc.title, 2, PdfAssembly.sha256(source))
                            ),
                            listOf(AssemblyPage(sourceId = doc.id, page = 1)),
                        )
                        .json()
                        .toString()
                )
                val pending =
                    DocumentJobs.prepare(
                        repo,
                        "PDF",
                        JobInput.from(
                            workDataOf(
                                "mode" to "assemble",
                                "document" to doc.id,
                                "options" to JSONObject().put("manifest", manifest.path).toString(),
                            )
                        ),
                    )
                val archive = File(folder, "backup.zip")
                BackupStore.backup(repo, archive)
                BackupStore.restore(repo, Uri.fromFile(archive))
                val restored =
                    repo.dao.allTasks().single {
                        it.type == "PDF" &&
                            it.title == pending.title &&
                            it.id != pending.id &&
                            it.resourceEntries().any { r -> r.id == "manifest" }
                    }
                assertEquals(LocalTaskState.PARTIAL, restored.state)
                val restoredInput = JSONObject(restored.payload).getJSONObject("input")
                assertNotEquals(doc.id, restoredInput.getString("document"))
                val restoredManifest =
                    File(JSONObject(restoredInput.getString("options")).getString("manifest"))
                val restoredPlan = AssemblyPlan.decode(JSONObject(restoredManifest.readText()))
                assertEquals(restored.documentId, restoredPlan.sources.single().documentId)
                assertEquals(
                    WorkInfo.State.SUCCEEDED,
                    finished(DocumentJobs.retry(repo, restored)).state,
                )

                val noteProof = File(app.cacheDir, "native-note-fixed-proof.pdf")
                PdfMarks.save(
                    app,
                    source,
                    noteProof,
                    listOf(PdfMark(0, "note", listOf(PointN(.2f, .3f)), text = "可见便签")),
                    editable = true,
                )
            } finally {
                repo.dao
                    .allDocuments()
                    .filter { it.id !in before && it.id !in kept }
                    .forEach { repo.deleteDocument(it) }
                AssemblyDraft.write(app, oldDraft)
                folder.listFiles()?.forEach { it.delete() }
                folder.delete()
            }
        }
}
