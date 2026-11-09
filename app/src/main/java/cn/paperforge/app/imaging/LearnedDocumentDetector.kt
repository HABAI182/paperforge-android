package cn.paperforge.app.imaging

import ai.onnxruntime.*
import android.content.Context
import android.graphics.*
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings
import cn.paperforge.app.imaging.docquad.DocQuadLetterbox
import cn.paperforge.app.imaging.docquad.DocQuadPostprocessor
import cn.paperforge.app.model.*
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.*
import org.opencv.android.Utils
import org.opencv.core.Mat

/** MakeACopy model, letterbox and postprocessor; provenance/license travel in assets. */
object LearnedDocumentDetector {
    private var context: Context? = null
    private var session: OrtSession? = null
    private var failure: String? = null
    private val environment by lazy { OrtEnvironment.getEnvironment() }

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    private fun session(): OrtSession {
        session?.let {
            return it
        }
        val app = checkNotNull(context)
        val model = File(app.cacheDir, "docquad-01bebd39.ort")
        if (!model.exists() || model.length() != 13_404_960L) {
            val part = File(model.path + ".part")
            app.assets.open("docquad/docquadnet256.ort").use { input ->
                part.outputStream().use { input.copyTo(it) }
            }
            check(part.renameTo(model))
        }
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(model.path, options).also { session = it }
        }
    }

    /** Raw model output for [bitmap], with coordinates in its own pixels; null if unavailable. */
    @Synchronized
    internal fun infer(bitmap: Bitmap): DocQuadPostprocessor.Result? {
        if (context == null || failure != null) return null
        try {
            val lb = DocQuadLetterbox.create(bitmap.width, bitmap.height)
            val input = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val tensorValues = FloatArray(3 * 256 * 256)
            try {
                Canvas(input).apply {
                    drawColor(Color.BLACK)
                    drawBitmap(
                        bitmap,
                        null,
                        RectF(
                            lb.offsetX.toFloat(),
                            lb.offsetY.toFloat(),
                            (lb.offsetX + bitmap.width * lb.scale).toFloat(),
                            (lb.offsetY + bitmap.height * lb.scale).toFloat(),
                        ),
                        Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
                    )
                }
                val pixels = IntArray(256 * 256)
                input.getPixels(pixels, 0, 256, 0, 0, 256, 256)
                pixels.forEachIndexed { i, c ->
                    tensorValues[i] = (c shr 16 and 255) / 255f
                    tensorValues[65536 + i] = (c shr 8 and 255) / 255f
                    tensorValues[131072 + i] = (c and 255) / 255f
                }
            } finally {
                input.recycle()
            }
            OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(tensorValues),
                    longArrayOf(1, 3, 256, 256),
                )
                .use { tensor ->
                    session().run(mapOf("input" to tensor)).use { output ->
                        @Suppress("UNCHECKED_CAST")
                        val corners =
                            output.get("corner_heatmaps").get().value
                                as Array<Array<Array<FloatArray>>>
                        @Suppress("UNCHECKED_CAST")
                        val mask =
                            output.get("mask_logits").get().value as Array<Array<Array<FloatArray>>>
                        return DocQuadPostprocessor.postprocess(
                            corners,
                            mask,
                            lb,
                            DocQuadPostprocessor.PeakMode.REFINE_5X5_QUADRATIC,
                        )
                    }
                }
        } catch (e: Exception) {
            failure = e.javaClass.simpleName + ": " + e.message
            android.util.Log.w("PaperForgeDetector", "AI unavailable; using local edge detector", e)
            return null
        }
    }

    fun detect(bitmap: Bitmap): DetectionResult {
        val started = System.nanoTime()
        if (context == null || failure != null) return DetectionResult(null, 0f, "ai-unavailable")
        val result = infer(bitmap) ?: return DetectionResult(null, 0f, "ai-fallback")
        val points =
            result.chosenQuadOriginal()?.map {
                PointN(
                    (it[0] / bitmap.width).toFloat().coerceIn(0f, 1f),
                    (it[1] / bitmap.height).toFloat().coerceIn(0f, 1f),
                )
            }
        val quad = points?.let { Quad.ordered(it) }?.takeIf { it.valid() && it.area >= .06f }
        val confidence = result.cornerConfidence().toFloat().coerceIn(0f, 1f)
        return DetectionResult(
            quad,
            confidence,
            "docquad-${result.chosenSource()}",
            mapOf(
                "mask_iou" to result.cornerMaskIou().toFloat(),
                "corner_peak" to result.minPeakProb().toFloat(),
            ),
            (System.nanoTime() - started) / 1e6f,
        )
    }

    /**
     * A full-frame quad is the absence of a crop, not a located page. Collapsing it here keeps
     * every engine honest: the caller sees "nothing detected", the capture still falls back to the
     * full frame, and confidence gates never treat a blank desk as a verified document.
     */
    private fun frameOnly(result: DetectionResult): DetectionResult {
        val quad = result.quad ?: return result
        if (!quad.hugsFrame()) return result
        return DetectionResult(
            null,
            min(result.confidence, .2f),
            result.method + "-frame-only",
            result.diagnostics + mapOf("frameOnly" to 1f),
            result.elapsedMs,
        )
    }

    fun detect(
        source: Mat,
        preview: Boolean = false,
        engineOverride: String? = null,
    ): DetectionResult {
        val result = detectRaw(source, preview, engineOverride)
        // PageNet already separates the top sheet of a pile.
        return if (preview || result.method.startsWith("pagenet")) result
        else PrimaryPaperFrame.refine(source, result)
    }

    private fun detectRaw(source: Mat, preview: Boolean, engineOverride: String?): DetectionResult {
        val engine = engineOverride ?: UserSettings[SettingKeys.Detector]
        if (engine == "INDEPENDENT") return IndependentDocumentDetector.detect(source, preview)
        if (engine == "STANDARD") return frameOnly(DocumentDetector.detect(source, preview))
        PageNetDetector.detect(source, preview)?.let { found ->
            val quad = found.quad ?: return@let
            return frameOnly(
                if (preview || ScanConfig.int("pageNetRefine", 1) == 0) found
                else found.copy(quad = DocumentDetector.refine(source, quad), method = found.method + "+refined")
            )
        }
        // PageNet saw no sheet. The edge detector prefers background rectangles when it decides
        // alone, so it is only the last resort.
        val bitmap = Bitmap.createBitmap(source.cols(), source.rows(), Bitmap.Config.ARGB_8888)
        val learned =
            try {
                Utils.matToBitmap(source, bitmap)
                detect(bitmap)
            } finally {
                bitmap.recycle()
            }
        return frameOnly(if (learned.quad != null) learned else DocumentDetector.detect(source, preview))
    }
}
