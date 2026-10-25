package cn.paperforge.app.pdf

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.*

/** Region-decoded upright rows. No full-height source bitmap is needed. */
@Suppress("DEPRECATION")
internal class OrientedImageRegion(file: File, val width: Int) : AutoCloseable {
    private val decoder =
        BitmapRegionDecoder.newInstance(file.path, false) ?: error("无法读取 ${file.name}")
    private val rawW = decoder.width.toFloat()
    private val rawH = decoder.height.toFloat()
    private val orientation =
        runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
            .getOrDefault(1)
    private val rotated = orientation in 5..8
    private val scale = width / (if (rotated) rawH else rawW)
    val height: Int
    private val transform = Matrix()
    private val inverse = Matrix()
    private var sample = 1

    init {
        val displayW = if (rotated) decoder.height else decoder.width
        val displayH = if (rotated) decoder.width else decoder.height
        val value = (displayH.toLong() * width + displayW - 1) / displayW
        if (value !in 1..Int.MAX_VALUE.toLong()) {
            decoder.recycle()
            error("图片过长，请减小输出宽度")
        }
        height = value.toInt()
        val values =
            when (orientation) {
                2 -> floatArrayOf(-1f, 0f, rawW, 0f, 1f, 0f, 0f, 0f, 1f)
                3 -> floatArrayOf(-1f, 0f, rawW, 0f, -1f, rawH, 0f, 0f, 1f)
                4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, rawH, 0f, 0f, 1f)
                5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                6 -> floatArrayOf(0f, -1f, rawH, 1f, 0f, 0f, 0f, 0f, 1f)
                7 -> floatArrayOf(0f, -1f, rawH, -1f, 0f, rawW, 0f, 0f, 1f)
                8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, rawW, 0f, 0f, 1f)
                else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }
        for (i in 0..5) values[i] *= scale
        transform.setValues(values)
        check(transform.invert(inverse))
        while (sample * 2 * scale <= 1f) sample *= 2
    }

    fun draw(bitmap: Bitmap, target: Rect, sourceRow: Int, shift: Int = 0) {
        val area =
            RectF(
                (target.left - shift).toFloat(),
                sourceRow.toFloat(),
                (target.right - shift).toFloat(),
                (sourceRow + target.height()).toFloat(),
            )
        inverse.mapRect(area)
        val roi =
            Rect(
                (floor(area.left) - 1).toInt().coerceAtLeast(0),
                (floor(area.top) - 1).toInt().coerceAtLeast(0),
                (ceil(area.right) + 1).toInt().coerceAtMost(decoder.width),
                (ceil(area.bottom) + 1).toInt().coerceAtMost(decoder.height),
            )
        if (roi.isEmpty) return
        val region =
            decoder.decodeRegion(
                roi,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: error("无法读取图片分块")
        try {
            val local =
                Matrix().apply {
                    setScale(
                        roi.width().toFloat() / region.width,
                        roi.height().toFloat() / region.height,
                    )
                    postTranslate(roi.left.toFloat(), roi.top.toFloat())
                }
            val matrix =
                Matrix().apply {
                    setConcat(transform, local)
                    postTranslate(shift.toFloat(), (target.top - sourceRow).toFloat())
                }
            Canvas(bitmap).apply {
                save()
                clipRect(target)
                drawBitmap(region, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                restore()
            }
        } finally {
            region.recycle()
        }
    }

    fun band(top: Int, rows: Int): Bitmap {
        val count = rows.coerceIn(1, height - top)
        return Bitmap.createBitmap(width, count, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
            draw(it, Rect(0, 0, width, count), top)
        }
    }

    override fun close() {
        decoder.recycle()
    }
}
