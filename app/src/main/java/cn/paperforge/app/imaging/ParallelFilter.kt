package cn.paperforge.app.imaging

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.opencv.core.CvType
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

    /**
     * [body] over the index range [0, size) in contiguous parts on the filter threads, each part's result in
     * order. For per-pixel loops whose pixels are independent: each part writes only its own indices, so the
     * output is the same to the bit as one pass. Small ranges, a call from a filter thread, and `parallelLoops` 0
     * run in one part on the caller.
     */
    fun <T> ranges(size: Int, body: (Int, Int) -> T): List<T> {
        if (threads <= 1 || size < ScanConfig.int("parallelLoopsMinPixels", 250_000) ||
            ScanConfig.int("parallelLoops", 1) == 0 || Thread.currentThread().name == "paperforge-filter"
        ) return listOf(body(0, size))
        val jobs = (0 until threads).map { k ->
            val from = (size.toLong() * k / threads).toInt()
            val to = (size.toLong() * (k + 1) / threads).toInt()
            pool.submit<T> { body(from, to) }
        }
        return try {
            jobs.map { it.get() }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * [count] independent jobs on the filter threads, results in order; for a few coarse jobs that each do much
     * work (the bands of a page). One at a time on the caller with `parallelLoops` 0, one thread, or when
     * called from a filter thread.
     */
    fun <T> tasks(count: Int, body: (Int) -> T): List<T> {
        if (count <= 1 || threads <= 1 || ScanConfig.int("parallelLoops", 1) == 0 ||
            Thread.currentThread().name == "paperforge-filter"
        ) return List(count) { body(it) }
        val jobs = (0 until count).map { k -> pool.submit<T> { body(k) } }
        return try {
            jobs.map { it.get() }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** GaussianBlur with a computed kernel size, as `Size(0.0, 0.0)` gives. */
    fun gaussian(source: Mat, destination: Mat, sigmaX: Double, sigmaY: Double = 0.0) {
        val reach = ceil(4 * max(sigmaX, sigmaY)).toInt() + 2
        strips(source, destination, reach) { a, b -> Imgproc.GaussianBlur(a, b, Size(0.0, 0.0), sigmaX, sigmaY) }
    }

    /**
     * A wide Gaussian on a float field, worked out on a smaller copy: what a sigma of 8 px or more leaves has no
     * detail finer than a few pixels, so it is blurred at a quarter (or half) of the size and drawn back up.
     * On the bench pages a sigma of 18 at full size was the single largest cost of a page; on a page's paper
     * field the result is within 0.7 of a grey level on 99.9 % of pixels (mean 0.004). Narrower blurs, and any
     * non-float image, take the exact path.
     */
    fun wideGaussian(source: Mat, destination: Mat, sigma: Double) {
        val factor = when { sigma >= 16 -> 4; sigma >= 8 -> 2; else -> 1 }
        if (factor == 1 || source.depth() != CvType.CV_32F || ScanConfig.int("wideGaussianReduced", 1) == 0) {
            gaussian(source, destination, sigma)
            return
        }
        val width = source.cols()
        val height = source.rows()
        val small = Mat()
        try {
            Imgproc.resize(
                source,
                small,
                Size(max(1, width / factor).toDouble(), max(1, height / factor).toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
            // The reduction and the drawing back up blur a little themselves.
            val reduced = sqrt(max(sigma * sigma - factor * factor / 4.0, sigma * sigma / 4)) / factor
            Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), reduced)
            Imgproc.resize(small, destination, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        } finally {
            small.release()
        }
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
