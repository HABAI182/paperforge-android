package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.Utils
import org.opencv.core.Mat

/** Product-path checks: these need an Android device; desktop model scores do not substitute. */
class CaptureCanonicalFrameTest {
    private val frame = Quad(listOf(PointN(.1f, .1f), PointN(.9f, .1f), PointN(.9f, .9f), PointN(.1f, .9f)))

    @Test
    fun canonicalReferenceRoundTripsAndOldTasksKeepLegacySemantics() {
        val reference = CaptureReference(frame, 1000, 1100, 240, 320, "v", 5L, .9f, listOf(.5f), listOf(.5f), canonicalFrame = true)
        val preset = CapturePreset("ORIGINAL", 0f, 1f, 1f, 0f, "HYBRID", true)
        val encoded = CaptureSpec(preset, reference).encode()
        assertTrue(CaptureSpec.decode(encoded).reference!!.canonicalFrame)
        encoded.getJSONObject("reference").remove("canonicalFrame")
        assertFalse(CaptureSpec.decode(encoded).reference!!.canonicalFrame)
    }

    @Test
    fun matchedCanonicalFrameIsExactWhileStaleAndUnlockedUseFreshDetection() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val file = File(app.cacheDir, "canonical-crop-markers.jpg")
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint()
            // Long black border strokes trigger the legacy edge-ink expansion.
            paint.color = Color.BLACK
            drawRect(24f, 32f, 27f, 288f, paint)
            drawRect(24f, 32f, 216f, 35f, paint)
            for ((color, point) in listOf(Color.RED to (34f to 42f), Color.GREEN to (198f to 42f), Color.BLUE to (34f to 270f), Color.MAGENTA to (198f to 270f))) {
                paint.color = color
                drawRect(point.first, point.second, point.first + 9, point.second + 9, paint)
            }
        }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it)) }
        bitmap.recycle()
        val decoded = ScanProcessor.decode(file, 2000)
        val mat = Mat()
        val quality = try {
            Utils.bitmapToMat(decoded, mat)
            CameraQualityAnalyzer.analyze(mat, frame)
        } finally {
            mat.release()
            decoded.recycle()
        }
        val reference = CaptureReference(frame, 1000, 1100, 240, 320, "v", 1L, .95f, quality.sceneSignature, quality.signature, canonicalFrame = true)
        val result = ScanProcessor.detectFile(file, frame, reference, "HYBRID", true)
        assertEquals("viewfinder-locked", result.method)
        assertEquals(frame, result.quad)
        assertEquals(1f, result.diagnostics["canonicalFrameReused"]!!, 0f)
        val metadata = JSONObject(CaptureMetadata.encode(reference, result))
        assertTrue(metadata.getJSONObject("capture").getBoolean("exactFrame"))
        val page = PageRow("p", "d", 0, file.path, corners = frame.encode(), filter = "ORIGINAL", processingMetadata = metadata.toString())
        assertTrue(page.hasExactCropFrame())

        // An explicit saved frame must not secretly grow when the old protection setting changes.
        val config = ScanConfig.replaceForBenchmark(JSONObject().put("contentCropProtect", 1))
        var first: Bitmap? = null
        var second: Bitmap? = null
        try {
            first = ScanProcessor.render(page, 480)
            ScanConfig.replaceForBenchmark(JSONObject().put("contentCropProtect", 0))
            second = ScanProcessor.render(page, 480)
            assertEquals(first.width, second.width)
            assertEquals(first.height, second.height)
            assertTrue("ORIGINAL exact crop changed under edge protection", first.sameAs(second))
            val colors = IntArray(4)
            for (y in 0 until first.height) for (x in 0 until first.width) {
                val c = first.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                when {
                    r > 150 && g < 100 && b < 100 -> colors[0]++
                    g > 150 && r < 100 && b < 100 -> colors[1]++
                    b > 150 && r < 100 && g < 100 -> colors[2]++
                    r > 150 && b > 150 && g < 100 -> colors[3]++
                }
            }
            assertTrue("edge markers lost: ${colors.toList()}", colors.all { it > 20 })
        } finally {
            first?.recycle(); second?.recycle()
            ScanConfig.restoreAfterBenchmark(config)
        }
        val stale = ScanProcessor.detectFile(file, frame, reference.copy(requestedAt = 1600), "HYBRID", true)
        assertTrue(stale.method.contains("fresh-after-stale"))
        assertNotEquals(1f, stale.diagnostics["canonicalFrameReused"])
        assertFalse(JSONObject(CaptureMetadata.encode(reference, stale)).getJSONObject("capture").getBoolean("exactFrame"))
        val unlocked = ScanProcessor.detectFile(file, frame, reference, "HYBRID", false)
        assertNotEquals("viewfinder-locked", unlocked.method)
        assertNotEquals(1f, unlocked.diagnostics["canonicalFrameReused"])
    }
}
