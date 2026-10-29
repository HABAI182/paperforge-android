package cn.paperforge.app

import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.pdf.*
import cn.paperforge.app.ui.PdfSession
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class SensitiveCandidatesFlowTest {
    @Test
    fun nativeAndScannedCandidatesStayReadOnlyAndMapBackToThePage() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val proof = File(app.filesDir, "sensitive-candidates-proof").apply { mkdirs() }
            val native = File(proof, "source.pdf")
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle(600f, 600f))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { s ->
                    s.beginText()
                    s.setFont(PDType1Font.HELVETICA, 18f)
                    s.newLineAtOffset(35f, 550f)
                    listOf(
                            "Email: contact@example.invalid",
                            "Phone: 18800000000",
                            "ID: 990000200001010011",
                            "Card: 4111 1111 1111 1111",
                            "Keep: 00123 12345678",
                        )
                        .forEach {
                            s.showText(it)
                            s.newLineAtOffset(0f, -50f)
                        }
                    s.endText()
                }
                doc.save(native)
            }
            val nativeHash = PdfAssembly.sha256(native)
            val blocks = ReaderDocument.text(native, 0)
            val found = SensitiveTextCandidates.find(blocks)
            val expected = setOf("邮箱", "手机号", "身份证格式", "银行卡格式")
            assertEquals(expected, found.map { it.kind }.toSet())
            assertEquals(4, found.size)
            assertTrue(found.all { it.precise && it.left < it.right && it.top < it.bottom })
            found.forEach { candidate ->
                val line = blocks.single { it.text.contains(candidate.text) }
                assertTrue(candidate.left > line.left)
                assertEquals(1, PdfSearch.find(native, candidate.text).size)
            }
            val fullwidth =
                SensitiveTextCandidates.find(
                    listOf(ReaderTextBlock("电话：１８８００００００００", .1f, .1f, .9f, .2f))
                )
            assertEquals("１８８００００００００", fullwidth.single().text)
            assertFalse(fullwidth.single().precise)
            val scanned = File(proof, "scanned.pdf")
            val image = PdfSession(native).use { it.render(0, 1800) }
            try {
                PDDocument().use { doc ->
                    val page = PDPage(PDRectangle(600f, 600f))
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { s ->
                        s.drawImage(
                            JPEGFactory.createFromImage(doc, image, .96f),
                            0f,
                            0f,
                            600f,
                            600f,
                        )
                    }
                    doc.save(scanned)
                }
            } finally {
                image.recycle()
            }
            assertTrue(ReaderDocument.text(scanned, 0).isEmpty())
            val scannedHash = PdfAssembly.sha256(scanned)
            val recognized = PageTextSource.load(app, scanned, 0, app.ocrEngine.snapshot())
            assertTrue(recognized.isNotEmpty() && recognized.none { it.source == "native" })
            val scannedFound = SensitiveTextCandidates.find(recognized)
            assertTrue(
                "Phone and email must work with on-device OCR",
                scannedFound.map { it.kind }.containsAll(setOf("手机号", "邮箱")),
            )
            assertEquals(nativeHash, PdfAssembly.sha256(native))
            assertEquals(scannedHash, PdfAssembly.sha256(scanned))
            fun json(items: List<SensitiveTextCandidates.Candidate>) =
                JSONArray(
                    items.map {
                        JSONObject()
                            .put("kind", it.kind)
                            .put("text", it.text)
                            .put("source", it.source)
                            .put("box", JSONArray(listOf(it.left, it.top, it.right, it.bottom)))
                            .put("unitMapped", it.precise)
                    }
                )
            File(proof, "proof.json")
                .writeText(
                    JSONObject()
                        .put("native", json(found))
                        .put("scannedOcr", json(scannedFound))
                        .put("originalsUnchanged", true)
                        .put("fullwidthPreservesOriginalText", true)
                        .put("automaticRedaction", false)
                        .toString(2)
                )
        }
}
