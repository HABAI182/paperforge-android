package cn.paperforge.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.*
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.model.ScanExportOptions
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real TalkBack service + native accessibility focus and click actions; no Compose selectors. */
class TalkBackWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use {
            it.readBytes().toString(Charsets.UTF_8).trim()
        }

    private fun setting(key: String) = shell("settings get secure $key")

    private fun restore(key: String, value: String) {
        shell(
            if (value == "null") "settings delete secure $key"
            else "settings put secure $key $value"
        )
    }

    private fun waitFor(label: String, timeout: Long = 15000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        ui.takeScreenshot()?.also { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), "issue8/timeout.png")
                .outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        error("Timed out: $label")
    }

    private fun walk(
        root: AccessibilityNodeInfo?,
        test: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        if (test(root)) return root
        for (i in 0 until root.childCount) walk(root.getChild(i), test)?.let {
            return it
        }
        return null
    }

    private fun appRoot() =
        ui.rootInActiveWindow?.takeIf { it.packageName?.toString() == "cn.paperforge.app" }
            ?: ui.windows
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() == "cn.paperforge.app" }
            ?: ui.rootInActiveWindow

    private fun node(label: String) =
        walk(appRoot()) {
            it.text?.toString() == label ||
                it.contentDescription?.toString() == label ||
                it.viewIdResourceName == label
        }

    private fun caption(): String =
        ui.windows
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() == "com.android.talkback" }
            .joinToString(" ") { root ->
                val words = mutableListOf<String>()
                fun texts(n: AccessibilityNodeInfo) {
                    n.text?.toString()?.let(words::add)
                    for (i in 0 until n.childCount) n.getChild(i)?.let(::texts)
                }
                texts(root)
                words.joinToString(" ")
            }

    private val spoken = linkedSetOf<String>()
    private val actions = JSONArray()

    private fun click(label: String) {
        var found: AccessibilityNodeInfo? = null
        waitFor(label) {
            found = node(label)
            if (found?.isVisibleToUser == true) return@waitFor true
            val scroll = walk(appRoot()) { it.isScrollable && it.isVisibleToUser }
            scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            false
        }
        var target = found!!
        repeat(6) { if (!target.isClickable) target = target.parent ?: target }
        // Let TalkBack finish processing a just-opened window before requesting its node.
        Thread.sleep(600)
        assertTrue(
            "Focus $label",
            target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS),
        )
        // Record only the expected fixture/control label, never arbitrary document names.
        val speechDeadline = SystemClock.uptimeMillis() + 4000
        var refocused = false
        while (SystemClock.uptimeMillis() < speechDeadline) {
            if (caption().contains(label)) {
                spoken += label
                break
            }
            if (!refocused && SystemClock.uptimeMillis() > speechDeadline - 2000) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
                target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                refocused = true
            }
            Thread.sleep(40)
        }
        // Invoke the same node action TalkBack uses for activation. Instrumentation touch
        // injection is routed differently by emulator input filters; do not claim gesture QA.
        assertTrue("Activate $label", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        android.util.Log.i(
            "PaperForgeTalkBackTest",
            "Activated $label; speech=${spoken.contains(label)}",
        )
        actions.put(label)
        Thread.sleep(200)
    }

    @Test
    fun sharedImageCropOcrPdfReaderTrashAndSettingsWithTalkBack() {
        val app = instrumentation.targetContext.applicationContext as PaperForgeApp
        // Android package visibility hides unrelated apps from the production context.
        val talkback =
            Regex("versionName=([^\\s]+)")
                .find(shell("dumpsys package com.android.talkback"))
                ?.groupValues
                ?.get(1)
                .orEmpty()
        assertTrue(
            "Install upstream TalkBack 16.2 test build",
            talkback.startsWith("TfPu_release_16_2"),
        )
        val services = setting("enabled_accessibility_services")
        val enabled = setting("accessibility_enabled")
        val export = ScanExportOptions.current()
        val proof = File(app.getExternalFilesDir(null), "issue8").apply { mkdirs() }
        val directory =
            File(app.cacheDir, "share/accessibility-${UUID.randomUUID()}").apply { mkdirs() }
        val image = File(directory, "Accessibility-input.png")
        Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888).also { b ->
            Canvas(b).apply {
                drawColor(Color.WHITE)
                drawText(
                    "PAPERFORGE OCR 012345",
                    70f,
                    220f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 52f
                        typeface = Typeface.DEFAULT_BOLD
                    },
                )
            }
            image.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", image)
        val intent =
            Intent(app, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        var source: cn.paperforge.app.data.DocumentRow? = null
        var resultDoc: cn.paperforge.app.data.DocumentRow? = null
        var model: MainViewModel? = null
        val component = "com.android.talkback/com.google.android.marvin.talkback.TalkBackService"
        try {
            val info = ui.serviceInfo
            info.flags =
                info.flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            ui.serviceInfo = info
            shell(
                "settings put secure enabled_accessibility_services ${if(services=="null" || services.isBlank()) component else "$services:$component"}"
            )
            shell("settings put secure accessibility_enabled 1")
            waitFor("TalkBack touch exploration") {
                (app.getSystemService(android.view.accessibility.AccessibilityManager::class.java))
                    .isTouchExplorationEnabled
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java] }
                waitFor("shared image import", 30000) {
                    model?.selected?.value?.kind == "scan" &&
                        model?.busy?.value == false &&
                        model?.pages?.value?.size == 1
                }
                source = model!!.selected.value!!
                click("裁切")
                waitFor("accessible crop position") {
                    if (node("crop-x") == null) {
                        walk(appRoot()) { it.isScrollable && it.isVisibleToUser }
                            ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        Thread.sleep(150)
                    }
                    node("crop-x") != null
                }
                val horizontal = node("crop-x")!!
                horizontal.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                )
                assertTrue(
                    horizontal.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                )
                assertTrue(
                    horizontal.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        Bundle().apply {
                            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, .05f)
                        },
                    )
                )
                click("应用")
                waitFor("crop committed") { model?.busy?.value == false && node("识字") != null }
                click("识字")
                waitFor("offline OCR", 60000) {
                    model?.ocr?.value?.contains("012345") == true && model?.busy?.value == false
                }
                click("关闭")
                click("导出")
                click("生成 PDF")
                waitFor("scan PDF export", 60000) {
                    model?.output?.value?.extension == "pdf" && model?.busy?.value == false
                }
                click("预览")
                waitFor("PDF reader") { node("视图") != null }
                resultDoc = model!!.selected.value
                click("视图")
                click("连续滚动")
                click("完成")
                click("返回")
                runBlocking { app.repository.trash(source!!) }
                click("回收站")
                click(source!!.title)
                waitFor("trash restore") {
                    runBlocking { app.repository.dao.document(source!!.id)?.trashedAt ?: 0L } == 0L
                }
                ui.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                )
                click("设置")
                click("使用说明")
                waitFor("offline help") { node("搜索使用说明") != null }
                assertTrue(
                    "Actual TalkBack speech overlay did not report expected controls",
                    spoken.contains("裁切") && spoken.contains("视图") && spoken.contains("设置"),
                )
                proof
                    .resolve("talkback.json")
                    .writeText(
                        JSONObject()
                            .put("screenReader", talkback)
                            .put("ttsEngine", setting("tts_default_synth"))
                            .put("actualTouchExploration", true)
                            .put("nativeAccessibilityActivation", true)
                            .put("physicalGestureValidation", false)
                            .put("actions", actions)
                            .put("spokenControlLabels", JSONArray(spoken.toList()))
                            .put("ocrContainsDigits", true)
                            .put("pdfGenerated", true)
                            .put("trashRestored", true)
                            .toString(2)
                    )
                ui.takeScreenshot().also { bitmap ->
                    proof.resolve("talkback-help.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
        } catch (failure: Throwable) {
            ui.takeScreenshot()?.also { bitmap ->
                proof.resolve("failure.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            throw failure
        } finally {
            restore("enabled_accessibility_services", services)
            restore("accessibility_enabled", enabled)
            export.remember()
            runBlocking {
                source?.let { AccessibilityFixtures.remove(app, it) }
                resultDoc
                    ?.takeIf { it.id != source?.id }
                    ?.let { AccessibilityFixtures.remove(app, it) }
            }
            image.delete()
            directory.delete()
        }
    }
}
