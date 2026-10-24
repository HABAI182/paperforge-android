package cn.paperforge.app.model

import kotlin.math.*

/**
 * Raw analysis corners, never the display-smoothed outline. Sensor timestamps are diagnostic only.
 */
data class CaptureReference(
    val quad: Quad,
    val analyzedAt: Long,
    val requestedAt: Long,
    val width: Int,
    val height: Int,
    val viewport: String,
    val sourceTimestampNs: Long,
    val confidence: Float,
    val scene: List<Float>,
    val paper: List<Float>,
    val motion: Float = 0f,
) {
    val age
        get() = requestedAt - analyzedAt

    fun reason(
        photoWidth: Int,
        photoHeight: Int,
        photoScene: List<Float>,
        photoPaper: List<Float>,
    ): String =
        when {
            age !in 0..450 -> "stale"
            motion > .065f -> "motion"
            abs(ln((photoWidth.toFloat() / photoHeight) / (width.toFloat() / height))) > .035f ->
                "viewport-changed"
            signatureDistance(scene, photoScene) > .075f -> "scene-changed"
            signatureDistance(paper, photoPaper) > .065f -> "paper-changed"
            else -> "matched"
        }

    companion object {
        /** Suppress uniform exposure shifts while preserving spatial content changes. */
        fun signatureDistance(first: List<Float>, second: List<Float>): Float {
            if (first.isEmpty() || first.size != second.size) return 1f
            val delta = first.zip(second) { a, b -> a - b }
            val shift = delta.average().toFloat()
            return delta.map { abs(it - shift) }.average().toFloat()
        }
    }
}
