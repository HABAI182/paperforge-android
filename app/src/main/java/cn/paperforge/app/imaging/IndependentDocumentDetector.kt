package cn.paperforge.app.imaging

import android.graphics.Bitmap
import cn.paperforge.app.model.*
import kotlin.math.*
import org.opencv.android.Utils
import org.opencv.core.Mat

/** Optional independent ensemble. Existing default engines remain unchanged. */
object IndependentDocumentDetector {
    private fun preserveFrame(result: DetectionResult): DetectionResult =
        if (result.quad?.hugsFrame() == true)
            result.copy(quad = null, confidence = 0f, method = result.method + "-frame-only")
        else result

    fun detect(source: Mat, preview: Boolean): DetectionResult {
        val bitmap = Bitmap.createBitmap(source.cols(), source.rows(), Bitmap.Config.ARGB_8888)
        val learned =
            try {
                Utils.matToBitmap(source, bitmap)
                LearnedDocumentDetector.detect(bitmap)
            } finally {
                bitmap.recycle()
            }
        val standard = DocumentDetector.detect(source, preview)
        if (learned.quad == null || learned.confidence < .55f) return preserveFrame(standard)
        fun refined() =
            if (preview) learned
            else
                learned.copy(
                    quad = DocumentDetector.refine(source, requireNotNull(learned.quad)),
                    method = learned.method + "+refined",
                )
        if (standard.quad == null) return preserveFrame(refined())
        val shift =
            learned.quad.points.zip(standard.quad.points).maxOf { (a, b) ->
                hypot(a.x - b.x, a.y - b.y)
            }
        // Consensus keeps the high-resolution edge fit. A confident semantic model
        // can resolve a background rectangle that the edge-only detector preferred.
        if (shift < ScanConfig.float("detectorConsensusShift", .055f))
            return preserveFrame(
                standard.copy(
                    method = "ai+" + standard.method,
                    confidence = max(standard.confidence, learned.confidence),
                )
            )
        return preserveFrame(
            if (learned.confidence >= ScanConfig.float("detectorSemanticConfidence", .72f))
                refined()
            else standard
        )
    }
}
