package cn.paperforge.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.AutoExportPageSignal
import cn.paperforge.app.data.AutoExportSignal
import cn.paperforge.app.data.BackupState
import cn.paperforge.app.data.BackupStore
import cn.paperforge.app.data.DocumentJobs
import cn.paperforge.app.data.LocalTaskRow
import cn.paperforge.app.data.LocalTaskState
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.AutoExportRule
import cn.paperforge.app.model.AutoExportRules
import cn.paperforge.app.model.RecipeStep
import cn.paperforge.app.model.WorkflowRecipe
import cn.paperforge.app.model.WorkflowRecipes
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupRestoreFlowTest {
    @Test
    fun appendOrSkipIdentityPreservesEditableDocumentsAndMergesWorkflowsSafely() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = (context.applicationContext as PaperForgeApp).repository
        val before = repo.dao.allDocuments().map { it.id }.toSet()
        val settings = UserSettings.exportJson()
        val oldDraft = cn.paperforge.app.pdf.AssemblyDraft.read(context)
        val testTitle = "同名-${UUID.randomUUID()}"
        val archive = File(repo.exports, "restore-flow-${System.nanoTime()}.zip")
        val rebackup = File(repo.exports, "restore-flow-again-${System.nanoTime()}.zip")
        try {
            UserSettings.set("workflowRecipes", "[]")
            UserSettings.set("autoExportRules", "[]")
            val recipe =
                WorkflowRecipe(name = "备份配方", steps = listOf(RecipeStep(operation = "rename")))
            val rule =
                AutoExportRule(
                    name = "备份规则",
                    enabled = true,
                    folder = "test-${UUID.randomUUID()}",
                    recipe = recipe,
                    export =
                        JSONObject(
                                cn.paperforge.app.model.ScanExportOptions.current()
                                    .values()
                                    .toString()
                            )
                            .put("searchable", false),
                )
            WorkflowRecipes.save(recipe)
            AutoExportRules.save(rule)
            val retained = repo.newScan()
            val source = repo.newScan().copy(title = testTitle, folder = rule.folder)
            repo.dao.putDocument(source)
            val file = repo.captureFile(source.id)
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("scan-core/dark-desk-0.jpg")
                .use { input -> file.outputStream().use { input.copyTo(it) } }
            val page = PageRow(UUID.randomUUID().toString(), source.id, 0, file.path)
            repo.dao.putPage(page)
            repo.savePage(page.copy(rotation = 90))
            val reader = ReaderPreferences(context)
            reader.save(source.id, ReaderPosition(page = 0, zoom = 2f))
            reader.bookmarks(source.id, setOf(0))
            UserSettings.set("fontScale", .9f)
            cn.paperforge.app.pdf.AssemblyDraft.write(
                context,
                cn.paperforge.app.pdf.AssemblyPlan(title = "备份草稿"),
            )
            BackupStore.backup(repo, archive)
            repo.deleteDocument(source)
            val local = repo.newScan().copy(title = source.title)
            repo.dao.putDocument(local)
            WorkflowRecipes.save(recipe.copy(name = "本机修改"))
            AutoExportRules.save(rule.copy(name = "本机规则", enabled = true))
            UserSettings.set("fontScale", 1.25f)
            cn.paperforge.app.pdf.AssemblyDraft.write(
                context,
                cn.paperforge.app.pdf.AssemblyPlan(title = "本机草稿"),
            )
            val preview = BackupStore.preview(repo, Uri.fromFile(archive))
            assertTrue(preview.sameId >= 1)
            assertTrue(preview.sameName >= 1)
            assertEquals(
                1,
                BackupStore.restore(
                    repo,
                    Uri.fromFile(archive),
                    BackupStore.DuplicatePolicy.SKIP_SAME_ID,
                    false,
                ),
            )
            assertNotNull(repo.dao.document(retained.id))
            assertNotNull(repo.dao.document(local.id))
            val restored =
                repo.dao.allDocuments().single { it.title == source.title && it.id != local.id }
            val initialTaskIds =
                repo.dao
                    .allTasks()
                    .filter { it.type == "AUTO_EXPORT" && it.documentId == restored.id }
                    .map { it.id }
                    .toSet()
            delay(3000)
            assertEquals(
                initialTaskIds,
                repo.dao
                    .allTasks()
                    .filter { it.type == "AUTO_EXPORT" && it.documentId == restored.id }
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(1.25f, UserSettings.number("fontScale", 1f), .001f)
            assertEquals("本机草稿", cn.paperforge.app.pdf.AssemblyDraft.read(context).title)
            assertEquals(
                0,
                BackupStore.restore(
                    repo,
                    Uri.fromFile(archive),
                    BackupStore.DuplicatePolicy.SKIP_SAME_ID,
                    false,
                ),
            )
            fun fingerprint(doc: cn.paperforge.app.data.DocumentRow, page: PageRow) =
                AutoExportSignal(
                        doc,
                        AutoExportPageSignal(
                                page.id,
                                page.documentId,
                                page.position,
                                page.revision,
                                page.processingMetadata,
                                page.ocrText,
                            )
                            .signature,
                        0,
                    )
                    .fingerprint
            assertEquals(90, repo.dao.pages(restored.id).single().rotation)
            val initial = fingerprint(restored, repo.dao.pages(restored.id).single())
            assertEquals(initial, BackupState(context).baseline(restored.id))
            BackupStore.backup(repo, rebackup)
            ZipFile(rebackup).use { zip ->
                val metadata =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("paperforge.json")).bufferedReader().use {
                            it.readText()
                        }
                    )
                assertEquals(
                    source.id,
                    metadata.getJSONObject("documentOrigins").getString(restored.id),
                )
                assertEquals(
                    page.id,
                    metadata
                        .getJSONObject("pageOrigins")
                        .getString(repo.dao.pages(restored.id).single().id),
                )
            }
            assertEquals(2f, reader.load(restored.id).zoom, .001f)
            assertEquals(setOf(0), reader.bookmarks(restored.id))
            repo.undo(restored.id)
            val newTask =
                withTimeout(45000) {
                    while (true) {
                        val found =
                            repo.dao.allTasks().firstOrNull {
                                it.type == "AUTO_EXPORT" &&
                                    it.documentId == restored.id &&
                                    it.id !in initialTaskIds
                            }
                        if (found != null) return@withTimeout found
                        delay(250)
                    }
                    error("unreachable")
                }
            val completed =
                withTimeout(90000) {
                    while (true) {
                        val current = requireNotNull(repo.dao.task(newTask.id))
                        if (current.state !in LocalTaskRow.active) return@withTimeout current
                        delay(500)
                    }
                    error("unreachable")
                }
            assertEquals("自动导出任务未成功：${completed.error}", LocalTaskState.SUCCEEDED, completed.state)
            assertNotEquals(
                initial,
                fingerprint(repo.dao.document(restored.id)!!, repo.dao.pages(restored.id).single()),
            )
            assertEquals(0, repo.dao.pages(restored.id).single().rotation)
            assertTrue(File(repo.dao.pages(restored.id).single().original).isFile)
            assertTrue(WorkflowRecipes.all().any { it.id == recipe.id && it.name == "本机修改" })
            assertTrue(WorkflowRecipes.all().any { it.id != recipe.id && it.name == "备份配方" })
            assertTrue(AutoExportRules.all().any { it.id == rule.id && it.name == "本机规则" })
            assertTrue(AutoExportRules.all().any { it.id != rule.id && it.name == "备份规则" })
            assertTrue(AutoExportRules.all().single { it.id == rule.id }.enabled)
            assertTrue(AutoExportRules.all().filter { it.id != rule.id }.none { it.enabled })
        } finally {
            repo.dao
                .allTasks()
                .filter { it.type == "AUTO_EXPORT" && it.title.contains(testTitle) }
                .forEach { if (it.state in LocalTaskRow.active) DocumentJobs.cancel(repo, it) }
            repo.dao.allDocuments().filter { it.id !in before }.forEach { repo.deleteDocument(it) }
            repo.dao
                .allTasks()
                .filter { it.type == "AUTO_EXPORT" && it.title.contains(testTitle) }
                .forEach { DocumentJobs.forget(repo, it.id) }
            UserSettings.importJson(settings)
            cn.paperforge.app.pdf.AssemblyDraft.write(context, oldDraft)
            archive.delete()
            rebackup.delete()
        }
    }
}
