package cn.paperforge.app.pdf

import java.io.File
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

object ImageLongImages {
    suspend fun convert(
        inputs: List<File>,
        names: List<String>,
        output: File,
        options: LongImageOptions = LongImageOptions(),
        seams: List<StitchSeam> = emptyList(),
        progress: suspend (Int) -> Unit = {},
    ): JSONObject {
        require(inputs.isNotEmpty()) { "请选择要拼接的图片" }
        options.validate()
        val shifts = mutableListOf(0)
        for (i in 1 until inputs.size) shifts +=
            (shifts.last() + ((seams.getOrNull(i - 1)?.shift ?: 0f) * options.width).roundToInt())
        val left = shifts.min()
        val canvasWidth = options.width + shifts.max() - left
        require(canvasWidth in 128..8192) { "水平偏移过大，请减小偏移或分组拼接" }
        val part = File(output.path + ".part")
        try {
            var report: JSONObject? = null
            val joins = JSONArray()
            ZipOutputStream(part.outputStream().buffered()).use { zip ->
                LongImageWriter(zip, options.copy(width = canvasWidth)).use { writer ->
                    inputs.forEachIndexed { index, file ->
                        currentCoroutineContext().ensureActive()
                        OrientedImageRegion(file, options.width).use { source ->
                            val seam = seams.getOrNull(index - 1) ?: StitchSeam()
                            val skipped =
                                (source.height * seam.overlap)
                                    .roundToInt()
                                    .coerceIn(0, source.height - 1)
                            val x = shifts[index] - left
                            if (index > 0)
                                joins.put(
                                    seam
                                        .json()
                                        .put("sourceIndex", index + 1)
                                        .put("skippedTopRows", skipped)
                                        .put("fullSourceHeight", source.height)
                                        .put("outputX", x)
                                )
                            writer.append(
                                index + 1,
                                names.getOrNull(index) ?: file.name,
                                source.height - skipped,
                                skipped,
                                x,
                                options.width,
                            ) { bitmap, target, offset ->
                                source.draw(bitmap, target, offset + skipped, x)
                            }
                        }
                        if (
                            index < inputs.lastIndex &&
                                (seams.getOrNull(index)?.overlap ?: 0f) == 0f
                        )
                            writer.gap()
                        progress((index + 1) * 100 / inputs.size)
                    }
                    report =
                        writer.finish(
                            if (seams.isEmpty()) null
                            else
                                JSONObject()
                                    .put("stitches", joins)
                                    .put("normalizedSourceWidth", options.width)
                                    .put(
                                        "sourceCoordinateSpace",
                                        "upright rows normalized to source width",
                                    )
                        )
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
