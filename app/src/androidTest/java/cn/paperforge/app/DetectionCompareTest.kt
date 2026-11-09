package cn.paperforge.app

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.imaging.DetectionResult
import cn.paperforge.app.imaging.LearnedDocumentDetector
import cn.paperforge.app.imaging.ScanConfig
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat

/** Writes the default detector's still and preview quads for every photo in files/detect-samples. */
@RunWith(AndroidJUnit4::class)
class DetectionCompareTest {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private fun timed(block: () -> DetectionResult): JSONObject {
        val started = System.nanoTime()
        val result = block()
        val quad = result.quad?.points?.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }
        return JSONObject()
            .put("method", result.method)
            .put("confidence", result.confidence.toDouble())
            .put("wallMs", (System.nanoTime() - started) / 1e6)
            .put("quad", quad?.let { JSONArray(it) } ?: JSONObject.NULL)
    }

    @Test
    fun recordDetectedQuads() {
        val samples = File(context.filesDir, "detect-samples").listFiles().orEmpty().sorted()
        assertTrue("no photos in files/detect-samples", samples.isNotEmpty())
        val output = File(context.filesDir, "detect-current.json")
        val rows = JSONArray()
        for (file in samples) {
            val bitmap = BitmapFactory.decodeFile(file.path) ?: continue
            val rgba = Mat()
            try {
                Utils.bitmapToMat(bitmap, rgba)
                val row =
                    timed { LearnedDocumentDetector.detect(rgba, false) }
                        .put("file", file.name)
                        .put("preview", timed { LearnedDocumentDetector.detect(rgba, true) })
                for ((label, overrides) in
                    listOf(
                        "withRefine" to JSONObject().put("pageNetRefine", 1),
                        "noRecover" to JSONObject().put("pageNetRecover", 0),
                    )) {
                    val snapshot = ScanConfig.replaceForBenchmark(overrides)
                    try {
                        row.put(label, timed { LearnedDocumentDetector.detect(rgba, false) })
                    } finally {
                        ScanConfig.restoreAfterBenchmark(snapshot)
                    }
                }
                rows.put(row)
                output.writeText(rows.toString(1))
            } finally {
                rgba.release()
                bitmap.recycle()
            }
        }
    }
}
