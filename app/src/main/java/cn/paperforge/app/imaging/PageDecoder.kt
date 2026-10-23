package cn.paperforge.app.imaging

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import cn.paperforge.app.model.*
import java.io.File
import kotlin.math.*

object PageDecoder {
    data class Decoded(
        val bitmap: Bitmap,
        val quad: Quad,
        val plan: ImageGeometry.DecodePlan,
        val aspect: Float,
    )

    @Suppress("DEPRECATION")
    fun crop(file: File, quad: Quad, target: Int): Decoded {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片" }
        val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        val raw = quad.points.map { ImageGeometry.exif(it, orientation, inverse = true) }
        val roi =
            Rect(
                floor(raw.minOf { it.x } * (bounds.outWidth - 1)).toInt().minus(2).coerceAtLeast(0),
                floor(raw.minOf { it.y } * (bounds.outHeight - 1))
                    .toInt()
                    .minus(2)
                    .coerceAtLeast(0),
                ceil(raw.maxOf { it.x } * (bounds.outWidth - 1))
                    .toInt()
                    .plus(3)
                    .coerceAtMost(bounds.outWidth),
                ceil(raw.maxOf { it.y } * (bounds.outHeight - 1))
                    .toInt()
                    .plus(3)
                    .coerceAtMost(bounds.outHeight),
            )
        val uprightWidth = if (orientation in 5..8) bounds.outHeight else bounds.outWidth
        val uprightHeight = if (orientation in 5..8) bounds.outWidth else bounds.outHeight
        val sides = ImageGeometry.pixelSides(quad, uprightWidth, uprightHeight)
        val plan =
            ImageGeometry.decodePlan(
                roi.width(),
                roi.height(),
                max(sides.first, sides.second),
                target,
                ScanConfig.int("maxDecodePixels", 18_000_000).toLong(),
            )
        val decoder = BitmapRegionDecoder.newInstance(file.path, false)
        val decoded =
            try {
                decoder.decodeRegion(
                    roi,
                    BitmapFactory.Options().apply {
                        inSampleSize = plan.sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                ) ?: error("图片区域解码失败")
            } finally {
                decoder.recycle()
            }
        val image = orient(decoded, orientation)
        val mapped =
            raw.map { p ->
                ImageGeometry.exif(
                    PointN(
                        ((p.x * (bounds.outWidth - 1) - roi.left) / (roi.width() - 1)).coerceIn(
                            0f,
                            1f,
                        ),
                        ((p.y * (bounds.outHeight - 1) - roi.top) / (roi.height() - 1)).coerceIn(
                            0f,
                            1f,
                        ),
                    ),
                    orientation,
                )
            }
        // The crop ROI shifts the principal point. Estimate physical aspect in the full image
        // frame.
        return Decoded(
            image,
            Quad(mapped),
            plan,
            ImageGeometry.aspect(quad, uprightWidth, uprightHeight),
        )
    }

    fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            6 -> matrix.setRotate(90f)
            7 -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            8 -> matrix.setRotate(270f)
        }
        val rotated =
            if (matrix.isIdentity) bitmap
            else
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                    if (it !== bitmap) bitmap.recycle()
                }
        if (!rotated.hasAlpha()) return rotated
        return Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888).also {
            opaque ->
            Canvas(opaque).apply {
                drawColor(Color.WHITE)
                drawBitmap(rotated, 0f, 0f, null)
            }
            opaque.setHasAlpha(false)
            rotated.recycle()
        }
    }
}
