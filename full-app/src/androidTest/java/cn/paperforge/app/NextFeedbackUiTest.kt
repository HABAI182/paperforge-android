package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NextFeedbackUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun rotationCompactCameraCardOriginalAndRetake() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val oldAuto = UserSettings[SettingKeys.AutoShutter]
        val doc = runBlocking { repo.newScan() }
        val source = repo.captureFile(doc.id)
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(
                "PaperForge 1.2.0",
                60f,
                120f,
                Paint().apply {
                    color = Color.BLACK
                    textSize = 40f
                },
            )
            drawLine(
                40f,
                200f,
                560f,
                200f,
                Paint().apply {
                    color = Color.GRAY
                    strokeWidth = 2f
                },
            )
        }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        val page =
            PageRow(UUID.randomUUID().toString(), doc.id, 0, source.path, filter = "ORIGINAL")
        runBlocking { repo.dao.putPage(page) }
        try {
            UserSettings.set(SettingKeys.AutoShutter, false)
            UserSettings.set(SettingKeys.ReviewAfterCapture, false)
            UserSettings.set(SettingKeys.Filter, "WORKBOOK")
            UserSettings.set("captureMode", "document")
            compose.runOnIdle {
                model.selected.value = doc
                model.screen.value = "editor"
            }
            compose.waitUntil(15000) {
                compose.onAllNodesWithContentDescription("裁剪旋转").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("裁剪旋转").performClick()
            compose.onNodeWithText("旋转校正").performClick()
            compose.onNodeWithText("右转 90°").performClick()
            compose
                .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                .performSemanticsAction(SemanticsActions.SetProgress) { it(3.5f) }
            compose.waitUntil(15_000) {
                compose
                    .onAllNodesWithContentDescription("旋转预览 · 90° · 3.5°")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("裁剪边界").performClick()
            compose.onNodeWithText("旋转校正").performClick()
            capture(app, "rotation.png")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(15000) {
                runBlocking {
                    repo.dao.pages(doc.id).first().let {
                        it.rotation == 90 && it.deskewDegrees == 3.5f
                    }
                }
            }
            compose.runOnIdle { model.screen.value = "capture" }
            compose.waitUntil(15000) {
                compose.onAllNodesWithText("多页").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("多页").assertIsSelected()
            compose.onNodeWithText("黑白").assertDoesNotExist()
            capture(app, "camera-compact.png")
            compose.onNodeWithText("去透页").performClick()
            compose.waitUntil(10000) { UserSettings[SettingKeys.Filter] == "CLEAN" }
            compose.onNodeWithText("证件").performClick()
            compose.waitUntil(10000) { UserSettings[SettingKeys.Filter] == "ORIGINAL" }
            capture(app, "camera-card.png")
            compose.onNodeWithContentDescription("此页重拍").assertDoesNotExist()
            compose.runOnIdle { model.screen.value = "editor" }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("此页重拍").performClick()
            compose.waitUntil(5000) { model.replacement.value == page.id }
            capture(app, "camera-retake.png")
        } finally {
            compose.runOnIdle {
                model.screen.value = "home"
                model.selected.value = null
                model.replacement.value = null
            }
            runBlocking { repo.deleteDocument(doc) }
            UserSettings.set(SettingKeys.AutoShutter, oldAuto)
            UserSettings.set(SettingKeys.Filter, "WORKBOOK")
            UserSettings.set(SettingKeys.ScanAspect, 0f)
            UserSettings.set("captureMode", "document")
        }
    }

    private fun capture(app: PaperForgeApp, name: String) {
        compose.waitForIdle()
        Thread.sleep(160)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            val root = File(app.filesDir, "flow-review-ui").apply { mkdirs() }
            File(root, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
