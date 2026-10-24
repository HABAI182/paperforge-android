package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.UserSettings
import cn.paperforge.app.imaging.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.opencv.android.Utils
import org.opencv.core.Mat

/** An unlabeled diagnostic corpus run. A returned rectangle is not an accuracy measurement. */
class RealPhotoProbe {
    @Test
    fun compareNativeHeadsOnPublicCameraPhotos() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "bench-real/original")
        val photos =
            root
                .listFiles()
                ?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
                .orEmpty()
                .sortedBy { it.name }
        check(photos.isNotEmpty()) { "Copy the explicit public/consented corpus first" }
        val run = InstrumentationRegistry.getArguments().getString("probeRun", "")
        val selected =
            InstrumentationRegistry.getArguments().getString("probeHeads", "").split(',').filter {
                it.isNotBlank()
            }
        val output =
            File(context.filesDir, "real-photo-probe" + if (run.isBlank()) "" else "-$run").apply {
                mkdirs()
            }
        val rows = JSONArray()
        val report =
            JSONObject()
                .put("dataset", "DocUNet public camera-photo subset")
                .put("ownerPhonePhotos", false)
                .put("groundTruthProvided", false)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("device", Build.MODEL)
                .put("decodeEdge", 1800)
                .put("rows", rows)
                .put("runtimeDetectorSetting", UserSettings.string("detector", "NEURAL"))
                .put(
                    "interpretation",
                    "Timing and returned candidates only; no accuracy or CER claim",
                )
        photos.forEach { file ->
            val start = SystemClock.elapsedRealtimeNanos()
            val bitmap = ScanProcessor.decode(file, 1800)
            val decodeMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            val mat = Mat()
            try {
                Utils.bitmapToMat(bitmap, mat)
                val scale = 600f / maxOf(bitmap.width, bitmap.height)
                val thumb =
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt(),
                        (bitmap.height * scale).toInt(),
                        true,
                    )
                try {
                    File(output, file.nameWithoutExtension + "-source.jpg").outputStream().use {
                        thumb.compress(Bitmap.CompressFormat.JPEG, 94, it)
                    }
                } finally {
                    if (thumb !== bitmap) thumb.recycle()
                }
                val heads = JSONObject()
                val probes =
                    listOf<Pair<String, () -> DetectionResult>>(
                        "mnn" to { NeuralEdgeDetector.detect(mat, false) },
                        "docquad" to { LearnedDocumentDetector.detect(bitmap) },
                        "standard" to { DocumentDetector.detect(mat, false) },
                        "current" to { LearnedDocumentDetector.detect(mat, false) },
                    )
                probes.forEach probe@{ (name, run) ->
                    if (selected.isNotEmpty() && name !in selected) return@probe
                    val at = SystemClock.elapsedRealtimeNanos()
                    val result = runCatching(run)
                    val record =
                        JSONObject()
                            .put("wallMs", (SystemClock.elapsedRealtimeNanos() - at) / 1_000_000.0)
                    result
                        .onSuccess { d ->
                            record
                                .put("method", d.method)
                                .put("score", d.confidence)
                                .put(
                                    "quad",
                                    d.quad?.let { q ->
                                        JSONArray().apply {
                                            q.points.forEach { put(JSONArray(listOf(it.x, it.y))) }
                                        }
                                    } ?: JSONObject.NULL,
                                )
                                .put("diagnostics", JSONObject(d.diagnostics))
                        }
                        .onFailure { record.put("error", it.message ?: it.javaClass.simpleName) }
                    heads.put(name, record)
                }
                rows.put(
                    JSONObject()
                        .put("file", file.name)
                        .put("width", bitmap.width)
                        .put("height", bitmap.height)
                        .put("decodeMs", decodeMs)
                        .put("heads", heads)
                )
                File(output, "results.json").writeText(report.toString(2))
            } finally {
                mat.release()
                bitmap.recycle()
            }
        }
    }
}
