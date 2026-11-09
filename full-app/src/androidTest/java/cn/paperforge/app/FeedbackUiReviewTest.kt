package cn.paperforge.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FeedbackUiReviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun folderDestinationAndCaptureModesAreVisibleAndUsable() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val preferences = LibraryPreferences(app)
        val oldFolders = preferences.folders()
        val oldAuto = UserSettings[SettingKeys.AutoShutter]
        val token = UUID.randomUUID().toString().take(8)
        val folder = "界面验收/$token"
        val doc = runBlocking { repo.newScan() }.copy(title = "界面验收-$token")
        try {
            val file = repo.captureFile(doc.id)
            File(app.filesDir, "workbook-candidate/notes-1.png").copyTo(file, overwrite = true)
            runBlocking {
                repo.dao.putDocument(doc)
                repo.dao.putPage(
                    PageRow(UUID.randomUUID().toString(), doc.id, 0, file.path, filter = "WORKBOOK")
                )
            }
            UserSettings.set(SettingKeys.AutoShutter, false)
            compose.runOnIdle {
                model.screen.value = "home"
                model.homeTab.value = 1
                model.query.value = doc.title
                model.createLibraryFolder(folder)
            }
            compose.waitUntil(15_000) {
                folder in model.libraryFolderPaths.value &&
                    compose
                        .onAllNodesWithContentDescription("分类 · 未分类")
                        .fetchSemanticsNodes()
                        .size == 1
            }
            compose
                .onNodeWithContentDescription("分类 · 未分类")
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText(token).performSemanticsAction(SemanticsActions.OnClick)
            capture(app, "folder-destination-picker.png")
            compose.onNodeWithText("移入$token").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(15_000) {
                compose
                    .onAllNodesWithContentDescription("分类 · $folder")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            capture(app, "document-category-visible.png")
            compose
                .onNodeWithTag("library-document-${doc.id}")
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(15_000) {
                model.screen.value == "editor" && model.folderNotice.value == null
            }
            compose
                .onNodeWithContentDescription("更多")
                .performSemanticsAction(SemanticsActions.OnClick)
            capture(app, "editor-more-categories.png")
            compose.onNodeWithText("画面与修复").performSemanticsAction(SemanticsActions.OnClick)
            compose
                .onNodeWithContentDescription("自动裁边 · 能力与格式说明")
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("输入").assertExists()
            compose.onNodeWithText("输出").assertExists()
            capture(app, "tool-capability.png")
            compose.onNodeWithText("知道了").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("高清与修复").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("练习册高清").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText("实时预览已更新").fetchSemanticsNodes().isNotEmpty()
            }
            compose
                .onNodeWithContentDescription("全屏检查")
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("另存副本").assertDoesNotExist()
            capture(app, "automatic-preview-fullscreen.png")
            compose
                .onNodeWithContentDescription("保存草稿并返回")
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(10_000) { model.screen.value == "editor" }
            compose.runOnIdle { model.screen.value = "capture" }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("原色").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("原色").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(10_000) { UserSettings[SettingKeys.Filter] == "AUTO" }
            compose.onNodeWithText("去透页").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(10_000) { UserSettings[SettingKeys.Filter] == "CLEAN" }
            assertEquals(0f, UserSettings[SettingKeys.ScanAspect])
            capture(app, "capture-mode-rail.png")
        } finally {
            compose.runOnIdle {
                model.screen.value = "home"
                model.selected.value = null
                model.query.value = ""
            }
            runBlocking { repo.deleteDocument(doc) }
            preferences.folders(oldFolders)
            UserSettings.set(SettingKeys.AutoShutter, oldAuto)
            UserSettings.set(SettingKeys.Filter, "WORKBOOK")
            UserSettings.set(SettingKeys.ScanAspect, 0f)
        }
    }

    private fun capture(app: PaperForgeApp, name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Semantics can lead the SurfaceFlinger buffer by one frame; wait for that buffer only.
        Thread.sleep(120)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            val folder = File(app.filesDir, "feedback-ui").apply { mkdirs() }
            File(folder, name).outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            image.recycle()
        }
    }
}
