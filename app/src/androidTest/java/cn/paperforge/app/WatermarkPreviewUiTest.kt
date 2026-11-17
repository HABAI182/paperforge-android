package cn.paperforge.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The watermark screen as a user drives it, on real documents (files/corpus): wait for the candidates, switch to
 * "去除后", toggle candidates quickly (the case that used to show "这一页暂时无法显示"), turn pages. Every view must render
 * a page; "这一页暂时无法显示" or a preview error fails the test. Screenshots and timings go to files/corpus-out/wm-ui/.
 */
class WatermarkPreviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PaperForgeApp

    private val out by lazy { File(app.filesDir, "corpus-out/wm-ui").apply { mkdirs() } }

    private fun shot(name: String) {
        repeat(3) {
            compose.waitForIdle()
            val image = runCatching { compose.onRoot().captureToImage().asAndroidBitmap() }.getOrNull()
            if (image != null) {
                File(out, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return
            }
            Thread.sleep(300)
        }
    }

    private fun failureShown() =
        compose.onAllNodesWithText("这一页暂时无法显示", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithTag("wm-preview-error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Waits until the given view shows a rendered page; fails at once on the failure state. Returns milliseconds. */
    private fun rendered(view: String, label: String, ms: Long = 30000): Long {
        val t0 = System.nanoTime()
        try {
            compose.waitUntil(ms) {
                check(!failureShown()) { "$label: preview failed" }
                compose.onAllNodes(SemanticsMatcher("a rendered reader page") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("reader-viewport-") == true }
                        .and(hasAnyAncestor(hasTestTag(view))), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            shot("fail-$label")
            throw AssertionError("$label: no rendered page in $view (${e.message})", e)
        }
        return (System.nanoTime() - t0) / 1_000_000
    }

    @Test
    fun previewsRenderWhileTogglingOnRealDocuments() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val report = JSONObject()
        for (name in listOf("camscanner-zh.pdf", "camscanner-en.pdf", "wm-guide.pdf", "edge-report.pdf", "word-watermark.pdf")) {
            val file = File(app.filesDir, "corpus/$name")
            assertTrue("push $name to files/corpus first", file.exists())
            val stem = name.substringBefore('.')
            val times = JSONObject()
            compose.runOnIdle {
                vm.watermarkInput.value = Uri.fromFile(file)
                vm.screen.value = "document-watermarks"
            }
            compose.waitUntil(60000) { compose.onAllNodesWithTag("wm-after", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            times.put("original", rendered("wm-view-original", "$stem-original"))
            compose.onNodeWithTag("wm-after", useUnmergedTree = true).performScrollTo().performClick()
            times.put("after", rendered("wm-view-after", "$stem-after"))
            shot("$stem-after")
            // Toggle the first candidate off and on quickly, three times: previews are rebuilt while others run.
            val boxes = compose.onAllNodes(isToggleable().and(hasAnyAncestor(hasTestTag("wm-candidate"))), useUnmergedTree = true)
            if (boxes.fetchSemanticsNodes().isNotEmpty()) {
                repeat(3) {
                    boxes[0].performScrollTo().performClick()
                    Thread.sleep(80)
                    boxes[0].performClick()
                    Thread.sleep(40)
                }
                compose.onNodeWithTag("wm-after", useUnmergedTree = true).performScrollTo()
                times.put("afterToggle", rendered("wm-view-after", "$stem-toggled"))
            }
            // Next page, if any, in the after view.
            val next = compose.onAllNodes(hasText("下一页").and(hasClickAction()).and(isEnabled()))
            if (next.fetchSemanticsNodes().isNotEmpty()) {
                runCatching { next[0].performScrollTo().performClick() }
                times.put("nextPage", rendered("wm-view-after", "$stem-page2"))
                shot("$stem-page2")
            }
            report.put(name, times)
            compose.runOnIdle { vm.screen.value = "home" }
            compose.waitForIdle()
        }
        File(out, "timing.json").writeText(report.toString(1))
    }

    /**
     * The crash: switching between the original and "去除后" again and again, with page turns between. Each switch
     * must render a page; afterwards the original must show the page's own colours (no yellow over it).
     */
    @Test
    fun rapidSwitchingDoesNotCrashAndOriginalKeepsItsColours() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val report = JSONObject()
        for (name in listOf("camscanner-zh.pdf", "wm-guide.pdf", "word-watermark.pdf")) {
            val file = File(app.filesDir, "corpus/$name")
            assertTrue("push $name to files/corpus first", file.exists())
            val stem = name.substringBefore('.')
            compose.runOnIdle {
                vm.watermarkInput.value = Uri.fromFile(file)
                vm.screen.value = "document-watermarks"
            }
            compose.waitUntil(60000) { compose.onAllNodesWithTag("wm-after", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            rendered("wm-view-original", "$stem-start")
            val t0 = System.nanoTime()
            repeat(25) { round ->
                compose.onNodeWithTag("wm-after", useUnmergedTree = true).performScrollTo().performClick()
                Thread.sleep(if (round % 3 == 0) 30 else 160)
                compose.onNodeWithTag("wm-original", useUnmergedTree = true).performScrollTo().performClick()
                Thread.sleep(if (round % 4 == 0) 20 else 120)
                check(!failureShown()) { "$stem: failure shown at round $round" }
                if (round % 5 == 4) {
                    val next = compose.onAllNodes(hasText("下一页").and(hasClickAction()).and(isEnabled()))
                    val prev = compose.onAllNodes(hasText("上一页").and(hasClickAction()).and(isEnabled()))
                    runCatching { (if (next.fetchSemanticsNodes().isNotEmpty()) next[0] else prev[0]).performScrollTo().performClick() }
                }
            }
            rendered("wm-view-original", "$stem-end-original")
            compose.onNodeWithTag("wm-after", useUnmergedTree = true).performScrollTo().performClick()
            rendered("wm-view-after", "$stem-end-after")
            compose.onNodeWithTag("wm-original", useUnmergedTree = true).performScrollTo().performClick()
            rendered("wm-view-original", "$stem-colour")
            // The paper of the original must stay neutral: on the brightest half of its pixels, blue as high as red.
            compose.waitForIdle()
            Thread.sleep(400)
            val image = compose.onNodeWithTag("wm-view-original", useUnmergedTree = true).captureToImage().asAndroidBitmap()
            File(out, "$stem-original-colour.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
            val bright = pixels.filter { (it shr 16 and 255) + (it shr 8 and 255) + (it and 255) > 600 }
            val red = bright.map { it shr 16 and 255 }.average()
            val blue = bright.map { it and 255 }.average()
            report.put(stem, JSONObject().put("switches", 50).put("ms", (System.nanoTime() - t0) / 1_000_000).put("paperRed", red).put("paperBlue", blue))
            assertTrue("$stem: the original's paper is tinted (red ${"%.1f".format(red)} vs blue ${"%.1f".format(blue)})", red - blue < 12)
            compose.runOnIdle { vm.screen.value = "home" }
            compose.waitForIdle()
        }
        File(out, "switching.json").writeText(report.toString(1))
    }
}
