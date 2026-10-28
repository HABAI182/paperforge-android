package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat

/** A single before/after package check; it measures dependency equivalence, not camera quality. */
class OpenCvPackageFlowTest {
    @Test
    fun productionCropEnhancementNativeDetectorAndActualPaddleRemainAvailable() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val folder = File(app.filesDir, "opencv-package-proof").apply { mkdirs() }
            val baselineFile = File(folder, "baseline.json")
            val phase =
                InstrumentationRegistry.getArguments().getString("phase")
                    ?: if (baselineFile.isFile) "candidate" else "baseline"
            val oldBackend = UserSettings.string("ocrBackend", "MLKIT")
            val oldPack = UserSettings.string("ocrPackId", "")
            val bitmap =
                InstrumentationRegistry.getInstrumentation()
                    .context
                    .assets
                    .open("scan-core/dark-desk-0.jpg")
                    .use { BitmapFactory.decodeStream(it)!! }
            val mat = Mat()
            val metrics =
                JSONObject()
                    .put("purpose", "same authored fixture; package/API equivalence only")
                    .put("phase", phase)
            try {
                Utils.bitmapToMat(bitmap, mat)
                val classical = DocumentDetector.detect(mat, true)
                val neural = NeuralEdgeDetector.detect(mat, true)
                assertNotNull(classical.quad)
                assertNotNull(neural.quad)
                metrics
                    .put("classicalQuad", classical.quad!!.encode())
                    .put("neuralQuad", neural.quad!!.encode())
                val files = JSONArray()
                val compared = JSONArray()
                for (mode in
                    listOf("ORIGINAL", "AUTO", "CLEAN", "BW", "TEXT", "COLOR", "WHITEBOARD")) {
                    val output =
                        ScanProcessor.transform(
                            bitmap,
                            neural.quad!!,
                            mode,
                            targetEdge = 640,
                            strength = 1f,
                        )
                    try {
                        val file = File(folder, "$phase-$mode.png")
                        file.outputStream().use {
                            output.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        assertTrue(output.width > 100 && output.height > 100)
                        files.put(
                            JSONObject()
                                .put("mode", mode)
                                .put("width", output.width)
                                .put("height", output.height)
                        )
                        if (phase != "baseline") {
                            val before =
                                BitmapFactory.decodeFile(File(folder, "baseline-$mode.png").path)
                            try {
                                assertEquals(before.width, output.width)
                                assertEquals(before.height, output.height)
                                compared.put(
                                    JSONObject()
                                        .put("mode", mode)
                                        .put("pixelsIdentical", before.sameAs(output))
                                )
                            } finally {
                                before.recycle()
                            }
                        }
                    } finally {
                        output.recycle()
                    }
                }
                metrics.put("images", files).put("comparison", compared)
                val textImage = Bitmap.createBitmap(1500, 260, Bitmap.Config.ARGB_8888)
                try {
                    textImage.eraseColor(Color.WHITE)
                    val paint =
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.BLACK
                            textSize = 62f
                            typeface =
                                Typeface.createFromAsset(
                                    app.assets,
                                    "fonts/PaperForgeCJK-Regular.ttf",
                                )
                        }
                    Canvas(textImage).drawText("PaperForge 00123 文档测试", 65f, 155f, paint)
                    val pack =
                        app.ocrModels.installed.value.first {
                            it.manifest.getString("id") == "paddle-ppocrv5-mobile"
                        }
                    UserSettings.set("ocrBackend", "PADDLE")
                    UserSettings.set("ocrPackId", pack.id)
                    val engine = app.ocrEngine.snapshot()
                    val layout =
                        engine.recognizeBitmap(textImage, true, OcrOptions(OcrMode.FAST, false))
                    assertTrue(layout.text.contains("00123"))
                    assertTrue(layout.text.contains("文档测试"))
                    metrics
                        .put("actualPaddle", JSONObject(layout.encode()))
                        .put("paddleText", layout.text)
                    if (phase != "baseline") {
                        val before = JSONObject(baselineFile.readText())
                        assertEquals(before.getString("paddleText"), layout.text)
                        for (key in listOf("classicalQuad", "neuralQuad")) {
                            val a = Quad.decode(before.getString(key))
                            val b = Quad.decode(metrics.getString(key))
                            assertTrue(
                                a.points.zip(b.points).all { (p, q) ->
                                    kotlin.math.hypot(p.x - q.x, p.y - q.y) < .005f
                                }
                            )
                        }
                    }
                } finally {
                    textImage.recycle()
                }
                File(folder, "$phase-build.txt").writeText(Core.getBuildInformation())
                File(folder, "$phase.json").writeText(metrics.toString(2))
                android.util.Log.i("OpenCvPackageFlowTest", "Proof: ${folder.path}, phase=$phase")
            } finally {
                mat.release()
                bitmap.recycle()
                UserSettings.set("ocrBackend", oldBackend)
                UserSettings.set("ocrPackId", oldPack)
                app.ocrEngine.trimUnused()
            }
        }
}
