package cn.paperforge.app

import cn.paperforge.app.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DewarpGridTest {
    @Test
    fun compressedGridRetainsTheOutputToInputMappingAndStrength() {
        val values = floatArrayOf(.1f, .05f, .9f, .1f, .05f, .95f, .95f, .9f)
        val grid = DewarpGrid(800, 1000, .7f, 2, values)
        val file = File.createTempFile("paperforge-grid", ".gz").apply { check(delete()) }
        try {
            grid.write(file)
            val read = DewarpGrid.read(file)
            assertArrayEquals(values, read.values, 0f)
            assertEquals(.7f, read.strength, 0f)
            assertEquals(grid.inputPoint(PointN(.2f, .6f)), read.inputPoint(PointN(.2f, .6f)))
            val identity = grid.copy(strength = 0f)
            assertEquals(PointN(.3f, .7f), identity.inputPoint(PointN(.3f, .7f)))
            assertEquals(.07f, grid.inputPoint(PointN(0f, 0f)).x, .00001f)
        } finally {
            file.delete()
        }
    }
}
