package cn.paperforge.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibraryWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun vm() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    @Test
    fun selectionAcrossRecentTrashAndPermanentDeleteUsesOnlyChosenDocuments() {
        val repo = (compose.activity.application as PaperForgeApp).repository
        val prefix = "多选验收${System.currentTimeMillis()}"
        val documents = runBlocking {
            List(3) { index -> repo.newScan().also { repo.rename(it, "$prefix-$index") } }
        }
        try {
            compose.onNode(hasSetTextAction()).performTextReplacement(prefix)
            compose.waitUntil(10000) { vm().documents.value.any { it.title == "$prefix-0" } }
            compose.onNodeWithTag("document-list").performScrollToNode(hasText("$prefix-0"))
            compose.onNodeWithText("$prefix-0").performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithText("$prefix-1").performScrollTo().performClick()
            compose.onNodeWithText("已选 2 项").assertIsDisplayed()
            compose.onNodeWithText("回收站", useUnmergedTree = true).performClick()
            compose.waitUntil(10000) {
                runBlocking {
                    documents.take(2).all { repo.dao.document(it.id)?.trashedAt != null }
                }
            }
            assertNull(runBlocking { repo.dao.document(documents[2].id) }!!.trashedAt)
            compose.onNodeWithText("文档", useUnmergedTree = true).performClick()
            compose.onNodeWithText("回收站", substring = true).performScrollTo().performClick()
            compose.onNodeWithText("多选").performScrollTo().performClick()
            compose.onNodeWithText("全选").performClick()
            compose.onNodeWithText("恢复", useUnmergedTree = true).performClick()
            compose.waitUntil(10000) {
                runBlocking { documents.all { repo.dao.document(it.id)?.trashedAt == null } }
            }
            compose.onNodeWithText("回收站", substring = true).performScrollTo().performClick()
            compose.onNodeWithText("多选").performScrollTo().performClick()
            compose.onNodeWithText("$prefix-0").performScrollTo().performClick()
            compose.onNodeWithText("直接删除", useUnmergedTree = true).performClick()
            compose.onNodeWithText("取消").performClick()
            assertNotNull(runBlocking { repo.dao.document(documents[0].id) })
            compose.onNodeWithText("直接删除", useUnmergedTree = true).performClick()
            compose.onAllNodesWithText("直接删除").onLast().performClick()
            compose.waitUntil(10000) { runBlocking { repo.dao.document(documents[0].id) == null } }
            assertNotNull(runBlocking { repo.dao.document(documents[1].id) })
        } finally {
            runBlocking {
                documents.forEach { doc ->
                    repo.dao.document(doc.id)?.let { repo.deleteDocument(it) }
                }
            }
        }
    }

    @Test
    fun cameraBindsSharedViewportAndManualShutterStillWorks() {
        val model = vm()
        val repo = model.repo
        val previousAuto = UserSettings.bool("autoShutter", true)
        UserSettings.set("autoShutter", false)
        compose.runOnIdle { model.newScan() }
        compose.waitUntil(20000) {
            compose.onAllNodesWithContentDescription("拍照").fetchSemanticsNodes().isNotEmpty() &&
                !model.busy.value
        }
        compose.waitUntil(20000) {
            runCatching { compose.onNodeWithContentDescription("拍照").assertIsEnabled() }.isSuccess
        }
        val doc = requireNotNull(model.selected.value)
        try {
            compose.onNodeWithContentDescription("拍照").performClick()
            compose.waitUntil(20000) { !model.busy.value && model.pages.value.isNotEmpty() }
            compose.onNodeWithText("完成 1").performClick()
            compose.onNodeWithText("整理扫描").assertIsDisplayed()
            assertEquals(1, runBlocking { repo.dao.pages(doc.id).size })
        } finally {
            runBlocking { repo.deleteDocument(doc) }
            UserSettings.set("autoShutter", previousAuto)
        }
    }

    @Test
    fun customizationChangesThemeAndVisibleHomeToolsImmediately() {
        val theme = UserSettings.string("theme", "SYSTEM")
        val shortcuts = UserSettings.string("homeTools", "images,pdf,ocr,merge,compress,tools")
        try {
            compose.onNodeWithContentDescription("个性化设置").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("主题")
            compose.onNodeWithText("深色", useUnmergedTree = true).performScrollTo().performClick()
            assertEquals("DARK", UserSettings.string("theme", "SYSTEM"))
            compose.onNode(hasSetTextAction()).performTextReplacement("首页")
            compose.onNodeWithContentDescription("显示图片识字").performScrollTo().performClick()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("扫描文档").assertIsDisplayed()
            compose.onAllNodesWithText("图片识字").assertCountEquals(0)
        } finally {
            UserSettings.set("theme", theme)
            UserSettings.set("homeTools", shortcuts)
        }
    }
}
