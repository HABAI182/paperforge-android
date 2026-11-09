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
            for (value in fieldValues) fieldHistogram[value.roundToInt().coerceIn(0, 255)]++
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
            val raw =
                FloatArray(grayBytes.size) {
                    (grayBytes[it].toInt() and 255) / max(1f, fieldValues[it])
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
            fun threshold(cut: Float) =
                mat().also { mask ->
                    mask.create(height, width, CvType.CV_8UC1)
                    mask.put(
                        0,
                        0,
                        ByteArray(relative.size) {
                            if (relative[it] < cut) 255.toByte() else 0.toByte()
                        },
                    )
                }
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
            val crisp = mat()
            crisp.create(height, width, CvType.CV_8UC1)
            crisp.put(
                0,
                0,
                ByteArray(relative.size) {
                    if (raw[it] < faintCut && softValues[it] - raw[it] > crispness) 255.toByte()
                    else 0.toByte()
                },
            )
            dropSmall(crisp, max(2, (6 * scale * scale).roundToInt()), owned)
            lap("crisp")
            val darkSeeds = mat().also(seeds::copyTo)
            Core.bitwise_or(seeds, crisp, seeds)
            // In a soft photo the thin horizontals of Song type are paler than any seed cut and too blurred to be
            // crisp, so the 一 of 插入一个 and the bars of 中 were never seeded and came out as gaps. See [lineSeeds].
            // Ruled paper is thin, and the writing on its other side shows through as lines beside the front's
            // writing: measured against the reference scanner, line seeds there added ten wrong marks for every right one.
            val lineSeed =
                if (ruled && ScanConfig.int("toneZonesLineSeedOnRuled", 0) == 0) null
                else lineSeeds(tidyValues, darkSeeds, rules, width, height, scale, owned)
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
                dropEdgeDebris(page, keep, thin, owned)
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
            val across = if (exactFrame) trackedAcross else trackedAcross.filterNot(::nearTopOrBottom)
            val drawnAcross = across.filterNot(::nearTopOrBottom)
            // A faint long vertical is a crease or the edge of a sheet, not a printed line.
            val faintDown = ScanConfig.float("toneZonesRuleFaintDown", .80f)
            val drawnDown = down.filter { track -> track.level < faintDown && track.heights[track.heights.size / 2].let { it > stripDown && it < width - stripDown } }
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
            down.forEach { clear(it, true) }
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
            var inkShare = 0
            var ruleShare = 0
            var lifted = 0.0
            for (i in relative.indices) {
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
            lap("drawRules")
            when (channels) {
                1 -> page.put(0, 0, output)
                else -> {
                    // A coloured mark gets one luma delta on all channels, so it keeps its hue. Weak colour
                    // is only the cast of the light, so that ink and what was not kept become neutral.
                    val colour = ByteArray(output.size * 3).also { page.get(0, 0, it) }
                    val castLow = ScanConfig.int("toneZonesCastLow", 50).toFloat()
                    val castHigh = ScanConfig.int("toneZonesCastHigh", 90).toFloat()
                    for (i in output.indices) {
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
        for (y in 1 until height - 1) {
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
        }
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
    private fun lineSeeds(tidy: FloatArray, darkSeeds: Mat, longLines: Mat, width: Int, height: Int, scale: Float, owned: MutableList<Mat>): LineSeeds? {
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
        for (y in step until height - step) {
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
        for (i in labelValues.indices) if (labelValues[i] != 0 && (zoneBytes[i] != 0.toByte() || longBytes[i] != 0.toByte())) nearInk[labelValues[i]] = true
        val kept = BooleanArray(count) {
            it != 0 && nearInk[it] && max(table[it * 5 + Imgproc.CC_STAT_WIDTH], table[it * 5 + Imgproc.CC_STAT_HEIGHT]) >= length
        }
        for (i in labelValues.indices) {
            if (!kept[labelValues[i]]) {
                path[i] = 0
                depth[i] = 0
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
                for (r in 0 until segments.rows()) {
                    segments.get(r, 0, seg)
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
                for (i in onBytes.indices) {
                    if (onBytes[i] != 0.toByte() && loose[i] != 0.toByte() &&
                        (besideBytes[i] == 0.toByte() || (looseDepth[i].toInt() and 255) > strictByte)) {
                        path[i] = 255.toByte()
                        if ((looseDepth[i].toInt() and 255) > (depth[i].toInt() and 255)) depth[i] = looseDepth[i]
                    }
                }
            }
        }
        pathMat.put(0, 0, path)
        return LineSeeds(pathMat, path, depth)
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
        for (i in labelValues.indices) {
            val label = labelValues[i]
            if (label == 0) continue
            val value = relative[i]
            if (value < midCut) mid[label]++
            if (value < coreCut) core[label]++
            if (value < sureCut) sure[label]++
            if (value < longCut) loose[label]++
        }
        val longSpan = ScanConfig.int("toneZonesFaintLongSpan", 30) * scale
        val anySpan = ScanConfig.int("toneZonesFaintSpan", 60) * scale
        val ghost =
            BooleanArray(count) {
                if (it == 0) return@BooleanArray false
                val size = table[it * 5 + Imgproc.CC_STAT_AREA]
                val span = max(table[it * 5 + Imgproc.CC_STAT_WIDTH], table[it * 5 + Imgproc.CC_STAT_HEIGHT])
                !(core[it] >= 3 || sure[it] >= .2 * size || (loose[it] >= .2 * size && span >= longSpan) || span >= anySpan)
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
            anchors.put(0, 0, ByteArray(relative.size) { if (anchor[labelValues[it]]) 255.toByte() else 0.toByte() })
            val radius = (2 * ScanConfig.int("toneZonesFaintRescueReach", 30) * scale + 1).toDouble()
            Imgproc.dilate(anchors, anchors, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(radius, radius)).also(owned::add))
            val near = ByteArray(relative.size).also { anchors.get(0, 0, it) }
            val light = IntArray(count)
            val beside = BooleanArray(count)
            for (i in labelValues.indices) {
                val label = labelValues[i]
                if (label == 0) continue
                if (relative[i] < rescue) light[label]++
                if (near[i] != 0.toByte()) beside[label] = true
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
            notInk.put(0, 0, ByteArray(relative.size) { if (anyInk[labelValues[it]]) 0 else 255.toByte() })
            val distance = Mat().also(owned::add)
            Imgproc.distanceTransform(notInk, distance, Imgproc.DIST_L2, 3)
            val distances = FloatArray(relative.size).also { distance.get(0, 0, it) }
            val nearest = FloatArray(count) { Float.MAX_VALUE }
            for (i in labelValues.indices) if (distances[i] < nearest[labelValues[i]]) nearest[labelValues[i]] = distances[i]
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
        if (lineSeeds != null) for (i in labelValues.indices) if (lineSeeds[i] != 0.toByte()) lined[labelValues[i]] = true
        for (label in 1 until count) if (lined[label]) ghost[label] = false
        // A lined mark is drawn on the narrow curve, with its centre line on top: crisp, where the faint curve
        // drew a blurred stroke's halo grey.
        for (i in labelValues.indices) {
            val label = labelValues[i]
            if (label != 0 && core[label] < 3 && !lined[label]) faint[i] = true
        }
        if (ghost.none { it }) return faint
        val bytes = ByteArray(relative.size).also { grown.get(0, 0, it) }
        for (i in labelValues.indices) if (ghost[labelValues[i]]) bytes[i] = 0
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
        val weight = FloatArray(relative.size) { if (bytes[it] != 0.toByte()) 1f else 0f }
        val level = FloatArray(relative.size) { if (bytes[it] != 0.toByte()) relative[it] else 0f }
        val sigma = max(8.0, ScanConfig.float("toneZonesRuleWindow", 60f) * scale.toDouble())
        val (values, shares) = spread(level, weight, width, height, sigma, owned)
        return FloatArray(relative.size) { if (shares[it] > 1e-5f) values[it] / shares[it] else 1f }
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
        for (i in relative.indices) {
            if (relative[i] < inkCut) {
                weightValues[i] = 1f
                levelValues[i] = relative[i]
                histogram[(relative[i] * 255f).roundToInt().coerceIn(0, 255)]++
                inkCount++
            }
        }
        val pageLevel = if (inkCount > 100) percentile(histogram, inkCount, .5f) / 255f else .45f
        val (levels, shares) = spread(levelValues, weightValues, width, height, sigma, owned)
        return FloatArray(relative.size) {
            val share = shares[it]
            val trust = (share / .01f).coerceIn(0f, 1f)
            depth * (trust * levels[it] / max(share, 1e-4f) + (1f - trust) * pageLevel)
        }
    }

    /**
     * Clears marks that are not writing from [keep], in place: what a crop leaves along the frame.
     * A mark must sit wholly in the outer band of one side. There it goes if it is mostly skin or
     * wood coloured (fingers, a desk), or if it touches the frame and is thick or saturated (a dark
     * wedge, a coloured tab). Handwriting at the edge is thin and dark, so it stays.
     */
    private fun dropEdgeDebris(page: Mat, keep: Mat, thin: Int, owned: MutableList<Mat>) {
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
        val area = IntArray(count)
        val warm = IntArray(count)
        val saturated = IntArray(count)
        val heavy = IntArray(count)
        for (i in labelValues.indices) {
            val label = labelValues[i]
            if (label == 0) continue
            area[label]++
            if (thickBytes[i] != 0.toByte()) heavy[label]++
            if (colour != null) {
                val r = colour[3 * i].toInt() and 255
                val g = colour[3 * i + 1].toInt() and 255
                val b = colour[3 * i + 2].toInt() and 255
                if (warmAt(i)) warm[label]++
                if (max(r, max(g, b)) - min(r, min(g, b)) >= (if (exact) 25 else 40)) saturated[label]++
            }
        }
        val drop = BooleanArray(count)
        for (label in 1 until count) {
            val x0 = table[label * 5 + Imgproc.CC_STAT_LEFT]
            val y0 = table[label * 5 + Imgproc.CC_STAT_TOP]
            val x1 = x0 + table[label * 5 + Imgproc.CC_STAT_WIDTH]
            val y1 = y0 + table[label * 5 + Imgproc.CC_STAT_HEIGHT]
            val inBand =
                x1 <= width * band ||
                    x0 >= width * (1 - band) ||
                    y1 <= height * band ||
                    y0 >= height * (1 - band)
            if (!inBand) continue
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
        for (i in labelValues.indices) {
            if (drop[labelValues[i]]) {
                keepBytes[i] = 0
                continue
            }
            val x = i % width
            val y = i / width
            if (x >= stripX && x < width - stripX && y >= stripY && y < height - stripY) continue
            if (thickBytes[i] != 0.toByte() || warmAt(i)) keepBytes[i] = 0
        }
        keep.put(0, 0, keepBytes)
        if (colour != null) dropWarmBlobs(keep, thick, thin, ::warmAt, owned)
        dropFrameLines(keep, owned)
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
     * Known cost: a printed border closer to the edge than `toneZonesEdgeLineStrip` goes too.
     */
    private fun dropFrameLines(keep: Mat, owned: MutableList<Mat>) {
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
        fun clear(area: Rect, across: Mat, along: Mat) {
            val region = keep.submat(area)
            val line = Mat()
            try {
                // Thickened across the run first, so a slightly tilted edge still forms one straight run.
                Imgproc.dilate(region, line, across)
                Imgproc.morphologyEx(line, line, Imgproc.MORPH_OPEN, along)
                Core.subtract(region, line, region)
            } finally {
                line.release()
                region.release()
            }
        }
        val vertical = rect(1, (height * run).toInt().coerceAtLeast(3))
        val horizontal = rect((width * runAcross).toInt().coerceAtLeast(3), 1)
        clear(Rect(0, 0, stripX, height), rect(5, 1), vertical)
        clear(Rect(width - stripX, 0, stripX, height), rect(5, 1), vertical)
        clear(Rect(0, 0, width, stripY), rect(1, 5), horizontal)
        clear(Rect(0, height - stripY, width, stripY), rect(1, 5), horizontal)
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
        mask.put(
            0,
            0,
            ByteArray(labelValues.size) { if (keep[labelValues[it]]) 255.toByte() else 0.toByte() },
        )
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
        result.put(
            0,
            0,
            ByteArray(labelValues.size) { if (long[labelValues[it]]) 255.toByte() else 0.toByte() },
        )
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
