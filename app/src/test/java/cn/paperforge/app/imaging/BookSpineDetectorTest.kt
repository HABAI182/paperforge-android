package cn.paperforge.app.imaging

import cn.paperforge.app.model.CropGeometry
import cn.paperforge.app.model.Quad
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #23 unit tests for the spine search. Everything here is a synthetic luma image built in the test
 * (no photos, no device), so the numbers are reproducible on the JVM. Thresholds and tolerances are
 * the ones documented in docs/acceptance/issues/23/README.md.
 */
class BookSpineDetectorTest {
    private class SyntheticImage(
        val width: Int = 1400,
        val height: Int = 1000,
        val seam: Float? = .5f,
        val tilt: Float = 0f,
        val seamWidth: Int = 14,
        val seamLuma: Int = 72,
        val paper: Int = 242,
        val ink: Int = 38,
        val lighting: Float = 0f,
        val text: Boolean = true,
        val centralGap: Boolean = false,
        val noise: Int = 3,
        val seed: Int = 7,
    ) {
        val luma: IntArray = build()

        private fun blocks(): List<IntRange> {
            val ranges = mutableListOf<IntRange>()
            when {
                seam != null -> {
                    ranges.add((width * .07f).roundToInt() until (width * .42f).roundToInt())
                    ranges.add((width * .58f).roundToInt() until (width * .93f).roundToInt())
                }
                centralGap -> {
                    ranges.add((width * .08f).roundToInt() until (width * .46f).roundToInt())
                    ranges.add((width * .54f).roundToInt() until (width * .92f).roundToInt())
                }
                else -> ranges.add((width * .06f).roundToInt() until (width * .94f).roundToInt())
            }
            return ranges
        }

        private fun isGlyph(x: Int, y: Int): Boolean {
            if (!text) return false
            val row = (y - (height * .12f).toInt()).mod(44)
            if (row >= 16) return false
            if (blocks().none { x in it }) return false
            return x.mod(13) < 8
        }

        private fun build(): IntArray {
            val random = Random(seed)
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val center =
                    seam?.let { (it + tilt * (y.toFloat() / (height - 1) - .5f)) * (width - 1) }
                for (x in 0 until width) {
                    val light = 1f - lighting * x / (width - 1f)
                    var value = paper * light
                    if (center != null && abs(x - center) <= seamWidth / 2f)
                        value = seamLuma.toFloat()
                    else if (isGlyph(x, y)) value = ink * light
                    pixels[y * width + x] =
                        (value.roundToInt() + random.nextInt(-noise, noise + 1)).coerceIn(0, 255)
                }
            }
            return pixels
        }
    }

    private fun detect(image: SyntheticImage) =
        BookSpineDetector.detect(image.luma, image.width, image.height)

    /** Keeps the measured numbers in the JUnit XML so the acceptance page is reproducible. */
    private fun report(label: String, result: SpineDetection) =
        println(
            "$label found=${result.found} method=${result.method} x=${result.x} " +
                "top=${result.top} bottom=${result.bottom} confidence=${result.confidence} " +
                "reason=${result.reason}"
        )

    @Test
    fun darkBandedSpreadFindsTheGutterWithinHalfAPercent() {
        val image = SyntheticImage(seam = .5f, seamLuma = 72)
        val result = detect(image).also { report("dark", it) }
        assertTrue("应为暗带书脊: ${result.reason}", result.found)
        assertEquals(BookSpineDetector.MethodDark, result.method)
        val x = requireNotNull(result.x)
        assertTrue("书脊 x=$x 偏差过大", abs(x - .5f) <= .005f)
        assertTrue("置信度 ${result.confidence}", result.confidence >= .6f)
        assertTrue(result.evidence.getValue("inkLeft") > 0f)
        assertTrue(result.evidence.getValue("inkRight") > 0f)
        assertTrue(result.top > .45f && result.top < .55f)
        assertTrue(result.bottom > .45f && result.bottom < .55f)
    }

    @Test
    fun whiteSeamBetweenSeparatedPagesIsDetected() {
        val image =
            SyntheticImage(seam = .5f, seamLuma = 253, seamWidth = 12, paper = 232, ink = 45)
        val result = detect(image).also { report("white", it) }
        assertTrue("应为白缝书脊: ${result.reason}", result.found)
        assertEquals(BookSpineDetector.MethodBright, result.method)
        val x = requireNotNull(result.x)
        assertTrue("白缝 x=$x 偏差过大", abs(x - .5f) <= .005f)
        assertTrue(result.confidence >= .55f)
    }

    @Test
    fun unevenLightingDoesNotMoveTheSeam() {
        val image = SyntheticImage(seam = .48f, seamLuma = 80, lighting = .22f)
        val result = detect(image).also { report("lighting", it) }
        assertTrue("光照不均下未检出: ${result.reason}", result.found)
        val x = requireNotNull(result.x)
        assertTrue("光照不均使 x=$x 偏移", abs(x - .48f) <= .008f)
    }

    @Test
    fun tiltedGutterKeepsDirectionAtTopAndBottom() {
        val image = SyntheticImage(seam = .5f, tilt = .02f, seamWidth = 16)
        val result = detect(image).also { report("tilt", it) }
        assertTrue("倾斜中缝未检出: ${result.reason}", result.found)
        assertTrue("上端 ${result.top} 应小于下端 ${result.bottom}", result.top < result.bottom)
        assertTrue("上下端点差 ${result.bottom - result.top} 太小", result.bottom - result.top > .005f)
        assertNotNull(result.x)
    }

    @Test
    fun singlePageWithoutGutterIsRefusedNotCut() {
        val image = SyntheticImage(seam = null, text = true)
        val result = detect(image).also { report("single", it) }
        assertFalse("单页不应检出书脊", result.found)
        assertNull(result.x)
        assertTrue(result.reason.isNotBlank())
        assertEquals(BookSpineDetector.MethodNone, result.method)
    }

    @Test
    fun blankPageIsRefused() {
        val image = SyntheticImage(seam = null, text = false, noise = 2)
        val result = detect(image).also { report("blank", it) }
        assertFalse(result.found)
        assertTrue(result.reason.isNotBlank())
    }

    @Test
    fun twoColumnTextWithoutARealFoldIsRefused() {
        val image = SyntheticImage(seam = null, centralGap = true, text = true)
        val result = detect(image).also { report("two-column", it) }
        assertFalse("两栏版心不应被当作书脊: ${result.reason}", result.found)
    }

    @Test
    fun splitKeepsLeftBeforeRightWithMeasuredSizes() {
        val image = SyntheticImage(seam = .46f, seamLuma = 72)
        val result = detect(image).also { report("asymmetric", it) }
        assertTrue(result.found)
        val top = result.top
        val bottom = result.bottom
        val (left, right) = CropGeometry.split(Quad.Full, top, bottom)
        assertTrue(left.valid())
        assertTrue(right.valid())
        assertEquals(top, left.points[1].x, 1e-6f)
        assertEquals(bottom, left.points[2].x, 1e-6f)
        assertEquals(left.points[1], right.points[0])
        assertEquals(left.points[2], right.points[3])
        assertEquals(1f, left.area + right.area, .0001f)
        assertTrue("书脊偏左时左页应更窄: ${left.area} / ${right.area}", left.area < right.area)
        val leftCenter = left.points.map { it.x }.average()
        val rightCenter = right.points.map { it.x }.average()
        assertTrue("左页整体应在右页左侧: $leftCenter / $rightCenter", leftCenter < rightCenter)
    }

    /** #23 follow-up: the detection carries both gutter edges so each half can be trimmed. */
    @Test
    fun detectedSeamExposesBothBandEdgesForInnerTrimming() {
        val image = SyntheticImage(seam = .46f, seamLuma = 72)
        val result = detect(image).also { report("edges", it) }
        assertTrue("未检出书脊: ${result.reason}", result.found)
        val left = requireNotNull(result.seamLeft) { "缺少缝左缘" }
        val right = requireNotNull(result.seamRight) { "缺少缝右缘" }
        val center = requireNotNull(result.x) { "缺少中心线" }
        assertTrue("左缘 ${left.top} 应在中心 $center 左侧", left.top < center)
        assertTrue("右缘 ${right.top} 应在中心 $center 右侧", right.top > center)
        // 14 px dark seam on a 1400 px image; the run edge may sit a few px outside because the
        // column means are smoothed (radius 2 px) and the edge threshold is 6 luma levels.
        val bandPx = (right.top - left.top) * (image.width - 1)
        println("edges left=${left.top} right=${right.top} bandPx=$bandPx")
        assertTrue("检出中缝宽度 ${bandPx}px", bandPx in image.seamWidth - 2f..image.seamWidth + 8f)
        // Straight seam here: both sample bands must report the same edges up to quantization.
        assertEquals(left.top, left.bottom, .002f)
        assertEquals(right.top, right.bottom, .002f)
    }
}
