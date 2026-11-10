package cn.paperforge.app

import cn.paperforge.app.model.LoupePlacement
import cn.paperforge.app.model.PointN
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoupePlacementTest {
    // A 1080 x 1400 px crop view at density 2.75: loupe radius 56 dp, fingertip 40 dp, margin 6 dp.
    private val w = 1080f
    private val h = 1400f
    private val r = 154f
    private val f = 110f
    private val m = 16.5f

    private fun place(target: PointN, finger: PointN?, previous: PointN? = null) =
        LoupePlacement.place(w, h, target, finger, r, f, m, previous)

    private fun assertClear(spot: PointN, target: PointN, finger: PointN?) {
        assertTrue("inside the view: $spot", spot.x - r >= 0 && spot.x + r <= w && spot.y - r >= 0 && spot.y + r <= h)
        assertTrue("off the point: $spot", hypot(spot.x - target.x, spot.y - target.y) >= r)
        if (finger != null) {
            assertTrue("off the fingertip: $spot", hypot(spot.x - finger.x, spot.y - finger.y) >= r + f)
            assertTrue("not on the hand below the finger: $spot", spot.y < finger.y || kotlin.math.abs(spot.x - finger.x) > f)
        }
    }

    @Test
    fun aBottomCornerPutsTheLoupeAboveTheFinger() {
        val target = PointN(120f, 1250f)
        val finger = PointN(150f, 1290f)
        val spot = place(target, finger)
        assertClear(spot, target, finger)
        assertTrue("above the finger: $spot", spot.y < finger.y - r)
    }

    @Test
    fun theTopCornersPutTheLoupeBesideTheFingerTowardsTheMiddle() {
        val left = PointN(60f, 60f)
        val leftSpot = place(left, PointN(90f, 100f))
        assertClear(leftSpot, left, PointN(90f, 100f))
        assertTrue("to the right of a top-left finger: $leftSpot", leftSpot.x > 90f + r)
        val right = PointN(1020f, 60f)
        val rightSpot = place(right, PointN(990f, 100f))
        assertClear(rightSpot, right, PointN(990f, 100f))
        assertTrue("to the left of a top-right finger: $rightSpot", rightSpot.x < 990f - r)
    }

    @Test
    fun theLoupeStaysPutWhileTheFingerMovesALittle() {
        val first = place(PointN(500f, 900f), PointN(520f, 940f))
        val second = place(PointN(510f, 905f), PointN(530f, 945f), previous = first)
        assertEquals(first.x, second.x, .01f)
        assertEquals(first.y, second.y, .01f)
    }

    @Test
    fun theLoupeMovesWhenTheFingerReachesIt() {
        val first = place(PointN(500f, 900f), PointN(520f, 940f))
        val finger = PointN(first.x, first.y + 40f)
        val target = PointN(first.x - 10f, first.y)
        val second = place(target, finger, previous = first)
        assertClear(second, target, finger)
    }

    @Test
    fun nudgingFromTheButtonsKeepsTheLoupeOffThePoint() {
        val target = PointN(540f, 700f)
        val spot = place(target, null)
        assertClear(spot, target, null)
        assertTrue("above the point: $spot", spot.y < target.y)
    }

    @Test
    fun aViewSmallerThanTheLoupeStillGivesAPlace() {
        val spot = LoupePlacement.place(200f, 200f, PointN(10f, 10f), PointN(20f, 20f), r, f, m, null)
        assertEquals(100f, spot.x, .01f)
        assertEquals(100f, spot.y, .01f)
    }
}
