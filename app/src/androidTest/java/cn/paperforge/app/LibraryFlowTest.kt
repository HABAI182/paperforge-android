package cn.paperforge.app

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import cn.paperforge.app.reader.StudyNotes
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibraryFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun nativeAndOcrSearchPageNavigationAndOutputReferencesSurviveBackup() = runBlocking {
        val app = compose.activity.application as PaperForgeApp
        val repo = app.repository
        val old = repo.dao.allDocuments().map { it.id }.toSet()
        val oldOutputs = repo.dao.allOutputs().map { it.path }.toSet()
        val oldNotes = StudyNotes.load(app).map { it.id }.toSet()
        val library = LibraryPreferences(app)
        val oldLibrary = app.getSharedPreferences("library", 0).all.toMap()
        val backup = File(repo.exports, "library-${System.nanoTime()}.zip")
        val scan = repo.newScan().copy(title = "Library scan ${System.nanoTime()}")
        repo.dao.updateDocument(scan)
        val file = File(repo.exports, "library-native-${System.nanoTime()}.pdf")
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            repeat(2) { i ->
                val image = repo.captureFile(scan.id)
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .context
                    .assets
                    .open("scan-core/dark-desk-0.jpg")
                    .use { input -> image.outputStream().use { input.copyTo(it) } }
                val line =
                    OcrLine(
                        if (i == 1) "付款编号 001234" else "测试首页",
                        listOf(
                            PointN(.1f, .1f),
                            PointN(.8f, .1f),
                            PointN(.8f, .2f),
                            PointN(.1f, .2f),
                        ),
                    )
                repo.dao.putPage(
                    PageRow(
                        UUID.randomUUID().toString(),
                        scan.id,
                        i,
                        image.path,
                        ocrText = line.text,
                        ocrLayout = OcrLayout(800, 1000, listOf(line)).encode(),
                    )
                )
            }
            PDDocument().use { pdf ->
                repeat(2) { i ->
                    val page = PDPage(PDRectangle.A4)
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { s ->
                        s.beginText()
                        s.setFont(PDType1Font.HELVETICA, 20f)
                        s.newLineAtOffset(60f, 650f)
                        s.showText(if (i == 1) "Invoice 001234" else "Native first page")
                        s.endText()
                    }
                }
                pdf.save(file)
            }
            val native = repo.registerPdf(file, "Native search ${System.nanoTime()}")
            val derived =
                File(repo.exports, "library-derived-${System.nanoTime()}.pdf").also {
                    file.copyTo(it)
                }
            val derivedDoc = repo.registerPdf(derived, scan.title, listed = false)
            repo.recordOutput(derived, scan)
            assertFalse(repo.dao.observeDocuments().first().any { it.id == derivedDoc.id })
            LibraryIndexer.index(repo, native)
            val hits = repo.dao.searchHits(CjkSearch.expression("001234")).first()
            assertTrue(hits.any { it.documentId == scan.id && it.page == 1 })
            assertTrue(
                hits.any {
                    it.documentId == native.id && it.page == 1 && it.text.contains("Invoice")
                }
            )
            assertTrue(repo.dao.search("001234").first().any { it.id == native.id })
            val modified = native.modified
            compose.runOnIdle {
                model.screen.value = "home"
                model.query.value = "001234"
            }
            compose.waitUntil(15000) {
                model.documents.value.any { it.id == native.id } &&
                    model.librarySearchHits.value.any { it.documentId == native.id }
            }
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasTestTag("library-hit-${native.id}-1"))
            compose.onNodeWithTag("library-hit-${native.id}-1").performClick()
            compose.waitUntil(15000) {
                compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(modified, repo.dao.document(native.id)?.modified)
            assertTrue((repo.dao.document(native.id)?.lastOpened ?: 0) > 0)
            compose.waitUntil(10000) { !model.busy.value }
            compose.runOnIdle { model.screen.value = "home" }
            compose.waitUntil(10000) { model.documents.value.any { it.id == scan.id } }
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasTestTag("library-hit-${scan.id}-1"))
            compose.onNodeWithTag("library-hit-${scan.id}-1").performClick()
            compose.waitUntil(15000) {
                compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle {
                model.screen.value = "home"
                model.query.value = ""
            }
            repo.recordOutput(file, scan)
            repo.dao.outputDestination(file.path, "content://test-provider/result.pdf")
            assertTrue(
                repo.dao.libraryFacts().first().single { it.documentId == scan.id }.hasExport
            )
            library.save(
                LibraryFilter("Library check", collection = "NO_OCR", sort = "OPENED", tags = "发票")
            )
            BackupStore.backup(repo, backup)
            BackupStore.restore(repo, Uri.fromFile(backup))
            val restored =
                repo.dao.allDocuments().single {
                    it.id !in old && it.id != scan.id && it.title == scan.title && it.kind == "scan"
                }
            val restoredOutputs =
                repo.dao.allOutputs().filter { it.sourceDocumentId == restored.id }
            assertEquals(2, restoredOutputs.size)
            val output = restoredOutputs.single { it.externalUri != null }
            assertTrue(File(output.path).exists())
            assertEquals("content://test-provider/result.pdf", output.externalUri)
            assertEquals(
                output.path,
                repo.dao.document(checkNotNull(output.outputDocumentId))?.pdfPath,
            )
            assertTrue(library.saved().any { it.name == "Library check" && it.tags == "发票" })
        } finally {
            compose.runOnIdle {
                model.screen.value = "home"
                model.query.value = ""
            }
            repo.dao.allDocuments().filter { it.id !in old }.forEach { repo.deleteDocument(it) }
            repo.dao
                .allOutputs()
                .filter { it.path !in oldOutputs }
                .forEach { row ->
                    repo.dao.removeOutput(row.path)
                    val f = File(row.path)
                    if (f.canonicalFile.parentFile == repo.exports.canonicalFile) f.delete()
                }
            StudyNotes.load(app)
                .filter { it.id !in oldNotes }
                .forEach { StudyNotes.remove(app, it.id) }
            val prefs = app.getSharedPreferences("library", 0).edit().clear()
            oldLibrary.forEach { (key, value) -> if (value is String) prefs.putString(key, value) }
            prefs.commit()
            backup.delete()
        }
    }
}
