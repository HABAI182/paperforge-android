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
import cn.paperforge.app.imaging.ToneZones
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 20020 tone-zone acceptance on the owner's real page.
 *
 * Every case goes through the same [ScanProcessor.render] the PDF export worker uses, with the quad
 * the app recorded on import, at the delivery size (long edge 1375 = the 1063x1375 embedded page).
 * A lossless PNG plus the pipeline's own stage dumps are written for the offline measurement, so
 * the acceptance note can quote application output instead of a desktop post-process.
 *
 * The three parameter groups are the sensitivity probe; each case flips only the tone-zone keys
 * through [ScanConfig.replaceForBenchmark] and restores the production defaults afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ToneZones20020Test {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private val recordedQuad =
        "0.1262081,0.09509941;0.9059508,0.12445831;0.9395825,0.89406466;0.06452234,0.8832647"

    private val deliveryEdge = 1375

    private fun image(): File {
        val target = File(context.filesDir, "edge-ink/original.jpg")
        assertTrue("owner photo missing at ${target.path}", target.isFile)
        return target
    }

    private fun root(): File = File(context.filesDir, "edge-ink/r8").apply { mkdirs() }

    private fun page() =
        PageRow(
            id = "tonezones-page",
            documentId = "tonezones-doc",
            position = 0,
            original = image().path,
            corners = recordedQuad,
            filter = "WORKBOOK",
            enhanceStrength = 1f,
        )

    private fun diagnostics(): JSONObject =
        JSONObject()
            .put("toneZones", ToneZones.diagnostics() ?: JSONObject.NULL)
            .put("ghost", PaperGhost.diagnostics() ?: JSONObject.NULL)
            .put("tone", PaperTone.diagnostics() ?: JSONObject.NULL)
            .put("white", PaperWhiteFinal.diagnostics() ?: JSONObject.NULL)

    private fun renderCase(label: String, overrides: JSONObject): JSONObject {
        val dir = File(root(), label).apply { mkdirs() }
        val snapshot =
            ScanConfig.replaceForBenchmark(JSONObject(overrides.toString()).put("edgeInkDump", 1))
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

    private fun timeRender(overrides: JSONObject, runs: Int = 3): JSONObject {
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
        return JSONObject().put("ms", samples).put("medianMs", values[values.size / 2])
    }

    @Test
    fun toneZoneTiersOnTheOwnerPage() {
        ScanProcessor.render(page(), deliveryEdge).recycle()
        // Hysteresis round (tone-zones-4). The two baselines stay because analyze_r5 builds its
        // masks from them.
        val cases =
            listOf(
                "baseline_20020" to JSONObject().put("pageNetExterior", 0),
                "flat_20020_frames_off" to
                    JSONObject().put("toneZones", 0).put("pageNetExterior", 0),
                "both" to JSONObject(),
                "both_keep_faint" to JSONObject().put("toneZonesSeedCut", .8),
            )
        val rows = JSONArray()
        for ((label, overrides) in cases) {
            rows.put(JSONObject().put("label", label).put("stats", renderCase(label, overrides)))
        }
        val perf =
            JSONObject()
                .put("both", timeRender(JSONObject()))
                .put("tone_off", timeRender(JSONObject().put("toneZones", 0)))
        File(root(), "runs.json").writeText(
            JSONObject()
                .put("fingerprint", ScanConfig.fingerprint())
                .put("edge", deliveryEdge)
                .put("quad", recordedQuad)
                .put("cases", rows)
                .put("perf", perf)
                .toString(2)
        )
        assertTrue("runs.json missing", File(root(), "runs.json").isFile)
    }
}

