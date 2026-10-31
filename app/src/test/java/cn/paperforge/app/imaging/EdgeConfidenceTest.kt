package cn.paperforge.app.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scoring-only tests for #21. They do not touch geometry: the features here are the measured
 * components of a candidate, and the assertions pin the calibration semantics documented in
 * docs/acceptance/issues/21/confidence-calibration.md.
 */
class EdgeConfidenceTest {
    private fun features(
        supportMean: Float = 1f,
        supportMin: Float = 1f,
        supportSpread: Float = 0f,
        strength: Float = 1f,
        edgeResidual: Float = 1f,
        contourMean: Float = .6f,
        contourP90: Float = 1.1f,
        cornerMin: Float = 1f,
        cornerMean: Float = 1f,
        area: Float = .5f,
        aspect: Float = 1.4f,
    ) =
        EdgeConfidenceFeatures(
            edgeSupportMean = supportMean,
            edgeSupportMin = supportMin,
            edgeSupportSpread = supportSpread,
            edgeStrengthMean = strength,
            edgeResidualMean = edgeResidual,
            contourResidualMean = contourMean,
            contourResidualP90 = contourP90,
            cornerPeakMin = cornerMin,
            cornerPeakMean = cornerMean,
            area = area,
            aspect = aspect,
        )

    private fun score(f: EdgeConfidenceFeatures) = EdgeConfidence.score(f)

    @Test
    fun cleanPageIsConfidentButNeverPinnedToACeiling() {
        val clean = score(features())
        assertTrue("clean page must clear auto-capture: $clean", clean >= .85f)
        assertTrue("clean page must stay below the hard ceiling: $clean", clean < .98f)
        assertTrue(clean.isFinite())
    }

    @Test
    fun curvedOrInsetContourLosesConfidence() {
        val clean = score(features())
        val curved =
            score(
                features(
                    edgeResidual = 1.5f,
                    contourMean = 1.1f,
                    contourP90 = 3.2f,
                    cornerMin = .92f,
                    cornerMean = .96f,
                    area = .08f,
                    aspect = 2.4f,
                )
            )
        assertTrue("curved receipt ($curved) below clean ($clean)", curved < clean - .08f)
        assertTrue("curved receipt still trackable: $curved", curved >= .55f)
    }

    @Test
    fun weakerEdgesAndCornersDropBelowCaptureFloor() {
        val weak =
            score(
                features(
                    supportMean = .55f,
                    supportMin = .28f,
                    supportSpread = .4f,
                    strength = .45f,
                    edgeResidual = 2.4f,
                    contourMean = 1.6f,
                    contourP90 = 3.4f,
                    cornerMin = .3f,
                    cornerMean = .4f,
                    area = .05f,
                    aspect = 3.8f,
                )
            )
        assertTrue("weak candidate ($weak) must fall under the .55 outline gate", weak < .55f)
    }

    @Test
    fun scoreIsStrictlyMonotoneInContourAndEdgeEvidence() {
        val ladder =
            listOf(1.0f, 1.6f, 2.2f, 2.8f, 3.4f).map { p90 ->
                score(features(contourP90 = p90, contourMean = p90 * .4f))
            }
        ladder.zipWithNext().forEach { (better, worse) ->
            assertTrue("structure must be strictly monotone: $ladder", better > worse)
        }
        assertEquals(ladder.size, ladder.distinct().size)

        val good = score(features())
        val worseSupport = score(features(supportMean = .9f, supportMin = .8f))
        val worseCorner = score(features(cornerMin = .7f))
        val worseResidual = score(features(edgeResidual = 2f))
        assertTrue(good > worseSupport)
        assertTrue(good > worseCorner)
        assertTrue(good > worseResidual)
    }

    @Test
    fun priorsOnlyDiscountDegenerateQuads() {
        val normal = score(features(area = .2f, aspect = 1.4f))
        val nearFullFrame = score(features(area = .95f))
        val strip = score(features(aspect = 8f))
        val tiny = score(features(area = .045f))
        assertEquals(normal, score(features(area = .2f)), 0f)
        assertTrue(normal > nearFullFrame)
        assertTrue(normal > strip)
        assertTrue(normal > tiny)
    }

    @Test
    fun everyInputStaysInUnitRange() {
        val extremes =
            listOf(
                features(
                    supportMean = 0f,
                    supportMin = 0f,
                    supportSpread = 1f,
                    strength = 0f,
                    edgeResidual = 3f,
                    contourMean = 3f,
                    contourP90 = 8f,
                    cornerMin = 0f,
                    cornerMean = 0f,
                    area = .01f,
                    aspect = 20f,
                ),
                features(),
            )
        extremes.forEach { f ->
            val value = score(f)
            assertTrue("$value out of range", value in 0f..0.999f)
        }
    }
}
