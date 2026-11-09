package cn.paperforge.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.imaging.ContentCrop
import cn.paperforge.app.imaging.GridStraighten
import cn.paperforge.app.imaging.PaperEdgeGuard
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Synthetic rules for the v200 20016 edge-ink fix. No existing assertion is modified; these are
 * new cases requested by the acceptance brief:
 *  - a stroke that reaches/crosses the page edge must survive the edge guard;
 *  - a ruled line touching the edge must survive;
 *  - an isolated, low-contrast grey rim must still be cleaned;
 *  - a crop whose frame clips a stroke must be pushed outward;
 *  - a straighten shift must be refused when ink touches the frame edge.
 */
@RunWith(AndroidJUnit4::class)
class EdgeInkRulesTest {
    private fun page(): Mat =
        Mat(1100, 800, CvType.CV_8UC3).also {
            it.setTo(Scalar(246.0, 246.0, 246.0))
        }

    private fun luma(mat: Mat, x: Int, y: Int): Double {
        val p = mat.get(y, x) ?: return 0.0
        return .299 * p[0] + .587 * p[1] + .114 * p[2]
    }

    private fun withConfig(block: () -> Unit) {
        val snapshot = ScanConfig.replaceForBenchmark(JSONObject())
        try {
            block()
        } finally {
            ScanConfig.restoreAfterBenchmark(snapshot)
        }
    }

    @Test
    fun strokeCrossingTheEdgeSurvivesTheGuard() = withConfig {
        val image = page()
        try {
            // 3 px pen stroke entering at x=0 and continuing into the page.
            Imgproc.rectangle(image, Point(0.0, 398.0), Point(160.0, 401.0), Scalar(38.0, 38.0, 38.0), -1)
            val cleaned = PaperEdgeGuard.apply(image)
            assertTrue("edge ink was rewritten", luma(image, 1, 400) < 80.0)
            assertEquals("a crossing stroke must not be counted as cleaned rim", 0, cleaned)
        } finally {
            image.release()
        }
    }

    @Test
    fun shortDarkRuleEndAtTheEdgeSurvivesTheGuard() = withConfig {
        val image = page()
        try {
            // A 60 px vertical rule that stops at the very edge; the old guard only sampled the
            // pixel at 2*ring inward, saw paper, and lifted the rule's end.
            Imgproc.rectangle(image, Point(0.0, 200.0), Point(2.0, 260.0), Scalar(45.0, 45.0, 45.0), -1)
            PaperEdgeGuard.apply(image)
            assertTrue("a dark rule end at the edge was lifted", luma(image, 0, 230) < 90.0)
        } finally {
            image.release()
        }
    }

    @Test
    fun isolatedGreyRimIsStillRemoved() = withConfig {
        val image = page()
        try {
            for (y in 0 until image.rows()) for (x in 0 until 2) {
                image.put(y, x, byteArrayOf(218.toByte(), 218.toByte(), 218.toByte()))
            }
            val cleaned = PaperEdgeGuard.apply(image)
            assertTrue("isolated grey rim was not cleaned ($cleaned px)", cleaned > 100)
            assertTrue("rim pixel stayed grey", luma(image, 0, 500) > 235.0)
        } finally {
            image.release()
        }
    }

    @Test
    fun fullEdgeSpineBandIsStillCleaned() = withConfig {
        val image = page()
        try {
            for (y in 0 until image.rows()) for (x in 0 until 4) {
                val v = 140 + x * 8
                image.put(y, x, byteArrayOf(v.toByte(), v.toByte(), v.toByte()))
            }
            PaperEdgeGuard.apply(image)
            assertTrue("uniform spine band was not cleaned", luma(image, 0, 500) > 200.0)
        } finally {
            image.release()
        }
    }

    @Test
    fun cropIsPushedOutwardWhenInkTouchesTheFrame() = withConfig {
        val image = Mat(600, 800, CvType.CV_8UC3)
        try {
            image.setTo(Scalar(246.0, 246.0, 246.0))
            // Stroke sits just inside the quad's left side (x = 12), so a naive crop clips it.
            Imgproc.rectangle(image, Point(12.0, 100.0), Point(18.0, 700.0), Scalar(40.0, 40.0, 40.0), -1)
            val quad =
                Quad(
                    listOf(
                        PointN(.02f, .02f),
                        PointN(.98f, .02f),
                        PointN(.98f, .98f),
                        PointN(.02f, .98f),
                    )
                )
            val expanded = ContentCrop.protect(image, quad)
            assertTrue(
                "crop was not pushed outward: ${quad.points[0].x} -> ${expanded.points[0].x}",
                expanded.points[0].x < quad.points[0].x - .002f,
            )
            assertTrue("expanded quad became invalid", expanded.valid() && expanded.area > quad.area)
        } finally {
            image.release()
        }
    }

    @Test
    fun straightenIsRefusedWhenInkTouchesTheFrameEdge() {
        val clean = Mat(300, 400, CvType.CV_8UC1)
        val inked = Mat(300, 400, CvType.CV_8UC1)
        try {
            clean.setTo(Scalar(240.0))
            inked.setTo(Scalar(240.0))
            Imgproc.rectangle(inked, Point(0.0, 40.0), Point(5.0, 260.0), Scalar(40.0), -1)
            val cleanBytes = ByteArray(400 * 300).also { clean.get(0, 0, it) }
            val inkedBytes = ByteArray(400 * 300).also { inked.get(0, 0, it) }
            assertTrue("clean page reported edge ink", !GridStraighten.touchesEdge(cleanBytes, 400, 300))
            assertTrue("edge ink was not detected", GridStraighten.touchesEdge(inkedBytes, 400, 300))
        } finally {
            clean.release()
            inked.release()
        }
    }
}
