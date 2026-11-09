package cn.paperforge.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import java.io.File
import kotlin.math.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

data class DewarpTrace(
    val input: File,
    val grid: DewarpGrid,
    val sourceFile: File,
    val version: String,
    val currentOutput: String,
) {
    /** Replay only the immutable geometry; filter edits must never enhance enhanced pixels. */
    fun sourceAppearance(maxEdge: Int, cleanPaper: Boolean = false): Bitmap {
        val decoded = ScanProcessor.decode(input, maxEdge)
        val source =
            if (
                cleanPaper &&
                    version.startsWith("flat-paper-") &&
                    (version.removePrefix("flat-paper-").toIntOrNull() ?: 0) >= 8
            )
                try {
                    FlatDocument.sourcePaper(decoded)
                } finally {
                    decoded.recycle()
                }
            else decoded
        // The output may have an explicit aspect or super-resolution applied.
        val size = PageDecoder.uprightSize(File(currentOutput)) ?: (grid.width to grid.height)
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(size.first, size.second))
        return try {
            sample(
                source,
                grid,
                (size.first * scale).roundToInt().coerceAtLeast(2),
                (size.second * scale).roundToInt().coerceAtLeast(2),
            )
        } finally {
            source.recycle()
        }
    }

    /** OCR coordinates refer to the current rendered page, including later crop/rotation. */
    fun inputPoint(page: PageRow, point: PointN, renderWidth: Int, renderHeight: Int): PointN {
        var p = point
        if (page.deskewDegrees != 0f) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(page.original, bounds)
            val ratio =
                page.outputAspect.takeIf { it > 0f }
                    ?: ImageGeometry.aspect(
                        Quad.decode(page.corners),
                        bounds.outWidth,
                        bounds.outHeight,
                        PageDecoder.focalShare(java.io.File(page.original)),
                    )
            val rotatedRatio = if (page.rotation % 180 == 0) ratio else 1 / ratio
            val angle = page.deskewDegrees * PI / 180
            val h =
                ((renderHeight / (abs(cos(angle)) + abs(sin(angle)) * rotatedRatio) +
                        renderWidth / (abs(cos(angle)) * rotatedRatio + abs(sin(angle)))) / 2)
                    .roundToInt()
            p = DeskewGeometry((h * rotatedRatio).roundToInt(), h, page.deskewDegrees).inverse(p)
        }
        p =
            when ((page.rotation % 360 + 360) % 360) {
                90 -> PointN(p.y, 1 - p.x)
                180 -> PointN(1 - p.x, 1 - p.y)
                270 -> PointN(1 - p.y, p.x)
                else -> p
            }
        val matrix = Matrix()
        check(
            matrix.setPolyToPoly(
                floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
                0,
                Quad.decode(page.corners).points.flatMap { listOf(it.x, it.y) }.toFloatArray(),
                0,
                4,
            )
        )
        val xy = floatArrayOf(p.x, p.y)
        matrix.mapPoints(xy)
        return grid.inputPoint(PointN(xy[0], xy[1]))
    }

    companion object {

        /** Shared original-pixel grid sampler. A zero-strength grid must be a real identity. */
        internal fun sample(source: Bitmap, grid: DewarpGrid, width: Int, height: Int): Bitmap {
            if (grid.strength == 0f)
                return if (source.width == width && source.height == height)
                    source.copy(Bitmap.Config.ARGB_8888, false)
                else Bitmap.createScaledBitmap(source, width, height, true)
            val owned = mutableListOf<Mat>()
            fun mat() = Mat().also(owned::add)
            val pixels = mat()
            val sparse = mat()
            val dense = mat()
            val unused = mat()
            val output = mat()
            try {
                Utils.bitmapToMat(source, pixels)
                val values = FloatArray(grid.values.size)
                for (y in 0 until grid.side) for (x in 0 until grid.side) {
                    val i = 2 * (y * grid.side + x)
                    val px = ((x + .5f) * grid.width / grid.side - .5f) / (grid.width - 1)
                    val py = ((y + .5f) * grid.height / grid.side - .5f) / (grid.height - 1)
                    values[i] =
                        (px * (1 - grid.strength) + grid.values[i] * grid.strength) *
                            (source.width - 1)
                    values[i + 1] =
                        (py * (1 - grid.strength) + grid.values[i + 1] * grid.strength) *
                            (source.height - 1)
                }
                sparse.create(grid.side, grid.side, CvType.CV_32FC2)
                sparse.put(0, 0, values)
                Imgproc.resize(
                    sparse,
                    dense,
                    Size(width.toDouble(), height.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_LINEAR,
                )
                Imgproc.remap(
                    pixels,
                    output,
                    dense,
                    unused,
                    Imgproc.INTER_CUBIC,
                    Core.BORDER_CONSTANT,
                    Scalar.all(255.0),
                )
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    Utils.matToBitmap(output, it)
                }
            } finally {
                owned.forEach(Mat::release)
            }
        }

        fun load(page: PageRow): DewarpTrace? {
            val meta = PageResources.metadata(page)
            val steps = meta.optJSONArray("steps") ?: return null
            val resources = PageResources.entries(page).associateBy { it.id }
            for (i in steps.length() - 1 downTo 0) {
                val step = steps.getJSONObject(i)
                if (
                    step.optString("kind") == "dewarp" &&
                        resources[step.optString("output")]?.path == page.original
                ) {
                    return DewarpTrace(
                        File(resources.getValue(step.getString("input")).path),
                        DewarpGrid.read(File(resources.getValue(step.getString("grid")).path)),
                        File(resources.getValue(step.getString("original")).path),
                        step.getString("algorithm"),
                        page.original,
                    )
                }
            }
            return null
        }
    }
}
