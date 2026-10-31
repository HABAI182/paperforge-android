package cn.paperforge.app.imaging

import android.graphics.Bitmap
import kotlin.math.*

/**
 * One spine search result. [x], [top] and [bottom] are normalized to 0..1 of the rendered page
 * width; [top] and [bottom] are the fractions to hand to `CropGeometry.split` so each half keeps
 * the full page height. [x] is null when nothing was found and [reason] says why.
 *
 * [seamLeft]/[seamRight] are the two edges of the detected gutter band at the upper and lower
 * sample bands. The auto split trims the left page to [seamLeft] and the right page to [seamRight],
 * so neither page keeps half of the shadow. Both are null when nothing was found.
 */
data class SpineDetection(
    val x: Float?,
    val top: Float = 0f,
    val bottom: Float = 0f,
    val confidence: Float = 0f,
    val method: String = BookSpineDetector.MethodNone,
    val reason: String = "",
    val evidence: Map<String, Float> = emptyMap(),
    val seamLeft: SpineEdge? = null,
    val seamRight: SpineEdge? = null,
) {
    val found: Boolean
        get() = x != null
}

/** One gutter edge as page-width fractions at the upper ([top]) and lower ([bottom]) band. */
data class SpineEdge(val top: Float, val bottom: Float)

/**
 * #23: locate the vertical fold / gutter of a scanned two-page spread.
 *
 * The core is a pure luma-array function so it can be unit-tested on the JVM without bitmaps. The
 * input is one already-cropped page: the editor renders the page quad first, so the returned
 * top/bottom fractions are fractions of the rendered width and can be passed straight to
 * `CropGeometry.split`.
 *
 * Two independent cues, both measured in the middle 40% of the width only:
 * 1. Column projection high-pass. Mean luma per column minus a baseline taken at ±5% page width
 *    removes uneven illumination. A bound-book fold is a valley (shadow), a separated page pair is
 *    a peak (bright gap).
 * 2. Vertical consistency. Per sampled row, compare the column with the same row ±5% width away.
 *    Text strokes vote only on the rows that contain ink, while a fold votes on nearly every row.
 *
 * A candidate must also split the page into two halves that both contain ink. Anything else returns
 * [SpineDetection.found] = false with a human-readable reason; callers must not split.
 */
object BookSpineDetector {
    const val MethodDark = "dark-band"
    const val MethodBright = "white-seam"
    const val MethodNone = "none"

    // The fold has to sit near the middle of a spread; the search window is 30%..70% of the width.
    private const val SearchFrom = .30f
    private const val SearchTo = .70f
    private const val BaselineFraction = .05f
    private const val RowStepTarget = 480

    // Contrast in 0..255 luma levels against the local baseline, and the fraction of sampled rows
    // that must vote for the column. Text columns vote on their glyph rows only (~35% at 44 px
    // leading with 16 px glyphs), so 0.65 rejects text while a full-height shadow stays near 1.0.
    private const val DarkMinContrast = 11f
    private const val BrightMinContrast = 6f
    private const val DarkMinCoverage = .65f
    private const val BrightMinCoverage = .82f
    private const val DarkFullCoverage = .55f
    private const val BrightFullCoverage = .90f
    private const val RowVoteDark = 8f
    private const val RowVoteBright = 5f

    // Width of the anomaly itself as a fraction of the page width. A gutter is narrow; a lighting
    // gradient or a wide vignette is not.
    private const val MinBandFraction = .0005f
    private const val DarkMaxBandFraction = .06f
    private const val BrightMaxBandFraction = .03f

    private const val MaxTiltFraction = .04f
    private const val MinInk = .002f
    private const val MaxInkRatio = 4f
    private const val InkDelta = 30
    private const val MinConfidence = .50f

    fun detect(bitmap: Bitmap): SpineDetection {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return none("图片为空")
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val red = pixel shr 16 and 0xff
            val green = pixel shr 8 and 0xff
            val blue = pixel and 0xff
            luma[i] = (red * 299 + green * 587 + blue * 114) / 1000
        }
        return detect(luma, width, height)
    }

    fun detect(luma: IntArray, width: Int, height: Int): SpineDetection {
        if (width < 240 || height < 240) return none("图像过小（${width}×${height}），无法判断双页")
        if (luma.size < width * height) return none("像素数量与尺寸不一致")
        val rowStep = max(1, height / RowStepTarget)
        val topBand =
            band(luma, width, (height * .08f).roundToInt(), (height * .45f).roundToInt(), rowStep)
        val bottomBand =
            band(luma, width, (height * .55f).roundToInt(), (height * .92f).roundToInt(), rowStep)
        val fullBand =
            band(luma, width, (height * .04f).roundToInt(), (height * .96f).roundToInt(), rowStep)

        val topPeak = best(topBand, width) ?: return none("未找到贯穿上下的暗带或白缝")
        val bottomPeak = best(bottomBand, width) ?: return none("未找到贯穿上下的暗带或白缝")
        if (topPeak.method != bottomPeak.method)
            return none("上下半区的书脊特征不一致（${topPeak.method} / ${bottomPeak.method}）")
        val dark = topPeak.method == MethodDark
        val tilt = abs(topPeak.x - bottomPeak.x)
        if (tilt > width * MaxTiltFraction)
            return none("书脊倾斜 ${"%.1f".format(tilt * 100f / width)}% 页宽，超过本轮直线中缝范围")

        val split = (topPeak.x + bottomPeak.x) / 2
        val fullCoverage =
            maxOf(
                fullBand.coverage(topPeak.x, dark),
                fullBand.coverage(bottomPeak.x, dark),
                fullBand.coverage(split, dark),
            )
        if (fullCoverage < if (dark) DarkFullCoverage else BrightFullCoverage)
            return none("书脊带没有贯穿全页（覆盖 ${(fullCoverage * 100).roundToInt()}%）")

        val (inkLeft, inkRight) = inkFractions(luma, width, height, split, rowStep)
        if (inkLeft < MinInk || inkRight < MinInk) return none("左右两半的内容分布不足，判断不是完整跨页")
        val ratio = max(inkLeft, inkRight) / min(inkLeft, inkRight)
        if (ratio > MaxInkRatio) return none("左右内容相差 ${"%.1f".format(ratio)} 倍，判断不是同一跨页")

        val balance = (1f - ln(ratio) / ln(MaxInkRatio)).coerceIn(0f, 1f)
        val confidence =
            (.58f * min(topPeak.score, bottomPeak.score) +
                .30f * (topPeak.score + bottomPeak.score) / 2f +
                .12f * balance) * (1f - .08f * (tilt / (width * MaxTiltFraction)))
        if (confidence < MinConfidence)
            return none("书脊证据不足（置信度 ${(confidence * 100).roundToInt()}%）")

        val maxX = (width - 1).toFloat()
        val evidence =
            mapOf(
                "topFraction" to topPeak.x / maxX,
                "bottomFraction" to bottomPeak.x / maxX,
                "topContrast" to topPeak.contrast,
                "bottomContrast" to bottomPeak.contrast,
                "topCoverage" to topPeak.coverage,
                "bottomCoverage" to bottomPeak.coverage,
                "fullCoverage" to fullCoverage,
                "bandFraction" to max(topPeak.bandFraction, bottomPeak.bandFraction),
                "inkLeft" to inkLeft,
                "inkRight" to inkRight,
                "tiltFraction" to tilt / maxX,
            )
        return SpineDetection(
            x = split / maxX,
            top = topPeak.x / maxX,
            bottom = bottomPeak.x / maxX,
            confidence = confidence.coerceIn(0f, 1f),
            method = topPeak.method,
            evidence = evidence,
            seamLeft = SpineEdge(topPeak.left / maxX, bottomPeak.left / maxX),
            seamRight = SpineEdge(topPeak.right / maxX, bottomPeak.right / maxX),
        )
    }

    private class Band(
        val delta: FloatArray,
        val darkVotes: FloatArray,
        val brightVotes: FloatArray,
    ) {
        fun coverage(x: Int, dark: Boolean) = if (dark) darkVotes[x] else brightVotes[x]
    }

    private class Peak(
        val x: Int,
        val method: String,
        val contrast: Float,
        val coverage: Float,
        val bandFraction: Float,
        val score: Float,
        val left: Int,
        val right: Int,
    )

    private fun band(luma: IntArray, width: Int, yFrom: Int, yTo: Int, rowStep: Int): Band {
        val offset = (width * BaselineFraction).roundToInt().coerceIn(8, 120)
        val sum = DoubleArray(width)
        val darkVotes = FloatArray(width)
        val brightVotes = FloatArray(width)
        var samples = 0
        var y = yFrom.coerceIn(0, max(0, yTo - 1))
        while (y < yTo) {
            val row = y * width
            for (x in 0 until width) sum[x] += luma[row + x].toFloat()
            var x = offset
            while (x < width - offset) {
                val side = (luma[row + x - offset] + luma[row + x + offset]) * .5f
                val value = luma[row + x].toFloat()
                if (side - value >= RowVoteDark) darkVotes[x]++
                if (value - side >= RowVoteBright) brightVotes[x]++
                x++
            }
            samples++
            y += rowStep
        }
        if (samples == 0) return Band(FloatArray(width), FloatArray(width), FloatArray(width))
        val mean = FloatArray(width) { (sum[it] / samples).toFloat() }
        val smoothed = smooth(mean, max(1, width / 600))
        // Compare against the BRIGHTER side, not the average. An average lets the dark gutter pull
        // down the baseline of the blank margin next to it, which then looks like a bright seam
        // (measured on synthetic spreads: a dark fold at 46% was reported as a white seam at
        // 50.9%).
        // Comparing with the brighter side keeps every genuine shadow a valley and every genuine
        // bright gap a peak, without inventing a peak beside a dark band.
        val delta =
            FloatArray(width) { i ->
                val left = smoothed[(i - offset).coerceIn(0, width - 1)]
                val right = smoothed[(i + offset).coerceIn(0, width - 1)]
                smoothed[i] - max(left, right)
            }
        for (i in 0 until width) {
            darkVotes[i] /= samples
            brightVotes[i] /= samples
        }
        return Band(delta, darkVotes, brightVotes)
    }

    private fun smooth(values: FloatArray, radius: Int): FloatArray {
        if (radius <= 0) return values
        val n = values.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + values[i]
        return FloatArray(n) { i ->
            val from = max(0, i - radius)
            val to = min(n - 1, i + radius)
            ((prefix[to + 1] - prefix[from]) / (to - from + 1)).toFloat()
        }
    }

    private fun best(band: Band, width: Int): Peak? =
        (peaks(band, width, dark = true) + peaks(band, width, dark = false)).maxByOrNull {
            it.score
        }

    /**
     * One candidate per contiguous anomalous run. The run's MIDPOINT is the position: a flat dark
     * band has the same contrast on every column inside it, so taking the strongest column biases
     * the split to the run's brightest edge (measured: 0.464 on a fold whose true centre was
     * 0.460). The run width is the second rejection cue: a lighting gradient is wide, a fold is
     * not.
     */
    private fun peaks(band: Band, width: Int, dark: Boolean): List<Peak> {
        val from = (width * SearchFrom).roundToInt().coerceIn(1, width - 2)
        val to = (width * SearchTo).roundToInt().coerceIn(from, width - 2)
        val edgeThreshold = if (dark) 6f else 3.5f
        fun hit(x: Int) = (if (dark) -band.delta[x] else band.delta[x]) >= edgeThreshold
        val minContrast = if (dark) DarkMinContrast else BrightMinContrast
        val minCoverage = if (dark) DarkMinCoverage else BrightMinCoverage
        val maxBand = if (dark) DarkMaxBandFraction else BrightMaxBandFraction
        val result = mutableListOf<Peak>()
        var x = from
        while (x <= to) {
            if (!hit(x)) {
                x++
                continue
            }
            val left = x
            var right = x
            while (right + 1 <= to && hit(right + 1)) right++
            x = right + 1
            val widthFraction = (right - left + 1).toFloat() / width
            if (widthFraction !in MinBandFraction..maxBand) continue
            var contrast = 0f
            for (i in left..right) contrast =
                max(contrast, if (dark) -band.delta[i] else band.delta[i])
            if (contrast < minContrast) continue
            val center = (left + right) / 2
            val coverage = band.coverage(center, dark)
            if (coverage < minCoverage) continue
            val centerOffset = (center.toFloat() / (width - 1) - .5f) / .16f
            val centerScore = exp(-(centerOffset * centerOffset)).toFloat()
            val contrastScore = (contrast / if (dark) 40f else 16f).coerceIn(0f, 1f)
            val coverageScore =
                ((coverage - if (dark) .55f else .70f) / if (dark) .40f else .25f).coerceIn(0f, 1f)
            val score = .46f * contrastScore + .34f * coverageScore + .20f * centerScore
            result.add(
                Peak(
                    center,
                    if (dark) MethodDark else MethodBright,
                    contrast,
                    coverage,
                    widthFraction,
                    score,
                    left,
                    right,
                )
            )
        }
        return result
    }

    /**
     * Ink coverage of each half, with a paper level estimated per half so a horizontal lighting
     * gradient is not mistaken for content imbalance. The seam band itself is excluded.
     */
    private fun inkFractions(
        luma: IntArray,
        width: Int,
        height: Int,
        split: Int,
        rowStep: Int,
    ): Pair<Float, Float> {
        val margin = (width * .02f).roundToInt()
        val gap = max(2, (width * .015f).roundToInt())
        fun ink(from: Int, to: Int): Float {
            if (to - from < 2) return 0f
            val histogram = IntArray(256)
            var count = 0
            var y = (height * .06f).roundToInt()
            val yEnd = (height * .94f).roundToInt()
            while (y < yEnd) {
                val row = y * width
                for (x in from until to) {
                    histogram[luma[row + x]]++
                    count++
                }
                y += rowStep
            }
            if (count == 0) return 0f
            val paper = quantile(histogram, .90f)
            var inkCount = 0
            for (level in 0 until max(0, paper - InkDelta)) inkCount += histogram[level]
            return inkCount.toFloat() / count
        }
        return ink(margin, max(margin + 1, split - gap)) to
            ink(min(split + gap, width - margin), max(split + gap + 2, width - margin))
    }

    private fun quantile(histogram: IntArray, fraction: Float): Int {
        val target = max(1, (histogram.sum() * fraction).toInt())
        var count = 0
        for (i in histogram.indices) {
            count += histogram[i]
            if (count >= target) return i
        }
        return 255
    }

    private fun none(reason: String) =
        SpineDetection(x = null, method = MethodNone, reason = reason)
}
