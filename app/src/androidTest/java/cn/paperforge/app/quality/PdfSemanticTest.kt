package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfSemanticTest {
    @Test
    fun nestedImageCompressionPreservesNativeStreamsLinksAndForm() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val root = File(app.filesDir, "quality-semantics").apply { mkdirs() }
        val source = File(root, "mixed-original.pdf")
        val output = File(root, "mixed-compressed.pdf")
        PDDocument().use { doc ->
            val bitmap = Bitmap.createBitmap(2000, 2600, Bitmap.Config.ARGB_8888)
            val random = java.util.Random(20261023)
            val row = IntArray(2000)
            for (y in 0 until 2600) {
                for (x in row.indices) row[x] =
                    Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                bitmap.setPixels(row, 0, 2000, 0, y, 2000, 1)
            }
            val image =
                try {
                    PdfImageEncoder.jpeg(doc, bitmap, false, 98, 600)
                } finally {
                    bitmap.recycle()
                }
            val inner =
                PDFormXObject(doc).apply {
                    resources = PDResources()
                    bBox = PDRectangle(200f, 260f)
                }
            inner.cosObject.createOutputStream(COSName.FLATE_DECODE).use { stream ->
                PDPageContentStream(doc, inner, stream).use {
                    it.drawImage(image, 0f, 0f, 200f, 260f)
                }
            }
            val outer =
                PDFormXObject(doc).apply {
                    resources = PDResources()
                    bBox = PDRectangle(200f, 260f)
                }
            outer.cosObject.createOutputStream(COSName.FLATE_DECODE).use { stream ->
                PDPageContentStream(doc, outer, stream).use { it.drawForm(inner) }
            }
            repeat(2) { n ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 18f)
                    stream.newLineAtOffset(35f, 790f)
                    stream.showText("NATIVE TEXT PAGE ${n+1}")
                    stream.endText()
                    stream.moveTo(35f, 760f)
                    stream.lineTo(530f, 760f)
                    stream.stroke()
                    stream.saveGraphicsState()
                    stream.transform(Matrix.getTranslateInstance(40f, 350f))
                    if (n == 1) stream.transform(Matrix.getScaleInstance(.5f, .5f))
                    stream.drawForm(outer)
                    stream.restoreGraphicsState()
                }
                page.annotations.add(
                    PDAnnotationLink().apply {
                        rectangle = PDRectangle(30f, 760f, 250f, 40f)
                        action = PDActionURI().apply { uri = "https://example.org/paperforge-test" }
                    }
                )
            }
            val form =
                PDAcroForm(doc).apply {
                    defaultResources =
                        PDResources().apply { put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
                    defaultAppearance = "/F1 12 Tf 0 g"
                }
            doc.documentCatalog.acroForm = form
            val field =
                PDTextField(form).apply {
                    partialName = "owner"
                    defaultAppearance = "/F1 12 Tf 0 g"
                }
            form.fields.add(field)
            field.widgets
                .first()
                .apply {
                    rectangle = PDRectangle(40f, 100f, 180f, 30f)
                    page = doc.getPage(0)
                }
                .also { doc.getPage(0).annotations.add(it) }
            field.value = "Owner value"
            doc.documentInformation.title = "Semantic preservation fixture"
            doc.save(source)
        }
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
        val contentHashes =
            PDDocument.load(source).use { d -> d.pages.map { hash(it.contents.readBytes()) } }
        val report = PdfImageOptimizer.compress(source, output, 75)
        assertTrue("No nested image was reduced: $report", output.length() < source.length() / 3)
        PDDocument.load(output).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertTrue(PDFTextStripper().getText(doc).contains("NATIVE TEXT PAGE 1"))
            assertEquals(contentHashes, doc.pages.map { hash(it.contents.readBytes()) })
            assertEquals(
                "Owner value",
                doc.documentCatalog.acroForm.getField("owner").valueAsString,
            )
            assertEquals(
                "https://example.org/paperforge-test",
                ((doc.getPage(0).annotations.filterIsInstance<PDAnnotationLink>().single()).action
                        as PDActionURI)
                    .uri,
            )
            assertEquals("Semantic preservation fixture", doc.documentInformation.title)
        }
        val plain = File(root, "text-only.pdf")
        val plainOut = File(root, "text-only-compressed.pdf")
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { s ->
                s.beginText()
                s.setFont(PDType1Font.HELVETICA, 12f)
                s.newLineAtOffset(20f, 500f)
                s.showText("SELECTABLE NATIVE TEXT")
                s.endText()
            }
            doc.save(plain)
        }
        PdfImageOptimizer.compress(plain, plainOut, 75)
        assertTrue(PdfEngine.text(plainOut).contains("SELECTABLE NATIVE TEXT"))
        assertTrue(plainOut.length() <= plain.length())
    }
}
