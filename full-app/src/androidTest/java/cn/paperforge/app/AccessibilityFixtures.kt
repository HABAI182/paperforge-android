package cn.paperforge.app

import android.graphics.*
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.UUID

internal object AccessibilityFixtures {
    suspend fun scan(app: PaperForgeApp): Pair<DocumentRow, PageRow> {
        val repo = app.repository
        val doc = repo.newScan().copy(title = "Accessibility scan fixture", listed = false)
        repo.dao.putDocument(doc)
        val file = File(app.cacheDir, "accessibility-${UUID.randomUUID()}.png")
        val b = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(Color.WHITE)
            drawText(
                "PAPERFORGE OCR 012345",
                40f,
                150f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 32f
                },
            )
        }
        try {
            file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            b.recycle()
        }
        var page = PageRow(UUID.randomUUID().toString(), doc.id, 0, file.path, filter = "ORIGINAL")
        val line =
            OcrLine(
                "ORIGINAL OCR TEXT",
                listOf(
                    PointN(.05f, .12f),
                    PointN(.95f, .12f),
                    PointN(.95f, .24f),
                    PointN(.05f, .24f),
                ),
            )
        val layout =
            OcrLayout(600, 800, listOf(line), frame = OcrFrame.bind(page), sourceRevision = 0)
        page = page.copy(ocrText = layout.text, ocrLayout = layout.encode())
        repo.dao.putPage(page)
        return doc to page
    }

    suspend fun pdf(app: PaperForgeApp): DocumentRow {
        val file = File(app.cacheDir, "accessible-pdf-${UUID.randomUUID()}.pdf")
        PDDocument().use { pdf ->
            repeat(3) { index ->
                val page = PDPage(PDRectangle(400f, 600f))
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use {
                    it.beginText()
                    it.setFont(PDType1Font.HELVETICA, 24f)
                    it.newLineAtOffset(40f, 500f)
                    it.showText("Accessible PDF page ${index+1}")
                    it.endText()
                }
            }
            pdf.save(file)
        }
        return app.repository.registerPdf(file, "Accessibility PDF fixture", listed = false)
    }

    suspend fun remove(app: PaperForgeApp, doc: DocumentRow) {
        val files =
            app.repository.dao.pages(doc.id).map { File(it.original) } +
                listOfNotNull(doc.pdfPath?.let(::File))
        app.repository.dao.deleteDocument(doc.id)
        files.forEach { it.delete() }
    }
}
