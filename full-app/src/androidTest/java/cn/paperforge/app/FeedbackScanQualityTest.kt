package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedbackScanQualityTest {
    @Test
    fun finalWorkbookToneOnExistingSevenPhotoCrops() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val output = File(app.filesDir, "feedback-scan/output")
        val report = JSONObject(File(output, "metrics.json").readText())
        val cases = report.getJSONArray("cases")
        val started = System.nanoTime()
        for (index in 0 until cases.length()) {
            val name = cases.getJSONObject(index).getString("id")
            val source = BitmapFactory.decodeFile(File(output, "$name-crop.png").path)
            val rgba = org.opencv.core.Mat()
            val rgb = org.opencv.core.Mat()
            val result = org.opencv.core.Mat()
            try {
                org.opencv.android.Utils.bitmapToMat(source, rgba)
                org.opencv.imgproc.Imgproc.cvtColor(
                    rgba,
                    rgb,
                    org.opencv.imgproc.Imgproc.COLOR_RGBA2RGB,
                )
                EnhancePipeline.apply(rgb, result, "WORKBOOK", .85f)
                val bitmap =
                    Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                try {
                    org.opencv.android.Utils.matToBitmap(result, bitmap)
                    File(output, "$name-workbook.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    bitmap.recycle()
                }
            } finally {
                source.recycle()
                rgba.release()
                rgb.release()
                result.release()
            }
        }
        report
            .put("finalWorkbookGamma", ScanConfig.float("workbookInkGamma", 1.7f))
            .put("finalWorkbookSevenFilterMs", (System.nanoTime() - started) / 1e6)
        File(output, "metrics.json").writeText(report.toString(2))
    }

    @Test
    fun cropRendersTheVisiblePaperBoundaryWithoutADeskRing() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val image = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val source = File(app.cacheDir, "feedback-exact-paper.png")
        try {
            Canvas(image).apply {
                drawColor(Color.RED)
                drawRect(98f, 98f, 303f, 503f, Paint().apply { color = Color.WHITE })
            }
            source.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val quad =
                Quad(
                    listOf(
                        PointN(100f / 399, 100f / 599),
                        PointN(300f / 399, 100f / 599),
                        PointN(300f / 399, 500f / 599),
                        PointN(100f / 399, 500f / 599),
                    )
                )
            val rendered =
                ScanProcessor.render(
                    PageRow(
                        "exact",
                        "test",
                        0,
                        source.path,
                        corners = quad.encode(),
                        filter = "ORIGINAL",
                    ),
                    600,
                )
            try {
                for (x in listOf(0, rendered.width / 2, rendered.width - 1)) for (y in
                    listOf(0, rendered.height / 2, rendered.height - 1)) {
                    val pixel = rendered.getPixel(x, y)
                    assertTrue(
                        "Visible white paper must not regain the red desk",
                        Color.green(pixel) > 245 && Color.blue(pixel) > 245,
                    )
                }
            } finally {
                rendered.recycle()
            }
        } finally {
            image.recycle()
            source.delete()
        }
    }

    @Test
    fun ownerFeedbackPhotosCompareActualOldAndNewPipelines() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val folder = File(app.filesDir, "feedback-scan")
        val input = JSONObject(File(folder, "input.json").readText()).getJSONArray("cases")
        val output = File(folder, "output").apply { mkdirs() }
        val results = JSONArray()
        val original = ScanConfig.replaceForBenchmark(JSONObject())
        try {
            for (index in 0 until input.length()) {
                val item = input.getJSONObject(index)
                val name = item.getString("id")
                val file = File(folder, item.getString("file"))
                val length = file.length()
                assertTrue("Private input $name must be present", length > 0)
                val started = System.nanoTime()
                ScanConfig.replaceForBenchmark(
                    JSONObject()
                        .put("edgeRidgeFit", 0)
                        .put("cropSafetyMargin", .01)
                        .put("workbookInkNoise", 6)
                        .put("workbookInkBlack", 150)
                        .put("workbookInkGamma", 3)
                        .put("workbookFaintEvidenceFloor", 12)
                        .put("workbookFaintEvidenceHigh", 42)
                        .put("workbookOutputInkFloor", 0)
                        .put("workbookSmoothEvidence", 0)
                )
                val old = ScanProcessor.detectFile(file, detectorOverride = "HYBRID")
                val oldQuad = requireNotNull(old.quad) { "Baseline detection $name" }
                fun save(mode: String, quad: Quad, suffix: String) {
                    val image =
                        ScanProcessor.render(
                            PageRow(
                                name,
                                "benchmark",
                                index,
                                file.path,
                                corners = quad.encode(),
                                filter = mode,
                                enhanceStrength = .85f,
                            ),
                            1600,
                        )
                    try {
                        File(output, "$name-$suffix.png").outputStream().use {
                            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally {
                        image.recycle()
                    }
                }
                save("WORKBOOK", oldQuad, "old-workbook")
                ScanConfig.replaceForBenchmark(JSONObject().put("workbookLocalPaper", 0))
                save("CLEAN", oldQuad, "old-clean")
                ScanConfig.restoreAfterBenchmark(original)
                val fresh = ScanProcessor.detectFile(file, detectorOverride = "HYBRID")
                val freshQuad = requireNotNull(fresh.quad) { "New detection $name" }
                assertTrue(freshQuad.valid())
                save("ORIGINAL", freshQuad, "crop")
                save("WORKBOOK", freshQuad, "workbook")
                save("CLEAN", freshQuad, "clean")
                assertEquals("Source must remain intact", length, file.length())
                results.put(
                    JSONObject()
                        .put("id", name)
                        .put("oldQuad", oldQuad.encode())
                        .put("quad", freshQuad.encode())
                        .put("method", fresh.method)
                        .put("elapsedMs", (System.nanoTime() - started) / 1e6)
                )
            }
            File(output, "metrics.json")
                .writeText(
                    JSONObject().put("physicalDevice", false).put("cases", results).toString(2)
                )
        } finally {
            ScanConfig.restoreAfterBenchmark(original)
        }
    }
}
