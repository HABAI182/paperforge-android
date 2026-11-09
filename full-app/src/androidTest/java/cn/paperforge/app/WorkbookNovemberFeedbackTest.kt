package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat

@RunWith(AndroidJUnit4::class)
class WorkbookNovemberFeedbackTest {
    private val app
        get() = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private val folder
        get() = File(app.filesDir, "november-feedback").apply { mkdirs() }

    private fun save(bitmap: Bitmap, name: String) {
        File(folder, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun realBookAutomaticBeforeAfter() = runBlocking {
        val source = ScanProcessor.decode(File(folder, "book.jpg"), 2000)
        val detection = ScanProcessor.detectResult(source)
        assertNotNull("real book detector failed", detection.quad)
        val crop = ScanProcessor.transform(source, detection.quad!!, "ORIGINAL", targetEdge = 1400)
        save(crop, "source-crop")
        val rows = JSONArray()
        try {
            for ((pass, before) in listOf(false, false, true).withIndex()) {
                val edge = if (pass == 1) AutomaticScanBudget.edge() else 1400
                val ratio = minOf(1f, edge.toFloat() / maxOf(crop.width, crop.height))
                val input =
                    if (ratio < 1f)
                        Bitmap.createScaledBitmap(
                            crop,
                            (crop.width * ratio).toInt(),
                            (crop.height * ratio).toInt(),
                            true,
                        )
                    else crop
                val snapshot =
                    ScanConfig.replaceForBenchmark(
                        if (before)
                            JSONObject()
                                .put("workbookSupportedInkGain", 2.4)
                                .put("workbookConfidentBackground", 0)
                        else JSONObject()
                    )
                try {
                    val start = System.nanoTime()
                    val result = FlatDocument.process(app, input, ai = true, automatic = true)
                    try {
                        save(
                            result.bitmap,
                            if (before) "legacy-tone"
                            else if (pass == 1) "new-adaptive" else "new-automatic",
                        )
                        rows.put(
                            JSONObject()
                                .put("before", before)
                                .put("pass", pass)
                                .put("workEdge", edge)
                                .put("milliseconds", (System.nanoTime() - start) / 1e6)
                                .put("diagnostics", JSONObject(result.diagnostics ?: "{}"))
                        )
                    } finally {
                        result.bitmap.recycle()
                    }
                } finally {
                    ScanConfig.restoreAfterBenchmark(snapshot)
                    if (input !== crop) input.recycle()
                }
            }
            File(folder, "metrics.json")
                .writeText(
                    JSONObject()
                        .put("realPhoto", true)
                        .put("physicalPhone", false)
                        .put("referenceIsScreenshot", true)
                        .put("quad", detection.quad!!.encode())
                        .put("detector", detection.method)
                        .put("rows", rows)
                        .toString(2)
                )
        } finally {
            crop.recycle()
            source.recycle()
        }
    }

    @Test
    fun stackedSheetsAndSingleBorder() {
        val image = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.WHITE)
        val pen =
            Paint().apply {
                color = Color.BLACK
                strokeWidth = 2f
            }
        val mat = Mat()
        try {
            for (x in listOf(12f, 33f, 56f)) canvas.drawLine(x, 1f, x, 798f, pen)
            Utils.bitmapToMat(image, mat)
            val original = DetectionResult(Quad.Full, .9f, "fixture")
            val stack = PrimaryPaperFrame.refine(mat, original)
            assertTrue("stack not tightened", stack.quad!!.points[0].x > .07f)
            canvas.drawColor(Color.WHITE)
            for (x in listOf(12f, 33f, 56f)) for (y in 1..750 step 50) canvas.drawLine(
                x,
                y.toFloat(),
                x,
                (y + 40).toFloat(),
                pen,
            )
            Utils.bitmapToMat(image, mat)
            assertTrue(
                "fragmented stack not tightened",
                PrimaryPaperFrame.refine(mat, original).quad!!.points[0].x > .07f,
            )
            canvas.drawColor(Color.WHITE)
            canvas.drawLine(35f, 1f, 35f, 798f, pen)
            Utils.bitmapToMat(image, mat)
            assertEquals(
                "single rule cropped",
                Quad.Full,
                PrimaryPaperFrame.refine(mat, original).quad,
            )
        } finally {
            mat.release()
            image.recycle()
        }
    }
}
