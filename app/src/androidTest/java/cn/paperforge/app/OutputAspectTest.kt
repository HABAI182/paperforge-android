package cn.paperforge.app

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.PageResources
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.FlatDocument
import cn.paperforge.app.imaging.ScanProcessor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The crop dialog's A4/A5, Square and ID presets shape the finished page, both for a plain page and for a page
 * that was already flattened (the camera's usual result), which is finished again from its photo on save.
 * Writes files/phone-out/output-aspect.json.
 */
@RunWith(AndroidJUnit4::class)
class OutputAspectTest {
    private val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    @Test
    fun presetsShapeTheFinishedPage() = runBlocking {
        val repo = app.repository
        val doc = repo.newScan()
        val file = repo.captureFile(doc.id)
        InstrumentationRegistry.getInstrumentation().context.assets.open("scan-core/dark-desk-0.jpg").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val base =
            PageRow(
                UUID.randomUUID().toString(),
                doc.id,
                0,
                file.path,
                corners = "0.2425,0.08;0.76,0.09666667;0.84,0.91;0.18,0.98333335",
                filter = "WORKBOOK",
            )
        repo.dao.putPage(base)
        val report = JSONObject()
        val failures = mutableListOf<String>()
        try {
            // A page as the camera finishes it: flattened into its own image.
            val flat = FlatDocument.prepare(repo, base, false, .70710678f, {}, automatic = true)
            repo.savePage(flat)
            for ((name, aspect) in listOf("A4/A5" to .7071068f, "square" to 1f, "ID" to 85.6f / 54f)) {
                val plain = ScanProcessor.render(base.copy(outputAspect = aspect), 1600)
                val plainRatio = plain.width.toFloat() / plain.height
                plain.recycle()
                // What the crop dialog edits for a finished page, and what saving it runs (MainViewModel.saveGeometry).
                val draft = PageResources.sourceAppearance(flat).copy(outputAspect = aspect)
                val after = draft.copy(filter = flat.filter, enhanceStrength = flat.enhanceStrength)
                val refinished =
                    FlatDocument.prepare(
                        repo,
                        after,
                        false,
                        .70710678f,
                        {},
                        workEdge = cn.paperforge.app.imaging.AutomaticScanBudget.edge(),
                        automatic = true,
                    )
                val shown = ScanProcessor.render(refinished, 1600)
                val flatRatio = shown.width.toFloat() / shown.height
                shown.recycle()
                report.put(name, JSONObject().put("want", aspect).put("plain", plainRatio).put("flattened", flatRatio))
                if (kotlin.math.abs(plainRatio - aspect) > .02f) failures += "$name plain page $plainRatio, want $aspect"
                if (kotlin.math.abs(flatRatio - aspect) > .02f) failures += "$name flattened page $flatRatio, want $aspect"
            }
        } finally {
            File(app.filesDir, "phone-out").mkdirs()
            File(app.filesDir, "phone-out/output-aspect.json").writeText(report.toString(1))
            repo.deleteDocument(doc)
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }
}
