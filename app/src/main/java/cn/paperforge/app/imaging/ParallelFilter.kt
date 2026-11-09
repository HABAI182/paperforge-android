package cn.paperforge.app.imaging

import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Large neighbourhood filters run in horizontal strips on several threads. OpenCV's own blur here runs on
 * one thread whatever its thread setting: on the emulator a sigma-18 blur of a page took 4.3 s, and 1.0 s
 * in six strips.
 *
 * Every strip is filtered with a margin of rows above and below at least as deep as the filter reaches, and
 * only its middle is kept, so every output pixel sees the same neighbours as in one call on the whole page,
 * and the result is the same to the bit. Without the margin a morphological close differed by up to 210 at
 * strip edges, since OpenCV does not look past a strip for it. Small images, and `parallelFilters` 0, take
 * the single call.
 */
internal object ParallelFilter {
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    private val pool by lazy {
        Executors.newFixedThreadPool(threads) { job -> Thread(job, "paperforge-filter").apply { isDaemon = true } }
    }

    private fun enabled(image: Mat) =
        threads > 1 && ScanConfig.int("parallelFilters", 1) != 0 && image.rows() >= 4 * threads &&
            image.rows().toLong() * image.cols() >= ScanConfig.int("parallelFiltersMinPixels", 1_000_000)

    /** [filter] from [source] into [destination] in strips with [margin] rows of overlap; in place is allowed. */
    fun strips(source: Mat, destination: Mat, margin: Int, filter: (Mat, Mat) -> Unit) {
        if (!enabled(source)) {
            filter(source, destination)
            return
        }
        val rows = source.rows()
        val inPlace = destination.nativeObj == source.nativeObj || (!destination.empty() && destination.dataAddr() == source.dataAddr())
        val target = if (inPlace) Mat() else destination
        target.create(source.rows(), source.cols(), source.type())
        val parts = threads
        val jobs = ArrayList<Future<*>>(parts)
        for (k in 0 until parts) {
            val top = rows * k / parts
            val bottom = rows * (k + 1) / parts
            if (bottom <= top) continue
            val from = max(0, top - margin)
            val to = min(rows, bottom + margin)
            jobs += pool.submit {
                val input = source.rowRange(from, to)
                val output = Mat()
                try {
                    filter(input, output)
                    output.rowRange(top - from, bottom - from).copyTo(target.rowRange(top, bottom))
                } finally {
                    output.release()
                    input.release()
                }
            }
        }
        jobs.forEach { it.get() }
        if (inPlace) {
            target.copyTo(destination)
            target.release()
        }
    }

    /** GaussianBlur with a computed kernel size, as `Size(0.0, 0.0)` gives. */
    fun gaussian(source: Mat, destination: Mat, sigmaX: Double, sigmaY: Double = 0.0) {
        val reach = ceil(4 * max(sigmaX, sigmaY)).toInt() + 2
        strips(source, destination, reach) { a, b -> Imgproc.GaussianBlur(a, b, Size(0.0, 0.0), sigmaX, sigmaY) }
    }

    /** morphologyEx: two passes for open and close, so the margin covers both. */
    fun morphology(source: Mat, destination: Mat, operation: Int, element: Mat) {
        val reach = 2 * (element.rows() / 2) + 2
        strips(source, destination, reach) { a, b -> Imgproc.morphologyEx(a, b, operation, element) }
    }

    fun dilate(source: Mat, destination: Mat, element: Mat) {
        strips(source, destination, element.rows() / 2 + 1) { a, b -> Imgproc.dilate(a, b, element) }
    }

    fun erode(source: Mat, destination: Mat, element: Mat) {
        strips(source, destination, element.rows() / 2 + 1) { a, b -> Imgproc.erode(a, b, element) }
    }
}
