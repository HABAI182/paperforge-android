package cn.paperforge.app

import cn.paperforge.app.model.*
import org.junit.Assert.*
import org.junit.Test

class ReadingFrameTest {
    @Test
    fun landscapeUsesTheWholeViewportForZoomAndMapsBackToSource() {
        val f = ReadingFrame.fit(1000f, 500f, .75f, false)
        assertEquals(375f, f.pageWidth, .01f)
        assertEquals(0f, f.limitX(2f), .01f)
        assertEquals((375f * 4 - 1000) / 750, f.limitX(4f), .01f)
        val source = PageViewport(.1f, .2f, .9f, .8f)
        val point = PointN(.3f, .6f)
        val v = source.view(point)
        val x = f.viewportWidth / 2 + (.2f + (v.x - .5f) * 4) * f.pageWidth
        val y = f.viewportHeight / 2 + (-.3f + (v.y - .5f) * 4) * f.pageHeight
        val inverse = source.source(f.point(x, y, 4f, .2f, -.3f))
        assertEquals(point.x, inverse.x, .0001f)
        assertEquals(point.y, inverse.y, .0001f)
        val width = ReadingFrame.fit(1000f, 500f, .75f, true)
        assertTrue(width.limitY(1f) > 0)
        assertEquals(0f, width.limitX(1f), .0001f)
    }

    @Test
    fun croppedTilesUseOriginalPageCoordinatesAndOnlyTheVisibleArea() {
        val crop = PageViewport(.2f, .1f, .8f, .9f)
        val tiles = VisibleTiles.plan(600, 800, 4f, 0f, 0f, crop, 1.5f, 1f)
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { it.column > 0 && it.row > 0 })
        val full = VisibleTiles.plan(600, 800, 4f, 0f, 0f, crop, 4f, 4f)
        assertTrue(tiles.size < full.size)
        assertEquals(12800, tiles.first().edge) // Crop and zoom need a finer source raster.
    }
}
