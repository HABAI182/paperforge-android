package cn.paperforge.app.imaging

import cn.paperforge.app.model.PointN
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Levels lines of print or writing that are still tilted after the other geometry stages. Lines lying flat stack
 * into sharp peaks in a band's row profile, so the angle that makes the profile sharpest is the angle of the
 * band's lines. The page is measured in `textLevelBands` bands; the top and bottom lines are then mapped onto
 * level lines by one homography that turns the page about its middle. A uniform tilt is a plain rotation.
 * Then the left margin, where the lines of text start, is made upright by a shear when it leans at least
 * `textLevelMarginMinimum` degrees and fits a straight line well ([marginLean]): the horizontal alignment and
 * the vertical indentation of print, as the owner asked.
 *
 * Why: the text-line dewarp needs lines that run across a third of the page, and an exam's formulas break them
 * up. The owner's exam page, printed slightly askew on its sheet and cropped to the sheet, kept 1.6 degrees of
 * tilt at the top and 0.7 at the bottom, where the reference scanner's page was level. Measured on the owner's eight pages,
 * only that one was turned; on the other seven every band read 0.00.
 *
 * Known cost: a page whose text is meant to run at a slant, a stamp say, is turned level when it dominates the
 * page. Paper beyond the sheet that the turn brings into a corner is left to the tone stage's edge cleanup.
 */
internal object TextLevel {
    const val VERSION = "text-level-1"

    class Estimate(
        val angles: List<Double?>,
        val confidence: List<Double>,
        val top: Double,
        val bottom: Double,
        /** The left margin's lean after levelling, in degrees; 0 when it was not corrected. */
        val margin: Double,
        private val inverse: DoubleArray?,
        private val shear: Double,
        private val shearCentre: Double,
        private val width: Int,
        private val height: Int,
    ) {
        /** The input point, in 0..1 page coordinates, that the levelled page shows at [point]. */
        fun input(point: PointN): PointN {
            // The margin is made upright first, on the levelled page; then the levelling turn maps it back.
            val x = point.x.toDouble() * (width - 1) + shear * (point.y.toDouble() * (height - 1) - shearCentre)
            val y = point.y.toDouble() * (height - 1)
            if (inverse == null) return PointN((x / (width - 1)).toFloat(), point.y)
            val d = inverse[6] * x + inverse[7] * y + inverse[8]
            val sx = (inverse[0] * x + inverse[1] * y + inverse[2]) / d
            val sy = (inverse[3] * x + inverse[4] * y + inverse[5]) / d
            return PointN((sx / (width - 1)).toFloat(), (sy / (height - 1)).toFloat())
        }

        fun json(): JSONObject =
            JSONObject()
                .put("version", VERSION)
                .put("angles", JSONArray(angles.map { it?.let { a -> (a * 100).roundToInt() / 100.0 } ?: JSONObject.NULL }))
                .put("confidence", JSONArray(confidence.map { (it * 100).roundToInt() / 100.0 }))
                .put("top", (top * 100).roundToInt() / 100.0)
                .put("bottom", (bottom * 100).roundToInt() / 100.0)
                .put("margin", (margin * 100).roundToInt() / 100.0)
    }

    /** Measures [preview], a small copy of the page after the other stages; null when its lines are level. */
    fun estimate(preview: Mat): Estimate? {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        try {
            val gray = mat()
            when (preview.channels()) {
                1 -> preview.copyTo(gray)
                4 -> Imgproc.cvtColor(preview, gray, Imgproc.COLOR_RGBA2GRAY)
                else -> Imgproc.cvtColor(preview, gray, Imgproc.COLOR_RGB2GRAY)
            }
            val w = gray.cols()
            val h = gray.rows()
            if (w < 64 || h < 64) return null
            val binary = mat()
            Imgproc.adaptiveThreshold(gray, binary, 1.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 31, 15.0)
            val ink = mat()
            binary.convertTo(ink, CvType.CV_32F)
            val bands = ScanConfig.int("textLevelBands", 4).coerceIn(1, 12)
            val range = ScanConfig.float("textLevelRange", 4f).toDouble()
            val x0 = (w * .06).roundToInt()
            val x1 = (w * .94).roundToInt()
            val centres = DoubleArray(bands)
            val angles = MutableList<Double?>(bands) { null }
            val confidence = MutableList(bands) { 0.0 }
            for (k in 0 until bands) {
                val y0 = (h * (.06 + .88 * k / bands)).roundToInt()
                val y1 = (h * (.06 + .88 * (k + 1) / bands)).roundToInt()
                centres[k] = (y0 + y1) / 2.0
                val band = ink.submat(y0, y1, x0, x1).also(owned::add)
                if (Core.mean(band).`val`[0] < .01) continue
                val (angle, sharpness) = bandAngle(band, range, owned)
                angles[k] = angle
                confidence[k] = sharpness
            }
            val minimum = ScanConfig.float("textLevelConfidence", 1.3f).toDouble()
            val good = (0 until bands).filter { angles[it] != null && confidence[it] >= minimum }
            if (good.isEmpty()) return null
            val ys = good.map { centres[it] }
            val values = good.map { angles[it]!! }
            var slope = 0.0
            var offset = values.sorted()[values.size / 2]
            if (good.size >= 2) {
                val my = ys.average()
                val ma = values.average()
                val sxx = ys.sumOf { (it - my) * (it - my) }
                if (sxx > 0) {
                    val b = ys.indices.sumOf { (ys[it] - my) * (values[it] - ma) } / sxx
                    val a = ma - b * my
                    // A page that does not tilt one way throughout is turned by its middle angle.
                    if (ys.indices.all { abs(a + b * ys[it] - values[it]) <= .6 }) {
                        slope = b
                        offset = a
                    }
                }
            }
            val yTop = if (good.size >= 2) ys.first() else h * .25
            val yBottom = if (good.size >= 2) ys.last() else h * .75
            val top = offset + slope * yTop
            val bottom = offset + slope * yBottom
            val inverse =
                if (max(abs(top), abs(bottom)) < ScanConfig.float("textLevelMinimum", .3f)) null
                else homography(w, h, yTop, top, yBottom, bottom)
            // The left edge of the text, measured on the levelled page, should then stand upright.
            val levelled = mat()
            if (inverse != null) {
                val matrix = Mat(3, 3, CvType.CV_64F).also(owned::add)
                matrix.put(0, 0, *inverse)
                Imgproc.warpPerspective(binary, levelled, matrix, binary.size(), Imgproc.INTER_NEAREST or Imgproc.WARP_INVERSE_MAP)
            } else binary.copyTo(levelled)
            val lean = marginLean(levelled)
            val minimumLean = ScanConfig.float("textLevelMarginMinimum", .2f).toDouble()
            var shear = if (lean != null && abs(lean) >= minimumLean && abs(lean) <= range) tan(Math.toRadians(lean)) else 0.0
            var leanDegrees = if (shear != 0.0) lean!! else 0.0
            // Without a text margin, a page of long vertical lines, such as a technical drawing, stands them upright:
            // on the owner's three-view drawing they leaned 1.8 degrees where the reference scanner's stood at 0.
            if (shear == 0.0) {
                val (upright, sharpness) = verticalShear(levelled, range, owned)
                if (sharpness >= ScanConfig.float("textLevelVerticalSharpness", 5f) && abs(Math.toDegrees(atan(upright))) >= ScanConfig.float("textLevelMinimum", .3f)) {
                    shear = upright
                    leanDegrees = Math.toDegrees(atan(upright))
                }
            }
            if (inverse == null && shear == 0.0) return null
            return Estimate(angles, confidence, top, bottom, leanDegrees, inverse, shear, h / 2.0, w, h)
        } finally {
            owned.forEach(Mat::release)
        }
    }

    /**
     * The lean, in degrees, of the left margin of [ink] (1 where there is ink): positive when lines start further
     * right lower down. Rows of text are found from the row profile; the rows that start within 2% of the page's
     * width of the leftmost starts mark the margin, and a line through their starts gives its lean. Null unless at
     * least five rows over 40% of the page fit within a pixel and a half: a ragged margin of handwriting says
     * nothing. Measured on the owner's pages, our printed pages leaned 0.5 to 0.8 degrees where the reference scanner's
     * stood within 0.05; the ruled pages read 0.1 or less.
     */
    private fun marginLean(ink: Mat): Double? {
        val w = ink.cols()
        val h = ink.rows()
        // A sheet's edge or a stack's edges near the frame run down the page; text does not. They are taken
        // out first, and the outer 6% is not searched, or every row would start at the edge.
        val lines = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, max(9.0, h * .08)))
        val text = Mat()
        try {
            Imgproc.morphologyEx(ink, lines, Imgproc.MORPH_OPEN, kernel)
            Core.subtract(ink, lines, text)
            return marginLeanOf(text)
        } finally {
            lines.release()
            kernel.release()
            text.release()
        }
    }

    private fun marginLeanOf(ink: Mat): Double? {
        val w = ink.cols()
        val h = ink.rows()
        val bytes = ByteArray(w * h).also { ink.get(0, 0, it) }
        val x0 = (w * .06).roundToInt()
        val x1 = (w * .94).roundToInt()
        val threshold = max(3, (.01 * w).roundToInt())
        val starts = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var y = 0
        fun count(row: Int): Int {
            var n = 0
            for (x in x0 until x1) if (bytes[row * w + x] != 0.toByte()) n++
            return n
        }
        while (y < h) {
            if (count(y) > threshold) {
                val y0 = y
                while (y < h && count(y) > threshold) y++
                if (y - y0 >= 4) {
                    var first = -1
                    loop@ for (x in x0 until x1) for (r in y0 until y) if (bytes[r * w + x] != 0.toByte()) {
                        first = x
                        break@loop
                    }
                    if (first >= 0) {
                        starts += first.toDouble()
                        ys += (y0 + y) / 2.0
                    }
                }
            }
            y++
        }
        if (starts.size < 6) return null
        val base = starts.sorted()[(starts.size * .1).toInt()]
        var picked = starts.indices.filter { starts[it] <= base + .02 * w }
        fun fit(rows: List<Int>): Pair<Double, Double>? {
            if (rows.size < 5) return null
            val my = rows.sumOf { ys[it] } / rows.size
            val mx = rows.sumOf { starts[it] } / rows.size
            val syy = rows.sumOf { (ys[it] - my) * (ys[it] - my) }
            if (syy <= 0) return null
            val b = rows.sumOf { (ys[it] - my) * (starts[it] - mx) } / syy
            return b to mx - b * my
        }
        var line = fit(picked) ?: return null
        val residuals = picked.map { abs(starts[it] - (line.second + line.first * ys[it])) }
        val limit = max(1.5, 2.5 * residuals.sorted()[residuals.size / 2])
        picked = picked.filterIndexed { i, _ -> residuals[i] <= limit }
        line = fit(picked) ?: return null
        val spread = picked.maxOf { ys[it] } - picked.minOf { ys[it] }
        val fitError = picked.map { abs(starts[it] - (line.second + line.first * ys[it])) }.sorted()[picked.size / 2]
        if (spread < .4 * h || fitError > 1.5) return null
        return Math.toDegrees(atan(line.first))
    }

    /**
     * The shear s, as in "the page shows at x what lies at x + s (y - middle)", that makes [ink]'s column profile
     * sharpest, and how much sharper that profile is than a typical shear's: vertical lines stand upright when
     * their columns stack into sharp peaks. Only clear vertical structure gives a high ratio; text gives little.
     */
    private fun verticalShear(ink: Mat, range: Double, owned: MutableList<Mat>): Pair<Double, Double> {
        val float = Mat().also(owned::add)
        ink.convertTo(float, CvType.CV_32F)
        val sheared = Mat().also(owned::add)
        val profile = Mat().also(owned::add)
        val cy = ink.rows() / 2.0
        fun score(s: Double): Double {
            val m = Mat(2, 3, CvType.CV_64F)
            try {
                m.put(0, 0, 1.0, s, -s * cy, 0.0, 1.0, 0.0)
                Imgproc.warpAffine(float, sheared, m, float.size(), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP)
            } finally {
                m.release()
            }
            Core.reduce(sheared, profile, 0, Core.REDUCE_SUM, CvType.CV_32F)
            val cols = FloatArray(profile.cols()).also { profile.get(0, 0, it) }
            var sum = 0.0
            for (i in 1 until cols.size) {
                val d = (cols[i] - cols[i - 1]).toDouble()
                sum += d * d
            }
            return sum
        }
        val coarse = generateSequence(-range) { it + .2 }.takeWhile { it <= range + 1e-9 }.map { tan(Math.toRadians(it)) }.toList()
        val scores = coarse.map(::score)
        val best = coarse[scores.indices.maxBy { scores[it] }]
        val step = tan(Math.toRadians(.05))
        val fine = (-4..4).map { best + it * step }
        val fineScores = fine.map(::score)
        val index = fineScores.indices.maxBy { fineScores[it] }
        return fine[index] to fineScores[index] / scores.sorted()[scores.size / 2].coerceAtLeast(1e-9)
    }

    /**
     * The angle, in degrees and positive when lines rise to the right, that makes [band]'s row profile sharpest,
     * and how much sharper that profile is than the band's typical one.
     */
    private fun bandAngle(band: Mat, range: Double, owned: MutableList<Mat>): Pair<Double, Double> {
        val turned = Mat().also(owned::add)
        val profile = Mat().also(owned::add)
        val centre = Point(band.cols() / 2.0, band.rows() / 2.0)
        fun score(angle: Double): Double {
            val turn = Imgproc.getRotationMatrix2D(centre, angle, 1.0)
            try {
                Imgproc.warpAffine(band, turned, turn, band.size(), Imgproc.INTER_LINEAR)
            } finally {
                turn.release()
            }
            Core.reduce(turned, profile, 1, Core.REDUCE_SUM, CvType.CV_32F)
            val rows = FloatArray(profile.rows()).also { profile.get(0, 0, it) }
            var sum = 0.0
            for (i in 1 until rows.size) {
                val d = (rows[i] - rows[i - 1]).toDouble()
                sum += d * d
            }
            return sum
        }
        val coarse = generateSequence(-range) { it + .2 }.takeWhile { it <= range + 1e-9 }.toList()
        val scores = coarse.map(::score)
        val best = coarse[scores.indices.maxBy { scores[it] }]
        val fine = generateSequence(best - .2) { it + .05 }.takeWhile { it <= best + .2 + 1e-9 }.toList()
        val fineScores = fine.map(::score)
        val index = fineScores.indices.maxBy { fineScores[it] }
        val typical = scores.sorted()[scores.size / 2].coerceAtLeast(1e-9)
        // OpenCV turns counterclockwise for a positive angle, so lines rising to the right were levelled by a
        // negative one.
        return -fine[index] to fineScores[index] / typical
    }

    /**
     * Output-to-input homography, row-major 3 x 3: the line through the page's middle column at [yTop] with angle
     * [top], and the one at [yBottom] with angle [bottom], become level, and lines across the page square to them.
     */
    private fun homography(w: Int, h: Int, yTop: Double, top: Double, yBottom: Double, bottom: Double): DoubleArray? {
        val cx = (w - 1) / 2.0
        val cy = (yTop + yBottom) / 2.0
        val mean = Math.toRadians((top + bottom) / 2)
        val ux = cos(mean)
        val uy = -sin(mean)
        val vx = sin(mean)
        val vy = cos(mean)
        val reach = .4 * w
        fun meet(px: Double, py: Double, dx: Double, dy: Double, qx: Double, qy: Double, ex: Double, ey: Double): DoubleArray? {
            val det = dx * -ey - -ex * dy
            if (abs(det) < 1e-9) return null
            val s = ((qx - px) * -ey - -ex * (qy - py)) / det
            return doubleArrayOf(px + s * dx, py + s * dy)
        }
        fun along(degrees: Double) = Math.toRadians(degrees).let { doubleArrayOf(cos(it), -sin(it)) }
        val source = ArrayList<Point>()
        for ((y, angle) in listOf(yTop to top, yBottom to bottom)) {
            val d = along(angle)
            for (side in intArrayOf(-1, 1)) {
                val p = meet(cx, y, d[0], d[1], cx + side * reach * ux, cy + side * reach * uy, vx, vy) ?: return null
                source += Point(p[0], p[1])
            }
        }
        val dt = along(top)
        val db = along(bottom)
        val topMid = meet(cx, yTop, dt[0], dt[1], cx, cy, vx, vy) ?: return null
        val bottomMid = meet(cx, yBottom, db[0], db[1], cx, cy, vx, vy) ?: return null
        val half = abs((bottomMid[0] - topMid[0]) * vx + (bottomMid[1] - topMid[1]) * vy) / 2
        val target =
            listOf(Point(cx - reach, cy - half), Point(cx + reach, cy - half), Point(cx - reach, cy + half), Point(cx + reach, cy + half))
        val from = MatOfPoint2f(*target.toTypedArray())
        val to = MatOfPoint2f(*source.toTypedArray())
        val matrix = Imgproc.getPerspectiveTransform(from, to)
        try {
            return DoubleArray(9).also { matrix.get(0, 0, it) }
        } finally {
            matrix.release()
            from.release()
            to.release()
        }
    }
}
