package cn.paperforge.app.imaging

import android.graphics.Bitmap
import cn.paperforge.app.data.PageResources
import cn.paperforge.app.data.PageRow
import java.io.File

/**
 * A least-recently-used map bounded by the summed [sizeOf] of its values, not by their count. Values pushed out are only
 * dropped, never closed or recycled: a frame may still be drawing one.
 */
internal class BoundedLru<K : Any, V : Any>(private val maximum: Long, private val sizeOf: (V) -> Long) {
    private val entries = LinkedHashMap<K, V>(0, .75f, true)
    var retained = 0L
        private set

    @Synchronized fun get(key: K): V? = entries[key]

    /** Stores [value] unless an entry for [key] arrived first, and returns the one kept. */
    @Synchronized
    fun putIfAbsent(key: K, value: V): V {
        entries[key]?.let { return it }
        val size = sizeOf(value)
        if (size > maximum) return value
        entries[key] = value
        retained += size
        val iterator = entries.entries.iterator()
        while (retained > maximum && iterator.hasNext()) {
            val eldest = iterator.next()
            retained -= sizeOf(eldest.value)
            iterator.remove()
        }
        return value
    }

    @Synchronized fun size(): Int = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
        retained = 0
    }
}

/**
 * Small page thumbnails (page grid, editor strip, filter picker, capture review: long side at most [maximumEdge]).
 * Before this cache each visible card ran the page pipeline again whenever it scrolled back into view, since the
 * composable recycled its bitmap on leaving.
 *
 * The key is everything the render reads:
 * - the whole page row (corners, filter, strength, rotation, dewarp, metadata and resource paths);
 * - the requested edge and whether the plain photo was asked for;
 * - [ScanConfig.fingerprint], since enhancement settings change the look;
 * - size and modification time of the photo and of every resource file the page lists.
 *
 * A changed input is a new key, so a stale thumbnail is never shown. The bitmaps are the same the render gave before;
 * only repeat work goes. About [maximumBytes] are kept (a 360 px grid card is about 0.4 MB).
 */
object ThumbnailCache {
    const val maximumEdge = 640
    const val maximumBytes = 16L * 1024 * 1024

    private val entries = BoundedLru<Key, Bitmap>(maximumBytes) { it.byteCount.toLong() }

    private data class Key(
        val page: PageRow,
        val edge: Int,
        val original: Boolean,
        val config: String,
        val files: List<Long>,
    )

    private fun stamp(path: String): List<Long> =
        File(path).let { listOf(it.length(), it.lastModified()) }

    private fun key(page: PageRow, edge: Int, original: Boolean): Key {
        val resources = runCatching { PageResources.entries(page).map { it.path } }.getOrDefault(emptyList())
        return Key(page, edge, original, ScanConfig.fingerprint(), (listOf(page.original) + resources).flatMap(::stamp))
    }

    /** The thumbnail for [page], from the cache or made by [render]. The caller must not recycle it. */
    fun obtain(page: PageRow, edge: Int, original: Boolean, render: () -> Bitmap): Bitmap {
        val key = key(page, edge, original)
        entries.get(key)?.let { return it }
        return entries.putIfAbsent(key, render())
    }

    fun clear() = entries.clear()
}
