package cn.paperforge.app.imaging

import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Bounded GrabCut refinement around an already accepted edge-connected skin silhouette. */
internal object FingerFringe {
    fun refine(rgb: Mat, silhouette: ByteArray): ByteArray {
        val w = rgb.cols()
        val h = rgb.rows()
        val marked = silhouette.indices.filter { silhouette[it] != 0.toByte() }
        if (marked.size < 24) return silhouette
        val radius = ScanConfig.int("workbookFingerFringeRadius", 10).coerceIn(3, 16)
        val x = (marked.minOf { it % w } - radius - 2).coerceAtLeast(0)
        val y = (marked.minOf { it / w } - radius - 2).coerceAtLeast(0)
        val right = (marked.maxOf { it % w } + radius + 3).coerceAtMost(w)
        val bottom = (marked.maxOf { it / w } + radius + 3).coerceAtMost(h)
        val rect = Rect(x, y, right - x, bottom - y)
        if (rect.area() > 180_000) return silhouette
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val image = rgb.submat(rect).also(owned::add)
        val seed = mat()
        val core = mat()
        val band = mat()
        val labels = mat()
        val connected = mat()
        val foreground = mat()
        val background = mat()
        val kernel =
            Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0)).also(owned::add)
        val wide =
            Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE,
                    Size((radius * 2 + 1).toDouble(), (radius * 2 + 1).toDouble()),
                )
                .also(owned::add)
        try {
            val pixels =
                ByteArray(rect.width * rect.height) {
                    silhouette[(it / rect.width + y) * w + it % rect.width + x]
                }
            seed.create(rect.height, rect.width, CvType.CV_8UC1)
            seed.put(0, 0, pixels)
            Imgproc.erode(seed, core, kernel)
            Imgproc.dilate(seed, band, wide)
            val sure = ByteArray(pixels.size).also { core.get(0, 0, it) }
            if (sure.count { it != 0.toByte() } < 12) return silhouette
            val nearby = ByteArray(pixels.size).also { band.get(0, 0, it) }
            val mask =
                ByteArray(pixels.size) {
                    (when {
                            sure[it] != 0.toByte() -> Imgproc.GC_FGD
                            pixels[it] != 0.toByte() -> Imgproc.GC_PR_FGD
                            nearby[it] != 0.toByte() -> Imgproc.GC_PR_BGD
                            else -> Imgproc.GC_BGD
                        })
                        .toByte()
                }
            labels.create(rect.height, rect.width, CvType.CV_8UC1)
            labels.put(0, 0, mask)
            Imgproc.grabCut(
                image,
                labels,
                Rect(),
                background,
                foreground,
                ScanConfig.int("workbookFingerGraphCutIterations", 2).coerceIn(1, 3),
                Imgproc.GC_INIT_WITH_MASK,
            )
            labels.get(0, 0, mask)
            if (ScanConfig.int("workbookFingerFringePadding", 1) != 0) {
                // One bounded contour pixel includes the neutral antialias/shadow rim.
                seed.put(
                    0,
                    0,
                    ByteArray(mask.size) { if (mask[it].toInt() and 1 != 0) 255.toByte() else 0 },
                )
                Imgproc.dilate(seed, core, kernel)
                val padded = ByteArray(mask.size).also { core.get(0, 0, it) }
                for (i in mask.indices) if (padded[i] != 0.toByte() && nearby[i] != 0.toByte())
                    mask[i] = Imgproc.GC_PR_FGD.toByte()
            }
            // GrabCut can also label a disconnected pencil mark as foreground. Retain only
            // foreground components actually connected to the accepted skin seed.
            seed.put(
                0,
                0,
                ByteArray(mask.size) { if (mask[it].toInt() and 1 != 0) 255.toByte() else 0 },
            )
            val count = Imgproc.connectedComponents(seed, connected, 8, CvType.CV_32S)
            val ids = IntArray(mask.size).also { connected.get(0, 0, it) }
            val accepted = BooleanArray(count)
            for (i in ids.indices) if (sure[i] != 0.toByte()) accepted[ids[i]] = true
            accepted[0] = false
            return silhouette.copyOf().also { result ->
                for (i in mask.indices) if (nearby[i] != 0.toByte() && accepted[ids[i]])
                    result[(i / rect.width + y) * w + i % rect.width + x] = 255.toByte()
            }
        } finally {
            owned.forEach(Mat::release)
        }
    }
}
