package cn.paperforge.app.quality

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.imaging.ScanProcessor
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * #21 calibration: does the confidence actually track quality on one and the same photo?
 *
 * A clean synthetic page is progressively blurred; the geometry may move but the evidence must
 * fall. This is a synthetic/emulator probe, not a physical-camera claim. Results are written to
 * files/confidence-degradation/results.json for the acceptance page.
 */
@RunWith(AndroidJUnit4::class)
class ConfidenceDegradationTest {
    @Test
    fun blurringTheSamePageLowersConfidence() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val source = File(app.filesDir, "bench/dark-desk-0.jpg")
        check(source.isFile) { "Push synthetic-v1 to files/bench first" }
        val bitmap = ScanProcessor.decode(source, 1800)
        val original = Mat()
        val output = File(app.filesDir, "confidence-degradation").apply { mkdirs() }
        val rows = JSONArray()
        var clean = 0f
        try {
            Utils.bitmapToMat(bitmap, original)
            for (sigma in listOf(0.0, 2.0, 4.0, 8.0, 12.0)) {
                val blurred = Mat()
                val scaled = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                try {
                    if (sigma <= 0.0) original.copyTo(blurred)
                    else Imgproc.GaussianBlur(original, blurred, Size(0.0, 0.0), sigma)
                    Utils.matToBitmap(blurred, scaled)
                    val result = ScanProcessor.detectResult(scaled)
                    rows.put(
                        JSONObject()
                            .put("sigma", sigma)
                            .put("confidence", result.confidence.toDouble())
                            .put("method", result.method)
                            .put("hasQuad", result.quad != null)
                            .put("area", result.quad?.area?.toDouble() ?: 0.0)
                            .put(
                                "diagnostics",
                                JSONObject(
                                    result.diagnostics
                                        .mapValues { it.value.toDouble() }
                                        .toMap()
                                ),
                            )
                    )
                    if (sigma == 0.0) clean = result.confidence
                } finally {
                    scaled.recycle()
                    blurred.release()
                }
            }
            val report =
                JSONObject()
                    .put("dataset", "synthetic-v1")
                    .put("file", source.name)
                    .put("synthetic", true)
                    .put("physicalCamera", false)
                    .put("device", android.os.Build.MODEL)
                    .put("image", JSONObject().put("width", bitmap.width).put("height", bitmap.height))
                    .put("rows", rows)
            File(output, "results.json").writeText(report.toString(2))
            val worst = rows.getJSONObject(rows.length() - 1).getDouble("confidence").toFloat()
            assertTrue("clean confidence must be finite: $clean", clean.isFinite())
            assertTrue("clean confidence in range: $clean", clean in 0f..0.999f)
            assertTrue("clean page must clear auto-capture: $clean", clean >= .6f)
            assertTrue("heavy blur ($worst) must score below the clean page ($clean)", worst < clean)
        } finally {
            original.release()
            bitmap.recycle()
        }
    }
}
