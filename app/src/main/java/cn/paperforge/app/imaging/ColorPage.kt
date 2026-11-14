package cn.paperforge.app.imaging

import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Pages that are mostly colour, such as a book cover or coloured paper, keep their colours. The workbook path
 * takes the brightest smooth tone for paper and maps it to white, which turned a light-blue cover white and
 * left only its dark text. Measured on the owner's photos: a blue cover was 53% coloured (chroma over 45), every
 * document page 0-3%; pages over `colorPageShare` take this path.
 *
 * The colour path does what the reference scanner's colour output does: it evens out broad shading, stretches the levels
 * with one gain for all three channels so hues stay, lifts saturation a little and sharpens.
 */
internal object ColorPage {
    const val VERSION = "color-page-2"

    fun isColorful(rgb: Mat): Boolean {
        if (ScanConfig.int("colorPage", 1) == 0 || rgb.channels() < 3) return false
        val small = Mat()
        try {
            val f = 400.0 / max(rgb.cols(), rgb.rows())
            Imgproc.resize(rgb, small, Size(), f, f, Imgproc.INTER_AREA)
            val bytes = ByteArray((small.total() * small.channels()).toInt()).also { small.get(0, 0, it) }
            val c = small.channels()
            var coloured = 0
            val n = bytes.size / c
            val mask = ByteArray(n)
            for (i in 0 until n) {
                val r = bytes[i * c].toInt() and 255
                val g = bytes[i * c + 1].toInt() and 255
                val b = bytes[i * c + 2].toInt() and 255
                val hi = max(r, max(g, b))
                if (hi > 60 && hi - min(r, min(g, b)) > 45) {
                    coloured++
                    mask[i] = 1
                }
            }
            if (coloured >= ScanConfig.float("colorPageShare", .25f) * n) return true
            // Uncertain pages keep their colours: a cover with solid printed colour blocks over 8% of it, most of its
            // colour in large areas. A pale tinted panel is too weak to count as coloured, and on the paper path it was
            // flattened to white with halos round its text. Document pages measure 0-3% here, and pen ink is strokes.
            if (coloured < ScanConfig.float("colorPageSolidShare", .08f) * n) return false
            val solidMask = Mat(small.rows(), small.cols(), CvType.CV_8UC1)
            val opened = Mat()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
            try {
                solidMask.put(0, 0, mask)
                Imgproc.morphologyEx(solidMask, opened, Imgproc.MORPH_OPEN, kernel)
                val solid = Core.countNonZero(opened)
                return solid >= ScanConfig.float("colorPageSolid", .75f) * coloured
            } finally {
                solidMask.release()
                opened.release()
                kernel.release()
            }
        } finally {
            small.release()
        }
    }

    /**
     * On a document page, the solid colour prints keep their colour. The page's tone step takes a large smooth
     * area for paper, so a blue skyline printed on an exercise book's cover came out as its own outline on white.
     * A print is a coloured region still there after an opening wider than any pen stroke or printed letter;
     * there, [destination] (the toned page) is blended with the colour path's version of [rgb].
     */
    fun keepPrints(rgb: Mat, destination: Mat) {
        if (ScanConfig.int("colorPrints", 1) == 0) return
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        try {
            val channels = ArrayList<Mat>().also { Core.split(rgb, it) }
            owned += channels
            val high = mat()
            val low = mat()
            Core.max(channels[0], channels[1], high)
            Core.max(high, channels[2], high)
            Core.min(channels[0], channels[1], low)
            Core.min(low, channels[2], low)
            val chroma = mat()
            Core.subtract(high, low, chroma)
            val coloured = mat()
            Imgproc.threshold(chroma, coloured, 45.0, 255.0, Imgproc.THRESH_BINARY)
            val lit = mat()
            Imgproc.threshold(high, lit, 60.0, 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_and(coloured, lit, coloured)
            // Skin is coloured too, and a finger at the edge is taken off the page, not kept.
            val hsv = mat()
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            val skin = mat()
            val skinHigh = mat()
            Core.inRange(hsv, Scalar(0.0, 30.0, 50.0), Scalar(20.0, 180.0, 255.0), skin)
            Core.inRange(hsv, Scalar(165.0, 30.0, 50.0), Scalar(180.0, 180.0, 255.0), skinHigh)
            Core.bitwise_or(skin, skinHigh, skin)
            Core.subtract(coloured, skin, coloured)
            // The opening below keeps a subset of these pixels, so too few here means too few after it: the
            // large opening, a third of a second on a phone page, is skipped on the plain pages that are most.
            if (Core.countNonZero(coloured) < ScanConfig.float("colorPrintsShare", .01f) * rgb.total()) return
            val edge = max(rgb.cols(), rgb.rows())
            val k = max(9, (ScanConfig.float("colorPrintsWidth", .012f) * edge).roundToInt()) or 1
            val weight = mat()
            // A print is many times wider than the opening's own detail, and its weight is feathered over k / 4
            // anyway, so the mask is worked out at half size: the elliptical opening costs k² per pixel, and at
            // full size it was still 1.7 s of a page on the emulator. Small pages keep the full-size path.
            val half = ScanConfig.int("colorPrintsHalf", 1) != 0 && k >= 17
            val factor = if (half) 2 else 1
            val mask =
                if (!half) coloured
                else
                    mat().also {
                        Imgproc.resize(coloured, it, Size((rgb.cols() / 2).toDouble(), (rgb.rows() / 2).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                        Imgproc.threshold(it, it, 127.0, 255.0, Imgproc.THRESH_BINARY)
                    }
            val kk = if (half) (k / 2) or 1 else k
            val shape = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kk.toDouble(), kk.toDouble())).also(owned::add)
            val prints = mat()
            // On the filter threads, the same to the bit: one thread took 3.5 s on a 1300 x 1970 page and 17 s
            // on a 1860 x 2800 one on the emulator, the largest single step of the page.
            StepTimes.time("cp.open") { ParallelFilter.morphology(mask, prints, Imgproc.MORPH_OPEN, shape) }
            if (Core.countNonZero(prints) * factor * factor < ScanConfig.float("colorPrintsShare", .01f) * rgb.total()) return
            // Out to the print's own soft border, then feathered.
            val growSize = ((k / 2 or 1) / factor) or 1
            val grow = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(growSize.toDouble(), growSize.toDouble())).also(owned::add)
            ParallelFilter.dilate(prints, prints, grow)
            prints.convertTo(weight, CvType.CV_32F, 1.0 / 255)
            if (half) {
                Imgproc.GaussianBlur(weight, weight, Size(0.0, 0.0), k / 8.0)
                Imgproc.resize(weight, weight, Size(rgb.cols().toDouble(), rgb.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            } else ParallelFilter.wideGaussian(weight, weight, k / 4.0)
            val colour = mat()
            StepTimes.time("cp.enhance") { enhance(rgb, colour) }
            val toned = mat()
            destination.convertTo(toned, CvType.CV_32FC3)
            val vivid = mat()
            colour.convertTo(vivid, CvType.CV_32FC3)
            val weight3 = mat()
            Core.merge(listOf(weight, weight, weight), weight3)
            // toned + (colour - toned) * weight, per channel.
            Core.subtract(vivid, toned, vivid)
            Core.multiply(vivid, weight3, vivid)
            Core.add(toned, vivid, toned)
            toned.convertTo(destination, destination.type())
        } finally {
            owned.forEach(Mat::release)
        }
    }

    fun enhance(rgb: Mat, destination: Mat) {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        try {
            val work = mat()
            rgb.convertTo(work, CvType.CV_32FC3)
            // Broad shading only: blurred at a sixth of the page, so the cover's own colour regions stay.
            val gray = mat()
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            val shade = mat()
            gray.convertTo(shade, CvType.CV_32F)
            val sigma = max(rgb.cols(), rgb.rows()) / 6.0
            val tiny = mat()
            val f = 200.0 / max(rgb.cols(), rgb.rows())
            Imgproc.resize(shade, tiny, Size(), f, f, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(tiny, tiny, Size(0.0, 0.0), sigma * f)
            Imgproc.resize(tiny, shade, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val shadeValues = FloatArray(shade.total().toInt()).also { shade.get(0, 0, it) }
            val reference = shadeValues.copyOf().also { it.sort() }[(shadeValues.size * .9).toInt()]
            val gain = FloatArray(shadeValues.size) { (reference / max(shadeValues[it], 1f)).coerceIn(1f, 1.6f) }
            val gray8 = ByteArray(gray.total().toInt()).also { gray.get(0, 0, it) }
            // One levels stretch for all channels, from the corrected brightness.
            val hist = IntArray(256)
            for (i in gray8.indices) hist[((gray8[i].toInt() and 255) * gain[i]).roundToInt().coerceIn(0, 255)]++
            fun level(q: Double): Int {
                var sum = 0
                for (v in 0..255) {
                    sum += hist[v]
                    if (sum >= q * gray8.size) return v
                }
                return 255
            }
            val black = level(.005).toFloat()
            val white = max(black + 32f, level(.995).toFloat())
            val pixels = FloatArray((work.total() * 3).toInt()).also { work.get(0, 0, it) }
            val saturation = ScanConfig.float("colorPageSaturation", 1.15f)
            for (i in gain.indices) {
                var r = pixels[3 * i] * gain[i]
                var g = pixels[3 * i + 1] * gain[i]
                var b = pixels[3 * i + 2] * gain[i]
                r = (r - black) * 255f / (white - black)
                g = (g - black) * 255f / (white - black)
                b = (b - black) * 255f / (white - black)
                val y = .299f * r + .587f * g + .114f * b
                pixels[3 * i] = y + (r - y) * saturation
                pixels[3 * i + 1] = y + (g - y) * saturation
                pixels[3 * i + 2] = y + (b - y) * saturation
            }
            work.put(0, 0, pixels)
            val blurred = mat()
            Imgproc.GaussianBlur(work, blurred, Size(0.0, 0.0), 1.2)
            Core.addWeighted(work, 1.6, blurred, -.6, 0.0, work)
            work.convertTo(destination, rgb.type())
        } finally {
            owned.forEach(Mat::release)
        }
    }
}
