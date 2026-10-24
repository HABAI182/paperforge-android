package cn.paperforge.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.ScanProcessor
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HistoryBackupTest {
    @Test
    fun removedPagesAsymmetricSplitAndBackupRestoreKeepOriginalsAndReaderState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as PaperForgeApp
        val repo = app.repository
        val existing = repo.dao.allDocuments().map { it.id }.toSet()
        val originalSettings = UserSettings.exportJson()
        val oldNotes = cn.paperforge.app.reader.StudyNotes.load(context).map { it.id }.toSet()
        val doc = repo.newScan()
        val archive = File(repo.exports, "history-check-${System.nanoTime()}.zip")
        try {
            val page =
                PageRow(
                    UUID.randomUUID().toString(),
                    doc.id,
                    0,
                    repo.captureFile(doc.id).path,
                    filter = "ORIGINAL",
                    outputAspect = .7071068f,
                )
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("scan-core/dark-desk-0.jpg")
                .use { input -> File(page.original).outputStream().use { input.copyTo(it) } }
            repo.dao.putPage(page)
            val rendered = ScanProcessor.render(page, 600)
            assertEquals(.7071068f, rendered.width.toFloat() / rendered.height, .01f)
            rendered.recycle()
            repo.savePage(page.copy(filter = "TEXT"))
            repo.deletePage(repo.dao.pages(doc.id).single())
            assertTrue(repo.dao.pages(doc.id).isEmpty())
            assertTrue(File(page.original).exists())
            assertTrue(page.original in repo.originalResources(doc.id))
            repo.undo(doc.id)
            assertEquals("TEXT", repo.dao.pages(doc.id).single().filter)
            repo.undo(doc.id)
            assertEquals("ORIGINAL", repo.dao.pages(doc.id).single().filter)
            repo.undo(doc.id, true)
            val current = repo.dao.pages(doc.id).single()
            repo.splitBook(current, .35f, .55f)
            assertEquals(2, repo.dao.pages(doc.id).size)
            repo.undo(doc.id)
            assertEquals(1, repo.dao.pages(doc.id).size)
            repo.undo(doc.id, true)
            val reader = ReaderPreferences(context)
            reader.save(doc.id, ReaderPosition(page = 1, zoom = 2f))
            reader.bookmarks(doc.id, setOf(0, 1))
            cn.paperforge.app.reader.StudyNotes.add(
                context,
                doc.id,
                doc.title,
                1,
                "backup quote 00123",
                "backup note",
            )
            val font = UserSettings.number("fontScale", 1f)
            BackupStore.backup(repo, archive)
            ZipFile(archive).use { zip ->
                assertNotNull(zip.getEntry("integrity.json"))
                val meta =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("paperforge.json"))
                            .bufferedReader()
                            .readText()
                    )
                assertEquals(3, meta.getInt("format"))
                assertTrue(meta.has("reader") && meta.has("preferences"))
            }
            UserSettings.set("fontScale", 1.25f)
            BackupStore.restore(repo, Uri.fromFile(archive))
            val restored =
                repo.dao.allDocuments().single {
                    it.title == doc.title && it.id != doc.id && it.id !in existing
                }
            assertEquals(2, repo.dao.pages(restored.id).size)
            assertEquals(1, reader.load(restored.id).page)
            assertEquals(2f, reader.load(restored.id).zoom, .001f)
            assertEquals(setOf(0, 1), reader.bookmarks(restored.id))
            assertEquals(font, UserSettings.number("fontScale", 1f), .001f)
            assertTrue(
                cn.paperforge.app.reader.StudyNotes.load(context).any {
                    it.documentId == restored.id && it.quote == "backup quote 00123"
                }
            )
            repo.undo(restored.id)
            val recovered = repo.dao.pages(restored.id).single()
            assertTrue(File(recovered.original).exists())
            assertEquals(current.corners, recovered.corners)
        } finally {
            cn.paperforge.app.reader.StudyNotes.load(context)
                .filter { it.id !in oldNotes }
                .forEach { cn.paperforge.app.reader.StudyNotes.remove(context, it.id) }
            repo.dao
                .allDocuments()
                .filter { it.id !in existing }
                .forEach { repo.deleteDocument(it) }
            UserSettings.importJson(originalSettings)
            archive.delete()
        }
    }
}
