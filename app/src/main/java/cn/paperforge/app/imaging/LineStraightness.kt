package cn.paperforge.app.imaging

import kotlin.math.*

/**
 * Original, dependency-free straightness core for scanned pages.
 *
 * It follows long printed rules (and, on pages without rules, text-row ridges), estimates the
 * remaining rigid tilt plus a low-frequency bend field, and returns a bounded backward map. The
 * map is rejected unless it straightens the measured constraints without squeezing line spacing,
 * glyph height or ink mass beyond the guards. No learned weights or third-party code are used.
 *
 * Coordinates are normalized output-to-input, matching [cn.paperforge.app.model.DewarpGrid].
 */
object LineStraightness {
    const val VERSION = "line-straightness-1"

    enum class Axis {
        HORIZONTAL,
        VERTICAL,
        TEXT,
    }

    data class Line(
        val axis: Axis,
        val mid: Double,
        val lo: Double,
        val hi: Double,
        val xs: DoubleArray,
        val ys: DoubleArray,
        val rms: Double,
        val span: Double,
        val slope: Double,
        val fwhm: Double,
        val bandHeight: Double = 0.0,
    )

    data class AxisMetric(
        val lines: Int = 0,
        val rmsMedian: Double = 0.0,
        val rmsP95: Double = 0.0,
        val tiltMedianDeg: Double = 0.0,
        val tiltAbsMedianDeg: Double = 0.0,
        val fwhmMedian: Double = 0.0,
        val spanMedian: Double = 0.0,
        val spacingMedian: Double = 0.0,
    )

    data class Metrics(
        val horizontal: AxisMetric = AxisMetric(),
        val vertical: AxisMetric = AxisMetric(),
        val text: AxisMetric = AxisMetric(),
        val glyphHeight: Double = 0.0,
        val glyphWidth: Double = 0.0,
        val inkMass: Double = 0.0,
    )

    data class Correction(
        val side: Int,
        val dx: FloatArray,
        val dy: FloatArray,
        val angleDeg: Double,
        val maxShift: Double,
        val scale: Double,
        val linesH: Int,
        val linesV: Int,
        val note: String,
    ) {
        /** Backward map: output point -> source point in the same normalized frame. */
        fun inputPoint(x: Float, y: Float): FloatArray {
            val gx = (x.coerceIn(0f, 1f) * (side - 1)).coerceIn(0f, (side - 1).toFloat())
            val gy = (y.coerceIn(0f, 1f) * (side - 1)).coerceIn(0f, (side - 1).toFloat())
            val ix = floor(gx).toInt().coerceIn(0, side - 1)
            val iy = floor(gy).toInt().coerceIn(0, side - 1)
            val ix1 = (ix + 1).coerceAtMost(side - 1)
            val iy1 = (iy + 1).coerceAtMost(side - 1)
            val fx = gx - ix
            val fy = gy - iy
            fun at(values: FloatArray, yy: Int, xx: Int) = values[yy * side + xx]
            fun bilinear(values: FloatArray): Float {
                val top = at(values, iy, ix) * (1 - fx) + at(values, iy, ix1) * fx
                val bottom = at(values, iy1, ix) * (1 - fx) + at(values, iy1, ix1) * fx
                return top * (1 - fy) + bottom * fy
            }
            return floatArrayOf(
                (x + bilinear(dx)).coerceIn(0f, 1f),
                (y + bilinear(dy)).coerceIn(0f, 1f),
            )
        }
    }

    data class Guard(
        val inkRetention: Double = 1.0,
        val glyphHeightRatio: Double = 1.0,
        val glyphWidthRatio: Double = 1.0,
        val spacingRatio: Double = 1.0,
        val accepted: Boolean = false,
        val reason: String = "",
    )

    data class Result(
        val correction: Correction,
        val before: Metrics,
        val after: Metrics,
        val guard: Guard,
        val note: String,
    )

    private data class Stats(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val area: Int,
    )

    private data class Detection(
        val delta: FloatArray,
        val width: Int,
        val height: Int,
        val horizontal: List<Line>,
        val vertical: List<Line>,
        val text: List<Line>,
        val glyphHeight: Double,
        val glyphWidth: Double,
        val inkMass: Double,
    )

    private data class Fit(
        val slope: Double,
        val intercept: Double,
        val rms: Double,
        val keep: BooleanArray,
        val span: Double,
        val lo: Double,
        val hi: Double,
    )

    /** One control line in the de-rotated frame; [xs] must be increasing. */
    private data class Control(
        val mid: Double,
        val lo: Double,
        val hi: Double,
        val xs: DoubleArray,
        val ys: DoubleArray,
    )

    fun measure(gray: ByteArray, width: Int, height: Int): Metrics {
        require(gray.size >= width * height)
        val detection = detect(gray, width, height)
        return metricsOf(detection)
    }

    /**
     * Estimates a correction on a grayscale page. Returns null when there is not enough evidence,
     * when the measured straightness does not improve, or when a content guard fails.
     */
    fun estimate(
        gray: ByteArray,
        width: Int,
        height: Int,
        maxShiftFraction: Double = 0.02,
    ): Result? {
        if (width < 160 || height < 160) return null
        val detection = detect(gray, width, height)
        val before = metricsOf(detection)
        val hRules = detection.horizontal
        val vRules = detection.vertical
        val textRows = detection.text
        val alreadyStraight =
            (hRules.size >= 3 && before.horizontal.rmsMedian < .35 &&
                before.horizontal.tiltAbsMedianDeg < .08) &&
                (vRules.size < 3 ||
                    (before.vertical.rmsMedian < .5 &&
                        before.vertical.tiltAbsMedianDeg < .15))
        if (alreadyStraight) return null
        val theta = estimateAngle(hRules, vRules, textRows)
        val correctedH =
            if (hRules.size >= 3) rotateLines(hRules, theta, width, height, Axis.HORIZONTAL)
            else rotateLines(textRows, theta, width, height, Axis.TEXT)
        val correctedV = rotateLines(vRules, theta, width, height, Axis.VERTICAL)
        if (correctedH.size + correctedV.size < 3) return null
        val side = 64
        val dx = FloatArray(side * side)
        val dy = FloatArray(side * side)
        for (gy in 0 until side) {
            val y = (gy + 0.5) * height / side - 0.5
            for (gx in 0 until side) {
                val x = (gx + 0.5) * width / side - 0.5
                val rotated = rotate(x, y, theta, width, height, inverse = false)
                val by =
                    if (correctedH.isNotEmpty()) fieldResidual(correctedH, rotated[0], rotated[1])
                    else 0.0
                val bx =
                    if (correctedV.isNotEmpty()) fieldResidual(correctedV, rotated[1], rotated[0])
                    else 0.0
                val mx = min(x, width - 1 - x) / width
                val my = min(y, height - 1 - y) / height
                // Keep the physical page border fixed: a wider zero ring prevents the final
                // remap from sampling outside the cropped page and leaving white corners.
                val blend = ((min(mx, my) - .03) / .07).coerceIn(0.0, 1.0)
                val i = gy * side + gx
                dx[i] = ((rotated[0] - x + bx) * blend).toFloat()
                dy[i] = ((rotated[1] - y + by) * blend).toFloat()
            }
        }
        val maxAbsolute = maxOf(dx.maxOf { abs(it).toDouble() }, dy.maxOf { abs(it).toDouble() })
        if (maxAbsolute < .75) return null
        val limit = maxShiftFraction * min(width, height)
        val scale = min(1.0, limit / maxAbsolute)
        // Store a normalized backward displacement: callers add it to normalized coordinates.
        for (i in dx.indices) {
            dx[i] = (dx[i] * scale / (width - 1)).toFloat()
            dy[i] = (dy[i] * scale / (height - 1)).toFloat()
        }
        val correction =
            Correction(
                side,
                dx,
                dy,
                Math.toDegrees(theta),
                maxAbsolute * scale,
                scale,
                correctedH.size,
                correctedV.size,
                if (hRules.size >= 3) "rules" else "text-rows",
            )
        val afterGray = apply(gray, width, height, correction)
        val after = measure(afterGray, width, height)
        val guard = guardOf(detection, before, after, width, height)
        val horizontalImproves =
            (before.horizontal.lines >= 3 && after.horizontal.lines >= 3) &&
                (after.horizontal.rmsMedian <= before.horizontal.rmsMedian * .98 ||
                    after.horizontal.tiltAbsMedianDeg <=
                        before.horizontal.tiltAbsMedianDeg * .9)
        val textImproves =
            (before.text.lines >= 3 && after.text.lines >= 3) &&
                (after.text.rmsMedian <= before.text.rmsMedian * .98 ||
                    after.text.tiltAbsMedianDeg <= before.text.tiltAbsMedianDeg * .9)
        val verticalImproves =
            (before.vertical.lines >= 3 && after.vertical.lines >= 3) &&
                (after.vertical.rmsMedian <= before.vertical.rmsMedian * .98 ||
                    after.vertical.tiltAbsMedianDeg <= before.vertical.tiltAbsMedianDeg * .9)
        if (!horizontalImproves && !textImproves && !verticalImproves)
            return Result(correction, before, after, guard.copy(accepted = false, reason = "no-gain"), "")
        if (!guard.accepted) return Result(correction, before, after, guard, "")
        fun regressed(b: AxisMetric, a: AxisMetric): Boolean =
            b.lines >= 1 && a.lines >= 1 &&
                a.tiltAbsMedianDeg > max(b.tiltAbsMedianDeg + 1.0, 1.5)
        if (regressed(before.horizontal, after.horizontal) ||
            regressed(before.vertical, after.vertical)
        )
            return Result(
                correction,
                before,
                after,
                guard.copy(accepted = false, reason = "tilt-regression"),
                "",
            )
        val note =
            "长线约束：${correction.linesH} 横 / ${correction.linesV} 竖，残余 ${"%.2f".format(correction.angleDeg)}°"
        return Result(correction, before, after, guard, note)
    }

    /** Bilinear application used by verification and guards. */
    fun apply(gray: ByteArray, width: Int, height: Int, correction: Correction): ByteArray {
        val side = correction.side
        val out = ByteArray(width * height)
        val xs = FloatArray(side)
        val ys = FloatArray(side)
        for (i in 0 until side) {
            xs[i] = ((i + .5) * width / side - .5).toFloat()
            ys[i] = ((i + .5) * height / side - .5).toFloat()
        }
        fun field(values: FloatArray, x: Float, y: Float): Float {
            val gx = ((x + .5f) * side / width - .5f).coerceIn(0f, side - 1f)
            val gy = ((y + .5f) * side / height - .5f).coerceIn(0f, side - 1f)
            val ix = floor(gx).toInt().coerceIn(0, side - 1)
            val iy = floor(gy).toInt().coerceIn(0, side - 1)
            val ix1 = (ix + 1).coerceAtMost(side - 1)
            val iy1 = (iy + 1).coerceAtMost(side - 1)
            val fx = gx - ix
            val fy = gy - iy
            fun at(yy: Int, xx: Int) = values[yy * side + xx]
            val top = at(iy, ix) * (1 - fx) + at(iy, ix1) * fx
            val bottom = at(iy1, ix) * (1 - fx) + at(iy1, ix1) * fx
            return top * (1 - fy) + bottom * fy
        }
        for (y in 0 until height) for (x in 0 until width) {
            val sx = x + field(correction.dx, x.toFloat(), y.toFloat()) * (width - 1)
            val sy = y + field(correction.dy, x.toFloat(), y.toFloat()) * (height - 1)
            out[y * width + x] = sample(gray, width, height, sx, sy)
        }
        return out
    }

    // ---------------------------------------------------------------- detection

    private fun detect(gray: ByteArray, width: Int, height: Int): Detection {
        val delta = localDelta(gray, width, height)
        val mask = threshold(delta)
        val horizontal =
            detectRules(delta, mask, width, height, horizontal = true)
        val vertical =
            detectRules(delta, mask, width, height, horizontal = false)
        val text = detectTextRows(delta, mask, width, height, horizontal)
        var ink = 0.0
        for (value in delta) ink += value
        val glyph = glyphStats(mask, width, height)
        return Detection(
            delta,
            width,
            height,
            horizontal,
            vertical,
            text,
            glyph.first,
            glyph.second,
            ink,
        )
    }

    private fun detectRules(
        delta: FloatArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        horizontal: Boolean,
    ): List<Line> {
        val minRun = max(5, (if (horizontal) width else height) / 45)
        val filtered = runFilter(mask, width, height, horizontal, minRun)
        val labels = IntArray(width * height)
        val stats = mutableListOf<Stats>()
        labelComponents(filtered, width, height, labels, stats)
        val raw = mutableListOf<Line>()
        for ((index, stat) in stats.withIndex()) {
            val id = index + 1
            if (horizontal) {
                if (stat.width < .22 * width || stat.height > .035 * height ||
                    stat.area < stat.width * .6
                )
                    continue
            } else {
                if (stat.height < .22 * height || stat.width > .035 * width ||
                    stat.area < stat.height * .6
                )
                    continue
            }
            val points = trackRidge(delta, labels, id, stat, horizontal, width)
            if (points.size < 12) continue
            val fit = fit(points, width, height, horizontal) ?: continue
            if (abs(fit.slope) > .08 || fit.rms > max(2.0, .012 * height)) continue
            if (fit.span < .22 * (if (horizontal) width else height)) continue
            val compact = compact(points)
            raw.add(
                Line(
                    if (horizontal) Axis.HORIZONTAL else Axis.VERTICAL,
                    median(compact.second),
                    compact.first.first(),
                    compact.first.last(),
                    compact.first,
                    compact.second,
                    fit.rms,
                    fit.span,
                    fit.slope,
                    fwhm(delta, width, height, compact, horizontal),
                )
            )
        }
        raw.sortBy { it.mid }
        val groups = mutableListOf<MutableList<Line>>()
        for (line in raw) {
            val group = groups.lastOrNull()
            if (group != null && abs(line.mid - group.last().mid) <
                .008 * (if (horizontal) height else width)
            )
                group.add(line)
            else groups.add(mutableListOf(line))
        }
        val merged = mutableListOf<Line>()
        for (group in groups) {
            val xs = group.flatMap { it.xs.toList() }.toDoubleArray()
            val ys = group.flatMap { it.ys.toList() }.toDoubleArray()
            val compact = compact(xs, ys)
            if (compact.first.size < 12) continue
            val points = compact.first.indices.map { compact.first[it] to compact.second[it] }
            val fit = fit(points, width, height, horizontal) ?: continue
            merged.add(
                Line(
                    if (horizontal) Axis.HORIZONTAL else Axis.VERTICAL,
                    median(compact.second),
                    compact.first.first(),
                    compact.first.last(),
                    compact.first,
                    compact.second,
                    fit.rms,
                    fit.span,
                    fit.slope,
                    fwhm(delta, width, height, compact, horizontal),
                )
            )
        }
        return merged
    }

    private fun detectTextRows(
        delta: FloatArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        horizontal: List<Line>,
    ): List<Line> {
        val minRun = max(9, width / 40)
        // Bridge word/character gaps before component labelling; the vertical direction is
        // untouched so neighbouring text rows never merge.
        val bridged = bridgeHorizontal(mask, width, height, minRun)
        val filtered = runFilter(bridged, width, height, true, minRun)
        val labels = IntArray(width * height)
        val stats = mutableListOf<Stats>()
        labelComponents(filtered, width, height, labels, stats)
        val rows = mutableListOf<Line>()
        for ((index, stat) in stats.withIndex()) {
            if (stat.width < .32 * width || stat.height < 4 || stat.height > .13 * height ||
                stat.area < stat.width * 1.2
            )
                continue
            val points = trackMean(delta, labels, index + 1, stat, horizontal = true, width = width)
            if (points.size < .2 * width) continue
            val fit = fit(points, width, height, true) ?: continue
            if (fit.span < .30 * width || fit.rms > .02 * height) continue
            val compact = compact(points)
            if (compact.first.size < 20) continue
            rows.add(
                Line(
                    Axis.TEXT,
                    median(compact.second),
                    compact.first.first(),
                    compact.first.last(),
                    compact.first,
                    compact.second,
                    fit.rms,
                    fit.span,
                    fit.slope,
                    0.0,
                    stat.height.toDouble(),
                )
            )
        }
        rows.sortBy { it.mid }
        val merged = mutableListOf<Line>()
        for (line in rows) {
            if (horizontal.any { abs(it.mid - line.mid) < 3.0 }) continue
            val last = merged.lastOrNull()
            if (last != null && abs(line.mid - last.mid) < .012 * height) {
                val compact = compact(last.xs + line.xs, last.ys + line.ys)
                val points = compact.first.indices.map { compact.first[it] to compact.second[it] }
                val fit = fit(points, width, height, true) ?: continue
                merged[merged.lastIndex] =
                    Line(
                        Axis.TEXT,
                        median(compact.second),
                        compact.first.first(),
                        compact.first.last(),
                        compact.first,
                        compact.second,
                        fit.rms,
                        fit.span,
                        fit.slope,
                        0.0,
                        max(last.bandHeight, line.bandHeight),
                    )
            } else merged.add(line)
        }
        return merged
    }

    private fun trackRidge(
        delta: FloatArray,
        labels: IntArray,
        id: Int,
        stat: Stats,
        horizontal: Boolean,
        width: Int,
    ): List<Pair<Double, Double>> {
        val lo = if (horizontal) stat.x else stat.y
        val hi = if (horizontal) stat.x + stat.width - 1 else stat.y + stat.height - 1
        val alo = if (horizontal) stat.y else stat.x
        val ahi = if (horizontal) stat.y + stat.height - 1 else stat.x + stat.width - 1
        val profile = DoubleArray(ahi - alo + 1)
        for (a in alo..ahi) {
            var sum = 0.0
            for (l in lo..hi) {
                val x = if (horizontal) l else a
                val y = if (horizontal) a else l
                if (labels[y * width + x] == id) sum += delta[y * width + x].toDouble()
            }
            profile[a - alo] = sum
        }
        var cumulative = 0.0
        val total = profile.sum()
        if (total <= 0) return emptyList()
        var start = alo
        for (i in profile.indices) {
            cumulative += profile[i]
            if (cumulative >= total * .5) {
                start = alo + i
                break
            }
        }
        val reach = max(6, if (horizontal) stat.height else stat.width)
        fun sample(along: Int, last: Int): Double? {
            val a0 = max(alo, last - reach)
            val a1 = min(ahi, last + reach)
            var peakAt = -1
            var peak = 0f
            for (a in a0..a1) {
                val x = if (horizontal) along else a
                val y = if (horizontal) a else along
                val index = y * width + x
                if (labels[index] != id) continue
                if (delta[index] > peak) {
                    peak = delta[index]
                    peakAt = a
                }
            }
            if (peakAt < 0 || peak <= 1f) return null
            var weighted = 0.0
            var weight = 0.0
            for (a in max(a0, peakAt - 2)..min(a1, peakAt + 2)) {
                val x = if (horizontal) along else a
                val y = if (horizontal) a else along
                val index = y * width + x
                if (labels[index] != id) continue
                val value = delta[index].toDouble()
                weighted += a * value
                weight += value
            }
            if (weight <= 2) return null
            return weighted / weight
        }
        val points = ArrayList<Pair<Double, Double>>()
        val centre = (lo + hi) / 2
        for (direction in intArrayOf(1, -1)) {
            var last = start.toDouble()
            val range =
                if (direction > 0) centre..hi else centre downTo lo
            for (along in range) {
                val across = sample(along, last.roundToInt()) ?: continue
                last = across
                points.add((along + .5) to across)
            }
        }
        points.sortBy { it.first }
        return points
    }

    private fun trackMean(
        delta: FloatArray,
        labels: IntArray,
        id: Int,
        stat: Stats,
        horizontal: Boolean,
        width: Int,
    ): List<Pair<Double, Double>> {
        val points = ArrayList<Pair<Double, Double>>()
        if (horizontal) {
            for (x in stat.x until stat.x + stat.width) {
                var sum = 0.0
                var weighted = 0.0
                for (y in stat.y until stat.y + stat.height) {
                    val index = y * width + x
                    if (labels[index] != id) continue
                    val value = delta[index].toDouble()
                    weighted += y * value
                    sum += value
                }
                if (sum > 5) points.add((x + .5) to weighted / sum)
            }
        } else {
            for (y in stat.y until stat.y + stat.height) {
                var sum = 0.0
                var weighted = 0.0
                for (x in stat.x until stat.x + stat.width) {
                    val index = y * width + x
                    if (labels[index] != id) continue
                    val value = delta[index].toDouble()
                    weighted += x * value
                    sum += value
                }
                if (sum > 5) points.add((y + .5) to weighted / sum)
            }
        }
        points.sortBy { it.first }
        return points
    }

    // ---------------------------------------------------------------- helpers

    private fun localDelta(gray: ByteArray, width: Int, height: Int): FloatArray {
        val radius = max(6, min(width, height) / 40)
        val stride = width + 1
        val integral = LongArray(stride * (height + 1))
        for (y in 0 until height) {
            var row = 0L
            val src = y * width
            val above = y * stride
            val current = (y + 1) * stride
            for (x in 0 until width) {
                row += gray[src + x].toInt() and 255
                integral[current + x + 1] = integral[above + x + 1] + row
            }
        }
        val delta = FloatArray(width * height)
        for (y in 0 until height) {
            val y0 = max(0, y - radius)
            val y1 = min(height, y + radius + 1)
            for (x in 0 until width) {
                val x0 = max(0, x - radius)
                val x1 = min(width, x + radius + 1)
                val sum =
                    integral[y1 * stride + x1] - integral[y0 * stride + x1] -
                        integral[y1 * stride + x0] + integral[y0 * stride + x0]
                val count = (y1 - y0) * (x1 - x0)
                val background = sum.toDouble() / count
                delta[y * width + x] =
                    max(0.0, background - (gray[y * width + x].toInt() and 255)).toFloat()
            }
        }
        return delta
    }

    private fun threshold(delta: FloatArray): ByteArray {
        val hist = IntArray(256)
        for (value in delta) hist[min(255, (value * 3).toInt()).coerceAtLeast(0)]++
        var total = 0L
        var sum = 0.0
        for (i in 0..255) {
            total += hist[i]
            sum += i.toDouble() * hist[i]
        }
        var background = 0L
        var backgroundSum = 0.0
        var best = 0.0
        var bestAt = 0
        for (i in 0..255) {
            background += hist[i]
            backgroundSum += i.toDouble() * hist[i]
            val foreground = total - background
            if (background == 0L || foreground == 0L) continue
            val meanBackground = backgroundSum / background
            val meanForeground = (sum - backgroundSum) / foreground
            val score = background.toDouble() * foreground *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (score > best) {
                best = score
                bestAt = i
            }
        }
        val limit = max(6.0, .55 * bestAt)
        return ByteArray(delta.size) { if (delta[it] >= limit) 1 else 0 }
    }

    /** Horizontal close: fill ink-free gaps up to [gap] inside a single row. */
    private fun bridgeHorizontal(
        mask: ByteArray,
        width: Int,
        height: Int,
        gap: Int,
    ): ByteArray {
        val out = mask.copyOf()
        for (y in 0 until height) {
            val row = y * width
            var last = -1
            for (x in 0 until width) {
                if (mask[row + x].toInt() == 0) continue
                if (last >= 0 && x - last in 1..gap) {
                    for (fill in last + 1 until x) out[row + fill] = 1
                }
                last = x
            }
        }
        return out
    }

    private fun runFilter(
        mask: ByteArray,
        width: Int,
        height: Int,
        horizontal: Boolean,
        minimum: Int,
    ): ByteArray {
        val out = ByteArray(mask.size)
        if (horizontal) {
            for (y in 0 until height) {
                var start = 0
                while (start < width) {
                    while (start < width && mask[y * width + start].toInt() == 0) start++
                    var end = start
                    while (end < width && mask[y * width + end].toInt() != 0) end++
                    if (end - start >= minimum) for (x in start until end) out[y * width + x] = 1
                    start = end
                }
            }
        } else {
            for (x in 0 until width) {
                var start = 0
                while (start < height) {
                    while (start < height && mask[start * width + x].toInt() == 0) start++
                    var end = start
                    while (end < height && mask[end * width + x].toInt() != 0) end++
                    if (end - start >= minimum) for (y in start until end) out[y * width + x] = 1
                    start = end
                }
            }
        }
        return out
    }

    /** Median bounding-box size of character-sized ink components; a text-scale guard. */
    private fun glyphStats(mask: ByteArray, width: Int, height: Int): Pair<Double, Double> {
        val labels = IntArray(width * height)
        val stats = mutableListOf<Stats>()
        labelComponents(mask, width, height, labels, stats)
        val heights = ArrayList<Double>()
        val widths = ArrayList<Double>()
        for (stat in stats) {
            if (stat.height < 3 || stat.height > max(6, (height * .06).toInt())) continue
            if (stat.width < 2 || stat.width > max(4, (width * .12).toInt())) continue
            if (stat.area < 8) continue
            heights.add(stat.height.toDouble())
            widths.add(stat.width.toDouble())
        }
        return if (heights.isEmpty()) 0.0 to 0.0
        else median(heights) to median(widths)
    }

    private fun labelComponents(
        mask: ByteArray,
        width: Int,
        height: Int,
        labels: IntArray,
        stats: MutableList<Stats>,
    ) {
        val stack = IntArray(width * height)
        for (i in labels.indices) labels[i] = 0
        for (start in mask.indices) {
            if (mask[start].toInt() == 0 || labels[start] != 0) continue
            val id = stats.size + 1
            var top = 0
            stack[top++] = start
            labels[start] = id
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            var area = 0
            while (top > 0) {
                val at = stack[--top]
                val x = at % width
                val y = at / width
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                    val index = ny * width + nx
                    if (mask[index].toInt() != 0 && labels[index] == 0) {
                        labels[index] = id
                        stack[top++] = index
                    }
                }
            }
            stats.add(Stats(minX, minY, maxX - minX + 1, maxY - minY + 1, area))
        }
    }

    private fun compact(points: List<Pair<Double, Double>>): Pair<DoubleArray, DoubleArray> =
        compact(
            points.map { it.first }.toDoubleArray(),
            points.map { it.second }.toDoubleArray(),
        )

    private fun compact(xs: DoubleArray, ys: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val order = xs.indices.sortedBy { xs[it] }
        val cx = ArrayList<Double>(order.size)
        val cy = ArrayList<Double>(order.size)
        for (index in order) {
            if (cx.isNotEmpty() && abs(xs[index] - cx.last()) < .75)
                cy[cy.lastIndex] = .5 * (cy.last() + ys[index])
            else {
                cx.add(xs[index])
                cy.add(ys[index])
            }
        }
        if (cy.size >= 9) {
            val kernel = doubleArrayOf(1.0, 2.0, 3.0, 2.0, 1.0)
            val normal = kernel.sum()
            val smooth = DoubleArray(cy.size)
            for (i in cy.indices) {
                var value = 0.0
                for (j in kernel.indices) {
                    val at = (i + j - 2).coerceIn(0, cy.lastIndex)
                    value += cy[at] * kernel[j]
                }
                smooth[i] = value / normal
            }
            return cx.toDoubleArray() to smooth
        }
        return cx.toDoubleArray() to cy.toDoubleArray()
    }

    private fun fit(
        points: List<Pair<Double, Double>>,
        width: Int,
        height: Int,
        horizontal: Boolean,
    ): Fit? {
        if (points.size < 8) return null
        var keep = BooleanArray(points.size) { true }
        var slope = 0.0
        var intercept = 0.0
        repeat(3) {
            var n = 0.0
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var sxy = 0.0
            for ((index, point) in points.withIndex()) {
                if (!keep[index]) continue
                val x = point.first
                val y = point.second
                n += 1
                sx += x
                sy += y
                sxx += x * x
                sxy += x * y
            }
            val denominator = n * sxx - sx * sx
            if (n < 8 || abs(denominator) < 1e-9) return null
            slope = (n * sxy - sx * sy) / denominator
            intercept = (sy - slope * sx) / n
            val residuals =
                DoubleArray(points.size) { index ->
                    if (!keep[index]) 0.0
                    else abs(points[index].second - (slope * points[index].first + intercept))
                }
            val active = residuals.filterIndexed { index, _ -> keep[index] }.sorted()
            val mad = active.getOrElse(active.size / 2) { 0.0 }
            val limit = max(1.5, 3.0 * mad)
            var kept = 0
            for (i in points.indices) {
                keep[i] = residuals[i] <= limit
                if (keep[i]) kept++
            }
            if (kept < 8) return null
        }
        val residual = DoubleArray(points.size)
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (i in points.indices) {
            residual[i] =
                if (keep[i]) abs(points[i].second - (slope * points[i].first + intercept)) else 0.0
            if (keep[i]) {
                lo = min(lo, points[i].first)
                hi = max(hi, points[i].first)
            }
        }
        val active = residual.filterIndexed { index, _ -> keep[index] }
        val rms = sqrt(active.sumOf { it * it } / max(1, active.size))
        return Fit(slope, intercept, rms, keep, hi - lo, lo, hi)
    }

    private fun fwhm(
        delta: FloatArray,
        width: Int,
        height: Int,
        line: Pair<DoubleArray, DoubleArray>,
        horizontal: Boolean,
    ): Double {
        val profile = DoubleArray(25)
        val counts = IntArray(25)
        val step = max(1, line.first.size / 120)
        for (i in line.first.indices step step) {
            val along = line.first[i]
            val across = line.second[i]
            for (o in -12..12) {
                val x = if (horizontal) (along + .5).toInt() else (across + .5).toInt() + o
                val y = if (horizontal) (across + .5).toInt() + o else (along + .5).toInt()
                if (x < 0 || x >= width || y < 0 || y >= height) continue
                profile[o + 12] += delta[y * width + x]
                counts[o + 12]++
            }
        }
        var peak = 0.0
        for (i in 0..24) {
            profile[i] /= max(1, counts[i])
            peak = max(peak, profile[i])
        }
        if (peak <= 1) return 0.0
        var first = -1
        var last = -1
        for (i in 0..24) if (profile[i] >= peak * .5) {
            if (first < 0) first = i
            last = i
        }
        return if (first < 0) 0.0 else (last - first + 1).toDouble()
    }

    // ---------------------------------------------------------------- correction

    private fun estimateAngle(
        horizontal: List<Line>,
        vertical: List<Line>,
        text: List<Line>,
    ): Double {
        val slopes = ArrayList<Double>()
        if (horizontal.size >= 3) horizontal.forEach { slopes.add(it.slope) }
        else text.forEach { slopes.add(it.slope) }
        if (vertical.size >= 3) vertical.forEach { slopes.add(-it.slope) }
        if (slopes.isEmpty()) return 0.0
        return median(slopes).coerceIn(-.05, .05)
    }

    private fun rotateLines(
        lines: List<Line>,
        theta: Double,
        width: Int,
        height: Int,
        axis: Axis,
    ): List<Control> {
        val out = ArrayList<Control>()
        for (line in lines) {
            val pts =
                ArrayList<Pair<Double, Double>>(line.xs.size).also { points ->
                    for (i in line.xs.indices) {
                        val x = if (axis == Axis.VERTICAL) line.ys[i] else line.xs[i]
                        val y = if (axis == Axis.VERTICAL) line.xs[i] else line.ys[i]
                        val q = rotate(x, y, theta, width, height, inverse = true)
                        points.add(
                            if (axis == Axis.VERTICAL) q[1] to q[0] else q[0] to q[1]
                        )
                    }
                }
            val compact = compact(pts.map { it.first }.toDoubleArray(), pts.map { it.second }.toDoubleArray())
            if (compact.first.size < 12) continue
            out.add(
                Control(
                    median(compact.second),
                    compact.first.first(),
                    compact.first.last(),
                    compact.first,
                    compact.second,
                )
            )
        }
        return out.sortedBy { it.mid }
    }

    private fun fieldResidual(lines: List<Control>, along: Double, cross: Double): Double {
        if (lines.isEmpty()) return 0.0
        if (cross <= lines.first().mid) return residual(lines.first(), along)
        if (cross >= lines.last().mid) return residual(lines.last(), along)
        for (i in 0 until lines.size - 1) {
            val a = lines[i]
            val b = lines[i + 1]
            if (cross >= a.mid && cross <= b.mid) {
                val t = (cross - a.mid) / max(1e-6, b.mid - a.mid)
                return residual(a, along) * (1 - t) + residual(b, along) * t
            }
        }
        return residual(lines.last(), along)
    }

    private fun residual(line: Control, along: Double): Double {
        val at = along.coerceIn(line.lo, line.hi)
        return interpolate(line.xs, line.ys, at) - line.mid
    }

    private fun interpolate(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        if (xs.size == 1) return ys[0]
        if (x <= xs[0]) return ys[0]
        if (x >= xs[xs.lastIndex]) return ys[ys.lastIndex]
        var low = 0
        var high = xs.lastIndex
        while (low + 1 < high) {
            val middle = (low + high) / 2
            if (xs[middle] <= x) low = middle else high = middle
        }
        val t = (x - xs[low]) / max(1e-9, xs[high] - xs[low])
        return ys[low] * (1 - t) + ys[high] * t
    }

    private fun rotate(
        x: Double,
        y: Double,
        theta: Double,
        width: Int,
        height: Int,
        inverse: Boolean,
    ): DoubleArray {
        val cx = (width - 1) / 2.0
        val cy = (height - 1) / 2.0
        val px = x - cx
        val py = y - cy
        val ct = cos(theta)
        val st = sin(theta)
        return if (inverse) {
            // input -> output frame for analysing a line that will be rotated by -theta
            doubleArrayOf(ct * px + st * py + cx, -st * px + ct * py + cy)
        } else {
            // output -> input backward map
            doubleArrayOf(ct * px - st * py + cx, st * px + ct * py + cy)
        }
    }

    // ---------------------------------------------------------------- metrics and guards

    private fun metricsOf(detection: Detection): Metrics {
        val horizontal = axisMetric(detection.horizontal)
        val vertical = axisMetric(detection.vertical)
        val text = axisMetric(detection.text)
        return Metrics(
            horizontal,
            vertical,
            text,
            detection.glyphHeight,
            detection.glyphWidth,
            detection.inkMass,
        )
    }

    private fun axisMetric(lines: List<Line>): AxisMetric {
        if (lines.isEmpty()) return AxisMetric()
        val rms = lines.map { it.rms }
        val angles = lines.map { Math.toDegrees(atan(it.slope)) }
        val fw = lines.map { it.fwhm }.filter { it > 0 }
        val spacing = ArrayList<Double>()
        val sorted = lines.sortedBy { it.mid }
        for (i in 0 until sorted.size - 1) spacing.add(sorted[i + 1].mid - sorted[i].mid)
        return AxisMetric(
            lines.size,
            median(rms),
            percentile(rms, .95),
            median(angles),
            median(angles.map { abs(it) }),
            if (fw.isEmpty()) 0.0 else median(fw),
            median(lines.map { it.span }),
            if (spacing.isEmpty()) 0.0 else median(spacing),
        )
    }

    private fun guardOf(
        detection: Detection,
        before: Metrics,
        after: Metrics,
        width: Int,
        height: Int,
    ): Guard {
        val inkRatio = after.inkMass / max(1e-6, before.inkMass)
        val glyphHeightRatio =
            if (before.glyphHeight <= 0 || after.glyphHeight <= 0) 1.0
            else after.glyphHeight / before.glyphHeight
        val glyphWidthRatio =
            if (before.glyphWidth <= 0 || after.glyphWidth <= 0) 1.0
            else after.glyphWidth / before.glyphWidth
        val spacingBefore =
            if (before.horizontal.lines >= 3) before.horizontal.spacingMedian
            else before.text.spacingMedian
        val spacingAfter =
            if (before.horizontal.lines >= 3) after.horizontal.spacingMedian else after.text.spacingMedian
        val spacingRatio = if (spacingBefore <= 0 || spacingAfter <= 0) 1.0 else spacingAfter / spacingBefore
        var reason = ""
        if (inkRatio !in .95..1.05) reason = "ink-retention"
        else if (abs(glyphHeightRatio - 1) > .15) reason = "glyph-height"
        else if (abs(glyphWidthRatio - 1) > .18) reason = "glyph-width"
        else if (abs(spacingRatio - 1) > .10) reason = "line-spacing"
        // Border ink must survive; the field is already blended to zero at the margins.
        return Guard(
            inkRatio,
            glyphHeightRatio,
            glyphWidthRatio,
            spacingRatio,
            accepted = reason.isEmpty(),
            reason = reason,
        )
    }

    private fun sample(
        source: ByteArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float,
    ): Byte {
        val sx = x.coerceIn(0f, (width - 1).toFloat())
        val sy = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val x1 = min(width - 1, x0 + 1)
        val y1 = min(height - 1, y0 + 1)
        val fx = sx - x0
        val fy = sy - y0
        fun at(xx: Int, yy: Int) = source[yy * width + xx].toInt() and 255
        val top = at(x0, y0) * (1 - fx) + at(x1, y0) * fx
        val bottom = at(x0, y1) * (1 - fx) + at(x1, y1) * fx
        return (top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255).toByte()
    }

    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * fraction).roundToInt().coerceIn(0, sorted.lastIndex)]
    }
}
