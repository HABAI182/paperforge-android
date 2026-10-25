package cn.paperforge.app.pdf

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.zip.ZipOutputStream
import kotlin.math.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Region decode only the rows being written, including all eight EXIF orientations. */
object ImageLongImages {
    @Suppress("DEPRECATION")
    suspend fun convert(
        inputs: List<File>,
        names: List<String>,
        output: File,
        options: LongImageOptions = LongImageOptions(),
        progress: suspend (Int) -> Unit = {},
    ): JSONObject {
        require(inputs.isNotEmpty()) { "请选择要拼接的图片" }
        options.validate()
        val part = File(output.path + ".part")
        try {
            var report: JSONObject? = null
            ZipOutputStream(part.outputStream().buffered()).use { zip ->
                LongImageWriter(zip, options).use { writer ->
                    inputs.forEachIndexed { index, file ->
                        currentCoroutineContext().ensureActive()
                        val decoder =
                            BitmapRegionDecoder.newInstance(file.path, false)
                                ?: error("无法读取${names.getOrNull(index) ?: file.name}")
                        try {
                            val w = decoder.width.toFloat()
                            val h = decoder.height.toFloat()
                            val orientation =
                                runCatching {
                                        ExifInterface(file)
                                            .getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                                    }
                                    .getOrDefault(1)
                            val rotated = orientation in 5..8
                            val scale = options.width / (if (rotated) h else w)
                            val displayWidth = if (rotated) decoder.height else decoder.width
                            val displayHeight = if (rotated) decoder.width else decoder.height
                            val height =
                                (displayHeight.toLong() * options.width + displayWidth - 1) /
                                    displayWidth
                            require(height in 1..Int.MAX_VALUE.toLong()) { "图片过长，请减小输出宽度" }
                            val values =
                                when (orientation) {
                                    2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
                                    3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
                                    4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
                                    5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                                    6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
                                    7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
                                    8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
                                    else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                                }
                            for (i in 0..5) values[i] *= scale
                            val transform = Matrix().apply { setValues(values) }
                            val inverse = Matrix()
                            check(transform.invert(inverse))
                            var sample = 1
                            while (sample * 2 * scale <= 1f) sample *= 2
                            writer.append(
                                index + 1,
                                names.getOrNull(index) ?: file.name,
                                height.toInt(),
                            ) { bitmap, target, offset ->
                                val source =
                                    RectF(
                                        0f,
                                        offset.toFloat(),
                                        options.width.toFloat(),
                                        (offset + target.height()).toFloat(),
                                    )
                                inverse.mapRect(source)
                                val roi =
                                    Rect(
                                        (floor(source.left) - 1).toInt().coerceAtLeast(0),
                                        (floor(source.top) - 1).toInt().coerceAtLeast(0),
                                        (ceil(source.right) + 1).toInt().coerceAtMost(w.toInt()),
                                        (ceil(source.bottom) + 1).toInt().coerceAtMost(h.toInt()),
                                    )
                                val region =
                                    decoder.decodeRegion(
                                        roi,
                                        BitmapFactory.Options().apply {
                                            inSampleSize = sample
                                            inPreferredConfig = Bitmap.Config.ARGB_8888
                                        },
                                    ) ?: error("无法读取图片分块")
                                try {
                                    val local =
                                        Matrix().apply {
                                            setScale(
                                                roi.width().toFloat() / region.width,
                                                roi.height().toFloat() / region.height,
                                            )
                                            postTranslate(roi.left.toFloat(), roi.top.toFloat())
                                        }
                                    val draw =
                                        Matrix().apply {
                                            setConcat(transform, local)
                                            postTranslate(0f, (target.top - offset).toFloat())
                                        }
                                    Canvas(bitmap).apply {
                                        save()
                                        clipRect(target)
                                        drawBitmap(
                                            region,
                                            draw,
                                            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                                        )
                                        restore()
                                    }
                                } finally {
                                    region.recycle()
                                }
                            }
                        } finally {
                            decoder.recycle()
                        }
                        if (index < inputs.lastIndex) writer.gap()
                        progress((index + 1) * 100 / inputs.size)
                    }
                    report = writer.finish()
                }
            }
            currentCoroutineContext().ensureActive()
            check(part.renameTo(output)) { "无法发布拼接长图" }
            return checkNotNull(report)
        } finally {
            part.delete()
        }
    }
}
