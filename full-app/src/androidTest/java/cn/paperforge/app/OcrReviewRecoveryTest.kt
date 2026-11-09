package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrReviewRecoveryTest {
    @Test
    fun correctionKeepsRawSourceGeometrySearchAndUndo() = runBlocking {
        val repo = ApplicationProvider.getApplicationContext<PaperForgeApp>().repository
        val doc = repo.newScan()
        try {
            val file = repo.captureFile(doc.id)
            val bitmap =
                Bitmap.createBitmap(500, 700, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.WHITE)
                }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val page =
                PageRow(UUID.randomUUID().toString(), doc.id, 0, file.path, filter = "ORIGINAL")
            repo.dao.putPage(page)
            val corners =
                listOf(PointN(.1f, .1f), PointN(.8f, .1f), PointN(.8f, .2f), PointN(.1f, .2f))
            val original =
                OcrLayout(
                    500,
                    700,
                    listOf(
                        OcrLine(
                            "学号 2026O101",
                            corners,
                            .43f,
                            hypotheses = listOf(OcrHypothesis("学号 20260101", .7f, "alternate")),
                        )
                    ),
                    engineId = "synthetic-cached",
                )
            val recognized = repo.cacheOcr(page, original).first
            val corrected = repo.correctOcrLine(recognized, 0, null, "学号 20260101")
            val layout = OcrLayout.decode(corrected.ocrLayout!!)
            assertTrue(layout.lines.single().corrected)
            assertEquals(corners, layout.lines.single().corners)
            assertTrue(OcrConfidence.queue(layout, .6f, true).isEmpty())
            assertTrue(OcrRecords.versions(corrected).any { it.text.contains("2026O101") })
            assertTrue(repo.dao.search("20260101").first().any { it.id == doc.id })
            repo.undo(doc.id)
            assertEquals("学号 2026O101", repo.dao.pages(doc.id).single().ocrText)
            repo.undo(doc.id, true)
            assertEquals("学号 20260101", repo.dao.pages(doc.id).single().ocrText)
        } finally {
            repo.deleteDocument(doc)
        }
    }
}
