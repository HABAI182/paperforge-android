package cn.paperforge.app.imaging

import org.opencv.core.Core

/**
 * OpenCV's thread count, which is one setting for the whole process. The app keeps it at [BASE] so a camera
 * preview and the interface stay smooth. A page finish (render, flatten, tone) is one job at a time, though, and
 * on a phone with six or more cores it may use `finishOpenCvThreads` (4) while it runs. OpenCV's per-pixel
 * operations give the same pixels on any number of threads, so only the time changes.
 */
internal object OpenCvThreads {
    const val BASE = 2
    private var active = 0

    fun initialize() = Core.setNumThreads(BASE)

    /** [block] with the finish's thread count; nested and overlapping finishes share one raise. */
    inline fun <T> finishing(block: () -> T): T {
        enter()
        try {
            return block()
        } finally {
            exit()
        }
    }

    private fun finishThreads(): Int {
        val wanted = ScanConfig.int("finishOpenCvThreads", 4)
        return if (Runtime.getRuntime().availableProcessors() >= 6 && wanted > BASE) wanted else BASE
    }

    @PublishedApi
    @Synchronized
    internal fun enter() {
        if (active++ == 0) finishThreads().let { if (it != BASE) Core.setNumThreads(it) }
    }

    @PublishedApi
    @Synchronized
    internal fun exit() {
        if (--active == 0) Core.setNumThreads(BASE)
    }
}
