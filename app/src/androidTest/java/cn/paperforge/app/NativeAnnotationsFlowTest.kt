package cn.paperforge.app

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.model.PointN
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAnnotationsFlowTest {
    @Test
    fun nativeTypesEditableRoundtripAndFlattenKeepPageContent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val folder = File(app.cacheDir, "annotations-flow-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(folder, "source.pdf")
            PDDocument().use { pdf ->
                repeat(2) { n ->
                    val page = PDPage(PDRectangle(500f, 700f))
                    page.cropBox = PDRectangle(20f, 30f, 400f, 600f)
                    if (n == 0) page.rotation = 90
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { c ->
                        c.beginText()
                        c.setFont(PDType1Font.HELVETICA, 16f)
                        c.newLineAtOffset(55f, 520f)
                        c.showText("ORIGINAL SELECTABLE PAGE ${n+1}")
                        c.endText()
                    }
                    page.annotations.add(
                        PDAnnotationLink().apply {
                            rectangle = PDRectangle(40f, 450f, 80f, 20f)
                            action = PDActionURI().apply { uri = "https://example.org" }
                        }
                    )
                }
                pdf.save(input)
            }
            val digest = PdfAssembly.sha256(input)
            val marks =
                listOf(
                    PdfMark(
                        0,
                        "highlight",
                        listOf(PointN(.12f, .12f), PointN(.65f, .17f)),
                        Color.YELLOW,
                        text = "标记文字",
                    ),
                    PdfMark(0, "underline", listOf(PointN(.12f, .22f), PointN(.65f, .27f))),
                    PdfMark(0, "strikeout", listOf(PointN(.12f, .32f), PointN(.65f, .37f))),
                    PdfMark(0, "text", listOf(PointN(.12f, .46f)), text = "中文批注"),
                    PdfMark(0, "note", listOf(PointN(.12f, .59f)), text = "保留完整便签\n第二行"),
                    PdfMark(0, "label", listOf(PointN(.35f, .59f)), text = "已审核"),
                    PdfMark(
                        0,
                        "signature",
                        listOf(PointN(.12f, .72f), PointN(.22f, .77f), PointN(.32f, .72f)),
                    ),
                    PdfMark(0, "rect", listOf(PointN(.6f, .7f), PointN(.85f, .9f))),
                    PdfMark(
                        1,
                        "highlight",
                        listOf(PointN(.12f, .15f), PointN(.7f, .2f)),
                        Color.YELLOW,
                    ),
                )
            val editable = File(folder, "editable.pdf")
            PdfMarks.save(app, input, editable, marks, editable = true)
            PDDocument.load(editable).use { pdf ->
                val annotations = pdf.getPage(0).annotations
                assertEquals(
                    setOf(
                        "Link",
                        "Highlight",
                        "Underline",
                        "StrikeOut",
                        "FreeText",
                        "Text",
                        "Stamp",
                        "Ink",
                        "Square",
                    ),
                    annotations.map { it.subtype }.toSet(),
                )
                val highlight =
                    annotations.filterIsInstance<PDAnnotationTextMarkup>().first {
                        it.subtype == "Highlight"
                    }
                assertEquals(8, highlight.quadPoints.size)
                assertEquals(68f, highlight.quadPoints[0], .1f)
                assertEquals(102f, highlight.quadPoints[1], .1f)
                assertEquals(
                    "保留完整便签\n第二行",
                    annotations.filterIsInstance<PDAnnotationText>().single().contents,
                )
                annotations
                    .filterNot { it is PDAnnotationLink }
                    .forEach {
                        assertNotNull(it.appearance.normalAppearance.appearanceStream)
                        assertEquals(pdf.getPage(0).cosObject, it.page.cosObject)
                        assertTrue(it.isPrinted)
                    }
                assertEquals(90, pdf.getPage(0).rotation)
                assertEquals(20f, pdf.getPage(0).cropBox.lowerLeftX, .01f)
            }
            val imported = PdfNativeMarks.read(editable)
            assertEquals(marks, imported)
            val preview = PdfNativeMarks.preview(editable, File(folder, "preview.pdf"))
            PDDocument.load(preview).use { pdf ->
                assertEquals(listOf("Link"), pdf.getPage(0).annotations.map { it.subtype })
            }
            val changed =
                marks
                    .filterNot { it.kind == "rect" }
                    .map {
                        if (it.kind == "text") it.copy(text = "修改后的批注", color = Color.BLUE) else it
                    }
            val edited = File(folder, "edited.pdf")
            PdfMarks.save(app, editable, edited, changed, editable = true)
            assertEquals(changed, PdfNativeMarks.read(edited))
            PDDocument.load(edited).use { pdf -> assertEquals(8, pdf.getPage(0).annotations.size) }
            val flat = File(folder, "flat.pdf")
            PdfMarks.save(app, edited, flat, changed, editable = false)
            PDDocument.load(flat).use { pdf ->
                assertEquals(listOf("Link"), pdf.getPage(0).annotations.map { it.subtype })
                assertNull(
                    pdf.getPage(0)
                        .annotations
                        .single()
                        .cosObject
                        .getString(COSName.getPDFName("PFMark"))
                )
            }
            val text = PdfEngine.text(flat).replace(Regex("\\s+"), "")
            assertTrue(text.contains("ORIGINALSELECTABLEPAGE1"))
            assertTrue(text.contains("修改后的批注"))
            val cleared = File(folder, "cleared.pdf")
            PdfMarks.save(app, edited, cleared, emptyList(), editable = true)
            assertTrue(PdfNativeMarks.read(cleared).isEmpty())
            assertEquals(digest, PdfAssembly.sha256(input))
            editable.copyTo(File(app.cacheDir, "native-annotations-proof.pdf"), overwrite = true)
            flat.copyTo(File(app.cacheDir, "native-annotations-flat-proof.pdf"), overwrite = true)
            Unit
        } finally {
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
    }
}
