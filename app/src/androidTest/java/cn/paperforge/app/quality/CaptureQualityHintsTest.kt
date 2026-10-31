package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.imaging.CameraQualityAnalyzer
import cn.paperforge.app.model.CaptureHint
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * #21 post-capture hints. Synthetic images only: they prove the classifier reacts to blur, glare and
 * a cut-off sheet, and that a clean page produces no hint. Real phone photos are still required for
 * the published acceptance note.
 */
@RunWith(AndroidJUnit4::class)
class CaptureQualityHintsTest {
    private val page =
        Quad(
            listOf(
                PointN(.05f, .05f),
                PointN(.95f, .05f),
                PointN(.95f, .95f),
                PointN(.05f, .95f),
            )
        )

    private fun sheet(glare: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(242, 242, 238))
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 34f
            }
        for (row in 0 until 30)
            canvas.drawText("PaperForge document detail 0123456789", 70f, 110f + row * 48, paint)
        if (glare)
            canvas.drawRect(
                400f,
                500f,
                800f,
                900f,
                Paint().apply { color = Color.WHITE },
            )
        return bitmap
    }

    private fun blurred(source: Bitmap): Bitmap {
        val src = Mat()
        val dst = Mat()
        try {
            Utils.bitmapToMat(source, src)
            Imgproc.GaussianBlur(src, dst, Size(31.0, 31.0), 9.0)
            return Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(dst, it)
            }
        } finally {
            src.release()
            dst.release()
        }
    }

    @Test
    fun postCaptureClassifiesBlurGlareAndEdgeCut() {
        val root =
            File(ApplicationProvider.getApplicationContext<PaperForgeApp>().filesDir, "capture-hints")
                .apply { mkdirs() }
        val sharp = sheet(false)
        val glare = sheet(true)
        val soft = blurred(sharp)
        val cut =
            Quad(
                listOf(
                    PointN(.005f, .10f),
                    PointN(.995f, .10f),
                    PointN(.995f, .90f),
                    PointN(.005f, .90f),
                )
            )
        try {
            val clean = CameraQualityAnalyzer.postCapture(sharp, page)
            val blurHints = CameraQualityAnalyzer.postCapture(soft, page)
            val glareHints = CameraQualityAnalyzer.postCapture(glare, page)
            val cutHints = CameraQualityAnalyzer.postCapture(sharp, cut)
            val metrics =
                JSONObject()
                    .put("synthetic", true)
                    .put("physicalCamera", false)
                    .put("clean", JSONArray(clean.map { it.name }))
                    .put("blur", JSONArray(blurHints.map { it.name }))
                    .put("glare", JSONArray(glareHints.map { it.name }))
                    .put("cut", JSONArray(cutHints.map { it.name }))
            File(root, "hints.json").writeText(metrics.toString(2))
            File(root, "hints-clean.png").outputStream().use {
                sharp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue("clean shot produced $clean", clean.isEmpty())
            assertTrue("blur not detected: $blurHints", CaptureHint.BLUR in blurHints)
            assertTrue("glare not detected: $glareHints", CaptureHint.GLARE in glareHints)
            assertTrue("edge cut not detected: $cutHints", CaptureHint.MISSING_EDGE in cutHints)
            assertFalse("clean shot claimed an edge cut", CaptureHint.MISSING_EDGE in clean)
        } finally {
            sharp.recycle()
            glare.recycle()
            soft.recycle()
        }
    }
}
