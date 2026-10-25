package cn.paperforge.app

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OcrRegionFlowTest {
    @Test
    fun regionOnlyNativeOcrPreservesOutsideWordsAndRecordsEdits() = runBlocking {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as PaperForgeApp
        val repo = app.repository
        val doc = repo.newScan()
        val image = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        fun box(left: Float, top: Float, right: Float, bottom: Float) =
            listOf(
                PointN(left, top),
                PointN(right, top),
                PointN(right, bottom),
                PointN(left, bottom),
            )
        try {
            val canvas = Canvas(image)
            canvas.drawColor(Color.WHITE)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 54f
                    typeface = Typeface.MONOSPACE
                }
            canvas.drawText("VALUE 00123", 100f, 650f, paint)
            val file = repo.captureFile(doc.id)
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val page = PageRow(UUID.randomUUID().toString(), doc.id, 0, file.path)
            repo.dao.putPage(page)
            val header =
                OcrLine(
                    "Outside Header",
                    box(.1f, .1f, .9f, .15f),
                    elements =
                        listOf(
                            OcrElement("Outside", box(.1f, .1f, .45f, .15f)),
                            OcrElement("Header", box(.5f, .1f, .9f, .15f)),
                        ),
                )
            val baseline =
                OcrLayout(
                    1200,
                    1600,
                    listOf(
                        header,
                        OcrLine("wrong middle", box(.1f, .38f, .9f, .43f)),
                        OcrLine("Outside Footer", box(.1f, .8f, .9f, .85f)),
                    ),
                )
            var current = repo.cacheOcr(page, baseline).first
            current = repo.correctOcrLine(current, 0, 1, "Kept")
            val corrected = OcrLayout.decode(current.ocrLayout!!)
            assertEquals("Outside Kept", corrected.lines.first().text)
            assertEquals(2, corrected.lines.first().elements.size)
            assertEquals("Kept", corrected.lines.first().elements[1].text)
            val run =
                app.ocrEngine.recognizeRegion(
                    current,
                    OcrRegion(.05f, .3f, .95f, .55f),
                    false,
                    OcrOptions(OcrMode.FAST, false),
                )
            assertEquals(listOf("original"), run.result.attempts)
            assertTrue(run.result.width < run.width && run.result.height < run.height)
            android.util.Log.i(
                "PaperForgeOcrRegion",
                "Native input ${run.result.width}x${run.result.height}; full page ${run.width}x${run.height}",
            )
            assertTrue(run.height * run.region.height < run.height * .3f)
            assertTrue(run.result.text.contains("00123"))
            val merged = repo.applyOcrRegion(current, run)
            val layout = OcrLayout.decode(merged.ocrLayout!!)
            assertTrue(
                layout.text.contains("Outside Kept") && layout.text.contains("Outside Footer")
            )
            assertFalse(layout.text.contains("wrong middle"))
            assertTrue(layout.text.contains("00123"))
            assertEquals(0, layout.orientationCorrection)
            assertTrue(
                OcrRecords.versions(merged).any {
                    it.label.startsWith("选区") && it.text.contains("00123")
                }
            )
            repo.undo(doc.id)
            val undo = repo.dao.pages(doc.id).single()
            assertTrue(undo.ocrText!!.contains("wrong middle"))
            assertTrue(undo.ocrText!!.contains("Outside Kept"))
            val empty =
                OcrRegionMerge.apply(corrected, run.region, run.result.copy(lines = emptyList()))
            assertFalse(empty.canMerge)
            assertEquals(corrected, empty.layout)
            val wordOnly =
                OcrRegionMerge.apply(
                    corrected,
                    OcrRegion(.49f, .09f, .91f, .16f),
                    OcrLayout(100, 100, listOf(OcrLine("New", Quad.Full.points))),
                )
            assertTrue(wordOnly.canMerge)
            assertEquals("Outside New", wordOnly.layout.lines.first().text)
            val clippedWord =
                OcrRegionMerge.apply(
                    corrected,
                    OcrRegion(.1f, .1f, .7f, .15f),
                    OcrLayout(100, 100, listOf(OcrLine("Outside Partial", Quad.Full.points))),
                )
            assertFalse(clippedWord.canMerge)
            assertEquals(corrected, clippedWord.layout)
        } finally {
            image.recycle()
            repo.deleteDocument(doc)
        }
    }
}
