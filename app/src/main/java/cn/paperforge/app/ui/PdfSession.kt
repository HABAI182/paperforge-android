package cn.paperforge.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import kotlin.math.ceil

class PdfSession(file: File) : AutoCloseable {
    private val sizes = mutableMapOf<Int, Pair<Int, Int>>()
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer =
        try {
            PdfRenderer(fd)
        } catch (error: Throwable) {
            fd.close()
            throw error
        }
    val count = renderer.pageCount
    private var closed = false
    private val cache =
        object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }

    @Synchronized
    fun render(n: Int, edge: Int = 1600): Bitmap {
        check(!closed) { "阅读会话已关闭" }
        val key = "$n:$edge"
        return cache.get(key)
            ?: renderer.openPage(n).use { page ->
                sizes[n] = page.width to page.height
                val scale = edge.toFloat() / maxOf(page.width, page.height)
                Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    .also {
                        it.eraseColor(AndroidColor.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(key, it)
                    }
            }
    }

    @Synchronized
    fun dimensions(n: Int): Pair<Int, Int> {
        check(!closed)
        return sizes[n]
            ?: renderer.openPage(n).use { page ->
                (page.width to page.height).also { sizes[n] = it }
            }
    }

    @Synchronized
    fun tile(n: Int, edge: Int, column: Int, row: Int): PdfTile {
        check(!closed)
        val (width, height) = dimensions(n)
        val scale = edge.toFloat() / maxOf(width, height)
        val virtualWidth = ceil(width * scale).toInt()
        val virtualHeight = ceil(height * scale).toInt()
        val left = column * 512
        val top = row * 512
        require(left in 0 until virtualWidth && top in 0 until virtualHeight)
        val key = "$n:tile:$edge:$column:$row"
        val bitmap =
            cache.get(key)
                ?: renderer.openPage(n).use { page ->
                    val result =
                        Bitmap.createBitmap(
                            minOf(512, virtualWidth - left),
                            minOf(512, virtualHeight - top),
                            Bitmap.Config.ARGB_8888,
                        )
                    result.eraseColor(AndroidColor.WHITE)
                    val matrix =
                        Matrix().apply {
                            setScale(scale, scale)
                            postTranslate(-left.toFloat(), -top.toFloat())
                        }
                    page.render(result, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, result)
                    result
                }
        return PdfTile(edge, left, top, virtualWidth, virtualHeight, bitmap)
    }

    @Synchronized fun cachedBytes(): Int = cache.size()

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cache.evictAll()
        renderer.close()
        fd.close()
    }
}

data class PdfTile(
    val edge: Int,
    val left: Int,
    val top: Int,
    val virtualWidth: Int,
    val virtualHeight: Int,
    val bitmap: Bitmap,
)
