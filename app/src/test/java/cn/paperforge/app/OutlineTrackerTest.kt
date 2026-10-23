package cn.paperforge.app

import cn.paperforge.app.model.*
import org.junit.Assert.*
import org.junit.Test

class OutlineTrackerTest {
    private fun rectangle(left: Float) =
        Quad(
            listOf(
                PointN(left, .1f),
                PointN(left + .5f, .1f),
                PointN(left + .5f, .9f),
                PointN(left, .9f),
            )
        )

    @Test
    fun transientJumpsDoNotMoveTheOutlineAndLossExpiresInMilliseconds() {
        val tracker = OutlineTracker(weight = 3f, switchFrames = 2)
        val paper = rectangle(.1f)
        val other = rectangle(.3f)
        assertEquals(paper, tracker.update(paper, .9f, emptyList(), 1000))
        assertEquals(paper, tracker.update(other, .9f, emptyList(), 1160))
        assertEquals(other, tracker.update(other, .9f, emptyList(), 1320))
        assertEquals(other, tracker.update(null, 0f, emptyList(), 1480))
        assertNull(tracker.update(null, 0f, emptyList(), 1640))
        assertEquals(paper, tracker.update(paper, .9f, emptyList(), 1800, epoch = 1))
        val jittered = tracker.update(rectangle(.13f), .9f, emptyList(), 1960, epoch = 1)!!
        assertEquals(.11f, jittered.points[0].x, .00001f)
        assertEquals(.13f, rectangle(.13f).points[0].x, 0f)
    }
}
