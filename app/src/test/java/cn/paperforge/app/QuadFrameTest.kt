package cn.paperforge.app

import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #21: a frame-hugging quad is the absence of a crop, not a located page. */
class QuadFrameTest {
    @Test
    fun frameRingIsNotADocument() {
        assertTrue(Quad.Full.hugsFrame())
        val ring =
            Quad(
                listOf(
                    PointN(0.0042f, 0.0167f),
                    PointN(0.9916f, 0.0084f),
                    PointN(0.9958f, 0.9874f),
                    PointN(0.0084f, 0.9916f),
                )
            )
        assertTrue(ring.hugsFrame())
    }

    @Test
    fun realPageInsetIsKept() {
        val page =
            Quad(
                listOf(
                    PointN(0.04f, 0.05f),
                    PointN(0.96f, 0.04f),
                    PointN(0.97f, 0.95f),
                    PointN(0.03f, 0.96f),
                )
            )
        assertFalse(page.hugsFrame())
    }

    @Test
    fun smallQuadIsKept() {
        val ticket =
            Quad(
                listOf(
                    PointN(0.2f, 0.2f),
                    PointN(0.8f, 0.2f),
                    PointN(0.8f, 0.6f),
                    PointN(0.2f, 0.6f),
                )
            )
        assertFalse(ticket.hugsFrame())
    }
}
