package cn.paperforge.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cn.paperforge.app.model.ReadingFrame
import cn.paperforge.app.pdf.*
import cn.paperforge.app.ui.PdfViewTransformKey
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.*
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderAnchorsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PaperForgeApp

    private fun fixture(): File {
        val file = File(app.repository.exports, "anchors-${System.nanoTime()}.pdf")
        PDDocument().use { pdf ->
            repeat(3) { n ->
                val page = PDPage(PDRectangle.A4)
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use {
                    for (y in 100..700 step 100) {
                        it.beginText()
                        it.setFont(PDType1Font.HELVETICA, 14f)
                        it.newLineAtOffset(150f, y.toFloat())
                        it.showText("PAGE ${n+1} ANCHOR $y")
                        it.endText()
                    }
                }
            }
            val xyz =
                PDPageXYZDestination().apply {
                    page = pdf.getPage(1)
                    left = 150
                    top = 300
                    zoom = 1.6f
                }
            val rect =
                PDPageFitRectangleDestination().apply {
                    page = pdf.getPage(2)
                    left = 100
                    bottom = 200
                    right = 350
                    top = 400
                }
            val names = PDDocumentNameDictionary(pdf.documentCatalog)
            names.dests =
                PDDestinationNameTreeNode().apply { this.names = mapOf("exact-anchor" to xyz) }
            pdf.documentCatalog.names = names
            fun link(index: Int, box: PDRectangle, dest: PDDestination) {
                pdf.getPage(index)
                    .annotations
                    .add(
                        PDAnnotationLink().apply {
                            rectangle = box
                            action = PDActionGoTo().apply { destination = dest }
                        }
                    )
            }
            link(0, PDRectangle(250f, 400f, 60f, 45f), PDNamedDestination("exact-anchor"))
            link(1, PDRectangle(160f, 245f, 60f, 30f), rect)
            val outline = PDDocumentOutline()
            outline.addLast(
                PDOutlineItem().apply {
                    title = "Exact second anchor"
                    destination = PDNamedDestination("exact-anchor")
                }
            )
            outline.addLast(
                PDOutlineItem().apply {
                    title = "Third region"
                    destination = rect
                }
            )
            pdf.documentCatalog.documentOutline = outline
            pdf.save(file)
        }
        return file
    }

    @Test
    fun fractionalCoordinatesNullAxesRotationAndNamedActionsSurviveRead() {
        val file = File(app.cacheDir, "destination-types.pdf")
        PDDocument().use { pdf ->
            pdf.addPage(PDPage(PDRectangle.A4))
            pdf.addPage(PDPage(PDRectangle(100f, 50f, 400f, 700f)).apply { rotation = 90 })
            fun add(dest: PDPageDestination) {
                dest.page = pdf.getPage(1)
                pdf.getPage(0)
                    .annotations
                    .add(
                        PDAnnotationLink().apply {
                            rectangle = PDRectangle(20f, 20f, 40f, 40f)
                            destination = dest
                        }
                    )
            }
            val xyz = PDPageXYZDestination()
            xyz.cosObject.set(2, COSFloat(200.5f))
            xyz.cosObject.set(3, COSNull.NULL)
            xyz.cosObject.set(4, COSFloat(1.25f))
            add(xyz)
            add(PDPageFitWidthDestination().apply { top = 575 })
            add(PDPageFitHeightDestination().apply { left = 180 })
            add(
                PDPageFitRectangleDestination().apply {
                    left = 180
                    bottom = 190
                    right = 420
                    top = 610
                }
            )
            pdf.getPage(0)
                .annotations
                .add(
                    PDAnnotationLink().apply {
                        rectangle = PDRectangle(80f, 20f, 40f, 40f)
                        action = PDActionNamed().apply { n = "NextPage" }
                    }
                )
            pdf.save(file)
        }
        try {
            val targets = ReaderDocument.links(file, 0).map { checkNotNull(it.destination) }
            assertEquals(5, targets.size)
            assertNull(targets[0].x)
            assertEquals(.25125f, targets[0].y!!, .0001f)
            assertEquals(1.25f, targets[0].zoom!!, .0001f)
            assertEquals("FitV", targets[1].fit)
            assertEquals(.75f, targets[1].x!!, .0001f)
            assertEquals("FitH", targets[2].fit)
            assertEquals(.2f, targets[2].y!!, .0001f)
            assertEquals("FitR", targets[3].fit)
            assertEquals(.2f, targets[3].x!!, .0001f)
            assertEquals(.8f, targets[3].right!!, .0001f)
            assertEquals(1, targets[4].page)
        } finally {
            file.delete()
        }
    }

    @Test
    fun twoCanvasLinksReturnToZoomedAnchorAfterRecreationAndContinuousJumpReturnsScroll() {
        val file = fixture()
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        val doc = runBlocking { app.repository.registerPdf(file, "阅读锚点验收") }
        val prefs = ReaderPreferences(app)
        val origin = ReaderPosition(zoom = 2f, panX = .12f, panY = -.15f)
        prefs.save(doc.id, origin)
        fun model() = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        fun waitPage(n: Int) {
            compose.waitUntil(15000) {
                compose.onAllNodesWithText("$n / 3").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("reader-viewport-${n-1}").assertExists()
        }
        fun transform(n: Int) =
            compose
                .onNodeWithTag("reader-viewport-${n-1}")
                .fetchSemanticsNode()
                .config[PdfViewTransformKey]
        fun tapLink(n: Int) {
            val link = ReaderDocument.links(file, n - 1).single()
            val t = transform(n)
            compose.onNodeWithTag("reader-viewport-${n-1}").performTouchInput {
                val frame =
                    ReadingFrame.fit(
                        width.toFloat(),
                        height.toFloat(),
                        PDRectangle.A4.width / PDRectangle.A4.height,
                        false,
                    )
                click(
                    Offset(
                        width / 2 +
                            ((link.left + link.right) / 2 - .5f) * frame.pageWidth * t[0] +
                            t[1] * frame.pageWidth,
                        height / 2 +
                            ((link.top + link.bottom) / 2 - .5f) * frame.pageHeight * t[0] +
                            t[2] * frame.pageHeight,
                    )
                )
            }
        }
        try {
            compose.runOnIdle { model().open(doc) }
            waitPage(1)
            compose.waitUntil(10000) { transform(1)[0] > 1.9f }
            tapLink(1)
            waitPage(2)
            compose.waitUntil(10000) { transform(2)[0] > 2f }
            val second = transform(2)
            tapLink(2)
            waitPage(3)
            compose.waitUntil(10000) { transform(3)[0] > 2f }
            compose.activityRule.scenario.recreate()
            waitPage(3)
            compose.onNodeWithText("返回阅读处").performClick()
            waitPage(2)
            compose.waitUntil(10000) {
                transform(2).zip(second).all { (a, b) -> kotlin.math.abs(a - b) < .002f }
            }
            compose.onNodeWithText("返回阅读处").performClick()
            waitPage(1)
            val returned = transform(1)
            assertEquals(origin.zoom, returned[0], .002f)
            assertEquals(origin.panX, returned[1], .002f)
            assertEquals(origin.panY, returned[2], .002f)
            compose.onNodeWithText("返回阅读处").assertDoesNotExist()
            compose.runOnIdle { model().screen.value = "home" }
            compose.onNodeWithText("最近文档").assertIsDisplayed()
            prefs.save(doc.id, ReaderPosition(horizontal = false, offset = 350))
            compose.runOnIdle { model().open(doc) }
            waitPage(1)
            compose.onNodeWithContentDescription("目录与链接").performClick()
            compose.onNodeWithText("Exact second anchor").performClick()
            waitPage(2)
            compose.waitUntil(10000) {
                prefs.load(doc.id).page == 1 && prefs.load(doc.id).offset > 350
            }
            compose.onNodeWithText("返回阅读处").performClick()
            waitPage(1)
            compose.waitUntil(10000) {
                prefs.load(doc.id).page == 0 && prefs.load(doc.id).offset == 350
            }
            assertArrayEquals(hash, MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } catch (error: Throwable) {
            File(app.cacheDir, "reader-anchors-failure.txt")
                .writeText(error.stackTraceToString() + "\n" + prefs.load(doc.id))
            throw error
        } finally {
            compose.runOnIdle { model().screen.value = "home" }
            runBlocking { app.repository.deleteDocument(doc) }
        }
    }
}
