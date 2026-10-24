package cn.paperforge.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OcrRecordsTest {
    @Test
    fun correctionsUndoAndBackupKeepRawAutomaticAndEachUserRevision() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as PaperForgeApp
        val repo = app.repository
        val before = repo.dao.allDocuments().map { it.id }.toSet()
        val doc = repo.newScan()
        val archive = File(repo.exports, "ocr-records-${System.nanoTime()}.zip")
        try {
            val file = repo.captureFile(doc.id)
            instrument.context.assets.open("scan-core/dark-desk-0.jpg").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val page = PageRow(UUID.randomUUID().toString(), doc.id, 0, file.path)
            repo.dao.putPage(page)
            val raw = OcrLayout(1000, 1400, listOf(OcrLine("00123", Quad.Full.points, .7f)))
            val selected =
                raw.lines
                    .single()
                    .copy(
                        text = "00128",
                        hypotheses =
                            listOf(
                                OcrHypothesis("00123", .7f, "original"),
                                OcrHypothesis("00128", .8f, "clean"),
                            ),
                    )
            val automatic =
                raw.copy(
                    lines = listOf(selected),
                    rawRecognition = raw.encode(),
                    recognitionMode = "DETAILED",
                )
            val cached = repo.cacheOcr(page, automatic).first
            assertNull(OcrLayout.decode(cached.ocrLayout!!).rawRecognition)
            assertEquals(listOf("00123", "00128"), OcrRecords.versions(cached).map { it.text })
            val changed = repo.correctOcr(cached, "00129")
            assertTrue(OcrLayout.decode(changed.ocrLayout!!).lines.single().corrected)
            val expanded = repo.correctOcr(changed, "00129\nadded note")
            assertNull(expanded.ocrLayout)
            assertEquals(
                listOf("00123", "00128", "00129", "00129\nadded note"),
                OcrRecords.versions(expanded).map { it.text },
            )
            assertTrue(repo.originalResources(doc.id).count { it.endsWith(".pfocr.json.gz") } >= 3)
            repo.undo(doc.id)
            var latest = repo.dao.pages(doc.id).single()
            assertEquals("00129", latest.ocrText)
            assertEquals(latest.revision, OcrLayout.decode(latest.ocrLayout!!).sourceRevision)
            repo.undo(doc.id, true)
            latest = repo.dao.pages(doc.id).single()
            assertEquals("00129\nadded note", latest.ocrText)
            BackupStore.backup(repo, archive)
            BackupStore.restore(repo, Uri.fromFile(archive))
            val restored =
                repo.dao.allDocuments().single {
                    it.id !in before && it.id != doc.id && it.title == doc.title
                }
            val restoredPage = repo.dao.pages(restored.id).single()
            assertEquals(OcrRecords.versions(latest), OcrRecords.versions(restoredPage))
            assertTrue(
                PageResources.entries(restoredPage).all {
                    File(it.path).isFile &&
                        it.path.startsWith(repo.folder(restored.id).path + File.separator)
                }
            )
            repo.undo(restored.id)
            val restoredUndo = repo.dao.pages(restored.id).single()
            assertEquals("00129", restoredUndo.ocrText)
            assertEquals("00123", OcrRecords.versions(restoredUndo).first().text)
            assertEquals(
                restoredUndo.revision,
                OcrLayout.decode(restoredUndo.ocrLayout!!).sourceRevision,
            )
        } finally {
            repo.dao.allDocuments().filter { it.id !in before }.forEach { repo.deleteDocument(it) }
            archive.delete()
        }
    }
}
