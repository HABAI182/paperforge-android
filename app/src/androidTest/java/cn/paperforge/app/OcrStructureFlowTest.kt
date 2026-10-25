package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OcrStructureFlowTest {
    private fun quad(l: Float, t: Float, r: Float, b: Float) =
        listOf(PointN(l, t), PointN(r, t), PointN(r, b), PointN(l, b))

    @Test
    fun treeFramesCellsTransformsAndHistoryStayOnTheSamePageRevision() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val doc = repo.newScan()
            try {
                val image = repo.captureFile(doc.id)
                val bitmap =
                    Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                    }
                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val page = PageRow("ir-${doc.id}", doc.id, 0, image.path, filter = "ORIGINAL")
                repo.dao.putPage(page)
                val box = quad(.1f, .2f, .6f, .25f)
                val word = quad(.1f, .2f, .3f, .25f)
                val cells = listOf(OcrCell(0, 0, 1, 2, box, "00123 00077.50", listOf(0)))
                val initial =
                    OcrLayout(
                        600,
                        800,
                        listOf(
                            OcrLine(
                                "00123 00077.50",
                                box,
                                block = 2,
                                paragraph = 3,
                                elements =
                                    listOf(
                                        OcrElement("00123", word),
                                        OcrElement("00077.50", quad(.35f, .2f, .6f, .25f)),
                                    ),
                            )
                        ),
                        engineId = "fixture",
                        sourceRevision = page.revision,
                        orientationCorrection = 90,
                        deskewDegrees = -2f,
                        tables = listOf(OcrTable(1, 2, cells, "fixture-merged")),
                    )
                val (current, layout) = repo.cacheOcr(page, initial)
                assertTrue(layout.matches(current))
                assertEquals(layout, OcrLayout.decode(layout.encode()))
                val ir = OcrStructure.json(layout)
                val line =
                    ir.getJSONArray("blocks")
                        .getJSONObject(0)
                        .getJSONArray("paragraphs")
                        .getJSONObject(0)
                        .getJSONArray("lines")
                        .getJSONObject(0)
                assertEquals(2, line.getJSONArray("words").length())
                assertEquals("line-0", ir.getJSONArray("readingOrder").getString(0))
                assertEquals(
                    layout.lines[0].corners[0].x,
                    line.getJSONArray("corners").getJSONArray(0).getDouble(0).toFloat(),
                    0f,
                )
                assertEquals(
                    2,
                    ir.getJSONArray("tables")
                        .getJSONObject(0)
                        .getJSONArray("cells")
                        .getJSONObject(0)
                        .getInt("columnSpan"),
                )
                val transformedCell = layout.tables[0].cells[0]
                assertEquals(layout.lines[0].corners, transformedCell.corners)
                assertFalse(layout.matches(current.copy(rotation = (current.rotation + 90) % 360)))
                assertFalse(
                    layout.matches(
                        current.copy(
                            processingMetadata =
                                """{"steps":[{"kind":"dewarp","algorithm":"new-grid","strength":0.7,"modelSha256":"changed"}]}"""
                        )
                    )
                )
                val shifted = layout.rotated(90)
                assertNull(shifted.frame)
                assertEquals(shifted.lines[0].corners, shifted.tables[0].cells[0].corners)
                val copied = layout.withRevision(0)
                assertTrue(
                    copied.matches(
                        current.copy(id = "new-page", revision = 0, original = image.path)
                    )
                )
                val corrected = repo.correctOcr(current, "00124 00077.50")
                val edited = OcrLayout.decode(corrected.ocrLayout!!)
                val correctedLine =
                    OcrStructure.json(edited)
                        .getJSONArray("blocks")
                        .getJSONObject(0)
                        .getJSONArray("paragraphs")
                        .getJSONObject(0)
                        .getJSONArray("lines")
                        .getJSONObject(0)
                assertEquals("00124 00077.50", correctedLine.getString("text"))
                assertEquals(0, correctedLine.getJSONArray("words").length())
                assertTrue(edited.tables.isEmpty())
                repo.undo(doc.id)
                val restored = repo.dao.pages(doc.id).single()
                val restoredLayout = OcrLayout.decode(restored.ocrLayout!!)
                assertTrue(restoredLayout.matches(restored))
                assertEquals(layout.tables, restoredLayout.tables)
                val legacy =
                    JSONObject(initial.encode()).apply {
                        remove("ir")
                        put("schema", 3)
                    }
                assertEquals(1, OcrLayout.decode(legacy.toString()).lines.size)
                File(app.cacheDir, "ocr-ir-proof.json").writeText(ir.toString(2))
            } finally {
                repo.deleteDocument(doc)
            }
        }
}
