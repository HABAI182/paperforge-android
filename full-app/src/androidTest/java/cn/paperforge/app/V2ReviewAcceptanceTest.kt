package cn.paperforge.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.network.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class V2ReviewAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm
        get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .takeScreenshot() ?: return
        val file = java.io.File(vm.repo.context.filesDir, "v2-review/$name.png")
        file.parentFile!!.mkdirs()
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun watermarkIsOnWorkspaceAndCurrentScanEditor() =
        runBlocking<Unit> {
            val old = UserSettings.pinnedTools().joinToString(",")
            val repo = vm.repo
            val doc = repo.newScan().copy(title = "去水印入口验收", listed = true)
            val file = repo.captureFile(doc.id)
            Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888).also { b ->
                b.eraseColor(android.graphics.Color.WHITE)
                file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            repo.dao.putDocument(doc)
            repo.dao.putPage(
                PageRow(UUID.randomUUID().toString(), doc.id, 0, file.path, filter = "ORIGINAL")
            )
            try {
                compose.runOnIdle {
                    UserSettings.set("pinnedTools", SettingKeys.DefaultPinnedTools)
                    vm.screen.value = "home"
                    vm.homeTab.value = 2
                    vm.workspaceGroup.value = ""
                }
                compose.waitForIdle()
                capture("workspace")
                compose.onNodeWithText("去水印", substring = false).assertIsDisplayed().performClick()
                compose.waitForIdle()
                compose.onNodeWithText("文档去水印", substring = false).assertExists()
                compose.runOnIdle { vm.open(doc) }
                compose.waitForIdle()
                compose.onNodeWithContentDescription("更多").performClick()
                capture("scan-tools")
                compose.onNodeWithText("去水印", substring = false).performClick()
                compose.waitForIdle()
                compose.onNodeWithText("文档去水印", substring = false).assertExists()
                compose.waitUntil(20000) {
                    compose
                        .onAllNodesWithText("去水印入口验收.pdf", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithText("生成去水印副本").assertIsEnabled()
            } finally {
                compose.runOnIdle {
                    UserSettings.set("pinnedTools", old)
                    vm.closeDocument()
                }
                repo.deleteDocument(doc)
            }
        }

    @Test
    fun resetScanParameterAndGroupKeepOtherPreferences() {
        val saved = UserSettings.exportJson()
        try {
            compose.runOnIdle {
                UserSettings.set(SettingKeys.StableMs, 1800f)
                UserSettings.set(SettingKeys.AnalysisMs, 450f)
                UserSettings.set(SettingKeys.Quality, 77f)
                vm.settingsSection.value = "scan"
                vm.screen.value = "settings"
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("重置稳定后拍摄等待").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(600f, UserSettings[SettingKeys.StableMs], 0f) }
            compose.onNodeWithText("恢复本页默认").performScrollTo().performClick()
            compose.onNodeWithText("恢复默认", substring = false).performClick()
            compose.runOnIdle {
                assertEquals(160f, UserSettings[SettingKeys.AnalysisMs], 0f)
                assertEquals(77f, UserSettings[SettingKeys.Quality], 0f)
            }
        } finally {
            compose.runOnIdle {
                UserSettings.importJson(saved)
                vm.screen.value = "home"
            }
        }
    }

    @Test
    fun automaticUpdateCanScheduleChangeNetworkAndCancel() {
        val context = compose.activity
        val prefs = NetworkPreferences(context)
        val oldRepository = prefs.repository
        val oldEnabled = prefs.enabled
        val oldAutomatic = prefs.automaticUpdates
        val oldDownload = prefs.automaticDownload
        val manager = WorkManager.getInstance(context)
        try {
            // Invalid local configuration exercises scheduling/status without a remote request.
            prefs.repository = "invalid"
            prefs.enabled = true
            prefs.automaticUpdates = true
            prefs.automaticDownload = true
            AutomaticUpdates.sync(context)
            val first = manager.getWorkInfosForUniqueWork(AutomaticUpdates.WORK).get().last()
            assertEquals(NetworkType.UNMETERED, first.constraints.requiredNetworkType)
            prefs.automaticDownload = false
            AutomaticUpdates.sync(context)
            val changed = manager.getWorkInfosForUniqueWork(AutomaticUpdates.WORK).get().last()
            assertEquals(NetworkType.CONNECTED, changed.constraints.requiredNetworkType)
            prefs.automaticUpdates = false
            AutomaticUpdates.sync(context)
            assertTrue(
                manager.getWorkInfosForUniqueWork(AutomaticUpdates.WORK).get().all {
                    it.state.isFinished
                }
            )
        } finally {
            prefs.repository = oldRepository
            prefs.enabled = oldEnabled
            prefs.automaticUpdates = oldAutomatic
            prefs.automaticDownload = oldDownload
            AutomaticUpdates.sync(context)
        }
    }
}
