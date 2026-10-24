package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cn.paperforge.app.pdf.*
import cn.paperforge.app.reader.StudyNotes
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.*
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderFeaturesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PaperForgeApp

    private fun fixture(): File {
        val file = File(app.repository.exports, "reader-features-${System.nanoTime()}.pdf")
        PDDocument().use { doc ->
            repeat(3) { index ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 18f)
                    stream.newLineAtOffset(50f, 700f)
                    stream.showText(if (index == 2) "HEADER 3" else "HELLO NATIVE TEXT 00123")
                    stream.endText()
                    if (index == 2) {
                        val image = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(image)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawText(
                            "SCAN 00123",
                            35f,
                            180f,
                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.BLACK
                                textSize = 44f
                            },
                        )
                        stream.drawImage(
                            LosslessFactory.createFromImage(doc, image),
                            50f,
                            300f,
                            450f,
                            300f,
                        )
                        image.recycle()
                    }
                }
            }
            val labels = PDPageLabels(doc)
            labels.setLabelItem(
                0,
                PDPageLabelRange().apply { style = PDPageLabelRange.STYLE_ROMAN_LOWER },
            )
            labels.setLabelItem(
                2,
                PDPageLabelRange().apply { style = PDPageLabelRange.STYLE_DECIMAL },
            )
            doc.documentCatalog.pageLabels = labels
            val outline = PDDocumentOutline()
            outline.addLast(
                PDOutlineItem().apply {
                    title = "Chapter Three"
                    setDestination(doc.getPage(2))
                }
            )
            doc.documentCatalog.documentOutline = outline
            val destination = PDPageFitDestination().apply { page = doc.getPage(2) }
            doc.getPage(0)
                .annotations
                .add(
                    PDAnnotationLink().apply {
                        rectangle = PDRectangle(50f, 680f, 260f, 40f)
                        action = PDActionGoTo().apply { this.destination = destination }
                    }
                )
            doc.save(file)
        }
        return file
    }

    @Test
    fun nativeOutlineLabelsLinksTextSelectionAndExcerptExportsAreUsable() {
        val repo = app.repository
        val file = fixture()
        val title = "Reader feature ${System.nanoTime()}"
        val doc = runBlocking { repo.registerPdf(file, title) }
        val existingNotes = StudyNotes.load(app).map { it.id }.toSet()
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            val info = ReaderDocument.info(file)
            assertEquals(listOf("i", "ii", "1"), info.labels)
            assertEquals(2, info.outline.single().page)
            assertEquals(2, ReaderDocument.links(file, 0).single().page)
            assertTrue(ReaderDocument.text(file, 0).any { it.text.contains("00123") })
            compose.runOnIdle { model.open(doc) }
            compose.waitUntil(15000) {
                compose.onAllNodesWithText("1 / 3").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("第 1 页").performTouchInput {
                doubleClick(Offset(width * .8f, height * .7f))
            }
            compose.waitUntil(10000) {
                runCatching {
                        compose
                            .onNodeWithTag("reader-tiles-0")
                            .fetchSemanticsNode()
                            .config[cn.paperforge.app.ui.PdfTileCountKey] > 0
                    }
                    .getOrDefault(false)
            }
            compose.onNodeWithContentDescription("第 1 页").performTouchInput { swipeLeft() }
            compose.onNodeWithText("1 / 3").assertIsDisplayed()
            compose.onNodeWithContentDescription("第 1 页").performTouchInput { swipeLeft() }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle { model.readerTarget.value = doc.id to 0 }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("1 / 3").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("恢复缩放").performClick()
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("目录与链接").performScrollTo().performClick()
            compose.onNodeWithText("Chapter Three").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("3 / 3").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("返回阅读处").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("1 / 3").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("第 1 页").performTouchInput {
                click(Offset(width * .20f, height * .17f))
            }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("3 / 3").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("返回阅读处").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("1 / 3").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("第 1 页").performTouchInput {
                longClick(Offset(width * .20f, height * .17f))
            }
            compose.onNodeWithText("文字与朗读", substring = true).assertIsDisplayed()
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithText("HELLO NATIVE TEXT 00123")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("保存摘录").performClick()
            compose.onNode(hasSetTextAction() and hasText("笔记")).performTextInput("reader note")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(10000) { StudyNotes.load(app).any { it.documentId == doc.id } }
            compose.onNodeWithText("回到原页").performClick()
            val md = StudyNotes.export(app, repo.exports, "md")
            val tsv = StudyNotes.export(app, repo.exports, "tsv")
            val word = StudyNotes.export(app, repo.exports, "docx")
            assertTrue(md.readText().contains("paperforge://document/${doc.id}?page=1"))
            assertTrue(tsv.readText().contains("00123\treader note"))
            ZipFile(word).use { zip ->
                assertTrue(
                    zip.getInputStream(zip.getEntry("word/document.xml"))
                        .bufferedReader()
                        .readText()
                        .contains("00123")
                )
            }
            word.copyTo(File(app.filesDir, "reader-feature-notes.docx"), overwrite = true)
            compose.runOnIdle {
                model.openDocumentLink(
                    android.net.Uri.parse("paperforge://document/${doc.id}?page=3")
                )
            }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("3 / 3").fetchSemanticsNodes().isNotEmpty()
            }
            listOf(md, tsv, word).forEach(File::delete)
        } finally {
            compose.runOnIdle { model.screen.value = "home" }
            StudyNotes.load(app)
                .filter { it.id !in existingNotes }
                .forEach { StudyNotes.remove(app, it.id) }
            runBlocking { repo.deleteDocument(doc) }
        }
    }

    @Test
    fun mixedNativeHeaderAndScannedBodyCanBeRecognizedWithoutReplacingHeader() = runBlocking {
        val file = fixture()
        try {
            val native = PageTextSource.load(app, file, 2, app.ocrEngine)
            assertTrue(native.any { it.text.contains("HEADER") })
            assertFalse(native.any { it.text.contains("SCAN") })
            val recognized = PageTextSource.load(app, file, 2, app.ocrEngine, true)
            assertTrue(recognized.any { it.text.contains("HEADER") })
            assertTrue(recognized.any { it.text.replace(" ", "").contains("SCAN00123") })
            assertEquals(1, recognized.count { it.text.contains("HEADER") })
        } finally {
            file.delete()
        }
    }

    @Test
    fun facingCoverAndRtlKeepTheSelectedPhysicalPageAcrossRotation() {
        val repo = app.repository
        val file = fixture()
        val doc = runBlocking { repo.registerPdf(file, "Facing ${System.nanoTime()}") }
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previous = compose.activity.requestedOrientation
        ReaderPreferences(app)
            .view(
                doc.id,
                cn.paperforge.app.reader.ReaderViewOptions(
                    facingPages = true,
                    coverAlone = true,
                    rightToLeft = true,
                ),
            )
        try {
            compose.runOnIdle {
                compose.activity.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                model.open(doc)
            }
            compose.waitUntil(15000) {
                compose.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("第 2 页").assertDoesNotExist()
            compose.onNodeWithTag("reader-viewport-0").performTouchInput { swipeRight() }
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithContentDescription("第 2 页")
                    .fetchSemanticsNodes()
                    .isNotEmpty() &&
                    compose
                        .onAllNodesWithContentDescription("第 3 页")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
            }
            assertTrue(
                compose.onNodeWithTag("reader-viewport-2").fetchSemanticsNode().boundsInRoot.left <
                    compose
                        .onNodeWithTag("reader-viewport-1")
                        .fetchSemanticsNode()
                        .boundsInRoot
                        .left
            )
            compose.runOnIdle { model.readerTarget.value = doc.id to 2 }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("3 / 3").fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle {
                compose.activity.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            compose.waitUntil(15000) {
                compose.onAllNodesWithTag("reader-viewport-2").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("reader-viewport-1").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("3 / 3").assertIsDisplayed()
        } finally {
            compose.runOnIdle {
                model.screen.value = "home"
                compose.activity.requestedOrientation = previous
            }
            runBlocking { repo.deleteDocument(doc) }
        }
    }
}
