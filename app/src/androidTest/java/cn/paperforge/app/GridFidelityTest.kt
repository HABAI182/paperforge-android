package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.LocalTaskRow
import cn.paperforge.app.imaging.EdgeInkDiagnostics
import cn.paperforge.app.imaging.EnhancePipeline
import cn.paperforge.app.imaging.FlatDocument
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.imaging.ScanProcessor
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Stage-by-stage replay of a gallery import for photos pushed to files/grid-case (the owner's engineering
 * worksheet whose export grew grid lines into blank paper). Each page is imported exactly as the gallery does,
 * then the flatten stages are re-run one at a time so an offline script can tell which stage adds structure:
 *
 *   <name>-1-input.png      the crop the flatten step started from (flat-input)
 *   <name>-2-geometry.png   FlatDocument.geometry only (rule rectify, curved grid, straighten, level)
 *   <name>-3-tone.png       the local WORKBOOK enhancement on the unflattened input
 *   <name>-4-flat.png       the finished flatten output saved by the import
 *   <name>-5-final.png      ScanProcessor.render, as export reads it
 *   stages-<name>/          EnhancePipeline's own stage dumps for -3
 *
 * The test fails when the enhancement invents structure: a dark output pixel (< 128) needs a source pixel within
 * 3 px that is at least 5 % darker than the local paper. Checked for the finished flatten (-4 against -2, same
 * frame) and for the tone step alone (-3 against -1). Before the evidence fix the worksheet had 3,129 such pixels
 * (the grid carried on into blank paper and a ruled line was drawn through a drawing); after it, none.
 */
@RunWith(AndroidJUnit4::class)
class GridFidelityTest {
    private val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private fun save(bitmap: Bitmap, file: File) {
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    /** Dark output pixels with no darkening in the source nearby; see the class comment. */
    private fun unsupportedDark(source: Bitmap, output: Bitmap): Int {
        val s = Mat()
        val o = Mat()
        val paper = Mat()
        val evidence = Mat()
        val dark = Mat()
        try {
            Utils.bitmapToMat(source, s)
            Utils.bitmapToMat(output, o)
            Imgproc.cvtColor(s, s, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(o, o, Imgproc.COLOR_RGBA2GRAY)
            if (o.size() != s.size()) Imgproc.resize(o, o, s.size(), 0.0, 0.0, Imgproc.INTER_AREA)
            val square = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(31.0, 31.0))
            Imgproc.morphologyEx(s, paper, Imgproc.MORPH_CLOSE, square)
            Imgproc.blur(paper, paper, Size(31.0, 31.0))
            Core.multiply(paper, Scalar(0.95), paper)
            Core.compare(s, paper, evidence, Core.CMP_LT)
            Imgproc.dilate(evidence, evidence, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0)))
            Imgproc.threshold(o, dark, 127.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.bitwise_not(evidence, evidence)
            Core.bitwise_and(dark, evidence, dark)
            return Core.countNonZero(dark)
        } finally {
            listOf(s, o, paper, evidence, dark).forEach(Mat::release)
        }
    }

    @Test
    fun replayGridWorksheetStages() = runBlocking {
        val samples = File(app.filesDir, "grid-case").listFiles { f -> f.extension in setOf("jpg", "png") }.orEmpty().sorted()
        assertTrue("no photos in files/grid-case", samples.isNotEmpty())
        val out = File(app.filesDir, "grid-out").apply { deleteRecursively(); mkdirs() }
        UserSettings.set(SettingKeys.Filter, "WORKBOOK")
        val repo = app.repository
        val document = repo.newScan()
        repo.importImages(document.id, samples.map(Uri::fromFile))
        val limit = System.nanoTime() + samples.size * 180_000_000_000L
        while (repo.dao.allTasks().any { it.documentId == document.id && it.state in LocalTaskRow.active }) {
            assertTrue("import did not finish", System.nanoTime() < limit)
            delay(500)
        }
        val pages = repo.dao.pages(document.id)
        val report = JSONObject()
        val failures = mutableListOf<String>()
        for ((index, page) in pages.withIndex()) {
            val name = samples[index].nameWithoutExtension
            val metadata = JSONObject(page.processingMetadata ?: "{}")
            File(out, "$name-metadata.json").writeText(metadata.toString(1))
            File(page.original).copyTo(File(out, "$name-4-flat.png"), overwrite = true)
            val input = repo.folder(page.documentId).listFiles { f -> f.name.endsWith("-flat-input.png") }
                ?.maxByOrNull { it.lastModified() }
            val row = JSONObject().put("method", page.detectionMethod ?: "")
            if (input != null) {
                input.copyTo(File(out, "$name-1-input.png"), overwrite = true)
                val source = BitmapFactory.decodeFile(input.path)!!
                val prepared = FlatDocument.sourcePaper(source)
                val started = System.nanoTime()
                val shape = FlatDocument.geometry(prepared, source)
                row.put("geometryMs", (System.nanoTime() - started) / 1e6).put("algorithm", shape.algorithm)
                    .put("geometryDiagnostics", shape.diagnostics ?: "")
                save(shape.bitmap, File(out, "$name-2-geometry.png"))
                val flat = BitmapFactory.decodeFile(page.original)!!
                val flatUnsupported = unsupportedDark(shape.bitmap, flat)
                row.put("flatUnsupportedDark", flatUnsupported)
                failures += checkUnsupported("$name flatten", flatUnsupported, flat)
                flat.recycle()
                shape.bitmap.recycle()
                prepared.recycle()
                val stages = File(out, "stages-$name").apply { mkdirs() }
                val snapshot = ScanConfig.replaceForBenchmark(JSONObject().put("edgeInkDump", 1))
                val rgba = Mat()
                val rgb = Mat()
                val dst = Mat()
                try {
                    EdgeInkDiagnostics.directory = stages
                    Utils.bitmapToMat(source, rgba)
                    Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                    val toneStarted = System.nanoTime()
                    EnhancePipeline.apply(rgb, dst, "WORKBOOK", 1f)
                    row.put("toneMs", (System.nanoTime() - toneStarted) / 1e6)
                    val tone = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(dst, tone)
                    save(tone, File(out, "$name-3-tone.png"))
                    val toneUnsupported = unsupportedDark(source, tone)
                    row.put("toneUnsupportedDark", toneUnsupported)
                    failures += checkUnsupported("$name tone", toneUnsupported, tone)
                    tone.recycle()
                } finally {
                    EdgeInkDiagnostics.directory = null
                    ScanConfig.restoreAfterBenchmark(snapshot)
                    listOf(rgba, rgb, dst).forEach(Mat::release)
                    source.recycle()
                }
            }
            val final = ScanProcessor.render(page, 2400)
            save(final, File(out, "$name-5-final.png"))
            final.recycle()
            report.put(name, row)
        }
        File(out, "report.json").writeText(report.toString(1))
        assertTrue("dark pixels without source evidence: $failures", failures.isEmpty())
    }

    /** At most 2 per 10,000 pixels: tolerance for resampling at stroke ends, far below a drawn line. */
    private fun checkUnsupported(label: String, count: Int, image: Bitmap): List<String> =
        if (count > image.width.toLong() * image.height / 5000) listOf("$label: $count") else emptyList()
}
