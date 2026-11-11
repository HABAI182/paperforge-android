package cn.paperforge.app.imaging

import android.content.Context
import android.graphics.Bitmap
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Observed paper geometry first, learned foreground second; original pixels and map remain
 * available.
 */
object FlatDocument {
    const val VERSION = "flat-paper-13"

    data class Result(
        val bitmap: Bitmap,
        val grid: DewarpGrid,
        val lines: Int,
        val algorithm: String,
        val diagnostics: String? = null,
    )

    internal fun sourcePaper(source: Bitmap): Bitmap {
        val rgba = Mat()
        val rgb = Mat()
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val prepared =
                WorkbookPaperBoundary.prepare(rgb, paperTone = false, refineFinger = false)
                    ?: return source.copy(Bitmap.Config.ARGB_8888, false)
            try {
                return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                    .also { Utils.matToBitmap(prepared, it) }
            } finally {
                prepared.release()
            }
        } finally {
            rgba.release()
            rgb.release()
        }
    }

    fun geometry(source: Bitmap, samplingSource: Bitmap = source): PageDewarp.Result {
        if (ScanConfig.int("workbookIntelligentGeometry", 1) != 0)
            return intelligentGeometry(source, samplingSource)
        val result = legacyGeometry(source)
        if (samplingSource === source) return result
        return try {
            result.copy(
                bitmap =
                    DewarpTrace.sample(
                        samplingSource,
                        result.grid,
                        result.bitmap.width,
                        result.bitmap.height,
                    )
            )
        } finally {
            result.bitmap.recycle()
        }
    }

    private fun intelligentGeometry(source: Bitmap, samplingSource: Bitmap): PageDewarp.Result {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val rgba = mat()
        val thumbnail = mat()
        val preview = mat()
        val sparse = mat()
        val dense = mat()
        val unused = mat()
        val out = mat()
        val w = source.width
        val h = source.height
        val side = 240
        try {
            Utils.bitmapToMat(source, rgba)
            // A wrong crop fans the rules out, which the later stages read as no structure at all or
            // as waves. They estimate from the corrected page; the source is still sampled only once.
            val rectified = RuleRectify.estimate(rgba)
            rectified?.warp(rgba, rgba)
            val textScale =
                minOf(1.0, ScanConfig.int("flatTextAnalysisEdge", 800).toDouble() / maxOf(w, h))
            Imgproc.resize(rgba, thumbnail, Size(), textScale, textScale, Imgproc.INTER_AREA)
            val probe = WorkbookStructure.analyze(thumbnail, textFallback = false)
            val textEstimate =
                if (probe.horizontal.confidence < .7 && ScanConfig.int("flatTextCurves", 1) != 0)
                    TextLineDewarp.estimate(thumbnail, 1f)
                else null
            // Distributed long rules or broad text-ridges justify the cost of ruled tracking.
            // Printed paragraphs/code use the already estimated text grid directly.
            val ruled =
                if (probe.horizontal.confidence >= .7 || (textEstimate?.meanSpan ?: 0.0) >= .75)
                    RuledPageRefinement.estimate(
                        rgba,
                        ScanConfig.int("ruledHeaderExtension", 1) != 0,
                    )
                else null
            val text =
                if (ruled == null && ScanConfig.int("flatTextCurves", 1) != 0)
                    textEstimate?.takeIf {
                        it.lines >= 8 && it.grid.strength > 0f && it.baselineMad <= .5
                    }
                else null
            val curveActive =
                (ruled != null &&
                    ruled.maximumShift >= ScanConfig.float("flatIdentityTolerance", 1.5f)) ||
                    text != null
            fun base(point: PointN): PointN =
                if (curveActive && point.x in 0f..1f && point.y in 0f..1f)
                    ruled?.inputPoint(point) ?: text!!.grid.inputPoint(point)
                else point
            fun map(width: Int, height: Int, transform: (PointN) -> PointN): FloatArray {
                val values = FloatArray(side * side * 2)
                for (y in 0 until side) for (x in 0 until side) {
                    val p =
                        transform(
                            PointN(
                                (((x + .5f) * width / side - .5f) / (width - 1)).coerceIn(0f, 1f),
                                (((y + .5f) * height / side - .5f) / (height - 1)).coerceIn(0f, 1f),
                            )
                        )
                    val i = 2 * (y * side + x)
                    values[i] = p.x
                    values[i + 1] = p.y
                }
                return values
            }
            fun remap(input: Mat, output: Mat, values: FloatArray) {
                // Cache JNI properties: this loop has 115,200 entries, also in thumbnails.
                val inputWidth = input.cols()
                val inputHeight = input.rows()
                sparse.create(side, side, CvType.CV_32FC2)
                sparse.put(
                    0,
                    0,
                    FloatArray(values.size) { i ->
                        values[i] * (if (i % 2 == 0) inputWidth - 1 else inputHeight - 1)
                    },
                )
                Imgproc.resize(sparse, dense, input.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                Imgproc.remap(
                    input,
                    output,
                    dense,
                    unused,
                    Imgproc.INTER_CUBIC,
                    Core.BORDER_CONSTANT,
                    Scalar.all(255.0),
                )
            }
            val scale = minOf(1.0, 900.0 / maxOf(w, h))
            Imgproc.resize(rgba, thumbnail, Size(), scale, scale, Imgproc.INTER_AREA)
            if (curveActive)
                remap(thumbnail, preview, map(thumbnail.cols(), thumbnail.rows(), ::base))
            else thumbnail.copyTo(preview)
            // Analyze the coarse curved-grid result so global skew is never corrected twice.
            val structure = WorkbookStructure.analyze(preview, textFallback = text == null)
            val affine = WorkbookStructure.Mapping(w, h, structure)
            // Residual rigid tilt and low-frequency bend after the existing curve + affine
            // stage. The estimate runs on a small aligned preview; the final resample below
            // still samples the untouched source exactly once.
            val aligned = mat()
            var straighten: GridStraighten.Estimate? = null
            if (ScanConfig.int("gridStraighten", 1) != 0) {
                val alignedValues =
                    map(thumbnail.cols(), thumbnail.rows()) {
                        base(if (structure.active) affine.input(it) else it)
                    }
                remap(thumbnail, aligned, alignedValues)
                straighten = GridStraighten.analyze(aligned)?.takeIf(GridStraighten::acceptable)
            }
            // Lines still tilted after every stage above: an exam printed askew on its sheet. See [TextLevel].
            fun corrected(point: PointN): PointN {
                val mapped = straighten?.correction?.inputPoint(point.x, point.y)
                val turned = if (mapped == null) point else PointN(mapped[0], mapped[1])
                return base(if (structure.active) affine.input(turned) else turned)
            }
            var level: TextLevel.Estimate? = null
            if (ScanConfig.int("textLevel", 1) != 0) {
                val levelPreview = mat()
                remap(thumbnail, levelPreview, map(thumbnail.cols(), thumbnail.rows(), ::corrected))
                level = TextLevel.estimate(levelPreview)
            }
            val diagnostics =
                structure
                    .json()
                    .put("ruleRectifyLines", rectified?.fitted ?: 0)
                    .put("ruleRectifyMove", rectified?.moved ?: 0.0)
                    .put("curvedGrid", curveActive)
                    .put("ruledLines", ruled?.lines ?: 0)
                    .put("textCurveLines", text?.lines ?: 0)
                    .put("headerUniformFit", ruled?.fit ?: 1f)
                    .put("headerExtension", ruled?.topExtension ?: 0f)
                    .put("footerExtension", ruled?.bottomExtension ?: 0f)
                    .put("straighten", JSONObject(GridStraighten.json(straighten)))
                    .put("textLevel", level?.json() ?: JSONObject().put("active", false))
                    .put(
                        "fullResolutionResamples",
                        if (rectified != null || curveActive || structure.active || straighten != null || level != null) 1 else 0,
                    )
                    .put("config", ScanConfig.fingerprint())
                    .toString()
            if (rectified == null && !curveActive && !structure.active && straighten == null && level == null)
                return PageDewarp.Result(
                    samplingSource.copy(Bitmap.Config.ARGB_8888, false),
                    DewarpGrid(w, h, 0f, 2, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)),
                    if (
                        ruled != null ||
                            structure.horizontal.confidence >= .7 ||
                            structure.vertical.confidence >= .7
                    )
                        "纸面已经平整"
                    else "缺少可靠结构，保留原图",
                    ruled?.lines ?: 0,
                    WorkbookStructure.VERSION + "+identity",
                    diagnostics,
                )
            val values =
                map(w, h) { point ->
                    val page = corrected(level?.input(point) ?: point)
                    rectified?.input(page) ?: page
                }
            // Estimate geometry from the clean probe, but sample untouched colour once. Skin
            // and shadow cues must remain available to the final appearance/edge cleanup.
            if (samplingSource !== source || rectified != null) Utils.bitmapToMat(samplingSource, rgba)
            remap(rgba, out, values)
            val image =
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    Utils.matToBitmap(out, it)
                }
            val algorithm =
                buildString {
                    if (rectified != null) append(RuleRectify.VERSION).append("+")
                    append(WorkbookStructure.VERSION)
                    append(
                        if (ruled != null && curveActive) "+" + RuledPageRefinement.VERSION
                        else if (text != null) "+text-curves" else "+affine"
                    )
                    if (straighten != null) append("+").append(GridStraighten.VERSION)
                    if (level != null) append("+").append(TextLevel.VERSION)
                }
            return PageDewarp.Result(
                image,
                DewarpGrid(w, h, 1f, side, values),
                if (straighten != null) "结构感知校正＋长线拉直" else "结构感知校正",
                ruled?.lines ?: text?.lines ?: structure.horizontal.count,
                algorithm,
                diagnostics,
            )
        } finally {
            owned.forEach(Mat::release)
        }
    }

    private fun legacyGeometry(source: Bitmap): PageDewarp.Result {
        val mat = Mat()
        val sparse = Mat()
        val dense = Mat()
        val unused = Mat()
        val out = Mat()
        try {
            Utils.bitmapToMat(source, mat)
            val ruled =
                RuledPageRefinement.estimate(mat)
                    ?: return PageDewarp.process(source, 1f)
            val w = source.width
            val h = source.height
            if (ruled.maximumShift < ScanConfig.float("flatIdentityTolerance", 1.5f))
                return PageDewarp.Result(
                    source.copy(Bitmap.Config.ARGB_8888, false),
                    DewarpGrid(w, h, 0f, 2, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)),
                    "纸面已经平整",
                    ruled.lines,
                    RuledPageRefinement.VERSION + "+identity",
                )
            val side = 240
            val values = FloatArray(side * side * 2)
            for (y in 0 until side) for (x in 0 until side) {
                val point =
                    PointN(
                        (((x + .5f) * w / side - .5f) / (w - 1)).coerceIn(0f, 1f),
                        (((y + .5f) * h / side - .5f) / (h - 1)).coerceIn(0f, 1f),
                    )
                val input = ruled.inputPoint(point)
                val i = 2 * (y * side + x)
                values[i] = input.x
                values[i + 1] = input.y
            }
            val mapping = DewarpGrid(w, h, 1f, side, values)
            sparse.create(side, side, CvType.CV_32FC2)
            sparse.put(
                0,
                0,
                FloatArray(values.size) { i -> values[i] * (if (i % 2 == 0) w - 1 else h - 1) },
            )
            Imgproc.resize(sparse, dense, mat.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.remap(mat, out, dense, unused, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, image)
            return PageDewarp.Result(
                image,
                mapping,
                "${ruled.lines} 条纸纹展平",
                ruled.lines,
                RuledPageRefinement.VERSION,
            )
        } finally {
            listOf(mat, sparse, dense, unused, out).forEach(Mat::release)
        }
    }

    /**
     * A small photo's page is enlarged before it is flattened and toned, as the reference scanner's are: from a
     * 1440 x 1920 photo its page was 1690 x 2481. More pixels to a stroke give it a smooth, full edge.
     * Returns the bitmap to work on, the same one when nothing is enlarged, and how many output pixels
     * one pixel of the photo became, at most `captureUpscale`.
     */
    fun workSource(source: Bitmap, workEdge: Int): Pair<Bitmap, Float> {
        val edge = maxOf(source.width, source.height)
        val factor = minOf(workEdge.toFloat() / edge, ScanConfig.float("captureUpscale", 1.6f))
        if (factor <= 1.02f) return source to 1f
        val width = (source.width * factor).roundToInt()
        val height = (source.height * factor).roundToInt()
        val from = Mat()
        val to = Mat()
        return try {
            Utils.bitmapToMat(source, from)
            Imgproc.resize(from, to, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(to, it) } to
                width.toFloat() / source.width
        } finally {
            from.release()
            to.release()
        }
    }

    suspend fun process(
        context: Context,
        source: Bitmap,
        strength: Float = 1f,
        progress: suspend (Int) -> Unit = {},
        aspectOverride: Float? = null,
        ai: Boolean = true,
        automatic: Boolean = false,
        exactFrame: Boolean = false,
        sourceScale: Float = 1f,
        stackFrame: Boolean = false,
    ): Result =
        withContext(Dispatchers.Default) {
            val started = android.os.SystemClock.elapsedRealtime()
            if (strength <= 0f)
                return@withContext Result(
                    source.copy(Bitmap.Config.ARGB_8888, false),
                    DewarpGrid(
                        source.width,
                        source.height,
                        0f,
                        2,
                        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
                    ),
                    0,
                    VERSION,
                )
            progress(3)
            val preparedSource = sourcePaper(source)
            val preparedAt = android.os.SystemClock.elapsedRealtime()
            val shape =
                try {
                    geometry(preparedSource, source)
                } finally {
                    preparedSource.recycle()
                }
            progress(18)
            val geometryAt = android.os.SystemClock.elapsedRealtime()
            var steps: JSONObject? = null
            val clean =
                try {
                    if (ai && !automatic)
                        NeuralPaper.apply(context, shape.bitmap, strength) {
                            progress(8 + it * 78 / 100)
                        }
                    else {
                        val rgba = Mat()
                        val rgb = Mat()
                        val out = Mat()
                        try {
                            Utils.bitmapToMat(shape.bitmap, rgba)
                            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                            steps =
                                StepTimes.record {
                                        FastInkModel.scoped(!automatic || ai) {
                                            ToneZones.withSourceScale(sourceScale) {
                                                ToneZones.withStackFrame(stackFrame) {
                                                    ToneZones.withExactFrame(exactFrame) {
                                                        EnhancePipeline.apply(rgb, out, "WORKBOOK", strength)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .second
                            Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
                                .also { Utils.matToBitmap(out, it) }
                        } finally {
                            listOf(rgba, rgb, out).forEach(Mat::release)
                        }
                    }
                } finally {
                    shape.bitmap.recycle()
                }
            val boundaryEvidence = WorkbookPaperBoundary.diagnostics()
            progress(86)
            val detail = clean
            val finished =
                if (
                    ai &&
                        !automatic &&
                        maxOf(detail.width, detail.height) <
                            ScanConfig.int("flatSuperResolutionBelow", 2000)
                ) {
                    try {
                        ScanRetouch.superResolve(
                            context,
                            detail,
                            IntArray(detail.width * detail.height) { -1 },
                            ScanConfig.float("flatSuperResolutionStrength", .55f) * strength,
                            { progress(93 + it * 7 / 100) },
                            ScanConfig.int("flatWorkEdge", 2400),
                        )
                    } finally {
                        detail.recycle()
                    }
                } else detail
            val aspect =
                aspectOverride
                    ?: if (
                        ScanConfig.int("workbookIntelligentGeometry", 1) == 0 &&
                            shape.lines >= 10 &&
                            source.width < source.height
                    )
                        ScanConfig.float("flatPortraitAspect", .70710678f)
                    else 0f
            val normalized =
                if (
                    aspect > 0f &&
                        kotlin.math.abs(finished.width.toFloat() / finished.height - aspect) > .015f
                ) {
                    val edge = maxOf(finished.width, finished.height)
                    Bitmap.createScaledBitmap(
                            finished,
                            if (aspect < 1f) (edge * aspect).roundToInt() else edge,
                            if (aspect < 1f) edge else (edge / aspect).roundToInt(),
                            true,
                        )
                        .also { if (it !== finished) finished.recycle() }
                } else finished
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            val diagnostics =
                JSONObject(shape.diagnostics ?: "{}")
                    .put("automatic", automatic)
                    .put("processingMs", elapsed)
                    .put("sourcePreparationMs", preparedAt - started)
                    .put("geometryMs", geometryAt - preparedAt)
                    .put("appearanceMs", android.os.SystemClock.elapsedRealtime() - geometryAt)
                    .put("paperBoundary", boundaryEvidence)
                    .put("edge", maxOf(source.width, source.height))
                    .put("sourceScale", sourceScale.toDouble())
                    .put("steps", steps)
                    .put("heapMb", Runtime.getRuntime().maxMemory() / (1 shl 20))
                    .put(
                        "foregroundBackend",
                        if (automatic && ai) "paper-ink-v1-bounded"
                        else if (ai) "docres" else "local",
                    )
                    .toString()
            Result(normalized, shape.grid, shape.lines, shape.algorithm, diagnostics)
        }

    suspend fun prepare(
        repo: Repository,
        page: PageRow,
        ai: Boolean = true,
        portraitAspect: Float? = null,
        progress: suspend (Int) -> Unit,
        workEdge: Int = ScanConfig.int("flatWorkEdge", 2400),
        automatic: Boolean = false,
    ): PageRow {
        val exactFrame = page.hasExactCropFrame()
        val stackFrame = page.detectionMethod?.contains(SheetEdge.VERSION) == true
        val rendered =
            ScanProcessor.render(
                PageResources.sourceAppearance(page)
                    .copy(filter = "ORIGINAL", brightness = 0f, contrast = 1f),
                workEdge,
            )
        // The enlarged page is also the saved input, so the geometry replays on the same pixels.
        val (source, sourceScale) =
            if (automatic) workSource(rendered, workEdge).also { if (it.first !== rendered) rendered.recycle() }
            else rendered to 1f
        val folder = repo.folder(page.documentId)
        val key = UUID.randomUUID().toString()
        val input = File(folder, "$key-flat-input.png")
        val output = File(folder, "$key-flat.png")
        val grid = File(folder, "$key.pfgrid.gz")
        var success = false
        try {
            val result =
                process(
                    repo.context,
                    source,
                    1f,
                    progress,
                    page.outputAspect.takeIf { it > 0f } ?: portraitAspect?.takeIf { it == 0f },
                    ai,
                    automatic,
                    exactFrame,
                    sourceScale,
                    stackFrame,
                )
            try {
                output.outputStream().use {
                    check(result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                result.bitmap.recycle()
            }
            input.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            result.grid.write(grid)
            val metadata =
                JSONObject(
                        PageResources.withDewarp(
                            page,
                            input,
                            grid,
                            output,
                            1f,
                            VERSION,
                            result.algorithm,
                        )
                    )
                    .put(
                        "flatDocument",
                        JSONObject()
                            .put("version", VERSION)
                            .put("exactFrame", exactFrame)
                            .put("appearanceModel", 2)
                            .put("bakedFilter", "WORKBOOK")
                            .put("bakedStrength", 1f)
                            .put("sourceScale", sourceScale.toDouble())
                            .put("enhancement", ScanConfig.fingerprint())
                            .put("structure", result.diagnostics?.let(::JSONObject))
                            .put("foregroundSha256", if (ai && !automatic) NeuralPaper.SHA else "")
                            .put("ai", ai)
                            .put(
                                "foregroundCompositor",
                                if (ai && !automatic) NeuralPaper.COMPOSITOR_VERSION
                                else if (ScanConfig.int("learnedInk", 1) == 1 && (!automatic || ai))
                                    "paper-ink-v1+local-workbook"
                                else "local-workbook",
                            )
                            .put(
                                "paperPolicy",
                                if (page.outputAspect > 0f) "EXPLICIT_ASPECT"
                                else if (
                                    portraitAspect == 0f ||
                                        ScanConfig.int("workbookIntelligentGeometry", 1) != 0
                                )
                                    "SOURCE"
                                else "RULED_ISO",
                            )
                            .put("lines", result.lines),
                    )
            success = true
            return page.copy(
                original = output.path,
                corners = Quad.Full.encode(),
                rotation = 0,
                deskewDegrees = 0f,
                outputAspect = 0f,
                filter = "WORKBOOK",
                enhanceStrength = 1f,
                brightness = 0f,
                contrast = 1f,
                detectionMethod = "flat-document",
                ocrText = null,
                ocrLayout = null,
                processingMetadata = metadata.toString(),
            )
        } finally {
            source.recycle()
            if (!success) listOf(input, output, grid).forEach { it.delete() }
        }
    }
}
