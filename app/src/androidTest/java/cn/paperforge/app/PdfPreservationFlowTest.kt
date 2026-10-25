package cn.paperforge.app

import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.*
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.*
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PdfPreservationFlowTest {
    @Test
    fun selectionDuplicationAndLegacyEntrypointsKeepEditableFieldsAndNativeSemantics() =
        runBlocking {
            val app =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                    as PaperForgeApp
            val folder = File(app.cacheDir, "preserve-${UUID.randomUUID()}").apply { mkdirs() }
            val first = File(folder, "first.pdf")
            val second = File(folder, "second.pdf")
            fun dest(page: PDPage, type: String = "XYZ"): PDPageDestination {
                val array =
                    COSArray().apply {
                        add(page)
                        add(COSName.getPDFName(type))
                        add(COSInteger.get(30))
                        if (type == "XYZ") {
                            add(COSInteger.get(420))
                            add(COSFloat(1.5f))
                        }
                    }
                return PDDestination.create(array) as PDPageDestination
            }
            fun source(file: File, label: String, count: Int) {
                PDDocument().use { pdf ->
                    repeat(count) { index ->
                        val page = PDPage(PDRectangle.A4).also(pdf::addPage)
                        PDPageContentStream(pdf, page).use { s ->
                            s.beginText()
                            s.setFont(PDType1Font.HELVETICA, 14f)
                            s.newLineAtOffset(40f, 760f)
                            s.showText(
                                if (index == 2) "HIDDEN_PAGE_9876" else "$label PAGE ${index+1}"
                            )
                            s.endText()
                        }
                    }
                    val labels = PDPageLabels(pdf)
                    labels.setLabelItem(
                        0,
                        PDPageLabelRange().apply {
                            prefix = "$label-"
                            style =
                                if (label == "A") PDPageLabelRange.STYLE_DECIMAL
                                else PDPageLabelRange.STYLE_ROMAN_LOWER
                        },
                    )
                    pdf.documentCatalog.pageLabels = labels
                    val form = PDAcroForm(pdf)
                    pdf.documentCatalog.acroForm = form
                    form.defaultResources =
                        PDResources().apply {
                            put(
                                COSName.getPDFName("Helv"),
                                if (label == "A") PDType1Font.HELVETICA else PDType1Font.TIMES_BOLD,
                            )
                        }
                    form.defaultAppearance = "/Helv 12 Tf 0 g"
                    form.q = if (label == "A") 1 else 2
                    val root = PDNonTerminalField(form).apply { partialName = "party" }
                    form.fields.add(root)
                    val kids = COSArray()
                    root.cosObject.setItem(COSName.KIDS, kids)
                    fun text(name: String, page: Int, value: String) {
                        val field =
                            PDTextField(form).apply {
                                partialName = name
                                defaultAppearance = "/Helv 12 Tf 0 g"
                            }
                        field.cosObject.setItem(COSName.PARENT, root)
                        kids.add(field)
                        field.widgets.single().apply {
                            rectangle = PDRectangle(40f, 600f - page * 40f, 220f, 25f)
                            this.page = pdf.getPage(page)
                            pdf.getPage(page).annotations.add(this)
                        }
                        field.value = value
                        if (name == "amount")
                            field.cosObject.setItem(
                                COSName.AA,
                                COSDictionary().apply {
                                    setItem(
                                        COSName.F,
                                        COSDictionary().apply {
                                            setName(COSName.S, "JavaScript")
                                            setString(
                                                COSName.JS,
                                                "event.value=this.getField('party.note').value;",
                                            )
                                        },
                                    )
                                },
                            )
                    }
                    text("amount", 0, if (label == "A") "00123" else "00999")
                    text("note", 1, "$label note")
                    if (label == "A") text("secret", 2, "HIDDEN_FORM_9876")
                    else {
                        val box = PDCheckBox(form).apply { partialName = "checked" }
                        box.cosObject.setItem(COSName.PARENT, root)
                        kids.add(box)
                        val normal = COSDictionary()
                        for (state in listOf("Off", "Yes")) {
                            val appearance =
                                PDAppearanceStream(pdf).apply {
                                    bBox = PDRectangle(20f, 20f)
                                    resources = PDResources()
                                }
                            PDPageContentStream(pdf, appearance).use { s ->
                                s.addRect(1f, 1f, 18f, 18f)
                                s.stroke()
                                if (state == "Yes") {
                                    s.moveTo(3f, 10f)
                                    s.lineTo(8f, 4f)
                                    s.lineTo(17f, 17f)
                                    s.stroke()
                                }
                            }
                            normal.setItem(COSName.getPDFName(state), appearance)
                        }
                        box.widgets.single().apply {
                            rectangle = PDRectangle(40f, 480f, 20f, 20f)
                            page = pdf.getPage(1)
                            cosObject.setItem(
                                COSName.AP,
                                COSDictionary().apply { setItem(COSName.N, normal) },
                            )
                            pdf.getPage(1).annotations.add(this)
                        }
                        box.check()
                    }
                    val outline = PDDocumentOutline()
                    pdf.documentCatalog.documentOutline = outline
                    outline.addLast(
                        PDOutlineItem().apply {
                            title = "$label chapter one"
                            setDestination(dest(pdf.getPage(0)))
                        }
                    )
                    outline.addLast(
                        PDOutlineItem().apply {
                            title = "$label chapter two"
                            setDestination(dest(pdf.getPage(1), "FitH"))
                        }
                    )
                    if (label == "A")
                        outline.addLast(
                            PDOutlineItem().apply {
                                title = "HIDDEN_OUTLINE"
                                setDestination(pdf.getPage(2))
                            }
                        )
                    val names = PDDocumentNameDictionary(pdf.documentCatalog)
                    names.dests =
                        PDDestinationNameTreeNode().apply {
                            this.names = mapOf("section" to dest(pdf.getPage(1)))
                        }
                    fun attachment(name: String, text: String) =
                        PDComplexFileSpecification().apply {
                            this.file = name
                            fileUnicode = name
                            val embedded =
                                PDEmbeddedFile(pdf, text.byteInputStream()).apply {
                                    subtype = "text/plain"
                                }
                            embeddedFile = embedded
                            embeddedFileUnicode = embedded
                        }
                    names.embeddedFiles =
                        PDEmbeddedFilesNameTreeNode().apply {
                            this.names =
                                mapOf("note.txt" to attachment("note.txt", "$label attachment"))
                        }
                    pdf.documentCatalog.names = names
                    if (label == "A")
                        pdf.documentCatalog.cosObject.setItem(
                            COSName.getPDFName("AF"),
                            COSArray().apply {
                                add(attachment("invoice.xml", "<invoice>00123</invoice>"))
                            },
                        )
                    pdf.getPage(0)
                        .annotations
                        .add(
                            PDAnnotationLink().apply {
                                rectangle = PDRectangle(40f, 700f, 150f, 20f)
                                destination = PDNamedDestination("section")
                            }
                        )
                    pdf.save(file)
                }
            }
            try {
                source(first, "A", 3)
                source(second, "B", 2)
                val a = AssemblySource("A", "First", 3, PdfAssembly.sha256(first))
                val b = AssemblySource("B", "Second", 2, PdfAssembly.sha256(second))
                val plan =
                    AssemblyPlan(
                        listOf(a, b),
                        listOf(
                            AssemblyPage(sourceId = "A", page = 0),
                            AssemblyPage(sourceId = "B", page = 1),
                            AssemblyPage(sourceId = "A", page = 1),
                            AssemblyPage(sourceId = "B", page = 0),
                            AssemblyPage(sourceId = "A", page = 0),
                        ),
                        title = "Preservation",
                        cover = true,
                    )
                val output = File(folder, "preserved.pdf")
                val report =
                    PdfAssembly.writeFiles(
                        app,
                        AssemblyPlan.decode(plan.json()),
                        mapOf("A" to first, "B" to second),
                        output,
                    )
                assertEquals(6, report.getJSONObject("semantics").getInt("editableFields"))
                PDDocument.load(output).use { pdf ->
                    assertEquals(6, pdf.numberOfPages)
                    assertEquals(
                        listOf("封面", "A-1", "B-ii", "A-2", "B-i", "A-1"),
                        pdf.documentCatalog.pageLabels.labelsByPageIndices.toList(),
                    )
                    val form = pdf.documentCatalog.acroForm
                    assertEquals(
                        setOf(
                            "party.amount",
                            "party.note",
                            "party_copy2.amount",
                            "party_2.amount",
                            "party_2.note",
                            "party_2.checked",
                        ),
                        form.fieldTree
                            .filterIsInstance<PDTerminalField>()
                            .map { it.fullyQualifiedName }
                            .toSet(),
                    )
                    assertEquals("00123", form.getField("party.amount").valueAsString)
                    assertEquals("00999", form.getField("party_2.amount").valueAsString)
                    assertEquals("00123", form.getField("party_copy2.amount").valueAsString)
                    assertEquals("Yes", form.getField("party_2.checked").valueAsString)
                    assertTrue(
                        (form.getField("party_2.amount") as PDVariableText)
                            .defaultAppearance
                            .contains("S2_Helv")
                    )
                    val script =
                        ((form.getField("party_2.amount").cosObject.getDictionaryObject(COSName.AA)
                                    as COSDictionary)
                                .getDictionaryObject(COSName.F) as COSDictionary)
                            .getString(COSName.JS)
                    assertTrue(script.contains("party_2.note"))
                    for (field in form.fieldTree.filterIsInstance<PDTerminalField>()) for (widget in
                        field.widgets) assertTrue(pdf.pages.indexOf(widget.page) >= 0)
                    assertEquals(
                        5,
                        pdf.pages.indexOf(
                            (form.getField("party_copy2.amount") as PDTerminalField)
                                .widgets
                                .single()
                                .page
                        ),
                    )
                    val chapter = pdf.documentCatalog.documentOutline.firstChild.firstChild
                    val target = chapter.destination as PDPageDestination
                    assertEquals(1, pdf.pages.indexOf(target.page))
                    assertEquals("XYZ", (target.cosObject.getObject(1) as COSName).name)
                    assertEquals(420, target.cosObject.getInt(3))
                    assertEquals(
                        1.5f,
                        (target.cosObject.getObject(4) as COSNumber).floatValue(),
                        .001f,
                    )
                    assertNull(chapter.nextSibling.nextSibling)
                    val link =
                        pdf.getPage(1)
                            .annotations
                            .filterIsInstance<PDAnnotationLink>()
                            .single()
                            .destination as PDPageDestination
                    assertEquals(3, pdf.pages.indexOf(link.page))
                    assertEquals(
                        setOf("note.txt", "note (2).txt", "invoice.xml"),
                        pdf.documentCatalog.names.embeddedFiles.names.keys,
                    )
                    assertEquals(2, pdf.documentCatalog.names.dests.names.size)
                    assertEquals(
                        1,
                        (pdf.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("AF"))
                                as COSArray)
                            .size(),
                    )
                    assertFalse(PDFTextStripper().getText(pdf).contains("HIDDEN_PAGE_9876"))
                }
                val edited = File(folder, "edited.pdf")
                PdfTools.fillForms(
                    output,
                    edited,
                    mapOf(
                        "party.amount" to "00456",
                        "party_copy2.amount" to "00789",
                        "party_2.amount" to "00666",
                        "party_2.checked" to "Off",
                    ),
                )
                PDDocument.load(edited).use { pdf ->
                    val form = pdf.documentCatalog.acroForm
                    assertEquals("00456", form.getField("party.amount").valueAsString)
                    assertEquals("00789", form.getField("party_copy2.amount").valueAsString)
                    assertEquals("00666", form.getField("party_2.amount").valueAsString)
                    assertEquals("Off", form.getField("party_2.checked").valueAsString)
                    form.flatten()
                    fun pageText(index: Int) =
                        PDFTextStripper()
                            .apply {
                                startPage = index + 1
                                endPage = index + 1
                            }
                            .getText(pdf)
                    assertTrue(pageText(1).contains("00456"))
                    assertFalse(pageText(1).contains("00789"))
                    assertTrue(pageText(5).contains("00789"))
                    assertFalse(pageText(5).contains("00456"))
                }
                val selected = File(folder, "selected.pdf")
                PdfEngine.extract(first, "2,1", selected, 90)
                PDDocument.load(selected).use { pdf ->
                    assertEquals(2, pdf.numberOfPages)
                    assertEquals(90, pdf.getPage(0).rotation)
                    assertEquals(
                        1,
                        pdf.pages.indexOf(
                            (pdf.documentCatalog.acroForm.getField("party.amount")
                                    as PDTerminalField)
                                .widgets
                                .single()
                                .page
                        ),
                    )
                    assertNull(pdf.documentCatalog.acroForm.getField("party.secret"))
                    assertEquals(
                        listOf("A-2", "A-1"),
                        pdf.documentCatalog.pageLabels.labelsByPageIndices.toList(),
                    )
                }
                val merged = File(folder, "merged.pdf")
                PdfEngine.merge(listOf(first, second), merged)
                PDDocument.load(merged).use { pdf ->
                    assertEquals(5, pdf.numberOfPages)
                    assertNotNull(pdf.documentCatalog.acroForm.getField("party.secret"))
                    assertNotNull(pdf.documentCatalog.acroForm.getField("party_2.amount"))
                    assertEquals(3, pdf.documentCatalog.names.embeddedFiles.names.size)
                }
                val stripped = File(folder, "without-attachments.pdf")
                PdfAssembly.writeFiles(
                    app,
                    plan.copy(
                        cover = false,
                        keepAttachments = false,
                        keepPageLabels = false,
                        keepOriginalOutlines = false,
                    ),
                    mapOf("A" to first, "B" to second),
                    stripped,
                )
                PDDocument.load(stripped).use { pdf ->
                    assertNull(pdf.documentCatalog.names?.embeddedFiles)
                    assertNull(pdf.documentCatalog.pageLabels)
                    assertNull(pdf.documentCatalog.documentOutline.firstChild.firstChild)
                }
                assertEquals(a.sha256, PdfAssembly.sha256(first))
                assertEquals(b.sha256, PdfAssembly.sha256(second))
                output.copyTo(File(app.cacheDir, "preservation-proof.pdf"), overwrite = true)
                edited.copyTo(File(app.cacheDir, "preservation-edited-proof.pdf"), overwrite = true)
                Unit
            } finally {
                folder.listFiles()?.forEach { it.delete() }
                folder.delete()
            }
        }
}
