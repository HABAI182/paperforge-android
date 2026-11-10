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
            // Fullscreen hides the "n / N" badge (#31 decision 5), so check which page is on screen.
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithTag("scan-page-${pages[0].id}").assertIsDisplayed() }.isSuccess
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
            // 2.0 editor: "滤镜" opens the filter row (原图, 练习册, 强力去背景, 灰度).
            compose.onNodeWithText("滤镜", useUnmergedTree = true).performClick()
            compose.onNodeWithText("灰度").performClick()
            compose.waitUntil(10000) {
                !model.busy.value && model.pages.value.first().filter == "GRAY"
            }
            compose.onNodeWithText("对比原图").performClick()
            compose.waitUntil(5000) {
                runCatching { compose.onNodeWithContentDescription("原图对照").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithText("查看增强").performClick()
            compose.onNodeWithText("收起").performClick()
            compose.onNodeWithText("裁剪旋转", useUnmergedTree = true).performClick()
            // The crop view appears once the photo is decoded.
            compose.waitUntil(15000) {
                compose.onAllNodesWithTag("crop-canvas").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("crop-canvas").performTouchInput {
                swipe(
                    androidx.compose.ui.geometry.Offset(width * .50125f, height * .08833f),
                    androidx.compose.ui.geometry.Offset(width * .50125f, height * .12833f),
                    400,
                )
            }
            // The nudge keys appear after the drag and push the shape presets down the scrolling panel.
            compose.onNodeWithText("A4/A5").performScrollTo().performClick()
            compose.onNodeWithText("保存").performClick()
            // A finished page is cut again from its photo on save, with the A4/A5 shape baked into the new image
            // (its outputAspect field is then 0), so check the saved shape and the crop on the source photo.
            val before = pages[0]
            try {
                compose.waitUntil(30000) {
                    val now = model.pages.value.first()
                    !model.busy.value && (now.outputAspect > .7f || now.original != before.original)
                }
            } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                val now = model.pages.value.first()
                throw AssertionError(
                    "not saved: busy=${model.busy.value} aspect=${now.outputAspect} sameFile=${now.original == before.original} " +
                        "corners=${now.corners} filter=${now.filter} method=${now.detectionMethod} " +
                        "flat=${cn.paperforge.app.data.PageResources.metadata(now).has("flatDocument")} " +
                        "cropOpen=${compose.onAllNodesWithTag("crop-canvas").fetchSemanticsNodes().size} " +
                        "saveButtons=${compose.onAllNodesWithText("保存").fetchSemanticsNodes().size}",
                    timeout,
                )
            }
            val saved = model.pages.value.first()
            val shown = runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { cn.paperforge.app.imaging.ScanProcessor.render(saved, 1200) } }
            assertEquals("saved page has the A4/A5 shape", .7071f, shown.width.toFloat() / shown.height, .02f)
            shown.recycle()
            val edited =
                cn.paperforge.app.model.Quad.decode(cn.paperforge.app.data.PageResources.sourceAppearance(saved).corners)
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
            // The 2.0 "more" sheet groups its tools; the diagnostic package is under 文档与记录.
            compose.onNodeWithText("文档与记录").performScrollTo().performClick()
            compose.onNodeWithText("保存本页诊断包（含原图）").let { item ->
                runCatching { item.performScrollTo() }
                item.performClick()
            }
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
                assertEquals("GRAY", report.getString("filter"))
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
