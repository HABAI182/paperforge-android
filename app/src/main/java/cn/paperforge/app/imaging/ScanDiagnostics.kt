package cn.paperforge.app.imaging

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import cn.paperforge.app.data.PageResources
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.Quad
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Mat

/** Explicit, local export of a troublesome page. Never automatically uploads documents. */
object ScanDiagnostics {
    fun export(context: Context, page: PageRow, directory: File): File {
        val start = SystemClock.elapsedRealtime()
        val source = ScanProcessor.decode(File(page.original), 1800)
        val decodedAt = SystemClock.elapsedRealtime()
        val mat = Mat()
        val report = JSONObject()
        try {
            Utils.bitmapToMat(source, mat)
            val quality = CameraQualityAnalyzer.analyze(mat, Quad.decode(page.corners)).sample
            val detection = ScanProcessor.detectResult(source)
            val detectedAt = SystemClock.elapsedRealtime()
            val enhanced = ScanProcessor.render(page, 1800)
            try {
                val renderedAt = SystemClock.elapsedRealtime()
                report
                    .put("format", 1)
                    .put(
                        "version",
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName,
                    )
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("abis", org.json.JSONArray(Build.SUPPORTED_ABIS.toList()))
                    .put("pageId", page.id)
                    .put("corners", page.corners)
                    .put("filter", page.filter)
                    .put("strength", page.enhanceStrength)
                    .put("brightness", page.brightness)
                    .put("contrast", page.contrast)
                    .put("storedMethod", page.detectionMethod)
                    .put("storedConfidence", page.detectionConfidence)
                    .put("newMethod", detection.method)
                    .put("newCorners", detection.quad?.encode())
                    .put("newConfidence", detection.confidence)
                    .put("decodeMs", decodedAt - start)
                    .put("analysisAndDetectionMs", detectedAt - decodedAt)
                    .put("renderMs", renderedAt - detectedAt)
                    .put("renderEdge", 1800)
                    .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
                    .put(
                        "javaUsedBytes",
                        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                    )
                    .put(
                        "memoryClassMb",
                        context.getSystemService(ActivityManager::class.java).memoryClass,
                    )
                    .put("deviceModel", Build.MODEL)
                    .put("deviceManufacturer", Build.MANUFACTURER)
                    .put("qualityIsHeuristic", true)
                if (Build.VERSION.SDK_INT >= 29)
                    report.put(
                        "thermalStatus",
                        context.getSystemService(PowerManager::class.java).currentThermalStatus,
                    )
                quality?.let {
                    report
                        .put("sharpness", it.sharpness)
                        .put("meanBrightness", it.brightness)
                        .put("brightPixelFraction", it.glareFraction)
                }
                val target =
                    File(directory, "PaperForge-scan-case-${System.currentTimeMillis()}.zip")
                val temp = File(target.path + ".part")
                try {
                    ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                        zip.putNextEntry(
                            ZipEntry("original.${File(page.original).extension.ifBlank { "bin" }}")
                        )
                        val digest = MessageDigest.getInstance("SHA-256")
                        File(page.original).inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                digest.update(buffer, 0, n)
                                zip.write(buffer, 0, n)
                            }
                        }
                        zip.closeEntry()
                        val mapped =
                            PageResources.remap(page) { path ->
                                if (path == page.original)
                                    "original.${File(path).extension.ifBlank { "bin" }}"
                                else
                                    "resources/${PageResources.entries(page).first { it.path==path }.id}.${File(path).extension.ifBlank { "bin" }}"
                            }
                        PageResources.entries(page)
                            .distinctBy { it.path }
                            .filter { it.path != page.original }
                            .forEach { resource ->
                                val name =
                                    PageResources.entries(mapped)
                                        .first { it.id == resource.id }
                                        .path
                                zip.putNextEntry(ZipEntry(name))
                                File(resource.path).inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        report.put("processing", PageResources.metadata(mapped))
                        report.put(
                            "originalSha256",
                            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
                        )
                        zip.putNextEntry(ZipEntry("enhanced.jpg"))
                        enhanced.compress(Bitmap.CompressFormat.JPEG, 94, zip)
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("diagnostics.json"))
                        zip.write(report.toString(2).toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                    check(temp.renameTo(target)) { "诊断包保存失败" }
                    return target
                } finally {
                    temp.delete()
                }
            } finally {
                enhanced.recycle()
            }
        } finally {
            source.recycle()
            mat.release()
        }
    }
}
