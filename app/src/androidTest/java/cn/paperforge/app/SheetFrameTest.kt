package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.imaging.SheetFrame
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * [SheetFrame]: a quad that stopped at a box printed on the page grows to the page's edge; a top sheet of a
 * different paper keeps its quad, so the sheet underneath is never taken.
 */
@RunWith(AndroidJUnit4::class)
class SheetFrameTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun opencv() {
            OpenCVLoader.initLocal()
        }
    }

    /** The 320 x 320 RGB the network sees, as PageNetDetector builds it. */
    private fun input(bitmap: Bitmap): ByteArray {
        val rgba = Mat()
        val small = Mat()
        val rgb = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.resize(rgba, small, Size(320.0, 320.0), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
            return ByteArray(320 * 320 * 3).also { rgb.get(0, 0, it) }
        } finally {
            listOf(rgba, small, rgb).forEach(Mat::release)
        }
    }

    private fun quad(l: Float, t: Float, r: Float, b: Float) =
        Quad.ordered(listOf(PointN(l, t), PointN(r, t), PointN(r, b), PointN(l, b)))

    /** 1200 x 1600 photo: dark desk, an off-white page at .15-.85 x .12-.88, [inner] drawn on it. */
    private fun photo(inner: (Canvas) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(70, 58, 46))
        canvas.drawRect(180f, 192f, 1020f, 1408f, Paint().apply { color = Color.rgb(246, 244, 238) })
        inner(canvas)
        return bitmap
    }

    private val box = quad(300f / 1200, 420f / 1600, 900f / 1200, 1200f / 1600)

    @Test
    fun theFrameAroundAPrintedTableIsThePageEdge() {
        val bitmap =
            photo { canvas ->
                val line = Paint().apply { color = Color.rgb(120, 120, 126); strokeWidth = 2f; style = Paint.Style.STROKE }
                canvas.drawRect(300f, 420f, 900f, 1200f, line)
                for (x in 420..780 step 120) canvas.drawLine(x.toFloat(), 420f, x.toFloat(), 1200f, line)
                for (y in 540..1080 step 120) canvas.drawLine(300f, y.toFloat(), 900f, y.toFloat(), line)
            }
        val frame = SheetFrame.outerFrame(box, input(bitmap))
        assertNotNull("a frame around the table", frame)
        val page = quad(.15f, .12f, .85f, .88f)
        frame!!.points.zip(page.points).forEach { (a, b) ->
            assertEquals("x", b.x, a.x, .02f)
            assertEquals("y", b.y, a.y, .02f)
        }
    }

    @Test
    fun aDifferentTopSheetKeepsItsQuad() {
        // A yellow note lying on the page: the page edge is a full frame around it, but the strip between is
        // another paper, so the note's quad stays.
        val bitmap = photo { canvas -> canvas.drawRect(300f, 420f, 900f, 1200f, Paint().apply { color = Color.rgb(250, 222, 96) }) }
        assertNotNull("the page edge is found around the note", SheetFrame.outerFrame(box, input(bitmap)))
        assertNull("but the note keeps its own quad", SheetFrame.expand(box, input(bitmap)))
    }
}
