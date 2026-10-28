package cn.paperforge.app.pdf

import android.graphics.*
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.*

/** One bounded segment canvas, with source mapping recorded for every strip and separator. */
internal class LongImageWriter(
    private val zip: ZipOutputStream,
    val options: LongImageOptions,
    private val stages: JobFileStages? = null,
    private val renderSignature: String = "",
) : AutoCloseable {
    val height = options.segmentHeight.coerceAtMost(16_000_000 / options.width)
    private val bitmap = Bitmap.createBitmap(options.width, height, Bitmap.Config.ARGB_8888)
    private var used = 0
    private var segment = 1
    private var total = 0L
    private val pieces = JSONArray()
    private val segments = JSONArray()
    private var cacheKnown = false
    private var cachedSegment: File? = null
    private var renderedStrips = 0
    private var reusedStrips = 0

    private val stageKey
        get() = "long-image-segment-$segment"

    private val stageSignature
        get() = "long-image-r2|$renderSignature|$segment|$height"

    init {
        bitmap.eraseColor(options.background or 0xFF000000.toInt())
    }

    suspend fun append(
        source: Int,
        label: String,
        sourceHeight: Int,
        sourceStart: Int = 0,
        sourceX: Int = 0,
        sourceWidth: Int = options.width,
        render: (Bitmap, Rect, Int) -> Unit,
    ) {
        var offset = 0
        while (offset < sourceHeight) {
            currentCoroutineContext().ensureActive()
            if (used == height) flush()
            if (!cacheKnown) {
                cachedSegment = stages?.cached(stageKey, stageSignature)
                cacheKnown = true
            }
            val rows = minOf(sourceHeight - offset, height - used, 512)
            val target = Rect(0, used, options.width, used + rows)
            if (cachedSegment == null) {
                render(bitmap, target, offset)
                renderedStrips++
            } else reusedStrips++
            pieces.put(
                JSONObject()
                    .put("sourceIndex", source)
                    .put("sourceLabel", label)
                    .put("sourceRow", offset + sourceStart)
                    .put("sourceX", sourceX)
                    .put("sourceWidth", sourceWidth)
                    .put("rows", rows)
                    .put("segment", segment)
                    .put("segmentRow", used)
                    .put("logicalRow", total)
            )
            offset += rows
            used += rows
            total += rows
        }
    }

    suspend fun gap() {
        var remaining = options.gap
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            if (used == height) flush()
            val rows = minOf(remaining, height - used)
            pieces.put(
                JSONObject()
                    .put("separator", true)
                    .put("rows", rows)
                    .put("segment", segment)
                    .put("segmentRow", used)
                    .put("logicalRow", total)
            )
            used += rows
            remaining -= rows
            total += rows
        }
    }

    private suspend fun flush() {
        if (used == 0) return
        val name = "long-%03d.${options.format}".format(segment)
        fun encode(stream: OutputStream) {
            val content =
                if (used == height) bitmap
                else Bitmap.createBitmap(bitmap, 0, 0, options.width, used)
            try {
                check(
                    content.compress(
                        if (options.format == "jpg") Bitmap.CompressFormat.JPEG
                        else Bitmap.CompressFormat.PNG,
                        options.quality,
                        stream,
                    )
                ) {
                    "长图编码失败"
                }
            } finally {
                if (content !== bitmap) content.recycle()
            }
        }
        val ready =
            stages?.file(stageKey, stageSignature, options.format) { file ->
                check(cachedSegment == null) { "长图阶段文件已改变，请重试以重新生成" }
                file.outputStream().buffered().use { encode(it) }
            }
        zip.putNextEntry(ZipEntry(name))
        if (ready == null) encode(zip) else ready.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
        segments.put(JSONObject().put("file", name).put("width", options.width).put("height", used))
        bitmap.eraseColor(options.background or 0xFF000000.toInt())
        used = 0
        segment++
        cachedSegment = null
        cacheKnown = false
    }

    suspend fun finish(extra: JSONObject? = null): JSONObject {
        flush()
        val report =
            JSONObject()
                .put("format", 1)
                .put("options", options.json())
                .put("actualSegmentHeightLimit", height)
                .put("totalLogicalRows", total)
                .put("segments", segments)
                .put("sourceMapping", pieces)
                .put("renderedStrips", renderedStrips)
                .put("reusedStrips", reusedStrips)
        stages?.let { report.put("fileStages", it.entries) }
        extra?.keys()?.forEach { report.put(it, extra.get(it)) }
        zip.putNextEntry(ZipEntry("long-image-manifest.json"))
        zip.write(report.toString(2).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("说明.txt"))
        zip.write(
            (if (extra?.has("stitches") == true)
                    "按接缝设置去除重复顶行，水平偏移用新增留白保留边缘，分段连续。来源及接缝定位见 long-image-manifest.json。"
                else
                    "按选择顺序拼接，宽 ${options.width} 像素，间距 ${options.gap} 像素；实际分段高度上限 $height 像素，共 ${segments.length()} 段。分段连续，内容未裁弃。来源定位见 long-image-manifest.json。")
                .toByteArray(Charsets.UTF_8)
        )
        zip.closeEntry()
        return report
    }

    override fun close() {
        bitmap.recycle()
    }
}
