package cn.paperforge.app

import cn.paperforge.app.model.*
import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test
    fun autoCaptureRecognizesPageChangeAtTheSameCorners() {
        val gate = AutoCaptureGate(stableMs = 600, cooldownMs = 1500)
        var captures = 0
        for (time in 0..5000 step 200) {
            val scene =
                List(16) { index -> if (time < 2400) .7f else if (index % 2 == 0) .55f else .8f }
            if (gate.update(Quad.Full, time.toLong(), true, scene)) captures++
        }
        assertEquals(2, captures)
        val exposureGate = AutoCaptureGate(stableMs = 600, cooldownMs = 1500)
        var duplicates = 0
        for (time in 0..5000 step 200) {
            if (
                exposureGate.update(
                    Quad.Full,
                    time.toLong(),
                    true,
                    List(16) { if (time < 2400) .7f else .55f },
                )
            )
                duplicates++
        }
        assertEquals(1, duplicates)
    }

    private val quad =
        Quad(listOf(PointN(.1f, .1f), PointN(.9f, .1f), PointN(.9f, .9f), PointN(.1f, .9f)))

    @Test
    fun unorderedCornersBecomeClockwise() {
        val ordered =
            Quad.ordered(listOf(quad.points[2], quad.points[0], quad.points[3], quad.points[1]))
        assertEquals(quad, ordered)
        assertTrue(ordered.valid())
        assertEquals(.64f, ordered.area, .001f)
    }

    @Test
    fun crossedOrOutsideCornersRejected() {
        assertFalse(
            Quad(listOf(quad.points[0], quad.points[2], quad.points[1], quad.points[3])).valid()
        )
        assertFalse(
            quad
                .copy(points = quad.points.toMutableList().apply { set(0, PointN(-.1f, 0f)) })
                .valid()
        )
        assertFalse(Quad(List(4) { PointN(.1f, .1f) }).valid())
    }

    @Test
    fun corruptRecipeFallsBackToFullImage() {
        listOf("bad", "NaN,0;1,0;1,1;0,1", "0,0;1,1").forEach {
            assertEquals(Quad.Full, Quad.decode(it))
        }
        assertEquals(quad, Quad.decode(quad.encode()))
    }

    @Test
    fun stableDetectionCapturesOnceUntilPaperChanges() {
        val gate = AutoCaptureGate()
        assertFalse(gate.update(quad, 0))
        assertFalse(gate.update(quad, 600))
        assertTrue(gate.update(quad, 1300))
        assertFalse(gate.update(quad, 6000))
        assertFalse(gate.update(quad, 9000))
        assertFalse(gate.update(null, 9100))
        assertFalse(gate.update(null, 9800))
        assertFalse(gate.update(quad, 9900))
        assertTrue(gate.update(quad, 11200))
    }

    @Test
    fun jitterAndSmallShapesCannotTrigger() {
        val gate = AutoCaptureGate()
        assertFalse(gate.update(quad, 0))
        val shifted = quad.copy(points = quad.points.map { PointN(it.x - .04f, it.y) })
        assertFalse(gate.update(shifted, 1300))
        assertFalse(gate.update(quad, 2500))
        val small =
            Quad(listOf(PointN(.1f, .1f), PointN(.2f, .1f), PointN(.2f, .2f), PointN(.1f, .2f)))
        assertFalse(gate.update(small, 5000))
    }

    @Test
    fun rangesPreserveUserOrderAndRemoveDuplicates() {
        assertEquals(listOf(4, 0, 1, 2), parsePageRange("5,1-3,2", 10))
        assertEquals(listOf(0, 1, 2), parsePageRange("", 3))
        assertEquals(listOf(0, 2), parsePageRange("1，3", 3))
    }

    @Test
    fun invalidRangesDoNotProducePartialOutput() {
        listOf("0", "2-1", "1-11", "x", "1,,2", "1-2-3").forEach { invalid ->
            assertTrue(
                "Must reject $invalid",
                runCatching { parsePageRange(invalid, 10) }.isFailure,
            )
        }
    }

    @Test
    fun fileNamesCannotEscapeDestination() {
        assertEquals("_.._test_pdf", safeFileName("../../test:pdf"))
        assertEquals("纸匠文档", safeFileName("..."))
        assertTrue(safeFileName("a".repeat(200)).length <= 100)
    }
}
