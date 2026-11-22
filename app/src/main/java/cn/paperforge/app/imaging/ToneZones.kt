package cn.paperforge.app.imaging

import kotlin.math.*
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Hysteresis tone mapping for the rendered workbook page. Values are relative to the page's own
 * continuous paper field `P`, so the decisions follow the illumination.
 *
 * A pixel-by-pixel decision cannot separate show-through from faint ink, so this works on marks:
 *
 *  1. **Candidates** are pixels below `toneZonesCandidateCut * P`: anything that is not clearly paper.
 *  2. **Ink** is grown from seeds, only through candidates and only `toneZonesReach` px. There are
 *     four kinds of seed. Dark ones lie below `toneZonesSeedCut * P`, at least `toneZonesMinSeed` px.
 *     Very dark small ones lie below `toneZonesPunctuationCut`, which keeps a colon or a comma. Crisp
 *     ones lie below `toneZonesFaintCut` with edges sharper than `toneZonesCrisp`: paper diffuses
 *     show-through, so it has soft edges, while faint pencil on the sheet itself does not. Line seeds
 *     follow the middle of a thin line near writing, which keeps the pale thin horizontals of printed type
 *     ([lineSeeds]). Ruled paper is thin and shows its other side, so line seeds stay off there, and a
 *     printed page takes a smaller full stop (`toneZonesPunctuationMinPrint`).
 *  3. **Rules** are straight thin runs of at least `toneZonesRuleRun` px that together span
 *     `toneZonesRuleLength` of the page. They stay even when faint. A wide blob is shading, so it is
 *     excluded first.
 *  4. **Local ink level:** light veiling a lit page lifts its ink, while ink in shadow keeps more
 *     contrast, so a fixed curve draws lit strokes thin and shaded ones thick. Each pixel is therefore
 *     measured between the ink level near it and the paper. This is the local normalisation of
 *     Sauvola-style binarisation, used here to set a tone rather than a threshold. Only clearly dark
 *     pixels count as ink: with a looser cut, show-through in an empty area became the darkest thing
 *     nearby and was stretched to full black. Where there is no ink nearby, the page's level stands in.
 *     Each pixel is first sharpened against a slightly blurred copy, so strokes are thin and crisp
 *     without a narrower curve, which would fade faint pencil.
 *  5. Everything kept goes through one smooth curve from `toneZonesTargetInk` at `toneZonesCurveLow`
 *     to `toneZonesTargetPaper` at `toneZonesCurveHigh`. The curve is narrow, so a stroke has a crisp
 *     edge on clean paper, and the pixels are smoothed first by `toneZonesTidy`, so its outline is smooth.
 *     Smoothing makes a thin stroke paler, which alone would break small print, and sharpening restores
 *     it: a line loses less to the blur than a dot does, so lines come back and dust does not. The smoothing
 *     is measured in the photo's pixels ([withSourceScale]), as the noise it removes comes from them. A mark
 *     with no dark core keeps its own wider curve from `toneZonesCurveLowFaint` to `toneZonesCurveHighFaint`.
 *     Inside a mark with a dark core, the middle of a thin line (darker than the paper on both sides, at
 *     most `toneZonesRidgeCeiling` of it) is drawn as dark as it stands out, so a pen stroke's pale stretch
 *     stays one thin line instead of dashes, and thin print does not break. Everything else becomes paper.
 *  6. **Rules** are tracked across the page and redrawn as one continuous thin line of one tone
 *     (`toneZonesRuleTone`, `toneZonesRuleWidth` per 1000 px of width), under the writing, so a rule looks
 *     the same in shadow and in light and has no gaps. A rule too faint to be sure of keeps its own tone.
 *     Its height comes from a local straight fit, and an end that fades early continues in the direction
 *     the other rules take there.
 *
 * Marks a crop leaves along the frame are dropped too: see [dropEdgeDebris]. On ruled paper, dots of
 * show-through that the curve drew black are cleared from the finished page: see [dropDrawnSpecks].
 *
 * Settings were tuned on the owner's full-size phone photos against the reference scanner's PDF of the same photos
 * (2026-11-07): growth reaches `toneZonesReach` 2 from a seed, since farther it took in a 12 MP photo's grain
 * as grey hairs along every stroke; a printed full stop needs `toneZonesPunctuationMin` 2.
 *
 * Known costs: a mark lighter than `toneZonesFaintSure` all through and shorter than `toneZonesFaintSpan`
 * is treated as show-through, even if it is very faint pencil. A pale part of a mark with a dark core,
 * lighter than about .55 of the paper and not a thin line, is drawn as paper. Short or isolated pale pencil
 * can go with show-through: see [dropGhostMarks]. Pencil that is kept is drawn pale, where the reference scanner
 * removes most pencil. A long straight line along the frame within `toneZonesEdgeLineStrip` (on the top
 * sheet of a stack, `toneZonesStackLineStrip`) goes, as the edge of a sheet below; a printed border that
 * close to the edge goes with it.
 *
 * A kept mark is shifted by one luma delta on all channels, so it keeps its hue; everything else
 * becomes neutral paper. Radii are for a 1375 px long edge and scale with the page. Nothing here
 * changes geometry, text or the stored source.
 */
internal object ToneZones {
    const val VERSION = "tone-zones-29"
    private const val REFERENCE_EDGE = 1375f
    private val last = ThreadLocal<JSONObject>()
    private val exactFrame = ThreadLocal<Boolean>()

    /**
     * Inside [block], the page's frame is known to be the sheet's edge. Nothing beyond the sheet is left
     * to clean up, so the wide bands that catch a rough crop's debris would only cut real writing.
     */
    inline fun <T> withExactFrame(exact: Boolean, block: () -> T): T {
        setExactFrame(exact)
        try {
            return block()
        } finally {
            setExactFrame(false)
        }
    }

    @PublishedApi
    internal fun setExactFrame(exact: Boolean) = exactFrame.set(exact)

    private fun frameIsExact() = exactFrame.get() == true

    private val stackFrame = ThreadLocal<Boolean>()

    /** Inside [block], the frame came from [SheetEdge]: the page was the top sheet of a stack. */
    inline fun <T> withStackFrame(stack: Boolean, block: () -> T): T {
        setStackFrame(stack)
        try {
            return block()
        } finally {
            setStackFrame(false)
        }
    }

    @PublishedApi
    internal fun setStackFrame(stack: Boolean) = stackFrame.set(stack)

    private fun frameIsStack() = stackFrame.get() == true

    private val sourceScale = ThreadLocal<Float>()

    /**
     * Inside [block], one pixel of the photo covers [scale] pixels of the page, as when a small photo was
     * enlarged. Pixel noise comes from the photo's pixels, so the smoothing that removes it is measured in
     * them: measured in page pixels, it was 2.4 times too strong on a photo used at its own size and broke
     * thin print apart.
     */
    inline fun <T> withSourceScale(scale: Float, block: () -> T): T {
        setSourceScale(scale)
        try {
            return block()
        } finally {
            setSourceScale(1f)
        }
    }

    @PublishedApi
    internal fun setSourceScale(scale: Float) = sourceScale.set(scale)

    private fun photoPixel() = (sourceScale.get() ?: 1f).coerceIn(1f, 4f)

    fun diagnostics() = last.get()

    fun enabled() = ScanConfig.int("toneZones", 1) != 0

    /**
     * Maps [page] in place. [field] is the whole-page paper field already measured by the caller
     * (CV_32FC1 at page size); when absent it is estimated here from [page]. Nothing in [exterior],
     * a mask of what is not the sheet, is kept.
     */
    fun apply(page: Mat, field: Mat? = null, amount: Float = 1f, exterior: Mat? = null): JSONObject? {
        if (page.empty()) { last.remove(); return null }
        if (!enabled()) { last.remove(); return null }
        val channels = page.channels()
        if (channels != 1 && channels != 3) return null
        val width = page.cols()
        val height = page.rows()
        if (width < 64 || height < 64) { last.remove(); return null }
        val strength =
            amount.coerceIn(0f, 1f) * ScanConfig.float("toneZonesStrength", 1f).coerceIn(0f, 1f)
        if (strength <= 0f) { last.remove(); return null }
        val started = System.nanoTime()
        var lapAt = started
        fun lap(name: String) {
            val now = System.nanoTime()
            StepTimes.add("tz.$name", now - lapAt)
            lapAt = now
        }
        val scale = max(width, height) / REFERENCE_EDGE
        val seedCut = ScanConfig.float("toneZonesSeedCut", .70f).coerceIn(.2f, .9f)
        val candidateCut =
            ScanConfig.float("toneZonesCandidateCut", .90f).coerceIn(seedCut + .02f, .995f)
        val reach = (ScanConfig.int("toneZonesReach", 2) * scale).roundToInt().coerceIn(1, 64)
        val minSeed = (ScanConfig.int("toneZonesMinSeed", 8) * scale * scale).roundToInt()
        val ruleLength = ScanConfig.float("toneZonesRuleLength", .25f).coerceIn(.02f, 1f)
        val ruleRun = (ScanConfig.int("toneZonesRuleRun", 45) * scale).roundToInt().coerceIn(5, 400)
        val thin = (ScanConfig.int("toneZonesRuleThickness", 9) * scale).roundToInt().coerceIn(3, 63)
        val curveLow = ScanConfig.float("toneZonesCurveLow", .18f).coerceIn(.05f, .8f)
        val curveHigh = ScanConfig.float("toneZonesCurveHigh", .60f).coerceIn(curveLow + .05f, 1f)
        var targetInk = ScanConfig.int("toneZonesTargetInk", 10).coerceIn(0, 120)
        val targetPaper = ScanConfig.int("toneZonesTargetPaper", 255).coerceIn(230, 255)
        val paperFloor = ScanConfig.int("toneZonesPaperFloor", 150).coerceIn(60, 240)
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        try {
            val gray = mat()
            when (channels) {
                1 -> page.copyTo(gray)
                else -> Imgproc.cvtColor(page, gray, Imgproc.COLOR_RGB2GRAY)
            }
            val supplied =
                field != null &&
                    !field.empty() &&
                    field.type() == CvType.CV_32FC1 &&
                    field.cols() == width &&
                    field.rows() == height
            val paper = if (supplied) field!! else PaperField.estimate(gray)
            val fieldValues = FloatArray(width * height).also { paper.get(0, 0, it) }
            // Dark-capture interlock: a page whose paper is itself far below white is not a
            // rendered workbook page and must not be remapped to paper white.
            val fieldHistogram = IntArray(256)
            for (part in ParallelFilter.ranges(fieldValues.size) { from, to ->
                IntArray(256).also { counts -> for (i in from until to) counts[fieldValues[i].roundToInt().coerceIn(0, 255)]++ }
            }) for (v in 0 until 256) fieldHistogram[v] += part[v]
            // The brighter part decides: half a page in deep shadow is still a page with white paper.
            val fieldPaper = percentile(fieldHistogram, fieldValues.size, .75f)
            val stats =
                JSONObject()
                    .put("version", VERSION)
                    .put("paperFieldP50", fieldPaper)
                    .put("seedCut", seedCut.toDouble())
                    .put("candidateCut", candidateCut.toDouble())
                    .put("reach", reach)
                    .put("minSeed", minSeed)
                    .put("ruleLength", ruleLength.toDouble())
                    .put("curveLow", curveLow.toDouble())
                    .put("curveHigh", curveHigh.toDouble())
                    .put("targetInk", targetInk)
                    .put("targetPaper", targetPaper)
                    .put("fieldSupplied", supplied)
                    .put("strength", strength.toDouble())
            if (fieldPaper < paperFloor) {
                last.set(
                    stats
                        .put("applied", false)
                        .put("reason", "paper-below-floor")
                        .put("elapsedMs", elapsed(started))
                )
                return last.get()
            }
            val grayBytes = ByteArray(width * height).also { gray.get(0, 0, it) }
            val raw = FloatArray(grayBytes.size)
            ParallelFilter.ranges(raw.size) { from, to ->
                for (i in from until to) raw[i] = (grayBytes[i].toInt() and 255) / max(1f, fieldValues[i])
            }
            val ratio = mat()
            ratio.create(height, width, CvType.CV_32FC1)
            ratio.put(0, 0, raw)
            // Sharpening raw pixels makes stroke edges jagged, so pixel noise is smoothed away first.
            val tidied = mat()
            ParallelFilter.gaussian(ratio, tidied, max(.4, ScanConfig.float("toneZonesTidy", .7f) * photoPixel().toDouble()))
            val tidyValues = FloatArray(raw.size).also { tidied.get(0, 0, it) }
            // What is ink is decided on the same smoothed pixels: on raw ones a faint line broke into short
            // pale pieces, which then went as show-through and left the stroke dashed.
            val relative = if (ScanConfig.int("toneZonesDecideTidy", 0) != 0) tidyValues else raw
            lap("ratio")
            // 255 where `relative` < cut, compared in OpenCV on the float32 Mat that holds the same values.
            val relativeMat = if (relative === raw) ratio else tidied
            fun threshold(cut: Float) =
                mat().also { mask -> Core.compare(relativeMat, Scalar(cut.toDouble()), mask, Core.CMP_LT) }
            val candidates = threshold(candidateCut)
            val cross =
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).also(owned::add)
            val ruleCandidates = threshold(ScanConfig.float("toneZonesRuleCandidate", .88f))
            val rules = thinLongComponents(ruleCandidates, thin, ruleRun, ruleLength, cross, owned)
            // Only long straight runs reach here, so this can sit just under the candidate cut: a ghost
            // beside a rule stays out, while a real faint rule of ruled paper stays in.
            val faintRule = threshold(ScanConfig.float("toneZonesRuleCut", .87f).coerceAtMost(candidateCut))
            Core.bitwise_and(rules, faintRule, rules)
            // Ruled paper is thin: the writing on its other side shows through, as lines and as dots pressed into
            // the paper's pits. Several steps below are stricter there and looser on printed pages.
            val ruledShare = Core.countNonZero(rules).toDouble() / (width.toDouble() * height)
            // Ruled paper has many long rules at an even spacing. A technical drawing has long straight lines too,
            // but few and unevenly spaced: treated as ruled, its faint construction lines were cleared as rules
            // and redrawn in their own faint tone, so they vanished. On the owner's pages the notebooks had 17-23
            // evenly spaced long rules, the drawing, book and exam pages at most one.
            val ruled = ruledShare >= ScanConfig.float("toneZonesRuledShare", .01f) &&
                evenRules(ruleTracks(relative, rules, null, width, height, scale), width, scale)
            stats.put("ruledShare", ruledShare)
            // Print is drawn a little lighter than pen: the reference scanner's printed strokes were a dark grey with soft
            // edges, where ours were black and looked binarised. `toneZonesTargetInkPrint` applies off ruled paper.
            if (!ruled) targetInk = ScanConfig.int("toneZonesTargetInkPrint", 35).coerceIn(0, 120)
            lap("rules")
            val seeds = threshold(seedCut)
            if (minSeed > 1) dropSmall(seeds, minSeed, owned)
            val punctuation = threshold(ScanConfig.float("toneZonesPunctuationCut", .64f))
            // A real dot is several pixels across at any size; two dark pixels are noise, and were drawn as dust.
            // A printed full stop in a soft photo of an exam paper had six pixels below the cut, where the minimum
            // was eight: on unruled paper, where pits show through less, `toneZonesPunctuationMinPrint` applies.
            val punctuationMin =
                if (ruled) ScanConfig.float("toneZonesPunctuationMin", 2f)
                else ScanConfig.float("toneZonesPunctuationMinPrint", 1f)
            dropSmall(punctuation, max(2, (punctuationMin * scale * scale).roundToInt()), owned)
            Core.bitwise_or(seeds, punctuation, seeds)
            lap("seeds")
            // Crispness: how far a raw pixel falls below a slightly blurred copy of the ratio.
            val softened = mat()
            ParallelFilter.gaussian(ratio, softened, max(.8, 1.4 * scale.toDouble()))
            val softValues = FloatArray(relative.size).also { softened.get(0, 0, it) }
            val faintCut = ScanConfig.float("toneZonesFaintCut", .88f)
            val crispness = ScanConfig.float("toneZonesCrisp", .08f)
            // raw < faintCut and soft - raw > crispness, on the float32 Mats (the same IEEE subtraction).
            val crisp = mat()
            val sharpness = mat()
            Core.subtract(softened, ratio, sharpness)
            Core.compare(sharpness, Scalar(crispness.toDouble()), crisp, Core.CMP_GT)
            val belowFaint = mat()
            Core.compare(ratio, Scalar(faintCut.toDouble()), belowFaint, Core.CMP_LT)
            Core.bitwise_and(crisp, belowFaint, crisp)
            dropSmall(crisp, max(2, (6 * scale * scale).roundToInt()), owned)
            lap("crisp")
            val darkSeeds = mat().also(seeds::copyTo)
            Core.bitwise_or(seeds, crisp, seeds)
            // In a soft photo the thin horizontals of Song type are paler than any seed cut and too blurred to be
            // crisp, so the 一 of 插入一个 and the bars of 中 were never seeded and came out as gaps. See [lineSeeds].
            // Ruled paper is thin, and the writing on its other side shows through as lines beside the front's
            // writing: measured against the reference scanner, line seeds there added ten wrong marks for every right one.
            val baseLines =
                if (ruled && ScanConfig.int("toneZonesLineSeedOnRuled", 0) == 0) null
                else lineSeeds(tidyValues, darkSeeds, rules, width, height, scale, owned, relative)
            // Small print on ruled pages: a printed line's marks are seeded lines too, drawn the same way.
            val textLine = if (ruled) textLineSeeds(candidates, rules, seeds, cross, tidyValues, width, height, scale, owned) else null
            textLine?.let { stats.put("textLinePixels", Core.countNonZero(it.mask)) }
            val lineSeed = when {
                textLine == null -> baseLines
                baseLines == null -> textLine
                else -> {
                    Core.bitwise_or(baseLines.mask, textLine.mask, baseLines.mask)
                    for (i in baseLines.bytes.indices) {
                        if (textLine.bytes[i] != 0.toByte()) baseLines.bytes[i] = textLine.bytes[i]
                        if ((textLine.depth[i].toInt() and 255) > (baseLines.depth[i].toInt() and 255)) baseLines.depth[i] = textLine.depth[i]
                    }
                    baseLines
                }
            }
            if (lineSeed != null) {
                Core.bitwise_or(seeds, lineSeed.mask, seeds)
                stats.put("lineSeedPixels", Core.countNonZero(lineSeed.mask))
            }
            lap("lineSeeds")
            val grown = mat().also(seeds::copyTo)
            repeat(reach) {
                Imgproc.dilate(grown, grown, cross)
                Core.bitwise_and(grown, candidates, grown)
            }
            // A line's middle stays even where one pixel of it is lighter than the candidate cut.
            if (lineSeed != null) Core.bitwise_or(grown, lineSeed.mask, grown)
            lap("grow")
            val faintMarks = dropGhostMarks(grown, rules, cross, scale, relative, tidyValues, lineSeed?.bytes, owned)
            lap("ghosts")
            val keep = mat()
            Core.bitwise_or(grown, rules, keep)
            if (ScanConfig.int("toneZonesEdgeDebris", 1) != 0)
                dropEdgeDebris(page, keep, thin, owned, relative)
            if (exterior != null) Core.subtract(keep, exterior, keep)
            lap("edgeDebris")
            val finger = if (channels == 3) fingerZone(page, thin, darkSeeds, minSeed, owned) else null
            if (finger != null) {
                Core.subtract(keep, finger, keep)
                dropFingerCrumbs(keep, finger, thin, owned)
            }
            lap("finger")
            val inkFloor = inkFloor(relative, width, height, scale, owned)
            lap("inkFloor")
            val ruleLevel = ruleLevel(relative, rules, width, height, scale, owned)
            lap("ruleLevel")
            // A dark long line is pen, which keeps its own weight. Only printed lines are redrawn.
            // Only ruled paper has rules to redraw; on any other page long lines are drawn as the ink they are.
            val trackedAcross = if (!ruled) emptyList() else ruleTracks(relative, rules, finger, width, height, scale)
                .filter { it.level >= ScanConfig.float("toneZonesRuleDarkest", .25f) || it.end - it.start >= .8f * width }
            lap("ruleTracks")
            val down = if (!ruled) emptyList() else verticalTracks(relative, rules, finger, width, height, scale)
                .filter { it.level >= ScanConfig.float("toneZonesRuleDarkestDown", .5f) }
            lap("verticalTracks")
            val exactFrame = frameIsExact()
            val stripAcross = height * (if (exactFrame) ScanConfig.float("toneZonesExactRuleStripY", .012f) else ScanConfig.float("toneZonesEdgeLineStrip", .025f))
            val stripDown = width * (if (exactFrame) ScanConfig.float("toneZonesExactLineStrip", .012f) else ScanConfig.float("toneZonesEdgeLineStrip", .025f))
            fun nearTopOrBottom(track: RuleTrack) = track.heights[track.heights.size / 2].let { it <= stripAcross || it >= height - stripAcross }
            // With an exact frame, a line this close to it is the sheet's own edge. Otherwise it may be a
            // table's border, so it is left to the older path and not cleared.
            val acrossTracked = if (exactFrame) trackedAcross else trackedAcross.filterNot(::nearTopOrBottom)
            val drawnAcrossTracked = acrossTracked.filterNot(::nearTopOrBottom)
            // A faint long vertical is a crease or the edge of a sheet, not a printed line.
            val faintDown = ScanConfig.float("toneZonesRuleFaintDown", .80f)
            val drawnDownTracked = down.filter { track -> track.level < faintDown && track.heights[track.heights.size / 2].let { it > stripDown && it < width - stripDown } }
            // A rule is cleared and redrawn only where this photo shows it. A track is followed across the page and
            // carried on to where its neighbours end, so on a grid printed beside drawings, or one that stops short of
            // the sheet's edge, it ran on through blank paper and through the drawing, and the redraw put a printed
            // line there that the page never had, after the band had wiped what lay under it. Along each track the
            // page must be darker than paper by `toneZonesRuleEvidenceShare` of the rule's own contrast, within a
            // pixel of the line; gaps up to `toneZonesRuleEvidenceGap` (a speck, a highlight) are bridged, longer ones
            // split the track, and pieces shorter than `toneZonesRuleEvidenceRun` are left to the ink path as they are.
            val evidenceShare = ScanConfig.float("toneZonesRuleEvidenceShare", .55f).coerceIn(.05f, 1f)
            val evidenceGap = max(2, (ScanConfig.float("toneZonesRuleEvidenceGap", 6f) * scale).roundToInt())
            val evidenceRun = max(4, (ScanConfig.float("toneZonesRuleEvidenceRun", 8f) * scale).roundToInt())
            // A rule that fades (a shadowed margin, a highlight) is still faintly there. Within
            // `toneZonesRuleWeakReach` of the frame or across an inner gap of that length it is kept when most of
            // the stretch is at least `toneZonesRuleWeakShare` of the rule's contrast darker than paper. With no
            // trace at all, nothing is drawn: a grid that stops short of the margin stops there.
            val weakShare = ScanConfig.float("toneZonesRuleWeakShare", .2f).coerceIn(.02f, 1f)
            val weakReach = ScanConfig.float("toneZonesRuleWeakReach", .04f).coerceIn(0f, .2f)
            val weakFill = ScanConfig.float("toneZonesRuleWeakFill", .6f).coerceIn(0f, 1f)
            val ruleMask = ByteArray(relative.size).also { rules.get(0, 0, it) }
            val fingerMask = finger?.let { zone -> ByteArray(relative.size).also { zone.get(0, 0, it) } }
            // Skin or a desk lying over the sheet, judged as dropEdgeDebris judges it, when no finger zone was found.
            val warmSaturation = ScanConfig.float("toneZonesWarmSaturation", .45f)
            val colourBytes by lazy { if (channels == 3) ByteArray(relative.size * 3).also { page.get(0, 0, it) } else null }
            fun occluded(at: Int): Boolean {
                if (fingerMask != null && fingerMask[at] != 0.toByte()) return true
                val c = colourBytes ?: return false
                val r = c[3 * at].toInt() and 255
                val g = c[3 * at + 1].toInt() and 255
                val b = c[3 * at + 2].toInt() and 255
                return r >= 70 && r >= g && g > b && (r - b) >= warmSaturation * r
            }
            val evidenceBand = max(2, (2 * scale).roundToInt() + 1)
            fun supported(track: RuleTrack, vertical: Boolean): List<RuleTrack> {
                if (ScanConfig.int("toneZonesRuleEvidence", 1) == 0) return listOf(track)
                val limit = if (vertical) width else height
                val alongLimit = if (vertical) height else width
                val cut = track.level + (1f - track.level) * evidenceShare
                val weakCut = track.level + (1f - track.level) * (1f - weakShare)
                val n = track.heights.size
                // A rule is a line: paper lies just beside it, on one side at least. Beside the sheet's edge a dark
                // band (the desk, the sheet's shadow, the next page) is as dark as a rule all across, so every track
                // carried to the frame found "evidence" there and the redraw put a dash on each rule row of a grid,
                // on the dark strip beyond its margin (worksheet 43 of 2026-11-16). Where the page on both sides,
                // `toneZonesRuleEvidenceFlank` photo pixels beyond the band, is itself as dark as the rule must be, no
                // line is seen. A pale rule in a shadow keeps lighter paper beside it.
                val flank = evidenceBand + max(2, (ScanConfig.float("toneZonesRuleEvidenceFlank", 2f) * scale).roundToInt())
                val flankCheck = ScanConfig.float("toneZonesRuleEvidenceFlank", 2f) > 0f
                fun valueAt(along: Int, c: Int) = relative[if (vertical) along * width + c else c * width + along]
                fun besidePaper(along: Int, c: Int): Boolean {
                    if (!flankCheck) return true
                    val lighter = max(
                        if (c - flank in 0 until limit) valueAt(along, c - flank) else 0f,
                        if (c + flank in 0 until limit) valueAt(along, c + flank) else 0f,
                    )
                    return lighter > cut
                }
                val darkest = FloatArray(n) { i ->
                    val along = track.start + i
                    val centre = track.heights[i].roundToInt()
                    var value = Float.MAX_VALUE
                    for (c in centre - 1..centre + 1) {
                        if (c !in 0 until limit) continue
                        value = min(value, valueAt(along, c))
                    }
                    if (value > cut || besidePaper(along, centre)) value else 1f
                }
                // The track is a smoothed fit, and a slightly curved rule runs a few pixels off it. The rule mask (long
                // straight thin runs only, never a drawing's slanted strokes or blank paper) within the band that is
                // cleared counts as seeing the rule too.
                val seen = BooleanArray(n) { i ->
                    if (darkest[i] <= cut) return@BooleanArray true
                    val along = track.start + i
                    val centre = track.heights[i].roundToInt()
                    // The dark band beside a sheet is long and thin too, and the rule mask holds it: only a rule pixel
                    // with paper beside it, across this track, counts.
                    (centre - evidenceBand..centre + evidenceBand).any { c ->
                        c in 0 until limit && ruleMask[if (vertical) along * width + c else c * width + along] != 0.toByte() &&
                            besidePaper(along, c)
                    }
                }
                fun faint(from: Int, to: Int) = to > from && (from until to).count { darkest[it] <= weakCut } >= weakFill * (to - from)
                // A finger lying on the sheet hides the rule; the rule is seen on its far side, so it goes on beneath it.
                fun hidden(from: Int, to: Int): Boolean {
                    if (to <= from || ScanConfig.int("toneZonesRuleUnderOccluder", 1) == 0) return false
                    val covered = (from until to).count { k ->
                        val c = track.heights[k].roundToInt().coerceIn(0, limit - 1)
                        occluded(if (vertical) (track.start + k) * width + c else c * width + track.start + k)
                    }
                    return covered >= weakFill * (to - from)
                }
                val reach = (weakReach * alongLimit).roundToInt()
                // Bridge gaps between supported stretches only: short ones always, longer faint or hidden ones on their trace.
                var i = 0
                while (i < n) {
                    if (seen[i]) { i++; continue }
                    var j = i
                    while (j < n && !seen[j]) j++
                    if (i > 0 && j < n && (j - i <= evidenceGap || (j - i <= reach && faint(i, j)) || hidden(i, j))) for (k in i until j) seen[k] = true
                    i = j
                }
                // On to the frame, where the track was carried there and the rule still shows faintly on the way, or a
                // finger covers it.
                val first = seen.indexOfFirst { it }
                if (first > 0 && track.start == 0 && ((first <= reach && faint(0, first)) || hidden(0, first))) for (k in 0 until first) seen[k] = true
                val last = seen.indexOfLast { it }
                if (last in 0 until n - 1 && track.start + n >= alongLimit && ((n - 1 - last <= reach && faint(last + 1, n)) || hidden(last + 1, n)))
                    for (k in last + 1 until n) seen[k] = true
                val pieces = ArrayList<RuleTrack>()
                i = 0
                while (i < n) {
                    if (!seen[i]) { i++; continue }
                    var j = i
                    while (j < n && seen[j]) j++
                    if (j - i >= evidenceRun)
                        pieces += RuleTrack(track.start + i, track.start + j, track.heights.copyOfRange(i, j), track.level)
                    i = j
                }
                return pieces
            }
            val across = acrossTracked.flatMap { supported(it, false) }
            val drawnAcross = drawnAcrossTracked.flatMap { supported(it, false) }
            val drawnDown = drawnDownTracked.flatMap { supported(it, true) }
            // The old line goes, except where something darker crosses it: that is writing.
            val ruleBand = BooleanArray(relative.size)
            val bandHalf = max(2, (2 * scale).roundToInt() + 1)
            // A pen stroke that crosses a rule, or ends on it, is often paler than the rule: the band around
            // the rule was cleared and redrawn as one thin line, so every pale stroke broke a few pixels
            // above and below each rule it met. Where a stroke runs into the band from outside it, its ink
            // inside the band, up to the rule's own thin line, is drawn as ink. The stroke must reach well
            // away from the band and must not run along beside it: a rule's own blur, a second line beside
            // it, or a shadow does neither, and taking those left dark dashes along the rule. A rule whose
            // ink joins on most of its length is left as before. Rules across the page only.
            val crossing = BooleanArray(relative.size)
            val crossingOn = ruled && ScanConfig.int("toneZonesRuleCrossing", 1) != 0
            val crossingShare = ScanConfig.float("toneZonesRuleCrossingShare", .3f)
            val crossingCore = (max(ScanConfig.float("toneZonesRuleWidthMin", 1.1f), ScanConfig.float("toneZonesRuleWidth", 1.5f) * width / 1000f) / 2 + .5f).toInt()
            val crossingFar = max(4, (2 * scale).roundToInt() + 2)
            val crossingRun = max(8, (4 * scale).roundToInt())
            val inkBytes =
                if (!crossingOn) null
                else {
                    val stroke = mat()
                    Core.bitwise_and(grown, keep, stroke)
                    ByteArray(relative.size).also { stroke.get(0, 0, it) }
                }
            var crossingPixels = 0
            fun clear(track: RuleTrack, vertical: Boolean) {
                val cut = track.level - .12f
                val limit = if (vertical) width else height
                val alongLimit = if (vertical) height else width
                fun index(along: Int, c: Int) = if (vertical) along * width + c else c * width + along
                fun inked(along: Int, c: Int) =
                    along in 0 until alongLimit && c in 0 until limit && inkBytes!![index(along, c)] != 0.toByte()
                // Ink just outside the band at [edge] on the side [dir], that goes on away from the band and
                // does not run along beside it.
                fun stroke(along: Int, edge: Int, dir: Int): Boolean {
                    if ((along - 1..along + 1).none { inked(it, edge + dir) || inked(it, edge + 2 * dir) }) return false
                    if ((along - crossingFar..along + crossingFar).none { a -> (crossingFar..crossingFar + 2).any { inked(a, edge + it * dir) } }) return false
                    return (along - crossingRun..along + crossingRun).count { inked(it, edge + 2 * dir) } <= crossingRun * 3 / 2
                }
                if (inkBytes != null && !vertical) {
                    val marked = ArrayList<Int>()
                    var columns = 0
                    for (i in track.heights.indices) {
                        val along = track.start + i
                        val centre = track.heights[i].roundToInt()
                        val lo = max(0, centre - bandHalf)
                        val hi = min(limit - 1, centre + bandHalf)
                        val before = stroke(along, lo, -1)
                        val after = stroke(along, hi, 1)
                        if (!before && !after) continue
                        columns++
                        if (before) { var c = lo; while (c < centre - crossingCore && inked(along, c)) marked += index(along, c++) }
                        if (after) { var c = hi; while (c > centre + crossingCore && inked(along, c)) marked += index(along, c--) }
                    }
                    if (columns <= crossingShare * track.heights.size) {
                        for (at in marked) if (!crossing[at]) { crossing[at] = true; crossingPixels++ }
                    }
                }
                for (i in track.heights.indices) {
                    val along = track.start + i
                    val centre = track.heights[i].roundToInt()
                    for (c in max(0, centre - bandHalf)..min(limit - 1, centre + bandHalf)) {
                        val at = index(along, c)
                        if (relative[at] >= cut && !crossing[at]) ruleBand[at] = true
                    }
                }
            }
            across.forEach { clear(it, false) }
            down.flatMap { supported(it, true) }.forEach { clear(it, true) }
            lap("clearBands")
            val ruleTone = ScanConfig.int("toneZonesRuleTone", 50).toFloat()
            val ruleSureLevel = ScanConfig.float("toneZonesRuleSure", .82f)
            val ruleFaintLevel = ScanConfig.float("toneZonesRuleFaint", .90f)
            val ruleEdge = ScanConfig.float("toneZonesRuleEdge", .72f)
            // A soft weight so the kept region has no hard outline against the paper.
            val weightMask = mat()
            Imgproc.dilate(keep, weightMask, cross)
            val weightFloat = mat()
            weightMask.convertTo(weightFloat, CvType.CV_32F, 1.0 / 255)
            ParallelFilter.gaussian(weightFloat, weightFloat, 1.0)
            val weights = FloatArray(relative.size).also { weightFloat.get(0, 0, it) }
            val grownBytes = ByteArray(relative.size).also { grown.get(0, 0, it) }
            val ruleBytes = ByteArray(relative.size).also { rules.get(0, 0, it) }
            val output = ByteArray(relative.size)
            lap("weights")
            val range = curveHigh - curveLow
            // Faint marks keep their own curve: a narrower main curve darkens the middle tones, which would
            // draw whatever show-through survived darker.
            val curveLowFaint = ScanConfig.float("toneZonesCurveLowFaint", .14f)
            val rangeFaint = max(.05f, ScanConfig.float("toneZonesCurveHighFaint", .86f) - curveLowFaint)
            val sharpen = ScanConfig.float("toneZonesSharpen", 2f).coerceIn(0f, 4f)
            // Sharpening a mark with no dark core only darkens it, and most such marks are show-through.
            val sharpenFaint = ScanConfig.float("toneZonesSharpenFaint", .4f).coerceIn(0f, 4f)
            // The narrow curve draws anything paler than about half way to ink as paper, and that cut a pen
            // stroke's light stretches out of it: the stem of an "f" came out as dashes where the reference scanner drew a
            // thin whole line. A pixel at the middle of a thin line is darker than the paper on both sides of
            // it, while one on a blurred edge is darker on one side only. So inside a mark with a dark core, a
            // line's middle is drawn as dark as it stands out, which keeps the line whole and thin.
            val ridgeOn = ScanConfig.int("toneZonesRidge", 1) != 0
            val ridgeDepth = ScanConfig.float("toneZonesRidgeDepth", .15f).coerceIn(.02f, 1f)
            // On a full-size photo, grain along a stroke's edge forms tiny ridges too, and drew grey hairs
            // around every letter. A real faint stroke is darker than paper grain and stands out more.
            val ridgeCeiling = ScanConfig.float("toneZonesRidgeCeiling", .80f)
            val ridgeFloor = ScanConfig.float("toneZonesRidgeFloor", 0f).coerceIn(0f, .5f)
            val ridgeStep = max(2, (ScanConfig.float("toneZonesRidgeStep", 1.5f) * photoPixel()).roundToInt())
            val ridgeOffsets = intArrayOf(ridgeStep, ridgeStep * width, ridgeStep * (width + 1), ridgeStep * (width - 1))
            fun ridge(i: Int): Float {
                val x = i % width
                val y = i / width
                if (x < ridgeStep || y < ridgeStep || x >= width - ridgeStep || y >= height - ridgeStep) return targetPaper.toFloat()
                val centre = tidyValues[i]
                if (centre > ridgeCeiling) return targetPaper.toFloat()
                var depth = 0f
                for (o in ridgeOffsets) depth = max(depth, min(tidyValues[i - o], tidyValues[i + o]) - centre)
                return targetPaper + (targetInk - targetPaper) * smoothStep((depth - ridgeFloor) / ridgeDepth)
            }
            // Grain makes ridges a few pixels long; a stroke's middle runs on, as does a thin horizontal of
            // printed Song type, which the brightness cap alone cut. With `toneZonesRidgeLength` set, only
            // ridge paths at least that long count, so the cap can be loose.
            val ridgeLength = ScanConfig.float("toneZonesRidgeLength", 0f) * scale
            val ridgePath: BooleanArray? =
                if (!ridgeOn || ridgeLength <= 0f) null
                else {
                    val ridgeMin = ScanConfig.float("toneZonesRidgeMin", .03f)
                    val pathBytes = ByteArray(relative.size)
                    for (i in relative.indices) {
                        if (grownBytes[i] == 0.toByte() || faintMarks[i]) continue
                        val x = i % width
                        val y = i / width
                        if (x < ridgeStep || y < ridgeStep || x >= width - ridgeStep || y >= height - ridgeStep) continue
                        val centre = tidyValues[i]
                        if (centre > ridgeCeiling) continue
                        var depth = 0f
                        for (o in ridgeOffsets) depth = max(depth, min(tidyValues[i - o], tidyValues[i + o]) - centre)
                        if (depth > ridgeMin) pathBytes[i] = 255.toByte()
                    }
                    val pathMat = mat()
                    pathMat.create(height, width, CvType.CV_8UC1)
                    pathMat.put(0, 0, pathBytes)
                    val labels = mat()
                    val stats = mat()
                    val centroids = mat()
                    val count = Imgproc.connectedComponentsWithStats(pathMat, labels, stats, centroids, 8, CvType.CV_32S)
                    val table = statsTable(stats, count)
                    val long = BooleanArray(count) { it != 0 && max(table[it * 5 + Imgproc.CC_STAT_WIDTH], table[it * 5 + Imgproc.CC_STAT_HEIGHT]) >= ridgeLength }
                    val labelValues = IntArray(relative.size).also { labels.get(0, 0, it) }
                    BooleanArray(relative.size) { long[labelValues[it]] }
                }
            // A seeded line's middle is drawn as dark as it stands out, full ink from `toneZonesLineSeedFull`:
            // the reference scanner draws a soft thin horizontal as solid as the stroke beside it.
            val lineDepth = lineSeed?.depth
            val lineFull = ScanConfig.float("toneZonesLineSeedFull", .10f).coerceIn(.02f, 1f)
            // Every pixel is its own: the parts run on the filter threads and the page is the same to the bit.
            val parts = ParallelFilter.ranges(relative.size) { from, to ->
                var inkShare = 0
                var ruleShare = 0
                var lifted = 0.0
                for (i in from until to) {
                    val value = grayBytes[i].toInt() and 255
                    val r = relative[i]
                    val floor = inkFloor[i]
                    // Sharpened for tone only, never for deciding what is kept: an edge pixel lies above its
                    // surroundings and gets lighter, a stroke's middle and a faint line get darker.
                    val sharp = tidyValues[i] + (if (faintMarks[i]) sharpenFaint else sharpen) * (tidyValues[i] - softValues[i])
                    val normal = ((sharp - floor) / max(1f - floor, .2f)).coerceIn(0f, 1f)
                    val curve = targetInk + (targetPaper - targetInk) * smoothStep(if (faintMarks[i]) (normal - curveLowFaint) / rangeFaint else (normal - curveLow) / range)
                    if (ruleBand[i]) {
                        output[i] = (value + (targetPaper - value) * strength).roundToInt().coerceIn(0, 255).toByte()
                        lifted += abs(targetPaper - value).toDouble() * strength
                        ruleShare++
                        continue
                    }
                    val onRule = ruled && ruleBytes[i] != 0.toByte() && !crossing[i]
                    // A pixel much darker than its rule is writing that crosses it, so the curve draws it.
                    val isRule = onRule && r >= ruleLevel[i] - .12f
                    val lineTone =
                        if (lineDepth == null || isRule || lineDepth[i] == 0.toByte()) targetPaper.toFloat()
                        else targetPaper + (targetInk - targetPaper) * smoothStep((lineDepth[i].toInt() and 255) / 255f / lineFull)
                    val inked =
                        min(lineTone, if (isRule) {
                            val level = ruleLevel[i]
                            // Only the core of the line reaches full tone, which keeps a rule thin.
                            val depth = smoothStep(((1f - sharp) / max(1f - level, .05f) - ruleEdge) / (1f - ruleEdge))
                            val sure = targetPaper + (ruleTone - targetPaper) * depth
                            // Faint rules fade toward their own tone: they may be the other side of the sheet.
                            sure + (r * targetPaper - sure) * smoothStep((level - ruleSureLevel) / (ruleFaintLevel - ruleSureLevel))
                        } else if (ridgeOn && !faintMarks[i] && grownBytes[i] != 0.toByte() && (ridgePath == null || ridgePath[i])) min(curve, ridge(i)) else curve)
                    if (isRule) ruleShare++ else if (grownBytes[i] != 0.toByte()) inkShare++
                    val weight = (weights[i] * 1.5f).coerceAtMost(1f)
                    val mapped = targetPaper + (inked - targetPaper) * weight
                    val amountHere = value + (mapped - value) * strength
                    output[i] = amountHere.roundToInt().coerceIn(0, 255).toByte()
                    lifted += abs(amountHere - value).toDouble()
                }
                Triple(inkShare, ruleShare, lifted)
            }
            val inkShare = parts.sumOf { it.first }
            val ruleShare = parts.sumOf { it.second }
            var lifted = 0.0
            for (part in parts) lifted += part.third
            lap("pixelLoop")
            // One continuous thin line at one tone, under the writing. A rule too faint to be sure of
            // keeps its own light tone: it may be the other side of the sheet.
            val lineWidth = max(ScanConfig.float("toneZonesRuleWidthMin", 1.1f), ScanConfig.float("toneZonesRuleWidth", 1.5f) * width / 1000f)
            val lineIndex = ArrayList<Int>()
            val lineTone = ArrayList<Int>()
            fun draw(track: RuleTrack, vertical: Boolean) {
                val own = track.level * targetPaper
                val tone = ruleTone + (own - ruleTone) * smoothStep((track.level - ruleSureLevel) / (ruleFaintLevel - ruleSureLevel))
                val limit = if (vertical) width else height
                val reach = (lineWidth / 2 + 1).toInt()
                for (i in track.heights.indices) {
                    val along = track.start + i
                    val centre = track.heights[i]
                    for (c in max(0, centre.roundToInt() - reach)..min(limit - 1, centre.roundToInt() + reach)) {
                        val cover = (lineWidth / 2 + .5f - abs(c - centre)).coerceIn(0f, 1f)
                        if (cover <= 0f) continue
                        val value = (targetPaper + (tone - targetPaper) * cover * strength).roundToInt()
                        val at = if (vertical) along * width + c else c * width + along
                        if (value < (output[at].toInt() and 255)) {
                            output[at] = value.coerceIn(0, 255).toByte()
                            lineIndex += at
                            lineTone += value
                        }
                    }
                }
            }
            drawnAcross.forEach { draw(it, false) }
            drawnDown.forEach { draw(it, true) }
            if (ScanConfig.int("toneZonesPinholes", 1) != 0) fillPinholes(output, width, height)
            if (ruled && scale >= ScanConfig.float("toneZonesSpeckScale", 1.5f))
                dropDrawnSpecks(output, tidyValues, lineSeed?.bytes, width, height, scale, targetPaper, stats, owned)
            if (ruled && ScanConfig.int("toneZonesEdgeStubs", 1) != 0)
                stats.put("edgeStubs", dropEdgeStubs(output, drawnAcross, width, height, targetPaper.toFloat(), owned))
            lap("drawRules")
            when (channels) {
                1 -> page.put(0, 0, output)
                else -> {
                    // A coloured mark gets one luma delta on all channels, so it keeps its hue. Weak colour
                    // is only the cast of the light, so that ink and what was not kept become neutral.
                    val colour = ByteArray(output.size * 3).also { page.get(0, 0, it) }
                    val castLow = ScanConfig.int("toneZonesCastLow", 50).toFloat()
                    val castHigh = ScanConfig.int("toneZonesCastHigh", 90).toFloat()
                    ParallelFilter.ranges(output.size) { from, to ->
                        for (i in from until to) {
                            val target = output[i].toInt() and 255
                            val delta = target - (grayBytes[i].toInt() and 255)
                            val red = colour[3 * i].toInt() and 255
                            val green = colour[3 * i + 1].toInt() and 255
                            val blue = colour[3 * i + 2].toInt() and 255
                            val chroma = max(red, max(green, blue)) - min(red, min(green, blue))
                            val colourful = smoothStep((chroma - castLow) / (castHigh - castLow))
                            val tint = (1f - (1f - (weights[i] * 1.5f).coerceAtMost(1f)) * strength) * colourful
                            for (c in 0..2) {
                                val shifted = ((colour[3 * i + c].toInt() and 255) + delta).coerceIn(0, 255)
                                colour[3 * i + c] =
                                    (target + (shifted - target) * tint).roundToInt().coerceIn(0, 255).toByte()
                            }
                        }
                    }
                    for (k in lineIndex.indices) {
                        val at = lineIndex[k]
                        for (c in 0..2) {
                            if ((colour[3 * at + c].toInt() and 255) > lineTone[k]) colour[3 * at + c] = lineTone[k].toByte()
                        }
                    }
                    page.put(0, 0, colour)
                }
            }
            lap("colour")
            stats
                .put("applied", true)
                .put("reason", "hysteresis-map")
                .put("inkShare", inkShare.toDouble() / relative.size)
                .put("ruleShare", ruleShare.toDouble() / relative.size)
                .put("ruleCrossingPixels", crossingPixels)
                .put("meanAbsoluteShift", lifted / relative.size)
                .put("elapsedMs", elapsed(started))
            last.set(stats)
            if (ScanConfig.int("workbookDiagnostics", 0) != 0)
                android.util.Log.d("ToneZones", stats.toString())
            return stats
        } finally {
            owned.forEach(Mat::release)
        }
    }

    /**
     * A finger or other coloured object reaching in from the frame, widened to take in its shadow. It is
     * judged against the page's own paper, because fair skin under warm light is only a little warmer
     * than the paper, and at low resolution, because pen strokes are thin and an object is not. Warm light
     * makes a shadow warm too, and judging by warmth alone erased a page's writing. So an object must be
     * strongly warm, and must carry no marks: nothing written can be cleared this way. Null when there is
     * none. Known cost: a large warm picture touching the frame goes too; a pale finger stays.
     */
    private fun fingerZone(page: Mat, thin: Int, marks: Mat, minimumMark: Int, owned: MutableList<Mat>): Mat? {
        val lift = ScanConfig.float("toneZonesFingerLift", .10f)
        if (lift <= 0f) return null
        val small = Mat().also(owned::add)
        val width = max(16, page.cols() / 4)
        val height = max(16, page.rows() / 4)
        Imgproc.resize(page, small, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val bytes = ByteArray(width * height * 3).also { small.get(0, 0, it) }
        val warmth = FloatArray(width * height)
        val histogram = IntArray(101)
        val luma = IntArray(width * height)
        val lumaHistogram = IntArray(256)
        for (i in warmth.indices) {
            val r = bytes[3 * i].toInt() and 255
            val g = bytes[3 * i + 1].toInt() and 255
            val b = bytes[3 * i + 2].toInt() and 255
            val value = if (r >= 70 && r >= g && g >= b) (r - b).toFloat() / r else 0f
            warmth[i] = value
            histogram[(value * 100).roundToInt().coerceIn(0, 100)]++
            luma[i] = (r * 299 + g * 587 + b * 114) / 1000
            lumaHistogram[luma[i]]++
        }
        val paperLuma = percentile(lumaHistogram, warmth.size, .75f)
        val paper = percentile(histogram, warmth.size, .5f) / 100f
        val mask = Mat().also(owned::add)
        mask.create(height, width, CvType.CV_8UC1)
        mask.put(0, 0, ByteArray(warmth.size) { if (warmth[it] > paper + lift) 255.toByte() else 0.toByte() })
        val open = max(3, thin / 2) or 1
        Imgproc.morphologyEx(
            mask,
            mask,
            Imgproc.MORPH_OPEN,
            Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(open.toDouble(), open.toDouble())).also(owned::add),
        )
        val labels = Mat().also(owned::add)
        val stats = Mat().also(owned::add)
        val centroids = Mat().also(owned::add)
        val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S)
        val table = statsTable(stats, count)
        val minimum = ScanConfig.float("toneZonesFingerArea", .004f) * warmth.size
        val chosen =
            BooleanArray(count) {
                if (it == 0 || table[it * 5 + Imgproc.CC_STAT_AREA] < minimum) return@BooleanArray false
                val x0 = table[it * 5 + Imgproc.CC_STAT_LEFT]
                val y0 = table[it * 5 + Imgproc.CC_STAT_TOP]
                val x1 = x0 + table[it * 5 + Imgproc.CC_STAT_WIDTH]
                val y1 = y0 + table[it * 5 + Imgproc.CC_STAT_HEIGHT]
                x0 <= 1 || y0 <= 1 || x1 >= width - 1 || y1 >= height - 1
            }
        if (chosen.none { it }) return null
        val labelValues = IntArray(warmth.size).also { labels.get(0, 0, it) }
        // Judge each candidate's inside, away from the shadow and outline around it.
        val inner = Mat().also(owned::add)
        Imgproc.erode(
            mask,
            inner,
            Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(2.0 * open + 1, 2.0 * open + 1)).also(owned::add),
        )
        val innerBytes = ByteArray(warmth.size).also { inner.get(0, 0, it) }
        val inside = IntArray(count)
        val warmSum = FloatArray(count)
        for (i in warmth.indices) {
            if (innerBytes[i] != 0.toByte()) {
                inside[labelValues[i]]++
                warmSum[labelValues[i]] += warmth[i]
            }
        }
        // What is written inside the object counts against it, by area: at full resolution skin texture
        // makes many tiny marks, while writing covers far more. A stroke is dark, so it leaves a hole in
        // the warm area: closing fills the holes, while an outline just outside the object stays out.
        val written = LongArray(count)
        val wideLabels = Mat().also(owned::add)
        labels.convertTo(wideLabels, CvType.CV_16S)
        val widened = 4 * open + 1
        Imgproc.morphologyEx(
            wideLabels,
            wideLabels,
            Imgproc.MORPH_CLOSE,
            Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(widened.toDouble(), widened.toDouble())).also(owned::add),
        )
        val around = ShortArray(warmth.size).also { wideLabels.get(0, 0, it) }
        val markLabels = Mat().also(owned::add)
        val markStats = Mat().also(owned::add)
        val markCentres = Mat().also(owned::add)
        val markCount = Imgproc.connectedComponentsWithStats(marks, markLabels, markStats, markCentres, 8, CvType.CV_32S)
        val markTable = statsTable(markStats, markCount)
        val centres = DoubleArray(markCount * 2).also { markCentres.get(0, 0, it) }
        for (mark in 1 until markCount) {
            if (markTable[mark * 5 + Imgproc.CC_STAT_AREA] < minimumMark) continue
            val x = (centres[2 * mark] * width / page.cols()).toInt().coerceIn(0, width - 1)
            val y = (centres[2 * mark + 1] * height / page.rows()).toInt().coerceIn(0, height - 1)
            written[around[y * width + x].toInt()] += markTable[mark * 5 + Imgproc.CC_STAT_AREA].toLong()
        }
        val warmEnough = ScanConfig.float("toneZonesFingerWarmth", .50f)
        val allowed = ScanConfig.float("toneZonesFingerWritten", .03f)
        // Marks are measured at full size and the area here at a quarter, so 16 cells to the pixel count.
        val fullSizeCell = page.cols().toFloat() / width * page.rows() / height
        for (label in 1 until count) {
            if (!chosen[label]) continue
            val filled = table[label * 5 + Imgproc.CC_STAT_AREA] * fullSizeCell
            chosen[label] = inside[label] > 0 && warmSum[label] / inside[label] >= warmEnough && written[label] <= allowed * filled
        }
        if (chosen.none { it }) return null
        mask.put(0, 0, ByteArray(warmth.size) { if (chosen[labelValues[it]]) 255.toByte() else 0.toByte() })
        // Widened while small: this kernel is large, and the object's outline is only known roughly.
        val size = ((2 * ScanConfig.int("toneZonesFingerShadow", 5) * thin + 1) / 4 or 1).toDouble()
        Imgproc.dilate(
            mask,
            mask,
            Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(size, size)).also(owned::add),
        )
        val zone = Mat().also(owned::add)
        Imgproc.resize(mask, zone, page.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
        return zone
    }

    /**
     * What is left along a finger's edge: the dark crease just outside its zone. A short mark lying almost
     * wholly in the strip beside the zone goes. A rule or a stroke that merely passes through stays. Known
     * cost: a small mark squeezed right against the finger goes too.
     */
    private fun dropFingerCrumbs(keep: Mat, finger: Mat, thin: Int, owned: MutableList<Mat>) {
        val beside = Mat().also(owned::add)
        val size = (4 * thin + 1).toDouble()
        ParallelFilter.dilate(finger, beside, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(size, size)).also(owned::add))
        val labels = Mat().also(owned::add)
        val stats = Mat().also(owned::add)
        val centroids = Mat().also(owned::add)
        val count = Imgproc.connectedComponentsWithStats(keep, labels, stats, centroids, 8, CvType.CV_32S)
        if (count < 2) return
        val table = statsTable(stats, count)
        val labelValues = IntArray(keep.cols() * keep.rows()).also { labels.get(0, 0, it) }
        val besideBytes = ByteArray(labelValues.size).also { beside.get(0, 0, it) }
        val inside = IntArray(count)
        for (i in labelValues.indices) if (besideBytes[i] != 0.toByte()) inside[labelValues[i]]++
        val drop =
            BooleanArray(count) {
                it != 0 &&
                    max(table[it * 5 + Imgproc.CC_STAT_WIDTH], table[it * 5 + Imgproc.CC_STAT_HEIGHT]) < 3 * thin &&
                    inside[it] >= .8f * table[it * 5 + Imgproc.CC_STAT_AREA]
            }
        if (drop.none { it }) return
        val bytes = ByteArray(labelValues.size).also { keep.get(0, 0, it) }
        for (i in labelValues.indices) if (drop[labelValues[i]]) bytes[i] = 0
        keep.put(0, 0, bytes)
    }

    /** One rule: its height at every column from [start] to [end], and how dark it was where seen. */
    /**
     * Whether [tracks] are the rules of ruled paper: at least `toneZonesRuledTracks` of them run across half the
     * page, and seven in ten of the gaps between them are a whole number of one common gap, within 15%.
     */
    private fun evenRules(tracks: List<RuleTrack>, width: Int, scale: Float): Boolean {
        val heights = tracks.filter { it.end - it.start >= .5f * width }.map { it.heights[it.heights.size / 2] }.sorted()
        val rows = ArrayList<Float>()
        for (y in heights) if (rows.isEmpty() || y - rows.last() > 4 * scale) rows += y
        if (rows.size < ScanConfig.int("toneZonesRuledTracks", 8)) return false
        val gaps = rows.zipWithNext { a, b -> b - a }
        val common = gaps.sorted()[gaps.size / 2]
        if (common <= 0f) return false
        val even = gaps.count { val k = it / common; abs(k - k.roundToInt()) < .15f && k >= .5f }
        return even >= .7f * gaps.size
    }

    private class RuleTrack(val start: Int, val end: Int, val heights: FloatArray, val level: Float)

    /**
     * Follows each long rule across the page in short runs, through writing and faint stretches. A run
     * counts as seen only where the rule mask supports it, and between seen runs the height is
     * interpolated. An end near the frame, or one that stops at a finger, runs on to the frame.
     */
    private fun ruleTracks(relative: FloatArray, rules: Mat, finger: Mat?, width: Int, height: Int, scale: Float): List<RuleTrack> {
        val mask = ByteArray(relative.size).also { rules.get(0, 0, it) }
        val perRow = IntArray(height)
        for (y in 0 until height) {
            var count = 0
            for (x in 0 until width) if (mask[y * width + x] != 0.toByte()) count++
            perRow[y] = count
        }
        val window = IntArray(height)
        for (y in 1 until height - 1) window[y] = perRow[y - 1] + perRow[y] + perRow[y + 1]
        val fingerBytes = finger?.let { zone -> ByteArray(relative.size).also { zone.get(0, 0, it) } }
        val run = max(8, (12 * scale).roundToInt())
        val separation = max(3, (3 * scale).roundToInt())
        val starts = mutableListOf<Int>()
        for (y in 2 until height - 2) {
            if (window[y] < .25f * width) continue
            var peak = true
            for (d in -separation..separation) {
                val other = (y + d).coerceIn(0, height - 1)
                if (window[other] > window[y] || (d < 0 && window[other] == window[y])) peak = false
            }
            if (peak) starts += y
        }
        val tracks = mutableListOf<RuleTrack>()
        val runs = width / run
        if (runs < 8) return tracks
        class Traced(val first: Int, val heights: FloatArray, val seen: BooleanArray, val low: Int, val high: Int)
        val traced = mutableListOf<Traced>()
        for (first in starts) {
            val heights = FloatArray(runs)
            val seen = BooleanArray(runs)
            // The most supported run near this row is where tracking begins.
            var begin = -1
            var beginY = first
            var best = 0
            for (r in 0 until runs) {
                for (d in -2..2) {
                    val row = first + d
                    if (row !in 0 until height) continue
                    var support = 0
                    for (x in r * run until min(width, (r + 1) * run)) if (mask[row * width + x] != 0.toByte()) support++
                    if (support > best) {
                        best = support
                        begin = r
                        beginY = row
                    }
                }
            }
            if (begin < 0) continue
            fun walk(from: Int, step: Int) {
                var y = beginY
                var r = from
                while (r in 0 until runs) {
                    var bestRow = y
                    var bestSupport = 0
                    for (d in -1..1) {
                        val row = y + d
                        if (row !in 0 until height) continue
                        var support = 0
                        for (x in r * run until min(width, (r + 1) * run)) if (mask[row * width + x] != 0.toByte()) support++
                        if (support > bestSupport || (support == bestSupport && d == 0)) {
                            bestSupport = support
                            bestRow = row
                        }
                    }
                    if (bestSupport * 3 >= run) {
                        y = bestRow
                        seen[r] = true
                        // The middle of the line: support alone favours whichever edge row comes first.
                        var rows = 0L
                        var pixels = 0
                        for (row in max(0, y - 3)..min(height - 1, y + 3)) {
                            for (x in r * run until min(width, (r + 1) * run)) {
                                if (mask[row * width + x] != 0.toByte()) {
                                    rows += row
                                    pixels++
                                }
                            }
                        }
                        heights[r] = if (pixels > 0) rows.toFloat() / pixels else y.toFloat()
                    }
                    r += step
                }
            }
            walk(begin, 1)
            walk(begin - 1, -1)
            var low = 0
            while (low < runs && !seen[low]) low++
            var high = runs - 1
            while (high >= 0 && !seen[high]) high--
            if (high - low < .4f * runs) continue
            if ((low..high).count { seen[it] } < .5f * (high - low + 1)) continue
            traced += Traced(first, heights, seen, low, high)
        }
        if (traced.isEmpty()) return tracks
        // A straight local fit over a tenth of the page or so, so a pixel's wobble in a rule's height, or a
        // stroke crossing it, cannot show as a bend. The fit's slope is also what an end carries on along.
        val reachWindow = max(6, (ScanConfig.float("toneZonesRuleSmooth", .1f) * runs).roundToInt())
        class Fitted(val centres: FloatArray, val leftSlope: Float, val rightSlope: Float)
        fun fit(t: Traced): Fitted {
            // Median of five first: one jump onto a crossing stroke must not pull the fit.
            val medians = FloatArray(runs) { Float.NaN }
            for (r in t.low..t.high) {
                val around = (max(t.low, r - 2)..min(t.high, r + 2)).filter { t.seen[it] }.map { t.heights[it] }.sorted()
                if (around.isNotEmpty()) medians[r] = around[around.size / 2]
            }
            val centres = FloatArray(runs) { Float.NaN }
            val slopes = FloatArray(runs)
            for (r in t.low..t.high) {
                var sumW = 0f
                var sumX = 0f
                var sumY = 0f
                var sumXX = 0f
                var sumXY = 0f
                for (o in max(t.low, r - reachWindow)..min(t.high, r + reachWindow)) {
                    if (medians[o].isNaN()) continue
                    val x = (o - r).toFloat()
                    val w = 1f - abs(x) / (reachWindow + 1)
                    sumW += w
                    sumX += w * x
                    sumY += w * medians[o]
                    sumXX += w * x * x
                    sumXY += w * x * medians[o]
                }
                if (sumW <= 0f) continue
                val det = sumW * sumXX - sumX * sumX
                if (det < 1e-3f) {
                    centres[r] = sumY / sumW
                    continue
                }
                val slope = (sumW * sumXY - sumX * sumY) / det
                slopes[r] = slope
                centres[r] = (sumY - slope * sumX) / sumW
            }
            return Fitted(centres, slopes[t.low], slopes[t.high])
        }
        val fits = traced.map(::fit)
        // Where most rules reach is where the sheet's rules end. A rule that fades out early, at a curl or
        // in a highlight, carries on to the same place in the direction its neighbours take, since one
        // rule's own last stretch is the least reliable part of it. The sheet's rules are nearly parallel.
        val reachLow = traced.map { it.low }.sorted()[traced.size / 2]
        val reachHigh = traced.map { it.high }.sorted()[traced.size / 2]
        val sharedLeft = fits.map { it.leftSlope }.sorted()[fits.size / 2].coerceIn(-.3f, .3f)
        val sharedRight = fits.map { it.rightSlope }.sorted()[fits.size / 2].coerceIn(-.3f, .3f)
        for ((index, t) in traced.withIndex()) {
            val first = t.first
            val centres = fits[index].centres
            val low = min(t.low, reachLow)
            val high = max(t.high, reachHigh)
            for (r in low until t.low) centres[r] = centres[t.low] + sharedLeft * (r - t.low)
            for (r in t.high + 1..high) centres[r] = centres[t.high] + sharedRight * (r - t.high)
            fun reaches(fromX: Int, toX: Int): Boolean {
                if (toX - fromX <= .04f * width) return true
                val zone = fingerBytes ?: return false
                val row = centres[if (fromX == 0) low else high].let { if (it.isNaN()) first else it.roundToInt() }
                var inside = 0
                for (x in fromX until toX) if (zone[row.coerceIn(0, height - 1) * width + x] != 0.toByte()) inside++
                return inside >= .6f * (toX - fromX)
            }
            val startX = if (reaches(0, low * run)) 0 else low * run
            val endX = if (reaches(min(width, (high + 1) * run), width)) width else min(width, (high + 1) * run)
            val line = FloatArray(endX - startX)
            for (x in startX until endX) {
                val position = (x - run / 2f) / run
                var a = position.toInt().coerceIn(low, high)
                while (a > low && centres[a].isNaN()) a--
                var b = (a + 1).coerceAtMost(high)
                while (b < high && centres[b].isNaN()) b++
                val t = if (b == a) 0f else ((position - a) / (b - a)).coerceIn(0f, 1f)
                val ya = if (centres[a].isNaN()) centres[b] else centres[a]
                val yb = if (centres[b].isNaN()) ya else centres[b]
                line[x - startX] = ya + (yb - ya) * t
            }
            val samples = FloatArray(line.size)
            var sampleCount = 0
            for (i in line.indices) {
                val row = line[i].roundToInt().coerceIn(1, height - 2)
                val x = startX + i
                if (mask[row * width + x] != 0.toByte()) {
                    samples[sampleCount++] = min(relative[row * width + x], min(relative[(row - 1) * width + x], relative[(row + 1) * width + x]))
                }
            }
            if (sampleCount == 0) continue
            java.util.Arrays.sort(samples, 0, sampleCount)
            tracks += RuleTrack(startX, endX, line, samples[sampleCount / 2])
        }
        return tracks
    }

    /** [ruleTracks] for vertical lines, on the transposed page. Most pages have none, and return early. */
    private fun verticalTracks(relative: FloatArray, rules: Mat, finger: Mat?, width: Int, height: Int, scale: Float): List<RuleTrack> {
        val mask = ByteArray(relative.size).also { rules.get(0, 0, it) }
        val perColumn = IntArray(width)
        for (y in 0 until height) for (x in 0 until width) if (mask[y * width + x] != 0.toByte()) perColumn[x]++
        if (perColumn.none { it >= .1f * height }) return emptyList()
        val turned = FloatArray(relative.size)
        for (y in 0 until height) for (x in 0 until width) turned[x * height + y] = relative[y * width + x]
        val turnedRules = Mat()
        val turnedFinger = Mat()
        try {
            Core.transpose(rules, turnedRules)
            if (finger != null) Core.transpose(finger, turnedFinger)
            return ruleTracks(turned, turnedRules, if (finger != null) turnedFinger else null, height, width, scale)
        } finally {
            turnedRules.release()
            turnedFinger.release()
        }
    }

    /**
     * Clears small drawn marks, at most `toneZonesDrawnSpeckSpan` across, whose darkest smoothed pixel is lighter
     * than `toneZonesDrawnSpeck` of the paper. On thin ruled paper the writing on the other side presses dots
     * into the paper's pits, which the tone curve draws black; a written dot or full stop is far darker at its
     * middle. Judged on the drawn page, where a dot stands alone: the grown mark around it also held pale grain,
     * which made it too large to judge. Measured on the owner's ruled pages against the reference scanner's: at .50, 134 dots
     * the reference scanner has not went, and 33 marks it has, almost all specks of a pixel or two at a stroke's side.
     * Only called on ruled paper; off when the cut is 0.
     */
    private fun dropDrawnSpecks(
        output: ByteArray,
        tidy: FloatArray,
        lineSeeds: ByteArray?,
        width: Int,
        height: Int,
        scale: Float,
        targetPaper: Int,
        stats: JSONObject,
        owned: MutableList<Mat>,
    ) {
        val cut = ScanConfig.float("toneZonesDrawnSpeck", .45f)
        if (cut <= 0f) return
        val span = ScanConfig.float("toneZonesDrawnSpeckSpan", 8f) * scale
        val ink = Mat().also(owned::add)
        ink.create(height, width, CvType.CV_8UC1)
        ink.put(0, 0, ByteArray(output.size) { if ((output[it].toInt() and 255) < 170) 255.toByte() else 0.toByte() })
        val labels = Mat().also(owned::add)
        val table = Mat().also(owned::add)
        val centroids = Mat().also(owned::add)
        val count = Imgproc.connectedComponentsWithStats(ink, labels, table, centroids, 8, CvType.CV_32S)
        if (count < 2) return
        val boxes = statsTable(table, count)
        val labelValues = IntArray(output.size).also { labels.get(0, 0, it) }
        val darkest = FloatArray(count) { Float.MAX_VALUE }
        val lined = BooleanArray(count)
        for (i in labelValues.indices) {
            val label = labelValues[i]
            if (label == 0) continue
            if (tidy[i] < darkest[label]) darkest[label] = tidy[i]
            if (lineSeeds != null && lineSeeds[i] != 0.toByte()) lined[label] = true
        }
        var dropped = 0
        for (label in 1 until count) {
            val x0 = boxes[label * 5 + Imgproc.CC_STAT_LEFT]
            val y0 = boxes[label * 5 + Imgproc.CC_STAT_TOP]
            val w = boxes[label * 5 + Imgproc.CC_STAT_WIDTH]
            val h = boxes[label * 5 + Imgproc.CC_STAT_HEIGHT]
            if (max(w, h) > span || darkest[label] <= cut || lined[label]) continue
            dropped++
            // The mark and the soft rim the weight blur drew around it, but nothing of another mark.
            for (y in max(0, y0 - 2) until min(height, y0 + h + 2)) {
                for (x in max(0, x0 - 2) until min(width, x0 + w + 2)) {
                    val at = y * width + x
                    val owner = labelValues[at]
                    if (owner == 0 || owner == label) output[at] = targetPaper.toByte()
                }
            }
        }
        stats.put("drawnSpecks", dropped)
    }

    /**
     * Fills a pixel or two of paper that ink encloses on seven sides or more: a pit of grain inside a pen stroke,
     * which the reference scanner draws solid. A gap between two strokes is open on at least two sides, so it stays.
     */
    private fun fillPinholes(output: ByteArray, width: Int, height: Int) {
        val ink = ScanConfig.int("toneZonesPinholeInk", 110)
        val source = output.copyOf()
        val around = intArrayOf(-width - 1, -width, -width + 1, -1, 1, width - 1, width, width + 1)
        // Reads only the copy and writes each pixel on its own, so row bands on the filter threads give the same page.
        ParallelFilter.rowBands(height - 2, width) { from, to -> for (y in from + 1 until to + 1) {
            var i = y * width + 1
            for (x in 1 until width - 1) {
                if ((source[i].toInt() and 255) > ink) {
                    var inked = 0
                    var sum = 0
                    for (o in around) {
                        val v = source[i + o].toInt() and 255
                        if (v <= ink) {
                            inked++
                            sum += v
                        }
                    }
                    if (inked >= 7) output[i] = (sum / inked).toByte()
                }
                i++
            }
        } }
    }

    /**
     * The marks among [candidates] that sit in a printed line: at least `toneZonesTextLineMarks` of them, each a
     * character's size, alike in size (`toneZonesTextLineSize` at most between neighbours), set along a straight
     * line parallel to the page's edges (across or down: a photo may be turned) with gaps of at most
     * `toneZonesTextLineGap` characters, and off that line by at most `toneZonesTextLineDrift` of a character.
     */
    private fun printedLines(candidates: List<Int>, table: IntArray, centres: DoubleArray, scale: Float, accept: (List<Int>) -> Boolean = { true }): Set<Int> {
        val need = ScanConfig.int("toneZonesTextLineMarks", 6)
        if (need <= 0 || candidates.size < need) return emptySet()
        val sizeRatio = ScanConfig.float("toneZonesTextLineSize", 2f)
        val gapLimit = ScanConfig.float("toneZonesTextLineGap", 2.5f)
        val drift = ScanConfig.float("toneZonesTextLineDrift", .25f)
        val spread = ScanConfig.float("toneZonesTextLineSpread", 2.5f)
        val smallest = ScanConfig.float("toneZonesTextLineSmallest", 8f) * scale
        val largest = 60 * scale
        val kept = HashSet<Int>()
        for (down in listOf(false, true)) {
            // along: the direction the line runs; across: the character's height.
            fun along(l: Int) = centres[2 * l + if (down) 1 else 0]
            fun across(l: Int) = centres[2 * l + if (down) 0 else 1]
            fun extent(l: Int) = table[l * 5 + if (down) Imgproc.CC_STAT_WIDTH else Imgproc.CC_STAT_HEIGHT].toDouble()
            fun length(l: Int) = table[l * 5 + if (down) Imgproc.CC_STAT_HEIGHT else Imgproc.CC_STAT_WIDTH].toDouble()
            val marks = candidates.filter { max(extent(it), length(it)) in smallest..largest }.sortedBy { along(it) }
            val parent = IntArray(marks.size) { it }
            fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; parent[i] = r; return r }
            for (i in marks.indices) {
                val a = marks[i]
                val size = max(extent(a), 1.0)
                for (j in i + 1 until marks.size) {
                    val b = marks[j]
                    val gap = along(b) - along(a) - (length(a) + length(b)) / 2
                    if (gap > gapLimit * size) break
                    val other = max(extent(b), 1.0)
                    if (max(size, other) / min(size, other) > sizeRatio) continue
                    if (abs(across(b) - across(a)) > drift * max(size, other)) continue
                    parent[root(j)] = root(i)
                }
            }
            for ((_, group) in marks.indices.groupBy(::root)) {
                if (group.size < need) continue
                val labels = group.map { marks[it] }
                // Straight and along the page: a least-squares line through the centres, nearly level, and every
                // centre close to it.
                val xs = labels.map(::along)
                val ys = labels.map(::across)
                val mx = xs.average()
                val my = ys.average()
                val sxx = xs.sumOf { (it - mx) * (it - mx) }
                if (sxx <= 0.0) continue
                val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
                if (abs(slope) > .05) continue
                val sizes = labels.map { max(extent(it), length(it)) }.sorted()
                // Print is set in one size: the line's largest mark is at most `toneZonesTextLineSpread` times its smallest.
                if (sizes.last() / max(sizes.first(), 1.0) > spread) continue
                val typical = labels.map(::extent).sorted()[labels.size / 2]
                if (labels.indices.any { abs(ys[it] - (my + slope * (xs[it] - mx))) > drift * typical }) continue
                // Print is set at a fixed pitch: most gaps between neighbouring centres are a whole number of
                // character steps (a missing or split character makes two, or half). Writing, and the other side's
                // writing coming through in rows, is spaced as the hand went. On the owner's worksheet 13 of the
                // heading's 15 gaps were within `toneZonesTextLinePitchSlack` of a step; on a notebook's
                // show-through rows 2 or 3 of 6 to 9.
                val steps = xs.sorted().zipWithNext { a, b -> b - a }
                val pitch = steps.sorted()[steps.size / 2]
                if (pitch <= 0.0) continue
                val slack = ScanConfig.float("toneZonesTextLinePitchSlack", .15f)
                val onPitch = steps.count { g -> abs(g - max(1L, Math.round(g / pitch)) * pitch) <= slack * pitch }
                if (onPitch < ScanConfig.float("toneZonesTextLinePitchShare", .7f) * steps.size) continue
                if (!accept(labels)) continue
                kept += labels
                // A character in pieces (the three strokes of 三, a full stop) is smaller than its neighbours and does
                // not link up; a piece lying within the line's band and its length belongs to the line.
                val from = xs.min() - typical
                val to = xs.max() + typical
                for (other in candidates) {
                    if (other in kept || max(extent(other), length(other)) > largest) continue
                    val x = along(other)
                    if (x < from || x > to) continue
                    if (abs(across(other) - (my + slope * (x - mx))) <= .5 * typical) kept += other
                }
            }
        }
        return kept
    }

    /**
     * Seeds for a line of small print too pale and soft for any other seed, on ruled pages (elsewhere [lineSeeds]
     * finds it). The candidate marks away from the rules that hold no seed at all are grouped by [printedLines];
     * a mark in such a line is seeded whole. On the owner's soft photo of a grid worksheet the printed heading was
     * all such marks, and went. The other side's writing, coming through, is not set in even lines of one size.
     */
    private fun textLineSeeds(
        candidates: Mat,
        rules: Mat,
        seeds: Mat,
        cross: Mat,
        tidy: FloatArray,
        width: Int,
        height: Int,
        scale: Float,
        owned: MutableList<Mat>,
    ): LineSeeds? {
        val free = Mat().also(owned::add)
        Imgproc.dilate(rules, free, cross)
        Core.subtract(candidates, free, free)
        val labels = Mat().also(owned::add)
        val stats = Mat().also(owned::add)
        val centroids = Mat().also(owned::add)
        val count = Imgproc.connectedComponentsWithStats(free, labels, stats, centroids, 8, CvType.CV_32S)
        if (count < 2) return null
        val table = statsTable(stats, count)
        val labelValues = IntArray(width * height).also { labels.get(0, 0, it) }
        val seedBytes = ByteArray(labelValues.size).also { seeds.get(0, 0, it) }
        val seeded = BooleanArray(count)
        for (i in labelValues.indices) if (seedBytes[i] != 0.toByte()) seeded[labelValues[i]] = true
        val centres = DoubleArray(count * 2).also { centroids.get(0, 0, it) }
        val darkest = FloatArray(count) { 1f }
        for (i in labelValues.indices) if (tidy[i] < darkest[labelValues[i]]) darkest[labelValues[i]] = tidy[i]
        // Seeded marks may stand in a line (a character's darker stroke), but most of the line must be unseeded:
        // a row of the front's own writing is seeded nearly all through and needs nothing from here.
        val unseededShare = ScanConfig.float("toneZonesTextLineUnseeded", .6f)
        // And it must be print: the other side's writing sits on the other side's rules, which lie under the
        // front's, so on thin ruled paper it comes through in rows too. Those rows are specks, paler than print
        // even in a soft photo: on the owner's worksheet the heading's marks stood out by 0.29 (median), the
        // show-through rows of a notebook by 0.11. The line's median mark must stand out by
        // `toneZonesTextLineDepth`.
        val lineDepth = ScanConfig.float("toneZonesTextLineDepth", .18f)
        val lines = printedLines((1 until count).toList(), table, centres, scale) { group ->
            group.count { !seeded[it] } >= unseededShare * group.size &&
                group.map { 1f - darkest[it] }.sorted()[group.size / 2] >= lineDepth
        }
        if (lines.isEmpty()) return null
        // The page's print, now known, says what print looks like in this photo: how dark, how thick its strokes,
        // how large. A lone mark that matches it is print too: on the owner's worksheet the circled ① and ② stood
        // as dark as the heading (0.28 and 0.37 against its lower quartile 0.27) with strokes as thick (5.7 and 5.1
        // against its 4.7 median), where the page's show-through stood out by under 0.2 with 2 px strokes.
        // `toneZonesPrintSingles` 0 turns this off.
        val printed = HashSet(lines)
        if (ScanConfig.int("toneZonesPrintSingles", 1) != 0) {
            val members = lines.toList()
            val perimeter = IntArray(count)
            for (y in 1 until height - 1) {
                var i = y * width + 1
                for (x in 1 until width - 1) {
                    val l = labelValues[i]
                    if (l != 0 && (labelValues[i - 1] != l || labelValues[i + 1] != l || labelValues[i - width] != l || labelValues[i + width] != l)) perimeter[l]++
                    i++
                }
            }
            fun stroke(l: Int) = 2f * table[l * 5 + Imgproc.CC_STAT_AREA] / max(1, perimeter[l])
            fun size(l: Int) = max(table[l * 5 + Imgproc.CC_STAT_WIDTH], table[l * 5 + Imgproc.CC_STAT_HEIGHT])
            val depths = members.map { 1f - darkest[it] }.sorted()
            val strokes = members.map(::stroke).sorted()
            val sizes = members.map(::size).sorted()
            val minDepth = depths[depths.size / 4]
            val maxStroke = 1.2f * strokes[strokes.size * 3 / 4]
            val maxSize = 1.5f * sizes.last()
            val minSize = ScanConfig.float("toneZonesTextLineSmallest", 8f) * scale
            val singles = ArrayList<Int>()
            for (l in 1 until count) {
                // A seeded lone mark can still go as short and isolated in the ghost rules; this keeps it too.
                if (l in printed) continue
                val sz = size(l)
                if (sz < minSize || sz > maxSize) continue
                if (1f - darkest[l] >= minDepth && stroke(l) <= maxStroke) singles += l
            }
            // Lone symbols beside print are few. When more marks match than the lines themselves hold, the "print"
            // was the other side's printed lines coming through, and what matched is more of it: on a notebook with
            // strong, sharp show-through of a printed page 443 marks matched lines of 10-14 marks; on the
            // worksheet, 13 matched lines of 24. Then none is kept.
            if (singles.size <= members.size) printed += singles
        }
        val path = ByteArray(labelValues.size) { if (labelValues[it] in printed) 255.toByte() else 0 }
        // Drawn as a seeded line is: each pixel as dark as its line middle stands out (see [lineSeeds]).
        val step = max(2, (ScanConfig.float("toneZonesLineSeedStep", 3f) * photoPixel()).roundToInt())
        val offsets = intArrayOf(step, step * width, step * (width + 1), step * (width - 1))
        val depth = ByteArray(labelValues.size)
        val core = ScanConfig.float("toneZonesFaintCore", .55f)
        for (y in step until height - step) {
            var i = y * width + step
            for (x in step until width - step) {
                // A lone mark with a dark core keeps its own drawing and is only protected from the ghost rules:
                // drawn as a line, a page number came out bolder than it was. Pale ones (the circled ① ②) need the
                // line drawing to come out whole.
                if (path[i] != 0.toByte() && !(labelValues[i] !in lines && darkest[labelValues[i]] < core)) {
                    var d = 0f
                    for (o in offsets) d = max(d, min(tidy[i - o], tidy[i + o]) - tidy[i])
                    depth[i] = (d * 255).roundToInt().coerceIn(0, 255).toByte()
                }
                i++
            }
        }
        val mask = Mat().also(owned::add)
        mask.create(height, width, CvType.CV_8UC1)
        mask.put(0, 0, path)
        return LineSeeds(mask, path, depth)
    }

    /** A straight line's width at half its depth (pixels) and its depth, from profiles across it. */
    private class LineShape(val width: Float, val depth: Float)

    private fun sample(values: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, width - 2)
        val y0 = y.toInt().coerceIn(0, height - 2)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val i = y0 * width + x0
        return (values[i] * (1 - fx) + values[i + 1] * fx) * (1 - fy) + (values[i + width] * (1 - fx) + values[i + width + 1] * fx) * fy
    }

    /**
     * The shape of the line along [seg]: profiles across it at up to 40 places where [on] marks it (gaps of a dashed
     * line are skipped), each centred on its own darkest point, averaged. Null when it is not measurable.
     */
    private fun lineShape(values: FloatArray, on: ByteArray?, width: Int, height: Int, seg: IntArray): LineShape? {
        val dx = (seg[2] - seg[0]).toFloat()
        val dy = (seg[3] - seg[1]).toFloat()
        val length = kotlin.math.hypot(dx, dy)
        if (length < 8f) return null
        val ux = dx / length
        val uy = dy / length
        val reach = 24 // half-pixel steps either side: 12 px
        val sum = FloatArray(2 * reach + 1)
        var used = 0
        val profile = FloatArray(4 * reach + 1)
        val places = min(16, (length / 2).toInt())
        for (k in 0 until places) {
            val t = (k + .5f) * length / places
            val cx = seg[0] + ux * t
            val cy = seg[1] + uy * t
            if (cx < 12 || cy < 12 || cx >= width - 12 || cy >= height - 12) continue
            if (on != null && on[cy.roundToInt() * width + cx.roundToInt()] == 0.toByte()) continue
            for (j in profile.indices) {
                val o = (j - 2 * reach) * .5f
                profile[j] = sample(values, width, height, cx - uy * o, cy + ux * o)
            }
            // Centre on the darkest point within 2 px of the fitted line.
            var m = 2 * reach
            for (j in 2 * reach - 4..2 * reach + 4) if (profile[j] < profile[m]) m = j
            for (j in sum.indices) sum[j] += profile[m - reach + j]
            used++
        }
        if (used < 5) return null
        for (j in sum.indices) sum[j] /= used
        // Width and depth within 8 px of the middle (paper further out is lit differently); the twin search below
        // looks out to 12 px.
        val inner = 16
        val ends = (sum.copyOfRange(reach - inner, reach - inner + 5).toList() + sum.copyOfRange(reach + inner - 4, reach + inner + 1).toList()).sorted()
        val base = (ends[4] + ends[5]) / 2
        val low = sum[reach]
        val depth = base - low
        if (depth <= .01f) return null
        val half = low + depth / 2
        var l = reach
        while (l > reach - inner && sum[l - 1] <= half) l--
        var r = reach
        while (r < reach + inner && sum[r + 1] <= half) r++
        // A twin within 12 px, between half and twice as deep: a printed double rule. The other side's lines come
        // alone; a pair of like lines is the front's (a notebook's faint double margin rule had two lines 9 px
        // apart, each as pale and as wide as show-through). Not measured, so kept. A much deeper line beside it,
        // such as a grid line next to the other side's line, is no twin.
        // A twin is a line of its own: a dip at least half this line's depth below the paper that rises between them.
        for (j in 1 until sum.size - 1) {
            if (j in l - 1..r + 1 || sum[j] > half || base - sum[j] > 2 * depth) continue
            if (sum[j] > sum[j - 1] || sum[j] > sum[j + 1]) continue
            val between = if (j < l) sum.copyOfRange(j, l).max() else sum.copyOfRange(r + 1, j + 1).max()
            if (between - sum[j] >= .5f * depth) return null
        }
        return LineShape((r - l + 1) * .5f, depth)
    }

    /** True when the line middles along [seg] mostly stand out by [limit] or more: measuring its shape can wait. */
    private fun standsOut(middleDepth: ByteArray, width: Int, seg: IntArray, limit: Float): Boolean {
        val steps = 12
        var deep = 0
        var seen = 0
        for (k in 0 until steps) {
            val t = (k + .5f) / steps
            val x = (seg[0] + (seg[2] - seg[0]) * t).roundToInt()
            val y = (seg[1] + (seg[3] - seg[1]) * t).roundToInt()
            val at = y * width + x
            if (at !in middleDepth.indices) continue
            val d = middleDepth[at].toInt() and 255
            if (d == 0) continue
            seen++
            if (d >= 2 * limit * 255) deep++
        }
        return seen > 0 && deep * 3 >= seen * 2
    }

    /**
     * How wide the page's own thin dark lines are (pixels at half depth), as the reference that the camera and
     * focus set. Measured where 24 evenly spaced rows and columns cross the long thin lines ([lines]), across each
     * line, on [values]: the median over crossings at least 0.25 deep. Null with fewer than 20 such crossings.
     */
    private fun thinLineWidth(values: FloatArray, lines: Mat, width: Int, height: Int): Float? {
        val mask = ByteArray(width * height).also { lines.get(0, 0, it) }
        val widths = ArrayList<Float>()
        val reach = 8
        val profile = FloatArray(2 * reach + 1)
        fun measure(across: Boolean, fixed: Int, limit: Int) {
            var c = reach
            while (c < limit - reach) {
                val at = if (across) fixed * width + c else c * width + fixed
                if (mask[at] == 0.toByte()) { c++; continue }
                // The darkest pixel of this crossing, within 3 px.
                var best = c
                for (k in c..min(limit - reach - 1, c + 3)) {
                    val v = values[if (across) fixed * width + k else k * width + fixed]
                    if (v < values[if (across) fixed * width + best else best * width + fixed]) best = k
                }
                for (j in profile.indices) {
                    val k = best - reach + j
                    profile[j] = values[if (across) fixed * width + k else k * width + fixed]
                }
                val ends = (profile.take(4) + profile.takeLast(4)).sorted()
                val base = (ends[3] + ends[4]) / 2
                val depth = base - profile[reach]
                if (depth >= .25f) {
                    val half = profile[reach] + depth / 2
                    var l = reach
                    while (l > 0 && profile[l - 1] <= half) l--
                    var r = reach
                    while (r < profile.size - 1 && profile[r + 1] <= half) r++
                    widths += (r - l + 1).toFloat()
                }
                c = best + reach // past this line
            }
        }
        for (k in 1..24) {
            measure(true, k * height / 25, width)
            measure(false, k * width / 25, height)
        }
        if (widths.size < 20) return null
        return widths.sorted()[widths.size / 2]
    }

    /** Seeded line middles as a mask, its bytes, and how far each stands out (255 = the whole paper). */
    private class LineSeeds(val mask: Mat, val bytes: ByteArray, val depth: ByteArray)

    /**
     * Seeds along thin lines too pale for any cut. The middle of a line is darker than the paper on both sides
     * of it, `toneZonesLineSeedStep` photo pixels out, by more than `toneZonesLineSeedDepth`; it is no lighter than
     * `toneZonesLineSeedCeiling`. Such middles count when they run on for `toneZonesLineSeedLength` and come
     * within `toneZonesLineSeedNear` of a dark seed. Grain makes middles too, but only a few pixels long, and an
     * erased pencil line or show-through away from the writing does not qualify. Measured against the reference scanner's
     * page of a book photographed slightly out of focus: the step of 3 found its thin horizontals whole, where 2
     * found them in pieces. Off when the depth is 0.
     */
    private fun lineSeeds(tidy: FloatArray, darkSeeds: Mat, longLines: Mat, width: Int, height: Int, scale: Float, owned: MutableList<Mat>, relative: FloatArray = tidy): LineSeeds? {
        val minDepth = ScanConfig.float("toneZonesLineSeedDepth", .08f)
        if (minDepth <= 0f) return null
        val ceiling = ScanConfig.float("toneZonesLineSeedCeiling", .85f)
        val step = max(2, (ScanConfig.float("toneZonesLineSeedStep", 3f) * photoPixel()).roundToInt())
        val length = ScanConfig.float("toneZonesLineSeedLength", 8f) * scale
        val near = max(1, (ScanConfig.float("toneZonesLineSeedNear", 10f) * scale).roundToInt())
        if (width <= 2 * step || height <= 2 * step) return null
        val offsets = intArrayOf(step, step * width, step * (width + 1), step * (width - 1))
        // Every pixel of the line's middle counts, not only its centre: drawn one pixel wide and widened to three,
        // monospace print came out blotchy and broke the 一 apart again (sweep n, against the reference scanner's page).
        val depth = ByteArray(tidy.size)
        val path = ByteArray(tidy.size)
        // Paler middles too, for straight lines only (see below): a drawing's faint construction lines and dashes.
        val straightCeiling = ScanConfig.float("toneZonesStraightCeiling", .93f)
        val straightDepth = min(minDepth, ScanConfig.float("toneZonesStraightDepth", .05f))
        val loose = ByteArray(tidy.size)
        val looseDepth = ByteArray(tidy.size)
        ParallelFilter.rowBands(height - 2 * step, width) { from, to ->
            for (y in step + from until step + to) {
                var i = y * width + step
                for (x in step until width - step) {
                    val centre = tidy[i]
                    if (centre < max(ceiling, straightCeiling)) {
                        var d = 0f
                        for (o in offsets) d = max(d, min(tidy[i - o], tidy[i + o]) - centre)
                        if (d > straightDepth) {
                            val byte = (d * 255).roundToInt().coerceAtMost(255).toByte()
                            loose[i] = 255.toByte()
                            looseDepth[i] = byte
                            if (centre < ceiling && d > minDepth) {
                                path[i] = 255.toByte()
                                depth[i] = byte
                            }
                        }
                    }
                    i++
                }
            }
        }
        val pathMat = Mat().also(owned::add)
        pathMat.create(height, width, CvType.CV_8UC1)
        pathMat.put(0, 0, path)
        val labels = Mat().also(owned::add)
        val stats = Mat().also(owned::add)
        val centroids = Mat().also(owned::add)
        val count = Imgproc.connectedComponentsWithStats(pathMat, labels, stats, centroids, 8, CvType.CV_32S)
        val table = statsTable(stats, count)
        val zone = Mat().also(owned::add)
        val side = (2 * near + 1).toDouble()
        ParallelFilter.dilate(darkSeeds, zone, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(side, side)).also(owned::add))
        val zoneBytes = ByteArray(tidy.size).also { zone.get(0, 0, it) }
        // A long straight line counts as writing too, near ink or not: the construction lines of a drawing.
        val longBytes = ByteArray(tidy.size).also { longLines.get(0, 0, it) }
        val labelValues = IntArray(tidy.size).also { labels.get(0, 0, it) }
        val nearInk = BooleanArray(count)
        for (part in ParallelFilter.ranges(labelValues.size) { from, to ->
            BooleanArray(count).also { seen ->
                for (i in from until to) if (labelValues[i] != 0 && (zoneBytes[i] != 0.toByte() || longBytes[i] != 0.toByte())) seen[labelValues[i]] = true
            }
        }) for (label in 0 until count) if (part[label]) nearInk[label] = true
        val kept = BooleanArray(count) {
            it != 0 && nearInk[it] && max(table[it * 5 + Imgproc.CC_STAT_WIDTH], table[it * 5 + Imgproc.CC_STAT_HEIGHT]) >= length
        }
        ParallelFilter.ranges(labelValues.size) { from, to ->
            for (i in from until to) {
                if (!kept[labelValues[i]]) {
                    path[i] = 0
                    depth[i] = 0
                }
            }
        }
        // Straight lines, faint or dashed: line middles that lie on a long straight run, gaps allowed, are kept
        // wherever they are. On the owner's three-view drawing the faint construction lines and the dashed hidden
        // lines were pale short pieces, each dropped as show-through, where the reference scanner kept them whole.
        if (ScanConfig.int("toneZonesStraightLines", 1) != 0) {
            val looseMat = Mat().also(owned::add)
            looseMat.create(height, width, CvType.CV_8UC1)
            looseMat.put(0, 0, loose)
            val segments = Mat().also(owned::add)
            val minimum = ScanConfig.float("toneZonesStraightLength", .08f) * min(width, height)
            Imgproc.HoughLinesP(looseMat, segments, 1.0, PI / 360, max(20, (minimum / 3).roundToInt()), minimum.toDouble(), 12.0 * scale)
            if (segments.rows() > 0) {
                val onLine = Mat.zeros(height, width, CvType.CV_8UC1).also(owned::add)
                val seg = IntArray(4)
                val grid = if (ScanConfig.int("toneZonesStraightGrid", 0) != 0) gridAngles(darkSeeds, minimum, scale, owned) else DoubleArray(0)
                val gridTolerance = ScanConfig.float("toneZonesStraightGridTolerance", 2f)
                // A line from the other side of the sheet reaches the camera through the paper, which spreads it and
                // pales it: on the owner's worksheet the other side's projection lines were 1.4-1.6 times as wide as
                // the front's printed grid in the same photo and stood out by 0.075, where a drawing's faint pencil
                // construction lines were as wide as its other thin lines (0.9-1.0), its fine printed lines at most
                // 1.17 and the paler rows of a printed grid 1.33. A straight line wider than `toneZonesThroughWidth`
                // times the page's own thin dark lines is not kept as a line, unless it stands out by
                // `toneZonesThroughDepth` or more (a notebook's faint second margin rule stood out by 0.155).
                val thinWidth = if (ScanConfig.float("toneZonesThroughWidth", 1.4f) > 0f) thinLineWidth(relative, longLines, width, height) else null
                val throughWidth = ScanConfig.float("toneZonesThroughWidth", 1.4f)
                val throughDepth = ScanConfig.float("toneZonesThroughDepth", .15f)
                for (r in 0 until segments.rows()) {
                    segments.get(r, 0, seg)
                    if (grid.isNotEmpty()) {
                        val angle = angleOf(seg)
                        if (grid.any { g -> abs(((angle - g + 270.0) % 180.0) - 90.0) <= gridTolerance }) continue
                    }
                    if (thinWidth != null && !standsOut(looseDepth, width, seg, throughDepth)) {
                        val shape = lineShape(relative, loose, width, height, seg)
                        if (shape != null && shape.depth < throughDepth && shape.width >= throughWidth * thinWidth) continue
                    }
                    Imgproc.line(onLine, Point(seg[0].toDouble(), seg[1].toDouble()), Point(seg[2].toDouble(), seg[3].toDouble()), Scalar(255.0), 3)
                }
                val onBytes = ByteArray(tidy.size).also { onLine.get(0, 0, it) }
                // Beside dark ink the strict depth still applies: there the shallower one thickened print along its
                // rows, while a drawing's faint lines mostly run through open paper.
                val besideInk = Mat().also(owned::add)
                val reach = (2 * max(2, (2 * scale).roundToInt()) + 1).toDouble()
                ParallelFilter.dilate(darkSeeds, besideInk, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(reach, reach)).also(owned::add))
                val besideBytes = ByteArray(tidy.size).also { besideInk.get(0, 0, it) }
                val strictByte = (minDepth * 255).roundToInt()
                ParallelFilter.ranges(onBytes.size) { from, to ->
                    for (i in from until to) {
                        if (onBytes[i] != 0.toByte() && loose[i] != 0.toByte() &&
                            (besideBytes[i] == 0.toByte() || (looseDepth[i].toInt() and 255) > strictByte)) {
                            path[i] = 255.toByte()
                            if ((looseDepth[i].toInt() and 255) > (depth[i].toInt() and 255)) depth[i] = looseDepth[i]
                        }
                    }
                }
            }
        }
        pathMat.put(0, 0, path)
        return LineSeeds(pathMat, path, depth)
    }

    private fun angleOf(seg: IntArray): Double =
        (Math.toDegrees(kotlin.math.atan2((seg[3] - seg[1]).toDouble(), (seg[2] - seg[0]).toDouble())) + 180.0) % 180.0

    /**
     * The directions of a printed grid: angles (degrees, 0-180) along which the page's dark ink has at least
     * `toneZonesStraightGridLines` long straight lines at distinct offsets. On a printed grid sheet the other side's
     * grid shows through as faint lines in exactly these directions, and the straight-line rescue kept them as dashes;
     * a drawing's faint construction lines run through open paper, where there is no grid. Empty off grid pages.
     */
    private fun gridAngles(darkSeeds: Mat, minimum: Float, scale: Float, owned: MutableList<Mat>): DoubleArray {
        val segments = Mat().also(owned::add)
        Imgproc.HoughLinesP(darkSeeds, segments, 1.0, PI / 360, max(20, (minimum / 3).roundToInt()), minimum.toDouble(), 4.0 * scale)
        if (segments.rows() == 0) return DoubleArray(0)
        val need = ScanConfig.int("toneZonesStraightGridLines", 10)
        val spacing = max(4.0, 6.0 * scale)
        val offsets = Array(180) { ArrayList<Double>() }
        val seg = IntArray(4)
        for (r in 0 until segments.rows()) {
            segments.get(r, 0, seg)
            val angle = angleOf(seg)
            val bin = angle.roundToInt() % 180
            val t = Math.toRadians(bin.toDouble())
            // Offset of the line from the origin, across its direction.
            offsets[bin].add(-seg[0] * sin(t) + seg[1] * cos(t))
        }
        val out = ArrayList<Double>()
        for (b in 0 until 180) {
            if (offsets[b].isEmpty() || offsets[b].size < offsets[(b + 179) % 180].size || offsets[b].size < offsets[(b + 1) % 180].size) continue
            val all = (-1..1).flatMap { offsets[(b + it + 180) % 180] }.sorted()
            // Lines: offsets merged within the spacing. A grid has many, evenly spaced; a drawing's views have
            // long lines too, but at uneven offsets.
            val lines = ArrayList<Double>()
            for (v in all) if (lines.isEmpty() || v - lines.last() > spacing) lines.add(v)
            if (lines.size < need) continue
            val gaps = lines.zipWithNext { a, c -> c - a }
            val period = gaps.sorted()[gaps.size / 2]
            val even = gaps.count { g -> abs(g / period - (g / period).roundToInt()) <= .2 && g / period < 3.5 }
            if (even >= ScanConfig.float("toneZonesStraightGridEven", .8f) * gaps.size) out.add(b.toDouble())
        }
        return out.toDoubleArray()
    }

    /**
     * Clears marks that are light all through, which is show-through. Measured on real pages, darkness is
     * the best single cue, and length rescues what it alone would lose: a mark stays if it has a dark
     * core, if a fifth of it is darker than `toneZonesFaintSure`, or if it runs on and a fifth of it is
     * darker than `toneZonesFaintLong`. A cut fitted per page separated these marks no better than this
     * one. Rules are taken out first: a fragment touching a rule would otherwise pass as part of it.
     */
    private fun dropGhostMarks(
        grown: Mat,
        rules: Mat,
        cross: Mat,
        scale: Float,
        relative: FloatArray,
        tidy: FloatArray,
        lineSeeds: ByteArray?,
        owned: MutableList<Mat>,
    ): BooleanArray {
        val faint = BooleanArray(relative.size)
        val ruleZone = Mat().also(owned::add)
        Imgproc.dilate(rules, ruleZone, cross)
        val free = Mat().also(owned::add)
        Core.subtract(grown, ruleZone, free)
        val labels = Mat().also(owned::add)
        val stats = Mat().also(owned::add)
        val centroids = Mat().also(owned::add)
        val count = Imgproc.connectedComponentsWithStats(free, labels, stats, centroids, 8, CvType.CV_32S)
        val table = statsTable(stats, count)
        if (count < 2) return faint
        val labelValues = IntArray(relative.size).also { labels.get(0, 0, it) }
        val coreCut = ScanConfig.float("toneZonesFaintCore", .55f)
        val sureCut = ScanConfig.float("toneZonesFaintSure", .74f)
        val longCut = ScanConfig.float("toneZonesFaintLong", .78f)
        val midCut = ScanConfig.float("toneZonesGhostMid", .70f)
        val core = IntArray(count)
        val sure = IntArray(count)
        val loose = IntArray(count)
        val mid = IntArray(count)
        for (part in ParallelFilter.ranges(labelValues.size) { from, to ->
            val counts = Array(4) { IntArray(count) }
            for (i in from until to) {
                val label = labelValues[i]
                if (label == 0) continue
                val value = relative[i]
                if (value < midCut) counts[0][label]++
                if (value < coreCut) counts[1][label]++
                if (value < sureCut) counts[2][label]++
                if (value < longCut) counts[3][label]++
            }
            counts
        }) for (label in 0 until count) {
            mid[label] += part[0][label]
            core[label] += part[1][label]
            sure[label] += part[2][label]
            loose[label] += part[3][label]
        }
        val longSpan = ScanConfig.int("toneZonesFaintLongSpan", 30) * scale
        val anySpan = ScanConfig.int("toneZonesFaintSpan", 60) * scale
        val ghost =
            BooleanArray(count) {
                if (it == 0) return@BooleanArray false
                val size = table[it * 5 + Imgproc.CC_STAT_AREA]
                val span = max(table[it * 5 + Imgproc.CC_STAT_WIDTH], table[it * 5 + Imgproc.CC_STAT_HEIGHT])
                // Length alone used to keep any mark: the other side's drawing comes through as a pale line broken into
                // pieces, the short pieces went as show-through and the long ones stayed, so the line came out dashed
                // (worksheets 43/47 of 2026-11-15/16). A long mark now needs some of it at least as dark as the
                // long-faint cut, as pencil and faint print have; one that is paler than that all along goes with its
                // pieces.
                val spanKeeps = span >= anySpan && loose[it] >= ScanConfig.float("toneZonesFaintSpanLoose", .05f) * size
                !(core[it] >= 3 || sure[it] >= .2 * size || (loose[it] >= .2 * size && span >= longSpan) || spanKeeps)
            }
        // Pencil comes in groups, while the lightest part of a word can read as show-through. So a light
        // mark beside a sure faint mark stays. Marks beside dark pen do not get this: show-through often
        // lies next to pen, and pencil beside pen is rare.
        val rescue = ScanConfig.float("toneZonesFaintRescue", .82f)
        val anchorShare = ScanConfig.float("toneZonesFaintAnchor", .4f)
        val anchor =
            BooleanArray(count) {
                it != 0 && !ghost[it] && core[it] < 3 &&
                    sure[it] >= anchorShare * table[it * 5 + Imgproc.CC_STAT_AREA] &&
                    table[it * 5 + Imgproc.CC_STAT_AREA] >= 20 * scale * scale
            }
        if (rescue > 0f && anchor.any { it }) {
            val anchors = Mat().also(owned::add)
            anchors.create(grown.rows(), grown.cols(), CvType.CV_8UC1)
            anchors.put(0, 0, labelMask(labelValues) { anchor[it] })
            val radius = (2 * ScanConfig.int("toneZonesFaintRescueReach", 30) * scale + 1).toDouble()
            ParallelFilter.dilate(anchors, anchors, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(radius, radius)).also(owned::add))
            val near = ByteArray(relative.size).also { anchors.get(0, 0, it) }
            val light = IntArray(count)
            val beside = BooleanArray(count)
            for (part in ParallelFilter.ranges(labelValues.size) { from, to ->
                val partLight = IntArray(count)
                val partBeside = BooleanArray(count)
                for (i in from until to) {
                    val label = labelValues[i]
                    if (label == 0) continue
                    if (relative[i] < rescue) partLight[label]++
                    if (near[i] != 0.toByte()) partBeside[label] = true
                }
                partLight to partBeside
            }) for (label in 0 until count) {
                light[label] += part.first[label]
                if (part.second[label]) beside[label] = true
            }
            for (label in 1 until count) {
                if (ghost[label] && beside[label] && light[label] >= .2 * table[label * 5 + Imgproc.CC_STAT_AREA]) ghost[label] = false
            }
        }
        // A short mark that is mostly pale, even with a dark speck in it, is a fragment of show-through.
        // Measured against the reference scanner's page of the same photo. Pencil is safe here: its strokes run longer
        // or are darker through more of their area. Comes after the rescue, which would bring these back.
        val midShare = ScanConfig.float("toneZonesGhostMidShare", .25f)
        val shortSpan = ScanConfig.int("toneZonesGhostSpan", 20) * scale
        for (label in 1 until count) {
            val span = max(table[label * 5 + Imgproc.CC_STAT_WIDTH], table[label * 5 + Imgproc.CC_STAT_HEIGHT])
            if (span < shortSpan && mid[label] < midShare * table[label * 5 + Imgproc.CC_STAT_AREA]) ghost[label] = true
        }
        // the reference scanner keeps only marks with some real darkness: from the owner's photos it removed nearly all
        // pencil. With `toneZonesFaintDropSpan` set, a mark with no dark core goes unless it is that long.
        val dropSpan = ScanConfig.int("toneZonesFaintDropSpan", 0) * scale
        if (dropSpan > 0f) {
            for (label in 1 until count) {
                val span = max(table[label * 5 + Imgproc.CC_STAT_WIDTH], table[label * 5 + Imgproc.CC_STAT_HEIGHT])
                if (core[label] < 3 && span < dropSpan) ghost[label] = true
            }
        }
        // Show-through also lies where the front of the sheet is empty. A mostly pale mark far from any
        // substantial writing goes, even if longer. A mark dark through most of it stays, however alone:
        // that is a page number or a lone digit. A small printed letter must count as writing: with a cut
        // of 30 px no letter of a book's small print did, so whole words read as isolated and were erased.
        val substantial = (ScanConfig.int("toneZonesIsolatedCore", 10) * scale * scale).toInt()
        val anyInk = BooleanArray(count) { it != 0 && core[it] >= substantial }
        if (anyInk.any { it }) {
            val notInk = Mat().also(owned::add)
            notInk.create(grown.rows(), grown.cols(), CvType.CV_8UC1)
            notInk.put(0, 0, labelMask(labelValues) { !anyInk[it] })
            val distance = Mat().also(owned::add)
            Imgproc.distanceTransform(notInk, distance, Imgproc.DIST_L2, 3)
            val distances = FloatArray(relative.size).also { distance.get(0, 0, it) }
            val nearest = FloatArray(count) { Float.MAX_VALUE }
            for (part in ParallelFilter.ranges(labelValues.size) { from, to ->
                FloatArray(count) { Float.MAX_VALUE }.also { least ->
                    for (i in from until to) if (distances[i] < least[labelValues[i]]) least[labelValues[i]] = distances[i]
                }
            }) for (label in 0 until count) if (part[label] < nearest[label]) nearest[label] = part[label]
            val farAway = ScanConfig.int("toneZonesIsolatedAway", 40) * scale
            val longSpanLimit = ScanConfig.int("toneZonesIsolatedSpan", 45) * scale
            val paleShare = ScanConfig.float("toneZonesIsolatedPale", .5f)
            for (label in 1 until count) {
                if (anyInk[label] || nearest[label] < farAway) continue
                val span = max(table[label * 5 + Imgproc.CC_STAT_WIDTH], table[label * 5 + Imgproc.CC_STAT_HEIGHT])
                if (span < longSpanLimit && mid[label] < paleShare * table[label * 5 + Imgproc.CC_STAT_AREA]) ghost[label] = true
            }
        }
        // Show-through presses into the paper's pits, and a full-size photo resolves them as tiny dots as dark as
        // ink, scattered beside the writing. A printed or written dot has a dark core several pixels across; a pit
        // has one or two. Measured on the smoothed pixels, as single raw pixels are grain. Only on a page large
        // enough to resolve pits (`toneZonesSpeckScale` of the reference size): on a small one a real dot is a pixel
        // or two as well. Off when the count is 0.
        val speckCount = ScanConfig.float("toneZonesSpeckCore", 0f) * scale * scale
        if (speckCount > 0f && scale >= ScanConfig.float("toneZonesSpeckScale", 1.5f)) {
            val speckCut = ScanConfig.float("toneZonesSpeckCut", .50f)
            val speckSpan = ScanConfig.int("toneZonesSpeckSpan", 12) * scale
            val dark = IntArray(count)
            for (i in labelValues.indices) if (labelValues[i] != 0 && tidy[i] < speckCut) dark[labelValues[i]]++
            for (label in 1 until count) {
                val span = max(table[label * 5 + Imgproc.CC_STAT_WIDTH], table[label * 5 + Imgproc.CC_STAT_HEIGHT])
                if (span < speckSpan && dark[label] < speckCount) ghost[label] = true
            }
        }
        // A mark with a seeded line in it is a stroke, however pale, so it stays: see [lineSeeds].
        val lined = BooleanArray(count)
        if (lineSeeds != null) for (part in ParallelFilter.ranges(labelValues.size) { from, to ->
            BooleanArray(count).also { seen -> for (i in from until to) if (lineSeeds[i] != 0.toByte()) seen[labelValues[i]] = true }
        }) for (label in 0 until count) if (part[label]) lined[label] = true
        for (label in 1 until count) if (lined[label]) ghost[label] = false
        // A lined mark is drawn on the narrow curve, with its centre line on top: crisp, where the faint curve
        // drew a blurred stroke's halo grey.
        ParallelFilter.ranges(labelValues.size) { from, to ->
            for (i in from until to) {
                val label = labelValues[i]
                if (label != 0 && core[label] < 3 && !lined[label]) faint[i] = true
            }
        }
        if (ghost.none { it }) return faint
        val bytes = ByteArray(relative.size).also { grown.get(0, 0, it) }
        ParallelFilter.ranges(labelValues.size) { from, to -> for (i in from until to) if (ghost[labelValues[i]]) bytes[i] = 0 }
        grown.put(0, 0, bytes)
        return faint
    }

    /** A Gaussian mean of [values] over the pixels [weight] selects, at a quarter of the size for speed. */
    private fun spread(values: FloatArray, weight: FloatArray, width: Int, height: Int, sigma: Double, owned: MutableList<Mat>): Pair<FloatArray, FloatArray> {
        val level = Mat().also(owned::add)
        val share = Mat().also(owned::add)
        val small = Size(max(8.0, width / 4.0), max(8.0, height / 4.0))
        val full = Size(width.toDouble(), height.toDouble())
        fun blur(data: FloatArray, into: Mat): FloatArray {
            val mat = Mat(height, width, CvType.CV_32FC1)
            val reduced = Mat()
            try {
                mat.put(0, 0, data)
                Imgproc.resize(mat, reduced, small, 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.GaussianBlur(reduced, reduced, Size(0.0, 0.0), max(1.0, sigma / 4))
                Imgproc.resize(reduced, into, full, 0.0, 0.0, Imgproc.INTER_LINEAR)
                return FloatArray(data.size).also { into.get(0, 0, it) }
            } finally {
                mat.release()
                reduced.release()
            }
        }
        return blur(values, level) to blur(weight, share)
    }

    /** The ratio of the rule pixels near each pixel; 1 where there are none. */
    private fun ruleLevel(relative: FloatArray, rules: Mat, width: Int, height: Int, scale: Float, owned: MutableList<Mat>): FloatArray {
        val bytes = ByteArray(relative.size).also { rules.get(0, 0, it) }
        val weight = FloatArray(relative.size)
        val level = FloatArray(relative.size)
        ParallelFilter.ranges(relative.size) { from, to ->
            for (i in from until to) if (bytes[i] != 0.toByte()) {
                weight[i] = 1f
                level[i] = relative[i]
            }
        }
        val sigma = max(8.0, ScanConfig.float("toneZonesRuleWindow", 60f) * scale.toDouble())
        val (values, shares) = spread(level, weight, width, height, sigma, owned)
        val result = FloatArray(relative.size)
        ParallelFilter.ranges(result.size) { from, to ->
            for (i in from until to) result[i] = if (shares[i] > 1e-5f) values[i] / shares[i] else 1f
        }
        return result
    }

    /**
     * The tone that counts as full ink at each pixel: a share of the level of clearly dark pixels near
     * it. Where few are near, it fades to the page's own level.
     */
    private fun inkFloor(
        relative: FloatArray,
        width: Int,
        height: Int,
        scale: Float,
        owned: MutableList<Mat>,
    ): FloatArray {
        val inkCut = ScanConfig.float("toneZonesInkCut", .55f)
        val depth = ScanConfig.float("toneZonesInkDepth", .72f)
        val sigma = max(4.0, ScanConfig.float("toneZonesInkWindow", 28f) * scale.toDouble())
        val histogram = IntArray(256)
        var inkCount = 0
        val weightValues = FloatArray(relative.size)
        val levelValues = FloatArray(relative.size)
        for (part in ParallelFilter.ranges(relative.size) { from, to ->
            IntArray(256).also { counts ->
                for (i in from until to) {
                    if (relative[i] < inkCut) {
                        weightValues[i] = 1f
                        levelValues[i] = relative[i]
                        counts[(relative[i] * 255f).roundToInt().coerceIn(0, 255)]++
                    }
                }
            }
        }) for (v in 0 until 256) {
            histogram[v] += part[v]
            inkCount += part[v]
        }
        val pageLevel = if (inkCount > 100) percentile(histogram, inkCount, .5f) / 255f else .45f
        val (levels, shares) = spread(levelValues, weightValues, width, height, sigma, owned)
        val floor = FloatArray(relative.size)
        ParallelFilter.ranges(floor.size) { from, to ->
            for (i in from until to) {
                val share = shares[i]
                val trust = (share / .01f).coerceIn(0f, 1f)
                floor[i] = depth * (trust * levels[i] / max(share, 1e-4f) + (1f - trust) * pageLevel)
            }
        }
        return floor
    }

    /**
     * Clears marks that are not writing from [keep], in place: what a crop leaves along the frame.
     * A mark must sit wholly in the outer band of one side. There it goes if it is mostly skin or
     * wood coloured (fingers, a desk), or if it touches the frame and is thick or saturated (a dark
     * wedge, a coloured tab). Handwriting at the edge is thin and dark, so it stays.
     */
    private fun dropEdgeDebris(page: Mat, keep: Mat, thin: Int, owned: MutableList<Mat>, relative: FloatArray? = null) {
        val width = keep.cols()
        val height = keep.rows()
        val exact = frameIsExact()
        val band = if (exact) ScanConfig.float("toneZonesExactBand", .03f) else ScanConfig.float("toneZonesEdgeBand", .12f)
        val labels = Mat().also(owned::add)
        val stats = Mat().also(owned::add)
        val centroids = Mat().also(owned::add)
        val count = Imgproc.connectedComponentsWithStats(keep, labels, stats, centroids, 8, CvType.CV_32S)
        val table = statsTable(stats, count)
        if (count < 2) return
        val thick = Mat().also(owned::add)
        ParallelFilter.morphology(
            keep,
            thick,
            Imgproc.MORPH_OPEN,
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(thin.toDouble(), thin.toDouble()))
                .also(owned::add),
        )
        val labelValues = IntArray(width * height).also { labels.get(0, 0, it) }
        val thickBytes = ByteArray(labelValues.size).also { thick.get(0, 0, it) }
        val colour = if (page.channels() == 3) ByteArray(labelValues.size * 3).also { page.get(0, 0, it) } else null
        // Skin and wood are strongly saturated. Ink under warm light is only as warm as the paper, and
        // judging it by its tint alone deleted real letters, worst of all along a shadow's edge.
        val warmSaturation = ScanConfig.float("toneZonesWarmSaturation", .45f)
        fun warmAt(i: Int): Boolean {
            val pixels = colour ?: return false
            val r = pixels[3 * i].toInt() and 255
            val g = pixels[3 * i + 1].toInt() and 255
            val b = pixels[3 * i + 2].toInt() and 255
            return r >= 70 && r >= g && g > b && (r - b) >= warmSaturation * r
        }
        // Only marks wholly in the outer band are judged below, so only their pixels are counted.
        val inBand = BooleanArray(count) { label ->
            if (label == 0) return@BooleanArray false
            val x0 = table[label * 5 + Imgproc.CC_STAT_LEFT]
            val y0 = table[label * 5 + Imgproc.CC_STAT_TOP]
            val x1 = x0 + table[label * 5 + Imgproc.CC_STAT_WIDTH]
            val y1 = y0 + table[label * 5 + Imgproc.CC_STAT_HEIGHT]
            x1 <= width * band ||
                x0 >= width * (1 - band) ||
                y1 <= height * band ||
                y0 >= height * (1 - band)
        }
        val area = IntArray(count)
        val warm = IntArray(count)
        val saturated = IntArray(count)
        val heavy = IntArray(count)
        for (part in ParallelFilter.ranges(labelValues.size) { from, to ->
            val counts = Array(4) { IntArray(count) }
            for (i in from until to) {
                val label = labelValues[i]
                if (!inBand[label]) continue
                counts[0][label]++
                if (thickBytes[i] != 0.toByte()) counts[1][label]++
                if (colour != null) {
                    val r = colour[3 * i].toInt() and 255
                    val g = colour[3 * i + 1].toInt() and 255
                    val b = colour[3 * i + 2].toInt() and 255
                    if (warmAt(i)) counts[2][label]++
                    if (max(r, max(g, b)) - min(r, min(g, b)) >= (if (exact) 25 else 40)) counts[3][label]++
                }
            }
            counts
        }) for (label in 0 until count) {
            area[label] += part[0][label]
            heavy[label] += part[1][label]
            warm[label] += part[2][label]
            saturated[label] += part[3][label]
        }
        val drop = BooleanArray(count)
        for (label in 1 until count) {
            val x0 = table[label * 5 + Imgproc.CC_STAT_LEFT]
            val y0 = table[label * 5 + Imgproc.CC_STAT_TOP]
            val x1 = x0 + table[label * 5 + Imgproc.CC_STAT_WIDTH]
            val y1 = y0 + table[label * 5 + Imgproc.CC_STAT_HEIGHT]
            if (!inBand[label]) continue
            val touches = x0 <= 1 || y0 <= 1 || x1 >= width - 1 || y1 >= height - 1
            val total = area[label].toFloat()
            // Right at the frame, colour means desk or a neighbouring scrap even when it stops short of the
            // edge. Known cost: a coloured-pen note in this outermost 3% goes too.
            val outermost = ScanConfig.float("toneZonesExactBand", .03f)
            val atFrame = x1 <= width * outermost || x0 >= width * (1 - outermost) || y1 <= height * outermost || y0 >= height * (1 - outermost)
            // Just the tip of something beyond the crop: the sheet behind, cut off by the frame.
            val sliverX = width * ScanConfig.float("toneZonesEdgeSliver", .012f)
            val sliverY = height * ScanConfig.float("toneZonesEdgeSliver", .012f)
            val sliver =
                (x0 <= 1 && x1 <= sliverX) ||
                    (x1 >= width - 1 && x0 >= width - sliverX) ||
                    (y0 <= 1 && y1 <= sliverY) ||
                    (y1 >= height - 1 && y0 >= height - sliverY)
            drop[label] =
                sliver ||
                    warm[label] / total > .5f ||
                    (touches && heavy[label] / total > .2f) ||
                    ((touches || exact || atFrame) && saturated[label] / total > .5f)
        }
        val keepBytes = ByteArray(labelValues.size).also { keep.get(0, 0, it) }
        // A desk strip or a finger is often joined to a rule, which makes the whole mark too long to
        // judge above. In the outermost strip, judge each pixel: rules there are thin and neutral.
        val strip = if (exact) ScanConfig.float("toneZonesExactStrip", .006f) else ScanConfig.float("toneZonesEdgeStrip", .04f)
        val stripX = (width * strip).toInt()
        val stripY = (height * strip).toInt()
        ParallelFilter.ranges(labelValues.size) { from, to ->
            for (i in from until to) {
                if (drop[labelValues[i]]) {
                    keepBytes[i] = 0
                    continue
                }
                val x = i % width
                val y = i / width
                if (x >= stripX && x < width - stripX && y >= stripY && y < height - stripY) continue
                if (thickBytes[i] != 0.toByte() || warmAt(i)) keepBytes[i] = 0
            }
        }
        keep.put(0, 0, keepBytes)
        if (colour != null) dropWarmBlobs(keep, thick, thin, ::warmAt, owned)
        dropFrameLines(keep, owned, relative)
    }

    /**
     * Clears thick, strongly warm blobs wherever they lie: a finger or the desk. The thick part is judged
     * alone, because a finger often touches a ruled line, which makes the whole mark mostly neutral. The
     * warm rim around it goes too, while the rule stays. Known cost: a large red or orange printed shape
     * goes as well; red pen is thin and stays.
     */
    private fun dropWarmBlobs(keep: Mat, thick: Mat, thin: Int, warmAt: (Int) -> Boolean, owned: MutableList<Mat>) {
        val width = keep.cols()
        val labels = Mat().also(owned::add)
        val stats = Mat().also(owned::add)
        val centroids = Mat().also(owned::add)
        val count = Imgproc.connectedComponentsWithStats(thick, labels, stats, centroids, 8, CvType.CV_32S)
        val table = statsTable(stats, count)
        if (count < 2) return
        val labelValues = IntArray(width * keep.rows()).also { labels.get(0, 0, it) }
        val warm = IntArray(count)
        val area = IntArray(count)
        for (i in labelValues.indices) {
            val label = labelValues[i]
            if (label == 0) continue
            area[label]++
            if (warmAt(i)) warm[label]++
        }
        val share = ScanConfig.float("toneZonesWarmBlobShare", .3f)
        val blob = BooleanArray(count) { it != 0 && warm[it] >= share * area[it] }
        if (blob.none { it }) return
        val blobMask = Mat().also(owned::add)
        blobMask.create(keep.rows(), width, CvType.CV_8UC1)
        val size = ((4 * thin + 1) / 4 or 1).toDouble()
        val near = Mat().also(owned::add)
        val smallMask = Mat().also(owned::add)
        val ellipse = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(size, size)).also(owned::add)
        val smallSize = Size(max(8.0, width / 4.0), max(8.0, keep.rows() / 4.0))
        fun grow() {
            blobMask.put(0, 0, ByteArray(labelValues.size) { if (blob[labelValues[it]]) 255.toByte() else 0.toByte() })
            // Any blob pixel marks its small cell, so nothing is lost by working at a quarter of the size.
            Imgproc.resize(blobMask, smallMask, smallSize, 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.threshold(smallMask, smallMask, 0.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.dilate(smallMask, smallMask, ellipse)
            Imgproc.resize(smallMask, near, keep.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
        }
        grow()
        // A finger's shadow is thick and grey, so colour alone leaves it behind. It touches the finger.
        val beside = ByteArray(labelValues.size).also { near.get(0, 0, it) }
        var joined = false
        for (i in labelValues.indices) {
            val label = labelValues[i]
            if (label != 0 && !blob[label] && beside[i] != 0.toByte()) {
                blob[label] = true
                joined = true
            }
        }
        if (joined) grow()
        val nearBytes = ByteArray(labelValues.size).also { near.get(0, 0, it) }
        val keepBytes = ByteArray(labelValues.size).also { keep.get(0, 0, it) }
        for (i in labelValues.indices) {
            if (keepBytes[i] == 0.toByte()) continue
            if (blob[labelValues[i]] || (nearBytes[i] != 0.toByte() && warmAt(i))) keepBytes[i] = 0
        }
        keep.put(0, 0, keepBytes)
    }

    /**
     * Clears long lines running along the frame in its outermost strip: the edge of a stack of pages
     * or of the sheet itself. A ruled line meets the frame instead of following it, so it stays.
     * A printed border that close to the edge is told apart by [relative]: it is a dark ink line with paper on
     * both sides, where a sheet's edge has the desk or a shadow beyond it and the edge of a sheet below is a
     * soft pale step. Such a line stays (`toneZonesFrameLinePrinted`).
     */
    private fun dropFrameLines(keep: Mat, owned: MutableList<Mat>, relative: FloatArray? = null) {
        val width = keep.cols()
        val height = keep.rows()
        val exact = frameIsExact()
        // On a page framed by [SheetEdge] the sheets below cast lines a little farther in, so long straight
        // lines are cleared from a wider strip there. Stray pieces are judged in the narrow strip only:
        // in a wide one they took real writing near the edge.
        val strayStrip = ScanConfig.float("toneZonesEdgeLineStrip", .025f)
        val strip =
            if (exact) ScanConfig.float("toneZonesExactLineStrip", .012f)
            else if (frameIsStack()) ScanConfig.float("toneZonesStackLineStrip", .05f)
            else strayStrip
        // A sheet's lower edge casts a line a little way in, and it runs far along the frame. Only a long
        // run goes, so short writing near the top or bottom stays.
        val stripAcross = if (exact) ScanConfig.float("toneZonesExactLineStripY", .025f) else strip
        val run = ScanConfig.float("toneZonesEdgeLineRun", .05f)
        val runAcross = if (exact) ScanConfig.float("toneZonesExactLineRun", .10f) else run
        val stripX = (width * strip).toInt().coerceAtLeast(1)
        val stripY = (height * stripAcross).toInt().coerceAtLeast(1)
        fun rect(w: Int, h: Int) =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(w.toDouble(), h.toDouble())).also(owned::add)
        val printedCheck = relative != null && ScanConfig.int("toneZonesFrameLinePrinted", 1) != 0
        val sideOffset = max(6, (4 * max(width, height) / REFERENCE_EDGE).roundToInt())
        val sidePaper = ScanConfig.float("toneZonesFrameLinePaper", .9f)
        val printedCore = ScanConfig.float("toneZonesFrameLineCore", .65f)
        // A sheet's edge in a stack also has paper on both sides and a dark core. What a printed border has and an
        // edge does not is the page's own lines meeting it from inside: the worksheet's grid border had dozens.
        val printedCrossings = ScanConfig.int("toneZonesFrameLineCrossings", 3)
        val crossingDark = ScanConfig.float("toneZonesFrameLineCrossingDark", .8f)
        // Separate dark stretches along a line, a gap of 2 or more ending one.
        fun runs(marks: BooleanArray?): Int {
            if (marks == null) return 0
            var count = 0
            var gap = 2
            for (mark in marks) {
                if (mark) {
                    if (gap >= 2) count++
                    gap = 0
                } else gap++
            }
            return count
        }
        // Removes from [line] (a mask over [area]) each run that is printed: paper on both sides, a dark core,
        // and at least `toneZonesFrameLineCrossings` lines meeting it from the page's side.
        fun keepPrinted(line: Mat, area: Rect, vertical: Boolean) {
            val values = relative ?: return
            val labels = Mat()
            val stats = Mat()
            val centroids = Mat()
            try {
                val count = Imgproc.connectedComponentsWithStats(line, labels, stats, centroids, 8, CvType.CV_32S)
                if (count < 2) return
                val labelValues = IntArray(area.width * area.height).also { labels.get(0, 0, it) }
                val sides = DoubleArray(count * 2)
                val sideCount = IntArray(count * 2)
                val core = Array(count) { ArrayList<Float>() }
                val alongSize = if (vertical) height else width
                val meets = HashMap<Int, BooleanArray>()
                for (i in labelValues.indices) {
                    val label = labelValues[i]
                    if (label == 0) continue
                    val x = area.x + i % area.width
                    val y = area.y + i / area.width
                    core[label] += values[y * width + x]
                    // Just inside the line, towards the middle of the page: dark where a line of the page meets it.
                    val inside =
                        if (vertical) (if (x < width / 2) x + sideOffset else x - sideOffset)
                        else (if (y < height / 2) y + sideOffset else y - sideOffset)
                    val ix = if (vertical) inside else x
                    val iy = if (vertical) y else inside
                    if (ix in 0 until width && iy in 0 until height && values[iy * width + ix] < crossingDark)
                        meets.getOrPut(label) { BooleanArray(alongSize) }[if (vertical) y else x] = true
                    for (side in 0..1) {
                        val offset = if (side == 0) -sideOffset else sideOffset
                        val sx = if (vertical) x + offset else x
                        val sy = if (vertical) y else y + offset
                        if (sx !in 0 until width || sy !in 0 until height) continue
                        sides[label * 2 + side] += values[sy * width + sx].toDouble()
                        sideCount[label * 2 + side]++
                    }
                }
                val printed = BooleanArray(count) { label ->
                    label != 0 && sideCount[label * 2] > 0 && sideCount[label * 2 + 1] > 0 &&
                        sides[label * 2] / sideCount[label * 2] >= sidePaper &&
                        sides[label * 2 + 1] / sideCount[label * 2 + 1] >= sidePaper &&
                        core[label].sorted().let { it[it.size / 2] } <= printedCore &&
                        (printedCrossings <= 0 || runs(meets[label]) >= printedCrossings)
                }
                if (printed.none { it }) return
                val bytes = ByteArray(labelValues.size).also { line.get(0, 0, it) }
                for (i in labelValues.indices) if (printed[labelValues[i]]) bytes[i] = 0
                line.put(0, 0, bytes)
            } finally {
                labels.release()
                stats.release()
                centroids.release()
            }
        }
        fun clear(area: Rect, across: Mat, along: Mat, vertical: Boolean) {
            val region = keep.submat(area)
            val line = Mat()
            try {
                // Thickened across the run first, so a slightly tilted edge still forms one straight run.
                Imgproc.dilate(region, line, across)
                Imgproc.morphologyEx(line, line, Imgproc.MORPH_OPEN, along)
                if (printedCheck) {
                    // Judge the run on the line itself, not on its thickened copy.
                    Core.bitwise_and(line, region, line)
                    keepPrinted(line, area, vertical)
                }
                Core.subtract(region, line, region)
            } finally {
                line.release()
                region.release()
            }
        }
        val vertical = rect(1, (height * run).toInt().coerceAtLeast(3))
        val horizontal = rect((width * runAcross).toInt().coerceAtLeast(3), 1)
        clear(Rect(0, 0, stripX, height), rect(5, 1), vertical, true)
        clear(Rect(width - stripX, 0, stripX, height), rect(5, 1), vertical, true)
        clear(Rect(0, 0, width, stripY), rect(1, 5), horizontal, false)
        clear(Rect(0, height - stripY, width, stripY), rect(1, 5), horizontal, false)
        // With an exact frame, the sheet's own edge can still leave a dashed line a few pixels in, where the
        // crop ran a hair outside it. Only pieces that run along the frame go: a dot written in the margin stays.
        val exactStray = if (exact) ScanConfig.float("toneZonesExactStray", .008f) else 0f
        if (exact && exactStray <= 0f) return
        // What is left of a broken edge line lies wholly inside the strip. A ruled line reaches inward.
        val strayX = (width * (if (exact) exactStray else strayStrip)).toInt().coerceAtLeast(1)
        val strayY = (height * (if (exact) exactStray else strayStrip)).toInt().coerceAtLeast(1)
        val labels = Mat().also(owned::add)
        val stats = Mat().also(owned::add)
        val centroids = Mat().also(owned::add)
        val count = Imgproc.connectedComponentsWithStats(keep, labels, stats, centroids, 8, CvType.CV_32S)
        val table = statsTable(stats, count)
        val stray =
            BooleanArray(count) {
                if (it == 0) return@BooleanArray false
                val x0 = table[it * 5 + Imgproc.CC_STAT_LEFT]
                val y0 = table[it * 5 + Imgproc.CC_STAT_TOP]
                val x1 = x0 + table[it * 5 + Imgproc.CC_STAT_WIDTH]
                val y1 = y0 + table[it * 5 + Imgproc.CC_STAT_HEIGHT]
                val w = x1 - x0
                val h = y1 - y0
                if (exact) ((x1 <= strayX || x0 >= width - strayX) && h >= 2 * w) || ((y1 <= strayY || y0 >= height - strayY) && w >= 2 * h)
                else x1 <= strayX || x0 >= width - strayX || y1 <= strayY || y0 >= height - strayY
            }
        if (stray.none { it }) return
        val labelValues = IntArray(width * height).also { labels.get(0, 0, it) }
        val bytes = ByteArray(labelValues.size).also { keep.get(0, 0, it) }
        for (i in labelValues.indices) if (stray[labelValues[i]]) bytes[i] = 0
        keep.put(0, 0, bytes)
    }

    /**
     * Rule stubs at the sheet's left and right edges: the rule ends of the sheets beneath a pile, or of the facing page
     * across a gutter, that the crop takes in. The top sheet's own rules are redrawn as tracks; what is left of the
     * others is a short thin mark at a rule's height touching the frame, one per rule (the owner's pages 41-46 of
     * 2026-11-15/16). A dark component is cleared to paper only when it lies in the outer 5 % of the width, reaches
     * the frame (within 1.2 % of the width), is at most a third of a rule spacing tall and wide no more than the strip,
     * sits within 30 % of a spacing of a rule's height, and touches no redrawn rule: page numbers and margin writing are
     * taller, writing that reaches in is wider, and a rule running to the edge is one component with its redrawn line.
     * Returns the number of cleared components.
     */
    private fun dropEdgeStubs(output: ByteArray, tracks: List<RuleTrack>, width: Int, height: Int, paper: Float, owned: MutableList<Mat>): Int {
        if (tracks.size < 6) return 0
        val rows = tracks.map { it.heights[it.heights.size / 2] }.sorted()
        val gaps = rows.zipWithNext { a, b -> b - a }.filter { it > 2f }.sorted()
        if (gaps.size < 4) return 0
        val spacing = gaps[gaps.size / 2]
        val strip = (.05f * width).toInt()
        val touch = max(3, (.012f * width).toInt())
        val dark = (paper * .6f).toInt()
        val mask = Mat(height, width, CvType.CV_8U).also(owned::add)
        mask.put(0, 0, ByteArray(output.size) { i -> if ((output[i].toInt() and 255) < dark) 1 else 0 })
        val labels = Mat().also(owned::add)
        val stats = Mat().also(owned::add)
        val centroids = Mat().also(owned::add)
        val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S)
        if (count <= 1) return 0
        val table = statsTable(stats, count)
        // Height of the nearest rule at column x: the track's own height inside it, its end height beyond.
        fun ruleNear(x: Int, y: Float): Boolean =
            tracks.any { t ->
                val k = (x - t.start).coerceIn(0, t.heights.size - 1)
                abs(t.heights[k] - y) <= .3f * spacing
            }
        val clear = BooleanArray(count)
        var cleared = 0
        for (c in 1 until count) {
            val left = table[c * 5]
            val top = table[c * 5 + 1]
            val w = table[c * 5 + 2]
            val h = table[c * 5 + 3]
            val atLeft = left <= touch && left + w <= strip
            val atRight = left + w >= width - touch && left >= width - strip
            if (!atLeft && !atRight) continue
            if (h > max(3f, spacing / 3f)) continue
            if (!ruleNear(if (atLeft) left + w else left, top + h / 2f)) continue
            clear[c] = true
            cleared++
        }
        if (cleared == 0) return 0
        val label = IntArray(output.size).also { labels.get(0, 0, it) }
        val tone = paper.roundToInt().coerceIn(0, 255).toByte()
        for (i in output.indices) if (clear[label[i]]) output[i] = tone
        return cleared
    }

    private fun statsTable(stats: Mat, count: Int) = IntArray(count * 5).also { stats.get(0, 0, it) }

    /** Clears 8-connected components of [mask] with fewer than [minimum] pixels, in place. */
    private fun dropSmall(mask: Mat, minimum: Int, owned: MutableList<Mat>) {
        val labels = Mat().also(owned::add)
        val stats = Mat().also(owned::add)
        val centroids = Mat().also(owned::add)
        val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S)
        val table = statsTable(stats, count)
        val keep = BooleanArray(count) { it != 0 && table[it * 5 + Imgproc.CC_STAT_AREA] >= minimum }
        val labelValues = IntArray(mask.rows() * mask.cols()).also { labels.get(0, 0, it) }
        mask.put(0, 0, labelMask(labelValues) { keep[it] })
    }

    /** 255 where [chosen] holds for the pixel's label, else 0; per pixel on the filter threads. */
    private inline fun labelMask(labelValues: IntArray, crossinline chosen: (Int) -> Boolean): ByteArray {
        val out = ByteArray(labelValues.size)
        ParallelFilter.ranges(out.size) { from, to -> for (i in from until to) if (chosen(labelValues[i])) out[i] = 255.toByte() }
        return out
    }

    /**
     * Straight runs of at least [run] px in [candidates], minus wide blobs, spanning at least
     * [length] of the page. Only straight runs count: a ghost touching a rule is connected to it, so
     * plain connected components would let it inherit the rule's length.
     */
    private fun thinLongComponents(
        candidates: Mat,
        thin: Int,
        run: Int,
        length: Float,
        cross: Mat,
        owned: MutableList<Mat>,
    ): Mat {
        fun rect(width: Int, height: Int) =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(width.toDouble(), height.toDouble()))
                .also(owned::add)
        val blob = Mat().also(owned::add)
        ParallelFilter.morphology(candidates, blob, Imgproc.MORPH_OPEN, rect(thin, thin))
        Imgproc.dilate(blob, blob, cross)
        val fine = Mat().also(owned::add)
        Core.bitwise_not(blob, blob)
        Core.bitwise_and(candidates, blob, fine)
        // Thicken across the run first, so a slightly tilted rule still forms one straight run.
        val horizontal = Mat().also(owned::add)
        Imgproc.dilate(fine, horizontal, rect(1, 3))
        ParallelFilter.morphology(horizontal, horizontal, Imgproc.MORPH_OPEN, rect(run, 1))
        val vertical = Mat().also(owned::add)
        Imgproc.dilate(fine, vertical, rect(3, 1))
        ParallelFilter.morphology(vertical, vertical, Imgproc.MORPH_OPEN, rect(1, run))
        Core.bitwise_or(horizontal, vertical, horizontal)
        Core.bitwise_and(horizontal, fine, fine)
        val labels = Mat().also(owned::add)
        val stats = Mat().also(owned::add)
        val centroids = Mat().also(owned::add)
        val count = Imgproc.connectedComponentsWithStats(fine, labels, stats, centroids, 8, CvType.CV_32S)
        val table = statsTable(stats, count)
        val minimumWidth = length * candidates.cols()
        val minimumHeight = length * candidates.rows()
        val long =
            BooleanArray(count) {
                it != 0 &&
                    (table[it * 5 + Imgproc.CC_STAT_WIDTH] >= minimumWidth ||
                        table[it * 5 + Imgproc.CC_STAT_HEIGHT] >= minimumHeight)
            }
        val labelValues = IntArray(candidates.rows() * candidates.cols()).also { labels.get(0, 0, it) }
        val result = Mat().also(owned::add)
        result.create(candidates.rows(), candidates.cols(), CvType.CV_8UC1)
        result.put(0, 0, labelMask(labelValues) { long[it] })
        return result
    }

    private fun smoothStep(x: Float): Float {
        val value = x.coerceIn(0f, 1f)
        return value * value * (3f - 2f * value)
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

    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000.0
}
