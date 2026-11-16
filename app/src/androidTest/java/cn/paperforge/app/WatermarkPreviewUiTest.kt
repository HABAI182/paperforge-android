package cn.paperforge.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
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
                compose.onAllNodes(hasTestTag("reader-viewport-0").or(hasTestTag("reader-viewport-1")).or(hasTestTag("reader-viewport-2"))
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
}
