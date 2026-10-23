package cn.paperforge.app.quality

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.imaging.CameraQualityAnalyzer
import cn.paperforge.app.model.*
import java.io.File
import kotlin.math.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class CameraQualityTest {
    @Test
    fun recordedSharpBlurSequenceAndFocusReadinessGateAutomaticCapture() {
        val root =
            File(
                    ApplicationProvider.getApplicationContext<PaperForgeApp>().filesDir,
                    "quality-camera",
                )
                .apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(242, 242, 238))
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 40f
            }
        for (row in 0 until 24) canvas.drawText(
            "PaperForge document detail 0123456789",
            60f,
            140f + row * 57,
            paint,
        )
        val sharp = Mat()
        val blur = Mat()
        try {
            Utils.bitmapToMat(bitmap, sharp)
            Imgproc.GaussianBlur(sharp, blur, Size(51.0, 51.0), 10.0)
            val good = CameraQualityAnalyzer.analyze(sharp, Quad.Full)
            val bad = CameraQualityAnalyzer.analyze(blur, Quad.Full)
            fun sequence(result: CameraQualityAnalyzer.Result, focusReady: Boolean): Int {
                val gate = AutoCaptureGate()
                val quality = CaptureQualityTracker()
                var captures = 0
                repeat(30) { frame ->
                    val now = (frame + 1) * 200L
                    val acceptable =
                        quality.update(Quad.Full, result.sample, result.signature, now, focusReady)
                    if (gate.update(Quad.Full, now, acceptable)) captures++
                }
                return captures
            }
            val sharpCaptures = sequence(good, true)
            val blurredCaptures = sequence(bad, true)
            val unfocusedCaptures = sequence(good, false)
            assertEquals(1, sharpCaptures)
            assertEquals(0, blurredCaptures)
            assertEquals(0, unfocusedCaptures)
            val tracker = OutlineTracker()
            var lastRaw: Quad? = null
            var lastDisplay: Quad? = null
            var rawJitter = 0.0
            var displayJitter = 0.0
            repeat(40) { frame ->
                val shift = if (frame % 2 == 0) .005f else -.005f
                val raw =
                    Quad(
                        listOf(
                            PointN(.1f + shift, .1f),
                            PointN(.9f + shift, .1f),
                            PointN(.9f + shift, .9f),
                            PointN(.1f + shift, .9f),
                        )
                    )
                val display = tracker.update(raw, .95f, List(16) { .7f }, (frame + 1) * 200L)!!
                lastRaw?.let { rawJitter += abs(raw.points[0].x - it.points[0].x) }
                lastDisplay?.let { displayJitter += abs(display.points[0].x - it.points[0].x) }
                lastRaw = raw
                lastDisplay = display
            }
            assertTrue(displayJitter < rawJitter * .65)
            val report =
                JSONObject()
                    .put("synthetic", true)
                    .put("physicalCamera", false)
                    .put("sharpnessSharp", good.sample!!.sharpness)
                    .put("sharpnessBlur", bad.sample!!.sharpness)
                    .put("detailTilesSharp", good.sample.detailTiles)
                    .put("detailTilesBlur", bad.sample.detailTiles)
                    .put("framesPerSequence", 30)
                    .put("sharpCaptures", sharpCaptures)
                    .put("blurCaptures", blurredCaptures)
                    .put("focusNotReadyCaptures", unfocusedCaptures)
                    .put("rawCornerMotion", rawJitter)
                    .put("displayCornerMotion", displayJitter)
            File(root, "sequence.json").writeText(report.toString(2))
            File(root, "sharp.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val blurred = Bitmap.createBitmap(blur.cols(), blur.rows(), Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(blur, blurred)
                File(root, "blur.png").outputStream().use {
                    blurred.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                blurred.recycle()
            }
        } finally {
            bitmap.recycle()
            sharp.release()
            blur.release()
        }
    }
}
