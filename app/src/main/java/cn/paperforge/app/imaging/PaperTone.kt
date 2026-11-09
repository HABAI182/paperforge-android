package cn.paperforge.app.imaging

import kotlin.math.*
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Tone of the silhouette band that [WorkbookPaperBoundary] repaints (v200 20019 feedback).
 *
 * The 20019 report is "the shadow area looks like the background was pushed to 255 white while the
 * body paper is not that white, and the writing inside the shadow is a bit bolder". It is exactly
 * what the old exit did: `destination.setTo(255, exterior)` painted every accepted exterior pixel
 * to a flat 255 plateau. Two consequences:
 *
 * - the band sat 1-3 gray *above* the body's paper (the body keeps its measured level), and the
 *   mask contour became a hard step because the plateau has no paper texture at all;
 * - only the paper was repainted. The strokes inside the band are mask *holes* (the boundary's
 *   stroke veto), so they kept their own level while the paper around them jumped to 255. The
 *   ink/paper ratio therefore changed across the band edge - the "bolder letters" report.
 *
 * This step replaces the flood with one multiplicative gain applied to the whole band block
 * (paper **and** ink), so:
 *
 * 1. `gain = target / bandPaper` where `target = min(page paper median, paperToneCeiling)`. The
 *    ceiling is 254, so a page whose own paper is already >= 254 never gets a 255 band - the
 *    reported failure mode is structurally impossible, not clamped after the fact;
 * 2. every pixel of the block is scaled by that same gain, so `ink / paper` is exactly preserved
 *    and the apparent stroke weight cannot change;
 * 3. the gain is feathered across the mask contour (a blurred mask weight), so the band boundary is
 *    a gradient instead of a one-pixel step;
 * 4. the step only ever darkens a band (`gain <= 1`). Lifting paper is [PaperWhiteFinal]'s job and
 *    it measures the whole page; a band must never be *whiter* than the page it belongs to.
 *
 * The page's paper median is read from [PaperWhiteFinal]'s own measurement of the rendered page,
 * which runs immediately before this step in [EnhancePipeline.finishWorkbook]: the two steps then
 * share one definition of "paper level" instead of each inventing one.
 */
internal object PaperTone {
    const val VERSION = "paper-tone-1"
    private val last = ThreadLocal<JSONObject>()

    fun diagnostics() = last.get()

    fun enabled() = ScanConfig.int("paperToneSameGain", 1) != 0

    /**
     * Retone [page] (in place) over [band] (CV_8UC1, 0 or 255). Returns diagnostics, or null when
     * the step is disabled, the mask is empty, or there is nothing to do.
     */
    fun retone(page: Mat, band: Mat): JSONObject? {
        if (!enabled() || page.empty() || band.empty()) return null
        val started = System.nanoTime()
        val channels = page.channels()
        if (channels != 1 && channels != 3) return null
        val width = page.cols()
        val height = page.rows()
        if (band.cols() != width || band.rows() != height) return null
        val ceiling = ScanConfig.int("paperToneCeiling", 254).coerceIn(200, 254)
        val minimumGain = ScanConfig.float("paperToneMinimumGain", .80f).coerceIn(.5f, 1f)
        val feather =
            ScanConfig.float("paperToneFeather", 3f).coerceIn(0f, 12f).toDouble()
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        try {
            val gray = mat()
            when (channels) {
                1 -> page.copyTo(gray)
                else -> Imgproc.cvtColor(page, gray, Imgproc.COLOR_RGB2GRAY)
            }
            val grayBytes = ByteArray(width * height).also { gray.get(0, 0, it) }
            val bandBytes = ByteArray(width * height).also { band.get(0, 0, it) }
            // The block, not just the repainted paper: close the 1 px stroke holes the boundary's
            // stroke veto cut out of the mask so the strokes receive the same gain as the paper.
            val mask = mat()
            mask.create(height, width, CvType.CV_8UC1)
            mask.put(
                0,
                0,
                ByteArray(bandBytes.size) {
                    if (bandBytes[it].toInt() != 0) 255.toByte() else 0.toByte()
                },
            )
            val closeSize =
                max(3, (min(width, height) * ScanConfig.float("paperToneCloseFraction", .008f)).roundToInt() or 1)
            val closed = mat()
            Imgproc.morphologyEx(
                mask,
                closed,
                Imgproc.MORPH_CLOSE,
                Imgproc.getStructuringElement(
                        // A rectangular kernel is separable, so the close costs a fraction of the
                        // equivalent ellipse on an unaccelerated device.
                        Imgproc.MORPH_RECT,
                        Size(closeSize.toDouble(), closeSize.toDouble()),
                    )
                    .also(owned::add),
            )
            val closedBytes = ByteArray(width * height).also { closed.get(0, 0, it) }
            // No row-span fill here: the boundary's mask can touch both side edges of a row, so a
            // span fill would swallow the whole row (measured: 98% of the page instead of 12%).
            // The morphological close alone fills the 1-4 px stroke holes that matter.
            var blockPixels = 0
            val levels = IntArray(256)
            val darkest = IntArray(256)
            // Paper level of the page: the same statistic PaperWhiteFinal gates on when it ran in
            // this render, so both steps agree on what "the paper median" means. The full-page
            // histogram is only built when that measurement is unavailable (it is a second
            // 1.4 M-pixel pass, and this emulator has no hardware acceleration).
            val measured = PaperWhiteFinal.diagnostics()?.optInt("paperEstimate", 0) ?: 0
            for (i in grayBytes.indices) {
                val value = grayBytes[i].toInt() and 255
                if (closedBytes[i].toInt() != 0) {
                    blockPixels++
                    levels[value]++
                }
                if (measured <= 0) darkest[value]++
            }
            if (blockPixels == 0) return null
            val pagePaper = if (measured > 0) measured else percentile(darkest, grayBytes.size, .75f)
            val bandPaper = percentile(levels, blockPixels, .75f)
            val target = min(pagePaper, ceiling)
            val gain = (target.toFloat() / max(1, bandPaper)).coerceIn(minimumGain, 1f)
            val stats =
                JSONObject()
                    .put("version", VERSION)
                    .put("bandPixels", blockPixels)
                    .put("bandShare", blockPixels.toDouble() / grayBytes.size)
                    .put("pagePaper", pagePaper)
                    .put("bandPaper", bandPaper)
                    .put("target", target)
                    .put("gain", gain.toDouble())
                    .put("closeSize", closeSize)
            if (gain > .9995f) {
                last.set(
                    stats.put("applied", false)
                        .put("reason", "band-at-or-below-target")
                        .put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0)
                )
                return last.get()
            }
            // Feathered weight: 1 inside the block, 0 outside, Gaussian across the contour. Kept as
            // 8-bit so the per-pixel pass below is pure integer arithmetic (the emulator used for
            // acceptance has no hardware acceleration; a float loop here cost ~0.6 s per page).
            val weight = mat()
            closed.copyTo(weight)
            if (feather > 0.0) Imgproc.GaussianBlur(weight, weight, Size(0.0, 0.0), feather)
            val weightBytes = ByteArray(width * height).also { weight.get(0, 0, it) }
            // The per-pixel gain is an 8-bit multiplier (255 = unchanged) so the whole block is
            // scaled by one native Core.multiply instead of a JVM loop over 4.4 M samples. The gain
            // must be expressed in that 8-bit space, not in per-mille: 0.996 is 254/255, and
            // writing 996 here would clamp to 255 and silently do nothing (caught in acceptance).
            val unity = (gain * 255f).roundToInt().coerceIn(1, 255)
            val deficit = 255 - unity
            var changed = 0
            val gainBytes = ByteArray(weightBytes.size)
            for (i in weightBytes.indices) {
                val w = weightBytes[i].toInt() and 255
                val factor = 255 - (deficit * w + 127) / 255
                if (factor < 255) changed++
                gainBytes[i] = factor.coerceIn(1, 255).toByte()
            }
            val gainMap = mat()
            gainMap.create(height, width, CvType.CV_8UC1)
            gainMap.put(0, 0, gainBytes)
            val gainThree = mat()
            if (channels == 3) Imgproc.cvtColor(gainMap, gainThree, Imgproc.COLOR_GRAY2RGB)
            else gainMap.copyTo(gainThree)
            Core.multiply(page, gainThree, page, 1.0 / 255.0)
            last.set(
                stats
                    .put("applied", true)
                    .put("reason", "band-follows-page-paper")
                    .put("changedSamples", changed)
                    .put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0)
            )
            return last.get()
        } finally {
            owned.forEach(Mat::release)
        }
    }

    private fun percentile(histogram: IntArray, count: Int, fraction: Float): Int {
        if (count <= 0) return 255
        val target = max(1, (count * fraction).toInt())
        var sum = 0
        for (level in histogram.indices) {
            sum += histogram[level]
            if (sum >= target) return level
        }
        return 255
    }
}
