package cn.paperforge.app

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.imaging.LearnedDocumentDetector
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat

/**
 * The opt-in detector engine PAGENET_V2 runs the second page network (assets/pagenet/pagenet-v2.onnx) through the
 * same pipeline as HYBRID, while the default engine keeps the production network.
 */
@RunWith(AndroidJUnit4::class)
class PageNetV2EngineTest {
    private val expected =
        Quad(listOf(PointN(.2425f, .08f), PointN(.76f, .09666667f), PointN(.84f, .91f), PointN(.18f, .98333335f)))

    private fun detect(name: String, engine: String?, preview: Boolean) =
        InstrumentationRegistry.getInstrumentation().context.assets.open("scan-core/$name").use {
            val image = BitmapFactory.decodeStream(it)!!
            val mat = Mat()
            try {
                Utils.bitmapToMat(image, mat)
                LearnedDocumentDetector.detect(mat, preview = preview, engineOverride = engine)
            } finally {
                mat.release()
                image.recycle()
            }
        }

    @Test
    fun optInEngineUsesTheSecondNetworkAndFindsThePage() {
        for (name in listOf("dark-desk-0.jpg", "white-desk-0.jpg", "clutter-0.jpg")) {
            for (preview in listOf(true, false)) {
                val v2 = detect(name, "PAGENET_V2", preview)
                assertTrue("$name preview=$preview: expected the v2 network, got ${v2.method}", v2.method.startsWith("pagenet2"))
                val q = v2.quad
                assertNotNull("$name preview=$preview: v2 found no page (${v2.method})", q)
                val error = q!!.points.zip(expected.points).maxOf { (a, b) -> kotlin.math.hypot(a.x - b.x, a.y - b.y) }
                assertTrue("$name preview=$preview: v2 corners ${q.encode()} off by $error", error < .07f)
            }
        }
    }

    @Test
    fun defaultEngineKeepsTheProductionNetwork() {
        val standard = detect("dark-desk-0.jpg", "HYBRID", false)
        assertTrue("HYBRID must use the production network, got ${standard.method}",
            standard.method.startsWith("pagenet") && !standard.method.startsWith("pagenet2"))
    }
}
