package cn.paperforge.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.DocumentRow
import cn.paperforge.app.model.*
import cn.paperforge.app.pdf.*
import cn.paperforge.app.ui.PdfViewTransformKey
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * The editor as a user drives it: tap a line, press-hold-drag across lines, zoom and tap, recolour, retype, change
 * font, select from the list, undo/redo, save; then the saved library copy is read back.
 */
class NativeEditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PaperForgeApp

    private val proof by lazy { File(app.filesDir, "native-ui-proof").apply { deleteRecursively(); mkdirs() } }
    private val timing = JSONObject()

    /** Evidence screenshots; a capture during a window transition is retried, never fails the test. */
    private fun shot(name: String) {
        repeat(3) {
            compose.waitForIdle()
            val image = runCatching { compose.onRoot().captureToImage().asAndroidBitmap() }.getOrNull()
            if (image != null) {
                File(proof, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return
            }
            Thread.sleep(400)
        }
    }

    private fun text(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    private fun waitTag(tag: String, ms: Long = 20000) =
        compose.waitUntil(ms) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitText(tag: String, ms: Long = 20000, check: (String) -> Boolean) {
        try {
            compose.waitUntil(ms) { runCatching { check(text(tag)) }.getOrDefault(false) }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            shot("fail-$tag")
            val seen = runCatching { text(tag) }.getOrDefault("<none>")
            val message = runCatching { text("native-edit-message") }.getOrDefault("<none>")
            val status = runCatching { text("native-edit-status") }.getOrDefault("<none>")
            throw AssertionError("waiting for $tag: saw '$seen'; status '$status'; message '$message'", timeout)
        }
    }

    /** Screen position of a normalised page point on the editor's page. */
    private fun point(p: PointN, ratio: Float): Offset {
        val node = compose.onNodeWithTag("reader-viewport-0").fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val t = node.config[PdfViewTransformKey]
        val frame = ReadingFrame.fit(bounds.width, bounds.height, ratio, true)
        return Offset(
            bounds.center.x + (p.x - .5f) * frame.pageWidth * t[0] + t[1] * frame.pageWidth,
            bounds.center.y + (p.y - .5f) * frame.pageHeight * t[0] + t[2] * frame.pageHeight,
        )
    }

    @Test
    fun selectFormatEditUndoSaveAndReopen() {
        val repo = app.repository
        val source = File(app.cacheDir, "native-ui-source-${System.currentTimeMillis()}.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("native-edit/mixed-zh-en.pdf").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val title = "原生编辑界面验收-${System.currentTimeMillis()}"
        val doc = runBlocking { repo.registerPdf(source, title, listed = false) }
        val objects = runBlocking { NativePdfEditor.inspect(File(doc.pdfPath!!), 0).second }
        val second = objects.first { it.text.startsWith("请于") }
        val third = objects.first { it.text.startsWith("Email") }
        val ratio = 595f / 842f
        fun mid(o: NativePdfEditor.TextObject, k: Int) = o.units[k].let { PointN((it.left + it.right) / 2, (it.top + it.bottom) / 2) }
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnIdle {
            vm.nativeEditSource.value = doc
            vm.screen.value = "pdf-native-edit"
        }
        waitTag("reader-viewport-0")
        compose.waitUntil(20000) { runCatching { text("native-edit-status").contains("第 1 / 1 页") }.getOrDefault(false) }
        shot("01-open")

        // 1. Tap selects the whole line object.
        val tapAt = point(mid(second, 3), ratio)
        val bounds = compose.onNodeWithTag("reader-viewport-0").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("reader-viewport-0").performTouchInput { click(tapAt - bounds.topLeft) }
        waitTag("native-edit-count")
        waitText("native-edit-count") { it.contains("1 个对象") && it.contains("${second.text.length} 字") }
        shot("02-tap-selects-line")

        // 2. Press, hold, then drag down to the next line: selection follows the finger across objects.
        compose.onNodeWithContentDescription("取消选择").performClick()
        val from = point(mid(second, 2), ratio) - bounds.topLeft
        val to = point(mid(third, 5), ratio) - bounds.topLeft
        val t0 = System.nanoTime()
        compose.onNodeWithTag("reader-viewport-0").performTouchInput {
            down(from)
            advanceEventTime(900)
            moveTo(from + (to - from) * .5f)
            advanceEventTime(100)
            moveTo(to)
            advanceEventTime(100)
            up()
        }
        waitText("native-edit-count") { it.contains("2 个对象") }
        timing.put("holdDragToSelectionMs", (System.nanoTime() - t0) / 1_000_000)
        assertTrue(compose.onAllNodesWithTag("reader-selection-start").fetchSemanticsNodes().isNotEmpty())
        shot("03-hold-drag-two-lines")

        // 3. Recolour the selection.
        val t1 = System.nanoTime()
        compose.onNodeWithTag("native-edit-color").performClick()
        compose.onNodeWithTag("native-color-蓝").performClick()
        waitText("native-edit-status") { it.contains("2 处修改") }
        timing.put("recolorToPreviewMs", (System.nanoTime() - t1) / 1_000_000)
        shot("04-recolored")

        // 4. Undo, redo.
        compose.onNodeWithContentDescription("撤销").performClick()
        compose.waitUntil(10000) { runCatching { !text("native-edit-status").contains("处修改") }.getOrDefault(false) }
        compose.onNodeWithContentDescription("重做").performClick()
        waitText("native-edit-status") { it.contains("2 处修改") }

        // 5. Zoomed in, a tap still lands on the right line.
        compose.onNodeWithContentDescription("取消选择").performClick()
        // Double-tap on the text zooms in around the finger, as a user would.
        val zoomAt = point(mid(second, 6), ratio) - bounds.topLeft
        compose.onNodeWithTag("reader-viewport-0").performTouchInput { doubleClick(zoomAt) }
        compose.waitUntil(5000) { compose.onNodeWithTag("reader-viewport-0").fetchSemanticsNode().config[PdfViewTransformKey][0] > 1.2f }
        Thread.sleep(400)
        compose.waitForIdle()
        val zoomed = compose.onNodeWithTag("reader-viewport-0").fetchSemanticsNode().config[PdfViewTransformKey][0]
        assertTrue(zoomed > 1.2f)
        val visible = bounds.let { b -> androidx.compose.ui.geometry.Rect(b.left + 20, b.top + 40, b.right - 20, b.bottom - 40) }
        val zoomTarget = objects.first { visible.contains(point(mid(it, it.units.size / 2), ratio)) }
        val zoomTap = point(mid(zoomTarget, zoomTarget.units.size / 2), ratio)
        compose.onNodeWithTag("reader-viewport-0").performTouchInput { click(zoomTap - bounds.topLeft) }
        waitText("native-edit-count") { it.contains("1 个对象") && it.contains("${zoomTarget.text.length} 字") }
        shot("05-zoomed-tap")
        val first = zoomTarget

        // 6. Retype part of the line: select by hold on the first word then edit.
        compose.onNodeWithTag("native-edit-text").performClick()
        waitTag("native-edit-field")
        compose.onNodeWithTag("native-edit-field").performTextReplacement("PaperForge 扫描 OCR 文字编辑 v2.1")
        compose.onNodeWithTag("native-edit-apply").performClick()
        waitText("native-edit-message") { it.contains("已修改文字") || it.contains("新文字改用") }
        shot("06-retyped")

        // 7. Font picker with search and live preview.
        compose.onNodeWithTag("native-edit-font").performClick()
        waitTag("font-search")
        shot("07-font-picker")
        compose.onNodeWithTag("font-search").performTextInput("楷")
        waitTag("font-builtin:kai")
        compose.onNodeWithTag("font-builtin:kai").performClick()
        waitText("native-edit-message") { it.contains("已更换字体") }
        shot("08-kai")

        // 8. List: select all, then make everything bigger in one step.
        compose.onNodeWithContentDescription("取消选择").performClick()
        compose.onNodeWithTag("native-edit-list").performClick()
        waitTag("native-list-all")
        compose.onNodeWithTag("native-list-all").performClick()
        waitText("native-list-count") { it.contains("已选 3/3") }
        shot("09-list-all")
        compose.onNodeWithText("在页面上编辑").performClick()
        waitText("native-edit-count") { it.contains("3 个对象") }
        compose.onNodeWithTag("native-edit-bigger").performClick()
        compose.waitUntil(15000) { runCatching { text("native-edit-message").contains("字号放大") }.getOrDefault(false) }
        shot("10-bigger")

        // 9. Save: a new library document; the source stays.
        val before = runBlocking { repo.dao.allDocuments().map { it.id }.toSet() }
        val t2 = System.nanoTime()
        compose.onNodeWithTag("native-edit-save").performClick()
        var saved: DocumentRow? = null
        compose.waitUntil(90000) {
            saved = runBlocking { repo.dao.allDocuments().firstOrNull { it.id !in before && it.title.startsWith(title) && it.pdfPath != null } }
            val found = saved
            found != null && File(found.pdfPath!!).length() > 0
        }
        timing.put("saveToLibraryMs", (System.nanoTime() - t2) / 1_000_000)
        val copy = saved!!
        assertTrue("saved copy is listed in the library", copy.listed)
        val reread = runBlocking { NativePdfEditor.inspect(File(copy.pdfPath!!), 0).second }
        assertEquals(3, reread.size)
        assertTrue(reread.first { it.index == first.index }.text.endsWith("v2.1"))
        assertTrue(reread.all { it.edited })
        // Step 8 made everything 10 % bigger.
        assertTrue(reread.all { it.size > objects.first { o -> o.index == it.index }.size * 1.05f })
        // Source unchanged.
        val original = runBlocking { NativePdfEditor.inspect(File(doc.pdfPath!!), 0).second }
        assertEquals(objects.map { it.text }, original.map { it.text })
        File(proof, "saved.pdf").outputStream().use { out -> File(copy.pdfPath!!).inputStream().use { it.copyTo(out) } }
        File(proof, "timing.json").writeText(timing.toString())
    }
}
