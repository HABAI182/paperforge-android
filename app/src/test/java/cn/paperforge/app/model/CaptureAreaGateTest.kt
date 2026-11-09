package cn.paperforge.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #21 B: `captureArea` keeps gating ordinary pages, while clearly elongated receipts (the DocUNet
 * 4_2 class: area 7.39%, side ratio 2.62) are allowed down to 4%.
 */
class CaptureAreaGateTest {
    private val frameWidth = 1350
    private val frameHeight = 1800

    /** Corners recorded by the DocUNet probe for 4_2.jpg (normalized to the decoded 1350x1800). */
    private val receipt42 =
        Quad(
            listOf(
                PointN(.3863f, .3122f),
                PointN(.5722f, .2834f),
                PointN(.6474f, .6714f),
                PointN(.4477f, .6781f),
            )
        )

    /** A4-like page, 6% of the frame, physical aspect 1.41, long side vertical. */
    private val pageA4 =
        Quad(
            listOf(
                PointN(.332f, .411f),
                PointN(.668f, .411f),
                PointN(.668f, .589f),
                PointN(.332f, .589f),
            )
        )

    /** A small ticket that is long but still below the receipt floor. */
    private val tinyReceipt =
        Quad(
            listOf(
                PointN(.327f, .4567f),
                PointN(.673f, .4567f),
                PointN(.673f, .5433f),
                PointN(.327f, .5433f),
            )
        )

    private fun gate() = AutoCaptureGate(stableMs = 600, cooldownMs = 1500, minimumArea = .08f)

    private fun fireCount(quad: Quad): Int {
        val gate = gate()
        var fired = 0
        for (step in 0..12) {
            if (
                gate.update(
                    quad,
                    step * 200L,
                    true,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                )
            )
                fired++
        }
        return fired
    }

    private fun fireCountWithoutFrame(quad: Quad): Int {
        val gate = gate()
        var fired = 0
        for (step in 0..12) if (gate.update(quad, step * 200L, true)) fired++
        return fired
    }

    @Test
    fun recordedReceiptIsElongatedAndBelowThePageFloor() {
        assertEquals(.0739f, receipt42.area, .0005f)
        val sides = ImageGeometry.pixelSides(receipt42, frameWidth, frameHeight)
        val ratio = maxOf(sides.first, sides.second) / minOf(sides.first, sides.second)
        assertEquals(2.62f, ratio, .05f)
        assertTrue(ratio >= 1.8f)
        assertTrue(receipt42.area < .08f)
    }

    @Test
    fun elongatedReceiptNowArmsButOrdinaryPageAtSixPercentDoesNot() {
        assertEquals(1, fireCount(receipt42))
        assertEquals(0, fireCount(pageA4))
    }

    @Test
    fun pageFloorIsUnchangedWhenTheFrameIsUnknown() {
        // The legacy call (no frame dimensions) behaves exactly like the old fixed floor.
        assertEquals(0, fireCountWithoutFrame(receipt42))
        assertEquals(.08f, gate().areaFloor(receipt42, 0, 0), 0f)
    }

    @Test
    fun effectiveFloorOnlyDropsForElongatedQuads() {
        val gate = gate()
        assertEquals(.04f, gate.areaFloor(receipt42, frameWidth, frameHeight), 0f)
        assertEquals(.08f, gate.areaFloor(pageA4, frameWidth, frameHeight), 0f)
        assertEquals(.04f, gate.areaFloor(tinyReceipt, frameWidth, frameHeight), 0f)
    }

    @Test
    fun receiptStillNeedsTheLowerFloor() {
        assertTrue(tinyReceipt.area < .04f)
        assertEquals(0, fireCount(tinyReceipt))
    }

    @Test
    fun largePagesKeepTheUserFloor() {
        // 12% page: above both floors, the aspect branch must not disable arming.
        val large =
            Quad(listOf(PointN(.1f, .1f), PointN(.7f, .1f), PointN(.7f, .8f), PointN(.1f, .8f)))
        assertEquals(1, fireCount(large))
        assertFalse(gate().areaFloor(large, frameWidth, frameHeight) < .04f)
    }
}
