package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.imaging.LearnedDocumentDetector
import cn.paperforge.app.imaging.ScanModel
import cn.paperforge.app.ocr.BundledOcrEngine
import cn.paperforge.app.ocr.OcrOptions
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.Utils
import org.opencv.core.Mat

/** Exercise the bytes we repack, including both native model entry points and offline OCR. */
class ShrunkRuntimeTest {
    @Test
    fun canonicalFontCompressedModelsAndNativeOcrStillLoad() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        ZipFile(app.applicationInfo.sourceDir).use { apk ->
            val fonts =
                apk.entries()
                    .asSequence()
                    .filter { it.name.endsWith("/PaperForgeCJK-Regular.ttf") }
                    .toList()
            assertEquals(listOf("assets/fonts/PaperForgeCJK-Regular.ttf"), fonts.map { it.name })
            for (path in
                listOf(
                    "assets/docquad/docquadnet256.ort",
                    "assets/scan/border.mnn",
                    "assets/scan/dewarp.mnn",
                )) assertEquals(
                "$path must be compressed",
                ZipEntry.DEFLATED,
                apk.getEntry(path).method,
            )
        }
        val image = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val rgba = Mat()
        try {
            Canvas(image).apply {
                drawColor(Color.DKGRAY)
                drawRect(40f, 45f, 860f, 1155f, Paint().apply { color = Color.WHITE })
                drawText(
                    "PAPERFORGE 012345",
                    100f,
                    250f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 58f
                        typeface = Typeface.DEFAULT_BOLD
                    },
                )
            }
            Utils.bitmapToMat(image, rgba)
            for (dewarp in listOf(false, true)) {
                val field = ScanModel.run(rgba, dewarp)
                assertEquals(2 * 240 * 240, field.size)
                assertTrue(field.all(Float::isFinite))
            }
            // Force the same asset-to-file path used by a fresh installation.
            val ortCache = File(app.cacheDir, "docquad-01bebd39.ort")
            check(!ortCache.exists() || ortCache.delete())
            val detected = LearnedDocumentDetector.detect(image)
            assertTrue(
                "ORT unavailable: ${detected.method}",
                detected.method.startsWith("docquad-"),
            )
            assertEquals(13_404_960L, ortCache.length())
            val engine = BundledOcrEngine()
            val latin = engine.recognizeBitmap(image, false, OcrOptions.current())
            assertTrue(latin.text, latin.text.contains("012345"))
            val chinese = engine.recognizeBitmap(image, true, OcrOptions.current())
            assertTrue(chinese.text, chinese.text.contains("012345"))
            File(app.getExternalFilesDir(null), "issue11/runtime.json").apply {
                parentFile!!.mkdirs()
                writeText(
                    JSONObject()
                        .put("singleCanonicalCjkFont", true)
                        .put("compressedModelAssets", true)
                        .put("borderMnnLoaded", true)
                        .put("dewarpMnnLoaded", true)
                        .put("ortMethod", detected.method)
                        .put("latinOfflineOcr", latin.text)
                        .put("chineseOfflineOcr", chinese.text)
                        .toString(2)
                )
            }
        } finally {
            rgba.release()
            image.recycle()
        }
    }
}
