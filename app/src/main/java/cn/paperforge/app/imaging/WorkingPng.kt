package cn.paperforge.app.imaging

import android.graphics.Bitmap
import java.io.File
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * The flattening's working PNGs (its input and its output page). PNG is lossless at every zlib level, so the level
 * only trades file size for time: `Bitmap.compress` takes no level, and on the PC level 1 encoded these pages 4.8 times
 * faster than level 6 for files about a quarter larger, with every pixel decoding the same (15 pages,
 * work/round19/bench_candidates.json). The pixels written are those `Bitmap.compress` writes: un-premultiplied
 * RGBA. `workingPngLevel` -1 restores `Bitmap.compress`.
 */
internal object WorkingPng {
    fun write(bitmap: Bitmap, file: File) {
        val level = ScanConfig.int("workingPngLevel", 1)
        if (level !in 0..9) {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return
        }
        val rgba = Mat()
        val bgra = Mat()
        val options = MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, level)
        try {
            Utils.bitmapToMat(bitmap, rgba, true)
            Imgproc.cvtColor(rgba, bgra, Imgproc.COLOR_RGBA2BGRA)
            check(Imgcodecs.imwrite(file.path, bgra, options)) { "PNG not written: ${file.name}" }
        } finally {
            rgba.release()
            bgra.release()
            options.release()
        }
    }
}
