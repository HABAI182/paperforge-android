package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.Quad
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnhancementTest {
    @Test
    fun colorAndSolidFillsSurviveShadowRemoval() {
        val original = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        val pixels =
            IntArray(800 * 1000) { i ->
                val x = i % 800
                val y = i / 800
                val illumination = .45 + x / 800.0 * .55
                val c =
                    when {
                        x in 100..650 && y in 400..480 -> Color.rgb(24, 24, 24)
                        x in 550..650 && y in 700..800 -> Color.rgb(190, 25, 35)
                        x in 100..150 && y in 700..800 -> Color.rgb(35, 70, 205)
                        else -> Color.rgb(244, 243, 235)
                    }
                Color.rgb(
                    (Color.red(c) * illumination).toInt(),
                    (Color.green(c) * illumination).toInt(),
                    (Color.blue(c) * illumination).toInt(),
                )
            }
        original.setPixels(pixels, 0, 800, 0, 0, 800, 1000)
        try {
            for (mode in listOf("AUTO", "WHITEBOARD", "BW")) {
                val result = ScanProcessor.transform(original, Quad.Full, mode)
                try {
                    val dark = result.getPixel(result.width * 3 / 8, result.height * 44 / 100)
                    assertTrue("$mode erased black fill", Color.red(dark) < 90)
                    if (mode != "BW") {
                        val red = result.getPixel(result.width * 3 / 4, result.height * 3 / 4)
                        val blue = result.getPixel(result.width * 15 / 100, result.height * 3 / 4)
                        assertTrue("$mode lost red", Color.red(red) > Color.green(red) * 2)
                        assertTrue("$mode lost blue", Color.blue(blue) > Color.red(blue) * 2)
                    }
                } finally {
                    result.recycle()
                }
            }
        } finally {
            original.recycle()
        }
    }
}
