package cn.paperforge.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.LocalTaskRow
import cn.paperforge.app.imaging.CaptureTasks
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Opening the scanner and leaving without a photo used to leave an empty "0 页" scan in the library. A new
 * scan is now only written with its first capture or imported page, in the same transaction.
 */
class LazyScanDraftTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PaperForgeApp
    private val repo get() = app.repository

    private fun sample(): File =
        File(app.cacheDir, "lazy-draft-${System.nanoTime()}.jpg").also { file ->
            InstrumentationRegistry.getInstrumentation().context.assets.open("scan-core/dark-desk-0.jpg").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }

    @Test
    fun aScanLeftWithoutAPhotoNeverReachesTheLibrary() = runBlocking {
        val before = repo.dao.allDocuments().map { it.id }.toSet()
        val draft = repo.draftScan()
        assertTrue(repo.isPendingScan(draft.id))
        assertNull("a new scan is not in the library before anything is saved", repo.dao.document(draft.id))
        // The user backs out of the camera: nothing was saved, so nothing is left behind.
        assertEquals(before, repo.dao.allDocuments().map { it.id }.toSet())
        assertTrue(repo.dao.allTasks().none { it.documentId == draft.id })
    }

    @Test
    fun theFirstCaptureWritesTheScanInTheSameStep() = runBlocking {
        val draft = repo.draftScan()
        try {
            val task = CaptureTasks.prepare(repo, draft.id, null)
            val saved = repo.dao.document(draft.id)
            assertNotNull("the capture's document is in the library", saved)
            assertEquals(draft.title, saved!!.title)
            assertEquals(draft.id, repo.dao.task(task.id)?.documentId)
            // A second capture into the same scan does not write the document again.
            CaptureTasks.prepare(repo, draft.id, null)
            assertEquals(1, repo.dao.allDocuments().count { it.id == draft.id })
        } finally {
            repo.dao.allTasks().filter { it.documentId == draft.id }.forEach { CaptureTasks.fail(repo, it.id, "test") }
            repo.dao.document(draft.id)?.let { repo.deleteDocument(it) }
        }
    }

    @Test
    fun aGalleryImportWritesTheScanOnlyWhenAPageIsSaved() = runBlocking {
        val before = repo.dao.allDocuments().map { it.id }.toSet()
        val failed = repo.draftScan()
        val unreadable = Uri.fromFile(File(app.cacheDir, "no-such-image-${System.nanoTime()}.jpg"))
        assertTrue(runCatching { repo.importImages(failed.id, listOf(unreadable)) }.isFailure)
        assertNull("an import that saved nothing leaves no document", repo.dao.document(failed.id))
        assertEquals(before, repo.dao.allDocuments().map { it.id }.toSet())

        val draft = repo.draftScan()
        val image = sample()
        try {
            repo.importImages(draft.id, listOf(Uri.fromFile(image)))
            assertNotNull(repo.dao.document(draft.id))
            val limit = System.nanoTime() + 180_000_000_000L
            while (repo.dao.allTasks().any { it.documentId == draft.id && it.state in LocalTaskRow.active }) {
                assertTrue("import did not finish", System.nanoTime() < limit)
                delay(500)
            }
            assertEquals(1, repo.dao.pages(draft.id).size)
        } finally {
            image.delete()
            repo.dao.document(draft.id)?.let { repo.deleteDocument(it) }
        }
    }

    @Test
    fun existingDocumentsAndNewScanAreUnchanged() = runBlocking {
        // newScan() still writes at once for its other callers; ensureDocument leaves such a document alone.
        val doc = repo.newScan()
        try {
            assertNotNull(repo.dao.document(doc.id))
            assertFalse(repo.isPendingScan(doc.id))
            repo.ensureDocument(doc.id)
            assertEquals(1, repo.dao.allDocuments().count { it.id == doc.id })
            assertEquals(doc.title, repo.dao.document(doc.id)!!.title)
        } finally {
            repo.dao.document(doc.id)?.let { repo.deleteDocument(it) }
        }
    }
}
