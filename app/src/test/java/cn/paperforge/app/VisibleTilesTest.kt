package cn.paperforge.app

import cn.paperforge.app.model.VisibleTiles
import org.junit.Assert.*
import org.junit.Test

class VisibleTilesTest {
    @Test
    fun zoomedViewportRequestsOnlyAFewTilesAndPansToCorrectEdges() {
        assertTrue(VisibleTiles.plan(595, 842, 1f, 0f, 0f).isEmpty())
        val middle = VisibleTiles.plan(595, 842, 5f, 0f, 0f)
        assertTrue(middle.size in 1..20)
        assertTrue(middle.all { it.edge == 9600 })
        val upperLeft = VisibleTiles.plan(595, 842, 5f, 2f, 2f)
        val lowerRight = VisibleTiles.plan(595, 842, 5f, -2f, -2f)
        assertEquals(0, upperLeft.minOf { it.column })
        assertEquals(0, upperLeft.minOf { it.row })
        assertTrue(lowerRight.minOf { it.column } > upperLeft.maxOf { it.column })
        assertTrue(lowerRight.minOf { it.row } > upperLeft.maxOf { it.row })
        assertTrue(lowerRight.all { it.column in 0..13 && it.row in 0..18 })
    }
}
