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
