package cn.paperforge.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.model.UsageGuide
import cn.paperforge.app.ui.settingsSections
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UsageGuideUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsEntryOfflineSearchJumpAndLargeType() {
        val app = compose.activity.application as PaperForgeApp
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val theme = UserSettings.string("theme", "SYSTEM")
        val font = UserSettings[SettingKeys.FontScale]
        val systemFont =
            Settings.System.getFloat(app.contentResolver, Settings.System.FONT_SCALE, 1f)
        fun systemScale(value: Float) {
            ParcelFileDescriptor.AutoCloseInputStream(
                    InstrumentationRegistry.getInstrumentation()
                        .uiAutomation
                        .executeShellCommand("settings put system font_scale $value")
                )
                .use { it.readBytes() }
        }
        val proof = File(app.getExternalFilesDir(null), "issue14").apply { mkdirs() }
        fun shot(name: String) {
            compose.onRoot().captureToImage().asAndroidBitmap().let { b ->
                File(proof, "$name.png").outputStream().use {
                    b.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
        try {
            assertEquals(6, settingsSections.size)
            assertTrue(UsageGuide.topics.all { it.steps.size in 2..3 && it.note.isNotBlank() })
            assertEquals(
                setOf("capture", "convert", "documents", "organize", "pdf/pages", "pdf/optimize"),
                UsageGuide.topics.map { it.destination }.toSet(),
            )
            assertTrue(UsageGuide.search("PDF 增强").any { it.id == "enhance-pdf" })
            compose.runOnIdle { model.screen.value = "home" }
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("使用说明").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("搜索使用说明").performTextInput("PDF 增强")
            compose.onNodeWithText("PDF 二次增强").assertExists()
            compose.onNodeWithText("前往 PDF 优化工具").performScrollTo().assertIsDisplayed()
            shot("help-search")
            compose.onNodeWithText("前往 PDF 优化工具").performClick()
            assertEquals("pdf/optimize", model.workspaceGroup.value)
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("图像增强 · 扫描 PDF 二次增强").fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle { model.screen.value = "help" }
            systemScale(2f)
            compose.waitUntil(10000) { compose.activity.resources.configuration.fontScale >= 1.99f }
            for (mode in listOf("LIGHT", "DARK")) {
                compose.runOnIdle { UserSettings.set("theme", mode) }
                compose.onNodeWithText("搜索使用说明").performTextReplacement("备份")
                compose.onNodeWithText("前往文档整理").performScrollTo().assertIsDisplayed()
                shot("help-${mode.lowercase()}-200")
                compose.onNodeWithContentDescription("返回设置").assertIsDisplayed()
            }
            compose.onNodeWithContentDescription("返回设置").performClick()
            compose.onNodeWithText("使用说明").assertExists()
        } finally {
            systemScale(systemFont)
            compose.runOnIdle {
                UserSettings.set("theme", theme)
                UserSettings.set("fontScale", font)
                model.screen.value = "home"
            }
        }
    }
}
