package cn.paperforge.app.imaging

import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * PaperForge tile flat-field implementation. Technique research: OSS-DocumentScanner@ff05fc52
 * (MIT), WhitePaperTransform{,2}.cpp. No copied code; the color/large-fill tradeoff is measured in
 * enhancement-v030.
 */
object EnhancePipeline {
    /** RGB input, caller owns destination. ORIGINAL never changes channel count. */
    fun apply(rgb: Mat, destination: Mat, mode: String, strengthOverride: Float? = null) {
        if (mode == "ORIGINAL") {
            rgb.copyTo(destination)
            return
        }
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also { owned.add(it) }
        val small = mat()
        val tiles = mat()
        val smooth = mat()
        val field = mat()
        val corrected = mat()
        val gray = mat()
        try {
            val scale =
                min(
                    1.0,
                    ScanConfig.int("illuminationEdge", 480).toDouble() / max(rgb.cols(), rgb.rows()),
                )
            Imgproc.resize(rgb, small, Size(), scale, scale, Imgproc.INTER_AREA)
            val sw = small.cols()
            val sh = small.rows()
            val cols = max(3, (sw / 32.0).roundToInt())
            val rows = max(3, (sh / 32.0).roundToInt())
            val bytes = ByteArray(sw * sh * 3)
            small.get(0, 0, bytes)
            val tileBytes = ByteArray(rows * cols * 3)
            val fraction = ScanConfig.float("illuminationTilePercentile", .88f)
            for (y in 0 until rows) for (x in 0 until cols) {
                val hist = Array(3) { IntArray(256) }
                var count = 0
                for (ty in y * sh / rows until (y + 1) * sh / rows) for (tx in
                    x * sw / cols until (x + 1) * sw / cols) {
                    for (c in 0..2) hist[c][bytes[(ty * sw + tx) * 3 + c].toInt() and 255]++
                    count++
                }
                for (c in 0..2) tileBytes[(y * cols + x) * 3 + c] =
                    percentile(hist[c], count, fraction).toByte()
            }
            tiles.create(rows, cols, CvType.CV_8UC3)
            tiles.put(0, 0, tileBytes)
            Imgproc.cvtColor(tiles, gray, Imgproc.COLOR_RGB2GRAY)
            val luminance = ByteArray(rows * cols)
            gray.get(0, 0, luminance)
            val hist = IntArray(256)
            luminance.forEach { hist[it.toInt() and 255]++ }
            val floor = max(35f, percentile(hist, luminance.size, .9f) * .45f)
            val dark =
                ByteArray(luminance.size) {
                    if ((luminance[it].toInt() and 255) < floor) 255.toByte() else 0
                }
            val darkCount = dark.count { it.toInt() != 0 }
            if (darkCount in 1 until dark.size) {
                val mask = mat()
                mask.create(rows, cols, CvType.CV_8UC1)
                mask.put(0, 0, dark)
                Photo.inpaint(tiles, mask, smooth, 3.0, Photo.INPAINT_TELEA)
                smooth.copyTo(tiles)
            }
            Imgproc.GaussianBlur(tiles, smooth, Size(3.0, 3.0), 0.0)
            Imgproc.resize(smooth, field, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val input = ByteArray(rgb.rows() * rgb.cols() * 3)
            val illumination = ByteArray(input.size)
            rgb.get(0, 0, input)
            field.get(0, 0, illumination)
            val strength =
                strengthOverride?.coerceIn(0f, 1f)
                    ?: if (mode == "WHITEBOARD") 1f
                    else ScanConfig.float("illuminationStrength", 1f)
            for (i in input.indices) {
                val gain = (250f / max(70, illumination[i].toInt() and 255)).coerceIn(.85f, 3f)
                input[i] =
                    ((input[i].toInt() and 255) * (1 + (gain - 1) * strength))
                        .toInt()
                        .coerceIn(0, 255)
                        .toByte()
            }
            corrected.create(rgb.rows(), rgb.cols(), CvType.CV_8UC3)
            corrected.put(0, 0, input)
            if (mode in listOf("AUTO", "TEXT", "COLOR")) {
                enhanceLuminance(corrected, destination, mode, strength)
                return
            }
            if (mode in listOf("CLEAN", "WHITEBOARD")) {
                // CLEAN used to fall into the gray path and silently discard colored stamps.
                corrected.copyTo(destination)
                return
            }
            Imgproc.cvtColor(corrected, gray, Imgproc.COLOR_RGB2GRAY)
            if (mode != "BW") {
                gray.copyTo(destination)
                return
            }
            val window =
                max(
                    15,
                    (min(rgb.cols(), rgb.rows()) * ScanConfig.float("sauvolaWindowFraction", .05f))
                        .roundToInt(),
                ) or 1
            val f = mat()
            val mean = mat()
            val square = mat()
            val threshold = mat()
            gray.convertTo(f, CvType.CV_32F)
            Imgproc.boxFilter(
                f,
                mean,
                -1,
                Size(window.toDouble(), window.toDouble()),
                Point(-1.0, -1.0),
                true,
                Core.BORDER_REPLICATE,
            )
            Core.multiply(f, f, square)
            Imgproc.boxFilter(
                square,
                square,
                -1,
                Size(window.toDouble(), window.toDouble()),
                Point(-1.0, -1.0),
                true,
                Core.BORDER_REPLICATE,
            )
            Core.multiply(mean, mean, threshold)
            Core.subtract(square, threshold, square)
            Core.max(square, Scalar(0.0), square)
            Core.sqrt(square, square)
            square.convertTo(threshold, -1, 1.0 / 128, -1.0)
            Core.multiply(
                threshold,
                Scalar(ScanConfig.float("sauvolaK", .22f).toDouble()),
                threshold,
            )
            Core.add(threshold, Scalar(1.0), threshold)
            Core.multiply(mean, threshold, threshold)
            val grayBytes = ByteArray(gray.rows() * gray.cols())
            gray.get(0, 0, grayBytes)
            val gh = IntArray(256)
            grayBytes.forEach { gh[it.toInt() and 255]++ }
            // Sauvola alone erases interiors of large solid black fills.
            Core.max(
                threshold,
                Scalar(min(65.0, percentile(gh, grayBytes.size, .85f) * .22)),
                threshold,
            )
            Core.compare(f, threshold, destination, Core.CMP_GT)
        } finally {
            owned.forEach { it.release() }
        }
    }

    /** Local contrast and a thresholded unsharp mask affect luminance, preserving chroma. */
    private fun enhanceLuminance(rgb: Mat, destination: Mat, mode: String, strength: Float) {
        val ycc = Mat()
        val blurred = Mat()
        val channels = mutableListOf<Mat>()
        val clahe = Imgproc.createCLAHE(if (mode == "TEXT") 1.8 else 1.25, Size(8.0, 8.0))
        try {
            Imgproc.cvtColor(rgb, ycc, Imgproc.COLOR_RGB2YCrCb)
            Core.split(ycc, channels)
            val original = channels[0].clone()
            try {
                clahe.apply(original, channels[0])
                // Blend avoids flattening pencil and avoids amplifying noise like full CLAHE.
                val blend =
                    strength * (if (mode == "TEXT") .64 else if (mode == "COLOR") .42 else .28)
                Core.addWeighted(channels[0], blend, original, 1.0 - blend, 0.0, channels[0])
                Imgproc.GaussianBlur(channels[0], blurred, Size(0.0, 0.0), .85)
                val data = ByteArray(channels[0].rows() * channels[0].cols())
                val low = ByteArray(data.size)
                channels[0].get(0, 0, data)
                blurred.get(0, 0, low)
                val amount = (if (mode == "TEXT") .55f else .22f) * strength
                for (i in data.indices) {
                    val value = data[i].toInt() and 255
                    val detail = value - (low[i].toInt() and 255)
                    if (abs(detail) > 3)
                        data[i] = (value + detail * amount).roundToInt().coerceIn(0, 255).toByte()
                }
                channels[0].put(0, 0, data)
                Core.merge(channels, ycc)
                Imgproc.cvtColor(ycc, destination, Imgproc.COLOR_YCrCb2RGB)
            } finally {
                original.release()
            }
        } finally {
            channels.forEach { it.release() }
            ycc.release()
            blurred.release()
            clahe.collectGarbage()
        }
    }

    private fun percentile(hist: IntArray, count: Int, fraction: Float): Int {
        val target = max(1, (count * fraction).toInt())
        var sum = 0
        for (i in hist.indices) {
            sum += hist[i]
            if (sum >= target) return i
        }
        return 255
    }
}
