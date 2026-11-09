package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.imaging.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class WorkbookRuleFeedbackTest {
    @Test
    fun strongerSuppressionPreservesLongRulesAndDarkForeground() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val root = File(app.filesDir, "next-feedback-scan").apply { mkdirs() }
        val rgb = Mat(700, 600, CvType.CV_8UC3, Scalar(240.0, 240.0, 240.0))
        val ghosts = rgb.clone()
        val old = Mat()
        val next = Mat()
        val snapshot = ScanConfig.replaceForBenchmark(JSONObject())
        try {
            Imgproc.putText(
                ghosts,
                "REVERSE 123",
                Point(80.0, 110.0),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                1.4,
                Scalar(205.0, 205.0, 205.0),
                2,
            )
            Imgproc.GaussianBlur(ghosts, ghosts, Size(0.0, 0.0), 1.5)
            Core.min(rgb, ghosts, rgb)
            Imgproc.line(
                rgb,
                Point(30.0, 230.0),
                Point(570.0, 230.0),
                Scalar(205.0, 205.0, 205.0),
                2,
            )
            Imgproc.putText(
                rgb,
                "FRONT 456",
                Point(70.0, 390.0),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                1.6,
                Scalar(45.0, 45.0, 45.0),
                2,
            )
            ScanConfig.replaceForBenchmark(
                JSONObject()
                    .put("workbookRuleGain", 0)
                    .put("workbookInkNoise", 10)
                    .put("workbookInkGamma", 1.7)
                    .put("workbookFaintEvidenceFloor", 22)
                    .put("workbookFaintEvidenceHigh", 72)
                    .put("workbookOutputInkFloor", 4)
            )
            EnhancePipeline.apply(rgb, old, "WORKBOOK", .85f)
            ScanConfig.restoreAfterBenchmark(snapshot)
            EnhancePipeline.apply(rgb, next, "WORKBOOK", .85f)
            fun ink(mat: Mat, x1: Int, y1: Int, x2: Int, y2: Int): Double {
                val roi = mat.submat(y1, y2, x1, x2)
                return try {
                    255 - Core.mean(roi).`val`[0]
                } finally {
                    roi.release()
                }
            }
            val oldGhost = ink(old, 60, 60, 440, 125)
            val newGhost = ink(next, 60, 60, 440, 125)
            val oldRule = ink(old, 60, 230, 540, 231)
            val newRule = ink(next, 60, 230, 540, 231)
            val oldFront = ink(old, 60, 340, 420, 405)
            val newFront = ink(next, 60, 340, 420, 405)
            assertTrue("Ghost must be lighter: $oldGhost -> $newGhost", newGhost < oldGhost)
            assertTrue("Ruling must be clearer: $oldRule -> $newRule", newRule > oldRule + 8)
            assertTrue("Dark foreground must remain", newFront >= oldFront * .98)
            File(root, "synthetic-metrics.json")
                .writeText(
                    JSONObject()
                        .put("oldGhostInk", oldGhost)
                        .put("newGhostInk", newGhost)
                        .put("oldRuleInk", oldRule)
                        .put("newRuleInk", newRule)
                        .put("oldFrontInk", oldFront)
                        .put("newFrontInk", newFront)
                        .toString(2)
                )
            fun save(mat: Mat, name: String) {
                val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
                try {
                    Utils.matToBitmap(mat, bitmap)
                    File(root, name).outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            save(rgb, "synthetic-input.png")
            save(old, "synthetic-before.png")
            save(next, "synthetic-after.png")
            val previous = File(app.filesDir, "feedback-scan/output")
            for (name in listOf("new-1", "new-2", "new-3", "new-4", "old-1", "old-2", "old-3")) {
                val bitmap =
                    BitmapFactory.decodeFile(File(previous, "$name-crop.png").path)
                        ?: error("Missing private crop $name")
                val rgba = Mat()
                val input = Mat()
                val result = Mat()
                try {
                    Utils.bitmapToMat(bitmap, rgba)
                    Imgproc.cvtColor(rgba, input, Imgproc.COLOR_RGBA2RGB)
                    for (mode in listOf("WORKBOOK", "CLEAN")) {
                        EnhancePipeline.apply(input, result, mode, .85f)
                        save(result, "$name-${mode.lowercase()}.png")
                    }
                } finally {
                    bitmap.recycle()
                    rgba.release()
                    input.release()
                    result.release()
                }
            }
        } finally {
            ScanConfig.restoreAfterBenchmark(snapshot)
            rgb.release()
            ghosts.release()
            old.release()
            next.release()
        }
    }
}
