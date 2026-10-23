package cn.paperforge.app.imaging

import android.content.Context
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** CPU sessions for the two recovered 240px models. Inputs are planar BGR / 255. */
object ScanModel {
    private var context: Context? = null
    private val sessions = mutableMapOf<String, Long>()
    private val loaded by lazy {
        System.loadLibrary("paperforge_scan")
        true
    }

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    @Synchronized
    fun run(source: Mat, dewarp: Boolean = false): FloatArray {
        check(loaded)
        val asset = if (dewarp) "scan/dewarp.mnn" else "scan/border.mnn"
        val handle =
            sessions.getOrPut(asset) {
                nativeCreate(checkNotNull(context).assets.open(asset).use { it.readBytes() }).also {
                    check(it != 0L)
                }
            }
        val bgr = Mat()
        val resized = Mat()
        try {
            when (source.channels()) {
                4 -> Imgproc.cvtColor(source, bgr, Imgproc.COLOR_RGBA2BGR)
                3 -> Imgproc.cvtColor(source, bgr, Imgproc.COLOR_RGB2BGR)
                else -> Imgproc.cvtColor(source, bgr, Imgproc.COLOR_GRAY2BGR)
            }
            if (dewarp) {
                val short = minOf(bgr.cols(), bgr.rows())
                if (short >= 1024)
                    Imgproc.resize(bgr, bgr, Size(1024.0, 1024.0), 0.0, 0.0, Imgproc.INTER_AREA)
                if (short >= 352)
                    Imgproc.resize(bgr, bgr, Size(352.0, 352.0), 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.resize(bgr, resized, Size(240.0, 240.0), 0.0, 0.0, Imgproc.INTER_AREA)
            } else {
                val scale = minOf(1.0, 420.0 / maxOf(bgr.cols(), bgr.rows()))
                if (scale < 1)
                    Imgproc.resize(
                        bgr,
                        bgr,
                        Size(
                            (bgr.cols() * scale).toInt().toDouble(),
                            (bgr.rows() * scale).toInt().toDouble(),
                        ),
                        0.0,
                        0.0,
                        Imgproc.INTER_AREA,
                    )
                Imgproc.resize(bgr, resized, Size(240.0, 240.0), 0.0, 0.0, Imgproc.INTER_LINEAR)
            }
            val pixels = ByteArray(240 * 240 * 3)
            resized.get(0, 0, pixels)
            val input = FloatArray(pixels.size)
            for (i in 0 until 240 * 240) for (c in 0..2) input[c * 240 * 240 + i] =
                (pixels[i * 3 + c].toInt() and 255) / 255f
            return nativeRun(handle, input).also {
                check(it.size == 2 * 240 * 240 && it.all(Float::isFinite))
            }
        } finally {
            bgr.release()
            resized.release()
        }
    }

    private external fun nativeCreate(model: ByteArray): Long

    private external fun nativeRun(handle: Long, input: FloatArray): FloatArray
}
