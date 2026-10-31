package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.Quad
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #23 device test: build a synthetic two-page spread on the device, enter the real editor menu
 * item「拆分双页 · 自动书脊」, and check page count, left-before-right order, the kept original and undo.
 * Everything is synthetic: no owner sample, no real photo. The result JSON and the generated image
 * are written to files/spread-issue23 and pulled into work/spread-issue23.
 */
@RunWith(AndroidJUnit4::class)
class SpreadSplitSampleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun editorMenuSplitsSyntheticSpreadInOrderAndKeepsTheOriginal() {
        val app = compose.activity.application as PaperForgeApp
        val repo = app.repository
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val evidence = File(app.filesDir, "spread-issue23").apply { mkdirs() }
        val doc = runBlocking { repo.newScan() }
        try {
            val file = File(repo.folder(doc.id), "spread-source.jpg")
            val image = syntheticSpread()
            val width = image.width
            val height = image.height
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 96, it) }
            image.recycle()
            file.copyTo(File(evidence, "spread-source.jpg"), overwrite = true)
            val sourceSha = sha256(file)
            val page =
                PageRow(UUID.randomUUID().toString(), doc.id, 0, file.path, filter = "ORIGINAL")
            runBlocking { repo.dao.putPage(page) }

            compose.runOnIdle { model.open(doc) }
            compose.waitUntil(20000) { model.pages.value.size == 1 }
            compose.waitForIdle()
            compose.onNodeWithText("更多", useUnmergedTree = true).performClick()
            compose
                .onNodeWithText("拆分双页 · 自动书脊", useUnmergedTree = true)
                .performScrollTo()
                .performClick()
            compose.waitUntil(30000) { !model.busy.value && model.pages.value.size == 2 }

            val detection = requireNotNull(model.lastSpineDetection.value) { "没有检测结果" }
            assertTrue("未检测到书脊: ${detection.reason}", detection.found)
            val detectedX = requireNotNull(detection.x)
            assertTrue("书脊 x=$detectedX", abs(detectedX - .46f) <= .02f)

            val after = runBlocking { repo.dao.pages(doc.id) }
            assertEquals("页数应 +1", 2, after.size)
            val left = after[0]
            val right = after[1]
            assertEquals(listOf(0, 1), after.map { it.position })
            assertEquals(left.original, right.original)
            assertEquals(page.original, left.original)
            assertTrue("原图文件不在了", File(left.original).exists())
            assertEquals("原图必须一字节不变", sourceSha, sha256(File(left.original)))
            val leftQuad = Quad.decode(left.corners)
            val rightQuad = Quad.decode(right.corners)
            assertEquals(detection.top, leftQuad.points[1].x, .0001f)
            assertEquals(detection.bottom, leftQuad.points[2].x, .0001f)
            assertEquals(leftQuad.points[1], rightQuad.points[0])
            assertEquals(leftQuad.points[2], rightQuad.points[3])
            val leftCenter = leftQuad.points.map { it.x }.average()
            val rightCenter = rightQuad.points.map { it.x }.average()
            assertTrue("左页应在右页前: $leftCenter / $rightCenter", leftCenter < rightCenter)
            assertTrue("书脊偏左时左页应更窄", leftQuad.area < rightQuad.area)

            val leftImage = ScanProcessor.render(left, 900)
            val rightImage = ScanProcessor.render(right, 900)
            try {
                File(evidence, "left.png").outputStream().use {
                    leftImage.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                File(evidence, "right.png").outputStream().use {
                    rightImage.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                assertTrue(
                    "左页渲染宽度 ${leftImage.width} 应小于右页 ${rightImage.width}",
                    leftImage.width < rightImage.width,
                )
                val share = leftImage.width.toFloat() / (leftImage.width + rightImage.width)
                assertEquals("渲染宽度比例应接近书脊位置", detectedX, share, .015f)

                val pagesJson =
                    JSONArray(
                        listOf(left, right).mapIndexed { index, row ->
                            val quad = Quad.decode(row.corners)
                            JSONObject()
                                .put("position", index)
                                .put("id", row.id)
                                .put("original", File(row.original).name)
                                .put("sha256", sha256(File(row.original)))
                                .put("area", quad.area.toDouble())
                                .put("centerX", quad.points.map { it.x }.average())
                                .put("topFraction", quad.points[1].x.toDouble())
                                .put("bottomFraction", quad.points[2].x.toDouble())
                        }
                    )
                val rendered =
                    JSONObject()
                        .put("leftWidth", leftImage.width)
                        .put("leftHeight", leftImage.height)
                        .put("rightWidth", rightImage.width)
                        .put("rightHeight", rightImage.height)
                        .put("leftShare", share.toDouble())

                runBlocking { repo.undo(doc.id) }
                val restored = runBlocking { repo.dao.pages(doc.id) }
                assertEquals("撤销后应回到 1 页", 1, restored.size)
                assertEquals("撤销后仍是同一原图", page.original, restored[0].original)
                assertEquals(sourceSha, sha256(File(restored[0].original)))
                assertTrue(File(restored[0].original).exists())

                val result =
                    JSONObject()
                        .put("issue", 23)
                        .put("synthetic", true)
                        .put(
                            "syntheticDescription",
                            "测试内 Canvas 绘制的跨页：左右两页等宽文字、46% 处暗带中缝与横向光照渐变，不是真实照片",
                        )
                        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        .put("sdk", Build.VERSION.SDK_INT)
                        .put("serial", "emulator-5554")
                        .put("entry", "编辑器 → 更多（扫描页面工具）→ 拆分双页 · 自动书脊")
                        .put(
                            "source",
                            JSONObject()
                                .put("file", "spread-source.jpg")
                                .put("width", width)
                                .put("height", height)
                                .put("sha256", sourceSha),
                        )
                        .put(
                            "detection",
                            JSONObject()
                                .put("found", detection.found)
                                .put("method", detection.method)
                                .put("x", detectedX.toDouble())
                                .put("top", detection.top.toDouble())
                                .put("bottom", detection.bottom.toDouble())
                                .put("confidence", detection.confidence.toDouble())
                                .put(
                                    "evidence",
                                    JSONObject(detection.evidence.mapValues { it.value.toDouble() }),
                                ),
                        )
                        .put("pagesBefore", 1)
                        .put("pagesAfter", after.size)
                        .put("pages", pagesJson)
                        .put("rendered", rendered)
                        .put(
                            "assertions",
                            JSONObject()
                                .put("pageCountPlusOne", after.size == 2)
                                .put("leftBeforeRight", leftCenter < rightCenter)
                                .put("originalFileKept", File(left.original).exists())
                                .put(
                                    "originalShaUnchanged",
                                    sha256(File(left.original)) == sourceSha,
                                )
                                .put("leftNarrowerThanRight", leftImage.width < rightImage.width)
                                .put("undoRestoredOnePage", restored.size == 1),
                        )
                File(evidence, "result.json").writeText(result.toString(2))
            } finally {
                leftImage.recycle()
                rightImage.recycle()
            }
        } finally {
            compose.runOnIdle { model.screen.value = "home" }
            runBlocking { repo.deleteDocument(doc) }
        }
    }

    /**
     * 1400×1000 spread: a dark 18 px gutter at 46% of the width, text on both pages, and a
     * left-to-right paper gradient so the detector cannot rely on one global threshold.
     */
    private fun syntheticSpread(width: Int = 1400, height: Int = 1000): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paper =
            Paint().apply {
                shader =
                    LinearGradient(
                        0f,
                        0f,
                        width.toFloat(),
                        0f,
                        Color.rgb(246, 246, 244),
                        Color.rgb(196, 196, 192),
                        Shader.TileMode.CLAMP,
                    )
            }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paper)
        val seamX = width * .46f
        canvas.drawRect(
            seamX - 9f,
            0f,
            seamX + 9f,
            height.toFloat(),
            Paint().apply { color = Color.rgb(64, 64, 64) },
        )
        val ink =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(35, 35, 35)
                textSize = 20f
                typeface = Typeface.MONOSPACE
            }
        for (row in 0 until 18) {
            val y = 120f + row * 46f
            canvas.drawText("PAPERFORGE SPREAD 0123", width * .07f, y, ink)
            canvas.drawText("RIGHT PAGE 4567", width * .58f, y, ink)
        }
        return bitmap
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
