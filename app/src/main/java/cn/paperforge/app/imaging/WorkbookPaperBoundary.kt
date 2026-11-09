package cn.paperforge.app.imaging

import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Original edge-connected exterior/occluder detector for an already cropped paper photo. Colour
 * alone is insufficient: require a broad solid component, contact with the image edge, and
 * confinement to a narrow margin. Fine coloured annotations and interior figures survive. It cannot
 * recover handwriting hidden by a finger; it only replaces observed exterior pixels.
 */
internal object WorkbookPaperBoundary {
    const val VERSION = "workbook-boundary-11"
    private val last = ThreadLocal<JSONObject>()

    fun diagnostics() = last.get()

    fun prepare(
        rgb: Mat,
        paperTone: Boolean = false,
        exteriorMask: Mat? = null,
        refineFinger: Boolean = true,
    ): Mat? {
        val trace = JSONObject().put("paperTone", paperTone).put("classes", JSONArray())
        last.set(trace)
        if (ScanConfig.int("workbookEdgeCleanup", 1) == 0 || min(rgb.cols(), rgb.rows()) < 160)
            return null
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val small = mat()
        val candidate = mat()
        val solid = mat()
        val ids = mat()
        val stats = mat()
        val centers = mat()
        val selected = mat()
        val expanded = mat()
        val mask = mat()
        try {
            val scale =
                min(
                    1.0,
                    ScanConfig.int("workbookBoundaryEdge", 760).toDouble() /
                        max(rgb.cols(), rgb.rows()),
                )
            Imgproc.resize(rgb, small, Size(), scale, scale, Imgproc.INTER_AREA)
            val w = small.cols()
            val h = small.rows()
            val short = min(w, h)
            val pixels = ByteArray(w * h * 3).also { small.get(0, 0, it) }
            val paperSamples = Array(3) { mutableListOf<Int>() }
            for (y in h / 6 until h * 5 / 6 step 5) for (x in w / 5 until w * 4 / 5 step 5) {
                val i = (y * w + x) * 3
                val r = pixels[i].toInt() and 255
                val g = pixels[i + 1].toInt() and 255
                val b = pixels[i + 2].toInt() and 255
                if (maxOf(r, g, b) - minOf(r, g, b) < 65) {
                    paperSamples[0] += r
                    paperSamples[1] += g
                    paperSamples[2] += b
                }
            }
            if (paperSamples[0].size < 30) return null
            val paper = paperSamples.map { it.sorted()[(it.size * .8).toInt()] }
            val paperLuma = (paper[0] + paper[1] * 2 + paper[2]) / 4f
            trace.put("paperLuma", paperLuma)
            val window = (short * .035f).roundToInt().coerceIn(15, 41) or 1
            val localField = mat()
            Imgproc.dilate(
                small,
                localField,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).also(owned::add),
            )
            Imgproc.medianBlur(localField, localField, window)
            val illumination = ByteArray(w * h * 3).also { localField.get(0, 0, it) }
            val margin = ScanConfig.float("workbookBoundaryMargin", .10f).coerceIn(.03f, .12f)
            val values = ByteArray(w * h)
            val weakColour = ByteArray(w * h)
            val weakNeutral = ByteArray(w * h)
            val seedRed = ScanConfig.float("workbookSkinSeedRed", 10f)
            val seedBlue = ScanConfig.float("workbookSkinSeedBlue", 16f)
            val weakRed = ScanConfig.float("workbookSkinWeakRed", 8f)
            val weakBlue = ScanConfig.float("workbookSkinWeakBlue", 12f)
            val darkRatio = ScanConfig.float("workbookBoundaryDarkRatio", .65f)
            val weakRatio = ScanConfig.float("workbookBoundaryWeakRatio", .88f)
            for (y in 0 until h) for (x in 0 until w) {
                val i = (y * w + x) * 3
                val r = pixels[i].toInt() and 255
                val g = pixels[i + 1].toInt() and 255
                val b = pixels[i + 2].toInt() and 255
                val luma = (r + g * 2 + b) / 4f
                // Compare to the local paper's warm tint, not a universal white balance.
                val warmOccluder =
                    (r - g > max(22, paper[0] - paper[1] + 16) &&
                        r - b > max(42, paper[0] - paper[2] + 24) &&
                        g >= b - 8 &&
                        g >= r * .50f &&
                        b >= r * .35f &&
                        r > 65 &&
                        luma < paperLuma * 1.05) ||
                        (r * paper[1] - g * paper[0] > paper[0] * seedRed &&
                            r * paper[2] - b * paper[0] > paper[0] * seedBlue &&
                            g >= r * .35f &&
                            b >= r * .16f &&
                            g >= b - 8 &&
                            r > 40 &&
                            luma < paperLuma * 1.05)
                val exterior = luma < paperLuma * darkRatio && maxOf(r, g, b) - minOf(r, g, b) < 65
                // A fitted map's white canvas is not photographed paper illumination. Leaving
                // that jump in the median field turns the cream paper edge into a black halo.
                val padding = paperTone && paperLuma < 240 && minOf(r, g, b) >= 250
                values[y * w + x] =
                    (if (padding) 3 else if (warmOccluder) 1 else if (exterior) 2 else 0).toByte()
                // Paper-relative colour ratios still identify dark skin whose absolute RGB
                // differences shrink in shade. Only an accepted skin seed can grow through it.
                if (
                    r * paper[1] - g * paper[0] > paper[0] * weakRed &&
                        r * paper[2] - b * paper[0] > paper[0] * weakBlue &&
                        g >= r * .25f &&
                        b >= r * .10f &&
                        r > 16 &&
                        luma < paperLuma * 1.2f
                )
                    weakColour[y * w + x] = 255.toByte()
                val localPaper =
                    ((illumination[i].toInt() and 255) +
                        (illumination[i + 1].toInt() and 255) * 2 +
                        (illumination[i + 2].toInt() and 255)) / 4f
                if (luma < localPaper * weakRatio && maxOf(r, g, b) - minOf(r, g, b) < 65)
                    weakNeutral[y * w + x] = 255.toByte()
            }
            candidate.create(h, w, CvType.CV_8UC1)
            // Opening is applied ONLY to an exterior hypothesis, never to text/ink pixels.
            val size = max(3, (short * .009f).roundToInt() or 1)
            val kernel =
                Imgproc.getStructuringElement(
                        Imgproc.MORPH_ELLIPSE,
                        Size(size.toDouble(), size.toDouble()),
                    )
                    .also(owned::add)
            val chosen = ByteArray(w * h)
            val fingers = ByteArray(w * h)
            // Do not connect white padding to black ruled lines, or skin to touching dark ink:
            // such a union spans the paper and incorrectly vetoes every exterior candidate.
            for (kind in listOf(3, 1, 2)) {
                candidate.put(
                    0,
                    0,
                    ByteArray(w * h) { if (values[it].toInt() == kind) 255.toByte() else 0 },
                )
                Imgproc.morphologyEx(candidate, solid, Imgproc.MORPH_OPEN, kernel)
                if (kind == 1) {
                    // A dark skin rim can separate a bright seed from the literal image border.
                    // Establish weak-colour connectivity before testing border contact.
                    val potential = mat()
                    val grown = mat()
                    potential.create(h, w, CvType.CV_8UC1)
                    potential.put(0, 0, weakColour)
                    Core.bitwise_or(potential, solid, potential)
                    val link =
                        Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
                            .also(owned::add)
                    repeat(ScanConfig.int("workbookSkinFringe", 12).coerceIn(0, 16)) {
                        Imgproc.dilate(solid, grown, link)
                        Core.bitwise_and(grown, potential, solid)
                    }
                }
                val count =
                    Imgproc.connectedComponentsWithStats(
                        solid,
                        ids,
                        stats,
                        centers,
                        8,
                        CvType.CV_32S,
                    )
                val regions = IntArray(count * 5).also { stats.get(0, 0, it) }
                val labels = IntArray(w * h).also { ids.get(0, 0, it) }
                val central = IntArray(count)
                val whites = IntArray(count)
                for (i in labels.indices) if (
                    (0..2).all { (pixels[i * 3 + it].toInt() and 255) >= 250 }
                )
                    whites[labels[i]]++
                for (y in 0 until h) for (x in 0 until w) if (
                    x >= w * margin &&
                        x < w * (1 - margin) &&
                        y >= h * margin &&
                        y < h * (1 - margin)
                )
                    central[labels[y * w + x]]++
                val accepted = BooleanArray(count)
                for (id in 1 until count) {
                    val x = regions[id * 5]
                    val y = regions[id * 5 + 1]
                    val cw = regions[id * 5 + 2]
                    val ch = regions[id * 5 + 3]
                    val area = regions[id * 5 + 4]
                    fun whiteGap(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
                        if (x0 >= x1 || y0 >= y1) return true
                        for (yy in y0 until y1 step 2) for (xx in x0 until x1 step 2) {
                            val i = (yy * w + xx) * 3
                            if ((0..2).any { (pixels[i + it].toInt() and 255) < 250 }) return false
                        }
                        return true
                    }
                    // A fitted dewarp can add white padding. It is still the same paper exterior,
                    // but a component separated from the edge by real source pixels is not removed.
                    val touches =
                        x <= 1 ||
                            y <= 1 ||
                            x + cw >= w - 1 ||
                            y + ch >= h - 1 ||
                            (x < w * .03 && whiteGap(0, y, x, y + ch)) ||
                            (x + cw > w * .97 && whiteGap(x + cw, y, w, y + ch)) ||
                            (y < h * .03 && whiteGap(x, 0, x + cw, y)) ||
                            (y + ch > h * .97 && whiteGap(x, y + ch, x + cw, h))
                    // An exterior ring can touch several sides. Its bounding box spans the whole
                    // page even though every constituent pixel is confined to a thin outer margin.
                    val confined =
                        if (kind == 1) {
                            // Broad edge-connected fingers can extend beyond the narrow paper band.
                            x + cw < w * .36 || x > w * .64 || y + ch < h * .26 || y > h * .74
                        } else central[id] == 0
                    val edgeCount =
                        listOf(x <= 1, y <= 1, x + cw >= w - 1, y + ch >= h - 1).count { it }
                    val filledFigure =
                        kind == 2 &&
                            edgeCount == 1 &&
                            cw.toFloat() / ch in .45f..2.2f &&
                            area > cw * ch * .6f
                    accepted[id] =
                        touches &&
                            !filledFigure &&
                            confined &&
                            area >=
                                (if (
                                    kind == 2 &&
                                        paperLuma < 220 &&
                                        ((x <= 1 || x + cw >= w - 1) && (y <= 1 || y + ch >= h - 1))
                                )
                                    max(12f, w * h * .00005f)
                                else max(30f, w * h * .0003f)) &&
                            area <
                                w *
                                    h *
                                    (if (whites[id] > area * .8f) .20f
                                    else if (kind == 1) .16f else .06f) &&
                            min(cw, ch) >=
                                (if (kind == 2 && paperLuma < 220) max(2, size / 2) else size) &&
                            (area > cw * ch * .25f || cw > w * .8f || ch > h * .8f)
                }
                val layer = ByteArray(w * h) { if (accepted[labels[it]]) 255.toByte() else 0 }
                if (kind == 2 && accepted.any { it }) {
                    // Hysteresis starts only at accepted exterior objects. The class-specific
                    // weaker fringe cannot reach disconnected ink through bright paper.
                    val grown = mat()
                    val potential = mat()
                    val next = mat()
                    grown.create(h, w, CvType.CV_8UC1)
                    grown.put(0, 0, layer)
                    potential.create(h, w, CvType.CV_8UC1)
                    val weak = if (kind == 1) weakColour else weakNeutral
                    // Hysteresis must retain every accepted seed. A bright skin core can lie
                    // outside a weaker paper-relative colour class on warm paper.
                    potential.put(
                        0,
                        0,
                        ByteArray(layer.size) { i ->
                            if (layer[i] != 0.toByte() || weak[i] != 0.toByte()) 255.toByte() else 0
                        },
                    )
                    val fringeKernel =
                        Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
                            .also(owned::add)
                    repeat(
                        if (kind == 1) ScanConfig.int("workbookSkinFringe", 12).coerceIn(0, 12)
                        else ScanConfig.int("workbookBoundaryFringe", 3).coerceIn(0, 5)
                    ) {
                        Imgproc.dilate(grown, next, fringeKernel)
                        Core.bitwise_and(next, potential, grown)
                    }
                    grown.get(0, 0, layer)
                }
                for (i in chosen.indices) if (layer[i] != 0.toByte()) chosen[i] = 255.toByte()
                if (kind == 1) for (i in fingers.indices) fingers[i] = layer[i]
                if (ScanConfig.int("workbookDiagnostics", 0) != 0)
                    trace
                        .getJSONArray("classes")
                        .put(
                            JSONObject()
                                .put("kind", kind)
                                .put("components", count - 1)
                                .put("accepted", accepted.count { it })
                                .put("centralRejected", (1 until count).count { central[it] > 0 })
                                .put(
                                    "pixels",
                                    (1 until count)
                                        .filter { accepted[it] }
                                        .sumOf { regions[it * 5 + 4] },
                                )
                        )
            }
            var shadowCorrection: Mat? = null
            if (fingers.any { it != 0.toByte() }) {
                if (refineFinger && ScanConfig.int("workbookFingerGraphCut", 0) != 0) {
                    val refined = FingerFringe.refine(small, fingers)
                    trace.put(
                        "graphCutAdded",
                        refined.indices.count {
                            refined[it] != 0.toByte() && fingers[it] == 0.toByte()
                        },
                    )
                    for (i in chosen.indices) if (refined[i] != 0.toByte()) {
                        chosen[i] = 255.toByte()
                        fingers[i] = 255.toByte()
                    }
                }
                // Cast shadow is illumination, not opaque skin. Normalize its local paper
                // field instead of painting it white; handwriting beneath it must remain.
                if (refineFinger && ScanConfig.int("workbookFingerShadowNormalize", 1) != 0) {
                    val seed = mat()
                    val zone = mat()
                    val field = mat()
                    seed.create(h, w, CvType.CV_8UC1)
                    seed.put(0, 0, fingers)
                    val radius = ScanConfig.int("workbookFingerShadowRadius", 24).coerceIn(6, 32)
                    Imgproc.dilate(
                        seed,
                        zone,
                        Imgproc.getStructuringElement(
                                Imgproc.MORPH_ELLIPSE,
                                Size((radius * 2 + 1).toDouble(), (radius * 2 + 1).toDouble()),
                            )
                            .also(owned::add),
                    )
                    // Remove thin ink from the illumination estimate, while following the
                    // much narrower hand shadow that the global paper window smooths over.
                    Imgproc.dilate(
                        small,
                        field,
                        Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                            .also(owned::add),
                    )
                    Imgproc.medianBlur(field, field, 3)
                    val nearby = ByteArray(w * h).also { zone.get(0, 0, it) }
                    val shades = ByteArray(w * h * 3).also { field.get(0, 0, it) }
                    val gains = FloatArray(w * h * 3) { 1f }
                    var corrected = 0
                    for (i in nearby.indices) if (
                        nearby[i] != 0.toByte() && fingers[i] == 0.toByte()
                    ) {
                        val r = pixels[i * 3].toInt() and 255
                        val g = pixels[i * 3 + 1].toInt() and 255
                        val b = pixels[i * 3 + 2].toInt() and 255
                        val luma =
                            ((shades[i * 3].toInt() and 255) +
                                (shades[i * 3 + 1].toInt() and 255) * 2 +
                                (shades[i * 3 + 2].toInt() and 255)) / 4f
                        val skinTint =
                            r * paper[1] - g * paper[0] > paper[0] * weakRed &&
                                r * paper[2] - b * paper[0] > paper[0] * weakBlue
                        if (
                            !skinTint &&
                                maxOf(r, g, b) - minOf(r, g, b) < 65 &&
                                luma < paperLuma * .96f
                        ) {
                            corrected++
                            val gain = (paperLuma / max(16f, luma)).coerceIn(1f, 5f)
                            for (c in 0..2) gains[i * 3 + c] = gain
                        }
                    }
                    shadowCorrection =
                        mat().also {
                            it.create(h, w, CvType.CV_32FC3)
                            it.put(0, 0, gains)
                        }
                    trace.put("shadowNormalizedPixels", corrected)
                }
            }
            // v200 20016 regression follow-up: the owner's page has a dark spine shadow along the
            // left edge and the handwriting starts inside it. The exterior mask must paint the
            // shadow (and page-exterior furniture) out, yet keep the strokes. Two lessons from the
            // first fix:
            //   * protecting strokes at the bounded probe resolution left 2-3 px blocky holes after
            //     the nearest-neighbour upscale ("looks mosaic/censored");
            //   * protecting a 3 px-wide ring left a dark shadow halo around every stroke.
            // So the silhouette is filled exactly as before, and the stroke veto is applied ONCE at
            // full resolution with a 1 px fringe. The fill may run through a stroke because the
            // full-resolution veto cuts it out afterwards.
            // The crop quad can include things that are not the sheet: the page under it, a tab, a
            // strip of desk. PageNet says what is not the sheet; a shadow on the sheet stays sheet.
            // This is OR-ed in after the stroke veto on purpose: printed text on the page underneath
            // must not be protected as if it were content.
            val frame = PageNetDetector.exterior(rgb)
            if (chosen.none { it != 0.toByte() } && frame == null) return null
            trace.put("selectedPixels", chosen.count { it != 0.toByte() })
            for (y in 0 until h) {
                val right = (w * (1 - max(margin, .36f))).toInt()
                val left = (w * max(margin, .36f)).toInt()
                val first = (right until w).firstOrNull { chosen[y * w + it] != 0.toByte() }
                if (first != null) for (x in first until w) chosen[y * w + x] = 255.toByte()
                val last = (0 until left).lastOrNull { chosen[y * w + it] != 0.toByte() }
                if (last != null) for (x in 0..last) chosen[y * w + x] = 255.toByte()
            }
            selected.create(h, w, CvType.CV_8UC1)
            selected.put(0, 0, chosen)
            Imgproc.dilate(
                selected,
                expanded,
                Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
                    .also(owned::add),
            )
            // Linear upscale + threshold: the exterior contour follows the probe silhouette instead
            // of 2-3 px nearest-neighbour stairs.
            Imgproc.resize(expanded, mask, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val maskBytes = ByteArray(rgb.cols() * rgb.rows()).also { mask.get(0, 0, it) }
            for (i in maskBytes.indices) {
                maskBytes[i] = if ((maskBytes[i].toInt() and 255) >= 128) 255.toByte() else 0
            }
            mask.put(0, 0, maskBytes)
            // Full-resolution stroke veto: exact holes, one-pixel fringe.
            run {
                val gray = mat()
                val baseline = mat()
                Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
                val window =
                    max(15, (min(rgb.cols(), rgb.rows()) * .05f).roundToInt() or 1)
                        .coerceAtMost(61)
                Imgproc.medianBlur(gray, baseline, window)
                val grayBytes = ByteArray(rgb.cols() * rgb.rows()).also { gray.get(0, 0, it) }
                val baseBytes = ByteArray(grayBytes.size).also { baseline.get(0, 0, it) }
                val strokes = ByteArray(grayBytes.size)
                val contrast =
                    ScanConfig.float("workbookBoundaryStrokeContrast", 26f).coerceIn(12f, 80f)
                // Every stroke is protected here, up to the page edge. Printed text from a page
                // under the sheet is removed by the PageNet exterior, which is applied after this.
                var count = 0
                for (y in 0 until rgb.rows()) {
                    val row = y * rgb.cols()
                    for (x in 0 until rgb.cols()) {
                        val i = row + x
                        val value = grayBytes[i].toInt() and 255
                        val base = baseBytes[i].toInt() and 255
                        if (base - value >= contrast && value < paperLuma * .92f) {
                            strokes[i] = 255.toByte()
                            count++
                        }
                    }
                }
                val strokeMat = mat()
                strokeMat.create(rgb.rows(), rgb.cols(), CvType.CV_8UC1)
                strokeMat.put(0, 0, strokes)
                Imgproc.dilate(
                    strokeMat,
                    strokeMat,
                    Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
                        .also(owned::add),
                )
                Core.bitwise_not(strokeMat, strokeMat)
                Core.bitwise_and(mask, strokeMat, mask)
                trace.put("strokeProtectedPixels", count)
            }
            if (frame != null) {
                Core.bitwise_or(mask, frame, mask)
                trace.put(
                    "pageFramePixels",
                    frame.let { f ->
                        val bytes = ByteArray(rgb.cols() * rgb.rows()).also { f.get(0, 0, it) }
                        bytes.count { it != 0.toByte() }
                    },
                )
                EdgeInkDiagnostics.dump("03d-page-frame", frame)
                frame.release()
            }
            EdgeInkDiagnostics.dump("03c-boundary-mask", mask)
            exteriorMask?.let { mask.copyTo(it) }
            return rgb.clone().also { result ->
                shadowCorrection?.let { gains ->
                    val full = mat()
                    val floating = mat()
                    Imgproc.resize(gains, full, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                    result.convertTo(floating, CvType.CV_32FC3)
                    Core.multiply(floating, full, floating)
                    floating.convertTo(result, CvType.CV_8UC3)
                }
                if (!paperTone) result.setTo(Scalar.all(255.0), mask)
                else {
                    // v200 shadow pass: repaint with the SAME continuous whole-page paper field the
                    // interior is normalised by. The old branch copied the paper level of the
                    // nearest valid sample, so an accepted band inside the sheet was repainted at
                    // the interior level while the shadow's own paper level stayed on the other
                    // side of the contour: the mask contour became a hard brightness step. Tone is
                    // now constant across the contour; only occluded content is removed. Preserved
                    // strokes (mask holes) keep their measured level and are normalised by the same
                    // continuous field downstream, so the former per-stroke flat-field (which
                    // switched gain exactly at the 1 px protected fringe) is gone.
                    val probeGray = mat()
                    Imgproc.cvtColor(small, probeGray, Imgproc.COLOR_RGB2GRAY)
                    val probeField = PaperField.estimateBytes(probeGray)
                    val fullField = mat()
                    try {
                        Imgproc.resize(
                            probeField,
                            fullField,
                            rgb.size(),
                            0.0,
                            0.0,
                            Imgproc.INTER_LINEAR,
                        )
                    } finally {
                        probeField.release()
                    }
                    val fieldBytes =
                        ByteArray(rgb.cols() * rgb.rows()).also { fullField.get(0, 0, it) }
                    val keepBytes = ByteArray(fieldBytes.size).also { mask.get(0, 0, it) }
                    val outBytes = ByteArray(fieldBytes.size * 3).also { result.get(0, 0, it) }
                    val reference = max(1f, paperLuma)
                    for (i in fieldBytes.indices) {
                        if (keepBytes[i] == 0.toByte()) continue
                        val level = fieldBytes[i].toInt() and 255
                        // Normalise the replaced exterior to the paper colour: the interior is
                        // normalised by the same field later, so the mask contour must not keep a
                        // darker copy of the shadow (that paints a grey band where content was
                        // removed). The old branch copied the nearest paper sample, i.e. paper
                        // colour; here the continuous field only sets how far to lift.
                        val gain = (reference / max(1, level)).coerceIn(1f, 1.6f)
                        for (c in 0..2) {
                            outBytes[i * 3 + c] =
                                (paper[c] * gain).roundToInt().coerceIn(0, 255).toByte()
                        }
                    }
                    result.put(0, 0, outBytes)
                }
            }
        } finally {
            owned.forEach(Mat::release)
        }
    }
}
