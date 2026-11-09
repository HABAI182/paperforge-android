package cn.paperforge.app.imaging

import kotlin.math.*
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Whole-page continuous paper field.
 *
 * Why this exists (v200 feedback 2026-11-04, "shadow band + show-through"): the previous paper
 * estimate was assembled from piecewise stages - a 3.5% local window plus, on workbook pages,
 * `EnhancePipeline.repairEdgePaper`, which let each of the four 8% side bands take the maximum of
 * the field towards the interior. Both are *piecewise*: the field (and therefore the gain
 * `target / field`) changes abruptly where the band ends, and the workbook silhouette repaint in
 * [WorkbookPaperBoundary] added a third constant-gain segment at the mask contour. A shadow that
 * crosses one of those boundaries is lifted by one gain on one side and a different gain on the
 * other, which is the "hard boundary between the shadow band and the bright area" in the report.
 *
 * This object replaces that with one smooth surface defined on every pixel and continuous across
 * the whole page:
 *
 * 1. bounded copy (paper shading is low frequency; 480 px keeps the cost bounded);
 * 2. closing + median envelope rejects ink and keeps broad shading (same evidence as before);
 * 3. a robust least-squares quadratic is fitted to *paper pixels only* (ink below a floor relative
 *    to the envelope), reweighted twice, so dark handwriting inside a shadow cannot drag the paper
 *    surface down;
 * 4. the low-frequency residual of that quadratic, normalised by the paper mask, is added back, so
 *    book curvature beyond a quadratic is followed without ever pulling ink in;
 * 5. the surface is upscaled linearly.
 *
 * Every stage is smooth in image coordinates, so there is no band boundary to switch gain at. The
 * field is used for ink normalisation ([EnhancePipeline] local workbook), the final whiteness
 * division ([PaperWhiteFinal]), the silhouette repaint ([WorkbookPaperBoundary]) and ghost
 * suppression ([PaperGhost]).
 */
internal object PaperField {
    const val VERSION = "paper-field-1"

    /** Largest residual allowed on top of the quadratic; keeps a black object from bending paper. */
    private const val MAXIMUM_RESIDUAL = 40f

    /**
     * Smooth paper field for [gray] (CV_8UC1). Returns CV_32FC1 at the input size; [destination]
     * is filled and returned when supplied.
     */
    /**
     * Paper brightness that follows a hard shadow edge exactly. A grey closing removes whatever is
     * darker and narrower than its kernel, so strokes vanish while a shadow, wide on both sides of
     * its edge, stays where it is. Dividing by this makes shadowed paper equal to lit paper, and
     * gives a stroke in shadow the same profile as one in the light, before any ink decision.
     *
     * A solid mark wider than the kernel would be taken for shadow, so the result never drops below
     * [PaperField.estimate] times `paperFieldFloor`: that much shade is shadow, more is ink.
     * Radii are for a 1375 px long edge and scale with the page. Returns CV_32FC1.
     */
    fun edgePreserving(gray: Mat): Mat {
        val scale = maxOf(gray.cols(), gray.rows()) / 1375f
        val size = ((ScanConfig.int("paperFieldClose", 21) * scale).toInt() or 1).coerceIn(3, 201).toDouble()
        val closed = Mat()
        // A square costs the same at any size, where a disc's cost grows with it, and it removes strokes
        // just as well. Working at full size keeps a shadow's edge exact: a blurred edge draws a line.
        val element = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(size, size))
        val smooth = Mat()
        val floor = Mat()
        val sharp = Mat()
        val result = Mat()
        try {
            ParallelFilter.morphology(gray, closed, Imgproc.MORPH_CLOSE, element)
            closed.convertTo(result, CvType.CV_32F)
            val sigma = (ScanConfig.float("paperFieldBlur", 9f) * scale).toDouble().coerceAtLeast(1.0)
            ParallelFilter.gaussian(result, result, sigma)
            // The blur carries light across a hard shadow edge. Shaded paper measured against that
            // brighter value reads as ink and draws a line along the edge, so it is never allowed.
            closed.convertTo(sharp, CvType.CV_32F)
            Core.min(result, sharp, result)
            estimate(gray, smooth)
            Core.multiply(smooth, Scalar(ScanConfig.float("paperFieldFloor", .45f).toDouble()), floor)
            Core.max(result, floor, result)
            return result
        } finally {
            listOf(closed, element, smooth, floor, sharp).forEach(Mat::release)
        }
    }

    fun estimate(gray: Mat, destination: Mat = Mat()): Mat {
        require(gray.type() == CvType.CV_8UC1) { "paper field needs a grey page" }
        val width = gray.cols()
        val height = gray.rows()
        val edge = ScanConfig.int("paperFieldSmoothEdge", 480).coerceIn(240, 960)
        val scale = min(1.0, edge.toDouble() / max(width, height))
        val smallW = max(32, (width * scale).roundToInt())
        val smallH = max(32, (height * scale).roundToInt())
        val small = Mat()
        val closed = Mat()
        val coarse = Mat()
        val kernel = Mat()
        val poly = Mat()
        val residual = Mat()
        val masked = Mat()
        val blurredValue = Mat()
        val blurredMask = Mat()
        try {
            Imgproc.resize(
                gray,
                small,
                Size(smallW.toDouble(), smallH.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
            val short = min(smallW, smallH)
            val closeSize =
                max(3, (short * ScanConfig.float("paperFieldKernelFraction", .06f)).roundToInt() or 1)
                    .coerceAtMost(short - 1)
            Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE,
                    Size(closeSize.toDouble(), closeSize.toDouble()),
                )
                .copyTo(kernel)
            Imgproc.morphologyEx(small, closed, Imgproc.MORPH_CLOSE, kernel)
            val medianSize =
                (max(3, (short * .035f).roundToInt() or 1)).coerceAtMost((short - 1) or 1)
            Imgproc.medianBlur(closed, coarse, medianSize)
            val page = ByteArray(smallW * smallH).also { small.get(0, 0, it) }
            val envelope = ByteArray(smallW * smallH).also { coarse.get(0, 0, it) }
            val inkFloor = ScanConfig.float("paperFieldInkFloor", .03f).coerceIn(.005f, .12f)
            val floorScaled = (inkFloor * 1000f).roundToInt()
            val mask = FloatArray(smallW * smallH)
            var paperCount = 0
            for (i in mask.indices) {
                val level = max(1, envelope[i].toInt() and 255)
                val value = page[i].toInt() and 255
                // Integer form of `1 - value / envelope < inkFloor`.
                val isPaper = value * 1000 > level * (1000 - floorScaled)
                mask[i] = if (isPaper) 1f else 0f
                if (isPaper) paperCount++
            }
            val usable = paperCount >= max(64, smallW * smallH / 200)
            val coefficients = if (usable) fitQuadratic(envelope, mask, smallW, smallH) else null
            if (coefficients == null) {
                // Degenerate page (almost no paper evidence): keep the smooth envelope. Still
                // continuous; it simply has no quadratic trend to extrapolate.
                coarse.convertTo(destination, CvType.CV_32F)
                return destination
            }
            val polyValues = FloatArray(smallW * smallH)
            var index = 0
            for (y in 0 until smallH) {
                val yn = y / (smallH - 1f) * 2f - 1f
                for (x in 0 until smallW) {
                    val xn = x / (smallW - 1f) * 2f - 1f
                    polyValues[index++] =
                        (
                            coefficients[0] +
                                coefficients[1] * xn +
                                coefficients[2] * yn +
                                coefficients[3] * xn * xn +
                                coefficients[4] * xn * yn +
                                coefficients[5] * yn * yn)
                            .toFloat()
                }
            }
            poly.create(smallH, smallW, CvType.CV_32FC1)
            poly.put(0, 0, polyValues)
            // The residual is sampled on paper pixels only, so ink never enters it.
            val residualValues = FloatArray(smallW * smallH)
            val maskValues = FloatArray(smallW * smallH)
            for (i in residualValues.indices) {
                residualValues[i] =
                    ((envelope[i].toInt() and 255) - polyValues[i]).coerceIn(
                        -MAXIMUM_RESIDUAL,
                        MAXIMUM_RESIDUAL,
                    )
                maskValues[i] = mask[i]
            }
            val residualMat = Mat(smallH, smallW, CvType.CV_32FC1)
            val maskMat = Mat(smallH, smallW, CvType.CV_32FC1)
            try {
                residualMat.put(0, 0, residualValues)
                maskMat.put(0, 0, maskValues)
                Core.multiply(residualMat, maskMat, masked)
                val sigma =
                    max(
                        2.0,
                        (ScanConfig.float("paperFieldResidualSigma", .025f) * max(smallW, smallH))
                            .toDouble(),
                    )
                Imgproc.GaussianBlur(masked, blurredValue, Size(0.0, 0.0), sigma)
                Imgproc.GaussianBlur(maskMat, blurredMask, Size(0.0, 0.0), sigma)
                // Normalised convolution: pixels without paper support keep the quadratic.
                Core.divide(blurredValue, blurredMask, residual)
            } finally {
                residualMat.release()
                maskMat.release()
            }
            Core.add(poly, residual, poly)
            val fieldValues = FloatArray(smallW * smallH)
            poly.get(0, 0, fieldValues)
            for (i in fieldValues.indices) fieldValues[i] = fieldValues[i].coerceIn(1f, 255f)
            val smallField = Mat(smallH, smallW, CvType.CV_32FC1)
            try {
                smallField.put(0, 0, fieldValues)
                Imgproc.resize(
                    smallField,
                    destination,
                    Size(width.toDouble(), height.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_LINEAR,
                )
            } finally {
                smallField.release()
            }
            return destination
        } finally {
            listOf(small, closed, coarse, kernel, poly, residual, masked, blurredValue, blurredMask)
                .forEach(Mat::release)
        }
    }

    /** [estimate] as 8-bit paper bytes, the form the existing native normaliser consumes. */
    fun estimateBytes(gray: Mat, destination: Mat = Mat()): Mat {
        val field = estimate(gray)
        try {
            field.convertTo(destination, CvType.CV_8UC1)
            return destination
        } finally {
            field.release()
        }
    }

    /**
     * Robust quadratic fit `c0 + c1 x + c2 y + c3 x^2 + c4 xy + c5 y^2` on paper pixels.
     * Two IRLS reweights with a mean-absolute-deviation scale; null when the system is singular.
     */
    private fun fitQuadratic(
        values: ByteArray,
        mask: FloatArray,
        width: Int,
        height: Int,
    ): DoubleArray? {
        val rows = width * height
        val xn = DoubleArray(rows)
        val yn = DoubleArray(rows)
        var index = 0
        for (y in 0 until height) {
            val ny = y / (height - 1.0) * 2.0 - 1.0
            for (x in 0 until width) {
                xn[index] = x / (width - 1.0) * 2.0 - 1.0
                yn[index] = ny
                index++
            }
        }
        val weights = DoubleArray(rows) { mask[it].toDouble() }
        val basis = DoubleArray(6)
        for (round in 0..2) {
            val ata = DoubleArray(36)
            val atb = DoubleArray(6)
            for (row in 0 until height) {
                var i = row * width
                while (i < (row + 1) * width) {
                    val w = weights[i]
                    if (w > 0.0) {
                        val x = xn[i]
                        val y = yn[i]
                        basis[0] = 1.0
                        basis[1] = x
                        basis[2] = y
                        basis[3] = x * x
                        basis[4] = x * y
                        basis[5] = y * y
                        val target = (values[i].toInt() and 255).toDouble()
                        for (a in 0 until 6) {
                            atb[a] += w * basis[a] * target
                            for (b in a until 6) ata[a * 6 + b] += w * basis[a] * basis[b]
                        }
                    }
                    i++
                }
            }
            for (a in 1 until 6) for (b in 0 until a) ata[a * 6 + b] = ata[b * 6 + a]
            // Ridge term keeps a flat or degenerate page solvable.
            for (a in 0 until 6) ata[a * 6 + a] += 1e-6
            val solved = solve(ata, atb) ?: return null
            if (round == 2) return solved
            val residuals = DoubleArray(rows)
            var deviation = 0.0
            var count = 0
            for (i in 0 until rows) {
                if (weights[i] <= 0.0) continue
                val x = xn[i]
                val y = yn[i]
                val predicted =
                    solved[0] + solved[1] * x + solved[2] * y + solved[3] * x * x +
                        solved[4] * x * y + solved[5] * y * y
                val residual = (values[i].toInt() and 255) - predicted
                residuals[i] = residual
                deviation += abs(residual)
                count++
            }
            if (count == 0) return solved
            val scale = max(.5, deviation / count * 1.2533)
            for (i in 0 until rows) {
                if (mask[i] <= 0f) continue
                weights[i] = if (abs(residuals[i]) < 2.5 * scale) mask[i].toDouble() else 0.0
            }
        }
        return null
    }

    /** Gaussian elimination with partial pivoting on the 6x6 normal equations. */
    private fun solve(matrix: DoubleArray, vector: DoubleArray): DoubleArray? {
        val a = matrix.copyOf()
        val b = vector.copyOf()
        for (column in 0 until 6) {
            var pivot = column
            for (row in column + 1 until 6)
                if (abs(a[row * 6 + column]) > abs(a[pivot * 6 + column])) pivot = row
            if (abs(a[pivot * 6 + column]) < 1e-12) return null
            if (pivot != column) {
                for (k in 0 until 6) {
                    val tmp = a[column * 6 + k]
                    a[column * 6 + k] = a[pivot * 6 + k]
                    a[pivot * 6 + k] = tmp
                }
                val tmp = b[column]
                b[column] = b[pivot]
                b[pivot] = tmp
            }
            val diagonal = a[column * 6 + column]
            for (k in column until 6) a[column * 6 + k] /= diagonal
            b[column] /= diagonal
            for (row in 0 until 6) {
                if (row == column) continue
                val factor = a[row * 6 + column]
                if (factor == 0.0) continue
                for (k in column until 6) a[row * 6 + k] -= factor * a[column * 6 + k]
                b[row] -= factor * b[column]
            }
        }
        return b
    }

    /** Percentile summary of a field Mat, for acceptance JSON. */
    fun summary(field: Mat): JSONObject {
        val values = FloatArray(field.cols() * field.rows())
        field.get(0, 0, values)
        val histogram = IntArray(256)
        for (v in values) histogram[v.roundToInt().coerceIn(0, 255)]++
        val pixels = values.size
        fun percentile(fraction: Double): Int {
            val target = max(1, (pixels * fraction).toInt())
            var sum = 0
            for (level in 0..255) {
                sum += histogram[level]
                if (sum >= target) return level
            }
            return 255
        }
        return JSONObject()
            .put("version", VERSION)
            .put("p10", percentile(.10))
            .put("p50", percentile(.50))
            .put("p90", percentile(.90))
            .put("edge", ScanConfig.int("paperFieldSmoothEdge", 480))
    }
}
