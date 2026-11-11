// Adapted from phillip9933/open-android-doc-scanner@3ba5c50a64f9be8eaeec47d263b360488e58835b
// scanner-camera/.../OutlineTracker.kt (Apache-2.0). PaperForge: native Quad/result boundary,
// configurable smoothing, a confirmation delay before switching page, and a one-frame coast.
package cn.paperforge.app.model

import kotlin.math.*

/** Smooth the accepted viewfinder frame; a capture snapshots this same outline on original pixels. */
class OutlineTracker(private val weight: Float = 3f, private val switchFrames: Int = 2) {
    private var displayed: Quad? = null
    private var pending: Quad? = null
    private var pendingFrames = 0
    private var missed = 0
    private var lastTime: Long? = null
    private var viewport: Long? = null

    /** A frame that can be both drawn and captured now, excluding a coast or a pending page switch. */
    @Synchronized
    fun updateCurrent(raw: Quad?, confidence: Float, now: Long, epoch: Long = 0): Quad? =
        update(raw, confidence, emptyList(), now, epoch)?.takeIf {
            raw != null && raw.valid() && confidence.isFinite() && confidence >= .55f &&
                distance(it, raw) <= .04f
        }

    @Synchronized
    fun update(
        raw: Quad?,
        confidence: Float,
        signature: List<Float>,
        now: Long,
        epoch: Long = 0,
    ): Quad? {
        val current = displayed
        val time = lastTime
        val stale = viewport != epoch || time == null || now <= time || now - time > 600
        lastTime = now
        viewport = epoch
        if (raw == null || !raw.valid() || !confidence.isFinite() || confidence < .55f) {
            pending = null
            pendingFrames = 0
            if (stale || current == null || ++missed > 1) reset() else return current
            return null
        }
        missed = 0
        if (current == null || stale) return show(raw)
        if (distance(current, raw) <= .04f) {
            pending = null
            pendingFrames = 0
            val blend = 1f / weight.coerceAtLeast(1f)
            return show(
                Quad(
                        current.points.zip(raw.points).map { (a, b) ->
                            PointN(a.x + (b.x - a.x) * blend, a.y + (b.y - a.y) * blend)
                        }
                    )
                    .takeIf { it.valid() } ?: raw
            )
        }
        val candidate = pending
        pendingFrames = if (candidate != null && distance(candidate, raw) <= .04f) pendingFrames + 1 else 1
        pending = raw
        return if (pendingFrames >= switchFrames) show(raw) else current
    }

    private fun show(quad: Quad): Quad {
        pending = null
        pendingFrames = 0
        displayed = quad
        return quad
    }

    private fun distance(a: Quad, b: Quad) =
        a.points.zip(b.points).maxOf { (p, q) -> hypot(p.x - q.x, p.y - q.y) }

    @Synchronized
    fun reset() {
        displayed = null
        pending = null
        pendingFrames = 0
        missed = 0
        lastTime = null
        viewport = null
    }
}
