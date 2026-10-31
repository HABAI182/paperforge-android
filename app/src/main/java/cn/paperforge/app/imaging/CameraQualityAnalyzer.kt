package cn.paperforge.app.imaging

import android.graphics.Bitmap
import cn.paperforge.app.model.*
import kotlin.math.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object CameraQualityAnalyzer {
    data class Result(
        val sample: CaptureQualityPolicy.Sample?,
        val signature: List<Float>,
        val sceneSignature: List<Float>,
    )

    fun analyze(source: Mat, quad: Quad?): Result {
        val gray = Mat()
        val signature = Mat()
        val paper = Mat()
        val laplacian = Mat()
        val mean = MatOfDouble()
        val deviation = MatOfDouble()
        try {
            when (source.channels()) {
                1 -> source.copyTo(gray)
                4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
                else -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGB2GRAY)
            }
            Imgproc.resize(gray, signature, Size(4.0, 4.0), 0.0, 0.0, Imgproc.INTER_AREA)
            val scene = ByteArray(16)
            signature.get(0, 0, scene)
            val values = scene.map { (it.toInt() and 255) / 255f }
            if (quad == null) return Result(null, values, values)
            val points =
                MatOfPoint2f(
                    *quad.points
                        .map {
                            Point(
                                it.x * (gray.cols() - 1).toDouble(),
                                it.y * (gray.rows() - 1).toDouble(),
                            )
                        }
                        .toTypedArray()
                )
            val corners =
                MatOfPoint2f(
                    Point(0.0, 0.0),
                    Point(449.0, 0.0),
                    Point(449.0, 599.0),
                    Point(0.0, 599.0),
                )
            val transform = Imgproc.getPerspectiveTransform(points, corners)
            try {
                Imgproc.warpPerspective(
                    gray,
                    paper,
                    transform,
                    Size(450.0, 600.0),
                    Imgproc.INTER_LINEAR,
                    Core.BORDER_REPLICATE,
                )
            } finally {
                points.release()
                corners.release()
                transform.release()
            }
            Imgproc.Laplacian(paper, laplacian, CvType.CV_32F)
            Core.meanStdDev(laplacian, mean, deviation)
            val sharpness = deviation.toArray()[0].pow(2)
            var detailed = 0
            var minDetailed = 0.0
            for (y in 0..2) for (x in 0..2) {
                val tile = paper.submat(y * 200, (y + 1) * 200, x * 150, (x + 1) * 150)
                val detail = laplacian.submat(y * 200, (y + 1) * 200, x * 150, (x + 1) * 150)
                try {
                    Core.meanStdDev(tile, mean, deviation)
                    val contrast = deviation.toArray()[0]
                    Core.meanStdDev(detail, mean, deviation)
                    val variance = deviation.toArray()[0].pow(2)
                    // Only tiles that hold real detail can speak about local blur; blank margins
                    // would otherwise look soft and raise a false motion hint.
                    if (contrast > 12 && variance > 20) {
                        detailed++
                        if (minDetailed == 0.0 || variance < minDetailed) minDetailed = variance
                    }
                } finally {
                    tile.release()
                    detail.release()
                }
            }
            val bytes = ByteArray(paper.rows() * paper.cols())
            paper.get(0, 0, bytes)
            val glare = bytes.count { (it.toInt() and 255) >= 253 }.toDouble() / bytes.size
            Imgproc.resize(paper, signature, Size(8.0, 8.0), 0.0, 0.0, Imgproc.INTER_AREA)
            val paperSignature = ByteArray(64)
            signature.get(0, 0, paperSignature)
            return Result(
                CaptureQualityPolicy.Sample(
                    sharpness,
                    Core.mean(paper).`val`[0] / 255,
                    detailed,
                    glare,
                    minDetailed,
                ),
                paperSignature.map { (it.toInt() and 255) / 255f },
                values,
            )
        } finally {
            gray.release()
            signature.release()
            paper.release()
            laplacian.release()
            mean.release()
            deviation.release()
        }
    }

    /** Post-capture check on the saved photo. Advisory only: it never blocks saving. */
    fun postCapture(bitmap: Bitmap, quad: Quad?): List<CaptureHint> {
        val mat = Mat()
        return try {
            Utils.bitmapToMat(bitmap, mat)
            val result = analyze(mat, quad ?: Quad.Full)
            CaptureHints.of(result.sample, quad?.cutOffEdges() ?: 0)
        } finally {
            mat.release()
        }
    }
}
