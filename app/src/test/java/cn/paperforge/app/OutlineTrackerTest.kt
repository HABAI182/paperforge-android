package cn.paperforge.app

import cn.paperforge.app.model.*
import org.junit.Assert.*
import org.junit.Test

class OutlineTrackerTest {
    @Test
    fun currentFrameRejectsCoastAndUnconfirmedPageSwitch() {
        val tracker = OutlineTracker(weight = 3f, switchFrames = 2)
        val paper = rectangle(.1f)
        val other = rectangle(.3f)
        assertEquals(paper, tracker.updateCurrent(paper, .9f, 1000))
        assertNull("A missing raw frame cannot freeze an old crop", tracker.updateCurrent(null, 0f, 1160))
        assertNull("A pending page switch is not an accepted crop", tracker.updateCurrent(other, .9f, 1320))
        assertEquals(other, tracker.updateCurrent(other, .9f, 1480))
        assertNull(tracker.updateCurrent(other, .54f, 1640))
        assertNull(tracker.updateCurrent(other, Float.NaN, 1800))
        val invalid = Quad(List(4) { PointN(.1f, .1f) })
        assertNull(tracker.updateCurrent(invalid, .9f, 1960))
    }

    @Test
    fun currentFrameReturnsTheSameSmoothedCoordinatesThatCapturePersists() {
        val tracker = OutlineTracker(weight = 3f, switchFrames = 2)
        tracker.updateCurrent(rectangle(.1f), .9f, 1000)
        val accepted = requireNotNull(tracker.updateCurrent(rectangle(.13f), .9f, 1160))
        assertEquals(.11f, accepted.points[0].x, .00001f)
        val reference = CaptureReference(accepted, 1160, 1200, 600, 800, "v", 1L, .9f, emptyList(), emptyList(), canonicalFrame = true)
        assertEquals(accepted, reference.quad)
        assertNotEquals(rectangle(.13f), reference.quad)
    }

    @Test
    fun shownOutlineHoldsThroughAMissADipAndASwitchWhileCaptureNeedsTheCurrentFrame() {
        val tracker = OutlineTracker(weight = 3f, switchFrames = 2)
        val paper = rectangle(.1f)
        val other = rectangle(.3f)
        assertEquals(OutlineTracker.Frame(paper, paper), tracker.track(paper, .9f, 1000))
        val missed = tracker.track(null, 0f, 1160)
        assertEquals("one missed frame does not take the outline off the screen", paper, missed.shown)
        assertNull(missed.accepted)
        val dip = tracker.track(paper, .5f, 1320)
        assertEquals("a dip under the acceptance level keeps tracking the same page", paper, dip.shown)
        assertNull("but is not a crop", dip.accepted)
        val switching = tracker.track(other, .9f, 1480)
        assertEquals(paper, switching.shown)
        assertNull(switching.accepted)
        assertEquals(OutlineTracker.Frame(other, other), tracker.track(other, .9f, 1640))
        assertNull("a new page needs the acceptance level to appear", OutlineTracker().track(paper, .5f, 1000).shown)
        assertNull("deep dips still count as misses", tracker.track(other, .3f, 1800).accepted)
        assertNull(tracker.track(other, .3f, 1960).shown)
    }

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
