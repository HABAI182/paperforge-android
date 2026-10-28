package cn.paperforge.app

import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import java.io.*
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.*
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class CertificatesFormsFlowTest {
    @Test
    fun realKeystoreCmsTypedFormsIncrementalRevisionAndQueuedSigning() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val folder =
                File(app.filesDir, "certificate-form-proof-${UUID.randomUUID()}").apply { mkdirs() }
            val keysBefore = CertificateVault.list().map { it.alias }.toSet()
            var job: LocalTaskRow? = null
            var ownedDoc: DocumentRow? = null
            var outputDoc: DocumentRow? = null
            try {
                val original = File(folder, "original.pdf")
                fixture(original)
                val originalHash = PdfAssembly.sha256(original)
                val local = CertificateVault.create("PaperForge Device Proof", 2)
                val ec = importEc()
                assertTrue(ec.validNow)
                fun options(cert: CertificateVault.Identity, sha: String, field: String = "") =
                    JSONObject()
                        .put("sourceSha256", sha)
                        .put("alias", cert.alias)
                        .put("certificateSha256", cert.fingerprint)
                        .put("reason", "Personal offline proof")
                        .put("location", "Android emulator")
                        .put("field", field)
                val signed = File(folder, "signed.pdf")
                val firstReport =
                    PdfCertificates.sign(
                        original,
                        signed,
                        options(local, originalHash, "certificate"),
                    )
                val first = firstReport.getJSONArray("signatures").getJSONObject(0)
                assertEquals("valid", first.getString("integrity"))
                assertTrue(first.getBoolean("coversCurrentFile"))
                assertEquals(
                    "local-private-key",
                    first.getJSONArray("signers").getJSONObject(0).getString("trust"),
                )
                assertEquals(originalHash, PdfAssembly.sha256(original))
                assertTrue(
                    signed
                        .readBytes()
                        .take(original.length().toInt())
                        .toByteArray()
                        .contentEquals(original.readBytes())
                )
                PDDocument.load(signed).use { doc ->
                    assertEquals(
                        240f,
                        doc.documentCatalog.acroForm
                            .getField("certificate")
                            .widgets
                            .first()
                            .rectangle
                            .width,
                        .01f,
                    )
                    assertEquals(
                        "READ_ONLY",
                        doc.documentCatalog.acroForm.getField("readonly").valueAsString,
                    )
                }
                val tampered = File(folder, "tampered.pdf")
                val bytes = signed.readBytes()
                val marker = "BYTE_INTEGRITY_ORIGINAL".toByteArray()
                val offset =
                    bytes.indices.first { i ->
                        i + marker.size <= bytes.size &&
                            bytes.copyOfRange(i, i + marker.size).contentEquals(marker)
                    }
                bytes[offset] = 'X'.code.toByte()
                tampered.writeBytes(bytes)
                val tamperReport = PdfCertificates.verify(tampered)
                assertEquals(
                    "invalid",
                    tamperReport.getJSONArray("signatures").getJSONObject(0).getString("integrity"),
                )

                val info = PdfForms.inspect(signed)
                assertEquals(
                    setOf("text", "checkbox", "radio", "choice", "signature"),
                    info.fields.map { it.kind }.toSet(),
                )
                val formRequest =
                    PdfForms.request(
                        info,
                        mapOf(
                            "name" to listOf("中文表单 Alice"),
                            "checked" to listOf("Yes"),
                            "radio" to listOf("Right"),
                            "single" to listOf("B"),
                            "multiple" to listOf("A", "C"),
                            "readonly" to listOf("SHOULD_NOT_CHANGE"),
                        ),
                        false,
                    )
                assertFalse(formRequest.getJSONObject("values").has("readonly"))
                val filled = File(folder, "filled.pdf")
                val fillReport = PdfForms.fill(app, signed, filled, formRequest)
                val filledInfo = PdfForms.inspect(filled)
                assertEquals(
                    listOf("中文表单 Alice"),
                    filledInfo.fields.first { it.name == "name" }.values,
                )
                assertEquals(listOf("Yes"), filledInfo.fields.first { it.name == "checked" }.values)
                assertEquals(listOf("Right"), filledInfo.fields.first { it.name == "radio" }.values)
                assertEquals(listOf("B"), filledInfo.fields.first { it.name == "single" }.values)
                assertEquals(
                    listOf("A", "C"),
                    filledInfo.fields.first { it.name == "multiple" }.values,
                )
                assertEquals(
                    listOf("READ_ONLY"),
                    filledInfo.fields.first { it.name == "readonly" }.values,
                )
                val historical =
                    PdfCertificates.verify(filled).getJSONArray("signatures").getJSONObject(0)
                assertEquals("valid", historical.getString("integrity"))
                assertFalse(historical.getBoolean("coversCurrentFile"))

                // Actual WorkManager integration signs the filled revision using imported EC key
                // material.
                val doc = repo.registerPdf(filled, "表单证书验收")
                ownedDoc = doc
                val row =
                    DocumentJobs.prepare(
                        repo,
                        "PDF",
                        JobInput(
                            JSONObject()
                                .put("document", doc.id)
                                .put("mode", "certificate-sign")
                                .put("options", options(ec, PdfAssembly.sha256(filled)).toString())
                        ),
                    )
                job = row
                DocumentJobs.schedule(repo, row)
                val result =
                    withTimeout(30000) {
                        WorkManager.getInstance(app)
                            .getWorkInfoByIdFlow(UUID.fromString(row.workId))
                            .filterNotNull()
                            .first { it.state.isFinished }
                    }
                assertEquals(result.outputData.toString(), WorkInfo.State.SUCCEEDED, result.state)
                val actual = File(result.outputData.getString("path")!!)
                outputDoc = repo.dao.documentByPath(actual.path)
                actual.copyTo(File(folder, "queued-signed.pdf"))
                val finalReport = PdfCertificates.verify(actual)
                val signatures = finalReport.getJSONArray("signatures")
                assertEquals(2, signatures.length())
                assertEquals("valid", signatures.getJSONObject(0).getString("integrity"))
                assertFalse(signatures.getJSONObject(0).getBoolean("coversCurrentFile"))
                assertEquals("valid", signatures.getJSONObject(1).getString("integrity"))
                assertTrue(signatures.getJSONObject(1).getBoolean("coversCurrentFile"))
                File(folder, "device-public.cer").writeBytes(local.certificate.encoded)
                File(folder, "imported-public.cer").writeBytes(ec.certificate.encoded)
                File(folder, "proof.json")
                    .writeText(
                        JSONObject()
                            .put("originalSha256", originalHash)
                            .put("first", firstReport)
                            .put("tampered", tamperReport)
                            .put("formFill", fillReport)
                            .put("final", finalReport)
                            .put("workManager", result.state.name)
                            .put("originalPrefixPreserved", true)
                            .put(
                                "formValues",
                                JSONObject(
                                    filledInfo.fields.associate { it.name to JSONArray(it.values) }
                                ),
                            )
                            .toString(2)
                    )
                File(app.filesDir, "certificate-form-latest.txt").writeText(folder.name)
                // Keep a clearly named authored form for checking the real application interface.
                val ui = File(folder, "ui-form.pdf")
                original.copyTo(ui)
                val demo = repo.registerPdf(ui, "高级表单与证书 · 验收样例")
                File(folder, "ui-document.json")
                    .writeText(JSONObject().put("id", demo.id).toString())
            } finally {
                job?.let { DocumentJobs.forget(repo, it.id) }
                outputDoc?.let { repo.deleteDocument(it) }
                ownedDoc?.let { repo.deleteDocument(it) }
                CertificateVault.list()
                    .filter { it.alias !in keysBefore }
                    .forEach { CertificateVault.remove(it.alias) }
            }
        }

    private fun importEc(): CertificateVault.Identity {
        val bc = CertificateVault.provider
        val pair =
            KeyPairGenerator.getInstance("EC", bc).apply { initialize(256) }.generateKeyPair()
        val subject = X500Name("CN=PaperForge Imported EC Proof")
        val cert =
            JcaX509CertificateConverter()
                .setProvider(bc)
                .getCertificate(
                    JcaX509v3CertificateBuilder(
                            subject,
                            BigInteger.valueOf(System.currentTimeMillis()),
                            Date(System.currentTimeMillis() - 60000),
                            Date(System.currentTimeMillis() + 86400000),
                            subject,
                            pair.public,
                        )
                        .build(
                            JcaContentSignerBuilder("SHA256withECDSA")
                                .setProvider(bc)
                                .build(pair.private)
                        )
                )
        val password = "proof-only".toCharArray()
        val p12 =
            KeyStore.getInstance("PKCS12", bc).apply {
                load(null, password)
                setKeyEntry("proof", pair.private, password, arrayOf(cert))
            }
        val bytes =
            ByteArrayOutputStream().use { out ->
                p12.store(out, password)
                out.toByteArray()
            }
        return ByteArrayInputStream(bytes)
            .use { CertificateVault.import(it, password).single() }
            .also { bytes.fill(0) }
    }

    private fun fixture(output: File) {
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(500f, 720f))
            pdf.addPage(page)
            pdf.documentInformation.title = "BYTE_INTEGRITY_ORIGINAL"
            PDPageContentStream(pdf, page).use { s ->
                s.setNonStrokingColor(.1f, .4f, .7f)
                s.addRect(32f, 645f, 435f, 40f)
                s.fill()
                s.setNonStrokingColor(1f, 1f, 1f)
                s.beginText()
                s.setFont(PDType1Font.HELVETICA, 18f)
                s.newLineAtOffset(44f, 660f)
                s.showText("CERTIFICATE AND FORM")
                s.endText()
            }
            val form = PDAcroForm(pdf)
            pdf.documentCatalog.acroForm = form
            form.defaultResources =
                PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 12 Tf 0 g"
            fun attach(field: PDTerminalField, y: Float, width: Float = 240f) {
                form.fields.add(field)
                field.widgets.first().apply {
                    rectangle = PDRectangle(45f, y, width, 28f)
                    this.page = page
                    isPrinted = true
                    page.annotations.add(this)
                }
            }
            val text =
                PDTextField(form).apply {
                    partialName = "name"
                    alternateFieldName = "姓名"
                    isRequired = true
                }
            attach(text, 590f)
            text.value = "ORIGINAL"
            val readonly = PDTextField(form).apply { partialName = "readonly" }
            attach(readonly, 540f)
            readonly.value = "READ_ONLY"
            readonly.isReadOnly = true
            fun states(widget: PDAnnotationWidget, on: String) {
                val normal = COSDictionary()
                listOf("Off", on).forEach { state ->
                    val appearance =
                        PDAppearanceStream(pdf).apply {
                            bBox = PDRectangle(24f, 24f)
                            resources = PDResources()
                        }
                    PDPageContentStream(pdf, appearance).use { s ->
                        s.setStrokingColor(0f, 0f, 0f)
                        s.addRect(1f, 1f, 22f, 22f)
                        s.stroke()
                        if (state == on) {
                            s.moveTo(4f, 4f)
                            s.lineTo(20f, 20f)
                            s.moveTo(20f, 4f)
                            s.lineTo(4f, 20f)
                            s.stroke()
                        }
                    }
                    normal.setItem(COSName.getPDFName(state), appearance)
                }
                widget.cosObject.setItem(
                    COSName.AP,
                    COSDictionary().apply { setItem(COSName.N, normal) },
                )
            }
            val box = PDCheckBox(form).apply { partialName = "checked" }
            attach(box, 485f, 28f)
            states(box.widgets.first(), "Yes")
            box.unCheck()
            val radio = PDRadioButton(form).apply { partialName = "radio" }
            val widgets =
                listOf("Left", "Right").mapIndexed { i, on ->
                    PDAnnotationWidget().apply {
                        rectangle = PDRectangle(45f + i * 60, 430f, 24f, 24f)
                        this.page = page
                        setParent(radio)
                        states(this, on)
                        page.annotations.add(this)
                    }
                }
            radio.widgets = widgets
            form.fields.add(radio)
            radio.value = "Left"
            val single =
                PDComboBox(form).apply {
                    partialName = "single"
                    setOptions(listOf("A", "B", "C"), listOf("Alpha", "Beta", "Gamma"))
                }
            attach(single, 365f)
            single.setValue("A")
            val multi =
                PDListBox(form).apply {
                    partialName = "multiple"
                    isMultiSelect = true
                    options = listOf("A", "B", "C")
                }
            attach(multi, 290f)
            multi.value = listOf("B")
            val signature = PDSignatureField(form).apply { partialName = "certificate" }
            attach(signature, 180f)
            pdf.save(output)
        }
    }
}
