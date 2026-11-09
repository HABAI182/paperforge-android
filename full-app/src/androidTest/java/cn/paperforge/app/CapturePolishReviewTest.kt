package cn.paperforge.app

import android.Manifest
import android.graphics.*
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.*
import cn.paperforge.app.ui.*
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.opencv.core.*

class CapturePolishReviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm
        get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    private val folder
        get() = File(vm.repo.context.filesDir, "capture-polish").apply { mkdirs() }

    private fun shot(name: String) {
        val b =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .takeScreenshot() ?: return
        try {
            File(folder, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            b.recycle()
        }
    }

    private fun save(b: Bitmap, name: String) {
        File(folder, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun resultActionsStaySymmetricAndTextFits() {
        compose.activity.setContent {
            PaperForgeTheme {
                val density = LocalDensity.current
                // Fix the effective scale after the app's user multiplier, not before it.
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    OutputResultDialog(
                        "PDF 已生成",
                        "概率论 Week 7 课堂笔记.pdf",
                        "1.6 MB",
                        "",
                        null,
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        null,
                    )
                }
            }
        }
        compose.waitForIdle()
        val names = listOf("分享文件", "保存到手机", "预览文件", "用其他应用打开")
        val bounds =
            names.map {
                compose
                    .onNodeWithContentDescription(it)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            }
        for (b in bounds) {
            assertEquals(bounds[0].width, b.width, 1.1f)
            assertEquals(bounds[0].height, b.height, 1.1f)
        }
        assertEquals(bounds[0].top, bounds[1].top, 1f)
        assertEquals(bounds[2].top, bounds[3].top, 1f)
        for (label in listOf("分享", "保存", "预览", "打开")) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(
                SemanticsActions.GetTextLayoutResult
            ) {
                it(layouts)
            }
            assertEquals(1, layouts.single().lineCount)
        }
        compose.onNodeWithText("保存位置", substring = false).assertIsDisplayed()
        compose.onNodeWithText("保存时选择手机文件夹").assertIsDisplayed()
        shot("result-130-percent.png")
    }

    @Test
    fun twoCameraSavesHavePersistentAcknowledgement() =
        runBlocking<Unit> {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .grantRuntimePermission(vm.repo.context.packageName, Manifest.permission.CAMERA)
            val prefs = UserSettings.exportJson()
            compose.runOnIdle {
                UserSettings.set(SettingKeys.AutoShutter, false)
                UserSettings.set(SettingKeys.ReviewAfterCapture, false)
                UserSettings.set(SettingKeys.CaptureTimer, 0f)
                vm.newScan()
            }
            try {
                compose.waitUntil(15000) {
                    vm.selected.value != null && vm.screen.value == "capture" && !vm.busy.value
                }
                compose.waitUntil(15000) {
                    compose.onAllNodesWithContentDescription("拍照").fetchSemanticsNodes().any {
                        !it.config.contains(SemanticsProperties.Disabled)
                    }
                }
                compose.onNodeWithContentDescription("拍照").performClick()
                compose.waitUntil(15000) {
                    compose
                        .onAllNodes(
                            SemanticsMatcher.expectValue(
                                SemanticsProperties.StateDescription,
                                "本次已拍下 1 张",
                            )
                        )
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                shot("capture-saved-1.png")
                compose.onNodeWithContentDescription("拍照").performClick()
                compose.waitUntil(15000) {
                    compose
                        .onAllNodes(
                            SemanticsMatcher.expectValue(
                                SemanticsProperties.StateDescription,
                                "本次已拍下 2 张",
                            )
                        )
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                assertEquals(2, vm.pages.value.size)
                val saved = compose.onNodeWithText("已拍下第 2 页").fetchSemanticsNode().boundsInRoot
                val toggle =
                    compose
                        .onNodeWithText("多页", substring = false)
                        .fetchSemanticsNode()
                        .boundsInRoot
                assertTrue(
                    "Saved feedback must stay above the batch toggle",
                    saved.bottom < toggle.top,
                )
                shot("capture-saved-2.png")
            } finally {
                val doc = vm.selected.value
                compose.runOnIdle {
                    vm.closeDocument()
                    UserSettings.importJson(prefs)
                }
                if (doc != null) vm.repo.deleteDocument(doc)
            }
        }

    @Test
    fun ruledPagesAvoidInventedDentAndFlattenSupportedFold() {
        fun page(bent: Boolean): Bitmap {
            val b = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(Color.WHITE)
            val p =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.DKGRAY
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
            for (y in 85..1141 step 44) {
                val path = android.graphics.Path()
                for (x in 0 until b.width) {
                    val u = x.toFloat() / (b.width - 1)
                    val d =
                        if (bent) 18 * sin(PI * u).toFloat() + 8 * exp(-((u - .58f) / .045f).pow(2))
                        else 0f
                    if (x == 0) path.moveTo(x.toFloat(), y + d) else path.lineTo(x.toFloat(), y + d)
                }
                c.drawPath(path, p)
            }
            return b
        }
        fun spread(b: Bitmap): Double {
            val ys =
                (80..820 step 8).map { x ->
                    (520..565).minBy { y -> Color.red(b.getPixel(x, y)) }.toDouble()
                }
            val m = ys.average()
            return sqrt(ys.map { (it - m).pow(2) }.average())
        }
        val straight = page(false)
        val plain = FlatDocument.geometry(straight)
        try {
            assertTrue(plain.algorithm.contains("identity"))
            assertTrue(straight.sameAs(plain.bitmap))
        } finally {
            straight.recycle()
            plain.bitmap.recycle()
        }
        val bent = page(true)
        val result = FlatDocument.geometry(bent)
        try {
            val before = spread(bent)
            val after = spread(result.bitmap)
            save(bent, "known-fold-before.png")
            save(result.bitmap, "known-fold-after.png")
            File(folder, "geometry-metrics.json")
                .writeText(
                    JSONObject()
                        .put("beforeRms", before)
                        .put("afterRms", after)
                        .put("lines", result.lines)
                        .put("algorithm", result.algorithm)
                        .toString(2)
                )
            assertTrue(result.lines >= 12)
            assertTrue("Known fold must be flattened, without a center notch", after < before * .45)
        } finally {
            bent.recycle()
            result.bitmap.recycle()
        }
    }

    @Test
    fun ownerFastOutputAndActualInkEvidence() =
        runBlocking<Unit> {
            val source = File(vm.repo.context.filesDir, "flat-feedback-source/latest.jpg")
            assertTrue(source.isFile)
            val page =
                PageRow(
                    "p",
                    "d",
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
            val ablationKey =
                androidx.test.platform.app.InstrumentationRegistry.getArguments()
                    .getString("ablationKey") ?: "learnedInk"
            val configuration = ScanConfig.replaceForBenchmark(JSONObject().put(ablationKey, 0))
            try {
                val times = JSONArray()
                for ((name, enabled) in
                    listOf("owner-classic.png" to 0, "owner-learned.png" to 1)) {
                    ScanConfig.replaceForBenchmark(JSONObject().put(ablationKey, enabled))
                    val started = System.currentTimeMillis()
                    val result = FlatDocument.process(vm.repo.context, raw, ai = false)
                    try {
                        save(result.bitmap, name)
                        result.grid.write(File(folder, "owner.pfgrid.gz").also { it.delete() })
                        times.put(
                            JSONObject()
                                .put("file", name)
                                .put("ablationKey", ablationKey)
                                .put("milliseconds", System.currentTimeMillis() - started)
                                .put("lines", result.lines)
                                .put("algorithm", result.algorithm)
                        )
                        assertTrue(result.lines >= 8)
                    } finally {
                        result.bitmap.recycle()
                    }
                }
                File(folder, "owner-metrics.json").writeText(times.toString(2))
                val shape = FlatDocument.geometry(raw)
                try {
                    save(shape.bitmap, "owner-geometry.png")
                } finally {
                    shape.bitmap.recycle()
                }
            } finally {
                ScanConfig.restoreAfterBenchmark(configuration)
                raw.recycle()
            }
        }

    @Test
    fun ownerAiComparisonUsesSameSourceAndGeometry() =
        runBlocking<Unit> {
            val source = File(vm.repo.context.filesDir, "flat-feedback-source/latest.jpg")
            val page =
                PageRow(
                    "p",
                    "d",
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
            val ablationKey =
                androidx.test.platform.app.InstrumentationRegistry.getArguments()
                    .getString("ablationKey") ?: "learnedInk"
            val configuration = ScanConfig.replaceForBenchmark(JSONObject().put(ablationKey, 0))
            try {
                val times = JSONArray()
                for ((name, enabled) in
                    listOf("owner-ai-before.png" to 0, "owner-ai-learned.png" to 1)) {
                    ScanConfig.replaceForBenchmark(JSONObject().put(ablationKey, enabled))
                    val t = System.currentTimeMillis()
                    val result = FlatDocument.process(vm.repo.context, raw, ai = true)
                    try {
                        save(result.bitmap, name)
                        times.put(
                            JSONObject()
                                .put("file", name)
                                .put("ablationKey", ablationKey)
                                .put("milliseconds", System.currentTimeMillis() - t)
                                .put("lines", result.lines)
                        )
                        assertTrue(result.lines >= 8)
                    } finally {
                        result.bitmap.recycle()
                    }
                }
                File(folder, "owner-ai-metrics.json").writeText(times.toString(2))
            } finally {
                ScanConfig.restoreAfterBenchmark(configuration)
                raw.recycle()
            }
        }

    @Test
    fun faintRulesWithSmallGapsKeepCoherentEvidence() {
        val ink = Mat(800, 600, CvType.CV_8UC1, Scalar(0.0))
        val rules = Mat()
        try {
            for (y in 70..730 step 44) for (x in 0 until 600) if (x % 97 >= 4)
                ink.put(y, x, byteArrayOf(20))
            EnhancePipeline.rulingEvidence(ink, rules)
            val output = ByteArray(600)
            rules.get(334, 0, output)
            val present = (30..569).filter { it % 97 >= 4 }
            assertTrue(
                "A few gaps must not erase a real faint rule",
                present.count { (output[it].toInt() and 255) >= 14 } > present.size * .9,
            )
        } finally {
            ink.release()
            rules.release()
        }
    }

    @Test
    fun faintResidueClearsWhileFrontInkAndRulesStay() {
        val w = 240
        val h = 180
        val input = ByteArray(w * h) { 255.toByte() }
        val rules = ByteArray(input.size)
        val matte = ByteArray(input.size)
        for (y in 20..45) for (x in 170..205) input[y * w + x] = 225.toByte()
        for (x in 0 until w) {
            input[90 * w + x] = 235.toByte()
            rules[90 * w + x] = 20
        }
        input[40 * w + 35] = 30
        input[40 * w + 37] = 238.toByte()
        for (x in 20..60) {
            input[130 * w + x] = 222.toByte()
            matte[130 * w + x] = 250.toByte()
        }
        val original = input.copyOf()
        InkComponents.cleanFaint(input, rules, matte, w, h, 1f)
        assertEquals(255, input[30 * w + 190].toInt() and 255)
        assertEquals(30, input[40 * w + 35].toInt() and 255)
        assertEquals(238, input[40 * w + 37].toInt() and 255)
        assertEquals(235, input[90 * w + 100].toInt() and 255)
        assertEquals(222, input[130 * w + 40].toInt() and 255)
        val off = original.copyOf()
        InkComponents.cleanFaint(off, rules, matte, w, h, 0f)
        assertArrayEquals(original, off)
    }

    @Test
    fun archiveCleanupOnExistingSevenCrops() {
        val root = File(vm.repo.context.filesDir, "feedback-scan/output")
        val output = File(folder, "archive").apply { mkdirs() }
        val report = JSONArray()
        val configuration = ScanConfig.replaceForBenchmark(JSONObject())
        try {
            for (name in listOf("new-1", "new-2", "new-3", "new-4", "old-1", "old-2", "old-3")) {
                val source = BitmapFactory.decodeFile(File(root, "$name-crop.png").path)
                val rgba = Mat()
                val rgb = Mat()
                val result = Mat()
                try {
                    org.opencv.android.Utils.bitmapToMat(source, rgba)
                    org.opencv.imgproc.Imgproc.cvtColor(
                        rgba,
                        rgb,
                        org.opencv.imgproc.Imgproc.COLOR_RGBA2RGB,
                    )
                    val images = mutableListOf<IntArray>()
                    val times = JSONArray()
                    for (enabled in 0..1) {
                        ScanConfig.replaceForBenchmark(
                            JSONObject().put("paperFaintCleanup", enabled)
                        )
                        val t = System.currentTimeMillis()
                        EnhancePipeline.apply(rgb, result, "WORKBOOK", .85f)
                        times.put(System.currentTimeMillis() - t)
                        val bitmap =
                            Bitmap.createBitmap(
                                source.width,
                                source.height,
                                Bitmap.Config.ARGB_8888,
                            )
                        try {
                            org.opencv.android.Utils.matToBitmap(result, bitmap)
                            File(output, "$name-$enabled.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            images +=
                                IntArray(source.width * source.height).also {
                                    bitmap.getPixels(
                                        it,
                                        0,
                                        source.width,
                                        0,
                                        0,
                                        source.width,
                                        source.height,
                                    )
                                }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    val before = images[0]
                    val after = images[1]
                    val dark = before.indices.count { Color.red(before[it]) < 150 }
                    val changedDark =
                        before.indices.count {
                            Color.red(before[it]) < 150 && before[it] != after[it]
                        }
                    assertEquals(
                        "Dark original strokes must not be erased by the faint stage",
                        0,
                        changedDark,
                    )
                    report.put(
                        JSONObject()
                            .put("id", name)
                            .put("darkPixels", dark)
                            .put("changedDarkPixels", changedDark)
                            .put("changedPixels", before.indices.count { before[it] != after[it] })
                            .put("pixels", before.size)
                            .put("milliseconds", times)
                    )
                } finally {
                    source.recycle()
                    rgba.release()
                    rgb.release()
                    result.release()
                }
            }
            File(output, "metrics.json").writeText(report.toString(2))
        } finally {
            ScanConfig.restoreAfterBenchmark(configuration)
        }
    }

    @Test
    fun full本地样本应用ThreePhotoSweep() =
        runBlocking<Unit> {
            val output = File(folder, "full-reference").apply { mkdirs() }
            val variants =
                listOf(
                    Triple("surface-off", 0.0, 0.0),
                    Triple("surface-85", .85, 1.0),
                    Triple("surface-full", 1.0, 1.0),
                )
            val saved = ScanConfig.replaceForBenchmark(JSONObject())
            val report = JSONArray()
            try {
                for (name in listOf("old-1", "old-2", "old-3")) {
                    val source =
                        BitmapFactory.decodeFile(
                            File(vm.repo.context.filesDir, "feedback-scan/output/$name-crop.png")
                                .path
                        )
                    try {
                        for ((id, low, range) in variants) {
                            ScanConfig.replaceForBenchmark(
                                JSONObject()
                                    .put("ruledSurfaceCoherence", low)
                                    .put("ruledMapDamping", range.toInt())
                            )
                            val t = System.currentTimeMillis()
                            val result = FlatDocument.process(vm.repo.context, source, ai = false)
                            val ms = System.currentTimeMillis() - t
                            try {
                                File(output, "$name-$id.png").outputStream().use {
                                    result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                                report.put(
                                    JSONObject()
                                        .put("photo", name)
                                        .put("variant", id)
                                        .put("milliseconds", ms)
                                        .put("lines", result.lines)
                                        .put("algorithm", result.algorithm)
                                        .put("coherence", low)
                                        .put("damping", range)
                                )
                            } finally {
                                result.bitmap.recycle()
                            }
                        }
                    } finally {
                        source.recycle()
                    }
                }
                File(output, "metrics.json").writeText(report.toString(2))
            } finally {
                ScanConfig.restoreAfterBenchmark(saved)
            }
        }

    @Test
    fun flatMathDoesNotAcquireLocalWaves() {
        val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val rule =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(135, 135, 135)
                strokeWidth = 2f
            }
        for (y in 85..1141 step 44) canvas.drawLine(0f, y.toFloat(), 899f, y.toFloat(), rule)
        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 27f
                strokeWidth = 2f
            }
        for (y in listOf(300, 344, 388)) {
            canvas.drawText("arctan x - sin x", 340f, y.toFloat(), text)
            canvas.drawLine(340f, y + 8f, 565f, y + 8f, text)
            canvas.drawText("x / (1 + x * x)", 350f, y + 35f, text)
        }
        try {
            val result = FlatDocument.geometry(bitmap)
            try {
                assertTrue(
                    "A flat math page must use identity geometry",
                    result.algorithm.contains("identity"),
                )
                assertTrue(
                    "Flat formulas must remain pixel-identical",
                    bitmap.sameAs(result.bitmap),
                )
            } finally {
                result.bitmap.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
