package cn.paperforge.app.quality

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.imaging.LearnedDocumentDetector
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LearnedDetectorTest {
    @Test
    fun bundledModelRunsAndCaptureHintSurvivesStillRefinement() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val image = ScanProcessor.decode(File(app.filesDir, "bench/regression-v020.jpg"), 1800)
        try {
            val result = LearnedDocumentDetector.detect(image)
            assertTrue(
                "Model must actually execute offline: ${result.method}",
                result.method.startsWith("docquad-"),
            )
            assertTrue(result.confidence.isFinite())
            assertNotNull(result.quad)
            val hint =
                Quad(
                    listOf(
                        PointN(.085f, .085f),
                        PointN(.90f, .05f),
                        PointN(.955f, .90f),
                        PointN(.115f, .955f),
                    )
                )
            val crop =
                ScanProcessor.detectFile(File(app.filesDir, "bench/regression-v020.jpg"), hint)
            assertEquals("viewfinder+refined", crop.method)
            assertNotNull(crop.quad)
            assertTrue(
                crop.quad!!.points.zip(hint.points).all { (a, b) ->
                    kotlin.math.hypot(a.x - b.x, a.y - b.y) < .026f
                }
            )
        } finally {
            image.recycle()
        }
    }
}
