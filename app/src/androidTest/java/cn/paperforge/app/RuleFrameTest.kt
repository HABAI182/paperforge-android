package cn.paperforge.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.imaging.RuleFrame
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/** Which edge is the top sheet's, on drawn pages. Coordinates are fractions of the photo. */
@RunWith(AndroidJUnit4::class)
class RuleFrameTest {
    private val paper = Scalar(235.0, 232.0, 225.0)
    private val desk = Scalar(170.0, 120.0, 70.0)
    private val line = Scalar(120.0, 120.0, 120.0)

    // Rule framing is off by default since PageNet v3 finds the top sheet itself; these tests check the step.
    private var snapshot: org.json.JSONObject? = null

    @Before
    fun load() {
        assertTrue(OpenCVLoader.initLocal())
        snapshot = cn.paperforge.app.imaging.ScanConfig.replaceForBenchmark(org.json.JSONObject().put("ruleFrame", 1))
    }

    @org.junit.After
    fun restore() {
        snapshot?.let { cn.paperforge.app.imaging.ScanConfig.restoreAfterBenchmark(it) }
    }

    private fun photo(): Mat = Mat(1600, 1200, CvType.CV_8UC3, desk)

    private fun rules(image: Mat, x0: Int, x1: Int, firstY: Int, gap: Int, count: Int) {
        for (i in 0 until count) {
            val y = firstY + i * gap
            Imgproc.line(image, Point(x0.toDouble(), y.toDouble()), Point(x1.toDouble(), y.toDouble()), line, 2)
        }
    }

    private fun quad(x0: Double, y0: Double, x1: Double, y1: Double) =
        Quad(
            listOf(
                PointN(x0.toFloat(), y0.toFloat()),
                PointN(x1.toFloat(), y0.toFloat()),
                PointN(x1.toFloat(), y1.toFloat()),
                PointN(x0.toFloat(), y1.toFloat()),
            )
        )

    private fun left(result: RuleFrame.Result) = (result.quad.points[0].x + result.quad.points[3].x) / 2

    private fun right(result: RuleFrame.Result) = (result.quad.points[1].x + result.quad.points[2].x) / 2

    @Test
    fun aStackIsCutToTheTopSheet() {
        val image = photo()
        // Sheets below stick out on the left, with their rules at other heights.
        Imgproc.rectangle(image, Rect(150, 200, 950, 1200), paper, -1)
        rules(image, 150, 300, 243, 60, 19)
        Imgproc.rectangle(image, Rect(300, 200, 800, 1200), paper, -1)
        Imgproc.line(image, Point(300.0, 200.0), Point(300.0, 1400.0), Scalar(70.0, 70.0, 70.0), 2)
        rules(image, 302, 1098, 260, 60, 19)
        val result = RuleFrame.refine(image, quad(.125, .125, .9167, .875))
        assertNotNull("the rules are plain to see", result)
        assertEquals(300f / 1200, left(result!!), .012f)
        assertEquals(1100f / 1200, right(result), .012f)
        image.release()
    }

    @Test
    fun aMarginLineThatRulesCrossStays() {
        val image = photo()
        Imgproc.rectangle(image, Rect(150, 200, 950, 1200), paper, -1)
        rules(image, 150, 1098, 260, 60, 19)
        Imgproc.line(image, Point(280.0, 200.0), Point(280.0, 1400.0), Scalar(60.0, 60.0, 160.0), 2)
        val result = RuleFrame.refine(image, quad(.125, .125, .9167, .875))
        assertNotNull(result)
        assertEquals("the margin is part of the sheet", 150f / 1200, left(result!!), .012f)
        image.release()
    }

    @Test
    fun blankPaperBeyondTheRulesIsAMarginAndStays() {
        val image = photo()
        Imgproc.rectangle(image, Rect(150, 200, 950, 1200), paper, -1)
        rules(image, 300, 950, 260, 60, 19)
        val result = RuleFrame.refine(image, quad(.125, .125, .9167, .875))
        assertNotNull(result)
        assertEquals(150f / 1200, left(result!!), .012f)
        assertEquals(1100f / 1200, right(result), .012f)
        image.release()
    }

    @Test
    fun aQuadTooSmallGrowsToTheRulesEnds() {
        val image = photo()
        Imgproc.rectangle(image, Rect(150, 200, 950, 1200), paper, -1)
        rules(image, 150, 1098, 260, 60, 19)
        val result = RuleFrame.refine(image, quad(.1875, .1625, .8542, .8375))
        assertNotNull(result)
        assertEquals(150f / 1200, left(result!!), .015f)
        assertEquals(1100f / 1200, right(result), .015f)
        image.release()
    }

    @Test
    fun aPageWithoutRulesKeepsItsQuad() {
        val image = photo()
        Imgproc.rectangle(image, Rect(150, 200, 950, 1200), paper, -1)
        assertNull(RuleFrame.refine(image, quad(.125, .125, .9167, .875)))
        image.release()
    }
}
