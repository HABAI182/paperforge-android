package cn.paperforge.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class ConversionBatchFlowTest {
    private suspend fun terminal(repo: Repository, id: String): LocalTaskRow =
        withTimeout(120000) {
            while (true) {
                val row = checkNotNull(repo.dao.task(id))
                if (row.state !in LocalTaskRow.active) return@withTimeout row
                delay(200)
            }
            error("unreachable")
        }

    private fun result(row: LocalTaskRow) =
        File(row.resourceEntries().first { it.id == "result" }.path)

    @Test
    fun independentMembersOverridesFailedOnlyRetryAndRestoredGroups() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val folder = File(app.filesDir, "batch-proof-${UUID.randomUUID()}").apply { mkdirs() }
            val oldTasks = repo.dao.allTasks().map { it.id }.toSet()
            val oldDocs = repo.dao.allDocuments().map { it.id }.toSet()
            val language = UserSettings.string("ocrLanguage", "CHINESE")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            try {
                val a = File(folder, "first.txt").apply { writeText("批量一 00123\nFirst document") }
                val b = File(folder, "second.txt").apply { writeText("批量二 00456\nSecond document") }
                val batch =
                    BatchConversions.add(
                        repo,
                        listOf(
                            Uri.fromFile(a),
                            Uri.fromFile(File(folder, "missing.txt")),
                            Uri.fromFile(b),
                        ),
                        BatchPreset(),
                    )
                val initial =
                    repo.dao
                        .allTasks()
                        .filter {
                            BatchConversions.member(it) && BatchConversions.batchId(it) == batch
                        }
                        .sortedBy { BatchConversions.input(it).optInt("batchIndex") }
                assertEquals(3, initial.size)
                assertEquals(LocalTaskState.FAILED, initial[1].state)
                assertTrue(BatchConversions.source(initial[0])!!.isFile)
                assertTrue(BatchConversions.source(initial[2])!!.isFile)
                b.writeText("external provider changed after import")
                BatchConversions.configure(
                    repo,
                    initial[2].id,
                    BatchPreset(
                        "TEXT_PDF",
                        TextPdfOptions(fontSize = 18f, paper = "A5", landscape = true)
                            .json()
                            .toString(),
                    ),
                )
                assertEquals(2, BatchConversions.runPending(repo, batch))
                val first = terminal(repo, initial[0].id)
                val second = terminal(repo, initial[2].id)
                assertEquals(first.error, LocalTaskState.SUCCEEDED, first.state)
                assertEquals(second.error, LocalTaskState.SUCCEEDED, second.state)
                PDDocument.load(result(first)).use {
                    assertTrue(PDFTextStripper().getText(it).contains("00123"))
                }
                PDDocument.load(result(second)).use {
                    assertTrue(it.getPage(0).mediaBox.width > it.getPage(0).mediaBox.height)
                    assertTrue(PDFTextStripper().getText(it).contains("00456"))
                }
                val firstHash = PdfAssembly.sha256(result(first))
                assertEquals(0, BatchConversions.runPending(repo, batch, true))
                val repair = File(folder, "reselected.txt").apply { writeText("重选原件 00789") }
                BatchConversions.replace(repo, initial[1].id, Uri.fromFile(repair))
                assertEquals(1, BatchConversions.runPending(repo, batch))
                assertEquals(LocalTaskState.SUCCEEDED, terminal(repo, initial[1].id).state)

                val md =
                    File(folder, "chapter.md").apply {
                        writeText("# Batch chapter\n\nOriginal **body** 00123")
                    }
                val bad = File(folder, "broken.epub").apply { writeText("broken archive retained") }
                BatchConversions.add(
                    repo,
                    listOf(Uri.fromFile(md), Uri.fromFile(bad)),
                    BatchPreset("MARKUP", "{\"target\":\"HTML\"}"),
                    batch,
                )
                val markup =
                    repo.dao.allTasks().filter {
                        BatchConversions.member(it) &&
                            BatchConversions.batchId(it) == batch &&
                            BatchPreset.from(BatchConversions.input(it)).kind == "MARKUP"
                    }
                assertEquals(2, BatchConversions.runPending(repo, batch))
                val goodMarkup =
                    terminal(
                        repo,
                        markup
                            .first { BatchConversions.input(it).getString("name").endsWith(".md") }
                            .id,
                    )
                val failedMarkup =
                    terminal(
                        repo,
                        markup
                            .first {
                                BatchConversions.input(it).getString("name").endsWith(".epub")
                            }
                            .id,
                    )
                assertEquals(goodMarkup.error, LocalTaskState.SUCCEEDED, goodMarkup.state)
                assertEquals(LocalTaskState.FAILED, failedMarkup.state)
                val badOriginal = BatchConversions.source(failedMarkup)!!
                val badHash = PdfAssembly.sha256(badOriginal)
                val frozenRequest =
                    BatchConversions.input(failedMarkup).getJSONObject("ocrRequest").toString()
                val markupHash = PdfAssembly.sha256(result(goodMarkup))
                UserSettings.set("ocrLanguage", if (language == "LATIN") "CHINESE" else "LATIN")
                assertEquals(1, BatchConversions.runPending(repo, batch, true))
                val retried = terminal(repo, failedMarkup.id)
                assertEquals(LocalTaskState.FAILED, retried.state)
                assertEquals(
                    frozenRequest,
                    BatchConversions.input(retried).getJSONObject("ocrRequest").toString(),
                )
                assertEquals(goodMarkup.workId, repo.dao.task(goodMarkup.id)!!.workId)
                assertEquals(markupHash, PdfAssembly.sha256(result(goodMarkup)))
                BatchConversions.replace(repo, failedMarkup.id, Uri.fromFile(md))
                assertTrue(
                    repo.dao.task(failedMarkup.id)!!.resourceEntries().any {
                        it.role == "conversion-prior-input" && it.path == badOriginal.path
                    }
                )
                assertEquals(badHash, PdfAssembly.sha256(badOriginal))
                assertEquals(1, BatchConversions.runPending(repo, batch))
                assertEquals(LocalTaskState.SUCCEEDED, terminal(repo, failedMarkup.id).state)
                assertEquals(first.workId, repo.dao.task(first.id)!!.workId)
                assertEquals(firstHash, PdfAssembly.sha256(result(first)))

                suspend fun asset(name: String, preset: BatchPreset): String {
                    val source = File(folder, name)
                    InstrumentationRegistry.getInstrumentation().context.assets.open(name).use {
                        input ->
                        source.outputStream().use { input.copyTo(it) }
                    }
                    val before = repo.dao.allTasks().map { it.id }.toSet()
                    BatchConversions.add(repo, listOf(Uri.fromFile(source)), preset, batch)
                    return repo.dao.allTasks().first { it.id !in before }.id
                }
                val office =
                    asset("office-job-input.docx", BatchPreset("OFFICE", "{\"format\":\"pdf\"}"))
                val layout =
                    asset(
                        "layout-two-pages.djvu",
                        BatchPreset("LAYOUT", "{\"target\":\"PDF\",\"dpi\":100}"),
                    )
                val table =
                    asset(
                        "phone-office-table.pdf",
                        BatchPreset(
                            "TABLE_ANALYZE",
                            "{\"tableStrategy\":\"lines_strict\",\"ocr\":false}",
                        ),
                    )
                val pdf =
                    asset(
                        "phone-borderless-table.pdf",
                        BatchPreset("PDF_TOOL", "{\"mode\":\"txt\"}"),
                    )
                val image = File(folder, "image.jpg")
                InstrumentationRegistry.getInstrumentation()
                    .context
                    .assets
                    .open("scan-core/dark-desk-0.jpg")
                    .use { source -> image.outputStream().use { source.copyTo(it) } }
                val beforeImage = repo.dao.allTasks().map { it.id }.toSet()
                BatchConversions.add(
                    repo,
                    listOf(Uri.fromFile(image)),
                    BatchPreset(
                        "IMAGES",
                        ImageConversionOptions(format = "png", edge = 512).json().toString(),
                    ),
                    batch,
                )
                val imageId = repo.dao.allTasks().first { it.id !in beforeImage }.id
                assertEquals(5, BatchConversions.runPending(repo, batch))
                val modes = JSONObject()
                for (id in listOf(office, layout, table, pdf, imageId)) {
                    val row = terminal(repo, id)
                    assertEquals("${row.title}: ${row.error}", LocalTaskState.SUCCEEDED, row.state)
                    assertTrue(result(row).length() > 0)
                    val mode = BatchConversions.input(row).getString("kind")
                    val copy = File(folder, "$mode.${result(row).extension}")
                    result(row).copyTo(copy, overwrite = true)
                    modes.put(
                        mode,
                        JSONObject()
                            .put("task", row.id)
                            .put("result", copy.path)
                            .put("bytes", copy.length()),
                    )
                }
                assertEquals("PDF", repo.dao.task(pdf)!!.type)
                val queued = MutableStateFlow<UUID?>(null)
                val workspace =
                    TableWorkspace(repo, scope, {}, { queued.value = it }, { error(it) })
                workspace.result(repo.dao.task(table)!!)
                assertTrue(workspace.draft.value!!.tables.isNotEmpty())
                val review = repo.dao.task(workspace.draft.value!!.taskId)!!
                assertEquals(batch, BatchConversions.batchId(review))
                assertEquals(table, BatchConversions.input(review).getString("batchSourceTask"))
                workspace.export(TableExportOptions(format = "XLSX"))
                val exportWork = withTimeout(30000) { queued.filterNotNull().first() }
                val export =
                    withTimeout(30000) {
                        while (true) {
                            val row =
                                repo.dao.allTasks().firstOrNull {
                                    it.workId == exportWork.toString()
                                }
                            if (row != null) return@withTimeout row
                            delay(100)
                        }
                        error("unreachable")
                    }
                val exported = terminal(repo, export.id)
                assertEquals(exported.error, LocalTaskState.SUCCEEDED, exported.state)
                assertEquals(batch, BatchConversions.batchId(exported))
                assertEquals(table, BatchConversions.input(exported).getString("batchSourceTask"))
                ZipFile(result(exported)).use {
                    assertNotNull(it.getEntry("xl/worksheets/sheet1.xml"))
                }

                // Restore a reduced real backup, exercising ID/path remapping without cloning the
                // library.
                val sentinel = repo.newScan()
                val all = File(folder, "all-local-only.zip")
                BackupStore.backup(repo, all)
                val subset = File(folder, "batch-backup.zip")
                val groupTasks =
                    repo.dao.allTasks().filter { BatchConversions.batchId(it) == batch }
                val documentIds = groupTasks.mapNotNull { it.documentId }.toSet() + sentinel.id
                ZipFile(all).use { zip ->
                    val metadata =
                        JSONObject(
                            zip.getInputStream(zip.getEntry("paperforge.json"))
                                .bufferedReader()
                                .use { it.readText() }
                        )
                    val docs = metadata.getJSONArray("documents")
                    metadata.put(
                        "documents",
                        JSONArray(
                            (0 until docs.length())
                                .map { docs.getJSONObject(it) }
                                .filter { it.getString("id") in documentIds }
                        ),
                    )
                    val archived = metadata.getJSONArray("tasks")
                    val taskIds = groupTasks.map { it.id }.toSet()
                    metadata.put(
                        "tasks",
                        JSONArray(
                            (0 until archived.length())
                                .map { archived.getJSONObject(it) }
                                .filter { it.getString("id") in taskIds }
                        ),
                    )
                    listOf("pages", "history", "outputs", "studyNotes").forEach {
                        metadata.put(it, JSONArray())
                    }
                    metadata
                        .getJSONObject("preferences")
                        .getJSONObject("preferences")
                        .put("workflowRecipes", "[]")
                        .put("autoExportRules", "[]")
                    val raw = metadata.toString().toByteArray()
                    val integrity =
                        JSONObject(
                            zip.getInputStream(zip.getEntry("integrity.json"))
                                .bufferedReader()
                                .use { it.readText() }
                        )
                    integrity
                        .getJSONObject("paperforge.json")
                        .put(
                            "sha256",
                            MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") {
                                "%02x".format(it.toInt() and 255)
                            },
                        )
                        .put("bytes", raw.size)
                    ZipOutputStream(subset.outputStream()).use { target ->
                        zip.entries().asSequence().forEach { entry ->
                            target.putNextEntry(ZipEntry(entry.name))
                            when (entry.name) {
                                "paperforge.json" -> target.write(raw)
                                "integrity.json" -> target.write(integrity.toString().toByteArray())
                                else -> zip.getInputStream(entry).use { it.copyTo(target) }
                            }
                            target.closeEntry()
                        }
                    }
                }
                val beforeRestore = repo.dao.allTasks().map { it.id }.toSet()
                BackupStore.restore(
                    repo,
                    Uri.fromFile(subset),
                    BackupStore.DuplicatePolicy.APPEND_ALL,
                    false,
                )
                val restored =
                    repo.dao.allTasks().filter {
                        it.id !in beforeRestore && BatchConversions.batchId(it).isNotBlank()
                    }
                assertEquals(groupTasks.size, restored.size)
                val restoredId = restored.map(BatchConversions::batchId).distinct().single()
                assertNotEquals(batch, restoredId)
                assertTrue(
                    restored.all { row -> row.resourceEntries().all { File(it.path).isFile } }
                )
                val restoredRelated =
                    restored.filter { BatchConversions.input(it).optBoolean("batchRelated") }
                assertTrue(restoredRelated.size >= 2)
                assertTrue(
                    restoredRelated.all {
                        BatchConversions.input(it).getString("batchSourceTask") in
                            restored.map { row -> row.id }
                    }
                )
                assertTrue(
                    restoredRelated.none {
                        BatchConversions.input(it).getString("batchSourceTask") == table
                    }
                )
                val tableCopy = File(folder, "table-export.xlsx")
                result(exported).copyTo(tableCopy, overwrite = true)
                result(first).copyTo(File(folder, "first.pdf"), overwrite = true)
                result(second).copyTo(File(folder, "second-override.pdf"), overwrite = true)
                File(folder, "proof.json")
                    .writeText(
                        JSONObject()
                            .put("batch", batch)
                            .put("sourceRetentionFailureIsolated", true)
                            .put("perItemOverride", true)
                            .put("completedMembersUnchangedOnRetry", true)
                            .put("frozenOcrRequestPreserved", true)
                            .put("priorBadOriginalRetained", true)
                            .put("modes", modes)
                            .put("tableExport", tableCopy.path)
                            .put("restoredBatch", restoredId)
                            .put("restoredMembers", restored.size)
                            .put("relatedTaskIdsRemapped", true)
                            .toString(2)
                    )
                android.util.Log.i(
                    "ConversionBatchFlowTest",
                    "Proof: ${File(folder, "proof.json").path}",
                )
            } finally {
                UserSettings.set("ocrLanguage", language)
                File(folder, "all-local-only.zip").delete()
                File(folder, "batch-backup.zip").delete()
                scope.cancel()
                // Keep small proof outputs available for independent readback; remove imported
                // library copies.
                repo.dao
                    .allDocuments()
                    .filter { it.id !in oldDocs }
                    .forEach { repo.deleteDocument(it) }
                repo.dao
                    .allTasks()
                    .filter { it.id !in oldTasks && BatchConversions.batchId(it).isNotBlank() }
                    .forEach { repo.dao.deleteTask(it.id) }
            }
        }
}
