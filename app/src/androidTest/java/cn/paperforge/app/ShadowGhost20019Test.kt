package cn.paperforge.app

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.EdgeInkDiagnostics
import cn.paperforge.app.imaging.PaperGhost
import cn.paperforge.app.imaging.PaperTone
import cn.paperforge.app.imaging.PaperWhiteFinal
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.imaging.WorkbookContent
import cn.paperforge.app.imaging.WorkbookPaperBoundary
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 20019 round: paper-tone / show-through measurements on the owner's real page.
 *
 * Renders through the same [ScanProcessor.render] the PDF export worker uses, with the quad the app
 * recorded on import, at 1375 px on the long edge - the size the delivered 150 dpi page actually is
 * (1063x1375). Every case writes a lossless PNG plus the pipeline's own stage dumps
 * ([EdgeInkDiagnostics]) so an offline script can measure the band that
 * [WorkbookPaperBoundary] repainted instead of guessing a geometric band.
 *
 * Each case flips exactly one benchmark switch through [ScanConfig.replaceForBenchmark]; no existing
 * assertion is touched and production defaults are restored after every render.
 */
@RunWith(AndroidJUnit4::class)
class ShadowGhost20019Test {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private val recordedQuad =
        "0.1262081,0.09509941;0.9059508,0.12445831;0.9395825,0.89406466;0.06452234,0.8832647"

    /** Delivery size: the 150 dpi export embeds a 1063x1375 page. */
    private val deliveryEdge = 1375

    private fun image(): File {
        val target = File(context.filesDir, "edge-ink/original.jpg")
        assertTrue("owner photo missing at ${target.path}", target.isFile)
        return target
    }

    private fun root(): File = File(context.filesDir, "edge-ink/r3").apply { mkdirs() }

    private fun page() =
        PageRow(
            id = "shadow20019-page",
            documentId = "shadow20019-doc",
            position = 0,
            original = image().path,
            corners = recordedQuad,
            filter = "WORKBOOK",
            enhanceStrength = 1f,
        )

    private fun diagnostics(): JSONObject =
        JSONObject()
            .put("ghost", PaperGhost.diagnostics() ?: JSONObject.NULL)
            .put("tone", PaperTone.diagnostics() ?: JSONObject.NULL)
            .put("white", PaperWhiteFinal.diagnostics() ?: JSONObject.NULL)
            .put("boundary", WorkbookPaperBoundary.diagnostics() ?: JSONObject.NULL)
            .put("content", WorkbookContent.lastDecision() ?: JSONObject.NULL)

    private fun renderCase(label: String, overrides: JSONObject): JSONObject {
        val dir = File(root(), label).apply { mkdirs() }
        val snapshot = ScanConfig.replaceForBenchmark(JSONObject(overrides.toString()).put("edgeInkDump", 1))
        val stats = JSONObject()
        try {
            EdgeInkDiagnostics.directory = dir
            val bitmap = ScanProcessor.render(page(), deliveryEdge)
            try {
                File(dir, "final.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                stats.put("width", bitmap.width).put("height", bitmap.height)
            } finally {
                bitmap.recycle()
            }
            stats.put("diagnostics", diagnostics())
        } finally {
            EdgeInkDiagnostics.directory = null
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

    private fun timeRender(label: String, overrides: JSONObject, runs: Int = 3): JSONObject {
        val snapshot = ScanConfig.replaceForBenchmark(overrides)
        val samples = JSONArray()
        try {
            ScanProcessor.render(page(), deliveryEdge).recycle()
            repeat(runs) {
                val started = System.nanoTime()
                ScanProcessor.render(page(), deliveryEdge).recycle()
                samples.put((System.nanoTime() - started) / 1_000_000.0)
            }
        } finally {
            ScanConfig.restoreAfterBenchmark(snapshot)
        }
        val values = (0 until samples.length()).map { samples.getDouble(it) }.sorted()
        return JSONObject()
            .put("label", label)
            .put("overrides", overrides)
            .put("ms", samples)
            .put("medianMs", values[values.size / 2])
    }

    @Test
    fun paperToneAndGhostAblationOnTheOwnerPage() {
        // Warm-up so class loading and OpenCV init are not charged to any case.
        ScanProcessor.render(page(), deliveryEdge).recycle()
        val cases =
            listOf(
                "deliver" to JSONObject(),
                "bandoff" to JSONObject().put("paperToneSameGain", 0),
                "sweepoff" to JSONObject().put("paperGhostSweep", 0),
                "bothoff" to
                    JSONObject().put("paperToneSameGain", 0).put("paperGhostSweep", 0),
                "ghostoff" to JSONObject().put("paperGhostGuard", 0),
                // Sensitivity probe: the default reach is 4% of the short side. 15% is far past
                // anything defensible; it is here to show whether the sweep *can* reach this page's
                // residue at all, i.e. whether the negative result is a parameter problem.
                "sweepfar" to JSONObject().put("paperGhostSweepRadius", .15),
            )
        val rows = JSONArray()
        for ((label, overrides) in cases) {
            rows.put(JSONObject().put("label", label).put("stats", renderCase(label, overrides)))
        }
        val perf = JSONArray()
        for ((label, overrides) in cases) {
            perf.put(timeRender(label, overrides))
        }
        File(root(), "runs.json").writeText(
            JSONObject()
                .put("fingerprint", ScanConfig.fingerprint())
                .put("edge", deliveryEdge)
                .put("cases", rows)
                .put("perf", perf)
                .toString(2)
        )
        assertTrue("runs.json missing", File(root(), "runs.json").isFile)
    }
}
