package cn.paperforge.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupMissingFileTest {
    @Test
    fun backupSkipsVanishedExportAndItsArchiveStillRestores() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = (context.applicationContext as PaperForgeApp).repository
        val existingDocuments = repo.dao.allDocuments().map { it.id }.toSet()
        val existingOutputs = repo.dao.allOutputs().map { it.path }.toSet()
        val originalSettings = UserSettings.exportJson()
        val doc = repo.newScan()
        val stamp = System.nanoTime()
        val archive = File(repo.exports, "missing-export-$stamp.zip")
        val kept = File(repo.exports, "missing-export-kept-$stamp.pdf")
        val gone = File(repo.exports, "missing-export-gone-$stamp.pdf")
        try {
            val page =
                PageRow(
                    UUID.randomUUID().toString(),
                    doc.id,
                    0,
                    repo.captureFile(doc.id).path,
                    filter = "ORIGINAL",
                )
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("scan-core/dark-desk-0.jpg")
                .use { input -> File(page.original).outputStream().use { input.copyTo(it) } }
            repo.dao.putPage(page)
            kept.writeText("%PDF-1.4 kept export")
            gone.writeText("%PDF-1.4 vanished export")
            repo.dao.putOutput(OutputRow(kept.path, doc.id, "kept", "pdf"))
            repo.dao.putOutput(OutputRow(gone.path, doc.id, "gone", "pdf"))
            // The export disappears from storage while the library still lists it.
            assertTrue(gone.delete())

            val result = BackupStore.backup(repo, archive)
            // Earlier test runs may have left other vanished files; ours must be among them.
            assertTrue(result.missing >= 1)
            ZipFile(archive).use { zip ->
                val meta =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("paperforge.json"))
                            .bufferedReader()
                            .use { it.readText() }
                    )
                val integrity =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("integrity.json"))
                            .bufferedReader()
                            .use { it.readText() }
                    )
                val missing =
                    meta.getJSONArray("missing").let { a ->
                        (0 until a.length()).map { a.getString(it) }.toSet()
                    }
                assertEquals(result.missing, missing.size)
                val outputs =
                    meta.getJSONArray("outputs").let { a ->
                        (0 until a.length())
                            .map { OutputRow.decode(a.getJSONObject(it)) }
                            .filter { it.sourceDocumentId == doc.id }
                    }
                val goneEntry = outputs.single { it.sourceTitle == "gone" }.path
                val keptEntry = outputs.single { it.sourceTitle == "kept" }.path
                assertTrue(goneEntry in missing)
                assertNull(zip.getEntry(goneEntry))
                assertFalse(integrity.has(goneEntry))
                assertFalse(keptEntry in missing)
                assertNotNull(zip.getEntry(keptEntry))
                assertTrue(integrity.has(keptEntry))
            }

            assertTrue(BackupStore.preview(repo, Uri.fromFile(archive)).documents >= 1)
            BackupStore.restore(repo, Uri.fromFile(archive))
            val restored =
                repo.dao.allDocuments().single {
                    it.title == doc.title && it.id != doc.id && it.id !in existingDocuments
                }
            assertTrue(File(repo.dao.pages(restored.id).single().original).isFile)
            val restoredOutputs =
                repo.dao.allOutputs().filter { it.sourceDocumentId == restored.id }
            // The vanished export's stale record is dropped; the surviving one comes back.
            assertEquals(listOf("kept"), restoredOutputs.map { it.sourceTitle })
            assertEquals("%PDF-1.4 kept export", File(restoredOutputs.single().path).readText())
        } finally {
            repo.dao
                .allOutputs()
                .filter { it.path !in existingOutputs }
                .forEach {
                    repo.dao.removeOutput(it.path)
                    File(it.path).delete()
                }
            repo.dao
                .allDocuments()
                .filter { it.id !in existingDocuments }
                .forEach { repo.deleteDocument(it) }
            UserSettings.importJson(originalSettings)
            archive.delete()
            kept.delete()
            gone.delete()
        }
    }
}
