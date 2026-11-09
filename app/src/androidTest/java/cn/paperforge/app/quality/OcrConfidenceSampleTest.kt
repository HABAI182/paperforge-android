package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.data.correctOcrLine
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.BundledOcrEngine
import cn.paperforge.app.ocr.OcrMode
import cn.paperforge.app.ocr.OcrOptions
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #24: OCR the owner's device sample with the bundled ML Kit engine, count low-confidence lines and
 * words at the configured threshold, then run the same write-back the proofreading screen uses.
 *
 * The sample stays on the device (`files/samples`), never in the repository. Results are written to
 * `files/ocr-confidence/sample-confidence.json` for the acceptance page.
 */
@RunWith(AndroidJUnit4::class)
class OcrConfidenceSampleTest {
    private val threshold = .6f

    private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap =
        renderer.openPage(index).use { view ->
            val scale = 2000f / maxOf(view.width, view.height)
            val bitmap =
                Bitmap.createBitmap(
                    (view.width * scale).roundToInt().coerceAtLeast(1),
                    (view.height * scale).roundToInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
            bitmap.eraseColor(Color.WHITE)
            view.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            bitmap
        }

    private fun stats(page: Int, layout: OcrLayout): JSONObject {
        val flagged = OcrConfidence.lowLines(layout, threshold)
        val scores = layout.lines.mapNotNull { it.confidence }
        val elementScores = layout.lines.flatMap { line -> line.elements.mapNotNull { it.confidence } }
        val candidates =
            layout.lines.mapNotNull { line ->
                val alternatives =
                    line.hypotheses.map { it.text }.filter { it.isNotBlank() && it != line.text }.distinct()
                if (alternatives.isEmpty()) null
                else JSONObject().put("text", line.text.take(40)).put("candidates", JSONArray(alternatives.take(3)))
            }
        return JSONObject()
            .put("page", page)
            .put("width", layout.width)
            .put("height", layout.height)
            .put("engine", layout.engineId)
            .put("engineVersion", layout.engineVersion)
            .put("recognitionMode", layout.recognitionMode)
            .put("attempts", JSONArray(layout.attempts))
            .put("warnings", JSONArray(layout.warnings))
            .put("lines", layout.lines.size)
            .put("linesWithConfidence", scores.size)
            .put("linesWithoutConfidence", layout.lines.size - scores.size)
            .put("lineConfidenceMin", scores.minOrNull()?.toDouble() ?: JSONObject.NULL)
            .put("lineConfidenceMean", scores.takeIf { it.isNotEmpty() }?.average() ?: JSONObject.NULL)
            .put("lineConfidenceMax", scores.maxOrNull()?.toDouble() ?: JSONObject.NULL)
            .put("lowLinesStrict", layout.lines.indices.count { OcrConfidence.isLow(layout.lines[it].confidence, threshold) })
            .put("lowLinesFlagged", flagged.size)
            .put("lowWordFragments", layout.lines.indices.sumOf { OcrConfidence.lowWords(layout.lines[it], threshold).size })
            .put("elements", layout.lines.sumOf { it.elements.size })
            .put("elementsWithConfidence", elementScores.size)
            .put("linesWithCandidates", candidates.size)
            .put(
                "lowLinesByThreshold",
                JSONObject().apply {
                    listOf(.9f, .8f, .7f, .6f, .5f, .4f, .3f).forEach { value ->
                        put(
                            "${(value * 100).roundToInt()}%",
                            OcrConfidence.lowLines(layout, value).size,
                        )
                    }
                },
            )
            .put(
                "lowLineSamples",
                JSONArray(
                    flagged.take(5).map { index ->
                        JSONObject()
                            .put("index", index)
                            .put("text", layout.lines[index].text.take(40))
                            .put(
                                "confidence",
                                layout.lines[index].confidence?.toDouble() ?: JSONObject.NULL,
                            )
                    }
                ),
            )
            .put("candidateLines", JSONArray(candidates.take(3)))
            .put("textSample", layout.text.take(160))
    }

    @Test
    fun samplePdfExposesConfidenceAndWriteBackKeepsGeometry() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val sample = File(app.filesDir, "samples/local-sample-3.pdf")
        assertTrue("缺少设备端样本", sample.exists())
        val engine = app.ocrEngine.snapshot(BundledOcrEngine.ID)
        val pages = JSONArray()
        var totalLines = 0
        var totalFlagged = 0
        var totalLowWords = 0
        var totalLinesWithConfidence = 0
        var firstLayout: OcrLayout? = null
        var pdfPageCount = 0
        var ocrPages = 0
        ParcelFileDescriptor.open(sample, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                pdfPageCount = renderer.pageCount
                val count = minOf(2, renderer.pageCount)
                for (index in 0 until count) {
                    val bitmap = renderPage(renderer, index)
                    try {
                        val layout =
                            engine.recognizeBitmap(
                                bitmap,
                                true,
                                OcrOptions(OcrMode.BALANCED, true),
                            )
                        val pageStats = stats(index + 1, layout)
                        pages.put(pageStats)
                        totalLines += layout.lines.size
                        totalLinesWithConfidence +=
                            layout.lines.count { it.confidence != null }
                        totalFlagged += OcrConfidence.lowLines(layout, threshold).size
                        totalLowWords +=
                            layout.lines.indices.sumOf {
                                OcrConfidence.lowWords(layout.lines[it], threshold).size
                            }
                        if (firstLayout == null) firstLayout = layout
                        ocrPages++
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
        assertTrue("样本 PDF 没有页面", pdfPageCount > 0)
        assertTrue("没有 OCR 出任何行", totalLines > 0)
        assertTrue("引擎没有返回任何行级置信度，无法做低置信度高亮", totalLinesWithConfidence > 0)

        // Contrast sample: crisp printed latin text rendered in the test. This is synthetic and is
        // labelled as such; it only shows that high-confidence lines stay unflagged.
        val synthetic = Bitmap.createBitmap(1400, 1800, Bitmap.Config.ARGB_8888)
        synthetic.eraseColor(Color.WHITE)
        Canvas(synthetic).apply {
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 48f
                    typeface = Typeface.MONOSPACE
                }
            for (row in 0..7) drawText("PAPERFORGE 00123 ITEM $row", 90f, 140f + row * 76, paint)
        }
        val syntheticLayout =
            engine.recognizeBitmap(synthetic, false, OcrOptions(OcrMode.FAST, false))
        synthetic.recycle()
        assertTrue("对照样张没有 OCR 出文字", syntheticLayout.lines.isNotEmpty())

        // The device page used for write-back keeps the same OCR result on disk.
        val doc = repo.newScan()
        try {
            val file = repo.captureFile(doc.id)
            val bitmap = ParcelFileDescriptor.open(sample, ParcelFileDescriptor.MODE_READ_ONLY)
                .use { descriptor ->
                    PdfRenderer(descriptor).use { renderer -> renderPage(renderer, 0) }
                }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val page = PageRow(UUID.randomUUID().toString(), doc.id, 0, file.path, filter = "ORIGINAL")
            repo.dao.putPage(page)
            val (cachedPage, baseline) = repo.cacheOcr(page, checkNotNull(firstLayout))
            assertTrue("缓存后布局为空", baseline.lines.isNotEmpty())

            val flagged = OcrConfidence.lowLines(baseline, threshold)
            val lineIndex =
                flagged.firstOrNull { baseline.lines[it].elements.isNotEmpty() }
                    ?: baseline.lines.indices.firstOrNull { baseline.lines[it].elements.isNotEmpty() }
                    ?: 0
            val line = baseline.lines[lineIndex]
            val wordIndex = OcrConfidence.lowWords(line, threshold).firstOrNull() ?: 0
            val wordCorrected = repo.correctOcrLine(cachedPage, lineIndex, wordIndex, "校对字")
            val wordLayout = OcrLayout.decode(checkNotNull(wordCorrected.ocrLayout))
            assertEquals(baseline.lines.size, wordLayout.lines.size)
            assertEquals(baseline.width, wordLayout.width)
            assertEquals(baseline.height, wordLayout.height)
            assertEquals(baseline.lines.map { it.corners }, wordLayout.lines.map { it.corners })
            baseline.lines.forEachIndexed { index, old ->
                if (index != lineIndex) assertEquals(old.text, wordLayout.lines[index].text)
            }
            assertEquals("校对字", wordLayout.lines[lineIndex].elements[wordIndex].text)
            assertNull(wordLayout.lines[lineIndex].elements[wordIndex].confidence)
            assertNull(wordLayout.lines[lineIndex].confidence)
            assertTrue(wordLayout.lines[lineIndex].corrected)
            assertEquals(
                baseline.lines[lineIndex].elements[wordIndex].corners,
                wordLayout.lines[lineIndex].elements[wordIndex].corners,
            )
            val raw = JSONObject(wordCorrected.ocrLayout)
            assertEquals(4, raw.getInt("schema"))
            assertTrue(raw.has("ir"))
            assertEquals(baseline.lines.size, raw.getJSONArray("lines").length())
            assertEquals(
                4,
                raw.getJSONArray("lines").getJSONObject(0).getJSONArray("corners").length(),
            )

            val wholeLine = repo.correctOcrLine(
                wordCorrected,
                wordLayout.lines.lastIndex,
                null,
                "整行人工校正",
            )
            val wholeLayout = OcrLayout.decode(checkNotNull(wholeLine.ocrLayout))
            assertEquals(wordLayout.lines.size, wholeLayout.lines.size)
            assertTrue(wholeLayout.lines.last().elements.isEmpty())
            assertTrue(wholeLayout.lines.last().corrected)
            assertEquals(wordLayout.lines.last().corners, wholeLayout.lines.last().corners)
            assertEquals(
                wordLayout.lines.dropLast(1).map { it.text },
                wholeLayout.lines.dropLast(1).map { it.text },
            )

            val proof = File(app.filesDir, "ocr-confidence").apply { mkdirs() }
            val result =
                JSONObject()
                    .put("issue", 24)
                    .put("synthetic", false)
                    .put("ownerSample", "local-sample-3.pdf")
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("engine", engine.id)
                    .put("language", "CHINESE")
                    .put("mode", "BALANCED")
                    .put("threshold", threshold.toDouble())
                    .put("pdfPages", pdfPageCount)
                    .put("ocrPages", ocrPages)
                    .put(
                        "totals",
                        JSONObject()
                            .put("lines", totalLines)
                            .put("linesWithConfidence", totalLinesWithConfidence)
                            .put("lowLinesFlagged", totalFlagged)
                            .put("lowWordFragments", totalLowWords),
                    )
                    .put("pages", pages)
                    .put(
                        "syntheticContrast",
                        JSONObject()
                            .put("synthetic", true)
                            .put("description", "测试内渲染的清晰印刷拉丁文，不是真实照片")
                            .put("engine", syntheticLayout.engineId)
                            .put("threshold", threshold.toDouble())
                            .put("lines", syntheticLayout.lines.size)
                            .put("lowLinesFlagged", OcrConfidence.lowLines(syntheticLayout, threshold).size)
                            .put("page", stats(1, syntheticLayout)),
                    )
                    .put(
                        "writeback",
                        JSONObject()
                            .put("lineIndex", lineIndex)
                            .put("wordIndex", wordIndex)
                            .put("originalLine", line.text.take(80))
                            .put("correctedLine", wordLayout.lines[lineIndex].text.take(80))
                            .put("coordinatesUnchanged", true)
                            .put("schema", raw.getInt("schema"))
                            .put("wholeLineElementsCleared", wholeLayout.lines.last().elements.isEmpty())
                            .put("otherLinesUnchanged", true),
                    )
            File(proof, "sample-confidence.json").writeText(result.toString(2))
        } finally {
            repo.deleteDocument(doc)
        }
    }
}
