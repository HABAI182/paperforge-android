package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import cn.paperforge.app.pdf.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class DefaultExportNamesFlowTest {
    @Test
    fun defaultPdfSkipsOcrAndPublicRenamesKeepHistoryAndExplicitTemplates() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val oldBackend = UserSettings.string("ocrBackend", "MLKIT")
            val oldPack = UserSettings.string("ocrPackId", "")
            val ownedDocs = mutableListOf<DocumentRow>()
            val ownedTasks = mutableListOf<String>()
            val proof =
                File(app.filesDir, "export-names-proof-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                assertFalse(ScanExportOptions().searchable)
                assertFalse(ScanExportOptions.decode(JSONObject()).searchable)
                assertFalse(ScanExportOptions.current().searchable)
                UserSettings.set("ocrBackend", "PADDLE")
                UserSettings.set("ocrPackId", "absent-export-test-model")
                val doc = repo.newScan()
                ownedDocs += doc
                repo.rename(doc, "合同档案")
                val source = repo.captureFile(doc.id)
                val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                Canvas(bitmap)
                    .drawText(
                        "SHOULD STAY IMAGE",
                        25f,
                        100f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.BLACK
                            textSize = 26f
                        },
                    )
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val page =
                    PageRow(
                        UUID.randomUUID().toString(),
                        doc.id,
                        0,
                        source.path,
                        corners = Quad.Full.encode(),
                        filter = "ORIGINAL",
                    )
                repo.dao.putPage(page)
                suspend fun export(): File {
                    val row =
                        DocumentJobs.prepare(
                            repo,
                            "SCAN_EXPORT",
                            JobInput(
                                JSONObject()
                                    .put("document", doc.id)
                                    .put("format", "PDF")
                                    .put("resolution", "DPI:100")
                            ),
                        )
                    ownedTasks += row.id
                    assertFalse(JSONObject(row.payload).getJSONObject("input").has("ocrRequest"))
                    DocumentJobs.schedule(repo, row)
                    val work =
                        withTimeout(20000) {
                            WorkManager.getInstance(app)
                                .getWorkInfoByIdFlow(UUID.fromString(row.workId))
                                .filterNotNull()
                                .first { it.state.isFinished }
                        }
                    assertEquals(WorkInfo.State.SUCCEEDED, work.state)
                    val file = File(work.outputData.getString("path")!!)
                    repo.dao.documentByPath(file.path)?.let { ownedDocs += it }
                    assertEquals("合同档案.pdf", file.name)
                    assertTrue(PdfEngine.text(file).isBlank())
                    assertNull(repo.dao.pages(doc.id).single { it.id == page.id }.ocrLayout)
                    return file
                }
                val first = export()
                val firstHash = PdfAssembly.sha256(first)
                val second = export()
                assertNotEquals(first.path, second.path)
                assertEquals(firstHash, PdfAssembly.sha256(first))
                first.copyTo(File(proof, "default-no-ocr.pdf"))
                val legacy = File(repo.exports, "legacy-export-${UUID.randomUUID()}.pdf")
                first.copyTo(legacy)
                val result = repo.registerPdf(legacy, "旧名字", listed = false)
                ownedDocs += result
                repo.rename(result, "最终稿.pdf")
                assertEquals("最终稿.pdf", OutputNames.display(repo, legacy))
                assertTrue(legacy.isFile)
                assertEquals(firstHash, PdfAssembly.sha256(legacy))
                val pending =
                    DocumentJobs.prepare(
                        repo,
                        "SAVE",
                        JobInput(
                            JSONObject()
                                .put("path", legacy.path)
                                .put("treeUri", "content://owned-name-snapshot/tree")
                        ),
                    )
                ownedTasks += pending.id
                repo.rename(result, "更新稿")
                assertEquals(
                    "最终稿.pdf",
                    JSONObject(pending.payload).getJSONObject("input").getString("displayName"),
                )
                assertEquals("更新稿.pdf", OutputNames.display(repo, legacy))
                val custom = File(proof, "2026-自定义模板.pdf")
                first.copyTo(custom)
                val templateDoc = repo.registerPdf(custom, "自定义模板", listed = false)
                ownedDocs += templateDoc
                assertEquals("2026-自定义模板.pdf", OutputNames.display(repo, custom))
                val report =
                    JSONObject()
                        .put("defaultSearchable", false)
                        .put("ocrLayoutCreated", false)
                        .put("sameCleanName", first.name)
                        .put("separateInternalDirectories", first.parent != second.parent)
                        .put("firstResultUnchanged", true)
                        .put("renamedDisplayName", OutputNames.display(repo, legacy))
                        .put("queuedNameFrozen", "最终稿.pdf")
                        .put("explicitTemplatePreserved", true)
                File(proof, "proof.json").writeText(report.toString(2))
                File(app.filesDir, "export-names-latest.txt").writeText(proof.name)
            } finally {
                UserSettings.set("ocrBackend", oldBackend)
                UserSettings.set("ocrPackId", oldPack)
                for (id in ownedTasks) {
                    repo.dao.task(id)?.let {
                        if (it.state in LocalTaskRow.active) DocumentJobs.cancel(repo, it)
                    }
                    DocumentJobs.forget(repo, id)
                }
                ownedDocs.asReversed().forEach {
                    repo.dao.document(it.id)?.let { d -> repo.deleteDocument(d) }
                }
            }
        }
}
