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
            val leftEdge = requireNotNull(detection.seamLeft) { "缺少缝左缘" }
            val rightEdge = requireNotNull(detection.seamRight) { "缺少缝右缘" }
            // Inner-edge trim: each half ends on its own side of the gutter instead of sharing one
            // center line. The stored edge is inset into the page by the render's CropSafety ring
            // (1% of the shorter source side) because the render grows the quad back out by it.
            assertEquals("左页内侧应在缝左缘附近", leftEdge.top, leftQuad.points[1].x, .01f)
            assertEquals("左页内侧应在缝左缘附近", leftEdge.bottom, leftQuad.points[2].x, .01f)
            assertEquals("右页内侧应在缝右缘附近", rightEdge.top, rightQuad.points[0].x, .01f)
            assertEquals("右页内侧应在缝右缘附近", rightEdge.bottom, rightQuad.points[3].x, .01f)
            assertTrue(
                "两页内侧不应共享缝中心线: ${leftQuad.points[1].x} / ${rightQuad.points[0].x}",
                rightQuad.points[0].x > leftQuad.points[1].x,
            )
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

                // Inner-edge check: the left page's inner edge is its right border, and the right
                // page's inner edge is its left border. Column projection over the middle rows of
                // the rendered page; a column counts as dark when its mean luma is at least
                // [InnerDarkDelta] below the page's paper level. Only the dark run closest to the
                // inner edge inside the inner 10% of the page is measured, so text columns away
                // from the gutter do not count and a leftover paper column from the render's
                // safety ring does not hide the band.
                val leftMeans = columnMeans(leftImage)
                val rightMeans = columnMeans(rightImage)
                val (leftGap, leftDark) = innerDarkBand(leftMeans, fromRight = true)
                val (rightGap, rightDark) = innerDarkBand(rightMeans, fromRight = false)
                val leftPaper = paperLevel(leftMeans)
                val rightPaper = paperLevel(rightMeans)
                val innerEdge =
                    JSONObject()
                        .put(
                            "leftPage",
                            JSONObject()
                                .put("innerEdge", "right")
                                .put("darkColumns", leftDark)
                                .put("gapFromEdge", leftGap)
                                .put("paperLuma", leftPaper.toDouble())
                                .put("darkThresholdLuma", (leftPaper - InnerDarkDelta).toDouble())
                                .put(
                                    "innerColumnMeans",
                                    JSONArray(leftMeans.takeLast(8).map { it.toDouble() }),
                                ),
                        )
                        .put(
                            "rightPage",
                            JSONObject()
                                .put("innerEdge", "left")
                                .put("darkColumns", rightDark)
                                .put("gapFromEdge", rightGap)
                                .put("paperLuma", rightPaper.toDouble())
                                .put("darkThresholdLuma", (rightPaper - InnerDarkDelta).toDouble())
                                .put(
                                    "innerColumnMeans",
                                    JSONArray(rightMeans.take(8).map { it.toDouble() }),
                                ),
                        )

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
                                    "seamLeft",
                                    JSONObject()
                                        .put("top", leftEdge.top.toDouble())
                                        .put("bottom", leftEdge.bottom.toDouble()),
                                )
                                .put(
                                    "seamRight",
                                    JSONObject()
                                        .put("top", rightEdge.top.toDouble())
                                        .put("bottom", rightEdge.bottom.toDouble()),
                                )
                                .put(
                                    "evidence",
                                    JSONObject(detection.evidence.mapValues { it.value.toDouble() }),
                                ),
                        )
                        .put("pagesBefore", 1)
                        .put("pagesAfter", after.size)
                        .put("pages", pagesJson)
                        .put("rendered", rendered)
                        .put("innerEdgeDarkBand", innerEdge)
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
                                .put(
                                    "innerEdgeTrimmed",
                                    rightQuad.points[0].x > leftQuad.points[1].x,
                                )
                                .put("undoRestoredOnePage", restored.size == 1),
                        )
                File(evidence, "result.json").writeText(result.toString(2))
                // After the inner-edge trim each page must end on its own side of the gutter; the
                // rendered page may keep at most a 2 px dark fringe (warp/JPEG edge softening).
                val leftGapText = if (leftGap < 0) "内 10% 无暗带" else "距内边 ${leftGap}px"
                val rightGapText = if (rightGap < 0) "内 10% 无暗带" else "距内边 ${rightGap}px"
                assertTrue("左页内边暗带 ${leftDark}px（$leftGapText，目标 ≤2）", leftDark <= 2)
                assertTrue("右页内边暗带 ${rightDark}px（$rightGapText，目标 ≤2）", rightDark <= 2)
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

    /** Mean luma of every column over rows 15%–85%, so a full-height gutter dominates text rows. */
    private fun columnMeans(page: Bitmap): FloatArray {
        val width = page.width
        val height = page.height
        val from = (height * .15f).toInt()
        val to = (height * .85f).toInt()
        val means = FloatArray(width)
        val row = IntArray(width)
        for (y in from until to) {
            page.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val pixel = row[x]
                val red = pixel shr 16 and 0xff
                val green = pixel shr 8 and 0xff
                val blue = pixel and 0xff
                means[x] += (red * 299 + green * 587 + blue * 114) / 1000f
            }
        }
        val rows = (to - from).coerceAtLeast(1).toFloat()
        for (x in 0 until width) means[x] /= rows
        return means
    }

    /** Paper level = 90th percentile of the column means; ink and gutter columns sit far below. */
    private fun paperLevel(means: FloatArray): Float {
        val sorted = means.sorted()
        return sorted[(sorted.size * .9f).toInt().coerceIn(0, sorted.lastIndex)]
    }

    /**
     * Nearest dark run to the inner edge, scanning at most the inner 10% of the page. Returns
     * (paper columns before the run, dark columns in the run); (-1, 0) when the inner 10% is paper
     * only, i.e. there is no inner dark band at all.
     */
    private fun innerDarkBand(means: FloatArray, fromRight: Boolean): Pair<Int, Int> {
        val threshold = paperLevel(means) - InnerDarkDelta
        val window = (means.size * .10f).toInt().coerceAtLeast(8)
        val start = if (fromRight) means.lastIndex else 0
        val stop = if (fromRight) means.lastIndex - window else window
        val step = if (fromRight) -1 else 1
        var gap = 0
        var dark = 0
        var inBand = false
        var index = start
        while (index in means.indices && (if (fromRight) index >= stop else index <= stop)) {
            if (means[index] < threshold) {
                inBand = true
                dark++
            } else if (inBand) {
                break
            } else {
                gap++
            }
            index += step
        }
        return (if (dark == 0) -1 else gap) to dark
    }

    private companion object {
        /** A column is "inner dark band" when it is this many luma levels below the paper level. */
        const val InnerDarkDelta = 40f
    }
}
