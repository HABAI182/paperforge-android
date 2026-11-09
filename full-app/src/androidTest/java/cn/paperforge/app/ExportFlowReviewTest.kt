package cn.paperforge.app

import android.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.UUID
import kotlin.math.*
import kotlin.random.Random
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExportFlowReviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm
        get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    private fun screenshot(name: String) {
        val folder = File(vm.repo.context.filesDir, "export-speed/ui").apply { mkdirs() }
        val bitmap =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .takeScreenshot() ?: return
        try {
            File(folder, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun curvatureEnvelopeMatchesReferenceSearch() {
        val random = Random(52)
        for (bend in listOf(0f, .001f, .025f, .3f)) repeat(600) {
            val count = 13
            val input =
                FloatArray(count) {
                    if (random.nextInt(4) == 0) -1e9f else random.nextFloat() * 30 - 15
                }
            val output = FloatArray(count)
            val parents = IntArray(count)
            RuledPageRefinement.velocityEnvelope(input, 0, count, bend, output, parents)
            for (q in 0 until count) {
                val expected = (0 until count).maxOf { p -> input[p] - bend * (p - q) * (p - q) }
                assertEquals(expected, output[q], .0001f)
                assertTrue(parents[q] in input.indices)
            }
        }
    }

    @Test
    fun exportNameAndBackgroundProgressSurvivePendingOptionalAi() =
        runBlocking<Unit> {
            val repo = vm.repo
            val settings = UserSettings.exportJson()
            compose.runOnIdle { UserSettings.set(SettingKeys.ScanAiFinish, true) }
            val doc = repo.newScan().copy(title = "保留原稿名称", listed = true)
            repo.dao.putDocument(doc)
            val file = repo.captureFile(doc.id)
            Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888).also { b ->
                b.eraseColor(Color.WHITE)
                Canvas(b)
                    .drawText(
                        "Notes",
                        30f,
                        60f,
                        Paint().apply {
                            color = Color.BLACK
                            textSize = 22f
                        },
                    )
                file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            val page =
                PageRow(UUID.randomUUID().toString(), doc.id, 0, file.path, filter = "ORIGINAL")
            repo.dao.putPage(page)
            val capture =
                LocalTaskRow(
                    UUID.randomUUID().toString(),
                    "CAPTURE",
                    doc.id,
                    doc.title,
                    page.id,
                    null,
                    0,
                    LocalTaskState.RUNNING,
                    progress = 15,
                    detail = "纸边准备",
                    payload =
                        CaptureSpec(CapturePreset.current().copy(aiFinish = true), null)
                            .encode()
                            .toString(),
                    resources =
                        DocumentResources.encode(
                                listOf(PageResource("raw", "capture-original", file.path))
                            )
                            .toString(),
                )
            repo.dao.putTask(capture)
            try {
                compose.runOnIdle { vm.open(doc) }
                compose.waitForIdle()
                compose.onNodeWithContentDescription("导出 PDF").performClick()
                compose
                    .onNodeWithText("文件名", substring = false)
                    .assertIsDisplayed()
                    .performTextReplacement("数学课堂.pdf")
                screenshot("export-filename.png")
                compose.onNodeWithText("生成 PDF", substring = false).performClick()
                compose.waitUntil(15000) { vm.canBackgroundTask.value }
                compose.onNodeWithText("后台", substring = false).performClick()
                compose.runOnIdle {
                    assertFalse(vm.busy.value)
                    assertTrue(
                        repo.context
                            .getSharedPreferences("session", 0)
                            .getBoolean("exportBackground", false)
                    )
                    vm.screen.value = "home"
                }
                compose.waitForIdle()
                compose.onNodeWithContentDescription("任务中心").performClick()
                compose.waitUntil(5000) {
                    compose.onAllNodesWithText("查看进度").fetchSemanticsNodes().isNotEmpty()
                }
                screenshot("task-center.png")
                compose.onAllNodesWithText("查看进度")[0].performClick()
                compose.waitUntil(5000) { vm.busy.value }
                // Framing is ready, but optional AI remains running indefinitely.
                repo.dao.taskState(capture.id, LocalTaskState.RUNNING, 60, "可导出，AI 精修中", null)
                val started = System.currentTimeMillis()
                withTimeout(15000) { while (vm.output.value == null) delay(50) }
                assertEquals("数学课堂.pdf", vm.output.value!!.name)
                assertEquals(
                    "%PDF-",
                    vm.output.value!!.inputStream().use { String(it.readNBytes(5)) },
                )
                assertEquals(LocalTaskState.RUNNING, repo.dao.task(capture.id)!!.state)
                assertEquals(doc.title, repo.dao.document(doc.id)!!.title)
                val folder = File(repo.context.filesDir, "export-speed").apply { mkdirs() }
                File(folder, "export-metrics.json")
                    .writeText(
                        JSONObject()
                            .put("millisecondsAfterFraming", System.currentTimeMillis() - started)
                            .put("filename", vm.output.value!!.name)
                            .put("aiStillRunning", true)
                            .toString(2)
                    )
            } finally {
                compose.runOnIdle {
                    vm.output.value = null
                    vm.closeDocument()
                    UserSettings.importJson(settings)
                }
                repo.deleteDocument(doc)
            }
        }

    @Test
    fun watermarkPreviewsUseActualCleanedPage() =
        runBlocking<Unit> {
            val folder = File(vm.repo.context.cacheDir, "watermark-test").apply { mkdirs() }
            val file = File(folder, "source.pdf")
            PDDocument().use { pdf ->
                val page = PDPage(PDRectangle(300f, 420f))
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText()
                    out.setFont(PDType1Font.HELVETICA, 24f)
                    out.newLineAtOffset(40f, 210f)
                    out.showText("BODY")
                    out.endText()
                    out.beginText()
                    out.setFont(PDType1Font.HELVETICA, 24f)
                    out.newLineAtOffset(40f, 40f)
                    out.showText("DRAFT")
                    out.endText()
                }
                pdf.save(file)
            }
            val original = WatermarkPreviews.original(vm.repo.context, file, 0)
            val after =
                WatermarkPreviews.after(
                    vm.repo.context,
                    file,
                    0,
                    JSONObject().put("mode", "TEXT").put("text", "DRAFT"),
                    original.bitmap,
                )
            try {
                assertEquals(1, after.removed)
                assertEquals(original.bitmap.width, after.bitmap.width)
                var changed = 0
                for (y in 0 until after.bitmap.height) for (x in 0 until after.bitmap.width) if (
                    original.bitmap.getPixel(x, y) != after.bitmap.getPixel(x, y)
                )
                    changed++
                assertTrue("Real watermark removal must be visible", changed > 150)
                compose.runOnIdle {
                    vm.watermarkInput.value = android.net.Uri.fromFile(file)
                    vm.screen.value = "document-watermarks"
                }
                compose.waitUntil(10000) {
                    compose
                        .onAllNodesWithContentDescription("原图效果")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithText("指定文字").performScrollTo().performClick()
                compose.onNodeWithText("要移除的完整水印文字").performTextReplacement("DRAFT")
                compose.onNodeWithText("原图", substring = false).performScrollTo().performClick()
                compose.onNodeWithContentDescription("原图效果").performScrollTo().assertIsDisplayed()
                screenshot("watermark-original.png")
                compose.onNodeWithText("处理后", substring = false).performScrollTo().performClick()
                compose.waitUntil(10000) {
                    compose.onAllNodesWithText("当前页已清理 1 项水印").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("处理后效果").performScrollTo().assertIsDisplayed()
                screenshot("watermark-cleaned.png")
                compose.runOnIdle {
                    vm.screen.value = "home"
                    vm.homeTab.value = 3
                }
                compose.waitForIdle()
                compose
                    .onNodeWithText("PaperForge体验交流", substring = false)
                    .performScrollTo()
                    .performClick()
                compose.onNodeWithText("QQ群聊：980694348").assertIsDisplayed()
                screenshot("community.png")
                compose.onNodeWithText("关闭", substring = false).performClick()
            } finally {
                original.bitmap.recycle()
                after.bitmap.recycle()
                file.delete()
            }
        }

    @Test
    fun sixPageFastFinishBenchmark() =
        runBlocking<Unit> {
            val source = File(vm.repo.context.filesDir, "flat-feedback-source/latest.jpg")
            assertTrue(source.isFile)
            val page =
                PageRow(
                    "bench",
                    "bench",
                    0,
                    source.path,
                    filter = "ORIGINAL",
                    corners =
                        Quad(
                                listOf(
                                    PointN(.214f, .212f),
                                    PointN(.836f, .230f),
                                    PointN(.871f, .882f),
                                    PointN(.104f, .878f),
                                )
                            )
                            .encode(),
                )
            val raw = ScanProcessor.render(page, 2000)
            val folder = File(vm.repo.context.filesDir, "export-speed").apply { mkdirs() }
            val times = mutableListOf<Long>()
            val stages = org.json.JSONArray()
            try {
                repeat(6) { index ->
                    val t = System.currentTimeMillis()
                    val marks = JSONObject()
                    val result =
                        FlatDocument.process(
                            vm.repo.context,
                            raw,
                            ai = false,
                            progress = { p ->
                                marks.put(p.toString(), System.currentTimeMillis() - t)
                            },
                        )
                    stages.put(marks)
                    try {
                        times += System.currentTimeMillis() - t
                        assertTrue(result.lines >= 8)
                        if (index == 0) {
                            val reference =
                                androidx.test.platform.app.InstrumentationRegistry.getArguments()
                                    .getString("pixelReference")
                            if (reference != null) {
                                val bitmap =
                                    BitmapFactory.decodeFile(
                                        File(vm.repo.context.filesDir, reference).path
                                    )
                                try {
                                    assertTrue(
                                        "Native cleanup must preserve the reviewed pixels",
                                        result.bitmap.sameAs(bitmap),
                                    )
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }
                        if (index == 0)
                            File(folder, "fast-flat.png").outputStream().use {
                                result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                    } finally {
                        result.bitmap.recycle()
                    }
                }
                File(folder, "fast-metrics.json")
                    .writeText(
                        JSONObject()
                            .put("algorithm", RuledPageRefinement.VERSION)
                            .put("pageMilliseconds", org.json.JSONArray(times))
                            .put("stages", stages)
                            .put("totalMilliseconds", times.sum())
                            .put("ai", false)
                            .put("edge", 2000)
                            .toString(2)
                    )
                assertTrue("Six fast finishes should not take minutes", times.sum() < 20000)
            } finally {
                raw.recycle()
            }
        }
}
