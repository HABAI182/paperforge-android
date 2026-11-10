package cn.paperforge.app.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Where the crop loupe goes. The system magnifier sat a fixed ~42 dp above the touch, which a fingertip covers, and
 * it slid off the view at the top corners. The loupe is placed instead:
 * - clear of the fingertip and of the hand below it,
 * - never over the point being placed (that is what it magnifies),
 * - fully inside the view,
 * - preferably above, then beside, never below the finger,
 * - and where it already is while that spot still works, so it does not hop around as the finger moves.
 *
 * All values are view pixels. [finger] is null while the point is moved from the nudge buttons.
 */
object LoupePlacement {
    fun place(
        width: Float,
        height: Float,
        target: PointN,
        finger: PointN?,
        radius: Float,
        fingerRadius: Float,
        margin: Float,
        previous: PointN?,
    ): PointN {
        val lowX = radius + margin
        val highX = width - radius - margin
        val lowY = radius + margin
        val highY = height - radius - margin
        if (lowX > highX || lowY > highY) return PointN(width / 2, height / 2)
        val origin = finger ?: target
        val keepFinger = if (finger != null) radius + fingerRadius else 0f
        val keepTarget = radius + margin * 2
        fun clear(x: Float, y: Float): Boolean {
            if (hypot(x - target.x, y - target.y) < keepTarget) return false
            if (finger == null) return true
            if (hypot(x - finger.x, y - finger.y) < keepFinger) return false
            // The hand: a band from the fingertip down to the bottom of the view.
            val nearX = x.coerceIn(finger.x - fingerRadius * 1.3f, finger.x + fingerRadius * 1.3f)
            val nearY = y.coerceIn(finger.y, height + radius)
            return hypot(x - nearX, y - nearY) >= radius
        }
        val reach = max(keepFinger, keepTarget) + margin
        fun cost(x: Float, y: Float): Float {
            // 0 straight up from the finger, 1 straight down.
            val turn = abs(atan2((x - origin.x).toDouble(), (origin.y - y).toDouble())).toFloat() / PI.toFloat()
            val far = (hypot(x - origin.x, y - origin.y) - reach).coerceAtLeast(0f) / reach
            return turn + far * .3f
        }
        var best: PointN? = null
        var bestCost = Float.MAX_VALUE
        previous?.let {
            val x = it.x.coerceIn(lowX, highX)
            val y = it.y.coerceIn(lowY, highY)
            if (clear(x, y)) {
                best = PointN(x, y)
                bestCost = cost(x, y) - .35f
            }
        }
        for (step in 0 until 16) {
            val angle = -PI / 2 + step * PI / 8
            for (scale in floatArrayOf(1f, 1.5f, 2.2f)) {
                val x = (origin.x + cos(angle) * reach * scale).toFloat().coerceIn(lowX, highX)
                val y = (origin.y + sin(angle) * reach * scale).toFloat().coerceIn(lowY, highY)
                if (!clear(x, y)) continue
                val c = cost(x, y)
                if (c < bestCost) {
                    bestCost = c
                    best = PointN(x, y)
                }
            }
        }
        best?.let { return it }
        // Nothing is fully clear (a very small view): the view corner farthest from the finger and the point.
        return listOf(PointN(lowX, lowY), PointN(highX, lowY), PointN(lowX, highY), PointN(highX, highY)).maxBy {
            minOf(hypot(it.x - origin.x, it.y - origin.y), hypot(it.x - target.x, it.y - target.y))
        }
    }
}
