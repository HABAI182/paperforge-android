package cn.paperforge.app

import android.graphics.*
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class NativePdfEditorFlowTest {
    @Test
    fun replacementsKeepFollowingAnchorsSharedPagesAndPortableFontHistory() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val db = Room.inMemoryDatabaseBuilder(app, PaperDatabase::class.java).build()
            val repo = Repository(app, db)
            val proof =
                File(app.filesDir, "native-edit-proof-${UUID.randomUUID()}").apply { mkdirs() }
            val beforeFonts =
                File(app.filesDir, "native-fonts").listFiles()?.map { it.path }?.toSet().orEmpty()
            var ownedFont: File? = null
            try {
                val input = File(proof, "source.pdf")
                PDDocument().use { pdf ->
                    val page = PDPage(PDRectangle(500f, 700f))
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { stream ->
                        stream.setNonStrokingColor(.1f, .45f, .8f)
                        stream.addRect(30f, 100f, 400f, 80f)
                        stream.fill()
                        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.MAGENTA)
                        stream.drawImage(
                            LosslessFactory.createFromImage(pdf, bitmap),
                            380f,
                            430f,
                            50f,
                            50f,
                        )
                        bitmap.recycle()
                        stream.setNonStrokingColor(0f, 0f, 0f)
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 18f)
                        stream.setLeading(30f)
                        stream.setCharacterSpacing(.5f)
                        stream.setWordSpacing(1.2f)
                        stream.setHorizontalScaling(90f)
                        stream.newLineAtOffset(50f, 625f)
                        stream.showText("ORIGINAL")
                        stream.showText(" RIGHT_FIXED")
                        stream.newLine()
                        stream.showTextWithPositioning(arrayOf("KER", -120f, "NED"))
                        stream.showText(" AFTER_TJ")
                        stream.appendRawCommands("(QUOTE_OLD) '\n3 1 (DOUBLE_OLD) \"\n")
                        stream.showText(" END_FIXED")
                        stream.endText()
                        stream.saveGraphicsState()
                        stream.beginText()
                        stream.setFont(PDType1Font.TIMES_ITALIC, 20f)
                        stream.setTextMatrix(Matrix.getRotateInstance(.18, 70f, 350f))
                        stream.showText("ANGLED_OLD")
                        stream.endText()
                        stream.restoreGraphicsState()
                    }
                    page.annotations.add(
                        PDAnnotationLink().apply {
                            rectangle = PDRectangle(30f, 100f, 80f, 20f)
                            action = PDActionURI().apply { uri = "https://example.org/kept" }
                        }
                    )
                    val rotated =
                        PDPage(PDRectangle(500f, 700f)).apply {
                            cropBox = PDRectangle(20f, 30f, 400f, 600f)
                            rotation = 90
                        }
                    pdf.addPage(rotated)
                    PDPageContentStream(pdf, rotated).use { stream ->
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 18f)
                        stream.newLineAtOffset(55f, 520f)
                        stream.showText("ROTATED_OLD")
                        stream.showText(" ROTATION_FIXED")
                        stream.endText()
                    }
                    val shared =
                        PDPage(PDRectangle(500f, 700f)).apply {
                            setContents(page.contentStreams.next())
                            resources = page.resources
                        }
                    pdf.addPage(shared)
                    val form = PDAcroForm(pdf)
                    form.defaultResources =
                        PDResources().apply {
                            put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
                        }
                    form.defaultAppearance = "/Helv 12 Tf 0 g"
                    val field = PDTextField(form).apply { partialName = "keptField" }
                    form.fields.add(field)
                    pdf.documentCatalog.acroForm = form
                    field.widgets.single().apply {
                        rectangle = PDRectangle(50f, 40f, 180f, 30f)
                        this.page = shared
                        shared.annotations.add(this)
                    }
                    field.value = "FORM_VALUE"
                    pdf.documentInformation.title = "Native PDF editing authored fixture"
                    pdf.save(input)
                }
                input.copyTo(File(proof, "original.pdf"))
                val hash = PdfAssembly.sha256(input)
                val objects = NativePdfEditor.inspect(input, 0).second
                val rotated = NativePdfEditor.inspect(input, 1).second
                assertTrue(objects.first { it.text == "ORIGINAL" }.corners.size == 4)
                assertTrue(
                    rotated
                        .first { it.text == "ROTATED_OLD" }
                        .corners
                        .all { it.x in 0f..1f && it.y in 0f..1f }
                )
                val doc = repo.registerPdf(input, "Native edit fixture")
                val fontInput = File(proof, "import.ttf")
                app.assets.open("fonts/PaperForgeCJK-Regular.ttf").use { from ->
                    fontInput.outputStream().use { from.copyTo(it) }
                }
                val choice = NativeEditFonts.install(repo, doc, Uri.fromFile(fontInput))
                ownedFont = File(choice.path).takeIf { it.path !in beforeFonts }
                val changes =
                    listOf(
                        NativePdfEditor.Edit(
                            0,
                            objects.first { it.text == "ORIGINAL" }.index,
                            "ORIGINAL",
                            "A MUCH LONGER TITLE",
                            fit = true,
                        ),
                        NativePdfEditor.Edit(
                            0,
                            objects.first { it.text == "KERNED" }.index,
                            "KERNED",
                            "排版修改",
                            "CJK",
                            22f,
                            false,
                        ),
                        NativePdfEditor.Edit(
                            0,
                            objects.first { it.text == "QUOTE_OLD" }.index,
                            "QUOTE_OLD",
                            "QUOTE_NEW",
                            fit = false,
                        ),
                        NativePdfEditor.Edit(
                            0,
                            objects.first { it.text == "DOUBLE_OLD" }.index,
                            "DOUBLE_OLD",
                            "DOUBLE_NEW",
                            fit = true,
                        ),
                        NativePdfEditor.Edit(
                            0,
                            objects.first { it.text == "ANGLED_OLD" }.index,
                            "ANGLED_OLD",
                            "倾斜文字",
                            "CJK",
                            20f,
                            true,
                        ),
                        NativePdfEditor.Edit(
                            1,
                            rotated.first { it.text == "ROTATED_OLD" }.index,
                            "ROTATED_OLD",
                            "旋转字体",
                            choice.id,
                            18f,
                            true,
                            choice.sha256,
                        ),
                    )
                var draft = NativeEditDraft(hash).change(changes)
                assertEquals(0, draft.undo().edits.size)
                assertEquals(changes, draft.undo().redo().edits)
                NativeEditDraft.save(repo, doc, draft)
                assertEquals(draft, NativeEditDraft.load(repo, doc, hash))
                val request = draft.request()
                val file = File(repo.folder(doc.id), "native-request.json")
                TableJobs.atomic(file, request)
                DocumentResources.register(repo, doc.id, "native-edit-request", file)
                val task =
                    DocumentJobs.prepare(
                        repo,
                        "PDF",
                        JobInput(
                            JSONObject()
                                .put("document", doc.id)
                                .put("mode", "native-edit")
                                .put("options", JSONObject().put("editFile", file.path).toString())
                        ),
                    )
                val output = File(proof, "edited.pdf")
                val report =
                    NativePdfEditor.apply(
                        app,
                        input,
                        output,
                        request,
                        mapOf(choice.sha256 to File(choice.path)),
                    )
                assertEquals(hash, PdfAssembly.sha256(input))
                val text = PdfEngine.text(output)
                listOf("A MUCH LONGER TITLE", "排版修改", "QUOTE_NEW", "DOUBLE_NEW", "倾斜文字", "旋转字体")
                    .forEach {
                        assertTrue(
                            "Missing $it: $text",
                            text
                                .filterNot(Char::isWhitespace)
                                .contains(it.filterNot(Char::isWhitespace)),
                        )
                    }
                assertTrue(NativePdfEditor.inspect(output, 0).second.any { it.text == "倾斜文字" })
                PDDocument.load(output).use { pdf ->
                    assertEquals(
                        "FORM_VALUE",
                        pdf.documentCatalog.acroForm.getField("keptField").valueAsString,
                    )
                    assertEquals(
                        "Native PDF editing authored fixture",
                        pdf.documentInformation.title,
                    )
                    assertEquals(90, pdf.getPage(1).rotation)
                    assertEquals(400f, pdf.getPage(1).cropBox.width)
                    assertEquals(
                        "https://example.org/kept",
                        (pdf.getPage(0).annotations.single() as PDAnnotationLink).action.let {
                            (it as PDActionURI).uri
                        },
                    )
                    assertEquals(3, pdf.numberOfPages)
                }
                val metrics =
                    JSONObject()
                        .put("report", report)
                        .put("sourceUnchanged", true)
                        .put("text", text)
                        .put(
                            "originalObjects",
                            JSONArray(
                                objects.map { o ->
                                    JSONObject()
                                        .put("index", o.index)
                                        .put("text", o.text)
                                        .put(
                                            "corners",
                                            JSONArray(
                                                o.corners.map { JSONArray(listOf(it.x, it.y)) }
                                            ),
                                        )
                                }
                            ),
                        )
                val backup = File(proof, "native-history-backup.zip")
                BackupStore.backup(repo, backup)
                ownedFont?.delete() // The restored copy must resolve without the original global
                // directory.
                BackupStore.restore(repo, Uri.fromFile(backup), applyWorkspace = false)
                val restored = repo.dao.allDocuments().single { it.id != doc.id }
                draft = NativeEditDraft.load(repo, restored, hash)
                assertEquals(changes, draft.edits)
                assertTrue(draft.canUndo)
                val restoredFont = NativeEditFonts.resolve(repo, restored, choice.sha256)
                assertNotEquals(choice.path, restoredFont.path)
                val restoredTask = repo.dao.allTasks().single { it.id != task.id }
                val source = File(restoredTask.resourceEntries().single { it.id == "source" }.path)
                val restoredRequest =
                    File(restoredTask.resourceEntries().single { it.id == "native-edit" }.path)
                val restoredFiles =
                    restoredTask
                        .resourceEntries()
                        .filter { it.id.startsWith("native-font-") }
                        .associate { it.id.removePrefix("native-font-") to File(it.path) }
                NativePdfEditor.apply(
                    app,
                    source,
                    File(proof, "restored-edited.pdf"),
                    JSONObject(restoredRequest.readText()),
                    restoredFiles,
                )
                assertTrue(PdfEngine.text(File(proof, "restored-edited.pdf")).contains("旋转字体"))
                metrics
                    .put("backupDraftUndoRestored", true)
                    .put("backupFontRelocated", true)
                    .put(
                        "backupRequestContainsPhysicalFontPath",
                        restoredRequest.readText().contains("fontPath"),
                    )
                assertFalse(restoredRequest.readText().contains("fontPath"))
                val demo = File(proof, "ui-source.pdf")
                input.copyTo(demo)
                val demoDoc = app.repository.registerPdf(demo, "原生编辑验收")
                metrics.put("uiDocument", demoDoc.id)
                File(proof, "proof.json").writeText(metrics.toString(2))
                File(app.filesDir, "native-edit-latest.txt").writeText(proof.name)
            } finally {
                for (row in repo.dao.allTasks()) {
                    repo.dao.putTask(row.copy(state = LocalTaskState.CANCELLED))
                    DocumentJobs.forget(repo, row.id)
                }
                for (doc in repo.dao.allDocuments()) repo.deleteDocument(doc)
                ownedFont?.delete()
                db.close()
            }
        }
}
