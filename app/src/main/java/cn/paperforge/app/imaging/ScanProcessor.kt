package cn.paperforge.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.Quad
import java.io.File
import kotlin.math.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object ScanProcessor {
    val filters = listOf("ORIGINAL", "AUTO", "CLEAN", "GRAY", "BW", "COLOR", "WHITEBOARD")

    fun decode(file: File, maxEdge: Int = 2400): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片" }
        var sample = 1
        sample =
            cn.paperforge.app.model.ImageGeometry.decodePlan(
                    bounds.outWidth,
                    bounds.outHeight,
                    max(bounds.outWidth, bounds.outHeight).toFloat(),
                    maxEdge,
                    ScanConfig.int("maxDecodePixels", 18_000_000).toLong(),
                )
                .sample
        val bitmap =
            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: error("图片解码失败")
        val exif = ExifInterface(file)
        val m = Matrix()
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            2 -> m.setScale(-1f, 1f)
            3 -> m.setRotate(180f)
            4 -> m.setScale(1f, -1f)
            5 -> {
                m.setRotate(90f)
                m.postScale(-1f, 1f)
            }
            6 -> m.setRotate(90f)
            7 -> {
                m.setRotate(270f)
                m.postScale(-1f, 1f)
            }
            8 -> m.setRotate(270f)
        }
        val upright =
            if (m.isIdentity) bitmap
            else
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true).also {
                    if (it !== bitmap) bitmap.recycle()
                }
        if (max(upright.width, upright.height) <= maxEdge) return upright
        val source = Mat()
        val resized = Mat()
        try {
            Utils.bitmapToMat(upright, source)
            val ratio = maxEdge.toDouble() / max(upright.width, upright.height)
            Imgproc.resize(source, resized, Size(), ratio, ratio, Imgproc.INTER_AREA)
            return Bitmap.createBitmap(resized.cols(), resized.rows(), Bitmap.Config.ARGB_8888)
                .also { Utils.matToBitmap(resized, it) }
        } finally {
            upright.recycle()
            source.release()
            resized.release()
        }
    }

    fun detect(bitmap: Bitmap): Quad? = detectResult(bitmap).quad

    fun detectResult(bitmap: Bitmap, preview: Boolean = false): DetectionResult =
        DocumentDetector.detect(bitmap, preview)

    fun detectMat(source: Mat): Quad? = DocumentDetector.detect(source, preview = true).quad

    fun detectFile(file: File): DetectionResult {
        val image = decode(file, 4000)
        return try {
            DocumentDetector.detect(image)
        } finally {
            image.recycle()
        }
    }

    fun render(page: PageRow, maxEdge: Int = 2400): Bitmap {
        val decoded = PageDecoder.crop(File(page.original), Quad.decode(page.corners), maxEdge)
        try {
            val result =
                transform(
                    decoded.bitmap,
                    decoded.quad,
                    page.filter,
                    page.rotation,
                    page.brightness,
                    page.contrast,
                    maxEdge,
                    page.enhanceStrength,
                )
            if (page.deskewDegrees == 0f) return result
            return try {
                PageOrientation.rotate(result, page.deskewDegrees)
            } finally {
                result.recycle()
            }
        } finally {
            decoded.bitmap.recycle()
        }
    }

    fun transform(
        bitmap: Bitmap,
        quad: Quad,
        filter: String,
        rotation: Int = 0,
        brightness: Float = 0f,
        contrast: Float = 1f,
        targetEdge: Int = Int.MAX_VALUE,
        strength: Float? = null,
    ): Bitmap {
        require(quad.valid()) { "裁切四角不能交叉或重合" }
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val p = quad.points.map { Point((it.x * (w - 1)).toDouble(), (it.y * (h - 1)).toDouble()) }
        fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val sourceW = max(dist(p[0], p[1]), dist(p[3], p[2]))
        val sourceH = max(dist(p[0], p[3]), dist(p[1], p[2]))
        val aspect = cn.paperforge.app.model.ImageGeometry.aspect(quad, bitmap.width, bitmap.height)
        val edge = min(max(sourceW, sourceH), min(targetEdge.toDouble() * 1.25, 4096.0))
        val outW = (if (aspect >= 1) edge else edge * aspect).roundToInt().coerceAtLeast(32)
        val outH = (if (aspect >= 1) edge / aspect else edge).roundToInt().coerceAtLeast(32)
        val source = Mat()
        val warped = Mat()
        val rgb = Mat()
        val gray = Mat()
        val dst = Mat()
        val srcPoints = MatOfPoint2f(*p.toTypedArray())
        val dstPoints =
            MatOfPoint2f(
                Point(0.0, 0.0),
                Point((outW - 1).toDouble(), 0.0),
                Point((outW - 1).toDouble(), (outH - 1).toDouble()),
                Point(0.0, (outH - 1).toDouble()),
            )
        val matrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        try {
            Utils.bitmapToMat(bitmap, source)
            Imgproc.warpPerspective(
                source,
                warped,
                matrix,
                Size(outW.toDouble(), outH.toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE,
            )
            source.release()
            if (max(warped.cols(), warped.rows()) > targetEdge) {
                val ratio = targetEdge.toDouble() / max(warped.cols(), warped.rows())
                Imgproc.resize(warped, warped, Size(), ratio, ratio, Imgproc.INTER_AREA)
            }
            Imgproc.cvtColor(warped, rgb, Imgproc.COLOR_RGBA2RGB)
            EnhancePipeline.apply(rgb, dst, filter, strength)
            if (brightness != 0f || contrast != 1f)
                dst.convertTo(dst, -1, contrast.toDouble(), brightness * 100.0)
            when (((rotation % 360) + 360) % 360) {
                90 -> Core.rotate(dst, dst, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(dst, dst, Core.ROTATE_180)
                270 -> Core.rotate(dst, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            }
            return Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(dst, it)
            }
        } finally {
            listOf(source, warped, rgb, gray, dst, srcPoints, dstPoints, matrix).forEach {
                it.release()
            }
        }
    }
}
