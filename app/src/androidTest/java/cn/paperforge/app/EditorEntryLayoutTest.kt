package cn.paperforge.app

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.PageRow
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #31 device test for the rearranged editor entry points. It only uses semantic actions (clicks),
 * never raw touch injection, so it is not blocked by the local AVD touch-injection issue recorded in
 * docs/development/HANDOFF-2026-10-31.md.
 *
 * Covered: persistent thumbnail strip, the six bottom primary actions, the grouped「更多」sheet with
 * a working rotation item, and the new in-editor receipt entry (no cached OCR → guidance dialog).
 */
@RunWith(AndroidJUnit4::class)
class EditorEntryLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun editorKeepsThumbnailsSixPrimaryActionsGroupedMoreAndReceiptEntry() {
        val app = compose.activity.application as PaperForgeApp
        val repo = app.repository
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val doc = runBlocking { repo.newScan() }
        runBlocking {
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
            compose.waitUntil(20000) { model.pages.value.size == 2 }
            compose.waitForIdle()

            // 1) The thumbnail strip is persistent (expanded by default).
            compose.onNodeWithTag("thumbnail-strip").assertIsDisplayed()
            // Previews are decoded off the main thread; wait for both the canvas and the strip so
            // the acceptance screenshots are not taken on a blank/loading editor.
            compose.waitUntil(30000) {
                compose
                    .onAllNodesWithContentDescription("扫描预览", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty() &&
                    compose
                        .onAllNodesWithContentDescription("第 1 页", useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
            }

            // 2) The six bottom primary actions are all reachable.
            listOf("加页", "裁剪", "滤镜", "识字", "删除", "更多").forEach { label ->
                compose.onNodeWithContentDescription(label).assertIsDisplayed()
            }
            capture("issue31-editor-default.png")
            // 加页: one tap goes to the camera (not asserted here to keep the editor open), the
            // long-press alternative sheet lists import/retake/duplicate.
            compose
                .onNodeWithContentDescription("加页")
                .performSemanticsAction(SemanticsActions.OnLongClick)
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("添加页面").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("继续拍摄（新页）").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            // The sheet must leave the semantics tree, otherwise the next screenshot could catch it
            // mid-dismiss on a small screen.
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("添加页面").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("thumbnail-strip").assertIsDisplayed()

            // 3) The grouped「更多」sheet opens and its rotation item works.
            compose.onNodeWithContentDescription("更多").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("识别与文字").assertIsDisplayed()
            compose.onNodeWithText("页面与结构").performScrollTo().assertIsDisplayed()
            capture("issue31-more-menu.png")
            compose.onNodeWithText("顺时针旋转 90°").performScrollTo().performClick()
            compose.waitUntil(20000) {
                !model.busy.value && model.pages.value.firstOrNull()?.rotation == 90
            }
            assertEquals(90, model.pages.value.first().rotation)

            // 4) The receipt entry opens from the editor; no cached OCR → guidance, no crash.
            compose.onNodeWithContentDescription("更多").performClick()
            compose.waitForIdle()
            compose
                .onNodeWithText("票据信息（OCR 字段核对／CSV、XLSX）")
                .performScrollTo()
                .performClick()
            compose.waitForIdle()
            compose.waitUntil(30000) {
                compose
                    .onAllNodesWithText(
                        "这份文档还没有可用的离线 OCR 文字，无法抽取票据字段。请先识别文字后再试。"
                    )
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("知道了").performClick()
            compose.waitForIdle()

            // 5) Delete keeps its second confirmation, and the top-bar undo restores the page.
            compose.onNodeWithContentDescription("删除").performClick()
            compose.onNodeWithText("删除这一页？").assertIsDisplayed()
            compose.onNodeWithText("删除").performClick()
            compose.waitUntil(20000) { !model.busy.value && model.pages.value.size == 1 }
            compose.onNodeWithContentDescription("撤销").performClick()
            compose.waitUntil(20000) { !model.busy.value && model.pages.value.size == 2 }

            // 6) Second add-page entry: 更多 → 添加页面… opens the same sheet.
            compose.onNodeWithContentDescription("更多").performClick()
            compose.waitForIdle()
            compose
                .onNodeWithText("添加页面…（拍摄／导入／重拍／复制）")
                .performScrollTo()
                .performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("添加页面").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("导入图片（可多选）").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("添加页面").fetchSemanticsNodes().isEmpty()
            }
        } finally {
            compose.runOnIdle { model.screen.value = "home" }
            runBlocking { repo.deleteDocument(doc) }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File((compose.activity.application as PaperForgeApp).filesDir, name).outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        shot.recycle()
    }
}
