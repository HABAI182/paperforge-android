package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.PointN
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class WorkbookQualityCandidateTest {
    @Test
    fun knownRuledCurvatureImprovesWithoutTrimmingBorders() {
        ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
        for (base in 85..1141 step 44) {
            val path = Path()
            for (x in 0 until bitmap.width) {
                val y = base + 24f * (2f * x / (bitmap.width - 1) - 1).pow(2)
                if (x == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
            }
            canvas.drawPath(path, paint)
        }
        val source = Mat()
        val corrected = Mat()
        val map = Mat(1200, 900, CvType.CV_32FC2)
        val unused = Mat()
        try {
            Utils.bitmapToMat(bitmap, source)
            val result = RuledPageRefinement.estimate(source)
            assertNotNull(result)
            result!!
            assertTrue(result.lines >= 12)
            assertEquals(0f, result.inputPoint(PointN(.4f, 0f)).y, 1e-6f)
            assertEquals(1f, result.inputPoint(PointN(.4f, 1f)).y, 1e-6f)
            val row = FloatArray(900 * 2)
            for (y in 0 until 1200) {
                for (x in 0 until 900) {
                    row[x * 2] = x.toFloat()
                    row[x * 2 + 1] = result.inputPoint(PointN(x / 899f, y / 1199f)).y * 1199
                }
                map.put(y, 0, row)
            }
            Imgproc.remap(
                source,
                corrected,
                map,
                unused,
                Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE,
            )
            val image = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(corrected, image)
                fun spread(input: Bitmap): Double {
                    val ys =
                        (80..820 step 20).map { x ->
                            (515..565).minBy { y -> Color.red(input.getPixel(x, y)) }.toDouble()
                        }
                    val average = ys.average()
                    return sqrt(ys.map { (it - average).pow(2) }.average())
                }
                assertTrue(
                    "Known curved rule must become straighter",
                    spread(image) < spread(bitmap) * .5,
                )
                assertEquals(bitmap.getPixel(100, 0), image.getPixel(100, 0))
            } finally {
                image.recycle()
            }
            val blank = Mat(600, 400, CvType.CV_8UC1, Scalar(255.0))
            try {
                assertNull(RuledPageRefinement.estimate(blank))
            } finally {
                blank.release()
            }
        } finally {
            source.release()
            corrected.release()
            map.release()
            unused.release()
            bitmap.recycle()
        }
    }

}
