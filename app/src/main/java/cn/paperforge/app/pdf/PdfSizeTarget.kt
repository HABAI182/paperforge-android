package cn.paperforge.app.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.*

/** Bounded quality search over native image-only rewrites; no implicit page rasterization. */
object PdfSizeTarget {
    suspend fun compress(
        input: File,
        output: File,
        targetBytes: Long,
        progress: suspend (Int) -> Unit = {},
    ): JSONObject {
        require(targetBytes > 0 && input.canonicalFile != output.canonicalFile) { "请选择目标体积并保存为新文件" }
        val directory =
            File(output.parentFile, "size-search-${UUID.randomUUID()}").apply { mkdirs() }
        val part = File(output.path + ".part")
        val attempts = JSONArray()
        var best = input
        var bestQuality = -1
        var selected = input.takeIf { it.length() <= targetBytes }
        var selectedQuality = -1
        var fixedReason: String? = null
        suspend fun attempt(quality: Int): Pair<File, JSONObject> {
            currentCoroutineContext().ensureActive()
            val file = File(directory, "quality-$quality.pdf")
            val index = attempts.length()
            val detail =
                PdfImageOptimizer.compress(input, file, quality) { percent ->
                    progress(((index * 100 + percent) * 95 / 700).coerceAtMost(95))
                }
            attempts.put(
                JSONObject()
                    .put("quality", quality)
                    .put("bytes", file.length())
                    .put("fits", file.length() <= targetBytes)
                    .put("originalCopied", detail.optBoolean("originalCopied"))
            )
            if (file.length() < best.length()) {
                best = file
                bestQuality = quality
            }
            return file to detail
        }
        try {
            if (selected == null) {
                var previous = 96
                for (quality in listOf(90, 70, 50, 30, 10)) {
                    val (file, detail) = attempt(quality)
                    if (file.length() <= targetBytes) {
                        selected = file
                        selectedQuality = quality
                        var lower = quality
                        var upper = previous
                        repeat(2) {
                            if (upper - lower > 1) {
                                val middle = (upper + lower + 1) / 2
                                val (probe, _) = attempt(middle)
                                if (probe.length() <= targetBytes) {
                                    selected = probe
                                    selectedQuality = middle
                                    lower = middle
                                } else upper = middle - 1
                            }
                        }
                        break
                    }
                    val images = detail.optJSONArray("images") ?: JSONArray()
                    val allFixed =
                        (0 until images.length()).all { images.getJSONObject(it).has("skipped") }
                    if (
                        detail.optString("reason").startsWith("signed PDF") ||
                            images.length() == 0 ||
                            allFixed
                    ) {
                        fixedReason =
                            if (detail.optString("reason").startsWith("signed PDF")) "含签名记录，按字节保留原件"
                            else "当前没有可进一步重编码的图像资源"
                        break
                    }
                    previous = quality
                }
            }
            val chosen = selected ?: best
            val quality = if (selected != null) selectedQuality else bestQuality
            chosen.inputStream().use { source ->
                java.io.FileOutputStream(part).use { target ->
                    source.copyTo(target)
                    target.fd.sync()
                }
            }
            PDDocument.load(part, MemoryUsageSetting.setupTempFileOnly()).use {
                require(it.numberOfPages > 0)
            }
            currentCoroutineContext().ensureActive()
            check(part.renameTo(output)) { "无法发布压缩文件" }
            val achieved = output.length() <= targetBytes
            val preserved = chosen == input || output.length() == input.length()
            val reason =
                when {
                    achieved && preserved -> "原件已符合目标"
                    achieved -> "已达到目标体积"
                    fixedReason != null -> fixedReason
                    preserved -> "当前模式下无法继续减小，已保留原件副本"
                    else -> "已保留本轮最小结果，仍高于目标"
                }
            progress(100)
            return JSONObject()
                .put("inputBytes", input.length())
                .put("outputBytes", output.length())
                .put("targetBytes", targetBytes)
                .put("targetAchieved", achieved)
                .put("selectedQuality", if (quality >= 0) quality else JSONObject.NULL)
                .put("originalCopied", preserved)
                .put("reason", reason)
                .put("attempts", attempts)
                .put("maxAttempts", 7)
                .put(
                    "searchPolicy",
                    "highest tested quality fitting target, otherwise smallest tested output including original",
                )
                .put("flattened", false)
        } finally {
            part.delete()
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
