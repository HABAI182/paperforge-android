package cn.paperforge.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Round-10 screens as a user reaches them: the library filter toolbar and its menus, Settings → 字体管理 and the font
 * manager, About → licences and acknowledgments. Screenshots go to files/corpus-out/polish/<prefix>-*.png, where the
 * prefix comes from the instrumentation argument "shotPrefix" (run once per screen size / font scale).
 */
class PolishScreensUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PaperForgeApp

    private val prefix by lazy {
        androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "default"
    }
    private val out by lazy { File(app.filesDir, "corpus-out/polish").apply { mkdirs() } }

    /** The whole screen as the user sees it, dialogs and menus included. */
    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(out, "$prefix-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shotAll(name: String) = shot(name)

    private fun waitTag(tag: String, ms: Long = 15000) =
        compose.waitUntil(ms) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun libraryToolbarAndMenus() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnIdle {
            vm.screen.value = "home"
            vm.homeTab.value = 1
        }
        waitTag("library-filter-bar", 30000)
        shot("library")
        // The sort menu: opens below its chip; no stray divider when there are no saved views and nothing to save.
        compose.onNodeWithTag("filter-sort", useUnmergedTree = true).performClick()
        waitTag("filter-sort-item-MODIFIED")
        shotAll("library-sort-menu")
        // The sort chip never offers a clear cross.
        assertTrue(compose.onAllNodesWithTag("filter-sort-clear", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        val other = compose.onAllNodes(hasTestTagPrefix("filter-sort-item-"), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) }
            .firstOrNull { it != "filter-sort-item-MODIFIED" }
        if (other != null) {
            compose.onNodeWithTag(other, useUnmergedTree = true).performClick()
            compose.waitForIdle()
            shot("library-sorted")
            assertTrue("a non-default order must not add a clear button", compose.onAllNodesWithTag("filter-sort-clear", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            // Back to the default order.
            compose.onNodeWithTag("filter-sort", useUnmergedTree = true).performClick()
            waitTag("filter-sort-item-MODIFIED")
            compose.onNodeWithTag("filter-sort-item-MODIFIED", useUnmergedTree = true).performClick()
            compose.waitForIdle()
        } else {
            compose.onNodeWithTag("filter-sort-item-MODIFIED", useUnmergedTree = true).performClick()
        }
        // A type filter: the chip shows the choice and its own small cross; tapping the cross restores "类型".
        compose.onNodeWithTag("filter-collection", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasTestTagPrefix("filter-collection-item-"), useUnmergedTree = true).fetchSemanticsNodes().size > 1 }
        shotAll("library-type-menu")
        val pick = compose.onAllNodes(hasTestTagPrefix("filter-collection-item-"), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) }
            .first { it != "filter-collection-item-ALL" }
        compose.onNodeWithTag(pick, useUnmergedTree = true).performClick()
        waitTag("filter-collection-clear")
        shot("library-type-active")
        compose.onNodeWithTag("filter-collection-clear", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("filter-collection-clear", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        shot("library-cleared")
    }

    @Test
    fun settingsFontManager() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnIdle {
            vm.settingsSection.value = "fonts"
            vm.screen.value = "settings"
        }
        waitTag("settings-font-manager")
        shot("settings-fonts")
        compose.onNodeWithTag("settings-font-manager", useUnmergedTree = true).performClick()
        waitTag("font-manager")
        shotAll("font-manager-installed")
        compose.onNodeWithTag("font-tab-download", useUnmergedTree = true).performClick()
        waitTag("font-family-arimo")
        shotAll("font-manager-download")
        compose.onNodeWithTag("font-manager-search", useUnmergedTree = true).performTextInput("宋")
        compose.waitForIdle()
        shotAll("font-manager-search")
        assertTrue(compose.onAllNodesWithTag("font-family-noto-serif-sc", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aboutLicensesAndAcknowledgments() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnIdle {
            vm.screen.value = "home"
            vm.homeTab.value = 3
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText("版本信息与开源声明", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("版本信息与开源声明", useUnmergedTree = true).performScrollTo().performClick()
        waitTag("about-open-licenses")
        shotAll("about")
        compose.onNodeWithTag("about-open-licenses", useUnmergedTree = true).performClick()
        waitTag("about-licenses")
        shotAll("licenses")
        compose.onAllNodesWithText("OpenCV", substring = true, useUnmergedTree = true).onFirst().performClick()
        compose.waitForIdle()
        shotAll("licenses-opencv")
        compose.onNodeWithTag("about-page-back", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("about-licenses", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("about-open-acknowledgments", useUnmergedTree = true).performClick()
        waitTag("about-acknowledgments")
        shotAll("acknowledgments")
        val names = compose.onAllNodesWithTag("ack-name", useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { t -> t.text } }
        assertEquals(
            listOf("ChatGPT", "DeepSeek", "Claude", "Gemini", "Oxygen Sleeping", "哦就是什么", "我本纯真呢", "斯帕克勒8", "挺山", "Stefzhao", "拙劣灬小丑", "bili_44196418074", "NKAN_", "xgybddchh"),
            names,
        )
    }

    /** Status tags under a document's title, and the whole name in its menu (the list shows two lines at most). */
    @Test
    fun libraryShowsStatusTagsAndFullName() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val title = "2026 年秋季学期高等数学期中考试试卷（含参考答案与评分标准）第二版-已去水印-已编辑"
        val row = cn.paperforge.app.data.DocumentRow(
            java.util.UUID.randomUUID().toString(), title, kind = "pdf",
            statuses = cn.paperforge.app.data.DocumentStatus.encode(listOf(cn.paperforge.app.data.DocumentStatus.WATERMARK_REMOVED, cn.paperforge.app.data.DocumentStatus.EDITED)),
        )
        kotlinx.coroutines.runBlocking { app.repository.dao.putDocument(row) }
        try {
            compose.runOnIdle {
                vm.screen.value = "home"
                vm.homeTab.value = 1
            }
            waitTag("library-status-${row.id}", 30000)
            val tags = compose.onAllNodes(hasAnyAncestor(hasTestTag("library-status-${row.id}")).and(hasText("已去水印").or(hasText("已编辑")).or(hasText("Watermark removed")).or(hasText("Edited"))), useUnmergedTree = true)
            assertEquals(2, tags.fetchSemanticsNodes().size)
            shot("library-status-tags")
            compose.onNodeWithContentDescription("${title}的更多操作", useUnmergedTree = true).performClick()
            waitTag("library-full-name")
            compose.onNodeWithTag("library-full-name", useUnmergedTree = true).assertTextEquals(title)
            shot("library-full-name")
        } finally {
            kotlinx.coroutines.runBlocking { app.repository.dao.deleteDocument(row.id) }
        }
    }

    private fun hasTestTagPrefix(prefix: String) =
        SemanticsMatcher("testTag starts with $prefix") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }
}
