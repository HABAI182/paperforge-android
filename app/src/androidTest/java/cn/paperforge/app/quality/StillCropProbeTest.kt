package cn.paperforge.app.quality

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings
import cn.paperforge.app.imaging.CameraQualityAnalyzer
import cn.paperforge.app.imaging.CaptureMetadata
import cn.paperforge.app.imaging.DocumentDetector
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.CaptureReference
import cn.paperforge.app.model.Quad
import cn.paperforge.app.model.StillCrop
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.opencv.android.Utils
import org.opencv.core.Mat

/**
 * #30 two-stage probe on the public DocUNet camera-photo subset.
 *
 * Simulates the shipped capture path with two different resolutions:
 *  - viewfinder: 720 px decode, `preview = true` (no full-resolution refine),
 *  - saved still: 1800 px decode, `preview = false` (same detector/refine as a capture).
 *
 * It records both quads, their normalized max corner shift and the adoption decision under several
 * candidate thresholds, then calls the real `ScanProcessor.detectFile` with a matched
 * [CaptureReference] to prove the product wiring runs (method + diagnostics + notes).
 *
 * The corpus has no ground-truth corners, so this is a consistency measurement, not an accuracy
 * claim. Edge fit is measured offline (Otsu profile crossing) from the exported quads.
 */
class StillCropProbeTest {
    private val previewEdge = 720
    private val stillEdge = 1800
    private val thresholds = listOf(.01f, .015f, .02f, .03f)

    @Test
    fun previewAndStillDisagreeOnRealCameraPhotos() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val override = arguments.getString("probeDetector", "").orEmpty().takeIf { it.isNotBlank() }
        val detectorSetting = UserSettings.string("detector", "HYBRID")
        val corpus = File(context.filesDir, "docunet")
        val photos =
            corpus
                .listFiles()
                ?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
                .orEmpty()
                .sortedBy { naturalKey(it) }
        check(photos.isNotEmpty()) { "Push the DocUNet subset to files/docunet first" }
        val output = File(context.filesDir, "still-crop-probe").apply { mkdirs() }
        val rows = JSONArray()
        val report =
            JSONObject()
                .put("dataset", "DocUNet public camera-photo subset")
                .put("groundTruthProvided", false)
                .put(
                    "interpretation",
                    "preview(720px) vs still(1800px) agreement and adoption decisions; no accuracy claim",
                )
                .put("sdk", Build.VERSION.SDK_INT)
                .put("device", Build.MODEL)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("detectorSetting", detectorSetting)
                .put("detectorOverride", override ?: JSONObject.NULL)
                .put("previewEdge", previewEdge)
                .put("stillEdge", stillEdge)
                .put("candidateThresholds", JSONArray(thresholds.map { it.toDouble() }))
                .put("images", photos.size)
                .put("rows", rows)
        photos.forEach { file ->
            val row = JSONObject().put("file", file.name)
            val previewBitmap =
                runCatching { ScanProcessor.decode(file, previewEdge) }
                    .getOrElse {
                        row.put("error", "preview-decode: " + (it.message ?: it.javaClass.simpleName))
                        rows.put(row)
                        writeReport(output, report)
                        return@forEach
                    }
            val previewResult =
                runCatching {
                        val started = SystemClock.elapsedRealtimeNanos()
                        val result =
                            ScanProcessor.detectResult(
                                previewBitmap,
                                preview = true,
                                detectorOverride = override,
                            )
                        result to (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    }
                    .getOrElse { null }
            val preview = previewResult?.first
            val previewMs = previewResult?.second ?: 0.0
            // Reference signatures must come from the same analyzer the camera path uses.
            val previewMat = Mat()
            val quality =
                try {
                    Utils.bitmapToMat(previewBitmap, previewMat)
                    CameraQualityAnalyzer.analyze(previewMat, preview?.quad)
                } finally {
                    previewMat.release()
                }
            val stillBitmap =
                runCatching { ScanProcessor.decode(file, stillEdge) }
                    .getOrElse {
                        row.put("error", "still-decode: " + (it.message ?: it.javaClass.simpleName))
                        previewBitmap.recycle()
                        rows.put(row)
                        writeReport(output, report)
                        return@forEach
                    }
            row.put("width", stillBitmap.width).put("height", stillBitmap.height)
            row.put("previewWidth", previewBitmap.width).put("previewHeight", previewBitmap.height)
            val stillResult =
                runCatching {
                        val started = SystemClock.elapsedRealtimeNanos()
                        val result =
                            ScanProcessor.detectResult(
                                stillBitmap,
                                preview = false,
                                detectorOverride = override,
                            )
                        result to (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    }
                    .getOrElse { null }
            val still = stillResult?.first
            val stillMs = stillResult?.second ?: 0.0
            row.put(
                "preview",
                detectionJson(preview, previewMs),
            )
            row.put(
                "still",
                detectionJson(still, stillMs),
            )
            val previewQuad = preview?.quad
            val stillQuad = still?.quad
            // The production fallback is not the raw preview quad but the full-resolution refine of
            // it, so export that candidate too: the honest comparison is still vs refined preview.
            val refinedQuad =
                previewQuad?.let { hint ->
                    val mat = Mat()
                    try {
                        Utils.bitmapToMat(stillBitmap, mat)
                        DocumentDetector.refine(mat, hint)
                    } catch (e: Exception) {
                        null
                    } finally {
                        mat.release()
                    }
                }
            row.put("refinedPreviewQuad", quadJson(refinedQuad))
            val shift =
                if (previewQuad != null && stillQuad != null)
                    StillCrop.maxCornerShift(previewQuad, stillQuad)
                else -1f
            row.put("maxCornerShift", shift.toDouble())
            row.put(
                "refinedShift",
                if (previewQuad != null && refinedQuad != null)
                    StillCrop.maxCornerShift(previewQuad, refinedQuad).toDouble()
                else -1.0,
            )
            row.put(
                "stillVsRefinedShift",
                if (refinedQuad != null && stillQuad != null)
                    StillCrop.maxCornerShift(refinedQuad, stillQuad).toDouble()
                else -1.0,
            )
            val decisions = JSONObject()
            if (previewQuad != null) {
                thresholds.forEach { threshold ->
                    val decision =
                        StillCrop.decide(
                            previewQuad,
                            preview?.confidence ?: 0f,
                            stillQuad,
                            still?.confidence ?: 0f,
                            null,
                            threshold,
                        )
                    decisions.put(
                        threshold.toString(),
                        JSONObject()
                            .put("adopted", decision.adoptedStill)
                            .put("reason", decision.reason)
                            .put("quad", quadJson(decision.quad)),
                    )
                }
            }
            row.put("decisions", decisions)
            val history =
                arguments.getString("probeHistogram", "false") == "true"
            if (history && previewQuad != null && stillQuad != null) {
                // Localized where the two resolutions disagree most; useful when inspecting a photo.
                row.put(
                    "shiftByCorner",
                    JSONArray(
                        previewQuad.points.zip(stillQuad.points).map { (a, b) ->
                            kotlin.math.hypot(a.x - b.x, a.y - b.y).toDouble()
                        }
                    ),
                )
            }
            stillBitmap.recycle()
            // Product wiring: the real capture path decodes its own 4000 px copy and must record the
            // same decision fields. Run it for every photo so the probe proves the shipped route.
            if (previewQuad != null) {
                val now = SystemClock.elapsedRealtime()
                val reference =
                    CaptureReference(
                        previewQuad,
                        now,
                        now,
                        previewBitmap.width,
                        previewBitmap.height,
                        "${previewBitmap.width}x${previewBitmap.height}",
                        SystemClock.elapsedRealtimeNanos(),
                        preview?.confidence ?: 0f,
                        quality.sceneSignature,
                        quality.signature,
                        0f,
                    )
                val productStarted = SystemClock.elapsedRealtimeNanos()
                val product =
                    runCatching {
                        ScanProcessor.detectFile(
                            file,
                            previewQuad,
                            reference,
                            override,
                            lockReference = true,
                        )
                    }
                row.put(
                    "product",
                    product
                        .map { result ->
                            val metadata =
                                JSONObject(CaptureMetadata.encode(reference, result))
                                    .getJSONObject("capture")
                            JSONObject()
                                .put("method", result.method)
                                .put("confidence", result.confidence.toDouble())
                                .put("quad", quadJson(result.quad))
                                .put("notes", JSONObject(result.notes))
                                .put(
                                    "diagnostics",
                                    JSONObject(
                                        result.diagnostics
                                            .filterKeys {
                                                it.startsWith("still") ||
                                                    it.startsWith("reference")
                                            }
                                            .mapValues { it.value.toDouble() }
                                    ),
                                )
                                .put("metadataNotes", metadata.getJSONObject("notes"))
                                .put("metadataDecision", metadata.optString("decision"))
                                .put("referenceMatched", !result.method.contains("fresh-after-"))
                        }
                        .getOrElse {
                            JSONObject()
                                .put("error", it.message ?: it.javaClass.simpleName)
                        },
                )
                row.put(
                    "productMs",
                    (SystemClock.elapsedRealtimeNanos() - productStarted) / 1_000_000.0,
                )
            }
            previewBitmap.recycle()
            rows.put(row)
            writeReport(output, report)
        }
        report.put("finishedAtMillis", System.currentTimeMillis())
        writeReport(output, report)
        check(rows.length() > 0) { "No DocUNet photo was processed" }
        // Every photo must have gone through the real capture path and recorded the decision.
        var productRows = 0
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val product = row.optJSONObject("product") ?: continue
            check(product.optString("error").isBlank()) {
                "${row.getString("file")}: ${product.optString("error")}"
            }
            val diagnostics = product.getJSONObject("diagnostics")
            for (key in
                listOf(
                    "stillMaxCornerShift",
                    "stillShiftThreshold",
                    "stillAdopted",
                    "stillConfidence",
                    "stillReasonCode",
                )) {
                check(diagnostics.has(key)) { "${row.getString("file")} missing $key" }
            }
            check(product.getJSONObject("notes").optString("stillReason").isNotBlank()) {
                "${row.getString("file")} missing stillReason"
            }
            check(
                product.getJSONObject("metadataNotes").optString("stillReason") ==
                    product.getJSONObject("notes").optString("stillReason")
            ) {
                "${row.getString("file")} metadata lost the still decision"
            }
            check(product.optString("metadataDecision") == product.optString("method")) {
                "${row.getString("file")} metadata decision differs from detection method"
            }
            check(product.optBoolean("referenceMatched")) {
                "${row.getString("file")} dropped its capture reference: " +
                    product.optString("method")
            }
            checkNotNull(product.optJSONArray("quad")) {
                "${row.getString("file")} returned no crop quad"
            }
            productRows++
        }
        check(productRows == photos.size) {
            "product path ran for $productRows of ${photos.size} photos"
        }
        report.put("productRows", productRows)
        writeReport(output, report)
    }

    private fun detectionJson(result: cn.paperforge.app.imaging.DetectionResult?, ms: Double) =
        JSONObject()
            .put("hasQuad", result?.quad != null)
            .put("confidence", (result?.confidence ?: 0f).toDouble())
            .put("method", result?.method ?: "error")
            .put("area", (result?.quad?.area ?: 0f).toDouble())
            .put("quad", quadJson(result?.quad))
            .put("detectMs", ms)
            .put("detectorElapsedMs", (result?.elapsedMs ?: 0f).toDouble())

    private fun quadJson(quad: Quad?): Any {
        if (quad == null) return JSONObject.NULL
        return JSONArray().apply {
            quad.points.forEach { point ->
                put(
                    JSONArray().apply {
                        put(point.x.toDouble())
                        put(point.y.toDouble())
                    }
                )
            }
        }
    }

    private fun writeReport(output: File, report: JSONObject) {
        File(output, "results.json").writeText(report.toString(2))
    }

    private fun naturalKey(file: File): String =
        file.nameWithoutExtension.split('_').joinToString("/") { token ->
            token.toIntOrNull()?.let { "%04d".format(it) } ?: token
        }
}
