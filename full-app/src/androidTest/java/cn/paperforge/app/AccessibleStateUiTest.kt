package cn.paperforge.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import cn.paperforge.app.model.Quad
import cn.paperforge.app.ui.PdfViewTransformKey
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class AccessibleStateUiTest {
    private val window = WindowLayoutInfoPublisherRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(window).around(compose)
    private val app
        get() = compose.activity.application as PaperForgeApp

    private val model
        get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand(command)
            )
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun screenshot(name: String, dialog: Boolean = false) {
        val file = File(app.getExternalFilesDir(null), "issue8/$name.png")
        file.parentFile!!.mkdirs()
        val image =
            (if (dialog) compose.onNode(isDialog()) else compose.onRoot())
                .captureToImage()
                .asAndroidBitmap()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun cropRetainsUnsavedCornersAndActionsAt200PercentInLandscape() {
        val (doc, page) = runBlocking { AccessibilityFixtures.scan(app) }
        val scale = Settings.System.getFloat(app.contentResolver, Settings.System.FONT_SCALE, 1f)
        val orientation = compose.activity.requestedOrientation
        try {
            compose.runOnIdle { model.open(doc) }
            compose.waitUntil(15000) {
                compose.onAllNodesWithContentDescription("裁切").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("裁切").performClick()
            compose.onNodeWithText("按位置调整").performScrollTo().performClick()
            compose.onNodeWithTag("crop-x").performScrollTo().performSemanticsAction(
                SemanticsActions.SetProgress
            ) {
                it(.12f)
            }
            compose.onNodeWithTag("crop-y").performScrollTo().performSemanticsAction(
                SemanticsActions.SetProgress
            ) {
                it(.08f)
            }
            compose.onNodeWithText("方形").performScrollTo().performClick()
            assertEquals(
                Quad.Full.encode(),
                runBlocking { app.repository.dao.pages(doc.id).firstOrNull { it.id == page.id } }!!
                    .corners,
            )
            shell("settings put system font_scale 2.0")
            compose.waitUntil(15000) { compose.activity.resources.configuration.fontScale >= 1.99f }
            compose.onNodeWithText("取消").assertIsDisplayed()
            compose.onNodeWithText("应用").assertIsDisplayed()
            screenshot("crop-portrait-200", true)
            compose.runOnIdle {
                compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(15000) {
                compose.activity.resources.configuration.screenWidthDp >
                    compose.activity.resources.configuration.screenHeightDp
            }
            compose.onNodeWithTag("crop-x").performScrollTo()
            assertEquals(
                .12f,
                compose
                    .onNodeWithTag("crop-x")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.ProgressBarRangeInfo]
                    .current,
                .011f,
            )
            assertEquals(
                .08f,
                compose
                    .onNodeWithTag("crop-y")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.ProgressBarRangeInfo]
                    .current,
                .011f,
            )
            compose.onNodeWithText("方形").assertIsSelected()
            compose.onNodeWithText("取消").assertIsDisplayed()
            compose.onNodeWithText("应用").assertIsDisplayed()
            screenshot("crop-landscape-200", true)
            compose.onNodeWithText("应用").performClick()
            compose.waitUntil(15000) {
                runBlocking { app.repository.dao.pages(doc.id).firstOrNull { it.id == page.id } }
                    ?.outputAspect == 1f
            }
            val changed =
                runBlocking { app.repository.dao.pages(doc.id).firstOrNull { it.id == page.id } }!!
            assertEquals(.12f, Quad.decode(changed.corners).points.first().x, .011f)
            compose.onNodeWithText("导出").assertIsDisplayed()
        } finally {
            shell("settings put system font_scale $scale")
            compose.runOnIdle {
                compose.activity.requestedOrientation = orientation
                model.closeDocument()
            }
            runBlocking { AccessibilityFixtures.remove(app, doc) }
        }
    }

    @Test
    fun ocrCorrectionDraftSurvivesActivityRecreation() {
        val (doc, page) = runBlocking { AccessibilityFixtures.scan(app) }
        try {
            compose.runOnIdle {
                model.open(doc)
                model.proofPage.value = page
                model.screen.value = "ocr-proof"
            }
            compose.onNodeWithText("当前行").performTextReplacement("UNSAVED CORRECTION")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("UNSAVED CORRECTION").assertExists()
            assertEquals(
                "ORIGINAL OCR TEXT",
                runBlocking { app.repository.dao.pages(doc.id).firstOrNull { it.id == page.id } }!!
                    .ocrText,
            )
            compose.runOnIdle {
                model.screen.value = "editor"
                model.ocr.value = "ORIGINAL RESULT"
            }
            compose.onNodeWithText("文字内容").performTextReplacement("UNSAVED DIALOG CORRECTION")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("UNSAVED DIALOG CORRECTION").assertExists()
            compose.runOnIdle { model.ocr.value = null }
        } finally {
            compose.runOnIdle {
                model.screen.value = "home"
                model.selected.value = null
            }
            runBlocking { AccessibilityFixtures.remove(app, doc) }
        }
    }

    @Test
    fun readerFoldUnfoldKeepsPageZoomAndAccessibilityFocus() {
        val doc = runBlocking { AccessibilityFixtures.pdf(app) }
        val automation =
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val service = automation.serviceInfo
        val oldFlags = service.flags
        service.flags =
            service.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        automation.serviceInfo = service
        fun safeShell(command: String) =
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
                it.readBytes().toString(Charsets.UTF_8).trim()
            }
        val services = safeShell("settings get secure enabled_accessibility_services")
        val enabled = safeShell("settings get secure accessibility_enabled")
        val talkback = "com.android.talkback/com.google.android.marvin.talkback.TalkBackService"
        fun appRoot() =
            automation.windows
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() == "cn.paperforge.app" }
                ?: automation.rootInActiveWindow
        fun find(root: AccessibilityNodeInfo?, id: String): AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.viewIdResourceName == id) return root
            for (i in 0 until root.childCount) find(root.getChild(i), id)?.let {
                return it
            }
            return null
        }
        try {
            safeShell(
                "settings put secure enabled_accessibility_services ${if (services == "null" || services.isBlank()) talkback else "$services:$talkback"}"
            )
            safeShell("settings put secure accessibility_enabled 1")
            ReaderPreferences(app).save(doc.id, ReaderPosition(page = 1))
            compose.runOnIdle { model.open(doc) }
            compose.waitUntil(15000) {
                compose.onAllNodesWithTag("reader-viewport-1").fetchSemanticsNodes().isNotEmpty()
            }
            val zoom =
                compose
                    .onNodeWithTag("reader-viewport-1")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.CustomActions]
                    .first { it.label == "放大页面" }
            compose.runOnIdle { assertTrue(zoom.action()) }
            compose.waitUntil {
                compose
                    .onNodeWithTag("reader-viewport-1")
                    .fetchSemanticsNode()
                    .config[PdfViewTransformKey]
                    .first() > 1.4f
            }
            Thread.sleep(1500)
            assertTrue(
                find(appRoot(), "reader-viewport-1")!!.performAction(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                )
            )
            compose.waitUntil(3000) {
                find(appRoot(), "reader-viewport-1")?.isAccessibilityFocused == true
            }
            Thread.sleep(350)
            val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val width = bounds.width.toInt()
            val height = bounds.height.toInt()
            assertTrue("Run this flow with an expanded viewport", width >= 1800)
            val feature =
                object : FoldingFeature {
                    override val bounds = Rect(width / 2 - 8, 0, width / 2 + 8, height)
                    override val state = FoldingFeature.State.HALF_OPENED
                    override val orientation = FoldingFeature.Orientation.VERTICAL
                    override val isSeparating = true
                    override val occlusionType = FoldingFeature.OcclusionType.FULL
                }
            window.overrideWindowLayoutInfo(WindowLayoutInfo(listOf(feature)))
            compose.waitUntil(15000) {
                compose
                    .onAllNodesWithTag("workspace-document-pane")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            Thread.sleep(350)
            compose.waitUntil(3000) {
                find(appRoot(), "reader-viewport-1")?.isAccessibilityFocused == true
            }
            assertEquals(
                "reader-viewport-1",
                find(appRoot(), "reader-viewport-1")
                    ?.takeIf { it.isAccessibilityFocused }
                    ?.viewIdResourceName,
            )
            assertTrue(
                compose
                    .onNodeWithTag("reader-viewport-1")
                    .fetchSemanticsNode()
                    .config[PdfViewTransformKey]
                    .first() > 1.4f
            )
            screenshot("folded-reader")
            window.overrideWindowLayoutInfo(WindowLayoutInfo(emptyList()))
            compose.waitUntil(15000) {
                compose.onAllNodesWithTag("workspace-document-pane").fetchSemanticsNodes().isEmpty()
            }
            Thread.sleep(350)
            compose.waitUntil(3000) {
                find(appRoot(), "reader-viewport-1")?.isAccessibilityFocused == true
            }
            assertEquals(
                "reader-viewport-1",
                find(appRoot(), "reader-viewport-1")
                    ?.takeIf { it.isAccessibilityFocused }
                    ?.viewIdResourceName,
            )
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) {
                compose.onAllNodesWithTag("reader-viewport-1").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                compose
                    .onNodeWithTag("reader-viewport-1")
                    .fetchSemanticsNode()
                    .config[PdfViewTransformKey]
                    .first() > 1.4f
            )
        } finally {
            safeShell(
                if (services == "null") "settings delete secure enabled_accessibility_services"
                else "settings put secure enabled_accessibility_services $services"
            )
            safeShell("settings put secure accessibility_enabled $enabled")
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            window.overrideWindowLayoutInfo(WindowLayoutInfo(emptyList()))
            compose.runOnIdle { model.closeDocument() }
            runBlocking { AccessibilityFixtures.remove(app, doc) }
        }
    }
}
