package cn.paperforge.app.pdf

import android.graphics.Bitmap
import cn.paperforge.app.imaging.ScanConfig
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Watermarks merged into scanned page images. Two estimates, both conservative:
 * - several pages with the same overlay at the same place: per pixel, the median across pages of how much darker
 *   than the local paper each page is. Page content varies from page to page, a stamped overlay does not, so the
 *   median keeps only the overlay. The overlay is then divided out (multiply blend), channel by channel.
 * - one page and a user-drawn region: pixels lighter than ink and darker than paper (or clearly coloured while the
 *   ink is neutral) are returned to the local paper colour; ink pixels are left as they are.
 * Where the overlay hid the page almost completely nothing is invented: those pixels stay and are counted.
 * Only the page images are replaced; text layers, fonts, annotations and page geometry stay.
 */
object ScanWatermarkRestore {
    private const val WORK_EDGE = 1400
    private const val MIN_FACTOR = .35f

    data class PageImage(val page: Int, val name: COSName, val width: Int, val height: Int, val bilevel: Boolean)

    /** Overlay factors in work resolution: per channel, observed = original x factor. */
    class Overlay(val factors: Mat, val mask: Mat, val coverage: Float, val unrecoverable: Float, val pages: Int) {
        fun release() {
            factors.release()
            mask.release()
        }
    }

    data class Report(
        val pages: Int,
        val coverage: Float,
        val unrecoverable: Float,
        val skipped: List<String>,
        val millis: Map<String, Long> = emptyMap(),
    )

    /** The page-filling image of every page (null where there is none). */
    fun pageImages(pdf: PDDocument): List<PageImage?> =
        (0 until pdf.numberOfPages).map { n ->
            val page = pdf.getPage(n)
            val res = page.resources ?: return@map null
            val box = page.cropBox
            // A scan is drawn over (nearly) the whole page; choose the largest image placed on it.
            res.xObjectNames.mapNotNull { name ->
                val x = runCatching { res.getXObject(name) }.getOrNull() as? PDImageXObject ?: return@mapNotNull null
                if (x.width * x.height < 300_000) return@mapNotNull null
                val ratio = x.width.toFloat() / x.height
                val pageRatio = box.width / box.height
                val rotatedRatio = box.height / box.width
                if (kotlin.math.abs(ratio - pageRatio) > .08f && kotlin.math.abs(ratio - rotatedRatio) > .08f) return@mapNotNull null
                PageImage(n, name, x.width, x.height, x.bitsPerComponent == 1 || x.isStencil)
            }.maxByOrNull { it.width * it.height }
        }

    /** An ARGB_8888 copy of an image XObject (PDFBox caches the decoded bitmap: never recycle that one). */
    private fun pixels(image: PDImageXObject): Bitmap = image.image.copy(Bitmap.Config.ARGB_8888, false)

    private fun work(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        val scale = WORK_EDGE.toDouble() / max(rgb.cols(), rgb.rows())
        if (scale < 1) Imgproc.resize(rgb, rgb, Size(rgb.cols() * scale, rgb.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        val f = Mat()
        rgb.convertTo(f, CvType.CV_32FC3, 1 / 255.0)
        rgb.release()
        return f
    }

    /** Local paper colour: a large max filter removes ink and overlay strokes, a blur smooths it. */
    private fun paper(image: Mat, kernel: Double = 31.0, sigma: Double = 12.0): Mat {
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kernel, kernel))
        val bg = Mat()
        Imgproc.dilate(image, bg, k)
        Imgproc.GaussianBlur(bg, bg, Size(0.0, 0.0), sigma)
        Core.max(bg, Scalar(.05, .05, .05), bg)
        return bg
    }

    /** Estimates a repeated overlay from [pages] (bitmaps of the same size and placement). */
    fun estimate(pages: List<Bitmap>): Overlay? {
        if (pages.size < 3) return null
        val ratios = pages.map { b ->
            val img = work(b)
            val bg = paper(img)
            val r = Mat()
            Core.divide(img, bg, r)
            Core.min(r, Scalar(1.0, 1.0, 1.0), r)
            img.release()
            bg.release()
            r
        }
        val size = ratios.first().size()
        if (ratios.any { it.size() != size }) {
            ratios.forEach { it.release() }
            return null
        }
        // Per-pixel median across pages with an odd-even transposition network of whole-image min/max operations.
        val n = ratios.size
        val low = Mat()
        for (round in 0 until n) {
            var i = round % 2
            while (i + 1 < n) {
                Core.min(ratios[i], ratios[i + 1], low)
                Core.max(ratios[i], ratios[i + 1], ratios[i + 1])
                low.copyTo(ratios[i])
                i += 2
            }
        }
        low.release()
        val factors = Mat()
        if (n % 2 == 1) ratios[n / 2].copyTo(factors)
        else Core.addWeighted(ratios[n / 2 - 1], .5, ratios[n / 2], .5, 0.0, factors)
        ratios.forEach { it.release() }
        Imgproc.GaussianBlur(factors, factors, Size(0.0, 0.0), .6)
        return overlay(factors, n)
    }

    private fun overlay(factors: Mat, pages: Int): Overlay? {
        // Thin repeated dark lines (rules, printed grids, frame lines) are page structure, not overlay: a closing
        // removes them from the estimate, so they are neither masked nor divided away under the watermark.
        val close = ScanConfig.int("watermarkLineClose", 9).toDouble()
        if (close > 1)
            Imgproc.morphologyEx(factors, factors, Imgproc.MORPH_CLOSE,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(close, close)))
        val gray = Mat()
        Imgproc.cvtColor(factors, gray, Imgproc.COLOR_RGB2GRAY)
        // Darkening of at least 5 % marks the overlay.
        val raw = Mat()
        Imgproc.threshold(gray, raw, .95, 1.0, Imgproc.THRESH_BINARY_INV)
        raw.convertTo(raw, CvType.CV_8U)
        // Opaque repeated blobs (logos, stamps, letterheads) are content: drop whole components that are not
        // translucent, and specks.
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(raw, labels, stats, centroids, 8, CvType.CV_32S)
        val channels = ArrayList<Mat>()
        Core.split(factors, channels)
        val darkest = Mat()
        Core.min(channels[0], channels[1], darkest)
        Core.min(darkest, channels[2], darkest)
        channels.forEach { it.release() }
        val rows = raw.rows()
        val cols = raw.cols()
        val lab = IntArray(rows * cols).also { labels.get(0, 0, it) }
        val dark = FloatArray(rows * cols).also { darkest.get(0, 0, it) }
        // Median per component from a 64-bin histogram of its darkest-channel factor.
        val bins = 64
        val hist = IntArray(count * bins)
        val sizes = IntArray(count)
        for (i in lab.indices) {
            val c = lab[i]
            if (c <= 0) continue
            hist[c * bins + (dark[i].coerceIn(0f, .9999f) * bins).toInt()]++
            sizes[c]++
        }
        val keep = BooleanArray(count)
        for (c in 1 until count) {
            if (sizes[c] < 30) continue
            var seen = 0
            var b = 0
            while (b < bins) {
                seen += hist[c * bins + b]
                if (seen * 2 >= sizes[c]) break
                b++
            }
            keep[c] = (b + .5f) / bins >= MIN_FACTOR
        }
        val m = ByteArray(rows * cols) { i -> if (lab[i] > 0 && keep[lab[i]]) 1 else 0 }
        val mask = Mat(rows, cols, CvType.CV_8U).also { it.put(0, 0, m) }
        Core.multiply(mask, Scalar(255.0), mask)
        Imgproc.dilate(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        listOf(raw, labels, stats, centroids, darkest).forEach { it.release() }
        val covered = Core.countNonZero(mask)
        val total = rows * cols
        val opaque = Mat()
        Imgproc.threshold(gray, opaque, MIN_FACTOR.toDouble(), 1.0, Imgproc.THRESH_BINARY_INV)
        opaque.convertTo(opaque, CvType.CV_8U, 255.0)
        Core.bitwise_and(opaque, mask, opaque)
        val hidden = Core.countNonZero(opaque)
        opaque.release()
        gray.release()
        if (covered < total * .001) {
            mask.release()
            factors.release()
            return null
        }
        return Overlay(factors, mask, covered.toFloat() / total, if (covered == 0) 0f else hidden.toFloat() / covered, pages)
    }

    /** One page and a region (normalised l, t, r, b): returns pixels between ink and paper to paper. */
    fun estimateRegion(page: Bitmap, region: FloatArray): Overlay? {
        val img = work(page)
        val bg = paper(img)
        val r = Mat()
        Core.divide(img, bg, r)
        Core.min(r, Scalar(1.0, 1.0, 1.0), r)
        val roi = Mat.zeros(r.size(), CvType.CV_8U)
        Imgproc.rectangle(
            roi,
            Point((region[0] * r.cols()).toDouble(), (region[1] * r.rows()).toDouble()),
            Point((region[2] * r.cols()).toDouble(), (region[3] * r.rows()).toDouble()),
            Scalar(255.0),
            -1,
        )
        val gray = Mat()
        Imgproc.cvtColor(r, gray, Imgproc.COLOR_RGB2GRAY)
        // Ink is much darker than any light overlay; keep it untouched.
        val band = Mat()
        Core.inRange(gray, Scalar(.42), Scalar(.95), band)
        Core.bitwise_and(band, roi, band)
        val factors = Mat(r.size(), CvType.CV_32FC3, Scalar(1.0, 1.0, 1.0))
        r.copyTo(factors, band)
        img.release()
        bg.release()
        r.release()
        roi.release()
        gray.release()
        band.release()
        return overlay(factors, 1)
    }

    /** Divides the overlay out of [page] at full resolution. */
    fun restore(page: Bitmap, overlay: Overlay): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(page, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val f = Mat()
        Imgproc.resize(overlay.factors, f, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val mask = Mat()
        Imgproc.resize(overlay.mask, mask, rgb.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
        Core.max(f, Scalar(MIN_FACTOR.toDouble(), MIN_FACTOR.toDouble(), MIN_FACTOR.toDouble()), f)
        val src = Mat()
        rgb.convertTo(src, CvType.CV_32FC3)
        val fixed = Mat()
        Core.divide(src, f, fixed)
        Core.min(fixed, Scalar(255.0, 255.0, 255.0), fixed)
        // Never lighter than this page's local paper: dividing at a stroke's soft edge would otherwise leave a
        // bright outline where the watermark was.
        // The paper is smooth: estimated at half the work size, then enlarged.
        val small = work(page)
        Imgproc.resize(small, small, Size(small.cols() / 2.0, small.rows() / 2.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val sheet = paper(small, 15.0, 6.0)
        small.release()
        val sheetFull = Mat()
        Imgproc.resize(sheet, sheetFull, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        sheet.release()
        Core.multiply(sheetFull, Scalar(255.0, 255.0, 255.0), sheetFull)
        Core.min(fixed, sheetFull, fixed)
        sheetFull.release()
        fixed.convertTo(fixed, CvType.CV_8UC3)
        fixed.copyTo(rgb, mask)
        Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
        val out = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, out)
        listOf(rgba, rgb, f, mask, src, fixed).forEach { it.release() }
        return out
    }

    /**
     * Writes a copy of [input] with the overlay removed from the scan images of [pages] (null: every scanned page).
     * [region] (normalised l,t,r,b on [regionPage]) selects the single-page method.
     */
    fun apply(input: File, output: File, pages: Set<Int>?, region: FloatArray? = null, regionPage: Int = 0): Report {
        require(input.canonicalFile != output.canonicalFile) { "输出必须是新文件" }
        val part = File(output.path + ".part")
        try {
            val millis = linkedMapOf<String, Long>()
            fun <T> timed(name: String, block: () -> T): T {
                val t0 = System.nanoTime()
                return block().also { millis[name] = (millis[name] ?: 0L) + (System.nanoTime() - t0) / 1_000_000 }
            }
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                require(!pdf.isEncrypted || pdf.currentAccessPermission.canModify()) { "此 PDF 设置了禁止修改的权限，不能去除水印" }
                val images = pageImages(pdf)
                val skipped = mutableListOf<String>()
                val usable = images.filterNotNull().filter { !it.bilevel }
                images.filterNotNull().filter { it.bilevel }.forEach { skipped += "第 ${it.page + 1} 页是黑白二值扫描，水印与文字无法区分" }
                var sameSize = usable
                val overlay =
                    if (region != null) {
                        val target = usable.firstOrNull { it.page == regionPage } ?: error("这一页没有扫描图像")
                        val bitmap = pixels(pdf.getPage(target.page).resources.getXObject(target.name) as PDImageXObject)
                        estimateRegion(bitmap, region).also { bitmap.recycle() }
                    } else {
                        val size = usable.groupBy { it.width to it.height }.maxByOrNull { it.value.size }?.value.orEmpty()
                        require(size.size >= 3) { "至少需要 3 页同样大小的扫描页才能估计重复水印；单页请框选区域" }
                        sameSize = size
                        // Up to 9 pages spread over the document are enough for the median.
                        val sample = size.filterIndexed { i, _ -> size.size <= 9 || i % ((size.size + 8) / 9) == 0 }.take(9)
                        val bitmaps = timed("decode") { sample.map { pixels(pdf.getPage(it.page).resources.getXObject(it.name) as PDImageXObject) } }
                        timed("estimate") { estimate(bitmaps) }.also { bitmaps.forEach(Bitmap::recycle) }
                    } ?: error("没有找到可估计的水印覆盖层，原图保留")
                try {
                    var done = 0
                    val targets =
                        if (region != null) usable.filter { it.page == regionPage }
                        else sameSize.filter { pages == null || it.page in pages }
                    for (t in targets) {
                        val page = pdf.getPage(t.page)
                        val image = page.resources.getXObject(t.name) as PDImageXObject
                        val bitmap = timed("decode") { pixels(image) }
                        val fixed = timed("restore") { restore(bitmap, overlay) }
                        bitmap.recycle()
                        val jpeg = image.cosObject.filters?.let { it.toString().contains("DCTDecode") } == true
                        val replaced = timed("encode") {
                            if (jpeg) JPEGFactory.createFromImage(pdf, fixed, .92f) else LosslessFactory.createFromImage(pdf, fixed)
                        }
                        fixed.recycle()
                        page.resources.put(t.name, replaced)
                        done++
                    }
                    timed("save") { pdf.save(part) }
                    check(part.renameTo(output)) { "无法保存副本" }
                    return Report(done, overlay.coverage, overlay.unrecoverable, skipped, millis)
                } finally {
                    overlay.release()
                }
            }
        } finally {
            part.delete()
        }
    }

    /** Before/after bitmaps of one page for preview (long edge [edge]). */
    fun preview(input: File, page: Int, region: FloatArray?, edge: Int = 1600): Pair<Bitmap, Bitmap>? =
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
            val images = pageImages(pdf)
            val target = images.getOrNull(page) ?: return null
            if (target.bilevel) return null
            val overlay =
                if (region != null) {
                    val b = pixels(pdf.getPage(page).resources.getXObject(target.name) as PDImageXObject)
                    estimateRegion(b, region).also { b.recycle() }
                } else {
                    val same = images.filterNotNull().filter { it.width == target.width && it.height == target.height && !it.bilevel }
                    if (same.size < 3) return null
                    val sample = same.filterIndexed { i, _ -> same.size <= 9 || i % ((same.size + 8) / 9) == 0 }
                    val bitmaps = sample.map { pixels(pdf.getPage(it.page).resources.getXObject(it.name) as PDImageXObject) }
                    estimate(bitmaps).also { bitmaps.forEach(Bitmap::recycle) }
                } ?: return null
            try {
                val full = pixels(pdf.getPage(page).resources.getXObject(target.name) as PDImageXObject)
                val fixed = restore(full, overlay)
                val scale = edge.toFloat() / max(full.width, full.height)
                fun small(b: Bitmap) =
                    if (scale >= 1) b else Bitmap.createScaledBitmap(b, (b.width * scale).roundToInt(), (b.height * scale).roundToInt(), true).also { if (it !== b) b.recycle() }
                small(full) to small(fixed)
            } finally {
                overlay.release()
            }
        }
}
