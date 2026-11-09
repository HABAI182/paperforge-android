package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.EdgeInkDiagnostics
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.imaging.ScanProcessor
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer-by-layer reproduction of the v200 20016 regression "edge ink is erased".
 *
 * The owner's page is **not** copied into the repository: the test reads
 * `Android/data/cn.paperforge.app/files/edge-ink/original.jpg`, pushed by the acceptance script.
 * It then walks the real import render path (`ScanProcessor.render` -> `PageDecoder.crop` ->
 * `ScanProcessor.transform` -> `EnhancePipeline`), so every ablation runs the same code the
 * editor uses.
 *
 * The quad is the one the on-device import actually detected (recorded in `pages.corners` for
 * document `扫描 11-05 01.02`); a separate assertion checks the live detector still agrees, so the
 * fixture cannot silently drift away from the reported page.
 *
 * Stage images are written only when `edgeInkDump=1`; the numbers are computed offline from those
 * PNGs (see `scripts/edge_metrics.py`), never invented here.
 */
@RunWith(AndroidJUnit4::class)
class EdgeInkReproTest {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()
    private val recordedQuad =
        "0.1262081,0.09509941;0.9059508,0.12445831;0.9395825,0.89406466;0.06452234,0.8832647"

    private fun image(): File {
        val target = File(context.filesDir, "edge-ink/original.jpg")
        if (!target.isFile) {
            target.parentFile?.mkdirs()
            val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            assets.open("original.jpg").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        assertTrue("repro page was not packaged into the test APK", target.isFile)
        return target
    }

    private fun output(): File = File(context.filesDir, "edge-ink").apply { mkdirs() }

    private fun page(filter: String, corners: String = recordedQuad) =
        PageRow(
            id = "edge-ink-page",
            documentId = "edge-ink-doc",
            position = 0,
            original = image().path,
            corners = corners,
            filter = filter,
            enhanceStrength = 1f,
        )

    private fun render(label: String, overrides: JSONObject, filter: String = "WORKBOOK") {
        val dir = File(output(), label).apply { mkdirs() }
        val stages = File(dir, "stages").apply { mkdirs() }
        EdgeInkDiagnostics.directory = stages
        val snapshot = ScanConfig.replaceForBenchmark(overrides)
        try {
            val bitmap = ScanProcessor.render(page(filter), maxEdge = 1800)
            try {
                File(dir, "final.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            ScanConfig.restoreAfterBenchmark(snapshot)
            EdgeInkDiagnostics.directory = null
        }
        File(dir, "config.json").writeText(overrides.toString(2))
    }

    @Test
    fun recordedQuadMatchesTheLiveDetector() {
        val source = BitmapFactory.decodeFile(image().path)
        try {
            val detected = ScanProcessor.detect(source)?.encode()
            val recorded = page("WORKBOOK").let { cn.paperforge.app.model.Quad.decode(it.corners) }
            val delta =
                detected
                    ?.let { cn.paperforge.app.model.Quad.decode(it) }
                    ?.points
                    ?.zip(recorded.points)
                    ?.maxOfOrNull { (a, b) ->
                        kotlin.math.hypot(a.x - b.x, a.y - b.y).toDouble()
                    }
            File(output(), "quad.json")
                .writeText(
                    JSONObject()
                        .put("detected", detected)
                        .put("recorded", recordedQuad)
                        .put("maxCornerDelta", delta ?: -1.0)
                        .toString(2)
                )
            assertTrue("live detector moved away from the recorded import", (delta ?: 1.0) < .03)
        } finally {
            source.recycle()
        }
    }

    @Test
    fun dumpEveryEdgeInkLayerAndAblation() {
        val rows = JSONArray()
        // (a) crop only: is the content missing before any enhancement?
        render("a-original", JSONObject(), filter = "ORIGINAL")
        rows.put(JSONObject().put("label", "a-original-crop-only").put("group", "crop"))
        // Full pipeline, then one layer disabled at a time.
        val runs =
            listOf(
                "b-baseline" to JSONObject(),
                "c-edge-cleanup-off" to JSONObject().put("workbookEdgeCleanup", 0),
                "d-edge-guard-off" to JSONObject().put("paperEdgeGuard", 0),
                "e-white-off" to JSONObject().put("paperWhiteFinal", 0),
                "f-confident-background-off" to JSONObject().put("workbookConfidentBackground", 0),
                "g-workbook-intelligence-off" to JSONObject().put("workbookIntelligence", 0),
                "h-crop-safety" to JSONObject().put("cropSafetyMargin", 0.02),
                "i-boundary-margin" to JSONObject().put("workbookBoundaryMargin", 0.03),
                // Fixed-build ablations: which part of the fix carries the recovery.
                "j-fixed-no-content-crop" to JSONObject().put("contentCropProtect", 0),
                "k-fixed-no-edge-paper-repair" to
                    JSONObject().put("workbookEdgePaperRepair", 0),
                "l-fixed-no-stroke-veto" to
                    JSONObject().put("workbookBoundaryStrokeContrast", 999),
            )
        for ((label, overrides) in runs) {
            render(label, JSONObject().put("edgeInkDump", 1).also { out ->
                overrides.keys().forEach { out.put(it, overrides.get(it)) }
            })
            rows.put(JSONObject().put("label", label).put("group", "ablation"))
        }
        File(output(), "runs.json").writeText(rows.toString(2))
    }
}
