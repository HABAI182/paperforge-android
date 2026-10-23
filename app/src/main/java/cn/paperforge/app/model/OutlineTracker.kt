package cn.paperforge.app.model

import kotlin.math.*

/**
 * Display only. Two candidate buffers reject transient jumps; capture always uses raw corners.
 * Adapted from the recovered tracker, with explicit milliseconds and configurable switch latency.
 */
class OutlineTracker(private val weight: Float = 3f, private val switchFrames: Int = 2) {
    private var displayed: Quad? = null
    private var pending: Quad? = null
    private var pendingCount = 0
    private var signature: List<Float> = emptyList()
    private var lastTime = 0L
    private var lastSeen = 0L
    private var viewport: Long? = null

    @Synchronized
    fun update(
        raw: Quad?,
        confidence: Float,
        signature: List<Float>,
        now: Long,
        epoch: Long = 0,
    ): Quad? {
        if (viewport != epoch || now <= lastTime || now - lastTime > 600) reset()
        viewport = epoch
        lastTime = now
        if (raw == null || !raw.valid() || !confidence.isFinite() || confidence < .55f) {
            pending = null
            pendingCount = 0
            if (now - lastSeen > 260) displayed = null
            return displayed
        }
        lastSeen = now
        fun distance(a: Quad, b: Quad) =
            a.points.zip(b.points).maxOf { (x, y) -> max(abs(x.x - y.x), abs(x.y - y.y)) }
        val old = displayed
        val contentChanged =
            signature.size == this.signature.size &&
                signature.isNotEmpty() &&
                signature.zip(this.signature).map { (a, b) -> abs(a - b) }.average() > .12
        if (old == null || contentChanged) {
            displayed = raw
            pending = null
            pendingCount = 0
        } else if (distance(old, raw) <= 1f / 15) {
            val w = weight.coerceIn(1f, 8f)
            // Recovered EMA's old term is evaluated in double, new / weight in float.
            fun blend(a: Float, b: Float): Float =
                (((w - 1).toDouble() * a / w) + (b / w).toDouble()).toFloat()
            displayed =
                Quad(
                        old.points.zip(raw.points).map { (a, b) ->
                            PointN(blend(a.x, b.x), blend(a.y, b.y))
                        }
                    )
                    .takeIf { it.valid() } ?: raw
            pending = null
            pendingCount = 0
        } else {
            pendingCount =
                if (pending?.let { distance(it, raw) < 1f / 15 } == true) pendingCount + 1 else 1
            pending = raw
            if (pendingCount >= switchFrames.coerceIn(1, 6)) {
                displayed = raw
                pending = null
                pendingCount = 0
            }
        }
        this.signature = signature.toList()
        return displayed
    }

    @Synchronized
    fun reset() {
        displayed = null
        pending = null
        pendingCount = 0
        signature = emptyList()
        lastTime = 0
        lastSeen = 0
        viewport = null
    }
}
