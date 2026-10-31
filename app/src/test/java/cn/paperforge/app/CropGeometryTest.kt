package cn.paperforge.app

import cn.paperforge.app.model.*
import org.junit.Assert.*
import org.junit.Test

class CropGeometryTest {
    @Test
    fun edgeMovesAsAUnitAndNeverCrossesImageBounds() {
        val q = Quad(listOf(PointN(.1f, .1f), PointN(.9f, .1f), PointN(.9f, .9f), PointN(.1f, .9f)))
        val moved = CropGeometry.moveEdge(q, 0, .05f, .15f)
        assertEquals(.15f, moved.points[0].x, .0001f)
        assertEquals(.25f, moved.points[0].y, .0001f)
        assertEquals(.95f, moved.points[1].x, .0001f)
        assertEquals(q.points[2], moved.points[2])
        assertTrue(
            CropGeometry.moveEdge(q, 0, 5f, -.5f).points.all { it.x in 0f..1f && it.y in 0f..1f }
        )
        assertEquals(q, CropGeometry.moveEdge(q, 0, 0f, .95f))
    }

    @Test
    fun asymmetricSpinePartitionsTheOriginalWithoutDroppingAnArea() {
        val (left, right) = CropGeometry.split(Quad.Full, .35f, .55f)
        assertTrue(left.valid())
        assertTrue(right.valid())
        assertEquals(.35f, left.points[1].x, .0001f)
        assertEquals(.55f, left.points[2].x, .0001f)
        assertEquals(left.points[1], right.points[0])
        assertEquals(left.points[2], right.points[3])
        assertEquals(1f, left.area + right.area, .0001f)
    }

    /** #23 follow-up: each half ends on its own side of the gutter, so the band is dropped. */
    @Test
    fun twoBoundariesTrimEachHalfToItsOwnSideOfTheGutter() {
        val (left, right) = CropGeometry.split(Quad.Full, .4523f, .4523f, .4676f, .4676f)
        assertTrue(left.valid())
        assertTrue(right.valid())
        assertEquals(.4523f, left.points[1].x, .0001f)
        assertEquals(.4523f, left.points[2].x, .0001f)
        assertEquals(.4676f, right.points[0].x, .0001f)
        assertEquals(.4676f, right.points[3].x, .0001f)
        assertTrue(
            "两页内侧之间应留出中缝: ${left.points[1].x} / ${right.points[0].x}",
            right.points[0].x > left.points[1].x,
        )
        // Only the gutter itself is dropped; no page area outside it is lost.
        assertEquals(1f - (.4676f - .4523f), left.area + right.area, .0001f)
    }

    /** A single line must behave exactly as before: both call shapes agree. */
    @Test
    fun singleLineSplitIsIdenticalToPassingTheSameEdgeTwice() {
        assertEquals(
            CropGeometry.split(Quad.Full, .35f, .55f),
            CropGeometry.split(Quad.Full, .35f, .55f, .35f, .55f),
        )
        assertEquals(
            CropGeometry.split(Quad.Full, .5f, .5f),
            CropGeometry.split(Quad.Full, .5f, .5f, .5f, .5f),
        )
    }

    @Test
    fun fractionsOutsideThePageAreClampedToTheSingleLineLimits() {
        val (left, right) = CropGeometry.split(Quad.Full, -.5f, 2f, -.2f, 1.5f)
        assertEquals(.15f, left.points[1].x, .0001f)
        assertEquals(.85f, left.points[2].x, .0001f)
        assertEquals(.15f, right.points[0].x, .0001f)
        assertEquals(.85f, right.points[3].x, .0001f)
        assertTrue(left.valid())
        assertTrue(right.valid())
    }

    @Test
    fun crossedBoundariesAreRejectedInsteadOfProducingOverlappingPages() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                CropGeometry.split(Quad.Full, .6f, .55f, .4f, .45f)
            }
        assertTrue("应说明边界方向: ${error.message}", error.message.orEmpty().contains("书脊"))
    }
}
