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
 * Covered: persistent thumbnail strip, the six bottom primary actions (since 2.0.0: 加页, 裁剪旋转, 滤镜,
 * 此页重拍, 导出 PDF, 更多), the grouped「更多」sheet (categories first, then items) with a working
 * rotation item, the in-editor receipt entry, delete with confirmation and undo, and both add-page entries.
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
            listOf("加页", "裁剪旋转", "滤镜", "此页重拍", "导出 PDF", "更多").forEach { label ->
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

            // 3) The grouped「更多」sheet opens on its categories; 页面编排 holds the rotation item.
            openMoreGroup("页面编排", screenshot = "issue31-more-menu.png")
            compose.onNodeWithText("顺时针旋转 90°").performScrollTo().performClick()
            compose.waitUntil(20000) {
                !model.busy.value && model.pages.value.firstOrNull()?.rotation == 90
            }
            assertEquals(90, model.pages.value.first().rotation)

            // 4) The receipt entry opens from the editor and closes again. It now recognises missing text
            // offline, then shows the field review or a retry / manual-entry message; both are「票据信息」.
            openMoreGroup("导出与票据")
            compose.onNodeWithText("票据信息核对").performScrollTo().performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("票据信息").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(120000) {
                runCatching { compose.onNodeWithText("取消").assertIsEnabled() }.isSuccess
            }
            compose.onNodeWithText("取消").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("票据信息").fetchSemanticsNodes().isEmpty() }

            // 5) Delete (更多 · 页面编排 since 2.0.0) keeps its confirmation; the top-bar undo restores the page.
            openMoreGroup("页面编排")
            compose.onNodeWithText("删除本页").performScrollTo().performClick()
            compose.onNodeWithText("删除这一页？").assertIsDisplayed()
            compose.onNodeWithText("删除").performClick()
            compose.waitUntil(20000) { !model.busy.value && model.pages.value.size == 1 }
            compose.onNodeWithContentDescription("撤销").performClick()
            compose.waitUntil(20000) { !model.busy.value && model.pages.value.size == 2 }

            // 6) Second add-page entry: 更多 · 页面编排 · 添加页面 opens the same sheet.
            openMoreGroup("页面编排")
            compose.onNodeWithText("添加页面").performScrollTo().performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("导入图片（可多选）").fetchSemanticsNodes().isNotEmpty()
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

    /** Opens「更多」, checks it starts on its categories, and opens [group]. */
    private fun openMoreGroup(group: String, screenshot: String? = null) {
        compose.onNodeWithContentDescription("更多").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("识别与文字").assertIsDisplayed()
        compose.onNodeWithText(group).performScrollTo().assertIsDisplayed()
        screenshot?.let { capture(it) }
        compose.onNodeWithText(group).performScrollTo().performClick()
        compose.waitForIdle()
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
