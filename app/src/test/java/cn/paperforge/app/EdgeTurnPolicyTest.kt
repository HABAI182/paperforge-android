package cn.paperforge.app

import cn.paperforge.app.model.EdgeTurnPolicy
import org.junit.Assert.*
import org.junit.Test

class EdgeTurnPolicyTest {
    @Test
    fun firstDragOnlyArmsAndSecondSeparateOutwardDragTurns() {
        val first = EdgeTurnPolicy.release(2f, 0f, -.5f, -200f, 4f, 0, false, 80f, false)
        assertEquals(1, first.arm)
        assertEquals(0, first.flip)
        val second = EdgeTurnPolicy.release(2f, -.5f, -.5f, -180f, 2f, first.arm, true, 80f, false)
        assertEquals(1, second.flip)
        assertEquals(
            0,
            EdgeTurnPolicy.release(2f, -.5f, -.5f, -180f, 2f, 1, false, 80f, false).flip,
        )
    }

    @Test
    fun PinchVerticalMovementAndMovingAwayNeverTurn() {
        assertEquals(0, EdgeTurnPolicy.release(2f, -.5f, -.5f, -180f, 2f, 1, true, 80f, true).flip)
        assertEquals(
            0,
            EdgeTurnPolicy.release(2f, -.5f, -.5f, -180f, 220f, 1, true, 80f, false).flip,
        )
        assertEquals(0, EdgeTurnPolicy.release(2f, -.5f, 0f, 180f, 2f, 1, true, 80f, false).flip)
    }
}
