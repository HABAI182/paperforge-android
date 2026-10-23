// Adapted from phillip9933/open-android-doc-scanner@3ba5c50a64f9be8eaeec47d263b360488e58835b
// scanner-camera/.../OutlineTracker.kt (Apache-2.0). PaperForge: native Quad/result boundary.
package cn.paperforge.app.model

import kotlin.math.*

/** Display only. Capture/edits always use raw detection and original still pixels. */
class OutlineTracker {
    private var previous: Quad? = null
    private var displayed: Quad? = null
    private var anchor: List<Float>? = null
    private var lastTime: Long? = null
    private var viewport: Long? = null

    @Synchronized
    fun update(
        raw: Quad?,
        confidence: Float,
        signature: List<Float>,
        now: Long,
        epoch: Long = 0,
    ): Quad? {
        if (raw == null || !raw.valid() || !confidence.isFinite() || confidence < .60f) {
            reset()
            return null
        }
        val old = previous
        val scene = anchor
        val time = lastTime
        val changed =
            old == null ||
                displayed == null ||
                scene == null ||
                signature.isEmpty() ||
                viewport != epoch ||
                time == null ||
                now <= time ||
                now - time > 600 ||
                old.points.zip(raw.points).any { (a, b) -> hypot(a.x - b.x, a.y - b.y) > .04f } ||
                signature.size != scene.size ||
                signature.zip(scene).map { (a, b) -> abs(a - b) }.average() >= .12
        val result =
            if (changed) raw
            else
                Quad(
                        displayed!!.points.zip(raw.points).map { (a, b) ->
                            PointN(a.x + (b.x - a.x) * .65f, a.y + (b.y - a.y) * .65f)
                        }
                    )
                    .takeIf { it.valid() } ?: raw
        if (changed) anchor = signature.toList()
        previous = raw
        displayed = result
        lastTime = now
        viewport = epoch
        return result
    }

    @Synchronized
    fun reset() {
        previous = null
        displayed = null
        anchor = null
        lastTime = null
        viewport = null
    }
}
