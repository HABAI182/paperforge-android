// Adapted from phillip9933/open-android-doc-scanner@3ba5c50a64f9be8eaeec47d263b360488e58835b
// scanner-camera/.../OutlineTracker.kt (Apache-2.0). PaperForge: native Quad/result boundary,
// configurable smoothing, a confirmation delay before switching page, and a one-frame coast.
package cn.paperforge.app.model

import kotlin.math.*

/**
 * Smooth the accepted viewfinder frame; a capture snapshots this same outline on original pixels.
 *
 * Two outlines come out of every frame. [Frame.accepted] is this frame's own page, smoothed, and is the only one a
 * capture may use. [Frame.shown] is what the viewfinder draws: the same outline, held through a single missed
 * frame, a pending page switch, or a frame whose confidence dipped below the acceptance level but stayed above
 * [holdConfidence] while it still matched the outline on screen. The viewfinder used to draw the accepted outline,
 * so each of those frames took the outline off the screen and the next put it back: the live outline blinked,
 * most often with the MobileNetV3 detector, whose confidence on a held page moved around the 0.55 cut.
 */
class OutlineTracker(
    private val weight: Float = 3f,
    private val switchFrames: Int = 2,
    private val holdConfidence: Float = .45f,
) {
    data class Frame(val shown: Quad?, val accepted: Quad?)

    private var displayed: Quad? = null
    private var pending: Quad? = null
    private var pendingFrames = 0
    private var missed = 0
    private var lastTime: Long? = null
    private var viewport: Long? = null

    /** A frame that can be both drawn and captured now, excluding a coast or a pending page switch. */
    @Synchronized
    fun updateCurrent(raw: Quad?, confidence: Float, now: Long, epoch: Long = 0): Quad? =
        track(raw, confidence, now, epoch).accepted

    /** The outline to draw and, when this frame confirms it, the same outline for capture. See the class comment. */
    @Synchronized
    fun track(raw: Quad?, confidence: Float, now: Long, epoch: Long = 0): Frame {
        val shown = update(raw, confidence, emptyList(), now, epoch)
        val accepted =
            shown?.takeIf {
                raw != null && raw.valid() && confidence.isFinite() && confidence >= ACCEPT &&
                    distance(it, raw) <= .04f
            }
        return Frame(shown, accepted)
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
        // Once an outline is on screen, a frame of the same page a little under the acceptance level still
        // tracks it rather than counting as a miss.
        val holding = current != null && !stale && raw != null && raw.valid() && distance(current, raw) <= .04f
        val needed = if (holding) min(holdConfidence, ACCEPT) else ACCEPT
        if (raw == null || !raw.valid() || !confidence.isFinite() || confidence < needed) {
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

    private companion object {
        const val ACCEPT = .55f
    }

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
