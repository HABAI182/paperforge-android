package cn.paperforge.app.model

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillCropTest {
    private fun quad(vararg xy: Float) = Quad(xy.toList().chunked(2).map { (x, y) -> PointN(x, y) })

    private val preview = quad(.10f, .10f, .90f, .10f, .90f, .90f, .10f, .90f)
    private val refined = quad(.11f, .11f, .91f, .11f, .91f, .91f, .11f, .91f)

    private fun moved(dx: Float, dy: Float) =
        quad(
            .10f + dx,
            .10f + dy,
            .90f + dx,
            .10f + dy,
            .90f + dx,
            .90f + dy,
            .10f + dx,
            .90f + dy,
        )

    @Test
    fun shiftExactlyAtThresholdKeepsTheViewfinderQuad() {
        val still = moved(StillCrop.DefaultShiftThreshold, 0f)
        val shift = StillCrop.maxCornerShift(preview, still)
        assertTrue(shift > 0f)
        // Use the measured shift itself as the threshold: "equal" must stay on the preview side.
        val decision = StillCrop.decide(preview, .9f, still, .9f, refined, shiftThreshold = shift)
        assertFalse(decision.adoptedStill)
        assertEquals(StillCrop.ReasonWithinThreshold, decision.reason)
        assertEquals(refined, decision.quad)
        assertEquals(
            shift,
            decision.maxCornerShift,
            shift * 1e-4f,
        )
    }

    @Test
    fun shiftJustAboveThresholdAdoptsTheStillQuad() {
        val still = moved(.0201f, 0f)
        val decision = StillCrop.decide(preview, .9f, still, .9f, refined)
        assertTrue(decision.adoptedStill)
        assertEquals(StillCrop.ReasonAdopted, decision.reason)
        assertEquals(still, decision.quad)
        assertTrue(decision.maxCornerShift > StillCrop.DefaultShiftThreshold)
        assertEquals(1f, decision.diagnostics()["stillAdopted"])
    }

    @Test
    fun diagonalShiftUsesEuclideanDistanceNotEitherAxis() {
        // 0.005 on both axes is 0.0071 Euclidean: below the 0.01 threshold even though a per-axis
        // reading could be mistaken for a larger move; 0.008+0.008 = 0.0113 is above and must adopt.
        val near = moved(.005f, .005f)
        assertEquals(hypot(.005f, .005f), StillCrop.maxCornerShift(preview, near), 1e-6f)
        assertFalse(StillCrop.decide(preview, .9f, near, .9f, refined).adoptedStill)
        val far = moved(.008f, .008f)
        assertTrue(StillCrop.decide(preview, .9f, far, .9f, refined).adoptedStill)
    }

    @Test
    fun lowConfidenceStillNeverOverridesThePreview() {
        val still = moved(.06f, 0f)
        val decision = StillCrop.decide(preview, .95f, still, .599f, refined)
        assertFalse(decision.adoptedStill)
        assertEquals(StillCrop.ReasonStillLowConfidence, decision.reason)
        assertEquals(1f, decision.diagnostics()["stillReasonCode"])
        assertEquals(refined, decision.quad)
        assertTrue(decision.maxCornerShift > StillCrop.DefaultShiftThreshold)
    }

    @Test
    fun aConfidentStillOverridesAnUnsureViewfinder() {
        // Reported on a real page: an unsure preview quad covered part of the sheet and outvoted a
        // confident still that had found all of it.
        val still = moved(.06f, 0f)
        val decision = StillCrop.decide(preview, .59f, still, .9f, refined)
        assertTrue(decision.adoptedStill)
        assertEquals(StillCrop.ReasonAdopted, decision.reason)
        assertEquals(still, decision.quad)
        assertEquals(.59f, decision.diagnostics()["stillPreviewConfidence"]!!)
    }

    @Test
    fun aConfidentStillOverridesAnUnsureViewfinderEvenWhenClose() {
        val still = moved(.005f, 0f)
        val decision = StillCrop.decide(preview, .59f, still, .9f, refined)
        assertTrue(decision.adoptedStill)
        assertEquals(still, decision.quad)
    }

    @Test
    fun confidenceExactlyAtTheFloorIsTrusted() {
        // The shipped floor is 0.6; the gate is "below", so 0.6 itself may adopt a far still.
        val still = moved(.06f, 0f)
        assertTrue(StillCrop.decide(preview, .6f, still, .6f, refined).adoptedStill)
        assertTrue(StillCrop.decide(preview, .9f, still, .6f, refined).adoptedStill)
        assertFalse(StillCrop.decide(preview, .9f, still, .5999f, refined).adoptedStill)
        // Only the still's own confidence can block it: an unsure viewfinder never does.
        assertTrue(StillCrop.decide(preview, .5999f, still, .9f, refined).adoptedStill)
    }

    @Test
    fun failedOrInvalidStillFallsBackToTheRefinedPreview() {
        for (still in listOf(null, quad(.5f, .5f, .51f, .5f, .51f, .51f, .5f, .51f))) {
            val decision = StillCrop.decide(preview, .8f, still, 0f, refined)
            assertFalse(decision.adoptedStill)
            assertEquals(StillCrop.ReasonFailed, decision.reason)
            assertEquals(-1f, decision.maxCornerShift)
            assertEquals(refined, decision.quad)
        }
        val withoutRefine = StillCrop.decide(preview, .8f, null, 0f)
        assertEquals(preview, withoutRefine.quad)
    }

    @Test
    fun diagnosticsCarryShiftThresholdConfidencesAndReason() {
        val still = moved(.03f, 0f)
        val decision = StillCrop.decide(preview, .77f, still, .88f, refined)
        val diagnostics = decision.diagnostics()
        assertEquals(3, decision.reasonCode)
        assertEquals(.03f, diagnostics["stillMaxCornerShift"]!!, 1e-6f)
        assertEquals(StillCrop.DefaultShiftThreshold, diagnostics["stillShiftThreshold"]!!)
        assertEquals(1f, diagnostics["stillAdopted"]!!)
        assertEquals(.77f, diagnostics["stillPreviewConfidence"]!!)
        assertEquals(.88f, diagnostics["stillConfidence"]!!)
        assertEquals(3f, diagnostics["stillReasonCode"]!!)
        val ratio = diagnostics["stillAreaRatio"]!!
        assertTrue("area ratio $ratio", ratio > .5f && ratio < 1.5f)
    }
}
