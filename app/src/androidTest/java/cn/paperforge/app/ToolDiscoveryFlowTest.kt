package cn.paperforge.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ToolDiscoveryFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun aliasesPinsAndHomeConversionOpenTheRealOperation() {
        val original = UserSettings.exportJson()
        try {
            UserSettings.set("pinnedTools", "")
            compose.onAllNodesWithText("工具", useUnmergedTree = true).onLast().performClick()
            fun search(value: String) {
                compose
                    .onNodeWithTag("document-list")
                    .performScrollToNode(hasTestTag("tool-search"))
                compose.onNodeWithTag("tool-search").performTextReplacement(value)
            }
            fun find(title: String) {
                compose.onNodeWithTag("document-list").performScrollToNode(hasText(title))
                compose.onNodeWithText(title).assertIsDisplayed()
            }
            search("WoRd")
            find("PDF → Word · 结构重建")
            compose.onNodeWithContentDescription("PDF → Word · 结构重建的能力说明").performClick()
            compose.onNodeWithText("可编辑 DOCX 结构文档").assertIsDisplayed()
            compose.onNodeWithText("知道了").performClick()
            search("OCR")
            find("离线 OCR 图片识字")
            search("图片")
            find("图片格式互转")
            search("epub")
            find("文档 → EPUB 电子书")
            compose.onNodeWithContentDescription("固定文档 → EPUB 电子书").performClick()
            val portable = UserSettings.exportJson()
            UserSettings.toggleTool("tool:epub-create")
            UserSettings.importJson(portable.toString())
            assertTrue(UserSettings.pinnedTools().contains("tool:epub-create"))
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("document-list").fetchSemanticsNodes().isNotEmpty()
            }
            search("")
            compose.onNodeWithText("常用").performClick()
            find("文档 → EPUB 电子书")
            compose.onNodeWithContentDescription("取消固定文档 → EPUB 电子书").assertIsDisplayed()
            compose.onNodeWithText("文档 → EPUB 电子书").performClick()
            compose.onNodeWithText("生成 EPUB 电子书").assertIsDisplayed()
            compose.onNodeWithText("书名（留空沿用文档标题）").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("常用").performClick()
            compose.onNodeWithText("文档 → EPUB 电子书").assertIsDisplayed()
            compose.onNodeWithContentDescription("取消固定文档 → EPUB 电子书").performClick()
            compose.onNodeWithText("点击工具旁的星标，固定常用转换。").assertIsDisplayed()
        } finally {
            UserSettings.importJson(original.toString())
        }
    }
}
