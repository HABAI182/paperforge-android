package cn.paperforge.app.imaging

import android.graphics.Bitmap
import cn.paperforge.app.model.DewarpGrid
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Independent text-line dewarp; the explicit preview, undo and saved-grid interfaces are retained.
 */
object IndependentPageDewarp {
    const val VERSION = "paperforge-text-ridges-v1"

    data class Result(
        val bitmap: Bitmap,
        val grid: DewarpGrid,
        val note: String = "",
        val lines: Int = 0,
    )

    fun apply(bitmap: Bitmap, strength: Float): Bitmap = process(bitmap, strength).bitmap

    fun process(bitmap: Bitmap, strength: Float): Result {
        val source = Mat()
        val sparse = Mat()
        val dense = Mat()
        val unused = Mat()
        val output = Mat()
        try {
            Utils.bitmapToMat(bitmap, source)
            val estimate = TextLineDewarp.estimate(source, strength)
            val mapping = estimate.grid
            if (mapping.strength <= 0f) {
                return Result(
                    checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)),
                    mapping,
                    estimate.note,
                    estimate.lines,
                )
            }
            sparse.create(mapping.side, mapping.side, CvType.CV_32FC2)
            sparse.put(
                0,
                0,
                FloatArray(mapping.values.size) { i ->
                    mapping.values[i] * (if (i % 2 == 0) source.cols() - 1 else source.rows() - 1)
                },
            )
            Imgproc.resize(sparse, dense, source.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val row = FloatArray(source.cols() * 2)
            for (y in 0 until source.rows()) {
                dense.get(y, 0, row)
                for (x in 0 until source.cols()) {
                    row[2 * x] = x + (row[2 * x] - x) * mapping.strength
                    row[2 * x + 1] = y + (row[2 * x + 1] - y) * mapping.strength
                }
                dense.put(y, 0, row)
            }
            Imgproc.remap(source, output, dense, unused, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            val image = Bitmap.createBitmap(output.cols(), output.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(output, image)
            return Result(image, mapping, estimate.note, estimate.lines)
        } finally {
            listOf(source, sparse, dense, unused, output).forEach(Mat::release)
        }
    }
}
