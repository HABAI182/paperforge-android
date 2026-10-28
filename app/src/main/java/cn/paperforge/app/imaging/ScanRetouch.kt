package cn.paperforge.app.imaging

import ai.onnxruntime.*
import android.content.Context
import android.graphics.*
import cn.paperforge.app.data.RuntimeBudget
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

data class RetouchPoint(val x: Float, val y: Float)

data class RetouchStroke(
    val points: List<RetouchPoint>,
    val radius: Float,
    val erase: Boolean = false,
)

data class RetouchRequest(
    val mode: String = "REPAIR",
    val strength: Float = 1f,
    val strokes: List<RetouchStroke> = emptyList(),
    val modelSha: String =
        if (mode == "MIGAN") RetouchModels.MIGAN_SHA
        else if (mode == "SR") RetouchModels.SR_SHA else "",
) {
    fun json() =
        JSONObject()
            .put("version", 1)
            .put("algorithm", ScanRetouch.VERSION)
            .put("mode", mode)
            .put("strength", strength)
            .put("modelSha", modelSha)
            .put(
                "strokes",
                JSONArray(
                    strokes.map { stroke ->
                        JSONObject()
                            .put("radius", stroke.radius)
                            .put("erase", stroke.erase)
                            .put(
                                "points",
                                JSONArray(stroke.points.map { JSONArray(listOf(it.x, it.y)) }),
                            )
                    }
                ),
            )

    companion object {
        fun decode(json: JSONObject): RetouchRequest {
            val strokes = json.optJSONArray("strokes") ?: JSONArray()
            return RetouchRequest(
                json.optString("mode", "REPAIR"),
                json.optDouble("strength", 1.0).toFloat().coerceIn(0f, 1f),
                List(strokes.length()) { i ->
                    val s = strokes.getJSONObject(i)
                    val p = s.getJSONArray("points")
                    RetouchStroke(
                        List(p.length()) { j ->
                            RetouchPoint(
                                p.getJSONArray(j).getDouble(0).toFloat().coerceIn(0f, 1f),
                                p.getJSONArray(j).getDouble(1).toFloat().coerceIn(0f, 1f),
                            )
                        },
                        s.getDouble("radius").toFloat().coerceIn(.001f, .3f),
                        s.optBoolean("erase"),
                    )
                },
                json.optString("modelSha"),
            )
        }
    }
}

/** Selected pixels only. Learned output is composited over a byte-identical unselected source. */
object ScanRetouch {
    const val VERSION = "retouch-1"
    val modes =
        linkedMapOf(
            "REPAIR" to "局部修补",
            "MIGAN" to "智能填补",
            "INK" to "彩色笔迹",
            "DENOISE" to "去噪",
            "GLARE" to "高光压制",
            "SR" to "3× 超分",
        )

    fun mask(width: Int, height: Int, strokes: List<RetouchStroke>): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        strokes.forEach { stroke ->
            paint.color = if (stroke.erase) Color.BLACK else Color.WHITE
            paint.strokeWidth = 2 * stroke.radius * min(width, height)
            paint.style = Paint.Style.STROKE
            val path = Path()
            stroke.points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x * width, p.y * height)
                else path.lineTo(p.x * width, p.y * height)
            }
            canvas.drawPath(path, paint)
            stroke.points.firstOrNull()?.let { p ->
                paint.style = Paint.Style.FILL
                canvas.drawCircle(p.x * width, p.y * height, paint.strokeWidth / 2, paint)
            }
        }
        return bitmap
    }

    suspend fun apply(
        context: Context,
        source: Bitmap,
        request: RetouchRequest,
        progress: suspend (Int) -> Unit = {},
    ): Bitmap =
        withContext(Dispatchers.Default) {
            require(request.mode in modes) { "未知修复方式" }
            val w = source.width
            val h = source.height
            val selected = IntArray(w * h)
            mask(w, h, request.strokes).also {
                it.getPixels(selected, 0, w, 0, 0, w, h)
                it.recycle()
            }
            val hasMask = selected.any { it and 255 != 0 }
            require(hasMask || request.mode !in setOf("REPAIR", "MIGAN", "INK")) { "请先涂抹需要处理的区域" }
            if (!hasMask) selected.fill(Color.WHITE)
            if (request.strength <= 0)
                return@withContext source.copy(Bitmap.Config.ARGB_8888, false)
            progress(5)
            currentCoroutineContext().ensureActive()
            if (request.mode == "SR") {
                require(request.modelSha == RetouchModels.SR_SHA) { "超分模型版本已变化，请重新提交" }
                return@withContext superResolve(
                    context,
                    source,
                    selected,
                    request.strength,
                    progress,
                )
            }
            val altered =
                if (request.mode == "MIGAN") {
                    require(request.modelSha == RetouchModels.MIGAN_SHA) { "填补模型版本已变化，请重新提交" }
                    native { inpaint(context, source, selected) }
                } else if (request.mode == "DENOISE") denoise(source, selected, progress)
                else native { classical(source, selected, request.mode) }
            try {
                currentCoroutineContext().ensureActive()
                val pixels = IntArray(w * h)
                source.getPixels(pixels, 0, w, 0, 0, w, h)
                val changed = IntArray(w * h)
                altered.getPixels(changed, 0, w, 0, 0, w, h)
                for (i in pixels.indices) pixels[i] =
                    blend(pixels[i], changed[i], (selected[i] and 255) / 255f * request.strength)
                progress(95)
                Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            } finally {
                altered.recycle()
            }
        }

    private suspend fun <T> native(action: () -> T): T =
        RuntimeBudget.ocr.use(
            interactive =
                currentCoroutineContext()[cn.paperforge.app.data.BackgroundWork.Key] == null
        ) {
            action()
        }

    /**
     * Fixed working tiles, exact context cropping, cancellation/interactive priority between
     * passes.
     */
    private suspend fun denoise(
        source: Bitmap,
        selected: IntArray,
        progress: suspend (Int) -> Unit,
    ): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val stride = 256
        val count = ceil(source.width / 256.0).toInt() * ceil(source.height / 256.0).toInt()
        var done = 0
        try {
            for (top in 0 until source.height step stride) for (left in
                0 until source.width step stride) {
                currentCoroutineContext().ensureActive()
                val right = min(source.width, left + stride)
                val bottom = min(source.height, top + stride)
                val selectedTile =
                    (top until bottom).any { y ->
                        (left until right).any { x -> selected[y * source.width + x] and 255 != 0 }
                    }
                if (selectedTile) {
                    val x0 = max(0, left - 8)
                    val y0 = max(0, top - 8)
                    val x1 = min(source.width, right + 8)
                    val y1 = min(source.height, bottom + 8)
                    val tile = Bitmap.createBitmap(source, x0, y0, x1 - x0, y1 - y0)
                    try {
                        val cleaned = native { classical(tile, intArrayOf(), "DENOISE") }
                        try {
                            canvas.drawBitmap(
                                cleaned,
                                android.graphics.Rect(left - x0, top - y0, right - x0, bottom - y0),
                                android.graphics.Rect(left, top, right, bottom),
                                null,
                            )
                        } finally {
                            cleaned.recycle()
                        }
                    } finally {
                        if (tile !== source) tile.recycle()
                    }
                }
                progress(5 + ++done * 90 / count)
            }
            return result
        } catch (e: Throwable) {
            result.recycle()
            throw e
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        if (t <= 0) return a
        return Color.rgb(
            ((a shr 16 and 255) * (1 - t) + (b shr 16 and 255) * t).roundToInt(),
            ((a shr 8 and 255) * (1 - t) + (b shr 8 and 255) * t).roundToInt(),
            ((a and 255) * (1 - t) + (b and 255) * t).roundToInt(),
        )
    }

    private fun classical(source: Bitmap, selected: IntArray, mode: String): Bitmap {
        val rgba = Mat()
        val rgb = Mat()
        val mask = Mat(source.height, source.width, CvType.CV_8UC1)
        val result = Mat()
        val lab = Mat()
        val equal = Mat()
        val channels = mutableListOf<Mat>()
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            when (mode) {
                "REPAIR",
                "INK" -> {
                    val pixels = IntArray(selected.size)
                    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
                    val bytes =
                        ByteArray(selected.size) { i ->
                            val c = pixels[i]
                            val hi = maxOf(c shr 16 and 255, c shr 8 and 255, c and 255)
                            val lo = minOf(c shr 16 and 255, c shr 8 and 255, c and 255)
                            if (
                                (selected[i] and 255) > 32 &&
                                    (mode != "INK" || hi - lo > 35 && hi > 70)
                            )
                                255.toByte()
                            else 0
                        }
                    require(bytes.any { it != 0.toByte() }) { "选区内没有明显彩色笔迹" }
                    mask.put(0, 0, bytes)
                    Photo.inpaint(rgb, mask, result, 3.0, Photo.INPAINT_TELEA)
                }
                "DENOISE" -> Photo.fastNlMeansDenoisingColored(rgb, result, 7f, 7f, 3, 11)
                "GLARE" -> {
                    Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
                    Core.split(lab, channels)
                    val clahe = Imgproc.createCLAHE(1.8, Size(8.0, 8.0))
                    try {
                        clahe.apply(channels[0], equal)
                    } finally {
                        clahe.collectGarbage()
                        clahe.clear()
                    }
                    val original = ByteArray(selected.size)
                    val mapped = ByteArray(selected.size)
                    channels[0].get(0, 0, original)
                    equal.get(0, 0, mapped)
                    for (i in original.indices) {
                        val l = original[i].toInt() and 255
                        val amount = ((l - 145) / 65f).coerceIn(0f, 1f)
                        original[i] =
                            (l * (1 - amount) + (mapped[i].toInt() and 255) * amount)
                                .roundToInt()
                                .toByte()
                    }
                    channels[0].put(0, 0, original)
                    Core.merge(channels, lab)
                    Imgproc.cvtColor(lab, result, Imgproc.COLOR_Lab2RGB)
                }
            }
            Imgproc.cvtColor(result, rgba, Imgproc.COLOR_RGB2RGBA)
            return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(rgba, it)
            }
        } finally {
            (listOf(rgba, rgb, mask, result, lab, equal) + channels).forEach { it.release() }
        }
    }

    private fun inpaint(context: Context, source: Bitmap, selected: IntArray): Bitmap {
        val w = source.width
        val h = source.height
        var x0 = w
        var y0 = h
        var x1 = 0
        var y1 = 0
        selected.forEachIndexed { i, c ->
            if (c and 255 > 32) {
                x0 = min(x0, i % w)
                x1 = max(x1, i % w)
                y0 = min(y0, i / w)
                y1 = max(y1, i / w)
            }
        }
        require(x0 < w) { "请先涂抹选区" }
        x0 = max(0, x0 - 128)
        y0 = max(0, y0 - 128)
        x1 = min(w, x1 + 129)
        y1 = min(h, y1 + 129)
        val crop = Bitmap.createBitmap(source, x0, y0, x1 - x0, y1 - y0)
        val scale = min(1f, 1024f / max(crop.width, crop.height))
        val input =
            Bitmap.createScaledBitmap(
                crop,
                max(1, (crop.width * scale).roundToInt()),
                max(1, (crop.height * scale).roundToInt()),
                true,
            )
        var output: Bitmap? = null
        try {
            val size = input.width * input.height
            val pixels = IntArray(size)
            input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
            val data = ByteBuffer.allocateDirect(size * 3)
            val holes = ByteBuffer.allocateDirect(size)
            for (axis in 0..2) pixels.forEach {
                data.put((it shr (16 - axis * 8) and 255).toByte())
            }
            for (y in 0 until input.height) for (x in 0 until input.width) {
                val sx = (x0 + x / scale).toInt().coerceIn(0, w - 1)
                val sy = (y0 + y / scale).toInt().coerceIn(0, h - 1)
                holes.put(if (selected[sy * w + sx] and 255 > 32) 0 else 255.toByte())
            }
            data.rewind()
            holes.rewind()
            val env = OrtEnvironment.getEnvironment()
            RetouchModels.session(context, true).use { session ->
                OnnxTensor.createTensor(
                        env,
                        data,
                        longArrayOf(1, 3, input.height.toLong(), input.width.toLong()),
                        OnnxJavaType.UINT8,
                    )
                    .use { image ->
                        OnnxTensor.createTensor(
                                env,
                                holes,
                                longArrayOf(1, 1, input.height.toLong(), input.width.toLong()),
                                OnnxJavaType.UINT8,
                            )
                            .use { mask ->
                                session.run(mapOf("image" to image, "mask" to mask)).use { results
                                    ->
                                    val values = (results[0] as OnnxTensor).byteBuffer
                                    for (i in pixels.indices) pixels[i] =
                                        Color.rgb(
                                            values[i].toInt() and 255,
                                            values[size + i].toInt() and 255,
                                            values[2 * size + i].toInt() and 255,
                                        )
                                }
                            }
                    }
            }
            output = Bitmap.createBitmap(pixels, input.width, input.height, Bitmap.Config.ARGB_8888)
            return source.copy(Bitmap.Config.ARGB_8888, true).also {
                Canvas(it)
                    .drawBitmap(
                        output,
                        null,
                        android.graphics.Rect(x0, y0, x1, y1),
                        Paint(Paint.FILTER_BITMAP_FLAG),
                    )
            }
        } finally {
            output?.recycle()
            if (input !== crop) input.recycle()
            if (crop !== source) crop.recycle()
        }
    }

    /** 224px fixed model, 16px context on each side. Crop context instead of stitching seams. */
    private suspend fun superResolve(
        context: Context,
        source: Bitmap,
        selected: IntArray,
        strength: Float,
        progress: suspend (Int) -> Unit,
    ): Bitmap {
        val scale = min(1f, 4096f / (3 * max(source.width, source.height)))
        val low =
            Bitmap.createScaledBitmap(
                source,
                max(1, (source.width * scale).roundToInt()),
                max(1, (source.height * scale).roundToInt()),
                true,
            )
        val w = low.width
        val h = low.height
        val width = w * 3
        val height = h * 3
        val baseline = Bitmap.createScaledBitmap(source, width, height, true)
        val result = baseline.copy(Bitmap.Config.ARGB_8888, true)
        if (baseline !== source && baseline !== low) baseline.recycle()
        val sourcePixels = IntArray(w * h)
        low.getPixels(sourcePixels, 0, w, 0, 0, w, h)
        val env = OrtEnvironment.getEnvironment()
        val stride = 192
        val total = ceil(w / stride.toDouble()).toInt() * ceil(h / stride.toDouble()).toInt()
        var done = 0
        try {
            RetouchModels.session(context, false).use { session ->
                for (top in 0 until h step stride) for (left in 0 until w step stride) {
                    currentCoroutineContext().ensureActive()
                    val values =
                        FloatArray(224 * 224) { i ->
                            val x = (left + i % 224 - 16).coerceIn(0, w - 1)
                            val y = (top + i / 224 - 16).coerceIn(0, h - 1)
                            val c = sourcePixels[y * w + x]
                            (.299f * (c shr 16 and 255) +
                                .587f * (c shr 8 and 255) +
                                .114f * (c and 255)) / 255f
                        }
                    native {
                        OnnxTensor.createTensor(
                                env,
                                FloatBuffer.wrap(values),
                                longArrayOf(1, 1, 224, 224),
                            )
                            .use { input ->
                                session.run(mapOf("input" to input)).use { out ->
                                    val luminance = (out[0] as OnnxTensor).floatBuffer
                                    val tw = min(stride, w - left) * 3
                                    val th = min(stride, h - top) * 3
                                    val pixels = IntArray(tw * th)
                                    result.getPixels(pixels, 0, tw, left * 3, top * 3, tw, th)
                                    for (y in 0 until th) for (x in 0 until tw) {
                                        val i = y * tw + x
                                        val c = pixels[i]
                                        val r = c shr 16 and 255
                                        val g = c shr 8 and 255
                                        val b = c and 255
                                        val oldY = .299f * r + .587f * g + .114f * b
                                        val delta =
                                            luminance[(y + 48) * 672 + x + 48].coerceIn(0f, 1f) *
                                                255 - oldY
                                        val altered =
                                            Color.rgb(
                                                (r + delta).roundToInt().coerceIn(0, 255),
                                                (g + delta).roundToInt().coerceIn(0, 255),
                                                (b + delta).roundToInt().coerceIn(0, 255),
                                            )
                                        val sx =
                                            ((left * 3 + x) * source.width / width).coerceIn(
                                                0,
                                                source.width - 1,
                                            )
                                        val sy =
                                            ((top * 3 + y) * source.height / height).coerceIn(
                                                0,
                                                source.height - 1,
                                            )
                                        pixels[i] =
                                            blend(
                                                c,
                                                altered,
                                                strength *
                                                    (selected[sy * source.width + sx] and 255) /
                                                    255f,
                                            )
                                    }
                                    result.setPixels(pixels, 0, tw, left * 3, top * 3, tw, th)
                                }
                            }
                    }
                    progress(5 + ++done * 90 / total)
                }
            }
            return result
        } catch (e: Throwable) {
            result.recycle()
            throw e
        } finally {
            if (low !== source) low.recycle()
        }
    }
}
