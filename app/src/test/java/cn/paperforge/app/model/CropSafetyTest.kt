package cn.paperforge.app.model

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropSafetyTest {
    private fun quad(vararg xy: Float) = Quad(xy.toList().chunked(2).map { (x, y) -> PointN(x, y) })

    /** Independent containment check, so the production helper is not trusted for its own test. */
    private fun inside(outer: Quad, p: PointN): Boolean =
        (0..3).all { i ->
            val a = outer.points[i]
            val b = outer.points[(i + 1) % 4]
            (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x) >= -1e-5f
        }

    private val receipt = quad(.3863f, .3122f, .5722f, .2834f, .6474f, .6714f, .4477f, .6781f)

    @Test
    fun fullFrameQuadIsAlreadyAtTheFrame() {
        assertEquals(Quad.Full, CropSafety.expand(Quad.Full, 3024, 4032))
    }

    @Test
    fun receiptGrowsOutwardWithoutLeavingTheFrame() {
        val expanded = CropSafety.expand(receipt, 3024, 4032)
        assertTrue(expanded.valid())
        expanded.points.forEach { assertTrue(it.x in 0f..1f && it.y in 0f..1f) }
        assertTrue(expanded.area > receipt.area)
        receipt.points.forEach { assertTrue(inside(expanded, it)) }
        // 1% of the 3024 px short side is ~30 px; corners move out by at least that much on one
        // axis, and by less than the sum of two side offsets.
        receipt.points.zip(expanded.points).forEach { (old, new) ->
            val moved = hypot((old.x - new.x) * 3024f, (old.y - new.y) * 4032f)
            assertTrue("corner moved $moved px", moved in 10f..70f)
        }
    }

    @Test
    fun convexityAndCornerOrderSurviveSkew() {
        val skewed = quad(.10f, .18f, .78f, .09f, .92f, .83f, .22f, .91f)
        val expanded = CropSafety.expand(skewed, 1350, 1800)
        assertTrue(expanded.valid())
        assertTrue(expanded.area > skewed.area)
    }

    @Test
    fun borderTouchingQuadNeverCropsInsideTheDetectedArea() {
        val touching = quad(0f, .30f, .55f, .28f, .60f, .90f, 0f, .92f)
        val expanded = CropSafety.expand(touching, 3024, 4032)
        assertTrue(expanded.valid())
        touching.points.forEach { assertTrue(inside(expanded, it)) }
        expanded.points.forEach { assertTrue(it.x in 0f..1f && it.y in 0f..1f) }
    }

    @Test
    fun minimumAreaQuadStaysValid() {
        val small = quad(.30f, .30f, .44f, .30f, .44f, .445f, .30f, .445f)
        assertTrue(small.valid())
        val expanded = CropSafety.expand(small, 1350, 1800)
        assertTrue(expanded.valid())
        assertTrue(expanded.area > small.area)
    }

    @Test
    fun zeroOrDegenerateInputIsReturnedUnchanged() {
        assertEquals(receipt, CropSafety.expand(receipt, 1350, 1800, 0f))
        assertEquals(receipt, CropSafety.expand(receipt, 0, 1800))
        assertEquals(receipt, CropSafety.expand(receipt, 1350, 1))
    }

    @Test
    fun randomConvexQuadsStayValidClampedAndContaining() {
        val random = Random(20261031)
        var checked = 0
        repeat(400) {
            val points =
                List(4) {
                    PointN(.02f + random.nextFloat() * .96f, .02f + random.nextFloat() * .96f)
                }
            val ordered = runCatching { Quad.ordered(points) }.getOrNull() ?: return@repeat
            if (!ordered.valid()) return@repeat
            checked++
            val expanded = CropSafety.expand(ordered, 3024, 4032)
            assertTrue("expanded must stay a valid quad", expanded.valid())
            ordered.points.forEach {
                assertTrue("original corner must stay inside", inside(expanded, it))
            }
            expanded.points.forEach {
                assertTrue("clamped to frame", it.x in 0f..1f && it.y in 0f..1f)
            }
            assertTrue(expanded.area + 1e-6f >= ordered.area)
            // Adjacent corners keep their cyclic order: no self-intersection.
            (0..3).forEach { i ->
                val a = expanded.points[i]
                val b = expanded.points[(i + 1) % 4]
                val c = expanded.points[(i + 2) % 4]
                val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
                assertTrue("corner $i must be convex, cross=$cross", cross > 0f)
            }
            assertTrue(abs(expanded.area - ordered.area) < .5f)
        }
        assertTrue("expected a usable sample of valid quads, got $checked", checked > 200)
    }
}
