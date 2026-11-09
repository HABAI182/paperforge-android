package cn.paperforge.app.imaging

import ai.onnxruntime.*
import android.content.res.AssetManager
import java.nio.FloatBuffer
import java.security.MessageDigest
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Original tiny foreground classifier. Its output gates original ink, never synthesizes glyphs. */
internal object FastInkModel {
    private var assets: AssetManager? = null
    private var session: OrtSession? = null
    private var last: Pair<String, ByteArray>? = null
    private val permitted = ThreadLocal<Boolean>()

    fun <T> scoped(enabled: Boolean, block: () -> T): T {
        val previous = permitted.get()
        permitted.set(enabled)
        return try {
            block()
        } finally {
            if (previous == null) permitted.remove() else permitted.set(previous)
        }
    }

    fun initialize(source: AssetManager) {
        assets = source
    }

    @Synchronized
    fun confidence(ink: Mat): ByteArray? {
        if (ScanConfig.int("learnedInk", 1) == 0 || permitted.get() == false) return null
        val source = assets ?: return null
        val edge = ScanConfig.int("learnedInkEdge", 1280).coerceIn(640, 1600)
        val raw = ByteArray(ink.rows() * ink.cols())
        ink.get(0, 0, raw)
        val key =
            "${ink.cols()}x${ink.rows()}:$edge:" +
                MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") {
                    "%02x".format(it)
                }
        last
            ?.takeIf { it.first == key }
            ?.let {
                return it.second
            }
        val active =
            session
                ?: OrtSession.SessionOptions()
                    .use { options ->
                        options.setIntraOpNumThreads(2)
                        options.setInterOpNumThreads(1)
                        options.setMemoryPatternOptimization(false)
                        options.setCPUArenaAllocator(false)
                        OrtEnvironment.getEnvironment()
                            .createSession(
                                source.open("retouch/paper-ink-v1.onnx").use { it.readBytes() },
                                options,
                            )
                    }
                    .also { session = it }
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val small = mat()
        val input = mat()
        val soft = mat()
        val broad = mat()
        val score = mat()
        val bytes = mat()
        val full = mat()
        try {
            val scale = minOf(1.0, edge.toDouble() / maxOf(ink.cols(), ink.rows()))
            Imgproc.resize(
                ink,
                small,
                Size(kotlin.math.round(ink.cols() * scale), kotlin.math.round(ink.rows() * scale)),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
            small.convertTo(input, CvType.CV_32FC1, 1.0 / 255)
            Imgproc.GaussianBlur(input, soft, Size(0.0, 0.0), .8)
            Imgproc.GaussianBlur(input, broad, Size(0.0, 0.0), 2.0)
            val area = input.cols() * input.rows()
            val features = FloatArray(area * 3)
            val plane = FloatArray(area)
            listOf(input, soft, broad).forEachIndexed { index, m ->
                m.get(0, 0, plane)
                System.arraycopy(plane, 0, features, index * area, area)
            }
            val predicted = FloatArray(area)
            OnnxTensor.createTensor(
                    OrtEnvironment.getEnvironment(),
                    FloatBuffer.wrap(features),
                    longArrayOf(1, 3, input.rows().toLong(), input.cols().toLong()),
                )
                .use { tensor ->
                    active.run(mapOf("ink_features" to tensor)).use {
                        (it[0] as OnnxTensor).floatBuffer.get(predicted)
                    }
                }
            score.create(input.rows(), input.cols(), CvType.CV_32FC1)
            score.put(0, 0, predicted)
            score.convertTo(bytes, CvType.CV_8UC1, 255.0)
            Imgproc.resize(bytes, full, ink.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val result = ByteArray(raw.size)
            full.get(0, 0, result)
            last = key to result
            return result
        } finally {
            owned.forEach(Mat::release)
        }
    }
}
