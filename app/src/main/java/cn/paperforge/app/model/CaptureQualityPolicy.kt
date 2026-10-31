// Adapted from phillip9933/open-android-doc-scanner@3ba5c50a64f9be8eaeec47d263b360488e58835b
// scanner-camera/.../CaptureQualityPolicy.kt (Apache-2.0). PaperForge adds explicit AF/readiness.
package cn.paperforge.app.model

import kotlin.math.*

object CaptureQualityPolicy {
    data class Sample(
        val sharpness: Double,
        val brightness: Double,
        val detailTiles: Int,
        val glareFraction: Double = 0.0,
        /** Lowest local sharpness among tiles that actually contain detail; 0 when none do. */
        val minDetailedSharpness: Double = 0.0,
    )

    fun acceptable(
        sample: Sample,
        reference: Sample?,
        focusReady: Boolean,
        sharpnessFloor: Double = 60.0,
    ): Boolean {
        if (
            !focusReady ||
                !sample.sharpness.isFinite() ||
                sample.brightness !in 0.18..0.98 ||
                sample.detailTiles !in 0..9
        )
            return false
        // A first blurry frame must never declare itself a blank-page reference.
        if (sample.detailTiles < 2) return reference != null && reference.detailTiles in 0..1
        return sample.sharpness >= sharpnessFloor &&
            (reference == null || sample.sharpness >= reference.sharpness * .70)
    }
}

class CaptureQualityTracker {
    private var quad: Quad? = null
    private var signature: List<Float>? = null
    private var last = 0L
    var reference: CaptureQualityPolicy.Sample? = null
        private set

    fun update(
        raw: Quad?,
        sample: CaptureQualityPolicy.Sample?,
        scene: List<Float>,
        now: Long,
        focusReady: Boolean,
        floor: Double = 60.0,
    ): Boolean {
        val old = quad
        val prior = signature
        if (
            raw == null ||
                sample == null ||
                old == null ||
                now - last > 600 ||
                old.points.zip(raw.points).any { (a, b) -> hypot(a.x - b.x, a.y - b.y) > .08f } ||
                prior == null ||
                prior.size != scene.size ||
                prior.zip(scene).map { (a, b) -> abs(a - b) }.average() > .12
        )
            reference = null
        quad = raw
        signature = scene.toList()
        last = now
        if (raw == null || sample == null) return false
        if (
            sample.detailTiles >= 2 &&
                sample.sharpness >= floor &&
                sample.brightness in 0.18..0.98 &&
                sample.sharpness > (reference?.sharpness ?: 0.0)
        )
            reference = sample
        return CaptureQualityPolicy.acceptable(sample, reference, focusReady, floor)
    }

    fun reset() {
        quad = null
        signature = null
        reference = null
        last = 0
    }
}

/** Actionable, non-blocking capture problems. User-facing text lives in the UI layer. */
enum class CaptureHint {
    BLUR,
    MOTION,
    GLARE,
    MISSING_EDGE,
    DARK,
    BRIGHT,
}

object CaptureHints {
    /**
     * Classification shared by the viewfinder and the post-capture check on the real photo.
     * Localised blur is only reported when a detailed tile is clearly softer than the frame, so
     * blank page margins do not trigger it.
     */
    fun of(
        sample: CaptureQualityPolicy.Sample?,
        cutOffEdges: Int = 0,
        sharpnessFloor: Double = 60.0,
    ): List<CaptureHint> {
        val s = sample ?: return emptyList()
        val hints = mutableListOf<CaptureHint>()
        if (!s.sharpness.isFinite() || s.sharpness < sharpnessFloor) hints += CaptureHint.BLUR
        else if (s.minDetailedSharpness > 0.0 && s.minDetailedSharpness < sharpnessFloor * .5)
            hints += CaptureHint.MOTION
        if (s.glareFraction in .03..0.45) hints += CaptureHint.GLARE
        else if (s.glareFraction > 0.45) hints += CaptureHint.BRIGHT
        if (cutOffEdges > 0) hints += CaptureHint.MISSING_EDGE
        if (s.brightness < .25) hints += CaptureHint.DARK
        if (s.brightness > .96) hints += CaptureHint.BRIGHT
        return hints.distinct()
    }
}
