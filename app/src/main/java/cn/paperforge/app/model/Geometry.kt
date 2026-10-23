package cn.paperforge.app.model

import kotlin.math.*

data class PointN(val x: Float, val y: Float)

data class Quad(val points: List<PointN>) {
    val area: Float
        get() =
            abs(
                points.indices
                    .sumOf { i ->
                        val a = points[i]
                        val b = points[(i + 1) % 4]
                        (a.x * b.y - b.x * a.y).toDouble()
                    }
                    .toFloat()
            ) / 2

    fun valid(): Boolean =
        points.size == 4 &&
            points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f } &&
            area > .015f &&
            points.indices.all { i ->
                cross(points[i], points[(i + 1) % 4], points[(i + 2) % 4]) > .0001f
            }

    fun encode() = points.joinToString(";") { "${it.x},${it.y}" }

    companion object {
        val Full = Quad(listOf(PointN(0f, 0f), PointN(1f, 0f), PointN(1f, 1f), PointN(0f, 1f)))

        fun decode(value: String): Quad =
            runCatching {
                    Quad(
                            value.split(';').map { v ->
                                v.split(',').let { PointN(it[0].toFloat(), it[1].toFloat()) }
                            }
                        )
                        .also { require(it.valid()) }
                }
                .getOrDefault(Full)

        fun ordered(input: List<PointN>): Quad {
            require(input.size == 4)
            val cx = input.sumOf { it.x.toDouble() } / 4
            val cy = input.sumOf { it.y.toDouble() } / 4
            val sorted = input.sortedBy { atan2(it.y - cy, it.x - cx) }
            val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
            return Quad(List(4) { sorted[(it + start) % 4] })
        }

        private fun cross(a: PointN, b: PointN, c: PointN) =
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
    }
}

/** Requires a fresh stable interval after capture, plus cooldown. Monotonic milliseconds only. */
class AutoCaptureGate(private val stableMs: Long = 1200, private val cooldownMs: Long = 4000) {
    private var anchor: Quad? = null
    private var since = 0L
    private var lastCapture = -cooldownMs
    private var captured: Quad? = null
    private var absentSince: Long? = null
    var progress = 0f
        private set

    fun update(quad: Quad?, now: Long, qualityReady: Boolean = true): Boolean {
        if (quad == null || !quad.valid() || quad.area < .18f) {
            if (absentSince == null) absentSince = now
            if (now - (absentSince ?: now) > 600) captured = null
            reset()
            return false
        }
        absentSince = null
        if (!qualityReady) {
            reset()
            return false
        }
        captured?.let { old ->
            if (
                quad.points.indices.maxOf { i ->
                    hypot(quad.points[i].x - old.points[i].x, quad.points[i].y - old.points[i].y)
                } < .10f
            ) {
                progress = 0f
                return false
            }
            captured = null
            reset()
        }
        val old = anchor
        if (
            old == null ||
                quad.points.indices.maxOf { i ->
                    hypot(quad.points[i].x - old.points[i].x, quad.points[i].y - old.points[i].y)
                } > .025f
        ) {
            anchor = quad
            since = now
            progress = 0f
            return false
        }
        progress = ((now - since).toFloat() / stableMs).coerceIn(0f, 1f)
        if (progress >= 1 && now - lastCapture >= cooldownMs) {
            lastCapture = now
            captured = quad
            reset()
            return true
        }
        return false
    }

    fun reset() {
        anchor = null
        progress = 0f
    }
}

fun parsePageRange(input: String, pageCount: Int): List<Int> {
    require(pageCount > 0) { "文档没有页面" }
    if (input.isBlank()) return (0 until pageCount).toList()
    val result = linkedSetOf<Int>()
    input.split(',', '，').forEach { token ->
        val p = token.trim().split('-').map { it.trim().toIntOrNull() ?: error("页码格式应为 1-3,5") }
        require(p.size in 1..2) { "页码格式应为 1-3,5" }
        val first = p[0]
        val last = p.last()
        require(first in 1..pageCount && last in first..pageCount) { "页码必须在 1 到 $pageCount 之间" }
        (first..last).forEach { result.add(it - 1) }
    }
    return result.toList()
}

fun safeFileName(input: String): String =
    input.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(100).ifBlank {
        "纸匠文档"
    }
