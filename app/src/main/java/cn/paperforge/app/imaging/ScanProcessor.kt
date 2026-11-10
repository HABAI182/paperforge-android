package cn.paperforge.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.data.workbookDetail
import cn.paperforge.app.model.CaptureReference
import cn.paperforge.app.model.Quad
import cn.paperforge.app.model.StillCrop
import java.io.File
import kotlin.math.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object ScanProcessor {
    val filters = listOf("ORIGINAL", "WORKBOOK", "CLEAN", "GRAY")

    fun decode(file: File, maxEdge: Int = 2400): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片" }
        var sample = 1
        sample =
            cn.paperforge.app.model.ImageGeometry.decodePlan(
                    bounds.outWidth,
                    bounds.outHeight,
                    max(bounds.outWidth, bounds.outHeight).toFloat(),
                    maxEdge,
                    ScanConfig.int("maxDecodePixels", 18_000_000).toLong(),
                )
                .sample
                .let { if (bounds.outMimeType == "image/png") PageDecoder.pngSample(it) else it }
        val bitmap =
            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: error("图片解码失败")
        val exif = ExifInterface(file)
        val m = Matrix()
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            2 -> m.setScale(-1f, 1f)
            3 -> m.setRotate(180f)
            4 -> m.setScale(1f, -1f)
            5 -> {
                m.setRotate(90f)
                m.postScale(-1f, 1f)
            }
            6 -> m.setRotate(90f)
            7 -> {
                m.setRotate(270f)
                m.postScale(-1f, 1f)
            }
            8 -> m.setRotate(270f)
        }
        val upright =
            if (m.isIdentity) bitmap
            else
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true).also {
                    if (it !== bitmap) bitmap.recycle()
                }
        if (max(upright.width, upright.height) <= maxEdge) return upright
        val source = Mat()
        val resized = Mat()
        try {
            Utils.bitmapToMat(upright, source)
            val ratio = maxEdge.toDouble() / max(upright.width, upright.height)
            Imgproc.resize(source, resized, Size(), ratio, ratio, Imgproc.INTER_AREA)
            return Bitmap.createBitmap(resized.cols(), resized.rows(), Bitmap.Config.ARGB_8888)
                .also { Utils.matToBitmap(resized, it) }
        } finally {
            upright.recycle()
            source.release()
            resized.release()
        }
    }

    /**
     * [bitmap] shrunk to [maxEdge] by area averaging, as a new bitmap; [bitmap] is left to the caller. A
     * bilinear shrink by several times skips pixels, and a page's thin lines came out dotted.
     */
    fun shrunk(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val source = Mat()
        val resized = Mat()
        try {
            Utils.bitmapToMat(bitmap, source)
            val ratio = maxEdge.toDouble() / max(bitmap.width, bitmap.height)
            Imgproc.resize(source, resized, Size(), ratio, ratio, Imgproc.INTER_AREA)
            return Bitmap.createBitmap(resized.cols(), resized.rows(), Bitmap.Config.ARGB_8888)
                .also { Utils.matToBitmap(resized, it) }
        } finally {
            source.release()
            resized.release()
        }
    }

    fun detect(bitmap: Bitmap): Quad? = detectResult(bitmap).quad

    fun detectResult(
        bitmap: Bitmap,
        preview: Boolean = false,
        detectorOverride: String? = null,
    ): DetectionResult =
        Mat().let { mat ->
            try {
                Utils.bitmapToMat(bitmap, mat)
                LearnedDocumentDetector.detect(mat, preview, detectorOverride)
            } finally {
                mat.release()
            }
        }

    fun detectMat(source: Mat): Quad? = DocumentDetector.detect(source, preview = true).quad

    fun detectFile(
        file: File,
        previewQuad: Quad? = null,
        reference: CaptureReference? = null,
        detectorOverride: String? = null,
        lockReference: Boolean = cn.paperforge.app.UserSettings[SettingKeys.LockCrop],
    ): DetectionResult {
        val times = stageTimes.get().apply { clear() }
        var mark = System.nanoTime()
        fun lap(name: String) {
            val now = System.nanoTime()
            times[name] = (now - mark) / 1e6
            mark = now
        }
        // Corners are fractions of the photo, so detection needs no more than this: every stage works at
        // 900-1500 pixels anyway, and a full-size decode only cost time and memory.
        val image = decode(file, ScanConfig.int("detectDecodeEdge", 2000))
        val mat = Mat()
        return try {
            Utils.bitmapToMat(image, mat)
            lap("decode")
            val detected = detectDecoded(image, mat, previewQuad, reference, detectorOverride, lockReference)
            lap("detect")
            val outline = outlined(mat, detected)
            lap("outline")
            framedByRules(mat, outline).also { lap("frame") }
        } finally {
            mat.release()
            image.recycle()
        }
    }

    /** Milliseconds per stage of the last [detectFile] on this thread, for benchmarks. */
    val stageTimes: ThreadLocal<MutableMap<String, Double>> = ThreadLocal.withInitial { linkedMapOf() }

    /**
     * The page a capture of [frame] (RGBA, upright) would be cut along: the same detection, edge check and
     * top-sheet framing as [detectFile], on a camera frame. The camera shows it once the view holds still, so
     * the outline on screen is the crop that the capture makes.
     */
    fun detectFrame(frame: Mat): DetectionResult =
        framedByRules(frame, outlined(frame, LearnedDocumentDetector.detect(frame, preview = false)))

    private fun outlined(image: Bitmap, detection: DetectionResult): DetectionResult {
        val mat = Mat()
        return try {
            Utils.bitmapToMat(image, mat)
            outlined(mat, detection)
        } finally {
            mat.release()
        }
    }

    /** Takes the page's own outline when the detector's quad has a side with no edge along it. See [EdgeQuad]. */
    private fun outlined(mat: Mat, detection: DetectionResult): DetectionResult {
        val quad = detection.quad ?: return detection
        return EdgeQuad.check(mat, quad)?.let { detection.copy(quad = it, method = detection.method + "+" + EdgeQuad.VERSION) } ?: detection
    }

    private fun framedByRules(image: Bitmap, detection: DetectionResult): DetectionResult {
        val mat = Mat()
        return try {
            Utils.bitmapToMat(image, mat)
            framedByRules(mat, detection)
        } finally {
            mat.release()
        }
    }

    /**
     * Moves the corners onto the top sheet when the page's rules show where it is, or, on a page without
     * rules, when the top sheet's own edges cast shadow lines on the sheets below.
     */
    private fun framedByRules(mat: Mat, detection: DetectionResult): DetectionResult {
        val quad = detection.quad ?: return detection
        // The page's own outline is already its edge: looking for a top sheet inside it found a drawing's straight
        // bottom line and cut the drawing there.
        val outlined = detection.method.contains(EdgeQuad.VERSION)
        return run {
            val framed =
                RuleFrame.refine(mat, quad)
                    ?: return (if (outlined) null else SheetEdge.refine(mat, quad))?.let {
                        detection.copy(
                            quad = it.quad,
                            method = detection.method + "+" + SheetEdge.VERSION,
                            notes = detection.notes + mapOf("sheetEdgeSides" to it.sides),
                        )
                    } ?: detection
            return detection.copy(
                quad = framed.quad,
                method = detection.method + "+" + RuleFrame.VERSION,
                notes =
                    detection.notes +
                        mapOf("ruleFrameLeft" to framed.left, "ruleFrameRight" to framed.right),
            )
        }
    }

    private fun detectDecoded(
        image: Bitmap,
        mat: Mat,
        previewQuad: Quad?,
        reference: CaptureReference?,
        detectorOverride: String?,
        lockReference: Boolean,
    ): DetectionResult {
        return run {
            if (previewQuad != null && previewQuad.valid() && lockReference) {
                run {
                    if (reference != null) {
                        val signature = CameraQualityAnalyzer.analyze(mat, previewQuad)
                        val reason =
                            reference.reason(
                                image.width,
                                image.height,
                                signature.sceneSignature,
                                signature.signature,
                            )
                        if (reason != "matched") {
                            val fresh =
                                LearnedDocumentDetector.detect(
                                    mat,
                                    preview = false,
                                    engineOverride = detectorOverride,
                                )
                            val shift =
                                fresh.quad
                                    ?.takeIf { it.valid() }
                                    ?.let { StillCrop.maxCornerShift(previewQuad, it) } ?: -1f
                            return fresh.copy(
                                method = fresh.method + ";fresh-after-$reason",
                                diagnostics =
                                    fresh.diagnostics +
                                        mapOf(
                                            "referenceAgeMs" to reference.age.toFloat(),
                                            "referenceReused" to 0f,
                                            "stillMaxCornerShift" to shift,
                                            "stillAdopted" to if (fresh.quad != null) 1f else 0f,
                                        ),
                                notes = fresh.notes + mapOf("stillReason" to "reference-$reason"),
                            )
                        }
                    }
                    val refined =
                        if (reference != null && ScanConfig.int("pageNetRefine", 1) == 0) previewQuad
                        else DocumentDetector.refine(mat, previewQuad)
                    // Callers that only pass a hint (imports, manual retry) keep the exact old
                    // viewfinder-refine behaviour; there is no capture reference to compare with.
                    if (reference == null) {
                        return DetectionResult(
                            refined,
                            .85f,
                            "viewfinder+refined",
                            mapOf("referenceAgeMs" to -1f, "referenceReused" to 1f),
                        )
                    }
                    // Second, independent detection on the saved still. The viewfinder quad is the
                    // prior only for the fallback; the still result wins when the two disagree by
                    // more than the configured shift and it is confident enough (#30).
                    val still =
                        LearnedDocumentDetector.detect(
                            mat,
                            preview = false,
                            engineOverride = detectorOverride,
                        )
                    val decision =
                        StillCrop.decide(
                            previewQuad,
                            reference.confidence,
                            still.quad,
                            still.confidence,
                            refined,
                            ScanConfig.float("stillRefineShift", StillCrop.DefaultShiftThreshold),
                            ScanConfig.float(
                                "stillRefineMinConfidence",
                                StillCrop.DefaultMinConfidence,
                            ),
                        )
                    DetectionResult(
                        decision.quad,
                        if (decision.adoptedStill) still.confidence else reference.confidence,
                        (if (decision.adoptedStill) "still-verified+" + still.method
                        else "preview-verified+refined") + ";" + decision.reason,
                        still.diagnostics +
                            decision.diagnostics() +
                            mapOf(
                                "referenceAgeMs" to reference.age.toFloat(),
                                "referenceReused" to 1f,
                                "stillDetectMs" to still.elapsedMs,
                            ),
                        notes = mapOf("stillReason" to decision.reason),
                    )
                }
            } else LearnedDocumentDetector.detect(mat, false, detectorOverride)
        }
    }

    fun render(
        page: PageRow,
        maxEdge: Int = 2400,
        ocrSampling: Boolean = false,
        ocrInput: Boolean = false,
    ): Bitmap {
        val normalized = cn.paperforge.app.data.PageResources.normalizedAppearance(page)
        if (normalized != page) return render(normalized, maxEdge, ocrSampling, ocrInput)
        val metadata = cn.paperforge.app.data.PageResources.metadata(page)
        val flat = metadata.optJSONObject("flatDocument")
        val bakedFingerprint = flat?.optString("enhancement").orEmpty()
        val stale = bakedFingerprint.isNotBlank() && bakedFingerprint != ScanConfig.fingerprint()
        val retainedTrace =
            if (stale)
                runCatching { DewarpTrace.load(page) }.getOrNull()?.takeIf { it.input.isFile }
            else null
        if (
            !ocrInput &&
                flat != null &&
                (!stale || retainedTrace == null) &&
                page.filter == flat.optString("bakedFilter") &&
                kotlin.math.abs(
                    page.enhanceStrength - flat.optDouble("bakedStrength", 1.0).toFloat()
                ) < .0001f
        ) {
            // Reuse the completed appearance under its real filter, never enhance it twice.
            metadata.remove("flatDocument")
            return render(
                page.copy(filter = "ORIGINAL", processingMetadata = metadata.toString()),
                maxEdge,
                ocrSampling,
            )
        }
        if (flat != null) {
            val trace = retainedTrace ?: DewarpTrace.load(page)
            if (trace != null) {
                val source =
                    if (page.filter == "ORIGINAL") decode(trace.input, maxEdge)
                    else trace.sourceAppearance(maxEdge, cleanPaper = false)
                try {
                    return transform(
                        source,
                        Quad.decode(page.corners),
                        if (ocrInput) "ORIGINAL" else page.filter,
                        page.rotation,
                        page.brightness,
                        page.contrast,
                        maxEdge,
                        page.enhanceStrength,
                        page.outputAspect.takeIf { it > 0f }
                            ?: cn.paperforge.app.model.ImageGeometry.aspect(
                                Quad.decode(page.corners),
                                source.width,
                                source.height,
                                PageDecoder.focalShare(File(page.original)),
                            ),
                        if (ocrSampling) Imgproc.INTER_CUBIC else Imgproc.INTER_LINEAR,
                        if (ocrSampling) 1.5f else 1f,
                        page.workbookDetail(),
                        page.deskewDegrees,
                        !page.framedByRules(),
                    )
                } finally {
                    source.recycle()
                }
            }
        }
        val decoded = PageDecoder.crop(File(page.original), Quad.decode(page.corners), maxEdge)
        try {
            if (
                !ocrSampling &&
                    Quad.decode(page.corners) == Quad.Full &&
                    (page.filter == "ORIGINAL" || ocrInput) &&
                    page.rotation == 0 &&
                    page.deskewDegrees == 0f &&
                    page.brightness == 0f &&
                    page.contrast == 1f &&
                    page.outputAspect == 0f
            ) {
                val edge = max(decoded.bitmap.width, decoded.bitmap.height)
                return if (edge <= maxEdge) decoded.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                else shrunk(decoded.bitmap, maxEdge)
            }
            val result =
                transform(
                    decoded.bitmap,
                    decoded.quad,
                    if (ocrInput) "ORIGINAL" else page.filter,
                    page.rotation,
                    page.brightness,
                    page.contrast,
                    maxEdge,
                    page.enhanceStrength,
                    page.outputAspect.takeIf { it > 0f } ?: decoded.aspect,
                    if (ocrSampling) Imgproc.INTER_CUBIC else Imgproc.INTER_LINEAR,
                    if (ocrSampling) 1.5f else 1f,
                    page.workbookDetail(),
                    page.deskewDegrees,
                    !page.framedByRules(),
                )
            return result
        } finally {
            decoded.bitmap.recycle()
        }
    }

    private fun PageRow.framedByRules() =
        detectionMethod?.contains(RuleFrame.VERSION) == true ||
            processingMetadata?.contains("\"exactFrame\":true") == true

    fun transform(
        bitmap: Bitmap,
        quad: Quad,
        filter: String,
        rotation: Int = 0,
        brightness: Float = 0f,
        contrast: Float = 1f,
        targetEdge: Int = Int.MAX_VALUE,
        strength: Float? = null,
        aspectOverride: Float? = null,
        interpolation: Int = Imgproc.INTER_LINEAR,
        samplingScale: Float = 1f,
        workbookDetail: Float? = null,
        deskewDegrees: Float = 0f,
        protectEdgeInk: Boolean = true,
    ): Bitmap {
        require(quad.valid()) { "裁切四角不能交叉或重合" }
        // If ink touches the crop frame, push that side outwards instead of clipping the stroke.
        // The decoded crop keeps a small ring outside the quad so this expansion has real pixels.
        var cropQuad = quad
        if (protectEdgeInk && ContentCrop.enabled()) {
            val probe = Mat()
            try {
                Utils.bitmapToMat(bitmap, probe)
                cropQuad = ContentCrop.protect(probe, quad)
            } finally {
                probe.release()
            }
        }
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val p =
            cropQuad.points.map { Point((it.x * (w - 1)).toDouble(), (it.y * (h - 1)).toDouble()) }
        fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        // Distances join pixel centres. Include the last pixel or every edit shrinks a full page.
        val sourceW = max(dist(p[0], p[1]), dist(p[3], p[2])) + 1
        val sourceH = max(dist(p[0], p[3]), dist(p[1], p[2])) + 1
        val aspect =
            aspectOverride
                ?: cn.paperforge.app.model.ImageGeometry.aspect(
                    cropQuad,
                    bitmap.width,
                    bitmap.height,
                )
        val edge =
            min(max(sourceW, sourceH) * samplingScale, min(targetEdge.toDouble() * 1.25, 4096.0))
        val outW = (if (aspect >= 1) edge else edge * aspect).roundToInt().coerceAtLeast(32)
        val outH = (if (aspect >= 1) edge / aspect else edge).roundToInt().coerceAtLeast(32)
        val source = Mat()
        val warped = Mat()
        val rgb = Mat()
        val gray = Mat()
        val dst = Mat()
        val srcPoints = MatOfPoint2f(*p.toTypedArray())
        var canvasW = outW
        var canvasH = outH
        val corners =
            arrayOf(
                Point(0.0, 0.0),
                Point((outW - 1).toDouble(), 0.0),
                Point((outW - 1).toDouble(), (outH - 1).toDouble()),
                Point(0.0, (outH - 1).toDouble()),
            )
        val quarter = ((rotation % 360) + 360) % 360
        if (deskewDegrees != 0f) {
            val ratio = minOf(1.0, targetEdge.toDouble() / maxOf(outW, outH))
            val rw = (outW * ratio).roundToInt().coerceAtLeast(1)
            val rh = (outH * ratio).roundToInt().coerceAtLeast(1)
            val rotatedW = if (quarter == 90 || quarter == 270) rh else rw
            val rotatedH = if (quarter == 90 || quarter == 270) rw else rh
            val geometry = cn.paperforge.app.model.DeskewGeometry(rotatedW, rotatedH, deskewDegrees)
            canvasW = geometry.outputWidth
            canvasH = geometry.outputHeight
            for (i in corners.indices) {
                val x = corners[i].x * (rw - 1) / (outW - 1)
                val y = corners[i].y * (rh - 1) / (outH - 1)
                val p =
                    when (quarter) {
                        90 -> Point(rh - 1 - y, x)
                        180 -> Point(rw - 1 - x, rh - 1 - y)
                        270 -> Point(y, rw - 1 - x)
                        else -> Point(x, y)
                    }
                val mapped =
                    geometry.forward(
                        cn.paperforge.app.model.PointN(
                            (p.x / rotatedW).toFloat(),
                            (p.y / rotatedH).toFloat(),
                        )
                    )
                corners[i] = Point((mapped.x * canvasW).toDouble(), (mapped.y * canvasH).toDouble())
            }
        }
        val dstPoints = MatOfPoint2f(*corners)
        val matrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        try {
            Utils.bitmapToMat(bitmap, source)
            Imgproc.warpPerspective(
                source,
                warped,
                matrix,
                Size(canvasW.toDouble(), canvasH.toDouble()),
                interpolation,
                if (deskewDegrees != 0f) Core.BORDER_CONSTANT else Core.BORDER_REPLICATE,
                Scalar.all(255.0),
            )
            source.release()
            if (deskewDegrees == 0f && max(warped.cols(), warped.rows()) > targetEdge) {
                val ratio = targetEdge.toDouble() / max(warped.cols(), warped.rows())
                Imgproc.resize(warped, warped, Size(), ratio, ratio, Imgproc.INTER_AREA)
            }
            Imgproc.cvtColor(warped, rgb, Imgproc.COLOR_RGBA2RGB)
            EdgeInkDiagnostics.dump("02-warp", rgb)
            ToneZones.withExactFrame(!protectEdgeInk) {
                EnhancePipeline.apply(rgb, dst, filter, strength, workbookDetail)
            }
            EdgeInkDiagnostics.dump("09-final", dst)
            if (brightness != 0f || contrast != 1f)
                dst.convertTo(dst, -1, contrast.toDouble(), brightness * 100.0)
            when (if (deskewDegrees == 0f) quarter else 0) {
                90 -> Core.rotate(dst, dst, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(dst, dst, Core.ROTATE_180)
                270 -> Core.rotate(dst, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            }
            return Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(dst, it)
            }
        } finally {
            listOf(source, warped, rgb, gray, dst, srcPoints, dstPoints, matrix).forEach {
                it.release()
            }
        }
    }
}
