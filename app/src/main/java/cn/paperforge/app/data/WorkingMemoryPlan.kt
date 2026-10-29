package cn.paperforge.app.data

import kotlin.math.*

/** Estimated image working buffers, independent of total process RSS or native Office memory. */
data class WorkingMemoryPlan(val requestedMb: Int, val bytes: Long) {
    data class Size(val width: Int, val height: Int, val limited: Boolean)

    fun pixels(bytesPerPixel: Int = 12): Long = (bytes / bytesPerPixel).coerceIn(262144, 18_000_000)

    fun size(width: Int, height: Int, bytesPerPixel: Int = 12): Size {
        require(width > 0 && height > 0)
        val scale =
            minOf(
                1.0,
                sqrt(pixels(bytesPerPixel).toDouble() / width / height),
                16384.0 / max(width, height),
            )
        val w = max(1, floor(width * scale).toInt())
        val h = max(1, floor(height * scale).toInt())
        return Size(w, h, w != width || h != height)
    }

    companion object {
        fun create(requestedMb: Int, heapBytes: Long): WorkingMemoryPlan {
            val requested = requestedMb.coerceIn(32, 384)
            return WorkingMemoryPlan(
                requested,
                minOf(requested * 1048576L, (heapBytes / 2).coerceAtLeast(16 * 1048576L)),
            )
        }
    }
}
