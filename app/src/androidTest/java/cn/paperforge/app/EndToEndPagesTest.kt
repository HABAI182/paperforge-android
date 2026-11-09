package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.EdgeInkDiagnostics
import cn.paperforge.app.imaging.LearnedDocumentDetector
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.imaging.ScanProcessor
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat

/**
 * The whole path a user sees: photo, default detector, default workbook render. Writes each page to
 * files/end-to-end as lossless PNG, at the long edge the exported PDFs use.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndPagesTest {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    @Test
    fun renderPagesFromDetectedCrops() {
        val root = File(context.filesDir, "end-to-end").apply { mkdirs() }
        val rows = JSONArray()
        val samples =
            File(context.filesDir, "detect-samples").listFiles().orEmpty().sorted().filter {
                it.name in setOf("owner-page.jpg", "book.jpg", "workbook-counterexample.jpg")
            }
        assertTrue("project photos missing", samples.size == 3)
        for (file in samples) {
            val bitmap = BitmapFactory.decodeFile(file.path)
            val rgba = Mat()
            val detection =
                try {
                    Utils.bitmapToMat(bitmap, rgba)
                    LearnedDocumentDetector.detect(rgba, false)
                } finally {
                    rgba.release()
                    bitmap.recycle()
                }
            val quad = checkNotNull(detection.quad) { "no page found in ${file.name}" }
            val corners = quad.points.joinToString(";") { "${it.x},${it.y}" }
            val page =
                PageRow(
                    id = "end-to-end-" + file.nameWithoutExtension,
                    documentId = "end-to-end",
                    position = 0,
                    original = file.path,
                    corners = corners,
                    filter = "WORKBOOK",
                    enhanceStrength = 1f,
                )
            val started = System.nanoTime()
            val stages = File(root, file.nameWithoutExtension).apply { mkdirs() }
            val snapshot = ScanConfig.replaceForBenchmark(JSONObject().put("edgeInkDump", 1))
            val output =
                try {
                    EdgeInkDiagnostics.directory = stages
                    ScanProcessor.render(page, 2572)
                } finally {
                    EdgeInkDiagnostics.directory = null
                    ScanConfig.restoreAfterBenchmark(snapshot)
                }
            try {
                File(root, file.nameWithoutExtension + ".png").outputStream().use {
                    assertTrue(output.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                rows.put(
                    JSONObject()
                        .put("file", file.name)
                        .put("method", detection.method)
                        .put("confidence", detection.confidence.toDouble())
                        .put("corners", corners)
                        .put("width", output.width)
                        .put("height", output.height)
                        .put("renderMs", (System.nanoTime() - started) / 1e6)
                )
            } finally {
                output.recycle()
            }
        }
        File(root, "runs.json").writeText(rows.toString(1))
    }
}
