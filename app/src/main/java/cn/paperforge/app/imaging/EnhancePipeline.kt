package cn.paperforge.app.imaging

import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * PaperForge tile flat-field implementation. Technique research: OSS-DocumentScanner@ff05fc52
 * (MIT), WhitePaperTransform{,2}.cpp. No copied code; the color/large-fill tradeoff is measured in
 * enhancement-v030.
 */
object EnhancePipeline {
    /** Shared by scan thumbnails, page rendering, batch edits and PDF enhancement. */
    object SoftMonochrome {
        const val illuminationStrength = .85f
        const val paperWhite = 242f
        const val maximumGain = 2.6f
        const val windowFraction = .035f
        const val sauvolaK = .075f
    }

    /**
     * Thin-paper workbook preset. Show-through from the reverse side and a grey paper base are
     * broad and low-contrast, so they are dropped by a soft-knee on the paper residual instead of a
     * threshold: only ink clearly darker than the local paper survives. Pencil strokes are kept by
     * the floor being well below their residual, and a light high-pass restores stroke edges.
     */
    object Workbook {
        const val illuminationStrength = .9f
        const val paperWhite = 246f
        const val maximumGain = 2.9f

        /** Reverse-side bleed stays below this paper residual; thin pencil is above it. */
        const val ghostFloor = 16f
        const val inkGain = 1.35f
        const val sharpenAmount = .3f
    }

    /** RGB input, caller owns destination. ORIGINAL never changes channel count. */
    fun apply(
        rgb: Mat,
        destination: Mat,
        mode: String,
        strengthOverride: Float? = null,
        workbookDetailOverride: Float? = null,
    ) {
        if (mode == "ORIGINAL") {
            rgb.copyTo(destination)
            return
        }
        if (mode in setOf("WORKBOOK", "CLEAN") && ScanConfig.int("workbookLocalPaper", 1) != 0) {
            // A cover or coloured paper keeps its colours: see [ColorPage].
            if (rgb.channels() == 3 && ColorPage.isColorful(rgb)) {
                StepTimes.time("colorPage") { ColorPage.enhance(rgb, destination) }
                return
            }
            val strength = strengthOverride ?: ScanConfig.float("illuminationStrength", 1f)
            if (lightFirstPage(rgb, destination, strength)) {
                // A solid colour print on a document page, such as a cover's picture, keeps its colour.
                if (rgb.channels() == 3 && destination.channels() == 3)
                    StepTimes.time("colorPrints") { ColorPage.keepPrints(rgb, destination) }
                return
            }
            val exterior = Mat()
            val prepared =
                if (strength > 0f && WorkbookContent.enabled())
                    WorkbookPaperBoundary.prepare(rgb, paperTone = true, exteriorMask = exterior)
                else null
            try {
                if (prepared != null) EdgeInkDiagnostics.dump("03-boundary-prepared", prepared)
                if (!exterior.empty()) EdgeInkDiagnostics.dump("03b-exterior-mask", exterior)
                localWorkbook(
                    prepared ?: rgb,
                    destination,
                    strength,
                    workbookDetailOverride,
                    strong = mode == "CLEAN",
                )
                // The field extension is solely illumination evidence. Known exterior pixels
                // cannot be promoted back into foreground by the later contrast/source guard.
                // v200 20019: that flood used to be `setTo(255)`, which left a flat 255 plateau on
                // the former shadow band, one to three gray above the body's paper and with a hard
                // contour. PaperTone now retones the whole block with one gain so it lands on the
                // page's own paper median (capped at 254) and the ink/paper ratio is preserved.
                if (!exterior.empty()) {
                    if (PaperTone.enabled()) PaperTone.retone(destination, exterior)
                    else destination.setTo(Scalar.all(255.0), exterior)
                }
            } finally {
                prepared?.release()
                exterior.release()
            }
            return
        }
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also { owned.add(it) }
        val small = mat()
        val tiles = mat()
        val smooth = mat()
        val field = mat()
        val corrected = mat()
        val gray = mat()
        try {
            val scale =
                min(
                    1.0,
                    ScanConfig.int("illuminationEdge", 480).toDouble() / max(rgb.cols(), rgb.rows()),
                )
            Imgproc.resize(rgb, small, Size(), scale, scale, Imgproc.INTER_AREA)
            val sw = small.cols()
            val sh = small.rows()
            val cols = max(3, (sw / 32.0).roundToInt())
            val rows = max(3, (sh / 32.0).roundToInt())
            val bytes = ByteArray(sw * sh * 3)
            small.get(0, 0, bytes)
            val tileBytes = ByteArray(rows * cols * 3)
            val fraction = ScanConfig.float("illuminationTilePercentile", .88f)
            for (y in 0 until rows) for (x in 0 until cols) {
                val hist = Array(3) { IntArray(256) }
                var count = 0
                for (ty in y * sh / rows until (y + 1) * sh / rows) for (tx in
                    x * sw / cols until (x + 1) * sw / cols) {
                    for (c in 0..2) hist[c][bytes[(ty * sw + tx) * 3 + c].toInt() and 255]++
                    count++
                }
                for (c in 0..2) tileBytes[(y * cols + x) * 3 + c] =
                    percentile(hist[c], count, fraction).toByte()
            }
            tiles.create(rows, cols, CvType.CV_8UC3)
            tiles.put(0, 0, tileBytes)
            Imgproc.cvtColor(tiles, gray, Imgproc.COLOR_RGB2GRAY)
            val luminance = ByteArray(rows * cols)
            gray.get(0, 0, luminance)
            val hist = IntArray(256)
            luminance.forEach { hist[it.toInt() and 255]++ }
            val floor = max(35f, percentile(hist, luminance.size, .9f) * .45f)
            val dark =
                ByteArray(luminance.size) {
                    if ((luminance[it].toInt() and 255) < floor) 255.toByte() else 0
                }
            val darkCount = dark.count { it.toInt() != 0 }
            if (darkCount in 1 until dark.size) {
                val mask = mat()
                mask.create(rows, cols, CvType.CV_8UC1)
                mask.put(0, 0, dark)
                Photo.inpaint(tiles, mask, smooth, 3.0, Photo.INPAINT_TELEA)
                smooth.copyTo(tiles)
            }
            Imgproc.GaussianBlur(tiles, smooth, Size(3.0, 3.0), 0.0)
            Imgproc.resize(smooth, field, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val input = ByteArray(rgb.rows() * rgb.cols() * 3)
            val illumination = ByteArray(input.size)
            rgb.get(0, 0, input)
            field.get(0, 0, illumination)
            val userStrength =
                strengthOverride?.coerceIn(0f, 1f)
                    ?: if (mode == "WHITEBOARD") 1f
                    else ScanConfig.float("illuminationStrength", 1f)
            val monochrome = mode == "BW"
            val workbook = mode == "WORKBOOK"
            val paperTarget =
                when {
                    monochrome -> SoftMonochrome.paperWhite
                    workbook -> Workbook.paperWhite
                    else -> 250f
                }
            val maximumGain =
                when {
                    monochrome -> SoftMonochrome.maximumGain
                    workbook -> Workbook.maximumGain
                    else -> 3f
                }
            val strength =
                when {
                    monochrome -> userStrength * SoftMonochrome.illuminationStrength
                    workbook -> userStrength * Workbook.illuminationStrength
                    else -> userStrength
                }
            for (i in input.indices) {
                val gain =
                    (paperTarget / max(70, illumination[i].toInt() and 255)).coerceIn(
                        .85f,
                        maximumGain,
                    )
                input[i] =
                    ((input[i].toInt() and 255) * (1 + (gain - 1) * strength))
                        .toInt()
                        .coerceIn(0, 255)
                        .toByte()
            }
            corrected.create(rgb.rows(), rgb.cols(), CvType.CV_8UC3)
            corrected.put(0, 0, input)
            if (workbook) {
                workbookDocument(corrected, field, destination, strength)
                return
            }
            if (mode in listOf("AUTO", "TEXT", "COLOR")) {
                enhanceLuminance(corrected, destination, mode, strength)
                return
            }
            if (mode in listOf("CLEAN", "WHITEBOARD")) {
                // CLEAN used to fall into the gray path and silently discard colored stamps.
                corrected.copyTo(destination)
                return
            }
            Imgproc.cvtColor(corrected, gray, Imgproc.COLOR_RGB2GRAY)
            if (mode != "BW") {
                gray.copyTo(destination)
                return
            }
            val window =
                max(15, (min(rgb.cols(), rgb.rows()) * SoftMonochrome.windowFraction).roundToInt())
                    .coerceAtMost(201) or 1
            val f = mat()
            val mean = mat()
            val square = mat()
            val threshold = mat()
            gray.convertTo(f, CvType.CV_32F)
            Imgproc.boxFilter(
                f,
                mean,
                -1,
                Size(window.toDouble(), window.toDouble()),
                Point(-1.0, -1.0),
                true,
                Core.BORDER_REPLICATE,
            )
            Core.multiply(f, f, square)
            Imgproc.boxFilter(
                square,
                square,
                -1,
                Size(window.toDouble(), window.toDouble()),
                Point(-1.0, -1.0),
                true,
                Core.BORDER_REPLICATE,
            )
            Core.multiply(mean, mean, threshold)
            Core.subtract(square, threshold, square)
            Core.max(square, Scalar(0.0), square)
            Core.sqrt(square, square)
            square.convertTo(threshold, -1, 1.0 / 128, -1.0)
            Core.multiply(threshold, Scalar(SoftMonochrome.sauvolaK.toDouble()), threshold)
            Core.add(threshold, Scalar(1.0), threshold)
            Core.multiply(mean, threshold, threshold)
            val grayBytes = ByteArray(gray.rows() * gray.cols())
            gray.get(0, 0, grayBytes)
            val gh = IntArray(256)
            grayBytes.forEach { gh[it.toInt() and 255]++ }
            // Sauvola alone erases interiors of large solid black fills.
            Core.max(
                threshold,
                Scalar(min(65.0, percentile(gh, grayBytes.size, .85f) * .22)),
                threshold,
            )
            // Keep a soft ink ramp: a hard comparison turns tiny paper fluctuations into
            // fully black specks. Strong/solid ink remains black; faint edges retain grey.
            val thresholds = FloatArray(grayBytes.size)
            threshold.get(0, 0, thresholds)
            val output = ByteArray(grayBytes.size)
            for (i in output.indices) {
                val ink =
                    (thresholds[i] -
                            (grayBytes[i].toInt() and 255) -
                            ScanConfig.float("softMonoNoiseFloor", 2f))
                        .coerceAtLeast(0f)
                output[i] =
                    (255f - ink * ScanConfig.float("softMonoInkScale", 8f))
                        .roundToInt()
                        .coerceIn(0, 255)
                        .toByte()
            }
            destination.create(gray.rows(), gray.cols(), CvType.CV_8UC1)
            destination.put(0, 0, output)
        } finally {
            owned.forEach { it.release() }
        }
    }

    /**
     * Corrects the lighting on the raw page before deciding anything about ink, so a stroke in shadow
     * ends up the same as one in the light, with no boundary where the shadow was. Returns false, with
     * [destination] unspecified, when the page should take the older path: a very dark capture, or
     * strength zero.
     */
    private fun lightFirstPage(rgb: Mat, destination: Mat, strength: Float): Boolean {
        if (ScanConfig.int("workbookLightFirst", 1) == 0 || strength <= 0f) return false
        val gray = Mat()
        var field: Mat? = null
        var outside: Mat? = null
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            field = StepTimes.time("paperField") { PaperField.edgePreserving(gray) }
            outside = StepTimes.time("exterior") { PageNetDetector.exterior(rgb) }
            outside?.let { EdgeInkDiagnostics.dump("03d-exterior", it) }
            rgb.copyTo(destination)
            val stats = StepTimes.time("toneZones") { ToneZones.apply(destination, field, strength, outside) }
            if (stats?.optBoolean("applied") != true) return false
            EdgeInkDiagnostics.dump("07c-tone", destination)
            StepTimes.time("edgeGuard") { PaperEdgeGuard.apply(destination) }
            return true
        } finally {
            gray.release()
            field?.release()
            outside?.release()
        }
    }

    /**
     * Shared WORKBOOK/CLEAN exit. Order matters: show-through is attenuated on the normalised ink
     * plane first (it must not be lifted before it is removed), then the paper-whiteness division
     * normalizes the rendered page (B0, PAPER-WHITENING-2026-11-04), then the edge guard removes the
     * thin contaminated rim that the crop/warp keeps from outside the physical sheet. All three run
     * on the final page, so thumbnails, the editor, PDF packing and batch edits see the same result.
     */
    private fun finishWorkbook(destination: Mat, amount: Float = 1f) {
        PaperGhost.apply(destination, amount)
        EdgeInkDiagnostics.dump("07b-ghost", destination)
        // v200 tone-zone pass: one paper field is estimated for both the three-zone grey mapping
        // and the final whiteness division. Sharing it keeps the added cost to the mapping loop
        // instead of paying a second whole-page estimate.
        val field = Mat()
        val gray = Mat()
        try {
            if (ToneZones.enabled() || PaperWhiteFinal.enabled()) {
                when (destination.channels()) {
                    1 -> destination.copyTo(gray)
                    else -> Imgproc.cvtColor(destination, gray, Imgproc.COLOR_RGB2GRAY)
                }
                PaperField.estimate(gray, field)
            }
            ToneZones.apply(destination, if (field.empty()) null else field, amount)
            EdgeInkDiagnostics.dump("07c-tone", destination)
            PaperWhiteFinal.apply(destination, if (field.empty()) null else field)
        } finally {
            field.release()
            gray.release()
        }
        EdgeInkDiagnostics.dump("07-white", destination)
        PaperEdgeGuard.apply(destination)
        EdgeInkDiagnostics.dump("08-guard", destination)
    }

    /**
     * v200 20016 regression: on a bound workbook the left paper edge carries a broad spine/shadow
     * band. The local paper field (dilate + median) takes the band's own dark level as "paper", so
     * the handwriting sitting on it has almost no ink residual (`v ~= 0`) and every later stage is
     * entitled to erase it as background. Edge strokes must never define the paper estimate.
     *
     * The repair only touches a thin outer band: for each perimeter pixel the paper level becomes
     * the maximum of the field between that pixel and the interior. Paper is the bright side of a
     * page edge, so the estimate comes from the interior sheet, not from the shadow or the ink.
     * The interior is untouched, so mid-page contrast and the DocUNet regression are unaffected.
     */
    private fun repairEdgePaper(paper: ByteArray, width: Int, height: Int) {
        if (ScanConfig.int("workbookEdgePaperRepair", 1) == 0) return
        val short = min(width, height)
        val band =
            (short * ScanConfig.float("workbookEdgePaperBand", .08f))
                .roundToInt()
                .coerceIn(2, short / 4)
        fun value(x: Int, y: Int) = paper[y * width + x].toInt() and 255
        fun raise(index: Int, level: Int) {
            if (level > (paper[index].toInt() and 255)) paper[index] = level.toByte()
        }
        for (y in 0 until height) {
            var level = 0
            for (x in min(band, width - 1) downTo 0) {
                level = max(level, value(x, y))
                raise(y * width + x, level)
            }
            level = 0
            for (x in (width - 1 - band).coerceAtLeast(0) until width) {
                level = max(level, value(x, y))
                raise(y * width + x, level)
            }
        }
        for (x in 0 until width) {
            var level = 0
            for (y in min(band, height - 1) downTo 0) {
                level = max(level, value(x, y))
                raise(y * width + x, level)
            }
            level = 0
            for (y in (height - 1 - band).coerceAtLeast(0) until height) {
                level = max(level, value(x, y))
                raise(y * width + x, level)
            }
        }
    }

    /** Estimate only broad paper illumination at a bounded scale; native ink stays untouched. */
    internal fun paperField(
        gray: Mat,
        destination: Mat,
        window: Int,
        dilation: Int = 3,
        median: Boolean = true,
    ) {
        val small = Mat()
        val expanded = Mat()
        val field = Mat()
        val edge = ScanConfig.int("paperFieldEdge", 960).coerceIn(480, 2400)
        val scale = min(1.0, edge.toDouble() / max(gray.cols(), gray.rows()))
        val kernelSize = max(3, (dilation * scale).roundToInt()) or 1
        val kernel =
            Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(kernelSize.toDouble(), kernelSize.toDouble()),
            )
        try {
            if (scale < 1.0)
                Imgproc.resize(
                    gray,
                    small,
                    Size(
                        (gray.cols() * scale).roundToInt().toDouble(),
                        (gray.rows() * scale).roundToInt().toDouble(),
                    ),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
            else gray.copyTo(small)
            Imgproc.dilate(small, expanded, kernel)
            val lowWindow = max(7, (window * scale).roundToInt()) or 1
            if (median) Imgproc.medianBlur(expanded, field, lowWindow)
            else Imgproc.GaussianBlur(expanded, field, Size(0.0, 0.0), lowWindow / 3.0)
            if (scale < 1.0)
                Imgproc.resize(field, destination, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            else field.copyTo(destination)
        } finally {
            listOf(small, expanded, field, kernel).forEach(Mat::release)
        }
    }

    /** Long-rule occupancy tolerates small paper gaps without treating short text as a rule. */
    internal fun rulingEvidence(
        ink: Mat,
        destination: Mat,
        span: Float = ScanConfig.float("workbookRuleLength", .15f),
    ) {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        fun kernel(w: Int, h: Int) =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(w.toDouble(), h.toDouble()))
                .also(owned::add)
        val horizontal = mat()
        val vertical = mat()
        val expanded = mat()
        val binary = mat()
        val mean = mat()
        val coverage = mat()
        val curve = mat()
        val continuous = mat()
        try {
            val length = (min(ink.cols(), ink.rows()) * span).roundToInt().coerceIn(31, 511) or 1
            val tolerance = ScanConfig.int("workbookRuleTolerance", 5).coerceIn(1, 9) or 1
            for (axis in 0..1) {
                val size =
                    if (axis == 0) Size(length.toDouble(), 1.0) else Size(1.0, length.toDouble())
                Imgproc.dilate(
                    ink,
                    expanded,
                    if (axis == 0) kernel(1, tolerance) else kernel(tolerance, 1),
                )
                // Coverage alone mistakes a dense row of ghost glyphs for a paper rule.
                // Require an observed continuous directional run before pooling occupancy.
                val run =
                    (length * ScanConfig.float("workbookRuleContinuousFraction", .23f))
                        .roundToInt()
                        .coerceIn(9, 63) or 1
                Imgproc.morphologyEx(
                    expanded,
                    continuous,
                    Imgproc.MORPH_OPEN,
                    if (axis == 0) kernel(run, 1) else kernel(1, run),
                )
                Imgproc.threshold(continuous, binary, 6.0, 1.0, Imgproc.THRESH_BINARY)
                Imgproc.boxFilter(binary, coverage, CvType.CV_32FC1, size)
                Imgproc.boxFilter(expanded, mean, CvType.CV_32FC1, size)
                coverage.convertTo(coverage, -1, 1.0 / .22, -.72 / .22)
                Core.max(coverage, Scalar(0.0), coverage)
                Core.min(coverage, Scalar(1.0), coverage)
                coverage.convertTo(curve, -1, -2.0, 3.0)
                Core.multiply(coverage, coverage, coverage)
                Core.multiply(coverage, curve, coverage)
                Core.multiply(mean, coverage, mean)
                mean.convertTo(if (axis == 0) horizontal else vertical, CvType.CV_8UC1)
            }
            Core.max(horizontal, vertical, destination)
            Core.min(destination, ink, destination)
        } finally {
            owned.forEach(Mat::release)
        }
    }

    /** Local bright-paper field plus a smooth ink curve, preserving continuous faint strokes. */
    private fun localWorkbook(
        rgb: Mat,
        destination: Mat,
        strength: Float,
        detailOverride: Float?,
        strong: Boolean = false,
    ) {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val gray = mat()
        val field = mat()
        val smooth = mat()
        val inkMat = mat()
        val softened = mat()
        val neighbours = mat()
        val rules = mat()
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            val amount = strength.coerceIn(0f, 1f)
            if (amount <= 0f) {
                if (WorkbookContent.enabled()) rgb.copyTo(destination) else gray.copyTo(destination)
                return
            }
            val short = min(rgb.cols(), rgb.rows())
            val window =
                ((short * ScanConfig.float("workbookPaperWindow", .035f))
                    .roundToInt()
                    .coerceIn(15, 151) or 1)
            val useMedian = ScanConfig.int("workbookPaperMedian", 1) != 0
            val extent =
                if (useMedian) ScanConfig.int("workbookPaperDilate", 3).coerceIn(1, 9) or 1
                else window
            // Broad illumination needs no full-resolution median pass. Only its smooth
            // field is enlarged; normalization and stroke evidence use original pixels.
            // v200 shadow/ghost pass: the workbook page uses ONE continuous whole-page field
            // ([PaperField]) instead of the local window plus the 8% side-band max repair. The
            // repair is kept behind `paperFieldSmooth=0` only so the acceptance test can measure the
            // old piecewise gain on the same build.
            val smoothField = ScanConfig.int("paperFieldSmooth", 0) != 0
            if (smoothField) PaperField.estimateBytes(gray, smooth)
            else paperField(gray, smooth, window, extent, useMedian)
            val paperFloor = ScanConfig.int("workbookPaperFloor", 20).coerceIn(8, 100)
            val width = gray.cols()
            val count = width * gray.rows()
            val raw = ByteArray(count)
            val paper = ByteArray(count)
            gray.get(0, 0, raw)
            smooth.get(0, 0, paper)
            if (!smoothField) repairEdgePaper(paper, width, gray.rows())
            val normalizedInk = PaperPixelEngine.normalize(raw, paper, paperFloor)
            // Show-through is attenuated here, on the normalised ink plane, before the ink contrast
            // curve amplifies it; the rendered-page pass in finishWorkbook is only a safety net.
            val inkPlane =
                if (PaperGhost.enabled())
                    PaperGhost.attenuateInk(normalizedInk, width, gray.rows(), amount)
                else normalizedInk
            inkMat.create(gray.rows(), width, CvType.CV_8UC1)
            inkMat.put(0, 0, inkPlane)
            Imgproc.GaussianBlur(
                inkMat,
                softened,
                Size(0.0, 0.0),
                (short / 1000.0).coerceIn(.8, 2.4),
            )
            rulingEvidence(inkMat, rules)
            val detailKernel =
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).also(owned::add)
            Imgproc.dilate(inkMat, neighbours, detailKernel)
            val soft = ByteArray(count)
            val near = ByteArray(count)
            val rule = ByteArray(count)
            softened.get(0, 0, soft)
            neighbours.get(0, 0, near)
            rules.get(0, 0, rule)
            val noise =
                ScanConfig.float(
                    if (strong) "cleanInkNoise" else "workbookInkNoise",
                    if (strong) 22f else 13f,
                )
            val black =
                ScanConfig.float(
                        if (strong) "cleanInkBlack" else "workbookInkBlack",
                        if (strong) 115f else 145f,
                    )
                    .coerceAtLeast(noise + 1f)
            val gamma =
                ScanConfig.float(
                        if (strong) "cleanInkGamma" else "workbookInkGamma",
                        if (strong) 2.35f else 2.05f,
                    )
                    .coerceIn(1f, 5f)
            val requestedProtection =
                (detailOverride ?: ScanConfig.float("workbookDetailPreservation", .85f)).coerceIn(
                    0f,
                    1f,
                )
            val protect =
                requestedProtection *
                    if (strong) ScanConfig.float("cleanDetailProtection", .765f) else 1f
            val detailGain = ScanConfig.float("workbookSharpInkGain", 1.6f)
            val seedLow = ScanConfig.float("workbookInkSeedLow", 70f)
            val seedHigh = ScanConfig.float("workbookInkSeedHigh", 95f).coerceAtLeast(seedLow + 1f)
            val faintFloor =
                ScanConfig.float(
                    if (strong) "cleanFaintEvidenceFloor" else "workbookFaintEvidenceFloor",
                    if (strong) 36f else 26f,
                )
            val faintHigh =
                ScanConfig.float(
                        if (strong) "cleanFaintEvidenceHigh" else "workbookFaintEvidenceHigh",
                        if (strong) 96f else 84f,
                    )
                    .coerceAtLeast(faintFloor + 1f)
            val maximumFaint = ((requestedProtection - .85f) / .15f).coerceIn(0f, 1f)
            val outputFloor =
                ScanConfig.float(
                    if (strong) "cleanOutputInkFloor" else "workbookOutputInkFloor",
                    if (strong) 10f else 7f,
                ) * (1f - maximumFaint)
            val foreground = if (maximumFaint >= 1f) null else FastInkModel.confidence(inkMat)
            val output =
                PaperPixelEngine.workbook(
                    raw,
                    inkPlane,
                    soft,
                    near,
                    rule,
                    foreground,
                    floatArrayOf(
                        noise,
                        black,
                        gamma,
                        detailGain,
                        faintFloor,
                        faintHigh,
                        maximumFaint,
                        outputFloor,
                        protect,
                        seedLow,
                        seedHigh,
                        ScanConfig.float("workbookRuleFloor", 6f),
                        ScanConfig.float("workbookRuleRange", 14f).coerceAtLeast(1f),
                        ScanConfig.float("workbookRuleGain", 1.9f),
                        if (ScanConfig.int("workbookSmoothEvidence", 1) == 0) 0f else 1f,
                        if (WorkbookContent.enabled()) 1f else 1f - (1f - amount).pow(2),
                        ScanConfig.float("learnedInkFloor", .02f),
                        ScanConfig.float("learnedInkRange", .65f),
                        ScanConfig.float("learnedInkStrength", .9f) * (1f - maximumFaint),
                        ScanConfig.float("workbookRuleInkCeiling", 1.15f),
                    ),
                )
            if (WorkbookContent.enabled()) {
                val evidence =
                    WorkbookContent.protect(
                        rgb,
                        raw,
                        paper,
                        inkPlane,
                        soft,
                        rule,
                        output,
                        amount,
                        strong,
                        maximumFaint >= 1f,
                        learned = foreground,
                    )
                WorkbookContent.write(rgb, paper, evidence, destination, amount)
                EdgeInkDiagnostics.dump("06-content", destination)
                finishWorkbook(destination, amount)
                return
            }
            if (ScanConfig.int("workbookComponentCleanup", 1) == 1)
                InkComponents.clean(
                    output,
                    inkPlane,
                    rule,
                    width,
                    gray.rows(),
                    amount * (1f - maximumFaint),
                )
            InkComponents.cleanFaint(
                output,
                rule,
                foreground,
                width,
                gray.rows(),
                amount * (1f - maximumFaint),
            )
            destination.create(gray.rows(), width, CvType.CV_8UC1)
            destination.put(0, 0, output)
            finishWorkbook(destination, amount)
        } finally {
            owned.forEach(Mat::release)
        }
    }

    /**
     * Grey workbook rendering: a paper residual below the ghost floor counts as blank, so
     * reverse-side bleed disappears while thin pencil and printed rules keep their tone.
     */
    private fun workbookDocument(corrected: Mat, field: Mat, destination: Mat, strength: Float) {
        val gray = Mat()
        val paper = Mat()
        val out = Mat()
        val blurred = Mat()
        try {
            Imgproc.cvtColor(corrected, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.cvtColor(field, paper, Imgproc.COLOR_RGB2GRAY)
            val values = ByteArray(gray.rows() * gray.cols())
            val paperValues = ByteArray(values.size)
            gray.get(0, 0, values)
            paper.get(0, 0, paperValues)
            // A lower slider value must preserve more ink, not raise the erasure threshold.
            val floor = ScanConfig.float("workbookGhostKnee", Workbook.ghostFloor) * strength
            val gain =
                ScanConfig.float("workbookInkGain", Workbook.inkGain) * (.85f + .15f * strength)
            for (i in values.indices) {
                // Compare corrected ink against the corrected paper level, not the raw tile value:
                // the illumination correction has already lifted both, so mixing the scales would
                // read faint strokes as blank paper.
                val rawPaper = paperValues[i].toInt() and 255
                val paperTarget = Workbook.paperWhite / max(70, rawPaper)
                val correctedPaper =
                    rawPaper *
                        (1f + (paperTarget.coerceIn(.85f, Workbook.maximumGain) - 1f) * strength)
                val ink = (correctedPaper - (values[i].toInt() and 255)).coerceAtLeast(0f)
                // Attenuate low contrast bleed with a smooth knee rather than subtracting a
                // fixed residual from every stroke (which erased pencil and washed out print).
                val retention = if (floor <= 0f) 1f else ink / (ink + floor)
                val kept = ink * retention * gain
                val enhanced = (255f - kept).coerceIn(0f, 255f)
                val original = values[i].toInt() and 255
                values[i] = (original + (enhanced - original) * strength).roundToInt().toByte()
            }
            out.create(gray.rows(), gray.cols(), CvType.CV_8UC1)
            out.put(0, 0, values)
            Imgproc.GaussianBlur(out, blurred, Size(0.0, 0.0), 1.0)
            val sharp = ByteArray(values.size)
            val low = ByteArray(values.size)
            out.get(0, 0, sharp)
            blurred.get(0, 0, low)
            for (i in sharp.indices) {
                val value = sharp[i].toInt() and 255
                val detail = value - (low[i].toInt() and 255)
                if (abs(detail) > 2)
                    sharp[i] =
                        (value + detail * Workbook.sharpenAmount).toInt().coerceIn(0, 255).toByte()
            }
            out.put(0, 0, sharp)
            out.copyTo(destination)
            finishWorkbook(destination, strength)
        } finally {
            gray.release()
            paper.release()
            out.release()
            blurred.release()
        }
    }

    /** Local contrast and a thresholded unsharp mask affect luminance, preserving chroma. */
    private fun enhanceLuminance(rgb: Mat, destination: Mat, mode: String, strength: Float) {
        val ycc = Mat()
        val blurred = Mat()
        val channels = mutableListOf<Mat>()
        val clahe = Imgproc.createCLAHE(if (mode == "TEXT") 1.35 else 1.25, Size(8.0, 8.0))
        try {
            Imgproc.cvtColor(rgb, ycc, Imgproc.COLOR_RGB2YCrCb)
            Core.split(ycc, channels)
            val original = channels[0].clone()
            try {
                clahe.apply(original, channels[0])
                // Blend avoids flattening pencil and avoids amplifying noise like full CLAHE.
                val blend =
                    strength *
                        (if (mode == "TEXT") ScanConfig.float("textContrastBlend", .32f).toDouble()
                        else if (mode == "COLOR") .42 else .28)
                Core.addWeighted(channels[0], blend, original, 1.0 - blend, 0.0, channels[0])
                Imgproc.GaussianBlur(channels[0], blurred, Size(0.0, 0.0), .85)
                val data = ByteArray(channels[0].rows() * channels[0].cols())
                val low = ByteArray(data.size)
                channels[0].get(0, 0, data)
                blurred.get(0, 0, low)
                val amount =
                    (if (mode == "TEXT") ScanConfig.float("textSharpenAmount", .28f) else .22f) *
                        strength
                for (i in data.indices) {
                    val value = data[i].toInt() and 255
                    val detail = value - (low[i].toInt() and 255)
                    if (abs(detail) > 3)
                        data[i] = (value + detail * amount).roundToInt().coerceIn(0, 255).toByte()
                }
                channels[0].put(0, 0, data)
                Core.merge(channels, ycc)
                Imgproc.cvtColor(ycc, destination, Imgproc.COLOR_YCrCb2RGB)
            } finally {
                original.release()
            }
        } finally {
            channels.forEach { it.release() }
            ycc.release()
            blurred.release()
            clahe.collectGarbage()
        }
    }

    private fun percentile(hist: IntArray, count: Int, fraction: Float): Int {
        val target = max(1, (count * fraction).toInt())
        var sum = 0
        for (i in hist.indices) {
            sum += hist[i]
            if (sum >= target) return i
        }
        return 255
    }
}
