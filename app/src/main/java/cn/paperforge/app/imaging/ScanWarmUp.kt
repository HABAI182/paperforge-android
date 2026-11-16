package cn.paperforge.app.imaging

import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * The first page a process enhances used to take 2–4 s longer than the rest: the page pipeline's per-pixel loops ran
 * interpreted until the runtime compiled them. Once per process, at background priority, this runs the same pipeline
 * on a small synthetic ruled page (paper gradient, rules, pen strokes) so those loops are compiled before the user's
 * first scan or import. Pipeline state is thread-local, so it cannot disturb a page being processed at the same time;
 * its result is discarded.
 */
object ScanWarmUp {
    private val started = AtomicBoolean(false)
    private val done = java.util.concurrent.CountDownLatch(1)

    /**
     * A real page started while the warm-up still runs waits for it (at most [timeoutMs]): run side by side, both
     * ran uncompiled and competed for the processor, and the first page of a batch import took 25 s instead of 4.
     */
    fun awaitDone(timeoutMs: Long = 8000) {
        if (!started.get() || Thread.currentThread().name == "scan-warm-up") return
        runCatching { done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    fun start() {
        if (ScanConfig.int("scanWarmUp", 1) == 0 || !started.compareAndSet(false, true)) return
        Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                // Benchmark runs dump pipeline stages to a shared folder; the warm-up must not add files to it.
                if (EdgeInkDiagnostics.enabled()) return@Thread
                val page = syntheticPage()
                val out = Mat()
                try {
                    runCatching { EnhancePipeline.apply(page, out, "WORKBOOK") }
                        .onFailure { android.util.Log.w("PaperForgeWarmUp", "warm-up skipped", it) }
                } finally {
                    page.release()
                    out.release()
                }
            } finally {
                done.countDown()
            }
        }, "scan-warm-up").start()
    }

    /** A 900×1200 RGB notebook page: uneven light, ruled lines, a margin and some handwriting-like strokes. */
    private fun syntheticPage(): Mat {
        val width = 900
        val height = 1200
        val gray = Mat(height, width, CvType.CV_8UC1)
        val row = ByteArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) row[x] = (196 + 30 * x / width + 12 * y / height).coerceAtMost(240).toByte()
            gray.put(y, 0, row)
        }
        val page = Mat()
        Imgproc.cvtColor(gray, page, Imgproc.COLOR_GRAY2RGB)
        gray.release()
        val rule = Scalar(150.0, 160.0, 190.0)
        var y = 120
        while (y < height - 40) {
            Imgproc.line(page, Point(0.0, y.toDouble()), Point(width - 1.0, y + 3.0), rule, 2)
            y += 42
        }
        Imgproc.line(page, Point(90.0, 0.0), Point(92.0, height - 1.0), Scalar(200.0, 120.0, 120.0), 2)
        val ink = Scalar(30.0, 35.0, 60.0)
        for (line in 0 until 16) {
            val base = 112.0 + 42 * line
            var x = 120.0
            var k = line
            while (x < width - 80) {
                val w = 14.0 + (k * 7) % 22
                Imgproc.ellipse(page, Point(x + w / 2, base - 10), org.opencv.core.Size(w / 2, 9.0), (k * 37 % 180).toDouble(), 0.0, 300.0, ink, 2)
                x += w + 10 + (k * 5) % 12
                k++
            }
        }
        return page
    }
}
