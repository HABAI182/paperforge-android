package cn.paperforge.app.imaging

import kotlin.math.*
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Show-through / grey-residue suppression on the paper-normalised ink plane.
 *
 * Amplitude alone is not enough (PAPER-PAIRED-2026-11-04: the faint-content and show-through
 * amplitude distributions overlap), so the decision is a *soft threshold with connectivity
 * protection*:
 *
 * - **magnitude ramp**: pixels at or below `paperGhostFloor` are paper and never touched; between
 *   `paperGhostFloor` and `paperGhostHigh` the suppression weight ramps from 1 to 0, so a weak
 *   grey is attenuated in proportion to how weak it is, never binarised;
 * - **isolation**: everything within `paperGhostRadius` of a *stroke anchor* is protected. Anchors
 *   are strong ink (front-side strokes, rules) and pixels whose local maximum reaches
 *   `paperGhostPeak` (faint pencil / thin formula lines). This keeps antialiasing edges attached to
 *   a real stroke and keeps isolated thin-but-defined marks;
 * - **soft application**: `ink' = ink * (1 - weight * strength)`, so no pixel is clipped and a
 *   stroke's relative ink is preserved where it is not suppressed.
 *
 * The strength is the UI's "去透页强度" slider: 0 disables the step, 0.5 and 1.0 give the two
 * acceptance tiers. The same weights are used twice: on the normalised ink plane inside the
 * workbook content path (before the ink contrast curve amplifies residual show-through) and on the
 * rendered page as a final safety net ([EnhancePipeline.finishWorkbook]).
 *
 * v200 20019 adds the **blank-zone sweep** the owner asked for ("clear the blank areas, stay
 * conservative near ink"). A second distance transform is taken from *strong, connected* ink
 * (`ink >= paperGhostAnchor`). A pixel farther than `paperGhostSweepRadius` from any strong ink is
 * in a blank zone: there, low-contrast grey is mapped straight onto local paper (weight 1, i.e.
 * fully cleared at 100%). Nearer than that radius nothing changes - the amplitude ramp and the
 * anchor protection above still govern, so antialiasing edges and faint strokes keep their
 * thresholds. The sweep is a *maximum*, never a reduction, of the existing weight, so it cannot
 * loosen the near-ink rule.
 *
 * The radius and the clearing share the same 0/50/100 slider: at 50% the reach is halved and the
 * grey is halved rather than removed. Measured on the owner's page the sweep is bounded by a real
 * property of the input, not by tuning: 95% of that page's low-contrast residue sits within ~14 px
 * of strong ink (the show-through is registered with the front strokes), so only the far blank
 * margins are reachable - see the acceptance numbers.
 */
internal object PaperGhost {
    const val VERSION = "paper-ghost-2"
    private val last = ThreadLocal<JSONObject>()

    fun diagnostics() = last.get()

    fun enabled() = ScanConfig.int("paperGhostGuard", 1) != 0

    fun sweepEnabled() = ScanConfig.int("paperGhostSweep", 1) != 0

    /** Effective suppression strength in 0..1 for the user's slider value [amount]. */
    fun strength(amount: Float) =
        (amount.coerceIn(0f, 1f) * ScanConfig.float("paperGhostStrength", 1f).coerceIn(0f, 1f))

    /**
     * Attenuates an ink plane (0 = paper, 255 = full ink) in place-copy. Returns the new plane.
     * [amount] is the user's 去透页 strength.
     */
    fun attenuateInk(ink: ByteArray, width: Int, height: Int, amount: Float): ByteArray {
        if (!enabled() || width < 32 || height < 32) return ink
        val strength = strength(amount)
        if (strength <= 0f) return ink
        val low = (ScanConfig.float("paperGhostFloor", .015f).coerceIn(.002f, .10f) * 255f)
        val mid = (ScanConfig.float("paperGhostMid", .055f).coerceIn(.006f, .18f) * 255f)
            .coerceAtLeast(low + 1f)
        val high = (ScanConfig.float("paperGhostHigh", .22f).coerceIn(.03f, .60f) * 255f)
            .coerceAtLeast(mid + 1f)
        val anchorStrong =
            (ScanConfig.float("paperGhostAnchor", .30f).coerceIn(.12f, .8f) * 255f).roundToInt()
        val anchorPeak =
            (ScanConfig.float("paperGhostPeak", .10f).coerceIn(.04f, .40f) * 255f).roundToInt()
        val radius =
            (min(width, height) * ScanConfig.float("paperGhostRadius", .005f))
                .roundToInt()
                .coerceIn(2, 8)
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        try {
            val plane = mat()
            plane.create(height, width, CvType.CV_8UC1)
            plane.put(0, 0, ink)
            val dilated = mat()
            val local = ByteArray(ink.size)
            val three = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                .also(owned::add)
            Imgproc.dilate(plane, dilated, three)
            dilated.get(0, 0, local)
            // Anchors: strong ink, or a local maximum that reaches a defined faint mark.
            val anchorBytes = ByteArray(ink.size) { i ->
                val value = ink[i].toInt() and 255
                val peak = local[i].toInt() and 255
                if (value >= anchorStrong || peak >= anchorPeak) 255.toByte() else 0
            }
            val anchor = mat()
            anchor.create(height, width, CvType.CV_8UC1)
            anchor.put(0, 0, anchorBytes)
            val inverse = mat()
            Core.bitwise_not(anchor, inverse)
            val distance = mat()
            Imgproc.distanceTransform(inverse, distance, Imgproc.DIST_L2, 5)
            val distances = FloatArray(ink.size).also { distance.get(0, 0, it) }
            // Blank-zone sweep: distance from *strong* ink only. Nothing that reaches a defined
            // faint mark (the anchor plane above) is exempted by this map - it only widens the
            // region where an otherwise untouched grey may be mapped onto paper.
            val sweep = sweepEnabled()
            val sweepRadius =
                (min(width, height) * ScanConfig.float("paperGhostSweepRadius", .04f) *
                        strength.coerceAtLeast(0f))
                    .roundToInt()
                    .coerceIn(1, 160)
            val sweepLow =
                (ScanConfig.float("paperGhostSweepFloor", .004f).coerceIn(.0005f, .05f) * 255f)
            val sweepHigh =
                (ScanConfig.float("paperGhostSweepHigh", .30f).coerceIn(.02f, .60f) * 255f)
            val strongDistances =
                if (sweep) {
                    val strongBytes = ByteArray(ink.size) {
                        if ((ink[it].toInt() and 255) >= anchorStrong) 255.toByte() else 0
                    }
                    val strong = mat()
                    strong.create(height, width, CvType.CV_8UC1)
                    strong.put(0, 0, strongBytes)
                    val strongInverse = mat()
                    Core.bitwise_not(strong, strongInverse)
                    val strongDistance = mat()
                    Imgproc.distanceTransform(strongInverse, strongDistance, Imgproc.DIST_L2, 5)
                    FloatArray(ink.size).also { strongDistance.get(0, 0, it) }
                } else null
            val output = ByteArray(ink.size)
            var suppressed = 0
            var swept = 0
            var liftedInk = 0.0
            for (i in ink.indices) {
                val value = ink[i].toInt() and 255
                if (value == 0) {
                    output[i] = ink[i]
                    continue
                }
                // Anchored pixels keep their source value: antialiasing of a real stroke, or a
                // faint mark that is its own local maximum.
                if (distances[i] <= radius.toFloat()) {
                    output[i] = ink[i]
                    continue
                }
                // Blank zone: far from strong ink and not attached to any defined mark. The grey is
                // mapped onto the local paper instead of being merely attenuated, so the band below
                // `paperGhostFloor` is cleared too (that is the visible wash).
                val far =
                    strongDistances != null &&
                        strongDistances[i] > sweepRadius.toFloat() &&
                        value in (sweepLow.toInt() + 1)..(sweepHigh.toInt())
                if (value <= low && !far) {
                    output[i] = ink[i]
                    continue
                }
                val magnitude =
                    when {
                        value <= mid -> 1f
                        value >= high -> 0f
                        else -> (high - value) / (high - mid)
                    }
                val weight = max(magnitude, if (far) 1f else 0f) * strength
                if (weight <= 0f) {
                    output[i] = ink[i]
                    continue
                }
                val reduced = (value * (1f - weight)).roundToInt().coerceIn(0, 255)
                output[i] = reduced.toByte()
                suppressed++
                if (far) swept++
                liftedInk += (value - reduced).toDouble()
            }
            last.set(
                JSONObject()
                    .put("version", VERSION)
                    .put("strength", strength.toDouble())
                    .put("suppressedPixels", suppressed)
                    .put("suppressedShare", suppressed.toDouble() / ink.size)
                    .put("sweptPixels", swept)
                    .put("sweptShare", swept.toDouble() / ink.size)
                    .put("sweepRadius", sweepRadius)
                    .put("meanInkRemoved", if (suppressed == 0) 0.0 else liftedInk / suppressed)
                    .put("radius", radius)
                    .put("floor", low / 255.0)
                    .put("mid", mid / 255.0)
                    .put("high", high / 255.0)
            )
            return output
        } finally {
            owned.forEach(Mat::release)
        }
    }

    /**
     * Final-page pass. Recomputes the continuous paper field, expresses the page as ink relative to
     * it, attenuates and reconstructs the page. Returns diagnostics, or null when disabled.
     */
    fun apply(page: Mat, amount: Float): JSONObject? {
        if (!enabled() || page.empty()) return null
        val channels = page.channels()
        if (channels != 1 && channels != 3) return null
        val width = page.cols()
        val height = page.rows()
        if (width < 64 || height < 64) return null
        val strength = strength(amount)
        if (strength <= 0f) return null
        val gray = Mat()
        val field = Mat()
        try {
            when (channels) {
                1 -> page.copyTo(gray)
                else -> Imgproc.cvtColor(page, gray, Imgproc.COLOR_RGB2GRAY)
            }
            PaperField.estimate(gray, field)
            val grayBytes = ByteArray(width * height).also { gray.get(0, 0, it) }
            val fieldValues = FloatArray(width * height).also { field.get(0, 0, it) }
            val fieldBytes = ByteArray(width * height) {
                fieldValues[it].roundToInt().coerceIn(0, 255).toByte()
            }
            val ink = ByteArray(width * height)
            for (i in ink.indices) {
                val paper = max(1, fieldBytes[i].toInt() and 255)
                val value = grayBytes[i].toInt() and 255
                ink[i] = (255 - min(255, value * 255 / paper)).coerceIn(0, 255).toByte()
            }
            val reduced = attenuateInk(ink, width, height, amount)
            if (reduced === ink) return last.get()
            val source = ByteArray(width * height * channels).also { page.get(0, 0, it) }
            var changed = 0
            var removed = 0.0
            for (i in ink.indices) {
                val before = ink[i].toInt() and 255
                val after = reduced[i].toInt() and 255
                if (after >= before) continue
                val paper = max(1, fieldBytes[i].toInt() and 255)
                val value = grayBytes[i].toInt() and 255
                val target = (paper * (255 - after) / 255f).roundToInt().coerceIn(0, 255)
                // Suppression only lifts grey towards paper; never darken a channel.
                val ratio = if (value <= 0) 1f else (target.toFloat() / value).coerceIn(1f, 255f)
                for (c in 0 until channels) {
                    val original = source[i * channels + c].toInt() and 255
                    source[i * channels + c] =
                        (original * ratio).roundToInt().coerceIn(0, 255).toByte()
                }
                changed++
                removed += before - after
            }
            if (changed > 0) page.put(0, 0, source)
            val stats =
                JSONObject()
                    .put("version", VERSION)
                    .put("stage", "page")
                    .put("strength", strength.toDouble())
                    .put("changedPixels", changed)
                    .put("meanInkRemoved", if (changed == 0) 0.0 else removed / changed)
                    .put("paperFieldP50", PaperField.summary(field).optInt("p50", 0))
            last.set(stats)
            return stats
        } finally {
            gray.release()
            field.release()
        }
    }
}
