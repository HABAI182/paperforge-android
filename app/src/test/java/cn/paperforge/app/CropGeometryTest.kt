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
}
