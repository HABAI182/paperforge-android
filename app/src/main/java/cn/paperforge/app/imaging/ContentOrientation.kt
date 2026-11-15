package cn.paperforge.app.imaging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Which way up a finished page is, from its content, so a page photographed sideways is not saved sideways.
 *
 * Two small networks (work/orient in the research folder; trained on synthetic pages and public real pages, never
 * on the owner's photos):
 * - a layout model on the whole page (256x256, letterboxed), and
 * - a glyph model on up to 8 ink-dense 192x192 windows of the page at 1024 px, where letters and handwriting are
 *   large enough for up and down to show.
 * Each sees the page at all four quarter turns and the answers are averaged back (test-time augmentation). Class k
 * means the page shows k quarter turns CLOCKWISE. Scores: S_k = sum_i log p_i,k + w log g_k, posterior softmax(S / t)
 * with w and t fitted on held-back documents.
 *
 * Conservative by design: a wrong automatic turn is worse than leaving a page as it was. The page is turned only if
 * the posterior of a non-zero class is at least T AND at least M windows say the same with probability >= 0.7.
 * Drawings, sparse pages and maths with little text usually stay below that and are left alone.
 */
object ContentOrientation {
    const val VERSION = "orient-1"
    private const val GLOBAL = 256
    private const val WORK = 512
    private const val LONG = 1024
    private const val PATCH = 192
    private const val STRIDE = 96
    private const val MIN_INK = 6.0
    private const val EPS = 1e-4

    data class Decision(val turnsClockwise: Int, val posterior: FloatArray, val windows: Int, val agreeing: Int) {
        /** Degrees to add to PageRow.rotation (clockwise) to show the page upright. */
        val correction: Int
            get() = ((4 - turnsClockwise) % 4) * 90

        fun json(): JSONObject =
            JSONObject()
                .put("version", VERSION)
                .put("pageTurnsClockwise", turnsClockwise)
                .put("posterior", org.json.JSONArray(posterior.map { (it * 1000).roundToInt() / 1000.0 }))
                .put("windows", windows)
                .put("agreeing", agreeing)
    }

    private class Models(val global: OrtSession, val patches: List<OrtSession>, val params: JSONObject)

    @Volatile private var models: Models? = null
    @Volatile private var failure: String? = null

    @Synchronized
    private fun models(context: Context): Models? {
        models?.let { return it }
        if (failure != null) return null
        return runCatching {
                val assets = context.assets
                val params = JSONObject(assets.open("orient/orient.json").bufferedReader().use { it.readText() })
                val env = OrtEnvironment.getEnvironment()
                fun session(name: String) =
                    OrtSession.SessionOptions().use { options ->
                        options.setIntraOpNumThreads(2)
                        env.createSession(assets.open("orient/$name").use { it.readBytes() }, options)
                    }
                val patchNames = params.getJSONArray("patchModels")
                Models(
                    session(params.getString("globalModel")),
                    (0 until patchNames.length()).map { session(patchNames.getString(it)) },
                    params,
                )
            }
            .onFailure { failure = it.javaClass.simpleName + ": " + it.message }
            .getOrNull()
            ?.also { models = it }
    }

    /** The decision for [page] (any size, ARGB), or null when the models are unavailable or the page is blank. */
    fun decide(context: Context, page: Bitmap): Decision? {
        val m = models(context) ?: return null
        val gray = Mat()
        val rgba = Mat()
        try {
            Utils.bitmapToMat(page, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return decide(m, gray)
        } finally {
            rgba.release()
            gray.release()
        }
    }

    private fun decide(m: Models, gray: Mat): Decision? {
        val g = tta(m.global, globalInput(gray), GLOBAL)
        val windows = patchInputs(gray)
        if (windows.isEmpty()) return null
        val p = windows.map { w -> average(m.patches.map { tta(it, w, PATCH) }) }
        val wGlobal = m.params.getDouble("w")
        val t = m.params.getDouble("t")
        val s = DoubleArray(4) { k -> p.sumOf { ln(it[k] + EPS) } + wGlobal * ln(g[k] + EPS) }
        val top = s.max()
        val e = DoubleArray(4) { exp((s[it] - top) / t) }
        val total = e.sum()
        val posterior = FloatArray(4) { (e[it] / total).toFloat() }
        val best = posterior.indices.maxBy { posterior[it] }
        val informative = p.filter { it.max() >= .7 }.map { q -> q.indices.maxBy { q[it] } }
        val agreeing = informative.count { it == best }
        val accept =
            best != 0 &&
                posterior[best] >= m.params.getDouble("T") &&
                agreeing >= m.params.getInt("M")
        return Decision(if (accept) best else 0, posterior, p.size, agreeing)
    }

    private fun average(list: List<DoubleArray>) = DoubleArray(4) { k -> list.sumOf { it[k] } / list.size }

    /** Probabilities that the page shows k turns clockwise, averaged over the four turns of the input. */
    private fun tta(session: OrtSession, x: FloatArray, n: Int): DoubleArray {
        val env = OrtEnvironment.getEnvironment()
        val sum = DoubleArray(4)
        var input = x
        for (r in 0 until 4) {
            val logits =
                OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 1, n.toLong(), n.toLong())).use { tensor ->
                    session.run(mapOf("page" to tensor)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        (out[0].value as Array<FloatArray>)[0]
                    }
                }
            val top = logits.max()
            val e = DoubleArray(4) { exp((logits[it] - top).toDouble()) }
            val z = e.sum()
            // Turned r more clockwise, the page shows (k + r) turns: undo the shift.
            for (k in 0 until 4) sum[k] += e[(k + r) % 4] / z
            input = rotateClockwise(input, n)
        }
        return DoubleArray(4) { sum[it] / 4 }
    }

    private fun rotateClockwise(x: FloatArray, n: Int): FloatArray {
        val out = FloatArray(n * n)
        // out[y][x] = in[n-1-x][y]
        for (y in 0 until n) for (xx in 0 until n) out[y * n + xx] = x[(n - 1 - xx) * n + y]
        return out
    }

    /** Ink (255 - paper-normalised gray) of [gray] after resizing to a long edge of [long]. */
    private fun ink(gray: Mat, long: Int, enlarge: Boolean): Mat {
        val w = gray.cols()
        val h = gray.rows()
        val s = long.toDouble() / max(w, h)
        val g = Mat()
        if (s < 1.0 || enlarge) {
            Imgproc.resize(
                gray,
                g,
                Size(max(1.0, (w * s).roundToInt().toDouble()), max(1.0, (h * s).roundToInt().toDouble())),
                0.0,
                0.0,
                if (s < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR,
            )
        } else gray.copyTo(g)
        val bg = Mat()
        Imgproc.dilate(g, bg, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0)))
        Imgproc.GaussianBlur(bg, bg, Size(0.0, 0.0), 12.0)
        val gb = ByteArray(g.cols() * g.rows()).also { g.get(0, 0, it) }
        val bb = ByteArray(gb.size).also { bg.get(0, 0, it) }
        val inkBytes =
            ByteArray(gb.size) { i ->
                val norm = min(255.0, (gb[i].toInt() and 255) * 255.0 / max(bb[i].toInt() and 255, 1))
                (255.0 - norm).toInt().toByte()
            }
        val out = Mat(g.rows(), g.cols(), org.opencv.core.CvType.CV_8UC1)
        out.put(0, 0, inkBytes)
        g.release()
        bg.release()
        return out
    }

    private fun globalInput(gray: Mat): FloatArray {
        val ink = ink(gray, WORK, enlarge = false)
        val w = ink.cols()
        val h = ink.rows()
        val s = GLOBAL.toDouble() / max(w, h)
        val nw = max(1, (w * s).roundToInt())
        val nh = max(1, (h * s).roundToInt())
        val small = Mat()
        Imgproc.resize(ink, small, Size(nw.toDouble(), nh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val bytes = ByteArray(nw * nh).also { small.get(0, 0, it) }
        ink.release()
        small.release()
        val out = FloatArray(GLOBAL * GLOBAL)
        val y0 = (GLOBAL - nh) / 2
        val x0 = (GLOBAL - nw) / 2
        for (y in 0 until nh) for (x in 0 until nw) out[(y0 + y) * GLOBAL + x0 + x] = (bytes[y * nw + x].toInt() and 255) / 255f
        return out
    }

    private fun patchInputs(gray: Mat): List<FloatArray> {
        val w0 = gray.cols()
        val h0 = gray.rows()
        // As patches.py: never smaller than one window on either side.
        val s = LONG.toDouble() / max(w0, h0)
        val ink = ink(gray, LONG, enlarge = true)
        val w = ink.cols()
        val h = ink.rows()
        val bytes = ByteArray(w * h).also { ink.get(0, 0, it) }
        ink.release()
        if (w < PATCH || h < PATCH) return emptyList()
        // Summed-area table for window means.
        val integ = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += bytes[y * w + x].toInt() and 255
                integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row
            }
        }
        data class Cand(val score: Double, val y: Int, val x: Int)
        val cands = ArrayList<Cand>()
        var y = 0
        while (y <= h - PATCH) {
            var x = 0
            while (x <= w - PATCH) {
                val sum =
                    integ[(y + PATCH) * (w + 1) + x + PATCH] - integ[y * (w + 1) + x + PATCH] -
                        integ[(y + PATCH) * (w + 1) + x] + integ[y * (w + 1) + x]
                val mean = sum / (PATCH * PATCH)
                if (mean >= MIN_INK) cands += Cand(mean, y, x)
                x += STRIDE
            }
            y += STRIDE
        }
        cands.sortWith(compareBy<Cand>({ -it.score }, { it.y }, { it.x }))
        val kept = ArrayList<Cand>()
        for (c in cands) {
            if (kept.all { kotlin.math.abs(c.y - it.y) >= PATCH / 2 || kotlin.math.abs(c.x - it.x) >= PATCH / 2 }) {
                kept += c
                if (kept.size == 8) break
            }
        }
        return kept.map { c ->
            FloatArray(PATCH * PATCH) { i -> (bytes[(c.y + i / PATCH) * w + c.x + i % PATCH].toInt() and 255) / 255f }
        }
    }
}
