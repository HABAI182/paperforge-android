package cn.paperforge.app.imaging

import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Checks the detector's page against the photo's own edges. A page lies on the desk with a straight edge on every
 * side, so its outline is four long straight edges. When the detector's quad has hardly any edge along one of its
 * sides, it has found something inside the page, such as the light panel on a book's cover, and the largest
 * outline of four strong straight edges that holds it is the page.
 *
 * Measured on the owner's photos: the detector took only the cover panel of a blue book, and a skewed half of a
 * drawing sheet; both had a side with edges along under 10% of it. The eight earlier photos, whose quads were
 * right, all kept the detector's quad.
 */
internal object EdgeQuad {
    const val VERSION = "edge-quad-1"
    private const val WORK = 900.0

    private class LiveCheck(val detected: Quad, val result: Quad?, val at: Long)

    @Volatile private var lastLive: LiveCheck? = null
    @Volatile private var lastFound: Quad? = null

    private fun near(a: Quad, b: Quad) = a.points.zip(b.points).all { (p, q) -> hypot(p.x - q.x, p.y - q.y) < .03f }

    /**
     * The camera's outline. While the camera moves, the quick detector's outline is shown. Once that outline has
     * held still for two frames, the frame goes through the capture's own detection ([ScanProcessor.detectFrame]),
     * so what is shown is what the capture will cut; that answer stands while the quick outline stays where it
     * was, and is worked out again every 3 seconds. The quick outline alone showed a cover's light panel while
     * the capture cut the whole cover.
     */
    fun liveSteady(frame: Mat): DetectionResult {
        val found = LearnedDocumentDetector.detect(frame, preview = true)
        val quad = found.quad
        val steady = quad != null && lastFound?.let { near(it, quad) } == true
        lastFound = quad
        if (quad == null) return found
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastLive
        val same = last != null && near(last.detected, quad)
        val better =
            if (same && (now - last!!.at < 3000 || !steady)) last.result
            else if (steady)
                runCatching { ScanProcessor.detectFrame(frame).quad }.getOrNull().also { lastLive = LiveCheck(quad, it, now) }
            else null
        return if (better == null) found else found.copy(quad = better, method = found.method + "+capture-crop")
    }

    /**
     * The camera's live outline: the detector on a preview [frame] (RGBA, upright), or its [detection] when
     * already made, checked against the frame's own edges as a captured photo is. Without the check the
     * outline showed only the light panel of a blue book cover while the saved page was the whole cover.
     */
    fun live(frame: Mat, detection: DetectionResult? = null): DetectionResult {
        val found = detection ?: LearnedDocumentDetector.detect(frame, preview = true)
        // Live frames are checked small, which took the check from about a second to a fraction of that on
        // the emulator. The live outline is rough, so it is replaced only by an outline at least twice its
        // size: the cover panel and the half drawing sheet were under half of the page, while on a stack of
        // sheets a rough top sheet was swapped for the stack at under 1.5 times its size.
        val better =
            found.quad?.let {
                runCatching {
                        check(
                            frame,
                            it,
                            ScanConfig.float("edgeQuadLiveWork", 480f).toDouble(),
                            ScanConfig.float("edgeQuadLiveGrowth", 2f).toDouble(),
                        )
                    }
                    .getOrNull()
            } ?: return found
        return found.copy(quad = better, method = found.method + "+" + VERSION)
    }

    /**
     * A better quad than [detected] for [image] (RGBA), or null when the detector's quad stands. The image is
     * checked at [work] pixels, and a replacement must be at least [growth] times the detector's area.
     */
    fun check(image: Mat, detected: Quad, work: Double = WORK, growth: Double = 1.3): Quad? {
        if (ScanConfig.int("edgeQuad", 1) == 0 || !detected.valid()) return null
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        try {
            val f = work / max(image.cols(), image.rows())
            val small = mat()
            Imgproc.resize(image, small, Size(), f, f, Imgproc.INTER_AREA)
            val w = small.cols()
            val h = small.rows()
            val rgb = mat()
            Imgproc.cvtColor(small, rgb, if (small.channels() == 4) Imgproc.COLOR_RGBA2RGB else Imgproc.COLOR_BGR2RGB)
            val float = mat()
            rgb.convertTo(float, CvType.CV_32F)
            Imgproc.GaussianBlur(float, float, Size(0.0, 0.0), 1.5)
            val channels = ArrayList<Mat>().also { Core.split(float, it) }
            owned += channels
            val n = w * h
            val mag = FloatArray(n)
            val gx = FloatArray(n)
            val gy = FloatArray(n)
            val sx = mat()
            val sy = mat()
            val bx = FloatArray(n)
            val by = FloatArray(n)
            for (c in channels) {
                Imgproc.Sobel(c, sx, CvType.CV_32F, 1, 0, 3)
                Imgproc.Sobel(c, sy, CvType.CV_32F, 0, 1, 3)
                sx.get(0, 0, bx)
                sy.get(0, 0, by)
                // Compared squared: hypot() per pixel and channel was most of the check's time.
                for (i in 0 until n) {
                    val m = bx[i] * bx[i] + by[i] * by[i]
                    if (m > mag[i]) {
                        mag[i] = m
                        gx[i] = bx[i]
                        gy[i] = by[i]
                    }
                }
            }
            for (i in 0 until n) mag[i] = sqrt(mag[i])
            val threshold = mag.copyOf().also { it.sort() }[(n * .9).toInt()]
            // The weakest side's share of points with an edge along it. Once a side cannot reach [floor], the
            // quad is out, and counting stops there: most candidate quads fail on their first side.
            fun support(q: Array<DoubleArray>, floor: Double = 0.0): Double {
                var weakest = 1.0
                for (k in 0 until 4) {
                    val a = q[k]
                    val b = q[(k + 1) % 4]
                    val len = hypot(b[0] - a[0], b[1] - a[1]).coerceAtLeast(1e-6)
                    val dx = (b[0] - a[0]) / len
                    val dy = (b[1] - a[1]) / len
                    val nx = -dy
                    val ny = dx
                    val samples = max(20, (len / 4).toInt())
                    var hits = 0
                    for (s in 0 until samples) {
                        if (hits + samples - s < floor * samples) return (hits + samples - s).toDouble() / samples
                        val t = .05 + .9 * s / (samples - 1)
                        val px = a[0] + (b[0] - a[0]) * t
                        val py = a[1] + (b[1] - a[1]) * t
                        var hit = false
                        for (o in -3..3) {
                            val x = (px + nx * o).toInt()
                            val y = (py + ny * o).toInt()
                            if (x !in 0 until w || y !in 0 until h) continue
                            val i = y * w + x
                            if (mag[i] <= threshold) continue
                            // Only an edge running along the side counts, not one crossing it.
                            if (abs(gx[i] * nx + gy[i] * ny) / (mag[i] + 1e-6) > .9) {
                                hit = true
                                break
                            }
                        }
                        if (hit) hits++
                    }
                    weakest = min(weakest, hits.toDouble() / samples)
                    if (weakest < floor) return weakest
                }
                return weakest
            }
            val det = Array(4) { doubleArrayOf(detected.points[it].x * (w - 1.0), detected.points[it].y * (h - 1.0)) }
            if (support(det) >= ScanConfig.float("edgeQuadDetectorWeak", .3f)) return null
            val detArea = area(det)
            // Long straight edges, merged by direction and offset.
            val mask = mat()
            mask.create(h, w, CvType.CV_8UC1)
            mask.put(0, 0, ByteArray(n) { if (mag[it] > threshold) 255.toByte() else 0 })
            val lines = mat()
            Imgproc.HoughLinesP(mask, lines, 1.0, PI / 360, 80, .15 * min(w, h), 12.0)
            if (lines.rows() == 0) return null
            class Line(val nx: Double, val ny: Double, val rho: Double, var length: Double)
            val groups = arrayOf(ArrayList<Line>(), ArrayList<Line>())
            val seg = IntArray(4)
            for (r in 0 until lines.rows()) {
                lines.get(r, 0, seg)
                val (x1, y1, x2, y2) = seg.map(Int::toDouble)
                val angle = (Math.toDegrees(atan2(y2 - y1, x2 - x1)) + 180) % 180
                val g = if (angle < 45 || angle > 135) 0 else 1
                val length = hypot(x2 - x1, y2 - y1)
                val nx = -(y2 - y1) / length
                val ny = (x2 - x1) / length
                val rho = nx * x1 + ny * y1
                val same = groups[g].firstOrNull { abs(abs(it.nx * nx + it.ny * ny) - 1) < .002 && abs(it.rho - rho * sign(it.nx * nx + it.ny * ny)) < 6 }
                if (same != null) same.length += length else groups[g] += Line(nx, ny, rho, length)
            }
            val across = groups[0].sortedByDescending { it.length }.take(10)
            val down = groups[1].sortedByDescending { it.length }.take(10)
            fun meet(a: Line, b: Line): DoubleArray? {
                val d = a.nx * b.ny - a.ny * b.nx
                if (abs(d) < 1e-9) return null
                return doubleArrayOf((a.rho * b.ny - a.ny * b.rho) / d, (a.nx * b.rho - a.rho * b.nx) / d)
            }
            var best: Array<DoubleArray>? = null
            var bestScore = 0.0
            val minimum = ScanConfig.float("edgeQuadSupport", .6f)
            for (i in across.indices) for (j in i + 1 until across.size) for (k in down.indices) for (l in k + 1 until down.size) {
                val raw = listOf(meet(across[i], down[k]), meet(across[i], down[l]), meet(across[j], down[l]), meet(across[j], down[k]))
                if (raw.any { it == null }) continue
                val q = order(raw.map { it!! })
                if (q.any { it[0] < -.03 * w || it[0] > 1.03 * w || it[1] < -.03 * h || it[1] > 1.03 * h }) continue
                val a = area(q)
                if (a < .12 * w * h || a < growth * detArea || !convex(q)) continue
                val s = support(q, minimum.toDouble())
                if (s < minimum) continue
                if (overlap(q, det) < .85 * detArea) continue
                val score = a * s.pow(4)
                if (score > bestScore) {
                    bestScore = score
                    best = q
                }
            }
            val found = best ?: return null
            return Quad(found.map { PointN((it[0] / (w - 1)).toFloat().coerceIn(0f, 1f), (it[1] / (h - 1)).toFloat().coerceIn(0f, 1f)) })
        } finally {
            owned.forEach(Mat::release)
        }
    }

    /** Top-left first, then clockwise on screen. */
    private fun order(points: List<DoubleArray>): Array<DoubleArray> {
        val cx = points.sumOf { it[0] } / 4
        val cy = points.sumOf { it[1] } / 4
        val sorted = points.sortedBy { atan2(it[1] - cy, it[0] - cx) }
        val start = sorted.indices.minBy { sorted[it][0] + sorted[it][1] }
        return Array(4) { sorted[(start + it) % 4] }
    }

    private fun area(q: Array<DoubleArray>): Double {
        var s = 0.0
        for (k in 0 until 4) s += q[k][0] * q[(k + 1) % 4][1] - q[(k + 1) % 4][0] * q[k][1]
        return abs(s) / 2
    }

    private fun convex(q: Array<DoubleArray>): Boolean {
        var sign = 0
        for (k in 0 until 4) {
            val a = q[k]
            val b = q[(k + 1) % 4]
            val c = q[(k + 2) % 4]
            val z = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
            val s = z.sign.toInt()
            if (s == 0) continue
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun overlap(a: Array<DoubleArray>, b: Array<DoubleArray>): Double {
        val pa = MatOfPoint2f(*a.map { Point(it[0], it[1]) }.toTypedArray())
        val pb = MatOfPoint2f(*b.map { Point(it[0], it[1]) }.toTypedArray())
        val out = Mat()
        return try {
            Imgproc.intersectConvexConvex(pa, pb, out, true).toDouble()
        } finally {
            pa.release()
            pb.release()
            out.release()
        }
    }
}
