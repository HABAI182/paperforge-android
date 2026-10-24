package cn.paperforge.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** One focused flow using real WorkManager workers, raw JPEGs and a backup round trip. */
class CaptureTaskFlowTest {
    @Test
    fun recoverRawDraftsPreserveEditsRetryAndRestoreTasks() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as PaperForgeApp
        val repo = app.repository
        val documentsBefore = repo.dao.allDocuments().map { it.id }.toSet()
        val tasksBefore = repo.dao.allTasks().map { it.id }.toSet()
        val preferences = UserSettings.exportJson()
        val archive = File(repo.exports, "capture-tasks-${System.nanoTime()}.zip")
        val document = repo.newScan()
        suspend fun photo(target: String? = null): LocalTaskRow {
            val row = CaptureTasks.prepare(repo, document.id, null, target)
            instrument.context.assets.open("scan-core/dark-desk-0.jpg").use { input ->
                CaptureTasks.raw(row).outputStream().use { input.copyTo(it) }
            }
            return row
        }
        var locked = false
        try {
            CaptureTasks.decodeSlot.lock()
            locked = true
            UserSettings.set("filter", "ORIGINAL")
            val first = photo()
            val second = photo()
            UserSettings.set("filter", "TEXT")
            CaptureTasks.accept(repo, second.id)
            CaptureTasks.accept(repo, first.id)
            assertEquals(
                listOf(first.pageId, second.pageId),
                repo.dao.pages(document.id).map { it.id },
            )
            assertEquals("ORIGINAL", repo.dao.pages(document.id).first().filter)
            repo.savePage(
                repo.dao.pages(document.id).first().copy(filter = "BW", brightness = .17f)
            )
            val secondQueued = repo.dao.task(second.id)!!
            CaptureTasks.cancel(repo, secondQueued)
            CaptureTasks.accept(repo, second.id, true)
            assertNotEquals(secondQueued.workId, repo.dao.task(second.id)!!.workId)
            val recovered = photo()
            repo.database.openHelper.writableDatabase.execSQL(
                "DELETE FROM local_tasks WHERE id=?",
                arrayOf(recovered.id),
            )
            CaptureTasks.recover(repo)
            assertNotNull(repo.dao.task(recovered.id))
            assertTrue(repo.dao.pages(document.id).any { it.id == recovered.pageId })
            val retake = photo(second.pageId)
            CaptureTasks.accept(repo, retake.id)
            repo.savePage(
                repo.dao.pages(document.id).first { it.id == second.pageId }.copy(brightness = .29f)
            )
            val failed = photo()
            CaptureTasks.fail(repo, failed.id, "interrupted fixture")
            assertTrue(CaptureTasks.raw(failed).path in repo.originalResources(document.id))
            assertTrue(
                CaptureTasks.raw(failed).canonicalPath in
                    LibraryStorage(repo).report().protectedPaths
            )
            val pending = CaptureTasks.prepare(repo, document.id, null)
            BackupStore.backup(repo, archive)
            BackupStore.restore(repo, Uri.fromFile(archive))
            val restored =
                repo.dao.allDocuments().single {
                    it.id !in documentsBefore && it.id != document.id && it.title == document.title
                }
            val restoredTasks = repo.dao.tasksForDocument(restored.id)
            assertEquals(6, restoredTasks.size)
            assertTrue(
                restoredTasks.none {
                    it.id in
                        setOf(first.id, second.id, recovered.id, retake.id, failed.id, pending.id)
                }
            )
            val restoredFailure = restoredTasks.single { it.error == "interrupted fixture" }
            assertTrue(CaptureTasks.raw(restoredFailure).isFile)
            assertTrue(
                CaptureTasks.raw(restoredFailure)
                    .canonicalPath
                    .startsWith(repo.folder(restored.id).canonicalPath + File.separator)
            )
            val restoredFirst = repo.dao.pages(restored.id).first()
            assertTrue(
                JSONObject(restoredFirst.processingMetadata).getString("captureJob") in
                    restoredTasks.map { it.id }
            )
            CaptureTasks.decodeSlot.unlock()
            locked = false
            withTimeout(60000) {
                while (
                    repo.dao.allTasks().any {
                        it.documentId in setOf(document.id, restored.id) &&
                            it.state in LocalTaskRow.active
                    }
                ) delay(100)
            }
            val pages = repo.dao.pages(document.id)
            assertEquals("BW", pages.first { it.id == first.pageId }.filter)
            assertEquals(.17f, pages.first { it.id == first.pageId }.brightness, .001f)
            assertEquals(.29f, pages.first { it.id == second.pageId }.brightness, .001f)
            assertEquals(4, pages.size)
            assertTrue(repo.dao.task(retake.id)!!.detail.contains("另存"))
            assertEquals(LocalTaskState.FAILED, repo.dao.task(pending.id)!!.state)
            assertTrue(repo.dao.task(first.id)!!.detail.contains("修改"))
            assertEquals(4, repo.dao.pages(restored.id).size)
            repo.deleteDocument(document)
            assertNull(repo.dao.task(failed.id)!!.documentId)
            assertTrue(repo.dao.task(failed.id)!!.resourceEntries().isEmpty())
        } finally {
            if (locked) CaptureTasks.decodeSlot.unlock()
            repo.dao
                .allDocuments()
                .filter { it.id !in documentsBefore }
                .forEach { repo.deleteDocument(it) }
            repo.dao
                .allTasks()
                .filter { it.id !in tasksBefore }
                .forEach {
                    repo.database.openHelper.writableDatabase.execSQL(
                        "DELETE FROM local_tasks WHERE id=?",
                        arrayOf(it.id),
                    )
                }
            UserSettings.importJson(preferences)
            archive.delete()
        }
    }
}
