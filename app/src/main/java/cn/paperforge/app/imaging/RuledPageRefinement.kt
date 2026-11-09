package cn.paperforge.app.imaging

import cn.paperforge.app.model.PointN
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Original residual geometry estimator for ruled paper. It follows continuous rules rather than
 * individual glyphs, then interpolates a monotone output-to-input map. Unsupported headers and
 * footers translate with the nearest rule instead of being squeezed against the crop border.
 */
object RuledPageRefinement {
    const val VERSION = "ruled-residual-7"

    data class Result(
        val width: Int,
        val height: Int,
        val targets: FloatArray,
        val curves: List<FloatArray>,
        val maximumShift: Float,
        val extendMargins: Boolean = false,
    ) {
        val lines
            get() = curves.size

        // Fit the whole translated source with ONE UNIFORM scale. No local header compression,
        // clipping of the first formula, or independent horizontal/vertical aspect stretching.
        val topExtension =
            if (extendMargins) max(0f, curves.first().max() - targets.first()) else 0f
        val bottomExtension =
            if (extendMargins) max(0f, targets.last() - curves.last().min()) else 0f
        val fit = (height - 1f) / (height - 1f + topExtension + bottomExtension)

        fun inputPoint(point: PointN): PointN {
            val inputX = if (extendMargins) (point.x - .5f) / fit + .5f else point.x
            val x = inputX.coerceIn(0f, 1f) * (width - 1)
            val y =
                if (extendMargins) point.y * (height - 1) / fit - topExtension
                else point.y.coerceIn(0f, 1f) * (height - 1)
            var upper = targets.indexOfFirst { it >= y }
            if (upper < 0) upper = targets.size
            val lowY = if (upper == 0) 0f else targets[upper - 1]
            val highY = if (upper == targets.size) (height - 1).toFloat() else targets[upper]
            fun sample(index: Int): Float {
                if (index < 0) return 0f
                if (index >= curves.size) return (height - 1).toFloat()
                val ix = floor(x).toInt()
                val next = min(width - 1, ix + 1)
                return curves[index][ix] + (curves[index][next] - curves[index][ix]) * (x - ix)
            }
            if (extendMargins && upper == 0)
                return PointN(inputX, (y + sample(0) - targets.first()) / (height - 1))
            if (extendMargins && upper == targets.size)
                return PointN(
                    inputX,
                    (y + sample(curves.lastIndex) - targets.last()) / (height - 1),
                )
            val fraction = if (highY <= lowY) 0f else (y - lowY) / (highY - lowY)
            val source = sample(upper - 1) * (1 - fraction) + sample(upper) * fraction
            return PointN(inputX, source / (height - 1))
        }
    }

    internal fun velocityEnvelope(
        score: FloatArray,
        base: Int,
        count: Int,
        bend: Float,
        values: FloatArray,
        parents: IntArray,
        sites: IntArray = IntArray(count),
        borders: DoubleArray = DoubleArray(count + 1),
    ) {
        if (bend <= 0f) {
            val best = (0 until count).maxBy { score[base + it] }
            for (v in 0 until count) {
                values[base + v] = score[base + best]
                parents[base + v] = base + best
            }
            return
        }
        var last = -1
        for (q in 0 until count) {
            if (score[base + q] <= -5e8f) continue
            var cross = Double.NEGATIVE_INFINITY
            while (last >= 0) {
                val p = sites[last]
                cross =
                    ((-score[base + q].toDouble() + bend * q * q) -
                        (-score[base + p].toDouble() + bend * p * p)) / (2.0 * bend * (q - p))
                if (cross > borders[last]) break
                last--
            }
            last++
            sites[last] = q
            borders[last] = if (last == 0) Double.NEGATIVE_INFINITY else cross
            borders[last + 1] = Double.POSITIVE_INFINITY
        }
        if (last < 0) {
            for (v in 0 until count) {
                values[base + v] = -1e9f
                parents[base + v] = base + count / 2
            }
            return
        }
        var site = 0
        for (v in 0 until count) {
            while (site < last && borders[site + 1] < v) site++
            val p = sites[site]
            values[base + v] = score[base + p] - bend * (v - p) * (v - p)
            parents[base + v] = base + p
        }
    }

    /** Penalized second differences: suppress text-induced kinks without a fixed center. */
    internal fun smoothRidge(observed: FloatArray, strength: Float): FloatArray {
        val n = observed.size
        if (n < 4 || strength <= 0f) return observed.copyOf()
        val system = Array(n) { DoubleArray(n) }
        for (i in 0 until n) system[i][i] = 1.0
        val d = doubleArrayOf(1.0, -2.0, 1.0)
        for (i in 0 until n - 2) for (a in 0..2) for (b in 0..2) system[i + a][i + b] +=
            strength * d[a] * d[b]
        // Banded Cholesky, bandwidth two. Work is linear in the number of path samples.
        val l = Array(n) { DoubleArray(3) }
        for (i in 0 until n) for (j in max(0, i - 2)..i) {
            var value = system[i][j]
            for (k in max(0, i - 2) until j) value -= l[i][i - k] * l[j][j - k]
            l[i][i - j] = if (i == j) sqrt(max(1e-12, value)) else value / l[j][0]
        }
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var value = observed[i].toDouble()
            for (j in max(0, i - 2) until i) value -= l[i][i - j] * y[j]
            y[i] = value / l[i][0]
        }
        val out = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var value = y[i]
            for (j in i + 1..min(n - 1, i + 2)) value -= l[j][j - i] * out[j]
            out[i] = value / l[i][0]
        }
        return FloatArray(n) { out[it].toFloat() }
    }

    fun estimate(source: Mat, extendMargins: Boolean = false): Result? {
        if (ScanConfig.int("ruledRefinement", 1) == 0) return null
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val gray = mat()
        val small = mat()
        val field = mat()
        val background = mat()
        val ink = mat()
        val narrow = mat()
        val broad = mat()
        try {
            when (source.channels()) {
                4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
                3 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGB2GRAY)
                else -> source.copyTo(gray)
            }
            val width = min(source.cols(), ScanConfig.int("ruledWorkWidth", 760))
            val height = (source.rows().toDouble() * width / source.cols()).roundToInt()
            if (width < 160 || height < 240) return null
            Imgproc.resize(
                gray,
                small,
                Size(width.toDouble(), height.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
            val kernel =
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).also(owned::add)
            Imgproc.dilate(small, field, kernel)
            Imgproc.medianBlur(field, background, 35)
            val raw = ByteArray(width * height)
            val paper = ByteArray(raw.size)
            small.get(0, 0, raw)
            background.get(0, 0, paper)
            val inkValues =
                FloatArray(raw.size) { index ->
                    255f -
                        min(
                            255f,
                            (raw[index].toInt() and 255) * 255f /
                                max(20, paper[index].toInt() and 255),
                        )
                }
            ink.create(height, width, CvType.CV_32FC1)
            ink.put(0, 0, inkValues)
            Imgproc.GaussianBlur(ink, narrow, Size(0.0, 0.0), 8.0, .7)
            Imgproc.GaussianBlur(ink, broad, Size(0.0, 0.0), 8.0, 5.0)
            val ridge = FloatArray(raw.size)
            val low = FloatArray(raw.size)
            narrow.get(0, 0, ridge)
            broad.get(0, 0, low)
            for (i in ridge.indices) ridge[i] = max(0f, ridge[i] - low[i])
            val profile =
                FloatArray(height) { y ->
                    var sum = 0f
                    for (x in (width * .2f).toInt() until (width * .8f).toInt()) sum +=
                        ridge[y * width + x]
                    sum / ((width * .8f).toInt() - (width * .2f).toInt())
                }
            val mean = profile.average().toFloat()
            val spacing =
                (max(3, (height / 65f).roundToInt()) until (height / 15f).roundToInt())
                    .maxByOrNull { lag ->
                        var correlation = 0.0
                        for (y in 0 until height - lag) correlation +=
                            (profile[y] - mean) * (profile[y + lag] - mean)
                        correlation
                    } ?: return null
            val sorted = profile.sorted()
            val threshold = max(2f, sorted[(height * .98f).toInt().coerceAtMost(height - 1)] * .16f)
            val peaks =
                (3 until height - 3).filter { y ->
                    profile[y] >= threshold && (-2..2).all { profile[y] >= profile[y + it] }
                }
            val chosen = mutableListOf<Int>()
            peaks
                .sortedByDescending { profile[it] }
                .forEach { seed ->
                    if (chosen.all { abs(seed - it) > spacing * .58f }) chosen += seed
                }
            val step = ScanConfig.int("ruledColumnStep", 8).coerceIn(8, 24)
            val xs = (step until width - step step step).toList()
            if (xs.size < 6) return null
            val bins =
                Array(xs.size) { column ->
                    FloatArray(height) { y ->
                        var sum = 0f
                        for (x in xs[column] - 7..xs[column] + 7) sum += ridge[y * width + x]
                        sum / 15
                    }
                }
            val targets = mutableListOf<Float>()
            val curves = mutableListOf<FloatArray>()
            val rawCurves = mutableListOf<FloatArray>()
            val minimumSupport = ScanConfig.float("ruledMinimumSupport", .82f)
            var maximumShift = 0f
            for (seed in chosen.sorted()) {
                val radius = (spacing * .8f).roundToInt()
                val start = max(3, seed - radius)
                val end = min(height - 3, seed + radius + 1)
                val states = end - start
                val center = xs.size / 2
                val path = IntArray(xs.size)
                // A profile peak is averaged across columns, not the actual center ridge.
                // Anchoring all lines to it invents a common dent around the page midpoint.
                val anchorRadius = max(2, (spacing * .26f).roundToInt())
                val anchor =
                    (max(start, seed - anchorRadius)..min(end - 1, seed + anchorRadius)).maxBy {
                        bins[center][it] - .02f * abs(it - seed)
                    }
                path[center] = anchor - start
                val radiusVelocity = 6
                val velocities = radiusVelocity * 2 + 1
                val bend = ScanConfig.float("ruledBendPenalty", .025f)
                for (direction in listOf(-1, 1)) {
                    var score = FloatArray(states * velocities) { -1e9f }
                    // Do not force a horizontal tangent in the middle of a slanted/curling rule.
                    for (v in -radiusVelocity..radiusVelocity) score[
                        (anchor - start) * velocities + v + radiusVelocity] = -.002f * v * v
                    val history = mutableListOf<Pair<Int, IntArray>>()
                    val envelope = FloatArray(score.size)
                    val parentIndex = IntArray(score.size)
                    val sites = IntArray(velocities)
                    val borders = DoubleArray(velocities + 1)
                    var column = center + direction
                    while (column in xs.indices) {
                        val back = IntArray(score.size)
                        val next = FloatArray(score.size) { -1e9f }
                        for (priorState in 0 until states) velocityEnvelope(
                            score,
                            priorState * velocities,
                            velocities,
                            bend,
                            envelope,
                            parentIndex,
                            sites,
                            borders,
                        )
                        for (state in 0 until states) for (v in -radiusVelocity..radiusVelocity) {
                            val prior = state - v
                            if (prior !in 0 until states) continue
                            val priorIndex = prior * velocities + v + radiusVelocity
                            val best = envelope[priorIndex] - .001f * v * v
                            val parent = parentIndex[priorIndex]
                            val index = state * velocities + v + radiusVelocity
                            back[index] = parent
                            next[index] =
                                best + min(2f, bins[column][start + state] / max(2f, profile[seed]))
                        }
                        history += column to back
                        score = next
                        column += direction
                    }
                    var index = score.indices.maxBy { score[it] }
                    history.asReversed().forEach { (position, back) ->
                        path[position] = index / velocities
                        index = back[index]
                    }
                }
                val support =
                    path.indices
                        .count { bins[it][start + path[it]] >= max(2f, profile[seed] * .25f) }
                        .toFloat() / path.size
                if (support < minimumSupport) continue
                val pathMat = mat()
                val smoothed = mat()
                pathMat.create(1, path.size, CvType.CV_32FC1)
                pathMat.put(
                    0,
                    0,
                    FloatArray(path.size) { column ->
                        val y = path[column] + start
                        val before = bins[column][y - 1]
                        val centerValue = bins[column][y]
                        val after = bins[column][y + 1]
                        val denominator = before - 2f * centerValue + after
                        val subpixel =
                            if (
                                centerValue >= before &&
                                    centerValue >= after &&
                                    denominator < -.0001f
                            )
                                (.5f * (before - after) / denominator).coerceIn(-.5f, .5f)
                            else 0f
                        y + subpixel * ScanConfig.float("ruledSubpixel", 1f)
                    },
                )
                val observed = FloatArray(path.size)
                pathMat.get(0, 0, observed)
                val ys = smoothRidge(observed, ScanConfig.float("ruledCurveSmoothing", 12f))
                fun expand(samples: FloatArray) =
                    FloatArray(width) { x ->
                        val position = (x - xs.first()).toFloat() / step
                        when {
                            position < 0f -> samples[0] + (samples[2] - samples[0]) * .5f * position
                            position > samples.lastIndex ->
                                samples.last() +
                                    (samples.last() - samples[samples.lastIndex - 2]) *
                                        .5f *
                                        (position - samples.lastIndex)
                            else -> {
                                val left = floor(position).toInt()
                                val right = min(samples.lastIndex, left + 1)
                                samples[left] + (samples[right] - samples[left]) * (position - left)
                            }
                        }
                    }
                val curve = expand(ys)
                val inkOnLine =
                    (8 until width - 8).map { x ->
                        val y = curve[x].roundToInt().coerceIn(1, height - 2)
                        (-1..1).maxOf { dy -> inkValues[(y + dy) * width + x] }
                    }
                val inkThreshold = max(5f, inkOnLine.sorted()[inkOnLine.size / 2] * .3f)
                if (inkOnLine.count { it > inkThreshold }.toFloat() / inkOnLine.size < .65f)
                    continue
                val middle = curve.slice((width * .3f).toInt() until (width * .7f).toInt()).sorted()
                val target = middle[middle.size / 2]
                if (
                    curves.isNotEmpty() &&
                        curve.indices.any { curve[it] - curves.last()[it] < spacing * .4f }
                )
                    continue
                targets += target
                curves += curve
                rawCurves += expand(observed)
                maximumShift = max(maximumShift, curve.maxOf { abs(it - target) })
            }
            // A real narrow fold is shared by several neighboring rules. Restore that
            // observed detail; isolated glyph-induced wiggles stay smoothed away.
            val smoothCurves = curves.map { it.copyOf() }
            val sharedFold = ScanConfig.float("ruledSharedFold", .9f)
            for (i in curves.indices) for (x in 0 until width) {
                val residuals =
                    (max(0, i - 2)..min(curves.lastIndex, i + 2))
                        .map { rawCurves[it][x] - smoothCurves[it][x] }
                        .sorted()
                if (residuals.size < 3) continue
                val middle = residuals[residuals.size / 2]
                val agreement =
                    residuals.count {
                        it * middle > 0f &&
                            abs(it) >= abs(middle) * .4f &&
                            abs(it) <= abs(middle) * 2.5f
                    }
                if (agreement >= 3) curves[i][x] += middle * sharedFold
            }
            // Paper displacement varies smoothly down the page. A fraction bar or a glyph
            // must not bend just one tracked rule. Robust local vertical regression removes
            // those outliers while retaining folds observed across neighboring rules.
            val coherence = ScanConfig.float("ruledSurfaceCoherence", 1f).coerceIn(0f, 1f)
            if (coherence > 0f && curves.size >= 6) {
                val observed = curves.map { it.copyOf() }
                val neighbors = min(7, curves.size)
                val slopes = FloatArray(neighbors * (neighbors - 1) / 2)
                val offsets = FloatArray(neighbors)
                for (i in curves.indices) {
                    val first = (i - neighbors / 2).coerceIn(0, curves.size - neighbors)
                    for (x in 0 until width) {
                        var count = 0
                        for (a in first until first + neighbors) for (b in
                            a + 1 until first + neighbors) {
                            val da = observed[a][x] - targets[a]
                            val db = observed[b][x] - targets[b]
                            slopes[count++] = (db - da) / (targets[b] - targets[a])
                        }
                        slopes.sort()
                        val slope = slopes[slopes.size / 2]
                        for (j in 0 until neighbors) {
                            val n = first + j
                            offsets[j] =
                                observed[n][x] - targets[n] + slope * (targets[i] - targets[n])
                        }
                        offsets.sort()
                        val predicted = targets[i] + offsets[neighbors / 2]
                        curves[i][x] += (predicted - curves[i][x]) * coherence
                    }
                }
            }
            maximumShift =
                curves.indices.maxOfOrNull { i -> curves[i].maxOf { abs(it - targets[i]) } } ?: 0f
            if (curves.size < ScanConfig.int("ruledMinimumLines", 6)) return null
            val maximum = height * ScanConfig.float("ruledMaximumShift", .055f)
            var retained = if (maximumShift > maximum) maximum / maximumShift else 1f
            // The map is piecewise linear vertically. Its derivative is constant between
            // anchors, so checking each segment is exact and avoids scanning every pixel row.
            for (x in 0 until width step 4) {
                var previousSource = if (extendMargins) curves.first()[x] else 0f
                var previousTarget = if (extendMargins) targets.first() else 0f
                for (index in
                    (if (extendMargins) 1 else 0) until
                        (if (extendMargins) curves.size else curves.size + 1)) {
                    val target =
                        if (index == curves.size) (height - 1).toFloat() else targets[index]
                    val sourceY =
                        if (index == curves.size) (height - 1).toFloat() else curves[index][x]
                    if (target <= previousTarget) return null
                    val step = target - previousTarget
                    val delta = sourceY - previousSource - step
                    if (delta > 0f) retained = min(retained, .65f * step / delta)
                    else if (delta < 0f) retained = min(retained, -.5f * step / delta)
                    previousSource = sourceY
                    previousTarget = target
                }
            }
            if (retained < .25f) return null
            if (retained < 1f) {
                if (ScanConfig.int("ruledMapDamping", 1) == 0) return null
                // Retain the supported portion instead of replacing a nearly valid observed
                // map with an unrelated learned warp that may distort formulas.
                for (i in curves.indices) for (x in 0 until width) curves[i][x] =
                    targets[i] + (curves[i][x] - targets[i]) * retained
                maximumShift *= retained
            }
            return Result(
                width,
                height,
                targets.toFloatArray(),
                curves,
                maximumShift,
                extendMargins,
            )
        } finally {
            owned.forEach { it.release() }
        }
    }
}
