package cn.paperforge.app.imaging

import java.io.File
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Test-only stage dumps for the "edge ink is erased" investigation (v200 20016 feedback).
 *
 * Disabled by default: every call site gates on [enabled], which requires the benchmark-only
 * [ScanConfig] key `edgeInkDump=1` **and** a directory set by an instrumented test. Production
 * rendering never writes files from here. Each stage is a lossless PNG so an offline script can
 * count how much ink each layer removed without re-implementing the pipeline.
 */
internal object EdgeInkDiagnostics {
    @Volatile var directory: File? = null

    fun enabled() = ScanConfig.int("edgeInkDump", 0) != 0 && directory != null

    /** Writes [mat] as `<name>.png`; RGB Mats are converted to BGR so the PNG shows real colours. */
    @Synchronized
    fun dump(name: String, mat: Mat) {
        val dir = directory ?: return
        if (!enabled() || mat.empty()) return
        dir.mkdirs()
        if (mat.channels() == 3) {
            val bgr = Mat()
            try {
                Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGB2BGR)
                Imgcodecs.imwrite(File(dir, "$name.png").path, bgr)
            } finally {
                bgr.release()
            }
        } else {
            Imgcodecs.imwrite(File(dir, "$name.png").path, mat)
        }
    }
}
