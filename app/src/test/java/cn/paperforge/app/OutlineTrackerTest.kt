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
        // Jitter (a shift under 0.6 % of the frame) is averaged; see the adaptive blend in OutlineTracker.
        val accepted = requireNotNull(tracker.updateCurrent(rectangle(.106f), .9f, 1160))
        assertEquals(.102f, accepted.points[0].x, .00001f)
        val reference = CaptureReference(accepted, 1160, 1200, 600, 800, "v", 1L, .9f, emptyList(), emptyList(), canonicalFrame = true)
        assertEquals(accepted, reference.quad)
        assertNotEquals(rectangle(.106f), reference.quad)
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

    @Test
    fun aSlowMoveIsFollowedClosely() {
        val tracker = OutlineTracker(weight = 3f, switchFrames = 2)
        tracker.track(rectangle(.1f), .9f, 1000)
        // 2.5 % of the frame per analysis is a real move, not jitter: followed in full, not a third of the way.
        val moved = tracker.track(rectangle(.125f), .9f, 1160)
        assertEquals(.125f, moved.shown!!.points[0].x, .00001f)
        assertEquals(moved.shown, moved.accepted)
    }

    @Test
    fun aFastMoveIsFollowedWithoutFreezing() {
        val tracker = OutlineTracker(weight = 3f, switchFrames = 2)
        tracker.track(rectangle(.10f), .9f, 1000)
        // 8 % of the frame per analysis, one way: the first frame waits, the next confirms the move.
        assertEquals(.10f, tracker.track(rectangle(.18f), .9f, 1160).shown!!.points[0].x, .00001f)
        val second = tracker.track(rectangle(.26f), .9f, 1320)
        assertEquals("the move is followed, not frozen until the hand slows", .26f, second.shown!!.points[0].x, .00001f)
        assertEquals(second.shown, second.accepted)
        assertEquals(.34f, tracker.track(rectangle(.34f), .9f, 1480).shown!!.points[0].x, .00001f)
    }

    @Test
    fun aOneFrameJumpToAnotherShapeIsStillIgnored() {
        val tracker = OutlineTracker(weight = 3f, switchFrames = 2)
        val paper = rectangle(.1f)
        tracker.track(paper, .9f, 1000)
        // A different, much smaller quad for one frame (a wrong detection), then the page again.
        val wrong = Quad(listOf(PointN(.4f, .4f), PointN(.6f, .4f), PointN(.6f, .6f), PointN(.4f, .6f)))
        assertEquals(paper, tracker.track(wrong, .9f, 1160).shown)
        assertEquals(paper, tracker.track(paper, .9f, 1320).shown)
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
        val jittered = tracker.update(rectangle(.106f), .9f, emptyList(), 1960, epoch = 1)!!
        assertEquals(.102f, jittered.points[0].x, .00001f)
        assertEquals(.106f, rectangle(.106f).points[0].x, 0f)
    }
}
