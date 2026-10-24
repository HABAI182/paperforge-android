package cn.paperforge.app.model

import kotlin.math.abs

data class EdgeTurnResult(val arm: Int, val flip: Int)

object EdgeTurnPolicy {
    /** Reaching an edge arms it; only a separate outward gesture can turn the page. */
    fun release(
        zoom: Float,
        startX: Float,
        endX: Float,
        dragX: Float,
        dragY: Float,
        armed: Int,
        repeated: Boolean,
        minimumDrag: Float,
        multiplePointers: Boolean,
    ): EdgeTurnResult {
        if (
            zoom <= 1.01f ||
                multiplePointers ||
                abs(dragX) < minimumDrag ||
                abs(dragX) < abs(dragY) * 1.4f
        )
            return EdgeTurnResult(0, 0)
        val bound = (zoom - 1f) / 2f
        val direction =
            when {
                dragX < 0 && abs(endX + bound) < .01f -> 1
                dragX > 0 && abs(endX - bound) < .01f -> -1
                else -> 0
            }
        if (direction == 0) return EdgeTurnResult(0, 0)
        val startedAtEdge =
            if (direction == 1) abs(startX + bound) < .01f else abs(startX - bound) < .01f
        return if (startedAtEdge && armed == direction && repeated) EdgeTurnResult(0, direction)
        else EdgeTurnResult(direction, 0)
    }
}
