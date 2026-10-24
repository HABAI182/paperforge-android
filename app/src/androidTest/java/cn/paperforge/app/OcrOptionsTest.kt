package cn.paperforge.app

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.model.OcrLayout
import cn.paperforge.app.ocr.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OcrOptionsTest {
    @Test
    fun quickUsesOneNativePassAndBalancedAvoidsWholePageRetryCascade() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = (context.applicationContext as PaperForgeApp).ocrEngine
        val image = Bitmap.createBitmap(1400, 1800, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            canvas.drawColor(Color.WHITE)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 48f
                    typeface = Typeface.MONOSPACE
                }
            for (row in 0..19) canvas.drawText(
                "PAPERFORGE 00123 ITEM $row",
                90f,
                140f + row * 76,
                paint,
            )
            val fast = engine.recognizeBitmap(image, false, OcrOptions(OcrMode.FAST, false))
            assertEquals(listOf("original"), fast.attempts)
            assertEquals("FAST", fast.recognitionMode)
            assertTrue(fast.text.contains("00123"))
            val balanced = engine.recognizeBitmap(image, false, OcrOptions(OcrMode.BALANCED, false))
            assertTrue(balanced.text.contains("00123"))
            assertTrue(
                balanced.attempts.none {
                    it in setOf("clean", "upscale", "line-bw", "cubic-sample", "source-pixels")
                }
            )
            val stored = OcrLayout.decode(balanced.encode())
            assertEquals(balanced.attempts, stored.attempts)
            assertEquals("BALANCED", stored.recognitionMode)
            android.util.Log.i(
                "PaperForgeOcrMode",
                "FAST=${fast.attempts}; BALANCED=${balanced.attempts}; lines=${balanced.lines.size}",
            )
        } finally {
            image.recycle()
        }
    }
}

