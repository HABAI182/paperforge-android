package cn.paperforge.app.imaging

import android.graphics.Bitmap
import cn.paperforge.app.data.PageRow

/**
 * Enhanced-page bitmap cache for the editor canvas (v200 feedback 5: "zooming reloads the filter").
 *
 * Before this cache the canvas asked for `edge = 1800` at fit size and `edge = 2800` above 1.5x
 * zoom. Because the preview key included that edge, every zoom step across the threshold re-ran the
 * whole pipeline (decode, crop, warp, WORKBOOK/CLEAN enhancement) and the canvas showed "正在应用…",
 * which the owner read as the filter being re-applied and as flicker.
 *
 * The cache is keyed by the identity of the *image*, not of the viewport:
 * `(pageId, revision, filter, enhanceStrength, rotation)` plus the requested render edge. Zoom only
 * changes a `graphicsLayer` transform, so it can never invalidate an entry.
 *
 * Memory and eviction:
 * - The canvas always renders one fixed edge ([edge] = 1800 px long side), about 8-11 MB per page.
 * - [maximumBytes] = 32 MB, access-order LRU: at most three pages are retained, the least recently
 *   drawn is dropped first.
 * - Evicted bitmaps are only dropped from the cache, never `Bitmap.recycle()`-d: a frame may still
 *   be drawing the previous image, and API 30+ bitmaps are reclaimed by the garbage collector.
 *   [clear] exists for tests and for explicit teardown; it also does not recycle.
 * - The cache is process-wide but only the editor canvas reads it, so a page edit invalidates by
 *   `revision` rather than by clearing everything.
 */
object EnhancedPreviewCache {
    /** Long side used for every editor canvas render; zoom never changes it. */
    const val edge = 1800

    /** Upper bound for retained enhanced bitmaps. */
    const val maximumBytes = 32L * 1024 * 1024

    private data class Key(
        val pageId: String,
        val revision: Int,
        val filter: String,
        val strength: Float,
        val rotation: Int,
    )

    private val entries = LinkedHashMap<Key, Bitmap>(0, .75f, true)
    private var retainedBytes = 0L
    private var hits = 0
    private var misses = 0
    private var evictions = 0

    private fun key(page: PageRow) =
        Key(page.id, page.revision, page.filter, page.enhanceStrength, page.rotation)

    @Synchronized
    fun peek(page: PageRow): Bitmap? {
        val cached = entries[key(page)]
        if (cached != null) hits++ else misses++
        return cached
    }

    /**
     * Returns the enhanced bitmap for [page], reusing the cached render when the page identity has
     * not changed. The caller must not recycle the returned bitmap.
     */
    fun obtain(page: PageRow): Bitmap {
        peek(page)?.let { return it }
        val rendered = ScanProcessor.render(page, edge)
        synchronized(this) {
            val existing = entries[key(page)]
            if (existing != null) {
                // A concurrent request finished first; keep one bitmap and drop ours to the GC.
                return existing
            }
            entries[key(page)] = rendered
            retainedBytes += sizeOf(rendered)
            trimLocked()
            return rendered
        }
    }

    private fun sizeOf(bitmap: Bitmap): Long = bitmap.byteCount.toLong()

    private fun trimLocked() {
        val iterator = entries.entries.iterator()
        while (retainedBytes > maximumBytes && iterator.hasNext()) {
            val entry = iterator.next()
            retainedBytes -= sizeOf(entry.value)
            iterator.remove()
            evictions++
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        retainedBytes = 0
    }

    @Synchronized
    fun snapshot(): Snapshot =
        Snapshot(entries.size, retainedBytes, hits, misses, evictions, edge, maximumBytes)

    @Synchronized
    fun resetCounters() {
        hits = 0
        misses = 0
        evictions = 0
    }

    data class Snapshot(
        val size: Int,
        val retainedBytes: Long,
        val hits: Int,
        val misses: Int,
        val evictions: Int,
        val edge: Int,
        val maximumBytes: Long,
    )
}
