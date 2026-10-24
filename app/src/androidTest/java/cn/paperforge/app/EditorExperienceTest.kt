package cn.paperforge.app

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.PageRow
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EditorExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun scanReviewUsesLargeCanvasSwipeZoomFullscreenAndPreservesPages() {
        val app = compose.activity.application as PaperForgeApp
        val repo = app.repository
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val doc = runBlocking { repo.newScan() }
        val pages = runBlocking {
            List(2) { index ->
                val file = repo.captureFile(doc.id)
                InstrumentationRegistry.getInstrumentation()
                    .context
                    .assets
                    .open("scan-core/dark-desk-0.jpg")
                    .use { input -> file.outputStream().use { input.copyTo(it) } }
                PageRow(
                        UUID.randomUUID().toString(),
                        doc.id,
                        index,
                        file.path,
                        corners = "0.2425,0.08;0.76,0.09666667;0.84,0.91;0.18,0.98333335",
                        filter = "ORIGINAL",
                    )
                    .also { repo.dao.putPage(it) }
            }
        }
        try {
            compose.runOnIdle { model.open(doc) }
            compose.waitUntil(15000) { model.pages.value.size == 2 }
            compose.onNodeWithText("1 / 2").assertIsDisplayed()
            val normalHeight =
                compose.onNodeWithTag("scan-canvas").fetchSemanticsNode().boundsInRoot.height
            assertTrue(
                "Canvas should exceed old 280dp preview",
                normalHeight > compose.activity.resources.displayMetrics.density * 330,
            )
            compose.onNodeWithTag("scan-canvas").performTouchInput { swipeLeft() }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("scan-page-${pages[1].id}").performTouchInput { doubleClick() }
            compose.waitUntil(5000) {
                compose
                    .onAllNodes(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.StateDescription,
                            "缩放 200%",
                        )
                    )
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("scan-page-${pages[1].id}").performTouchInput { doubleClick() }
            compose.waitUntil(5000) {
                compose
                    .onAllNodes(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.StateDescription,
                            "缩放 100%",
                        )
                    )
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithContentDescription("全屏检查").performClick()
            compose.onNodeWithContentDescription("退出全屏检查").assertIsDisplayed()
            val fullHeight =
                compose.onNodeWithTag("scan-canvas").fetchSemanticsNode().boundsInRoot.height
            assertTrue("Fullscreen should reclaim controls", fullHeight > normalHeight + 100)
            compose.onNodeWithTag("scan-canvas").performTouchInput { swipeRight() }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithContentDescription("扫描预览").assertIsDisplayed() }
                    .isSuccess
            }
            compose.waitForIdle()
            val screenshot =
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(app.filesDir, "v061-editor-fullscreen.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            compose.onNodeWithContentDescription("退出全屏检查").performClick()
            compose.onNodeWithText("增强", useUnmergedTree = true).performClick()
            compose.onNodeWithText("清晰").performClick()
            compose.waitUntil(10000) {
                !model.busy.value && model.pages.value.first().filter == "TEXT"
            }
            compose.onNodeWithText("对比原图").performClick()
            compose.onNodeWithContentDescription("原图对照").assertIsDisplayed()
            compose.onNodeWithText("查看增强").performClick()
            compose.onNodeWithText("收起").performClick()
            compose.onNodeWithText("裁切", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("crop-canvas").performTouchInput {
                swipe(
                    androidx.compose.ui.geometry.Offset(width * .50125f, height * .08833f),
                    androidx.compose.ui.geometry.Offset(width * .50125f, height * .12833f),
                    400,
                )
            }
            compose.onNodeWithText("A4/A5").performClick()
            compose.onNodeWithText("应用").performClick()
            compose.waitUntil(10000) {
                !model.busy.value && model.pages.value.first().outputAspect > .7f
            }
            val edited = cn.paperforge.app.model.Quad.decode(model.pages.value.first().corners)
            assertTrue(edited.points[0].y > .09f && edited.points[1].y > .10f)
            assertEquals(.2425f, edited.points[0].x, .001f)
            compose.waitForIdle()
            Thread.sleep(350) // Wait for the platform system-bar inset transition, outside Compose.
            assertEquals(2, runBlocking { repo.dao.pages(doc.id).size })
            assertTrue(File(pages[0].original).exists())
            val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(app.filesDir, "next-editor.png").outputStream().use {
                shot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            shot.recycle()
            compose.onNodeWithText("更多", useUnmergedTree = true).performClick()
            compose.onNodeWithText("保存本页诊断包（含原图）").performScrollTo().performClick()
            compose.waitUntil(15000) { model.output.value?.exists() == true && !model.busy.value }
            val diagnostic = requireNotNull(model.output.value)
            java.util.zip.ZipFile(diagnostic).use { zip ->
                assertNotNull(zip.getEntry("original.jpg"))
                assertNotNull(zip.getEntry("enhanced.jpg"))
                val report =
                    org.json.JSONObject(
                        zip.getInputStream(zip.getEntry("diagnostics.json"))
                            .bufferedReader()
                            .readText()
                    )
                assertEquals("TEXT", report.getString("filter"))
                assertEquals(64, report.getString("originalSha256").length)
                assertTrue(report.getLong("renderMs") >= 0)
            }
            compose.runOnIdle { model.output.value = null }
            diagnostic.delete()
        } finally {
            compose.runOnIdle { model.screen.value = "home" }
            runBlocking { repo.deleteDocument(doc) }
        }
    }
}
