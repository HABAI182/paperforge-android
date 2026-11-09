package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.PaperField
import cn.paperforge.app.imaging.PaperGhost
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.imaging.ScanProcessor
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.imgproc.Imgproc

/**
 * Ablation + field-timing repro for the 20018 "shadow band + show-through" acceptance, on the
 * owner's real page (`work/repro-edge/original.jpg`), pushed to
 * `files/edge-ink/original.jpg`.
 *
 * This walks the same `ScanProcessor.render` path the PDF export worker uses, with the quad the app
 * recorded on import, and toggles exactly one new switch at a time through
 * `ScanConfig.replaceForBenchmark` (the same mechanism the 20016 edge-ink acceptance used). It does
 * not touch any existing assertion; it is new coverage for the two new switches.
 */
@RunWith(AndroidJUnit4::class)
class ShadowGhostReproTest {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private val recordedQuad =
        "0.1262081,0.09509941;0.9059508,0.12445831;0.9395825,0.89406466;0.06452234,0.8832647"

    private fun image(): File {
        val target = File(context.filesDir, "edge-ink/original.jpg")
        assertTrue("owner photo missing at ${target.path}", target.isFile)
        return target
    }

    private fun output(): File = File(context.filesDir, "edge-ink/shadow").apply { mkdirs() }

    private fun page() =
        PageRow(
            id = "shadow-repro-page",
            documentId = "shadow-repro-doc",
            position = 0,
            original = image().path,
            corners = recordedQuad,
            filter = "WORKBOOK",
            enhanceStrength = 1f,
        )

    private fun render(label: String, overrides: JSONObject): JSONObject {
        val dir = File(output(), label).apply { mkdirs() }
        val snapshot = ScanConfig.replaceForBenchmark(overrides)
        val stats = JSONObject()
        try {
            val bitmap = ScanProcessor.render(page(), 1800)
            try {
                File(dir, "final.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                stats.put("width", bitmap.width).put("height", bitmap.height)
                stats.put("ghost", PaperGhost.diagnostics() ?: JSONObject.NULL)
            } finally {
                bitmap.recycle()
            }
        } finally {
            ScanConfig.restoreAfterBenchmark(snapshot)
        }
        File(dir, "config.json").writeText(
            JSONObject()
                .put("label", label)
                .put("overrides", overrides)
                .put("stats", stats)
                .toString(2)
        )
        return stats
    }

    @Test
    fun ablationOnTheOwnerPage() {
        // Warm-up so class loading / OpenCV init is not charged to any case.
        ScanProcessor.render(page(), 1800).recycle()
        val rows = JSONArray()
        val cases =
            listOf(
                "after" to JSONObject(),
                "field-legacy" to JSONObject().put("paperFieldSmooth", 0),
                "ghost-off" to JSONObject().put("paperGhostGuard", 0),
                "both-off" to
                    JSONObject().put("paperFieldSmooth", 0).put("paperGhostGuard", 0),
            )
        for ((label, overrides) in cases) {
            val stats = render(label, overrides)
            rows.put(JSONObject().put("label", label).put("overrides", overrides).put("stats", stats))
        }
        // Paper-field cost on the same page: the export-sized page is rendered once, then the field
        // estimate (480 px working edge in this build) is timed twice per run on its grey plane.
        val bitmap = ScanProcessor.render(page(), 1800)
        try {
            val mat = org.opencv.core.Mat()
            Utils.bitmapToMat(bitmap, mat)
            val gray = org.opencv.core.Mat()
            try {
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
                val samples = JSONArray()
                repeat(5) {
                    val field = org.opencv.core.Mat()
                    val started = System.nanoTime()
                    PaperField.estimate(gray, field)
                    samples.put((System.nanoTime() - started) / 1_000_000.0)
                    field.release()
                }
                val summary = JSONObject()
                val field = org.opencv.core.Mat()
                try {
                    PaperField.estimate(gray, field)
                    summary.put("version", PaperField.VERSION)
                    summary.put("edge", ScanConfig.int("paperFieldSmoothEdge", 480))
                    summary.put("p10", PaperField.summary(field).optInt("p10"))
                    summary.put("p50", PaperField.summary(field).optInt("p50"))
                    summary.put("p90", PaperField.summary(field).optInt("p90"))
                    summary.put("size", JSONObject().put("w", gray.cols()).put("h", gray.rows()))
                } finally {
                    field.release()
                }
                rows.put(
                    JSONObject()
                        .put("label", "paperFieldEstimate")
                        .put("ms", samples)
                        .put("summary", summary)
                )
            } finally {
                gray.release()
                mat.release()
            }
        } finally {
            bitmap.recycle()
        }
        File(output(), "runs.json").writeText(
            JSONObject()
                .put("fingerprint", ScanConfig.fingerprint())
                .put("runs", rows)
                .toString(2)
        )
        assertTrue(
            "paper field summary missing",
            File(output(), "runs.json").isFile,
        )
    }
}
