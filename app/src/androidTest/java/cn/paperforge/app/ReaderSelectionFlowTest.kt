package cn.paperforge.app

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cn.paperforge.app.model.*
import cn.paperforge.app.pdf.*
import cn.paperforge.app.ui.PdfViewTransformKey
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderSelectionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PaperForgeApp

    @Test
    fun nativeHandlesColumnCopyLineMarksAndRegionalOcrStayOnSource() {
        val repo = app.repository
        val proof = File(app.cacheDir, "reader-selection-proof").apply { mkdirs() }
        val source = File(proof, "source.pdf")
        PDDocument().use { pdf ->
            val first = PDPage(PDRectangle(400f, 600f))
            pdf.addPage(first)
            PDPageContentStream(pdf, first).use { c ->
                fun line(x: Float, y: Float, text: String) {
                    c.beginText()
                    c.setFont(PDType1Font.HELVETICA, 16f)
                    c.newLineAtOffset(x, y)
                    c.showText(text)
                    c.endText()
                }
                line(40f, 520f, "ALPHA 00123")
                line(40f, 485f, "BETA 200.50")
                line(235f, 520f, "RIGHT 111")
                line(235f, 485f, "RIGHT 222")
            }
            val rotated =
                PDPage(PDRectangle(500f, 700f)).apply {
                    cropBox = PDRectangle(20f, 30f, 400f, 600f)
                    rotation = 90
                }
            pdf.addPage(rotated)
            PDPageContentStream(pdf, rotated).use { c ->
                c.beginText()
                c.setFont(PDType1Font.HELVETICA, 16f)
                c.newLineAtOffset(60f, 550f)
                c.showText("ROTATED 00123")
                c.endText()
            }
            val scan = PDPage(PDRectangle(400f, 600f))
            pdf.addPage(scan)
            val image = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            canvas.drawColor(Color.WHITE)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 52f
                }
            canvas.drawText("SCAN 00123", 100f, 480f, paint)
            canvas.drawText("OUTSIDE 999", 100f, 950f, paint)
            PDPageContentStream(pdf, scan).use {
                it.drawImage(LosslessFactory.createFromImage(pdf, image), 0f, 0f, 400f, 600f)
            }
            image.recycle()
            pdf.save(source)
        }
        val hash = runBlocking { PdfAssembly.sha256(source) }
        val doc = runBlocking { repo.registerPdf(source, "原页选择验收", listed = false) }
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val oldOrder = UserSettings.string("readerSelectionOrder", "0")
        UserSettings.set("readerSelectionOrder", "0")
        ReaderPreferences(app).save(doc.id, ReaderPosition(horizontal = true))
        val lines = ReaderDocument.text(source, 0)
        val alpha = lines.first { it.text.startsWith("ALPHA") }
        assertTrue(alpha.units.size >= 10)
        val rotated = ReaderDocument.text(source, 1).single()
        assertTrue(rotated.units.isNotEmpty())
        assertTrue(rotated.units.first().left in .8f..1f)
        assertTrue(rotated.units.first().top in 0.08f..0.16f)
        var copied = ""
        var columns = ""
        var ocr = ""
        fun waitTag(tag: String) =
            compose.waitUntil(20000) {
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        fun selected() =
            compose
                .onNodeWithTag("reader-selected-text")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString { it.text }
        fun point(page: Int, sourcePoint: PointN): Offset {
            val node = compose.onNodeWithTag("reader-viewport-$page").fetchSemanticsNode()
            val bounds = node.boundsInRoot
            val t = node.config[PdfViewTransformKey]
            val frame = ReadingFrame.fit(bounds.width, bounds.height, 400f / 600, false)
            return Offset(
                bounds.center.x +
                    (sourcePoint.x - .5f) * frame.pageWidth * t[0] +
                    t[1] * frame.pageWidth,
                bounds.center.y +
                    (sourcePoint.y - .5f) * frame.pageHeight * t[0] +
                    t[2] * frame.pageHeight,
            )
        }
        fun drag(tag: String, to: PointN) {
            val target = point(0, to)
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag(tag).performTouchInput {
                swipe(center, center + target - node.center, 600)
            }
        }
        fun screenshot(name: String) {
            File(proof, "$name.png").outputStream().use {
                compose
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        try {
            compose.runOnIdle { vm.open(doc) }
            waitTag("reader-viewport-0")
            val first = alpha.units.first()
            val press =
                point(0, PointN((first.left + first.right) / 2, (first.top + first.bottom) / 2))
            val bounds =
                compose.onNodeWithTag("reader-viewport-0").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("reader-viewport-0").performTouchInput {
                longClick(press - bounds.topLeft)
            }
            waitTag("reader-selection-end")
            assertEquals("ALPHA", selected())
            val last = alpha.units.last()
            drag(
                "reader-selection-end",
                PointN((last.left + last.right) / 2, (last.top + last.bottom) / 2),
            )
            compose.waitUntil(10000) { selected().endsWith("00123") }
            val number = alpha.units.first { it.start == alpha.text.indexOf("00123") }
            drag(
                "reader-selection-start",
                PointN((number.left + number.right) / 2, (number.top + number.bottom) / 2),
            )
            compose.waitUntil(10000) { selected() == "00123" }
            screenshot("native-selection")
            compose.onNodeWithText("复制", useUnmergedTree = true).performClick()
            compose.runOnIdle {
                copied =
                    (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .primaryClip
                        ?.getItemAt(0)
                        ?.text
                        .toString()
            }
            assertEquals("00123", copied)
            compose.onNodeWithText("选全页").performClick()
            compose.onNodeWithText("更多", useUnmergedTree = true).performClick()
            compose.onNodeWithText("先左栏后右栏").performClick()
            columns = selected()
            assertEquals("ALPHA 00123\nBETA 200.50\nRIGHT 111\nRIGHT 222", columns)
            compose.onNodeWithText("高亮", useUnmergedTree = true).performClick()
            val draft = File(repo.folder(doc.id), "annotations.json")
            compose.waitUntil(10000) { draft.exists() && JSONArray(draft.readText()).length() == 4 }
            val marks = PdfMark.decode(JSONArray(draft.readText()))
            assertEquals(4, marks.size)
            assertTrue(marks.all { it.page == 0 && it.points[1].x - it.points[0].x < .4f })
            PDDocument.load(source).use { pdf ->
                PdfNativeMarks.apply(app, pdf, marks, true)
                pdf.save(File(proof, "highlighted.pdf"))
            }
            assertEquals(hash, runBlocking { PdfAssembly.sha256(source) })
            screenshot("columns")
            compose.onNodeWithText("完成选择").performClick()
            compose.runOnIdle { vm.readerTarget.value = doc.id to 2 }
            waitTag("reader-viewport-2")
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("区域识字", useUnmergedTree = true).performClick()
            compose.onNodeWithText("拖动圈选要识别的区域").assertIsDisplayed()
            val from = point(2, PointN(.08f, .30f))
            val to = point(2, PointN(.9f, .48f))
            val scanBounds =
                compose.onNodeWithTag("reader-viewport-2").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("reader-viewport-2").performTouchInput {
                swipe(from - scanBounds.topLeft, to - scanBounds.topLeft, 700)
            }
            waitTag("reader-selected-text")
            ocr = selected()
            assertTrue(ocr.contains("00123"))
            assertFalse(ocr.contains("999"))
            screenshot("regional-ocr")
            val proofJson =
                JSONObject()
                    .put("sourceSha256", hash)
                    .put("originalUnchanged", hash == runBlocking { PdfAssembly.sha256(source) })
                    .put("copied", copied)
                    .put("columns", columns)
                    .put("marks", JSONArray(marks.map { it.json() }))
                    .put("regionalOcr", ocr)
                    .put(
                        "rotated",
                        JSONObject()
                            .put("text", rotated.text)
                            .put("firstLeft", rotated.units.first().left)
                            .put("firstTop", rotated.units.first().top),
                    )
            File(proof, "proof.json").writeText(proofJson.toString(2))
        } catch (error: Throwable) {
            runCatching { screenshot("failure") }
            File(proof, "failure.txt")
                .writeText(
                    error.stackTraceToString() +
                        "\nSelected: " +
                        runCatching { selected() }.getOrDefault("none")
                )
            throw error
        } finally {
            UserSettings.set("readerSelectionOrder", oldOrder)
            compose.runOnIdle { vm.screen.value = "home" }
        }
    }
}
