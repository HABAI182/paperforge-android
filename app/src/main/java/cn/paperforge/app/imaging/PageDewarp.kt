package cn.paperforge.app.imaging

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Recovered displacement model and dense-grid core. Applied explicitly, with undo support. */
object PageDewarp {
    fun apply(bitmap: Bitmap, strength: Float): Bitmap {
        if (strength <= 0f)
            return checkNotNull(bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false))
        val source = Mat()
        val field = Mat(240, 240, CvType.CV_32FC2)
        val smooth = Mat()
        val grid = Mat(240, 240, CvType.CV_32FC2)
        val dense = Mat()
        val unusedMap = Mat()
        val result = Mat()
        try {
            Utils.bitmapToMat(bitmap, source)
            val output = ScanModel.run(source, dewarp = true)
            field.put(0, 0, FloatArray(240 * 240 * 2) { i -> output[(i % 2) * 240 * 240 + i / 2] })
            Imgproc.GaussianBlur(
                field,
                smooth,
                Size(39.0, 39.0),
                -1.0,
                -1.0,
                Core.BORDER_REFLECT_101,
            )
            val values = FloatArray(240 * 240 * 2)
            smooth.get(0, 0, values)
            for (i in 0 until 240 * 240) {
                values[2 * i] += (i % 240).toFloat()
                values[2 * i + 1] += (i / 240).toFloat()
            }
            for (axis in 0..1) {
                var low = Float.POSITIVE_INFINITY
                var high = Float.NEGATIVE_INFINITY
                for (i in 0 until 240 * 240) {
                    low = minOf(low, values[2 * i + axis])
                    high = maxOf(high, values[2 * i + axis])
                }
                check(high - low > .001f) { "曲面校正未得到有效网格" }
                val dimension = if (axis == 0) source.cols() else source.rows()
                for (i in 0 until 240 * 240) values[2 * i + axis] =
                    (values[2 * i + axis] - low) / (high - low) * dimension
            }
            grid.put(0, 0, values)
            Imgproc.resize(grid, dense, source.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val row = FloatArray(source.cols() * 2)
            val amount = strength.coerceIn(0f, 1f)
            for (y in 0 until source.rows()) {
                dense.get(y, 0, row)
                for (x in 0 until source.cols()) {
                    row[2 * x] = x + (row[2 * x] - x) * amount
                    row[2 * x + 1] = y + (row[2 * x + 1] - y) * amount
                }
                dense.put(y, 0, row)
            }
            Imgproc.remap(
                source,
                result,
                dense,
                unusedMap,
                Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE,
            )
            return Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(result, it)
            }
        } finally {
            listOf(source, field, smooth, grid, dense, unusedMap, result).forEach { it.release() }
        }
    }
}
