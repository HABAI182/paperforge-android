package cn.paperforge.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cn.paperforge.app.data.DocumentRow
import cn.paperforge.app.model.*
import cn.paperforge.app.pdf.*
import cn.paperforge.app.ui.PdfViewTransformKey
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * The editor driven by touch on real exported documents (files/corpus, pushed by the test runner): WPS's per-glyph
 * output and Edge's two-column page. Tap a body line, hold-drag across two lines, retype one word, make one line
 * bold, save, and read the saved library copy back. Screenshots and a summary go to files/corpus-out/ui-<name>/.
 */
class RealEditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PaperForgeApp

    private fun shot(dir: File, name: String) {
        repeat(3) {
            compose.waitForIdle()
            // With a sheet or dialog open there are several window roots: capture each.
            val roots = runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().size }.getOrDefault(0)
            var saved = 0
            for (i in 0 until roots) {
                val image = runCatching { compose.onAllNodes(isRoot())[i].captureToImage().asAndroidBitmap() }.getOrNull() ?: continue
                File(dir, if (i == 0) "$name.png" else "$name-window$i.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                saved++
            }
            if (saved > 0) return
            Thread.sleep(400)
        }
    }

    private fun text(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    private fun waitTag(tag: String, ms: Long = 20000) =
        compose.waitUntil(ms) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitText(dir: File, tag: String, ms: Long = 20000, check: (String) -> Boolean) {
        try {
            compose.waitUntil(ms) { runCatching { check(text(tag)) }.getOrDefault(false) }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            shot(dir, "fail-$tag")
            val seen = runCatching { text(tag) }.getOrDefault("<none>")
            val message = runCatching { text("native-edit-message") }.getOrDefault("<none>")
            throw AssertionError("waiting for $tag: saw '$seen'; message '$message'", timeout)
        }
    }

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

    @Test fun wpsExport() = drive("wps-export.pdf")

    @Test fun edgeTwoColumns() = drive("edge-report.pdf")

    private fun drive(name: String) {
        val input = File(app.filesDir, "corpus/$name")
        assertTrue("push $name to files/corpus first", input.exists())
        val dir = File(app.filesDir, "corpus-out/ui-${name.substringBefore('.')}").apply { deleteRecursively(); mkdirs() }
        val summary = JSONObject()
        val repo = app.repository
        val source = File(app.cacheDir, "real-ui-${System.currentTimeMillis()}-$name")
        input.copyTo(source, true)
        val title = "真实文档编辑-${name.substringBefore('.')}-${System.currentTimeMillis()}"
        val doc = runBlocking { repo.registerPdf(source, title, listed = false) }
        val ratio = PDDocument.load(source).use { it.getPage(0).mediaBox.let { b -> b.width / b.height } }
        val objects = runBlocking { NativePdfEditor.inspect(File(doc.pdfPath!!), 0).second }
        // Two consecutive body lines of one paragraph, long enough to hold a word to replace.
        val body = objects.filter { it.editable && it.text.trim().length >= 12 && it.units.size >= 12 }
        val pair =
            body.zipWithNext().firstOrNull { (a, b) -> b.index == a.index + 1 && b.units[0].top > a.units[0].top }
                ?: error("no two consecutive editable lines in $name")
        val (lineA, lineB) = pair
        fun mid(o: NativePdfEditor.TextObject, k: Int) = o.units[k].let { PointN((it.left + it.right) / 2, (it.top + it.bottom) / 2) }
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnIdle {
            vm.nativeEditSource.value = doc
            vm.screen.value = "pdf-native-edit"
        }
        waitTag("reader-viewport-0")
        compose.waitUntil(30000) { runCatching { text("native-edit-status").contains("第 1 /") }.getOrDefault(false) }
        shot(dir, "01-open")
        val bounds = compose.onNodeWithTag("reader-viewport-0").fetchSemanticsNode().boundsInRoot

        // 1. A tap selects exactly the line under the finger.
        compose.onNodeWithTag("reader-viewport-0").performTouchInput { click(point(mid(lineA, lineA.units.size / 2), ratio) - bounds.topLeft) }
        waitText(dir, "native-edit-count") { it.contains("1 个对象") && it.contains("${lineA.text.length} 字") }
        shot(dir, "02-tap")
        summary.put("tap", text("native-edit-count"))

        // 2. Hold and drag from line A to line B: both lines, in reading order.
        compose.onNodeWithContentDescription("取消选择").performClick()
        val from = point(mid(lineA, 2), ratio) - bounds.topLeft
        val to = point(mid(lineB, 6), ratio) - bounds.topLeft
        compose.onNodeWithTag("reader-viewport-0").performTouchInput {
            down(from)
            advanceEventTime(900)
            moveTo(from + (to - from) * .5f)
            advanceEventTime(100)
            moveTo(to)
            advanceEventTime(100)
            up()
        }
        waitText(dir, "native-edit-count") { it.contains("2 个对象") }
        shot(dir, "03-hold-drag")
        summary.put("drag", text("native-edit-count"))

        // 3. Retype one word in line A through the text field.
        compose.onNodeWithContentDescription("取消选择").performClick()
        compose.onNodeWithTag("reader-viewport-0").performTouchInput { click(point(mid(lineA, lineA.units.size / 2), ratio) - bounds.topLeft) }
        waitText(dir, "native-edit-count") { it.contains("1 个对象") }
        compose.onNodeWithTag("native-edit-text").performClick()
        waitTag("native-edit-field")
        val han = lineA.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val at = lineA.text.length / 2
        val replacement = if (han) "修订" else "EDIT"
        val expected = lineA.text.substring(0, at) + replacement + lineA.text.substring(at + 2)
        compose.onNodeWithTag("native-edit-field").performTextReplacement(expected)
        // The sheet's state must hold the new text before 应用 (a fast tap could otherwise apply the old text).
        compose.waitUntil(10000) {
            runCatching {
                compose.onNodeWithTag("native-edit-field").fetchSemanticsNode().config[SemanticsProperties.EditableText].text == expected
            }.getOrDefault(false)
        }
        compose.waitForIdle()
        val applied = System.nanoTime()
        compose.onNodeWithTag("native-edit-apply").performClick()
        shot(dir, "04a-applying")
        waitText(dir, "native-edit-message", 60000) { it.contains("已修改文字") || it.contains("补齐") }
        summary.put("retype", text("native-edit-message")).put("retypeMs", (System.nanoTime() - applied) / 1_000_000)
        shot(dir, "04-retyped")

        // 4. Bold line B.
        compose.onNodeWithContentDescription("取消选择").performClick()
        compose.onNodeWithTag("reader-viewport-0").performTouchInput { click(point(mid(lineB, lineB.units.size / 2), ratio) - bounds.topLeft) }
        waitText(dir, "native-edit-count") { it.contains("1 个对象") && it.contains("${lineB.text.length} 字") }
        compose.onNodeWithTag("native-edit-bold").performScrollTo().performClick()
        waitText(dir, "native-edit-message", 30000) { it.contains("加粗") || it.contains("没有") }
        summary.put("bold", text("native-edit-message"))
        shot(dir, "05-bold")

        // 5. Save and read the library copy back.
        val before = runBlocking { repo.dao.allDocuments().map { it.id }.toSet() }
        compose.onNodeWithTag("native-edit-save").performClick()
        var saved: DocumentRow? = null
        compose.waitUntil(120000) {
            saved = runBlocking { repo.dao.allDocuments().firstOrNull { it.id !in before && it.title.startsWith(title) && it.pdfPath != null } }
            saved?.let { File(it.pdfPath!!).length() > 0 } == true
        }
        val out = File(saved!!.pdfPath!!)
        out.copyTo(File(dir, "saved.pdf"), true)
        val reread = runBlocking { NativePdfEditor.inspect(out, 0).second }
        val squash = { s: String -> s.replace(" ", "") }
        val foundA = reread.any { squash(it.text) == squash(expected) }
        val foundB = reread.any { squash(it.text) == squash(lineB.text) }
        val others = objects.filter { it.index != lineA.index && it.index != lineB.index }.map { squash(it.text) }.sorted()
        val othersAfter = reread.map { squash(it.text) }.filter { it != squash(expected) && it != squash(lineB.text) }.sorted()
        summary.put("lineA", lineA.text).put("expected", expected).put("foundA", foundA).put("lineB", lineB.text).put("foundB", foundB)
            .put("othersUnchanged", others == othersAfter)
        File(dir, "summary.json").writeText(summary.toString(1))
        assertTrue("edited line read back from ${out.name}", foundA)
        assertTrue("bold line keeps its text", foundB)
        assertEquals("other lines unchanged", others, othersAfter)
        val original = runBlocking { NativePdfEditor.inspect(File(doc.pdfPath!!), 0).second }
        assertEquals(objects.map { it.text }, original.map { it.text })
    }
}
