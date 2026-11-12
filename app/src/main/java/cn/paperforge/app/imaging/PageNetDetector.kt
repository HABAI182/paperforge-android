package cn.paperforge.app.imaging

import ai.onnxruntime.*
import android.content.Context
import cn.paperforge.app.model.*
import java.nio.FloatBuffer
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * PaperForge PageNet: finds the top sheet in a photo. An original network, trained by
 * scripts/train_page_net.py on generated scenes; provenance is in assets/pagenet/pagenet.json.
 *
 * The network draws the sheet as a mask. A quad comes from that mask: the largest confident region
 * gives four corners, then a straight line is refit along each side so a bent edge or a finger does
 * not drag a corner. Mirrors `quad_from_mask` in the training script.
 *
 * The network favours printed content and tends to drop blank paper, which cuts off margins. So a
 * still capture does not trust the mask's outline: the mask seeds a GrabCut on the photo, and blank
 * paper the mask missed is recovered because it looks like the rest of the sheet and not like the
 * desk. See [recover]. Measured by scripts/eval_page_crop.py.
 */
object PageNetDetector {
    const val VERSION = "pagenet-3"
    private const val INPUT = 320
    private const val OUTPUT = 160
    private const val RECOVER = 320
    private var context: Context? = null
    private var session: OrtSession? = null
    private var failure: String? = null
    private val environment by lazy { OrtEnvironment.getEnvironment() }

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    private fun session(): OrtSession {
        session?.let {
            return it
        }
        val bytes = checkNotNull(context).assets.open("pagenet/pagenet.onnx").use { it.readBytes() }
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(bytes, options).also { session = it }
        }
    }

    /**
     * Sigmoid mask at [OUTPUT]x[OUTPUT] for the [area] of [source]; null if the model is unavailable.
     * Downsampling must be area-averaged, as in training: a bilinear shrink aliases fine print into
     * texture the network has never seen.
     */
    @Synchronized
    private fun mask(source: Mat, area: Rect): FloatArray? {
        if (context == null || failure != null) return null
        try {
            val tensorValues = FloatArray(3 * INPUT * INPUT)
            val crop = source.submat(area)
            val small = Mat()
            val rgb = Mat()
            try {
                Imgproc.resize(crop, small, Size(INPUT.toDouble(), INPUT.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                when (small.channels()) {
                    4 -> Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
                    1 -> Imgproc.cvtColor(small, rgb, Imgproc.COLOR_GRAY2RGB)
                    else -> small.copyTo(rgb)
                }
                val pixels = ByteArray(3 * INPUT * INPUT).also { rgb.get(0, 0, it) }
                input.set(pixels)
                val plane = INPUT * INPUT
                for (i in 0 until plane) for (c in 0..2)
                    tensorValues[c * plane + i] = (pixels[3 * i + c].toInt() and 255) / 255f
            } finally {
                listOf(crop, small, rgb).forEach(Mat::release)
            }
            OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(tensorValues),
                    longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong()),
                )
                .use { tensor ->
                    session().run(mapOf("image" to tensor)).use { output ->
                        @Suppress("UNCHECKED_CAST")
                        val logits = output[0].value as Array<Array<Array<FloatArray>>>
                        val channel = logits[0][0]
                        val edge = logits[0][1]
                        boundary.set(FloatArray(OUTPUT * OUTPUT) { i -> 1f / (1f + exp(-edge[i / OUTPUT][i % OUTPUT])) })
                        return FloatArray(OUTPUT * OUTPUT) { i ->
                            val value = channel[i / OUTPUT][i % OUTPUT]
                            1f / (1f + exp(-value))
                        }
                    }
                }
        } catch (e: Exception) {
            failure = e.javaClass.simpleName + ": " + e.message
            android.util.Log.w("PaperForgeDetector", "PageNet unavailable", e)
            return null
        }
    }

    /** The sheet-boundary map of the last [mask] call on this thread (sigmoid, [OUTPUT]x[OUTPUT]). */
    private val boundary = ThreadLocal<FloatArray?>()

    /** The [INPUT]x[INPUT] RGB the network saw in the last [mask] call on this thread. */
    private val input = ThreadLocal<ByteArray?>()

    /**
     * Moves each side of [quad] inward onto the strongest ridge of the network's own sheet-boundary map, within
     * [reach] of the frame. A faint fringe of the mask onto a neighbouring sheet or a pale desk pushes a side
     * outward, while the boundary map still marks the sheet's real edge. Sides only move inward, and a move must
     * beat the current side by more the farther it goes, so a sheet whose mask is right keeps its quad.
     */
    private fun snapInward(quad: Quad, edge: FloatArray): Quad {
        val n = OUTPUT
        val blurred = Mat()
        val map = Mat(n, n, CvType.CV_32FC1)
        val values: FloatArray
        try {
            map.put(0, 0, edge)
            Imgproc.GaussianBlur(map, blurred, Size(3.0, 3.0), 0.0)
            values = FloatArray(n * n).also { blurred.get(0, 0, it) }
        } finally {
            map.release()
            blurred.release()
        }
        fun at(x: Double, y: Double): Float {
            val x0 = floor(x).toInt().coerceIn(0, n - 2)
            val y0 = floor(y).toInt().coerceIn(0, n - 2)
            val fx = (x - x0).toFloat().coerceIn(0f, 1f)
            val fy = (y - y0).toFloat().coerceIn(0f, 1f)
            val a = values[y0 * n + x0] * (1 - fx) + values[y0 * n + x0 + 1] * fx
            val b = values[(y0 + 1) * n + x0] * (1 - fx) + values[(y0 + 1) * n + x0 + 1] * fx
            return a * (1 - fy) + b * fy
        }
        fun mean(ax: Double, ay: Double, bx: Double, by: Double): Float {
            var total = 0f
            var count = 0
            for (k in 0 until 48) {
                val t = .1 + .8 * k / 47
                val x = ax + (bx - ax) * t
                val y = ay + (by - ay) * t
                if (x < 0 || y < 0 || x > n - 1 || y > n - 1) continue
                total += at(x, y)
                count++
            }
            return if (count < 24) -1f else total / count
        }
        fun continuity(ax: Double, ay: Double, bx: Double, by: Double): Float {
            var on = 0
            var count = 0
            for (k in 0 until 48) {
                val t = .1 + .8 * k / 47
                val x = ax + (bx - ax) * t
                val y = ay + (by - ay) * t
                if (x < 0 || y < 0 || x > n - 1 || y > n - 1) continue
                if (at(x, y) > .35f) on++
                count++
            }
            return if (count < 24) 0f else on.toFloat() / count
        }
        val far = ScanConfig.float("pageNetEdgeSnapFar", .3f) * n
        val farGain = ScanConfig.float("pageNetEdgeSnapFarGain", .3f)
        val farContinuity = ScanConfig.float("pageNetEdgeSnapFarContinuity", .9f)
        val pts = quad.points.map { doubleArrayOf(it.x * n - .5, it.y * n - .5) }
        val cx = pts.sumOf { it[0] } / 4
        val cy = pts.sumOf { it[1] } / 4
        val reach = ScanConfig.float("pageNetEdgeSnapReach", .06f) * n
        val penalty = ScanConfig.float("pageNetEdgeSnapPenalty", .15f)
        val lines = (0..3).map { i ->
            val a = pts[i]
            val c = pts[(i + 1) % 4]
            val length = hypot(c[0] - a[0], c[1] - a[1]).coerceAtLeast(1e-6)
            val dx = (c[0] - a[0]) / length
            val dy = (c[1] - a[1]) / length
            var nx = -dy
            var ny = dx
            val mx = (a[0] + c[0]) / 2
            val my = (a[1] + c[1]) / 2
            if ((cx - mx) * nx + (cy - my) * ny < 0) { nx = -nx; ny = -ny }
            val here = mean(a[0], a[1], c[0], c[1])
            var best = here
            var line = doubleArrayOf(a[0], a[1], c[0], c[1])
            // A long move, in or out, only onto a ridge that is nearly continuous and clearly stronger: a drawing
            // can leave the mask unsure over part of the sheet, while its edge stays plain in the boundary map.
            var farBest = -1f
            var farLine: DoubleArray? = null
            val half = length / 2 * 1.2
            val angle = ScanConfig.int("pageNetEdgeSnapAngle", 5)
            // Half-pixel steps near the side, whole pixels for the long moves: the same answers at half the work.
            val turns = (-angle..angle).map { Math.toRadians(it.toDouble()).let { r -> doubleArrayOf(cos(r), sin(r)) } }
            var off = -far.toDouble()
            while (off <= far + 1e-6) {
                val near = off >= 0 && off <= reach
                if (!near && abs(off - Math.round(off)) > 1e-6) {
                    off += .5
                    continue
                }
                for (turn in turns) {
                    val ux = dx * turn[0] - dy * turn[1]
                    val uy = dx * turn[1] + dy * turn[0]
                    val px = mx + nx * off
                    val py = my + ny * off
                    val ax = px - ux * half
                    val ay = py - uy * half
                    val bx = px + ux * half
                    val by = py + uy * half
                    val score = mean(ax, ay, bx, by)
                    if (off >= 0 && off <= reach && score > best + penalty * (off / reach)) {
                        best = score
                        line = doubleArrayOf(ax, ay, bx, by)
                    }
                    if (far > 0 && score >= here + farGain && score > farBest && continuity(ax, ay, bx, by) >= farContinuity) {
                        farBest = score
                        farLine = doubleArrayOf(ax, ay, bx, by)
                    }
                }
                off += .5
            }
            if (farLine != null && farBest > best) farLine else line
        }
        val corners = (0..3).map { i ->
            val p = lines[(i + 3) % 4]
            val q = lines[i]
            val d1x = p[2] - p[0]; val d1y = p[3] - p[1]
            val d2x = q[2] - q[0]; val d2y = q[3] - q[1]
            val det = d1x * d2y - d1y * d2x
            if (abs(det) < 1e-9) return quad
            val t = ((q[0] - p[0]) * d2y - (q[1] - p[1]) * d2x) / det
            PointN(
                (((p[0] + d1x * t) + .5) / n).toFloat().coerceIn(0f, 1f),
                (((p[1] + d1y * t) + .5) / n).toFloat().coerceIn(0f, 1f),
            )
        }
        val snapped = Quad.ordered(corners)
        return if (snapped.valid() && snapped.area >= .4f * quad.area) snapped else quad
    }

    private class Fit(val points: List<PointN>, val confidence: Float)

    private fun threshold(probabilities: FloatArray, cut: Float) =
        ByteArray(probabilities.size) { if (probabilities[it] > cut) 1 else 0 }

    /**
     * The sheet at [OUTPUT]x[OUTPUT] (0 or 1) after a GrabCut seeded by the mask, or null if the seed is
     * unusable. What the mask is sure of stays sheet, what is far from it stays out, and the band
     * between is decided by the photo's own colours and edges. Colour order does not matter here.
     */
    private fun recover(source: Mat, probabilities: FloatArray, cut: Float): ByteArray? {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        fun ellipse(size: Int) =
            Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(size.toDouble(), size.toDouble()))
                .also(owned::add)
        fun rect(size: Int) =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(size.toDouble(), size.toDouble()))
                .also(owned::add)
        try {
            val size = Size(RECOVER.toDouble(), RECOVER.toDouble())
            val small = mat()
            Imgproc.resize(source, small, size, 0.0, 0.0, Imgproc.INTER_AREA)
            val rgb = mat()
            when (small.channels()) {
                4 -> Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
                1 -> Imgproc.cvtColor(small, rgb, Imgproc.COLOR_GRAY2RGB)
                else -> small.copyTo(rgb)
            }
            val field = mat()
            field.create(OUTPUT, OUTPUT, CvType.CV_32FC1)
            field.put(0, 0, probabilities)
            val scaled = mat()
            Imgproc.resize(field, scaled, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
            val values = FloatArray(RECOVER * RECOVER).also { scaled.get(0, 0, it) }
            val seed = mat()
            seed.create(RECOVER, RECOVER, CvType.CV_8UC1)
            seed.put(0, 0, threshold(values, cut))
            val core = mat()
            core.create(RECOVER, RECOVER, CvType.CV_8UC1)
            core.put(0, 0, threshold(values, .5f))
            Imgproc.erode(core, core, rect(9))
            val reach = ScanConfig.float("pageNetRecoverReach", .15f)
            val near = mat()
            Imgproc.dilate(seed, near, ellipse(max(3, (reach * RECOVER).toInt()) or 1))
            val seedBytes = ByteArray(values.size).also { seed.get(0, 0, it) }
            val coreBytes = ByteArray(values.size).also { core.get(0, 0, it) }
            val nearBytes = ByteArray(values.size).also { near.get(0, 0, it) }
            var sure = 0
            var excluded = 0
            val labelBytes =
                ByteArray(values.size) {
                    when {
                        coreBytes[it] != 0.toByte() -> { sure++; Imgproc.GC_FGD.toByte() }
                        seedBytes[it] != 0.toByte() -> Imgproc.GC_PR_FGD.toByte()
                        nearBytes[it] != 0.toByte() -> Imgproc.GC_PR_BGD.toByte()
                        else -> { excluded++; Imgproc.GC_BGD.toByte() }
                    }
                }
            // A sheet filling the frame leaves nothing to compare it with, so the mask stands.
            if (sure < 50 || excluded < 50) return null
            val labels = mat()
            labels.create(RECOVER, RECOVER, CvType.CV_8UC1)
            labels.put(0, 0, labelBytes)
            // GrabCut starts from a random clustering, and on a sheet lying on a same-coloured sheet
            // the start can change the answer. Fixed, so the same photo always gets the same crop.
            Core.setRNGSeed(0)
            Imgproc.grabCut(
                rgb, labels, Rect(), mat(), mat(),
                ScanConfig.int("pageNetRecoveryIterations", 2).coerceIn(1, 5),
                Imgproc.GC_INIT_WITH_MASK,
            )
            labels.get(0, 0, labelBytes)
            val sheet = mat()
            sheet.create(RECOVER, RECOVER, CvType.CV_8UC1)
            sheet.put(
                0,
                0,
                ByteArray(labelBytes.size) {
                    val label = labelBytes[it].toInt()
                    if (label == Imgproc.GC_FGD || label == Imgproc.GC_PR_FGD) 1 else 0
                },
            )
            Imgproc.morphologyEx(sheet, sheet, Imgproc.MORPH_OPEN, rect(5))
            val reduced = mat()
            Imgproc.resize(sheet, reduced, Size(OUTPUT.toDouble(), OUTPUT.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            return ByteArray(OUTPUT * OUTPUT).also { reduced.get(0, 0, it) }
        } catch (e: org.opencv.core.CvException) {
            android.util.Log.w("PaperForgeDetector", "Crop recovery unavailable", e)
            return null
        } finally {
            owned.forEach(Mat::release)
        }
    }

    /**
     * Largest region of [sheet] (0 or 1 per mask pixel), four corners, then a line refit per side.
     * Points are normalised to the mask. Confidence reads [probabilities] inside the result.
     */
    private fun fit(probabilities: FloatArray, sheet: ByteArray): Fit? {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val contours = mutableListOf<MatOfPoint>()
        try {
            val binary = mat()
            binary.create(OUTPUT, OUTPUT, CvType.CV_8UC1)
            binary.put(0, 0, sheet)
            val labels = mat()
            val stats = mat()
            val centroids = mat()
            val count = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids, 4, CvType.CV_32S)
            if (count < 2) return null
            val best = (1 until count).maxBy { stats.get(it, Imgproc.CC_STAT_AREA)[0] }
            val area = stats.get(best, Imgproc.CC_STAT_AREA)[0]
            if (area < .02 * probabilities.size) return null
            val labelValues = IntArray(probabilities.size).also { labels.get(0, 0, it) }
            val region = mat()
            region.create(OUTPUT, OUTPUT, CvType.CV_8UC1)
            region.put(
                0,
                0,
                ByteArray(labelValues.size) { if (labelValues[it] == best) 1 else 0 },
            )
            Imgproc.findContours(region, contours, mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
            val contour = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            val contourPoints = contour.toArray()
            val hullIndices = MatOfInt().also(owned::add)
            Imgproc.convexHull(contour, hullIndices)
            val hull = MatOfPoint2f(*hullIndices.toArray().map { contourPoints[it] }.toTypedArray()).also(owned::add)
            val perimeter = Imgproc.arcLength(hull, true)
            var corners: Array<Point>? = null
            for (epsilon in doubleArrayOf(.01, .015, .02, .03, .04, .06, .08, .12)) {
                val approx = MatOfPoint2f().also(owned::add)
                Imgproc.approxPolyDP(hull, approx, epsilon * perimeter, true)
                if (approx.total() == 4L) {
                    corners = approx.toArray()
                    break
                }
            }
            val chosen =
                corners
                    ?: Array(4) { Point() }.also { Imgproc.minAreaRect(hull).points(it) }
            val lines = (0..3).map { side(contourPoints, chosen[it], chosen[(it + 1) % 4]) }
            val fitted =
                (0..3).map { i ->
                    val point = intersection(lines[(i + 3) % 4], lines[i])
                    if (
                        point == null ||
                            hypot(point.x - chosen[i].x, point.y - chosen[i].y) > .12 * OUTPUT
                    )
                        chosen[i]
                    else point
                }
            // How well the straight quad explains the region, times how sure the mask is inside it.
            val polygon = mat()
            polygon.create(OUTPUT, OUTPUT, CvType.CV_8UC1)
            polygon.setTo(Scalar(0.0))
            val drawn = MatOfPoint(*fitted.map { Point(it.x, it.y) }.toTypedArray()).also(owned::add)
            Imgproc.fillConvexPoly(polygon, drawn, Scalar(1.0))
            val inside = ByteArray(probabilities.size).also { polygon.get(0, 0, it) }
            var intersection = 0
            var union = 0
            var certainty = 0f
            var insideCount = 0
            for (i in inside.indices) {
                val a = inside[i] != 0.toByte()
                val b = labelValues[i] == best
                if (a && b) intersection++
                if (a || b) union++
                if (a) {
                    certainty += probabilities[i]
                    insideCount++
                }
            }
            val agreement = intersection.toFloat() / max(1, union)
            val confidence = agreement * certainty / max(1, insideCount)
            return Fit(
                fitted.map {
                    PointN(
                        (((it.x + .5) / OUTPUT).toFloat()).coerceIn(0f, 1f),
                        (((it.y + .5) / OUTPUT).toFloat()).coerceIn(0f, 1f),
                    )
                },
                confidence,
            )
        } finally {
            contours.forEach { it.release() }
            owned.forEach(Mat::release)
        }
    }

    /** A line through the contour points along side a..b: (vx, vy, x0, y0). */
    private fun side(points: Array<Point>, a: Point, b: Point): DoubleArray {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = hypot(dx, dy)
        if (length < 4) return doubleArrayOf(1.0, 0.0, a.x, a.y)
        val ux = dx / length
        val uy = dy / length
        val near =
            points.filter {
                val rx = it.x - a.x
                val ry = it.y - a.y
                val along = rx * ux + ry * uy
                along > .1 * length && along < .9 * length && abs(rx * uy - ry * ux) < max(3.0, .04 * length)
            }
        if (near.size < 8) return doubleArrayOf(ux, uy, a.x, a.y)
        val curve = MatOfPoint2f(*near.toTypedArray())
        val line = Mat()
        try {
            Imgproc.fitLine(curve, line, Imgproc.DIST_HUBER, 0.0, .01, .01)
            val values = FloatArray(4).also { line.get(0, 0, it) }
            return DoubleArray(4) { values[it].toDouble() }
        } finally {
            curve.release()
            line.release()
        }
    }

    private fun intersection(a: DoubleArray, b: DoubleArray): Point? {
        val determinant = a[0] * b[1] - a[1] * b[0]
        if (abs(determinant) < 1e-6) return null
        val t = ((b[2] - a[2]) * b[1] - (b[3] - a[3]) * b[0]) / determinant
        return Point(a[2] + a[0] * t, a[3] + a[1] * t)
    }

    /**
     * Null means the model is unavailable or saw no sheet. A [preview] frame skips recovery: it only
     * guides the user, and the capture is detected again as a still.
     */
    fun detect(source: Mat, preview: Boolean = false): DetectionResult? {
        val started = System.nanoTime()
        val probabilities = mask(source, Rect(0, 0, source.cols(), source.rows())) ?: return null
        val cut = ScanConfig.float("pageNetCut", .15f)
        val base = fit(probabilities, threshold(probabilities, cut)) ?: return null
        val fitted = Quad.ordered(base.points).takeIf { it.valid() } ?: return null
        val edges = boundary.get()
        val snapped =
            if (edges != null && ScanConfig.int("pageNetEdgeSnap", 1) != 0) snapInward(fitted, edges) else fitted
        // A mask that stopped at a box printed on the page grows to the page's own edge, see [SheetFrame]. After the
        // snap, which follows the network's boundary map and so would pull the side back onto the printed box; in
        // the preview too, so the live outline is the crop.
        val framed = input.get()?.let { SheetFrame.expand(snapped, it) }
        // A cover the mask cut at a printed colour boundary: only for a nearly square quad, see [CoverOutline].
        val extended = if (!preview && framed == null) CoverOutline.extend(source, snapped) else null
        val baseQuad = framed ?: extended ?: snapped
        var quad = baseQuad
        var confidence = base.confidence
        var method =
            if (framed != null) "pagenet+" + SheetFrame.VERSION
            else if (extended != null) "pagenet+" + CoverOutline.VERSION
            else "pagenet"
        // Recovery wins back blank margins the mask drops, which matters on a small sheet such as a receipt.
        // On a page that fills much of the frame it found the neighbouring page or the paper underneath,
        // and it costs about a second, so it runs only for small sheets.
        if (
            !preview && ScanConfig.int("pageNetRecover", 1) != 0 &&
                baseQuad.area < ScanConfig.float("pageNetRecoverBelow", .25f)
        ) {
            val recovered = recover(source, probabilities, cut)?.let { fit(probabilities, it) }
            val recoveredQuad = recovered?.let { Quad.ordered(it.points) }?.takeIf { it.valid() }
            // A much larger answer is the sheet underneath or the desk, not a recovered margin.
            if (recoveredQuad != null && recoveredQuad.area <= ScanConfig.float("pageNetRecoverKeep", 1.6f) * baseQuad.area) {
                quad = recoveredQuad
                // Recovered blank paper reads as unsure to the mask, so it must not lower this.
                confidence = max(base.confidence, recovered.confidence)
                method = "pagenet-recovered"
            }
        }
        // A pile of the same ruled paper reads as one sheet to the mask; the rules show where the top sheet ends.
        // Piles of notebook pages fill much of the frame; on a small sheet such as a receipt, printed text lines
        // would pass for rules.
        if (!preview && quad.area >= ScanConfig.float("ruleEndsAbove", .25f)) {
            TopSheetRules.refine(source, quad)?.let {
                quad = it
                method += "+" + TopSheetRules.VERSION
            }
        }
        return DetectionResult(
            quad,
            confidence.coerceIn(0f, .999f),
            method,
            elapsedMs = (System.nanoTime() - started) / 1e6f,
        )
    }

    /**
     * What is not the top sheet in an already warped page (CV_8UC1, 255 outside), or null when the
     * whole frame is the sheet or the result cannot be trusted. Unlike a colour or brightness rule, a
     * shadow on the page is still page, so shaded writing is safe. The margin keeps ink at the very
     * edge of the sheet: the mask is coarse next to a page at full resolution.
     */
    fun exterior(rgb: Mat): Mat? {
        if (ScanConfig.int("pageNetExterior", 1) == 0) return null
        val probabilities = mask(rgb, Rect(0, 0, rgb.cols(), rgb.rows())) ?: return null
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val contours = mutableListOf<MatOfPoint>()
        try {
            val binary = mat()
            binary.create(OUTPUT, OUTPUT, CvType.CV_8UC1)
            // Erasing is only for what is confidently not the sheet: a wrong "outside" loses writing,
            // while a wrong "inside" only leaves a little clutter at the edge.
            val cut = ScanConfig.float("pageNetExteriorCut", .1f)
            binary.put(0, 0, ByteArray(probabilities.size) { if (probabilities[it] > cut) 1 else 0 })
            Imgproc.findContours(binary, contours, mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val sheet = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            val coverage = Imgproc.contourArea(sheet) / (OUTPUT * OUTPUT)
            if (coverage < ScanConfig.float("pageNetExteriorMinimum", .55f) || coverage > .985) return null
            // Filling the outer contour makes holes in the mask, such as a dark photo, part of the sheet.
            val filled = mat()
            filled.create(OUTPUT, OUTPUT, CvType.CV_8UC1)
            filled.setTo(Scalar(0.0))
            Imgproc.drawContours(filled, listOf(sheet), -1, Scalar(255.0), -1)
            val full = mat()
            Imgproc.resize(filled, full, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.threshold(full, full, 127.0, 255.0, Imgproc.THRESH_BINARY)
            val margin =
                (min(rgb.cols(), rgb.rows()) * ScanConfig.float("pageNetExteriorMargin", .025f))
                    .roundToInt()
                    .coerceAtLeast(1)
            val element =
                Imgproc.getStructuringElement(
                        Imgproc.MORPH_ELLIPSE,
                        Size(2.0 * margin + 1, 2.0 * margin + 1),
                    )
                    .also(owned::add)
            Imgproc.dilate(full, full, element)
            val outside = Mat()
            Core.bitwise_not(full, outside)
            return outside
        } finally {
            contours.forEach { it.release() }
            owned.forEach(Mat::release)
        }
    }
}
