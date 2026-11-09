package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.Quad
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class WorkbookCleanupSweepTest {
    @Test
    fun zeroStrengthGridKeepsActualPaperEdges() {
        val source = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 0f, 20f, 600f, Paint().apply { color = Color.RED })
            drawRect(380f, 0f, 400f, 600f, Paint().apply { color = Color.BLUE })
            drawCircle(200f, 300f, 7f, Paint().apply { color = Color.BLACK })
        }
        val grid =
            cn.paperforge.app.model.DewarpGrid(
                400,
                600,
                0f,
                2,
                floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
            )
        val replay = DewarpTrace.sample(source, grid, 400, 600)
        try {
            assertTrue("identity grid displaced source edges", source.sameAs(replay))
        } finally {
            replay.recycle()
            source.recycle()
        }
    }

    @Test
    fun handFringeKeepsNearbyInkAndRealAutomaticOutput() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val folder = File(app.filesDir, "november-feedback").apply { mkdirs() }
        val image = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.rgb(241, 239, 235))
        canvas.drawRect(0f, 310f, 105f, 430f, Paint().apply { color = Color.rgb(211, 150, 112) })
        canvas.drawOval(65f, 310f, 150f, 430f, Paint().apply { color = Color.rgb(211, 150, 112) })
        val pen =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(210, 210, 210)
                strokeWidth = 1.5f
            }
        canvas.drawLine(160f, 345f, 195f, 350f, pen)
        canvas.drawCircle(162f, 380f, 2f, pen)
        pen.color = Color.RED
        pen.strokeWidth = 2f
        canvas.drawLine(10f, 50f, 10f, 170f, pen)
        val rgba = Mat()
        val rgb = Mat()
        val mask = Mat()
        try {
            Utils.bitmapToMat(image, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val prepared = WorkbookPaperBoundary.prepare(rgb, true, mask)
            try {
                assertNotNull("fixture hand not found", prepared)
                assertFalse(mask.empty())
                val pixels = ByteArray(600 * 800).also { mask.get(0, 0, it) }
                assertTrue("edge hand was not removed", pixels[360 * 600 + 80] != 0.toByte())
                for (y in 335..390) for (x in 157..200) assertEquals(
                    "nearby pencil erased at $x,$y",
                    0,
                    pixels[y * 600 + x].toInt(),
                )
                for (y in 50..170) assertEquals(
                    "margin annotation erased",
                    0,
                    pixels[y * 600 + 10].toInt(),
                )
            } finally {
                prepared?.release()
            }
        } finally {
            image.recycle()
            listOf(rgba, rgb, mask).forEach(Mat::release)
        }
        val raw = ScanProcessor.decode(File(folder, "source-crop.png"), 1400)
        try {
            val start = System.nanoTime()
            val result = FlatDocument.process(app, raw, ai = true, automatic = true)
            try {
                File(folder, "final-automatic.png").outputStream().use {
                    result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                File(folder, "final-automatic.json")
                    .writeText(
                        JSONObject()
                            .put("processingAndOutputMs", (System.nanoTime() - start) / 1e6)
                            .put("diagnostics", JSONObject(result.diagnostics ?: "{}"))
                            .put("physicalPhone", false)
                            .toString(2)
                    )
            } finally {
                result.bitmap.recycle()
            }
        } finally {
            raw.recycle()
        }
    }

    @Test
    fun realBookFingerAndBackgroundCandidates() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val folder = File(app.filesDir, "november-feedback").apply { mkdirs() }
        val raw = ScanProcessor.decode(File(folder, "source-crop.png"), 1400)
        val rows = JSONArray()
        try {
            for ((name, mode, graph) in
                listOf(
                    Triple("workbook-base", "WORKBOOK", 0),
                    Triple("workbook-graph", "WORKBOOK", 1),
                    Triple("clean-base", "CLEAN", 0),
                    Triple("clean-graph", "CLEAN", 1),
                    Triple("clean-soft-anchors", "CLEAN", 1),
                )) {
                val snapshot =
                    ScanConfig.replaceForBenchmark(
                        JSONObject()
                            .put("workbookFingerGraphCut", graph)
                            .put("workbookDiagnostics", 1)
                            .put(
                                "cleanBackgroundProtectAnchors",
                                if (name == "clean-soft-anchors") 0 else 1,
                            )
                    )
                try {
                    val start = System.nanoTime()
                    val result =
                        FastInkModel.scoped(true) { ScanProcessor.transform(raw, Quad.Full, mode) }
                    try {
                        File(folder, "sweep-$name.png").outputStream().use {
                            result.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally {
                        result.recycle()
                    }
                    rows.put(
                        JSONObject()
                            .put("name", name)
                            .put("ms", (System.nanoTime() - start) / 1e6)
                            .put("boundary", WorkbookPaperBoundary.diagnostics())
                            .put("decision", WorkbookContent.lastDecision())
                    )
                } finally {
                    ScanConfig.restoreAfterBenchmark(snapshot)
                }
            }
            File(folder, "sweep-metrics.json")
                .writeText(
                    JSONObject()
                        .put("realPhoto", true)
                        .put("phoneMeasured", false)
                        .put("rows", rows)
                        .toString(2)
                )
        } finally {
            raw.recycle()
        }
    }
}
