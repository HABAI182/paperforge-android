package cn.paperforge.app

import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * 此页重拍: the page shows the new photo as soon as its paper is found, not only after the slower finish, and the
 * finished page, repeated retakes and failed retakes stay consistent. Real WorkManager workers and raw JPEGs.
 */
class RetakePreviewTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val repo = (instrument.targetContext.applicationContext as PaperForgeApp).repository

    private suspend fun photo(document: String, asset: String, target: String? = null): LocalTaskRow {
        val row = CaptureTasks.prepare(repo, document, null, target)
        instrument.context.assets.open(asset).use { input ->
            CaptureTasks.raw(row).outputStream().use { input.copyTo(it) }
        }
        return row
    }

    private suspend fun settle(vararg ids: String, seconds: Long = 240) =
        withTimeout(seconds * 1000) {
            while (ids.any { repo.dao.task(it)?.state in LocalTaskRow.active }) delay(100)
        }

    /** A document with one plain page, then switched to the workbook look so a retake runs the full finish. */
    private suspend fun workbookPage(): Pair<DocumentRow, PageRow> {
        UserSettings.set("filter", "ORIGINAL")
        val document = repo.newScan()
        val first = photo(document.id, "scan-core/dark-desk-0.jpg")
        CaptureTasks.accept(repo, first.id)
        settle(first.id)
        val page = repo.dao.pages(document.id).single()
        repo.savePage(page.copy(filter = "WORKBOOK"))
        return document to repo.dao.pages(document.id).single()
    }

    private fun <T> cleanly(body: suspend () -> T) = runBlocking {
        val documentsBefore = repo.dao.allDocuments().map { it.id }.toSet()
        val tasksBefore = repo.dao.allTasks().map { it.id }.toSet()
        val preferences = UserSettings.exportJson()
        try {
            body()
        } finally {
            repo.dao.allDocuments().filter { it.id !in documentsBefore }.forEach { repo.deleteDocument(it) }
            repo.dao.allTasks().filter { it.id !in tasksBefore }.forEach {
                repo.database.openHelper.writableDatabase.execSQL("DELETE FROM local_tasks WHERE id=?", arrayOf(it.id))
            }
            UserSettings.importJson(preferences)
        }
    }

    @Test
    fun retakeShowsTheNewPhotoBeforeTheFinishAndTheFinishUsesIt() = cleanly {
        val (document, before) = workbookPage()
        var held = false
        try {
            // Hold the slow finish so the moment between "paper found" and "finished" can be looked at.
            CaptureTasks.finishSlot.lock()
            held = true
            val retake = photo(document.id, "scan-core/white-desk-0.jpg", before.id)
            CaptureTasks.accept(repo, retake.id)
            withTimeout(120_000) {
                while ((repo.dao.task(retake.id)?.progress ?: 0) < 20) delay(50)
            }
            val shown = repo.dao.pages(document.id).single()
            assertEquals("the new photo is on the page while it is finished", CaptureTasks.raw(retake).path, shown.original)
            assertTrue(shown.revision > before.revision)
            assertEquals(
                retake.id,
                JSONObject(requireNotNull(shown.processingMetadata)).getString("captureJob"),
            )
            assertEquals(LocalTaskState.RUNNING, repo.dao.task(retake.id)!!.state)
            CaptureTasks.finishSlot.unlock()
            held = false
            settle(retake.id)
            val task = repo.dao.task(retake.id)!!
            assertEquals(task.detail, LocalTaskState.SUCCEEDED, task.state)
            val finished = repo.dao.pages(document.id).single()
            assertEquals(before.id, finished.id)
            assertTrue("the finished page comes from the retake", JSONObject(finished.processingMetadata!!).has("flatDocument"))
            assertNotEquals(before.original, finished.original)
            assertNotEquals(shown.original, finished.original)
            assertTrue(File(finished.original).isFile)
            assertTrue(finished.revision > shown.revision)
            // Undo goes back through the retake's two steps to the page before it.
            repo.undo(document.id)
            repo.undo(document.id)
            assertEquals(before.original, repo.dao.pages(document.id).single().original)
        } finally {
            if (held) CaptureTasks.finishSlot.unlock()
        }
    }

    @Test
    fun repeatedRetakesKeepEveryPhotoAndAFailedRetakeLeavesThePage() = cleanly {
        UserSettings.set("filter", "ORIGINAL")
        val document = repo.newScan()
        val first = photo(document.id, "scan-core/dark-desk-0.jpg")
        CaptureTasks.accept(repo, first.id)
        settle(first.id)
        val before = repo.dao.pages(document.id).single()
        // Two quick retakes of the same page: one replaces it, the other is kept as a new page, never lost.
        val a = photo(document.id, "scan-core/white-desk-0.jpg", before.id)
        val b = photo(document.id, "scan-core/clutter-0.jpg", before.id)
        CaptureTasks.accept(repo, a.id)
        CaptureTasks.accept(repo, b.id)
        settle(a.id, b.id)
        val pages = repo.dao.pages(document.id)
        assertEquals(2, pages.size)
        val originals = pages.map { it.original }.toSet()
        assertEquals(setOf(CaptureTasks.raw(a).path, CaptureTasks.raw(b).path), originals)
        assertEquals(before.id, pages.first().id)
        assertTrue(listOf(a, b).any { repo.dao.task(it.id)!!.detail.contains("另存") })
        pages.forEach { assertTrue(File(it.original).isFile) }
        // A retake whose photo cannot be read fails and leaves the page as it was.
        val shown = repo.dao.pages(document.id).first()
        val broken = CaptureTasks.prepare(repo, document.id, null, shown.id)
        CaptureTasks.raw(broken).writeBytes(ByteArray(2048) { 7 })
        runCatching { CaptureTasks.accept(repo, broken.id) }
            .onFailure { CaptureTasks.fail(repo, broken.id, it.message ?: "failed") }
        settle(broken.id)
        assertEquals(LocalTaskState.FAILED, repo.dao.task(broken.id)!!.state)
        val after = repo.dao.pages(document.id).first()
        assertEquals(shown.original, after.original)
        assertEquals(shown.revision, after.revision)
    }

    @Test
    fun aRetakeStoppedDuringTheFinishKeepsAConsistentPage() = cleanly {
        val (document, before) = workbookPage()
        var held = false
        try {
            CaptureTasks.finishSlot.lock()
            held = true
            val retake = photo(document.id, "scan-core/white-desk-0.jpg", before.id)
            CaptureTasks.accept(repo, retake.id)
            withTimeout(120_000) {
                while ((repo.dao.task(retake.id)?.progress ?: 0) < 20) delay(50)
            }
            CaptureTasks.cancel(repo, repo.dao.task(retake.id)!!)
            CaptureTasks.finishSlot.unlock()
            held = false
            withTimeout(60_000) {
                while (repo.dao.task(retake.id)?.state != LocalTaskState.CANCELLED) delay(100)
            }
            delay(2_000)
            val page = repo.dao.pages(document.id).single()
            // The page keeps the photo it already showed (undo brings the old one back) or the old page; never a
            // missing image or a second page.
            assertTrue(page.original == CaptureTasks.raw(retake).path || page.original == before.original)
            assertTrue(File(page.original).isFile)
            assertEquals(1, repo.dao.pages(document.id).size)
        } finally {
            if (held) CaptureTasks.finishSlot.unlock()
        }
    }
}
