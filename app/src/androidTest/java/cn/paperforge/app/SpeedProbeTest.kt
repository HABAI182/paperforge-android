package cn.paperforge.app

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.imaging.PaperField
import cn.paperforge.app.imaging.ToneZones
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Range
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Where a full-size page's time goes, and whether the heavy filters gain from running in strips on several
 * threads. A filter run on a strip of a larger image reads its neighbours' real pixels across the strip's
 * edge, so the strips together must equal one call on the whole image; the test checks that they do.
 * Reads the first `*-2-flat.png` under files/phone-out; writes files/speed.json.
 */
@RunWith(AndroidJUnit4::class)
class SpeedProbeTest {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private fun ms(block: () -> Unit): Double {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1e6
    }

    private fun strips(source: Mat, destination: Mat, parts: Int, filter: (Mat, Mat) -> Unit) {
        val pool = Executors.newFixedThreadPool(parts)
        try {
            val rows = source.rows()
            val tasks =
                (0 until parts).map { k ->
                    val range = Range(rows * k / parts, rows * (k + 1) / parts)
                    pool.submit { filter(source.rowRange(range), destination.rowRange(range)) }
                }
            tasks.forEach { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    private fun maxDifference(a: Mat, b: Mat): Double {
        val diff = Mat()
        try {
            Core.absdiff(a, b, diff)
            return Core.minMaxLoc(diff.reshape(1)).maxVal
        } finally {
            diff.release()
        }
    }

    @Test
    fun heavyFiltersInStrips() {
        val file =
            File(context.filesDir, "phone-out").walk().filter { it.name.endsWith("-2-flat.png") }.sortedBy { it.path }.firstOrNull()
        assertTrue("run PhoneCaptureTest first: no *-2-flat.png", file != null)
        val bitmap = BitmapFactory.decodeFile(file!!.path)
        val rgba = Mat()
        val rgb = Mat()
        val gray = Mat()
        val owned = mutableListOf(rgba, rgb, gray)
        fun mat() = Mat().also(owned::add)
        val out = JSONObject().put("page", file.name).put("width", bitmap.width).put("height", bitmap.height)
        try {
            Utils.bitmapToMat(bitmap, rgba)
            bitmap.recycle()
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            val threads = Core.getNumThreads()
            val cores = Runtime.getRuntime().availableProcessors()
            out.put("opencvThreads", threads).put("cores", cores)
            val scale = maxOf(gray.cols(), gray.rows()) / 1375f
            val sigma = 9.0 * scale
            val size = ((21 * scale).toInt() or 1).toDouble()
            val element = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(size, size)).also(owned::add)
            val float32 = mat().also { gray.convertTo(it, CvType.CV_32F) }
            // Warm-up, so the first timing does not carry one-off setup.
            Imgproc.GaussianBlur(float32, mat(), Size(0.0, 0.0), sigma)
            val blurAll = mat()
            out.put("blurAllThreads", ms { Imgproc.GaussianBlur(float32, blurAll, Size(0.0, 0.0), sigma) })
            Core.setNumThreads(1)
            val blurOne = mat()
            out.put("blurOneThread", ms { Imgproc.GaussianBlur(float32, blurOne, Size(0.0, 0.0), sigma) })
            val blurStrips = mat().also { it.create(float32.size(), float32.type()) }
            out.put("blurStrips", ms { strips(float32, blurStrips, cores) { a, b -> Imgproc.GaussianBlur(a, b, Size(0.0, 0.0), sigma) } })
            out.put("blurStripsMaxDiff", maxDifference(blurAll, blurStrips))
            Core.setNumThreads(threads)
            val closeAll = mat()
            out.put("closeAllThreads", ms { Imgproc.morphologyEx(gray, closeAll, Imgproc.MORPH_CLOSE, element) })
            Core.setNumThreads(1)
            val closeOne = mat()
            out.put("closeOneThread", ms { Imgproc.morphologyEx(gray, closeOne, Imgproc.MORPH_CLOSE, element) })
            val closeStrips = mat().also { it.create(gray.size(), gray.type()) }
            out.put("closeStrips", ms { strips(gray, closeStrips, cores) { a, b -> Imgproc.morphologyEx(a, b, Imgproc.MORPH_CLOSE, element) } })
            out.put("closeStripsMaxDiff", maxDifference(closeAll, closeStrips))
            Core.setNumThreads(threads)
            var field: Mat? = null
            out.put("paperField", ms { field = PaperField.edgePreserving(gray) })
            field?.let(owned::add)
            val page = mat().also(rgb::copyTo)
            out.put("toneZones", ms { ToneZones.apply(page, field, 1f) })
            File(context.filesDir, "speed.json").writeText(out.toString(1))
            android.util.Log.i("SpeedProbe", out.toString())
        } finally {
            owned.forEach(Mat::release)
        }
    }
}
