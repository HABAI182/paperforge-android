package cn.paperforge.app

import cn.paperforge.app.model.CaptureHint
import cn.paperforge.app.model.CaptureHints
import cn.paperforge.app.model.CaptureQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #21: hint classification must stay actionable and must not fire on a clean shot. */
class CaptureHintsTest {
    private fun sample(
        sharpness: Double = 400.0,
        brightness: Double = .90,
        detailTiles: Int = 9,
        glare: Double = 0.0,
        minDetailed: Double = 0.0,
    ) = CaptureQualityPolicy.Sample(sharpness, brightness, detailTiles, glare, minDetailed)

    @Test
    fun cleanShotHasNoHints() {
        assertTrue(CaptureHints.of(sample(), cutOffEdges = 0).isEmpty())
    }

    @Test
    fun blurredAndLocallyBlurredAreSeparated() {
        assertEquals(listOf(CaptureHint.BLUR), CaptureHints.of(sample(sharpness = 20.0)))
        val motion =
            CaptureHints.of(sample(sharpness = 400.0, minDetailed = 22.0, detailTiles = 9))
        assertEquals(listOf(CaptureHint.MOTION), motion)
    }

    @Test
    fun glareExposureAndEdgeCutAreReported() {
        assertTrue(CaptureHint.GLARE in CaptureHints.of(sample(glare = .08)))
        assertTrue(CaptureHint.BRIGHT in CaptureHints.of(sample(glare = .60)))
        assertTrue(CaptureHint.DARK in CaptureHints.of(sample(brightness = .20)))
        assertTrue(CaptureHint.MISSING_EDGE in CaptureHints.of(sample(), cutOffEdges = 2))
    }

    @Test
    fun blankMarginsDoNotLookLikeMotion() {
        // No detailed tile means nothing to compare, so no motion claim.
        val blank = CaptureHints.of(sample(sharpness = 300.0, detailTiles = 0, minDetailed = 0.0))
        assertFalse(CaptureHint.MOTION in blank)
    }
}
