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

    /**
     * Upright pixel size of [file] without decoding pixels, or null when the header cannot be read.
     * Callers use it to convert `CropSafety`'s pixel ring back into a normalized offset.
     */
    fun uprightSize(file: File): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        return if (orientation in 5..8) bounds.outHeight to bounds.outWidth
        else bounds.outWidth to bounds.outHeight
    }

    /**
     * The lens's focal length as a share of the image diagonal, from the 35 mm equivalent in [file]'s EXIF
     * (a 35 mm frame's diagonal is 43.27 mm), or null when the photo does not record it, as a copy sent
     * through a chat app does not. It tells the page's true proportions from a photo taken at a slant.
     */
    fun focalShare(file: File): Float? =
        runCatching {
                ExifInterface(file).getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                    .takeIf { it in 8..300 }
                    ?.let { it / 43.27f }
            }
            .getOrNull()

    /**
     * A PNG is shrunk while decoding by keeping every n-th pixel, so a finished page decoded small for a
     * thumbnail kept a thin line only where a kept row fell on it: rules came out dotted and writing as specks.
     * A JPEG is shrunk by averaging and needs no cap. The rest of the way is an area resize.
     */
    fun pngSample(sample: Int) = sample.coerceAtMost(2)

    @Suppress("DEPRECATION")
    fun crop(file: File, quad: Quad, target: Int): Decoded {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片" }
        val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        val uprightWidth = if (orientation in 5..8) bounds.outHeight else bounds.outWidth
        val uprightHeight = if (orientation in 5..8) bounds.outWidth else bounds.outHeight
        // Render the visible crop exactly by default. An automatic outer ring used to bring
        // desk pixels back into an otherwise correct paper boundary. Explicit legacy margins
        // remain available to benchmark configurations; manual expansion stays in CropDialog.
        val crop =
            CropSafety.expand(
                quad,
                uprightWidth,
                uprightHeight,
                ScanConfig.float("cropSafetyMargin", CropSafety.Margin),
            )
        val raw = crop.points.map { ImageGeometry.exif(it, orientation, inverse = true) }
        // Keep a ring of real pixels outside the quad so ContentCrop can push a side outward when
        // ink touches the frame instead of clipping it. The ring is not part of the output page.
        val ring = (max(uprightWidth, uprightHeight) * .015f).roundToInt().coerceIn(4, 32)
        val roi =
            Rect(
                floor(raw.minOf { it.x } * (bounds.outWidth - 1))
                    .toInt()
                    .minus(2 + ring)
                    .coerceAtLeast(0),
                floor(raw.minOf { it.y } * (bounds.outHeight - 1))
                    .toInt()
                    .minus(2 + ring)
                    .coerceAtLeast(0),
                ceil(raw.maxOf { it.x } * (bounds.outWidth - 1))
                    .toInt()
                    .plus(3 + ring)
                    .coerceAtMost(bounds.outWidth),
                ceil(raw.maxOf { it.y } * (bounds.outHeight - 1))
                    .toInt()
                    .plus(3 + ring)
                    .coerceAtMost(bounds.outHeight),
            )
        val sides = ImageGeometry.pixelSides(crop, uprightWidth, uprightHeight)
        val plan =
            ImageGeometry.decodePlan(
                    roi.width(),
                    roi.height(),
                    max(sides.first, sides.second),
                    target,
                    ScanConfig.int("maxDecodePixels", 18_000_000).toLong(),
                )
                .let { if (bounds.outMimeType == "image/png") it.copy(sample = pngSample(it.sample)) else it }
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
            ImageGeometry.aspect(quad, uprightWidth, uprightHeight, focalShare(file)),
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
