package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cn.paperforge.app.pdf.*
import cn.paperforge.app.ui.PdfViewTransformKey
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderReflowFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PaperForgeApp

    @Test
    fun paragraphsPictureMergedTableAndSourceJumpRetainReadingSettings() {
        val proof = File(app.cacheDir, "reader-reflow-proof").apply { mkdirs() }
        val file = File(proof, "source.pdf")
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(500f, 700f))
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { c ->
                fun text(x: Float, y: Float, value: String) {
                    c.beginText()
                    c.setFont(PDType1Font.HELVETICA, 12f)
                    c.newLineAtOffset(x, y)
                    c.showText(value)
                    c.endText()
                }
                text(40f, 640f, "This paragraph begins on the first line")
                text(40f, 623f, "and continues with code 00123 and total 200.50.")
                val image = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(image)
                canvas.drawColor(Color.rgb(20, 110, 180))
                val paint = android.graphics.Paint().apply { color = Color.rgb(235, 170, 40) }
                canvas.drawRect(120f, 0f, 240f, 120f, paint)
                c.drawImage(LosslessFactory.createFromImage(pdf, image), 40f, 355f, 180f, 90f)
                image.recycle()
                c.setStrokingColor(0, 0, 0)
                c.setLineWidth(1f)
                for (y in listOf(150f, 200f, 250f, 300f)) {
                    c.moveTo(40f, y)
                    c.lineTo(460f, y)
                }
                for (x in listOf(40f, 460f)) {
                    c.moveTo(x, 150f)
                    c.lineTo(x, 300f)
                }
                c.moveTo(250f, 150f)
                c.lineTo(250f, 250f)
                c.stroke()
                text(50f, 270f, "DATA")
                text(50f, 220f, "00123")
                text(265f, 220f, "200.50")
                text(50f, 170f, "00077")
                text(265f, 170f, "90.00")
            }
            pdf.save(file)
        }
        val hash = runBlocking { PdfAssembly.sha256(file) }
        val page = runBlocking { ReaderReflow.load(app, file, 0, app.ocrEngine.snapshot()) }
        assertEquals(hash, page.sourceSha256)
        val paragraph = page.items.first { it.kind == "paragraph" }
        assertEquals(
            "This paragraph begins on the first line and continues with code 00123 and total 200.50.",
            paragraph.text,
        )
        assertEquals(1, page.items.count { it.kind == "image" })
        val tableItem = page.items.single { it.kind == "table" }
        val table = tableItem.table!!
        assertEquals(3, table.rows.size)
        assertEquals(5, table.cells.size)
        assertTrue(table.cells.any { it.row == 0 && it.columnEnd == 1 && it.text == "DATA" })
        assertTrue(table.cells.any { it.text == "00123" })
        assertTrue(table.cells.any { it.text == "200.50" })
        assertTrue(page.items.all { it.region.valid() && it.destination(0).page == 0 })
        val repo = app.repository
        val doc = runBlocking { repo.registerPdf(file, "段落图片表格重排验收", listed = false) }
        val prefs = ReaderPreferences(app)
        val origin = ReaderPosition(zoom = 1.7f, panX = .08f, panY = .12f)
        prefs.save(doc.id, origin)
        prefs.reflow(doc.id, ReflowPosition())
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        fun waitTag(tag: String) =
            compose.waitUntil(20000) {
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        fun screenshot(name: String) {
            File(proof, "$name.png").outputStream().use {
                compose
                    .onNodeWithTag("reader-reflow")
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        try {
            compose.runOnIdle { vm.open(doc) }
            waitTag("reader-viewport-0")
            compose.onNodeWithText("重排").performClick()
            waitTag("reflow-item-0")
            compose.onNodeWithText(paragraph.text).assertIsDisplayed()
            compose.onNodeWithText("字号").performClick()
            compose.onNodeWithText("A＋").performClick()
            compose.onNodeWithText("A＋").performClick()
            compose.onNodeWithText("字号 24 · 行距 1.6").assertIsDisplayed()
            compose.onNodeWithText("字号").performClick()
            screenshot("paragraph-picture")
            val tableIndex = page.items.indexOf(tableItem)
            compose.onNodeWithTag("reflow-source-$tableIndex").performScrollTo()
            compose.onNodeWithText("00123", useUnmergedTree = true).assertIsDisplayed()
            screenshot("table")
            compose.onNodeWithTag("reflow-source-$tableIndex").performClick()
            waitTag("reader-viewport-0")
            compose.waitUntil(10000) {
                compose
                    .onNodeWithTag("reader-viewport-0")
                    .fetchSemanticsNode()
                    .config[PdfViewTransformKey][0]
                    .let { kotlin.math.abs(it - origin.zoom) > .05f }
            }
            val placement = compose.onNodeWithTag("reader-viewport-0").fetchSemanticsNode()
            val t = placement.config[PdfViewTransformKey]
            val box = placement.boundsInRoot
            val frame =
                cn.paperforge.app.model.ReadingFrame.fit(box.width, box.height, 500f / 700, false)
            val center = (tableItem.region.top + tableItem.region.bottom) / 2
            val y =
                box.height / 2 + (center - .5f) * frame.pageHeight * t[0] + t[2] * frame.pageHeight
            assertTrue(y in 0f..box.height)
            compose.onNodeWithText("返回阅读处").performClick()
            compose.waitUntil(10000) {
                kotlin.math.abs(
                    compose
                        .onNodeWithTag("reader-viewport-0")
                        .fetchSemanticsNode()
                        .config[PdfViewTransformKey][0] - origin.zoom
                ) < .002f
            }
            val stored = prefs.reflow(doc.id)
            assertEquals(24f, stored.fontSize, .001f)
            assertTrue(stored.index > 0)
            val backup = prefs.export(listOf(doc.id))
            prefs.restore(backup, mapOf(doc.id to "reflow-restored-proof"))
            assertEquals(stored, prefs.reflow("reflow-restored-proof"))
            compose.onNodeWithText("重排").performClick()
            waitTag("reflow-item-$tableIndex")
            compose.onNodeWithText("字号").performClick()
            compose.onNodeWithText("字号 24 · 行距 1.6").assertIsDisplayed()
            File(proof, "proof.json")
                .writeText(
                    JSONObject()
                        .put("model", ReaderReflow.encode(page))
                        .put("position", stored.json())
                        .put("backupPositionMatches", true)
                        .put("sourceJumpAndReturn", true)
                        .put("originalUnchanged", hash == runBlocking { PdfAssembly.sha256(file) })
                        .toString(2)
                )
        } catch (error: Throwable) {
            runCatching { screenshot("failure") }
            File(proof, "failure.txt").writeText(error.stackTraceToString())
            throw error
        } finally {
            compose.runOnIdle { vm.screen.value = "home" }
        }
    }
}
