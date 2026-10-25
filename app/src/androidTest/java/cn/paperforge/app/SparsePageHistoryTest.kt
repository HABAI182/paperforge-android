package cn.paperforge.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import cn.paperforge.app.reader.StudyNotes
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SparsePageHistoryTest {
    @Test
    fun largeOcrReorderAndLegacyDeleteKeepLaterCapturesAndOcrIncludingAfterRestore() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as PaperForgeApp
        val repo = app.repository
        val existing = repo.dao.allDocuments().map { it.id }.toSet()
        val preferences = LibraryPreferences(app)
        val oldPreferences = preferences.export()
        val notes = StudyNotes.load(app).map { it.id }.toSet()
        val doc =
            repo.newScan().copy(title = "sparse-${UUID.randomUUID()}").also {
                repo.dao.updateDocument(it)
            }
        val archive = File(repo.exports, "sparse-${System.nanoTime()}.zip")
        try {
            val file = repo.captureFile(doc.id)
            instrument.context.assets.open("scan-core/dark-desk-0.jpg").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val layout =
                OcrLayout(
                    1000,
                    1400,
                    List(400) { OcrLine("history OCR 00123 line $it", Quad.Full.points, .9f) },
                    sourceRevision = 0,
                )
            val original =
                List(24) { index ->
                    PageRow(
                        UUID.randomUUID().toString(),
                        doc.id,
                        index,
                        file.path,
                        ocrText = if (index == 0) null else layout.text,
                        ocrLayout = if (index == 0) null else layout.encode(),
                        processingMetadata = JSONObject().put("orderFixture", index).toString(),
                    )
                }
            original.forEach { repo.dao.putPage(it) }
            assertTrue(HistoryState.snapshot(original).toByteArray().size > 2 * 1024 * 1024)
            repo.reorder(doc.id, original.reversed())
            val entry = repo.dao.undoEntry(doc.id)!!
            assertTrue((entry.beforeJson.length + entry.afterJson.length) < 12000)
            assertTrue(HistoryState.pages(entry.beforeJson).isEmpty())
            suspend fun append(target: DocumentRow, index: Int): PageRow {
                val raw = repo.captureFile(target.id)
                file.copyTo(raw)
                return PageRow(
                        UUID.randomUUID().toString(),
                        target.id,
                        repo.dao.pages(target.id).size,
                        raw.path,
                        processingMetadata = JSONObject().put("orderFixture", index).toString(),
                    )
                    .also { repo.dao.putPage(it) }
            }
            val later = append(doc, 24)
            val text = OcrLayout(1000, 1400, listOf(OcrLine("00123", Quad.Full.points, .9f)))
            repo.cacheOcr(repo.dao.pages(doc.id).first { it.id == original.first().id }, text)
            assertEquals(entry.id, repo.dao.undoEntry(doc.id)!!.id)
            repo.undo(doc.id)
            assertEquals(original.map { it.id } + later.id, repo.dao.pages(doc.id).map { it.id })
            assertEquals("00123", repo.dao.pages(doc.id).first().ocrText)
            repo.undo(doc.id, true)
            assertEquals(
                original.reversed().map { it.id } + later.id,
                repo.dao.pages(doc.id).map { it.id },
            )
            BackupStore.backup(repo, archive)
            BackupStore.restore(repo, Uri.fromFile(archive))
            val restored =
                repo.dao.allDocuments().single {
                    it.id !in existing && it.id != doc.id && it.title == doc.title
                }
            repo.undo(restored.id)
            val restoredRows = repo.dao.pages(restored.id)
            assertEquals(
                (0..24).toList(),
                restoredRows.map { PageResources.metadata(it).getInt("orderFixture") },
            )
            assertEquals("00123", restoredRows.first().ocrText)
            assertTrue(PageResources.paths(restoredRows.first()).all { File(it).isFile })
            assertEquals(
                original.reversed().map { it.id } + later.id,
                repo.dao.pages(doc.id).map { it.id },
            )
            repo.deletePage(repo.dao.pages(restored.id)[3])
            val afterDelete = append(restored, 25)
            repo.undo(restored.id)
            assertEquals(
                (0..25).toList(),
                repo.dao.pages(restored.id).map {
                    PageResources.metadata(it).getInt("orderFixture")
                },
            )
            assertTrue(File(afterDelete.original).exists())

            val legacy = repo.newScan()
            val legacyFile = repo.captureFile(legacy.id)
            file.copyTo(legacyFile)
            val old =
                List(3) { PageRow(UUID.randomUUID().toString(), legacy.id, it, legacyFile.path) }
            old.forEach { repo.dao.putPage(it) }
            val kept = listOf(old[0], old[2].copy(position = 1))
            repo.dao.deletePage(old[1].id)
            kept.forEach { repo.dao.putPage(it) }
            repo.dao.addHistory(
                EditHistory(
                    documentId = legacy.id,
                    pageId = old[1].id,
                    beforeJson = HistoryState.snapshot(old),
                    afterJson = HistoryState.snapshot(kept),
                )
            )
            val extra = append(legacy, 99)
            repo.cacheOcr(kept[0], text)
            repo.undo(legacy.id)
            assertEquals(old.map { it.id } + extra.id, repo.dao.pages(legacy.id).map { it.id })
            assertEquals("00123", repo.dao.pages(legacy.id).first().ocrText)
            repo.undo(legacy.id, true)
            assertEquals(kept.map { it.id } + extra.id, repo.dao.pages(legacy.id).map { it.id })
        } finally {
            archive.delete()
            repo.dao
                .allDocuments()
                .filter { it.id !in existing }
                .forEach { repo.deleteDocument(it) }
            preferences.restore(oldPreferences)
            StudyNotes.load(app)
                .filter { it.id !in notes }
                .forEach { StudyNotes.remove(app, it.id) }
        }
    }
}
