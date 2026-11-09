package cn.paperforge.app.imaging

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import cn.paperforge.app.data.RuntimeBudget
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** DocRes learned foreground and appearance tasks; source ink is composited without OCR repaint. */
object NeuralPaper {
    const val SHA = "d11338eb9c5cb67f3c6de192fafe3b050def10465b9b89d7fe47e305dbdcaff9"
    const val VERSION = "docres-foreground-2" // Inference cache: weights and prompt are unchanged.
    const val COMPOSITOR_VERSION = "coherent-ink-6"
    private const val EDGE = 128
    private val inference = Mutex()

    fun available(context: Context) =
        context.assets.list("retouch")?.contains("docres-q8.onnx") == true

    private fun sourceHash(source: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val pixels = IntArray(source.width)
        val bytes = ByteArray(source.width * 4)
        for (y in 0 until source.height) {
            source.getPixels(pixels, 0, source.width, 0, y, source.width, 1)
            for (x in pixels.indices) for (c in 0..3) bytes[x * 4 + c] =
                (pixels[x] ushr (c * 8)).toByte()
            digest.update(bytes)
        }
        digest.update("${source.width}x${source.height}".toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The prompt follows the published DocRes appearance preprocessing (MIT), including BGR. */
    private fun prompt(source: Bitmap): Bitmap {
        if (ScanConfig.int("docresForeground", 1) == 1) return foregroundPrompt(source)
        val rgba = Mat()
        val bgr = Mat()
        val small = Mat()
        val merged = Mat()
        val resized = Mat()
        val rgb = Mat()
        val channels = mutableListOf<Mat>()
        val restored = mutableListOf<Mat>()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.resize(bgr, small, Size(1024.0, 1024.0))
            Core.split(small, channels)
            for (plane in channels) {
                val background = Mat()
                val diff = Mat()
                val result = Mat()
                try {
                    Imgproc.dilate(plane, background, kernel)
                    Imgproc.medianBlur(background, background, 21)
                    Core.absdiff(plane, background, diff)
                    Core.bitwise_not(diff, diff)
                    Core.normalize(diff, result, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8UC1)
                    restored += result
                } finally {
                    background.release()
                    diff.release()
                }
            }
            Core.merge(restored, merged)
            Imgproc.resize(merged, resized, Size(source.width.toDouble(), source.height.toDouble()))
            Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
            return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(rgb, it)
            }
        } finally {
            (listOf(rgba, bgr, small, merged, resized, rgb, kernel) + channels + restored).forEach(
                Mat::release
            )
        }
    }

    /** Published binarization prompt: two Sauvola passes, gradient and binary prior. */
    private fun foregroundPrompt(source: Bitmap): Bitmap {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val rgba = mat()
        val bgr = mat()
        val gray = mat()
        val contrast = mat()
        val binary = mat()
        val gradient = mat()
        fun threshold(image: Mat, window: Int): Mat {
            val f = mat()
            val mean = mat()
            val square = mat()
            val energy = mat()
            val out = mat()
            image.convertTo(f, CvType.CV_32FC1)
            Core.multiply(f, f, square)
            Imgproc.boxFilter(
                f,
                mean,
                -1,
                Size(window.toDouble(), window.toDouble()),
                Point(-1.0, -1.0),
                true,
                Core.BORDER_REFLECT_101,
            )
            Imgproc.boxFilter(
                square,
                energy,
                -1,
                Size(window.toDouble(), window.toDouble()),
                Point(-1.0, -1.0),
                true,
                Core.BORDER_REFLECT_101,
            )
            val a = FloatArray(source.width * source.height)
            val b = FloatArray(a.size)
            mean.get(0, 0, a)
            energy.get(0, 0, b)
            for (i in a.indices) a[i] *=
                1f + .5f * (sqrt(max(0f, b[i] - a[i] * a[i])) / 127.5f - 1f)
            out.create(source.height, source.width, CvType.CV_32FC1)
            out.put(0, 0, a)
            return out
        }
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            val short = min(source.width, source.height)
            val first = threshold(gray, max(3, (short * .05f).toInt() or 1))
            val raw = ByteArray(source.width * source.height)
            gray.get(0, 0, raw)
            val t1 = FloatArray(raw.size)
            first.get(0, 0, t1)
            val high = Core.minMaxLoc(gray).maxVal.toFloat()
            val c = ByteArray(raw.size)
            for (i in c.indices) {
                val v = (raw[i].toInt() and 255).toFloat()
                c[i] =
                    (if (v > t1[i])
                            ((v - t1[i]) / max(.00001f, high - t1[i]) * 255)
                                .toInt()
                                .coerceIn(0, 255)
                        else 0)
                        .toByte()
            }
            contrast.create(source.height, source.width, CvType.CV_8UC1)
            contrast.put(0, 0, c)
            val second = threshold(contrast, max(3, (short * .1f).toInt() or 1))
            val t2 = FloatArray(raw.size)
            second.get(0, 0, t2)
            val binaryBytes =
                ByteArray(c.size) { if ((c[it].toInt() and 255) > t2[it]) 255.toByte() else 0 }
            binary.create(source.height, source.width, CvType.CV_8UC1)
            binary.put(0, 0, binaryBytes)
            val sx = mat()
            val sy = mat()
            val ax = mat()
            val ay = mat()
            val edges = mat()
            Imgproc.Sobel(bgr, sx, CvType.CV_16S, 1, 0)
            Imgproc.Sobel(bgr, sy, CvType.CV_16S, 0, 1)
            Core.convertScaleAbs(sx, ax)
            Core.convertScaleAbs(sy, ay)
            Core.addWeighted(ax, .5, ay, .5, 0.0, edges)
            Imgproc.cvtColor(edges, gradient, Imgproc.COLOR_BGR2GRAY)
            val thresholds = mat()
            second.convertTo(thresholds, CvType.CV_8UC1)
            val merged = mat()
            val rgb = mat()
            Core.merge(listOf(thresholds, gradient, binary), merged)
            Imgproc.cvtColor(merged, rgb, Imgproc.COLOR_BGR2RGB)
            return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(rgb, it)
            }
        } finally {
            owned.forEach(Mat::release)
        }
    }

    private suspend fun restored(
        context: Context,
        source: Bitmap,
        progress: suspend (Int) -> Unit,
    ): Bitmap =
        inference.withLock {
            val foreground = ScanConfig.int("docresForeground", 1) == 1
            val folder = File(context.cacheDir, "docres-paper").apply { mkdirs() }
            val cache =
                File(
                    folder,
                    "${sourceHash(source)}-$SHA-${if(foreground) VERSION else "appearance"}-e${ScanConfig.int("docresWorkEdge",1280)}-p${ScanConfig.int("docresContext",16)}.png",
                )
            android.graphics.BitmapFactory.decodeFile(cache.path)?.let {
                progress(85)
                return@withLock it
            }
            val maxEdge = ScanConfig.int("docresWorkEdge", 1280).coerceIn(512, 1600)
            val scale = min(1f, maxEdge.toFloat() / max(source.width, source.height))
            val input =
                if (scale < 1f)
                    Bitmap.createScaledBitmap(
                        source,
                        (source.width * scale).roundToInt(),
                        (source.height * scale).roundToInt(),
                        true,
                    )
                else source
            val guidance = prompt(input)
            val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
            try {
                val model = File(context.cacheDir, "docres-$SHA.onnx")
                if (!model.isFile || model.length() != 15890584L)
                    context.assets.open("retouch/docres-q8.onnx").use { stream ->
                        model.outputStream().use { stream.copyTo(it) }
                    }
                RuntimeBudget.ocr.use(
                    interactive =
                        currentCoroutineContext()[cn.paperforge.app.data.BackgroundWork.Key] == null
                ) {
                    OrtSession.SessionOptions().use { options ->
                        options.setIntraOpNumThreads(2)
                        options.setInterOpNumThreads(1)
                        options.setMemoryPatternOptimization(false)
                        options.setCPUArenaAllocator(false)

                        options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
                        OrtEnvironment.getEnvironment().createSession(model.path, options).use {
                            session ->
                            val pad = ScanConfig.int("docresContext", 16).coerceIn(8, 24)
                            val step = EDGE - 2 * pad
                            val area = EDGE * EDGE
                            val tiles =
                                ceil(input.width.toDouble() / step).toInt() *
                                    ceil(input.height.toDouble() / step).toInt()
                            var completed = 0
                            val values = FloatArray(area * 6)
                            val result = FloatArray(area * 3)
                            for (top in 0 until input.height step step) for (left in
                                0 until input.width step step) {
                                currentCoroutineContext().ensureActive()
                                val x0 = max(0, left - pad)
                                val y0 = max(0, top - pad)
                                val rw = min(input.width, left - pad + EDGE) - x0
                                val rh = min(input.height, top - pad + EDGE) - y0
                                val photo = IntArray(rw * rh)
                                val guide = IntArray(rw * rh)
                                input.getPixels(photo, 0, rw, x0, y0, rw, rh)
                                guidance.getPixels(guide, 0, rw, x0, y0, rw, rh)
                                for (y in 0 until EDGE) for (x in 0 until EDGE) {
                                    val index =
                                        ((top - pad + y).coerceIn(0, input.height - 1) - y0) * rw +
                                            (left - pad + x).coerceIn(0, input.width - 1) - x0
                                    val offset = y * EDGE + x
                                    for (c in 0..2) {
                                        values[c * area + offset] =
                                            (photo[index] ushr (c * 8) and 255) / 255f
                                        values[(c + 3) * area + offset] =
                                            (guide[index] ushr (c * 8) and 255) / 255f
                                    }
                                }
                                OnnxTensor.createTensor(
                                        OrtEnvironment.getEnvironment(),
                                        FloatBuffer.wrap(values),
                                        longArrayOf(1, 6, EDGE.toLong(), EDGE.toLong()),
                                    )
                                    .use { tensor ->
                                        session.run(mapOf("image_prompt" to tensor)).use {
                                            prediction ->
                                            (prediction[0] as OnnxTensor).floatBuffer.get(result)
                                        }
                                    }
                                val tw = min(step, input.width - left)
                                val th = min(step, input.height - top)
                                val pixels = IntArray(tw * th)
                                for (y in 0 until th) for (x in 0 until tw) {
                                    val index = (y + pad) * EDGE + x + pad
                                    fun channel(c: Int) =
                                        (result[c * area + index] * 255)
                                            .roundToInt()
                                            .coerceIn(0, 255)
                                    pixels[y * tw + x] =
                                        if (foreground) {
                                            val difference =
                                                (result[area + index] - result[index]).coerceIn(
                                                    -60f,
                                                    60f,
                                                )
                                            val white =
                                                (255f * (1f - 1f / (1f + exp(difference))))
                                                    .roundToInt()
                                                    .coerceIn(0, 255)
                                            Color.rgb(white, white, white)
                                        } else Color.rgb(channel(2), channel(1), channel(0))
                                }
                                output.setPixels(pixels, 0, tw, left, top, tw, th)
                                progress(5 + (++completed) * 80 / tiles)
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                // Official high-resolution appearance branch: upsample an observed tone ratio,
                // then apply it to native-resolution ink instead of enlarging a small raster.
                val full =
                    if (input === source) output.copy(Bitmap.Config.ARGB_8888, false)
                    else if (foreground)
                        Bitmap.createScaledBitmap(output, source.width, source.height, true)
                    else {
                        val ratios = Mat(input.height, input.width, CvType.CV_32FC3)
                        val dense = Mat()
                        try {
                            val row = FloatArray(input.width * 3)
                            val a = IntArray(input.width)
                            val b = IntArray(input.width)
                            for (y in 0 until input.height) {
                                input.getPixels(a, 0, input.width, 0, y, input.width, 1)
                                output.getPixels(b, 0, input.width, 0, y, input.width, 1)
                                for (x in a.indices) for (c in 0..2) row[x * 3 + c] =
                                    (b[x] ushr (c * 8) and 255).toFloat() /
                                        max(1, a[x] ushr (c * 8) and 255)
                                ratios.put(y, 0, row)
                            }
                            Imgproc.resize(
                                ratios,
                                dense,
                                Size(source.width.toDouble(), source.height.toDouble()),
                            )
                            val full =
                                Bitmap.createBitmap(
                                    source.width,
                                    source.height,
                                    Bitmap.Config.ARGB_8888,
                                )
                            val colors = IntArray(source.width)
                            val gain = FloatArray(source.width * 3)
                            for (y in 0 until source.height) {
                                source.getPixels(colors, 0, source.width, 0, y, source.width, 1)
                                dense.get(y, 0, gain)
                                for (x in colors.indices) {
                                    val color = colors[x]
                                    fun value(c: Int) =
                                        ((color ushr (c * 8) and 255) * gain[x * 3 + c])
                                            .roundToInt()
                                            .coerceIn(0, 255)
                                    colors[x] = Color.rgb(value(2), value(1), value(0))
                                }
                                full.setPixels(colors, 0, source.width, 0, y, source.width, 1)
                            }
                            full
                        } finally {
                            ratios.release()
                            dense.release()
                        }
                    }
                val part = File(cache.path + ".part")
                try {
                    part.outputStream().use {
                        check(full.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    check(part.renameTo(cache))
                } catch (e: Throwable) {
                    full.recycle()
                    throw e
                } finally {
                    part.delete()
                }
                full
            } finally {
                guidance.recycle()
                output.recycle()
                if (input !== source) input.recycle()
            }
        }

    /**
     * Connected stroke support: faint pixels attached to strong ink survive; weak isolated
     * rear-print groups and detached specks receive less weight. Rules remain a separate path.
     */
    private fun strokeSupport(ink: Mat, rules: Mat, confidence: Mat): Pair<ByteArray, ByteArray> {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val mask = mat()
        val labels = mat()
        val stats = mat()
        val centers = mat()
        val seeds = mat()
        val nearby = mat()
        val w = ink.cols()
        val h = ink.rows()
        val size = w * h
        val raw = ByteArray(size)
        val line = ByteArray(size)
        val probability = ByteArray(size)
        ink.get(0, 0, raw)
        rules.get(0, 0, line)
        confidence.get(0, 0, probability)
        try {
            val supportFloor = ScanConfig.float("foregroundInkFloor", 12f)
            val bytes =
                ByteArray(size) { i ->
                    if (
                        (raw[i].toInt() and 255) > supportFloor &&
                            (line[i].toInt() and 255) < 10 &&
                            (probability[i].toInt() and 255) < 192
                    )
                        255.toByte()
                    else 0
                }
            mask.create(h, w, CvType.CV_8UC1)
            mask.put(0, 0, bytes)
            val count =
                Imgproc.connectedComponentsWithStats(mask, labels, stats, centers, 8, CvType.CV_32S)
            val ids = IntArray(size)
            labels.get(0, 0, ids)
            val peak = IntArray(count)
            val areas = IntArray(count)
            val sums = DoubleArray(count)
            val squares = DoubleArray(count)
            for (i in ids.indices) {
                val id = ids[i]
                val value = raw[i].toInt() and 255
                peak[id] = max(peak[id], value)
                sums[id] += value
                squares[id] += value.toDouble() * value
            }
            for (id in 0 until count) areas[id] = stats.get(id, Imgproc.CC_STAT_AREA)[0].toInt()
            val reliableArea = max(12, size / 200000)
            val floor = ScanConfig.float("foregroundComponentFloor", 22f)
            val range = ScanConfig.float("foregroundComponentRange", 30f).coerceAtLeast(1f)
            // One dark crossing/speck must not promote an entire faint rear-print group.
            // A robust stroke level keeps consistently dark pen strokes and discounts groups
            // whose peak is an outlier. Confidence still comes from the learned foreground.
            val levels =
                FloatArray(count) { id ->
                    val mean = sums[id] / max(1, areas[id])
                    val variance = max(0.0, squares[id] / max(1, areas[id]) - mean * mean)
                    min(
                        peak[id].toFloat(),
                        (mean + ScanConfig.float("foregroundComponentSpread", .7f) * sqrt(variance))
                            .toFloat(),
                    )
                }
            val weights = FloatArray(count) { ((levels[it] - floor) / range).coerceIn(0f, 1f) }
            weights[0] = 0f
            seeds.create(h, w, CvType.CV_8UC1)
            seeds.put(
                0,
                0,
                ByteArray(size) {
                    if (
                        ids[it] != 0 &&
                            areas[ids[it]] >= reliableArea &&
                            levels[ids[it]] > floor + range
                    )
                        255.toByte()
                    else 0
                },
            )
            val radius = (min(w, h) * .018f).roundToInt().coerceAtLeast(3) or 1
            val kernel =
                Imgproc.getStructuringElement(
                        Imgproc.MORPH_ELLIPSE,
                        Size(radius.toDouble(), radius.toDouble()),
                    )
                    .also(owned::add)
            Imgproc.dilate(seeds, nearby, kernel)
            nearby.get(0, 0, bytes)
            val speckArea = max(3, size / 500000)
            val gain =
                ByteArray(size) { i ->
                    val id = ids[i]
                    val isolated = areas[id] <= speckArea && bytes[i] == 0.toByte()
                    (255f * weights[id] * if (isolated) .15f else 1f).roundToInt().toByte()
                }
            return gain to bytes
        } finally {
            owned.forEach(Mat::release)
        }
    }

    private fun finishForeground(
        output: Bitmap,
        context: ByteArray?,
        amount: Float,
        rules: Mat,
        foreground: ByteArray?,
    ) {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val rgba = mat()
        val gray = mat()
        val mask = mat()
        val labels = mat()
        val stats = mat()
        val centers = mat()
        val size = output.width * output.height
        try {
            Utils.bitmapToMat(output, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val values = ByteArray(size)
            gray.get(0, 0, values)
            if (context != null) {
                Imgproc.threshold(gray, mask, 240.0, 255.0, Imgproc.THRESH_BINARY_INV)
                val count =
                    Imgproc.connectedComponentsWithStats(
                        mask,
                        labels,
                        stats,
                        centers,
                        8,
                        CvType.CV_32S,
                    )
                val ids = IntArray(size)
                labels.get(0, 0, ids)
                val nearby = IntArray(count)
                for (i in ids.indices) if (context[i] != 0.toByte()) nearby[ids[i]]++
                val maximum =
                    max(6, (size * ScanConfig.float("foregroundSpeckArea", .00001f)).roundToInt())
                val allStats = IntArray(count * 5)
                stats.get(0, 0, allStats)
                val drop =
                    BooleanArray(count) { id ->
                        val area = allStats[id * 5 + Imgproc.CC_STAT_AREA]
                        id != 0 && area <= maximum && nearby[id] < area * .2
                    }
                for (i in values.indices) if (drop[ids[i]]) {
                    val v = values[i].toInt() and 255
                    values[i] = (v + (255 - v) * amount).roundToInt().toByte()
                }
            }
            val line = ByteArray(size)
            rules.get(0, 0, line)
            InkComponents.cleanFaint(values, line, foreground, output.width, output.height, amount)
            val pixels = IntArray(output.width)
            for (y in 0 until output.height) {
                for (x in pixels.indices) {
                    val value = values[y * output.width + x].toInt() and 255
                    pixels[x] = Color.rgb(value, value, value)
                }
                output.setPixels(pixels, 0, output.width, 0, y, output.width, 1)
            }
        } finally {
            owned.forEach(Mat::release)
        }
    }

    suspend fun apply(
        context: Context,
        source: Bitmap,
        strength: Float,
        progress: suspend (Int) -> Unit,
    ): Bitmap =
        withContext(Dispatchers.Default) {
            val amount = strength.coerceIn(0f, 1f)
            if (amount <= 0f) return@withContext source.copy(Bitmap.Config.ARGB_8888, false)
            val restored = restored(context, source, progress)
            val owned = mutableListOf<Mat>()
            fun mat() = Mat().also(owned::add)
            val rgba = mat()
            val gray = mat()
            val field = mat()
            val paper = mat()
            val ink = mat()
            val rules = mat()
            val confidence = mat()
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            try {
                Utils.bitmapToMat(source, rgba)
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                EnhancePipeline.paperField(
                    gray,
                    paper,
                    (min(source.width, source.height) * .035f).roundToInt().coerceIn(15, 151) or 1,
                )
                val raw = ByteArray(source.width * source.height)
                val local = ByteArray(raw.size)
                gray.get(0, 0, raw)
                paper.get(0, 0, local)
                val residual =
                    ByteArray(raw.size) { i ->
                        (255f -
                                min(
                                    255f,
                                    (raw[i].toInt() and 255) * 255f /
                                        max(20, local[i].toInt() and 255),
                                ))
                            .roundToInt()
                            .toByte()
                    }
                ink.create(source.height, source.width, CvType.CV_8UC1)
                ink.put(0, 0, residual)
                EnhancePipeline.rulingEvidence(
                    ink,
                    rules,
                    ScanConfig.float("foregroundRuleSpan", .35f),
                )
                Utils.bitmapToMat(restored, rgba)
                Imgproc.cvtColor(rgba, confidence, Imgproc.COLOR_RGBA2GRAY)
                val support =
                    if (ScanConfig.int("foregroundCoherence", 1) == 1)
                        strokeSupport(ink, rules, confidence)
                    else null
                val w = source.width
                val model = ByteArray(w)
                val line = ByteArray(w)
                val pixels = IntArray(w)
                val neural = ScanConfig.int("docresForeground", 1) == 1
                val foregroundFloor = ScanConfig.float("foregroundFloor", .24f)
                val foregroundRange = ScanConfig.float("foregroundRange", .58f)
                val inkFloor = ScanConfig.float("foregroundInkFloor", 12f)
                val inkGain = ScanConfig.float("foregroundInkGain", 4.5f)
                val ambiguityPenalty = ScanConfig.float("foregroundAmbiguityPenalty", .1f)
                val learned = FastInkModel.confidence(ink)
                val learnedFloor = ScanConfig.float("learnedInkFloor", .02f)
                val learnedRange = ScanConfig.float("learnedInkRange", .65f)
                val learnedStrength = ScanConfig.float("learnedInkStrength", .9f)
                val ruleInkCeiling = ScanConfig.float("workbookRuleInkCeiling", 1.15f)
                for (y in 0 until source.height) {
                    if (y % 64 == 0) {
                        currentCoroutineContext().ensureActive()
                        progress(86 + y * 14 / source.height)
                    }
                    confidence.get(y, 0, model)
                    rules.get(y, 0, line)
                    for (x in 0 until w) {
                        val i = y * w + x
                        val c = (residual[i].toInt() and 255).toFloat()
                        val p = (255 - (model[x].toInt() and 255)) / 255f
                        // Weak, ambiguous texture needs stronger model agreement; strong ink
                        // retains the original threshold and full-resolution stroke contrast.
                        val ambiguity = ((70f - c) / 70f).coerceIn(0f, 1f) * ambiguityPenalty
                        val t =
                            ((p - foregroundFloor - ambiguity * .5f) /
                                    (foregroundRange + ambiguity * .5f))
                                .coerceIn(0f, 1f)
                        var text =
                            if (neural)
                                min(255f, max(0f, c - inkFloor) * inkGain) *
                                    t *
                                    t *
                                    (3 - 2 * t) *
                                    (support?.first?.get(i)?.toInt()?.and(255)?.div(255f) ?: 1f)
                            else {
                                val v = 255f - (model[x].toInt() and 255)
                                val a = ((v - 26f) / 39f).coerceIn(0f, 1f)
                                min(255f, v * a * a * (3 - 2 * a) * 1.3f)
                            }
                        if (learned != null) {
                            val probability = (learned[i].toInt() and 255) / 255f
                            val m = ((probability - learnedFloor) / learnedRange).coerceIn(0f, 1f)
                            val hard = ((c - 90f) / 65f).coerceIn(0f, 1f)
                            text *=
                                1f - learnedStrength +
                                    learnedStrength * max(m * m * (3f - 2f * m), hard)
                        }
                        val evidence = (line[x].toInt() and 255).toFloat()
                        val rule =
                            min(210f, min(c, evidence * ruleInkCeiling) * 1.9f) *
                                ((evidence - 7f) / 16f).coerceIn(0f, 1f)
                        val cleaned = 255f - max(text, rule)
                        val original = (raw[i].toInt() and 255).toFloat()
                        val value =
                            (if (WorkbookContent.enabled()) cleaned
                                else original + (cleaned - original) * amount)
                                .roundToInt()
                                .coerceIn(0, 255)
                        pixels[x] = Color.rgb(value, value, value)
                    }
                    output.setPixels(pixels, 0, w, 0, y, w, 1)
                }
                if (WorkbookContent.enabled()) {
                    val sourceRgb = mat()
                    val candidate = mat()
                    val protected = mat()
                    Utils.bitmapToMat(source, rgba)
                    Imgproc.cvtColor(rgba, sourceRgb, Imgproc.COLOR_RGBA2RGB)
                    Utils.bitmapToMat(output, candidate)
                    WorkbookContent.guard(sourceRgb, candidate, protected, amount)
                    Utils.matToBitmap(protected, output)
                } else if (neural) finishForeground(output, support?.second, amount, rules, learned)
                output
            } catch (e: Throwable) {
                output.recycle()
                throw e
            } finally {
                restored.recycle()
                owned.forEach(Mat::release)
            }
        }
}
