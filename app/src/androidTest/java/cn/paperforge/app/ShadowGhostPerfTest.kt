package cn.paperforge.app

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.imaging.ScanProcessor
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end render timing for the 20018 "shadow band + show-through" acceptance.
 *
 * Deliberately uses only APIs that exist on the 20018 baseline too, so the *same* test class is
 * compiled into the baseline APK and the new APK and the two builds are timed on the same page,
 * the same quad and the same `ScanProcessor.render` call the PDF export worker uses. No timing
 * assertion is made here — the numbers are reported, not gated.
 */
@RunWith(AndroidJUnit4::class)
class ShadowGhostPerfTest {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private val recordedQuad =
        "0.1262081,0.09509941;0.9059508,0.12445831;0.9395825,0.89406466;0.06452234,0.8832647"

    private fun image(): File {
        val target = File(context.filesDir, "edge-ink/original.jpg")
        assertTrue("owner photo missing at ${target.path}; push it before running", target.isFile)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, bounds)
        assertTrue("owner photo is not decodable", bounds.outWidth > 0 && bounds.outHeight > 0)
        return target
    }

    private fun page() =
        PageRow(
            id = "shadow-perf-page",
            documentId = "shadow-perf-doc",
            position = 0,
            original = image().path,
            corners = recordedQuad,
            filter = "WORKBOOK",
            enhanceStrength = 1f,
        )

    private fun timeRender(maxEdge: Int, runs: Int): JSONArray {
        ScanProcessor.render(page(), maxEdge).recycle()
        val samples = JSONArray()
        repeat(runs) {
            val started = System.nanoTime()
            val bitmap = ScanProcessor.render(page(), maxEdge)
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            samples.put(elapsed)
            bitmap.recycle()
        }
        return samples
    }

    @Test
    fun workbookRenderTimeOnTheOwnerPage() {
        val version =
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val result =
            JSONObject()
                .put("page", "work/ repro-edge/original.jpg (1279x1706)")
                .put("versionCode", version)
                .put("fingerprint", ScanConfig.fingerprint())
                .put("quad", recordedQuad)
                .put("edge1800", timeRender(1800, 3))
                .put("edge2400", timeRender(2400, 3))
        val target = File(context.filesDir, "edge-ink/perf.json")
        target.parentFile?.mkdirs()
        target.writeText(result.toString(2))
        assertTrue("perf timing did not produce a report", target.isFile)
    }
}
