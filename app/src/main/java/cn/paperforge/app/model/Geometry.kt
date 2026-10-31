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

    /**
     * A quad that only traces the sensor/letterbox frame carries no crop information: a real page
     * fit keeps a visible inset, while blank or border-heavy scenes produce a ring glued to the
     * frame. Treated as "nothing detected" so a full-frame capture is not reported as a located
     * document (and does not feed confidence gates or auto-capture).
     */
    fun hugsFrame(tolerance: Float = .012f): Boolean {
        if (area < .9f) return false
        return points.all { p ->
            p.x <= tolerance || p.x >= 1f - tolerance || p.y <= tolerance || p.y >= 1f - tolerance
        }
    }

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
class AutoCaptureGate(
    private val stableMs: Long = 1200,
    private val cooldownMs: Long = 4000,
    private val minimumArea: Float = .08f,
) {
    private var anchor: Quad? = null
    private var since = 0L
    private var lastCapture = -cooldownMs
    private var captured: Quad? = null
    private var capturedScene: List<Float>? = null
    private var manualUnknown = false
    private var changedFrames = 0
    private var absentSince: Long? = null
    private var priorFrame: List<Float>? = null
    private var turnStarted = 0L
    private var lastMotion = 0L
    private var motionFrames = 0
    private var turnSeen = false
    var progress = 0f
        private set

    fun update(
        quad: Quad?,
        now: Long,
        qualityReady: Boolean = true,
        scene: List<Float>? = null,
        fullFrame: List<Float>? = null,
        turnRearm: Boolean = true,
        turnThreshold: Float = .06f,
    ): Boolean {
        val prior = priorFrame
        priorFrame = fullFrame?.toList()
        if (
            captured != null &&
                turnRearm &&
                fullFrame != null &&
                prior != null &&
                now - lastCapture >= 250
        ) {
            val moving = CaptureReference.signatureDistance(prior, fullFrame) > turnThreshold
            if (moving) {
                if (motionFrames == 0 || now - lastMotion > 700 || now - turnStarted > 2500) {
                    turnStarted = now
                    motionFrames = 0
                    turnSeen = false
                }
                motionFrames++
                lastMotion = now
                if (motionFrames >= 3 && now - turnStarted >= 240) turnSeen = true
                reset()
                return false
            }
            if (turnSeen && now - lastMotion in 300..2500) {
                captured = null
                capturedScene = null
                motionFrames = 0
                turnSeen = false
                reset()
            }
        }
        if (quad == null || !quad.valid() || quad.area < minimumArea) {
            if (absentSince == null) absentSince = now
            if (now - (absentSince ?: now) > 600) {
                captured = null
                manualUnknown = false
            }
            reset()
            return false
        }
        absentSince = null
        if (manualUnknown) {
            captured = quad
            capturedScene = scene?.toList()
            manualUnknown = false
            reset()
            return false
        }
        if (!qualityReady) {
            reset()
            return false
        }
        captured?.let { old ->
            val priorScene = capturedScene
            val contentChanged =
                scene != null &&
                    priorScene != null &&
                    scene.size == priorScene.size &&
                    scene
                        .zip(priorScene)
                        .map { (a, b) -> (a - b).toDouble() }
                        .let { delta ->
                            val exposureShift = delta.average()
                            delta.map { abs(it - exposureShift) }.average() > .025
                        }
            changedFrames = if (contentChanged) changedFrames + 1 else 0
            val changedPage = changedFrames >= 2
            if (
                !changedPage &&
                    quad.points.indices.maxOf { i ->
                        hypot(
                            quad.points[i].x - old.points[i].x,
                            quad.points[i].y - old.points[i].y,
                        )
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
            capturedScene = scene?.toList()
            changedFrames = 0
            motionFrames = 0
            turnSeen = false
            reset()
            return true
        }
        return false
    }

    fun reset(clearCaptured: Boolean = false) {
        if (clearCaptured) {
            captured = null
            capturedScene = null
            manualUnknown = false
            changedFrames = 0
            priorFrame = null
            motionFrames = 0
            turnSeen = false
        }
        anchor = null
        progress = 0f
    }

    fun markCaptured(quad: Quad?, now: Long, scene: List<Float>?, fullFrame: List<Float>? = null) {
        lastCapture = now
        captured = quad
        capturedScene = scene?.toList()
        manualUnknown = quad == null
        changedFrames = 0
        priorFrame = fullFrame?.toList()
        motionFrames = 0
        turnSeen = false
        reset()
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

fun fileStemPrefix(value: String, maxBytes: Int = 200, maxCharacters: Int = 100): String {
    var index = 0
    var bytes = 0
    var count = 0
    while (index < value.length && count < maxCharacters) {
        val point = Character.codePointAt(value, index)
        val size =
            when {
                point <= 0x7f -> 1
                point <= 0x7ff -> 2
                point <= 0xffff -> 3
                else -> 4
            }
        if (bytes + size > maxBytes) break
        bytes += size
        count++
        index += Character.charCount(point)
    }
    return value.substring(0, index)
}

fun safeFileName(input: String): String =
    fileStemPrefix(input.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.'))
        .trim()
        .trim('.')
        .ifBlank { "PaperForge文档" }
