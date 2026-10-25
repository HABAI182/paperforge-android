package cn.paperforge.app.pdf

import android.graphics.*
import java.io.File
import kotlin.math.roundToInt

object StitchPreview {
    fun render(previous: File, current: File, seam: StitchSeam, overlay: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(360, 320, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        try {
            OrientedImageRegion(previous, 256).use { a ->
                OrientedImageRegion(current, 256).use { b ->
                    val canvas = Canvas(bitmap)
                    val base = 52f
                    val previousRows = minOf(160, a.height)
                    val before = a.band(a.height - previousRows, previousRows)
                    try {
                        canvas.drawBitmap(before, base, (160 - previousRows).toFloat(), null)
                    } finally {
                        before.recycle()
                    }
                    val start = (seam.overlap * b.height).roundToInt().coerceIn(0, b.height - 1)
                    val x = base + seam.shift * 256
                    if (overlay && start > 0) {
                        val overlapRows = minOf(160, start)
                        val overlap = b.band(start - overlapRows, overlapRows)
                        try {
                            canvas.drawBitmap(
                                overlap,
                                x,
                                (160 - overlapRows).toFloat(),
                                Paint().apply { alpha = 128 },
                            )
                        } finally {
                            overlap.recycle()
                        }
                    }
                    val after = b.band(start, minOf(160, b.height - start))
                    try {
                        canvas.drawBitmap(after, x, 160f, null)
                    } finally {
                        after.recycle()
                    }
                    canvas.drawLine(
                        0f,
                        160f,
                        360f,
                        160f,
                        Paint().apply {
                            color = Color.rgb(18, 130, 100)
                            strokeWidth = 1f
                        },
                    )
                }
            }
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }
}
