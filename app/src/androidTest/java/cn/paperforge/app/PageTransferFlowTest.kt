package cn.paperforge.app

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import cn.paperforge.app.reader.StudyNotes
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PageTransferFlowTest {
    @Test
    fun originalOcrAndOriginsSurviveCopyMoveLinkedUndoLaterCapturesAndBackup() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as PaperForgeApp
        val repo = app.repository
        val before = repo.dao.allDocuments().map { it.id }.toSet()
        val library = LibraryPreferences(app)
        val oldPreferences = library.export()
        val oldNotes = StudyNotes.load(app).map { it.id }.toSet()
        val archive = File(repo.exports, "transfer-${System.nanoTime()}.zip")
        val prefix = "transfer-${UUID.randomUUID().toString().take(8)}"
        suspend fun document(name: String) =
            repo.newScan().copy(title = "$prefix-$name").also { repo.dao.updateDocument(it) }
        suspend fun image(doc: DocumentRow, position: Int): PageRow {
            val file = repo.captureFile(doc.id)
            instrument.context.assets.open("scan-core/dark-desk-0.jpg").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            return PageRow(UUID.randomUUID().toString(), doc.id, position, file.path).also {
                repo.dao.putPage(it)
            }
        }
        fun sha(path: String) =
            MessageDigest.getInstance("SHA-256").digest(File(path).readBytes()).toList()
        suspend fun ids(doc: DocumentRow) = repo.dao.pages(doc.id).map { it.id }
        try {
            val source = document("source")
            val target = document("target")
            val first =
                image(source, 8)
                    .copy(
                        processingMetadata =
                            JSONObject().put("captureJob", UUID.randomUUID()).toString()
                    )
            repo.dao.updatePage(first)
            val raw =
                OcrLayout(
                    1000,
                    1400,
                    listOf(OcrLine("00123", Quad.Full.points, .9f)),
                    sourceRevision = first.revision,
                )
            val recognized = repo.cacheOcr(first, raw.copy(rawRecognition = raw.encode())).first
            val corrected = repo.correctOcr(recognized, "00129")
            val second = corrected.copy(id = UUID.randomUUID().toString(), position = 12)
            repo.dao.putPage(second)
            val base = image(target, 4)
            repo.copyPages(source.id, setOf(corrected.id, second.id), target.id)
            val copied = repo.dao.pages(target.id).drop(1)
            assertEquals(2, copied.size)
            assertEquals(copied[0].original, copied[1].original)
            assertNotEquals(corrected.original, copied[0].original)
            assertEquals(sha(corrected.original), sha(copied[0].original))
            copied.forEachIndexed { i, page ->
                assertEquals("00129", page.ocrText)
                assertEquals(page.revision, OcrLayout.decode(page.ocrLayout!!).sourceRevision)
                assertEquals(
                    listOf("00123", "00123", "00129"),
                    OcrRecords.versions(page).map { it.text },
                )
                assertFalse(PageResources.metadata(page).has("captureJob"))
                assertTrue(
                    PageResources.paths(page).all {
                        it.startsWith(repo.folder(target.id).path + File.separator) &&
                            File(it).isFile
                    }
                )
                assertEquals(i + 1, page.origins().single().ordinal)
                assertEquals(source.id, page.origins().single().documentId)
                assertEquals(listOf(corrected.id, second.id)[i], page.origins().single().pageId)
                BitmapFactory.decodeFile(page.original).also {
                    assertNotNull(it)
                    it?.recycle()
                }
            }
            val later = image(target, 9)
            repo.undo(target.id)
            assertEquals(listOf(base.id, later.id), ids(target))
            repo.undo(target.id, true)
            assertEquals(listOf(base.id, copied[0].id, copied[1].id, later.id), ids(target))
            assertEquals(listOf(corrected.id, second.id), ids(source))

            val moved =
                repo
                    .copyPages(target.id, setOf(copied[0].id), null, "$prefix-moved", move = true)
                    .document
            val movedPage = repo.dao.pages(moved.id).single()
            assertEquals("00129", movedPage.ocrText)
            val extraSource = image(target, 9)
            val extraTarget = image(moved, 9)
            repo.undo(moved.id)
            assertEquals(
                listOf(base.id, copied[0].id, copied[1].id, later.id, extraSource.id),
                ids(target),
            )
            assertEquals(listOf(extraTarget.id), ids(moved))
            repo.undo(target.id, true)
            assertEquals(listOf(movedPage.id, extraTarget.id), ids(moved))
            repo.savePage(repo.dao.pages(target.id).first().copy(rotation = 90))
            assertTrue(runCatching { repo.undo(moved.id) }.isFailure)
            assertEquals(listOf(movedPage.id, extraTarget.id), ids(moved))
            repo.undo(target.id)
            repo.undo(moved.id)
            repo.undo(target.id, true)
            // Redo order on the other document must not replay its later edit first.
            assertEquals(listOf(movedPage.id, extraTarget.id), ids(moved))

            BackupStore.backup(repo, archive)
            BackupStore.restore(repo, Uri.fromFile(archive))
            val clones =
                repo.dao.allDocuments().filter {
                    it.id !in before && it.id !in setOf(source.id, target.id, moved.id)
                }
            val sourceClone = clones.single { it.title == source.title }
            val targetClone = clones.single { it.title == target.title }
            val movedClone = clones.single { it.title == moved.title }
            val clonedPage = repo.dao.pages(movedClone.id).first()
            assertEquals(sourceClone.id, clonedPage.origins().first().documentId)
            assertEquals(
                repo.dao.pages(sourceClone.id).first().id,
                clonedPage.origins().first().pageId,
            )
            assertEquals(targetClone.id, clonedPage.origins().last().documentId)
            repo.savePage(clonedPage.copy(rotation = 90))
            repo.undo(movedClone.id)
            repo.undo(movedClone.id)
            assertEquals(1, repo.dao.pages(movedClone.id).size)
            val returned = repo.dao.pages(targetClone.id)[1]
            assertEquals("00129", returned.ocrText)
            assertEquals(returned.revision, OcrLayout.decode(returned.ocrLayout!!).sourceRevision)
            assertEquals(listOf(movedPage.id, extraTarget.id), ids(moved))
            repo.savePage(repo.dao.pages(targetClone.id).first().copy(rotation = 180))
            assertNull(repo.dao.redoEntry(movedClone.id))
            repo.rename(movedClone, movedClone.title + "-independent")

            repo.deleteDocument(source)
            assertTrue(File(movedPage.original).isFile)
            repo.deleteDocument(target)
            assertTrue(File(movedPage.original).isFile)
            assertEquals("00129", OcrRecords.versions(movedPage).last().text)
            assertTrue(runCatching { repo.undo(moved.id) }.isFailure)
            archive.delete()
            val beforeSecond = repo.dao.allDocuments().map { it.id }.toSet()
            BackupStore.backup(repo, archive)
            BackupStore.restore(repo, Uri.fromFile(archive))
            val surviving =
                repo.dao.allDocuments().single { it.id !in beforeSecond && it.title == moved.title }
            val survivingPage = repo.dao.pages(surviving.id).first()
            assertTrue(PageResources.paths(survivingPage).all { File(it).isFile })
            assertEquals("00129", OcrRecords.versions(survivingPage).last().text)
            assertTrue(runCatching { repo.undo(surviving.id) }.isFailure)
            repo.deleteDocument(moved)
            assertFalse(File(app.filesDir, "documents/${target.id}").exists())
        } finally {
            archive.delete()
            repo.dao.allDocuments().filter { it.id !in before }.forEach { repo.deleteDocument(it) }
            library.restore(oldPreferences)
            StudyNotes.load(app)
                .filter { it.id !in oldNotes }
                .forEach { StudyNotes.remove(app, it.id) }
        }
    }
}
