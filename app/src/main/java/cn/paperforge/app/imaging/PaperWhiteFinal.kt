package cn.paperforge.app.imaging

import kotlin.math.*
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Final paper-whiteness step for the rendered page ("B0" in PAPER-WHITENING-2026-11-04,
 * "mild target" in PAPER-WHITENESS-TUNE-2026-11-04, whole-page field in the 2026-11-04
 * shadow/ghost pass).
 *
 * The page is divided by its own low-frequency paper field, so blank paper reaches the target
 * paper white and low-contrast paper gradients stop reading as grey. There is no ink knee, no
 * floor and no stroke-gated mix: the research rejected the soft knee (it erases uniform faint
 * pencil), the CLAHE path (it amplifies show-through) and the strict knee on real DocUNet photos.
 *
 * The field is [PaperField.estimate]: one smooth, continuous surface fitted on paper pixels only.
 * It replaces the old closing envelope, whose upper-envelope bias hid a page that was really at
 * 251 behind a "paper" reading of 255 and made the gate skip the lift. The division is a single
 * continuous gain `clip(target / field, 1, maximumGain)`, capped per pixel so a lifted pixel can
 * never pass 255 (no clipping) and can never be darkened. Because the field has no band boundary,
 * the gain has none either: a shadow band and the bright area are lifted by one continuous curve.
 *
 * It runs after [WorkbookContent.write] (or the legacy workbook exit) on the already rendered page,
 * and before thumbnails, page rendering and PDF packing consume it. [PdfImageEnhancement] and batch
 * edits call [EnhancePipeline.apply] too, so they share this step.
 *
 * Fail-safe: a page whose paper level is below [paperWhiteMinimumPaper] is returned unchanged, and
 * the inherited dark-capture interlock (field p10 < minimum) refuses the very dark inputs the
 * operator was not designed for.
 */
object PaperWhiteFinal {
    const val VERSION = "paper-white-final-3"
    private val last = ThreadLocal<JSONObject>()

    fun diagnostics() = last.get()

    fun enabled() = ScanConfig.int("paperWhiteFinal", 1) != 0

    /**
     * Mutates [destination] in place; returns the measured parameters, or null when skipped.
     * [supplied] is the whole-page paper field already measured by the caller (the same field
     * [ToneZones] uses), so one render estimates the field once instead of twice.
     */
    fun apply(destination: Mat, supplied: Mat? = null): JSONObject? {
        if (!enabled() || destination.empty()) return null
        val channels = destination.channels()
        if (channels != 1 && channels != 3) return null
        val width = destination.cols()
        val height = destination.rows()
        if (width < 64 || height < 64) return null
        val target = ScanConfig.int("paperWhiteTarget", 254).coerceIn(200, 254)
        val percentile = ScanConfig.float("paperWhitePercentile", .50f).coerceIn(.1f, .99f)
        val maximumGain = ScanConfig.float("paperWhiteMaximumGain", 1.35f).coerceIn(1f, 2.5f)
        val minimumPaper = ScanConfig.int("paperWhiteMinimumPaper", 120).coerceIn(1, 254)
        // Calibration from the DocUNet regression: the smooth field is not an upper envelope, so
        // its p10 no longer trips the v1 dark-capture interlock on ordinary camera photos
        // (paper 125-173). Without a paper floor the step would lift 2/20 public photos and cost
        // 2.08 pp of faint-stroke retention. The floor keeps the interlock's intent: this mild
        // final lift is for a rendered page, not for a dark photograph.
        val paperFloor = ScanConfig.int("paperWhitePaperFloor", 180).coerceIn(120, 254)
        val inkFloor = ScanConfig.float("paperWhiteInkFloor", .02f).coerceIn(.005f, .08f)
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val gray = mat()
        val field = mat()
        try {
            when (channels) {
                1 -> destination.copyTo(gray)
                else -> Imgproc.cvtColor(destination, gray, Imgproc.COLOR_RGB2GRAY)
            }
            // One whole-page field for the whole page: fitted on paper pixels, continuous at every
            // coordinate, no 8% band and no per-mask segment. The caller may already have it.
            if (
                supplied != null &&
                    !supplied.empty() &&
                    supplied.type() == CvType.CV_32FC1 &&
                    supplied.cols() == width &&
                    supplied.rows() == height
            )
                supplied.copyTo(field)
            else PaperField.estimate(gray, field)
            val fieldValues = FloatArray(width * height).also { field.get(0, 0, it) }
            val fieldBytes = ByteArray(width * height) {
                fieldValues[it].roundToInt().coerceIn(0, 255).toByte()
            }
            val grayBytes = ByteArray(width * height).also { gray.get(0, 0, it) }
            val source = ByteArray(width * height * channels)
            destination.get(0, 0, source)
            // Ink is measured against the same field the division uses, so "paper" here means
            // "within paperWhiteInkFloor of the local paper" - not "bright pixel". The
            // classification reads the grey copy, so a saturated colour channel cannot promote a
            // coloured patch to "paper".
            val floorScaled = (inkFloor * 1000f).roundToInt()
            val histogram = IntArray(256)
            val fieldHistogram = IntArray(256)
            var paperCount = 0
            for (i in 0 until width * height) {
                val paper = max(1, fieldBytes[i].toInt() and 255)
                val value = grayBytes[i].toInt() and 255
                fieldHistogram[paper]++
                // value * 1000 > paper * (1000 - floor) keeps the classification in integers.
                if (value * 1000 > paper * (1000 - floorScaled)) {
                    histogram[value]++
                    paperCount++
                }
            }
            val fieldLevel = percentile(fieldHistogram, width * height, .10f)
            val paperLevel = if (paperCount == 0) 0 else percentile(histogram, paperCount, percentile)
            val fieldStats = PaperField.summary(field)
            val stats =
                JSONObject()
                    .put("version", VERSION)
                    .put("paperEstimate", paperLevel)
                    .put("paperPixels", paperCount)
                    .put("paperPixelShare", if (width * height == 0) 0.0 else paperCount.toDouble() / (width * height))
                    .put("fieldP10", fieldLevel)
                    .put("field", fieldStats)
                    .put("target", target)
                    .put("percentile", percentile.toDouble())
                    .put("inkFloor", inkFloor.toDouble())
                    .put("maximumGain", maximumGain)
                    .put("paperFloor", paperFloor)
            // The dark-capture interlock is inherited from paper-white-final-1: it reads the
            // darkest decile of the paper field and refuses a page darker than the operator was
            // designed for (a photographed page at paper 70 only loses contrast if it is pushed to
            // 255). The gate that decides "is there paper to lift" is the measured paper level
            // below; this one is a safety interlock and is deliberately not loosened.
            if (fieldLevel < minimumPaper) {
                last.set(stats.put("applied", false).put("reason", "field-below-minimum"))
                return last.get()
            }
            if (paperLevel < minimumPaper) {
                last.set(stats.put("applied", false).put("reason", "paper-below-minimum"))
                return last.get()
            }
            if (paperLevel < paperFloor) {
                last.set(stats.put("applied", false).put("reason", "paper-below-floor"))
                return last.get()
            }
            // Act only while the paper itself is under the target. Gains are clamped at >= 1, so
            // when the paper is already at or above the target the exact same division would be an
            // identity; skipping makes that explicit in the diagnostics instead of lifting nothing.
            if (paperLevel >= target) {
                last.set(stats.put("applied", false).put("reason", "paper-at-or-above-target"))
                return last.get()
            }
            val gains = FloatArray(width * height)
            var lifted = 0
            var sumGain = 0.0
            for (i in gains.indices) {
                val paper = max(1, fieldBytes[i].toInt() and 255)
                val value = grayBytes[i].toInt() and 255
                var gain = (target.toDouble() / paper).coerceIn(1.0, maximumGain.toDouble())
                // Never push a pixel past the target, and never past 255 at all: that is what keeps
                // the ink amplitude and makes clipping impossible (target <= 254 < 255). Pixels
                // already at or above the target keep their value - this step never darkens.
                if (value in 1 until target) {
                    gain = min(gain, target.toDouble() / value)
                } else if (value >= target) {
                    gain = 1.0
                }
                gains[i] = gain.toFloat()
                sumGain += gain
                if (gain > 1.0005) lifted++
            }
            for (c in 0 until channels) {
                val base = c
                for (i in gains.indices) {
                    val value = source[i * channels + base].toInt() and 255
                    source[i * channels + base] =
                        (value * gains[i] + .5f).toInt().coerceIn(0, 255).toByte()
                }
            }
            destination.put(0, 0, source)
            stats
                .put("applied", true)
                .put("reason", "paper-below-target")
                .put("pixelsLifted", lifted)
                .put("liftedShare", if (width * height == 0) 0.0 else lifted.toDouble() / (width * height))
                .put("meanGain", if (width * height == 0) 1.0 else sumGain / (width * height))
            last.set(stats)
            if (ScanConfig.int("workbookDiagnostics", 0) != 0)
                android.util.Log.d("PaperWhiteFinal", stats.toString())
            return stats
        } finally {
            owned.forEach(Mat::release)
        }
    }

    private fun percentile(histogram: IntArray, count: Int, fraction: Float): Int {
        val target = max(1, (count * fraction).toInt())
        var sum = 0
        for (i in histogram.indices) {
            sum += histogram[i]
            if (sum >= target) return i
        }
        return 255
    }
}
