package cn.paperforge.app.imaging

import kotlin.math.*

/**
 * The long edge of an automatic page. the reference scanner keeps a phone photo's page at about 2300-3300 px, and
 * thin print breaks up when a page is made smaller, so the cap is `captureFlatEdge`. Speed never lowers
 * it: a slow phone waits a little longer rather than getting a coarser page. Only the heap can: the
 * tone stage holds several full-page arrays on the Java heap, measured at `captureHeapBytesPerPixel`, and
 * a page uses at most `captureHeapShare` of the heap the system gives the app. Manual HD is unchanged.
 */
internal object AutomaticScanBudget {
    fun edge(): Int = edge(Runtime.getRuntime().maxMemory())

    /** For a heap of [heapBytes]. A page is close to 4:3, so its area is about three quarters of edge². */
    fun edge(heapBytes: Long): Int {
        val cap = ScanConfig.int("captureFlatEdge", 2800)
        // Measured: 248 MB of Java heap at most for a 2800 x 2394 page, about 37 bytes a pixel.
        val perPixel = ScanConfig.int("captureHeapBytesPerPixel", 48).coerceAtLeast(16)
        val share = ScanConfig.float("captureHeapShare", .6f).coerceIn(.1f, .9f)
        val pixels = heapBytes * share / perPixel
        val fits = sqrt(pixels / .75).toInt()
        return min(cap, fits).coerceAtLeast(ScanConfig.int("captureFlatEdgeFloor", 1400))
    }
}
