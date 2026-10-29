package cn.paperforge.app

import android.graphics.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeEditStagesFlowTest {
    @Test
    fun subsetFontAndStreamPatchesResumeAndRetainSharedOriginalResources() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val database = Room.inMemoryDatabaseBuilder(app, PaperDatabase::class.java).build()
            val repo = Repository(app, database)
            val proof = File(app.filesDir, "native-stages-proof").apply { mkdirs() }
            val input = File(proof, "source.pdf")
            PDDocument().use { pdf ->
                val shared = PDResources()
                val bitmap = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
                val random = java.util.Random(29101)
                bitmap.setPixels(
                    IntArray(1024 * 768) {
                        Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                    },
                    0,
                    1024,
                    0,
                    0,
                    1024,
                    768,
                )
                bitmap.setHasAlpha(false)
                val image = LosslessFactory.createFromImage(pdf, bitmap)
                bitmap.recycle()
                repeat(3) { number ->
                    val page = PDPage()
                    page.resources = shared
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { c ->
                        c.drawImage(image, 40f, 80f, 300f, 225f)
                        c.beginText()
                        c.setFont(PDType1Font.HELVETICA, 20f)
                        c.newLineAtOffset(40f, 700f)
                        c.showText("BEFORE_${number+1}")
                        c.showText(" ANCHOR_${number+1}")
                        c.endText()
                    }
                }
                pdf.getPage(0)
                    .annotations
                    .add(
                        PDAnnotationLink().apply {
                            rectangle =
                                com.tom_roush.pdfbox.pdmodel.common.PDRectangle(
                                    20f,
                                    720f,
                                    100f,
                                    20f,
                                )
                            action =
                                PDActionURI().apply { uri = "https://example.test/native-stage" }
                        }
                    )
                pdf.save(input)
            }
            val hash = PdfAssembly.sha256(input)
            val edits = JSONArray()
            for (number in 0..1) {
                val original =
                    NativePdfEditor.inspect(input, number).second.first {
                        it.text == "BEFORE_${number+1}"
                    }
                edits.put(
                    NativePdfEditor.Edit(
                            number,
                            original.index,
                            original.text,
                            if (number == 0) "第一页甲" else "第二页乙",
                            font = "CJK",
                        )
                        .json()
                )
            }
            val request = JSONObject().put("sourceSha256", hash).put("edits", edits)
            val task =
                DocumentJobs.prepare(repo, "PDF", JobInput(JSONObject().put("mode", "native-edit")))
            var handle = JobHandle(task, JobInput(JSONObject(task.payload).getJSONObject("input")))
            suspend fun resume(): JobFileStages {
                val row =
                    checkNotNull(repo.dao.task(task.id))
                        .copy(workId = UUID.randomUUID().toString(), state = LocalTaskState.RUNNING)
                repo.dao.putTask(row)
                handle = JobHandle(row, JobInput(JSONObject(row.payload).getJSONObject("input")))
                return JobFileStages(repo, handle)
            }
            try {
                val cold = File(proof, "cold.pdf")
                NativePdfEditor.apply(app, input, cold, request)
                val first = JobFileStages(repo, handle)
                var stopped = false
                try {
                    NativePdfEditor.apply(
                        app,
                        input,
                        File(proof, "interrupted.pdf"),
                        request,
                        stages = first,
                    ) {
                        if (it < 90) throw CancellationException("after persisted native page")
                    }
                } catch (_: CancellationException) {
                    stopped = true
                }
                assertTrue(stopped)
                assertEquals(1, first.generated)
                val next = resume()
                val result = File(proof, "resumed.pdf")
                val report = NativePdfEditor.apply(app, input, result, request, stages = next)
                assertEquals(1, next.reused)
                assertEquals(1, next.generated)
                val text = PdfEngine.text(result).filterNot(Char::isWhitespace)
                listOf("第一页甲", "第二页乙", "BEFORE_3", "ANCHOR_1", "ANCHOR_2").forEach {
                    assertTrue("Missing $it: $text", text.contains(it))
                }
                assertEquals(PdfEngine.text(cold).filterNot(Char::isWhitespace), text)
                assertEquals(hash, PdfAssembly.sha256(input))
                PDDocument.load(result).use { pdf ->
                    assertEquals(3, pdf.numberOfPages)
                    assertEquals(
                        "https://example.test/native-stage",
                        ((pdf.getPage(0).annotations.single() as PDAnnotationLink).action
                                as PDActionURI)
                            .uri,
                    )
                }
                val checkpoint =
                    JSONObject(checkNotNull(repo.dao.task(task.id)).payload)
                        .getJSONObject("checkpoint")
                val bytes =
                    checkpoint
                        .keys()
                        .asSequence()
                        .mapNotNull { checkpoint.optJSONObject(it) }
                        .filter { it.optString("key").startsWith("native-edit-page-") }
                        .sumOf { it.getLong("bytes") }
                assertTrue("Patch should not duplicate shared bitmap", bytes < input.length() / 4)
                File(proof, "proof.json")
                    .writeText(
                        JSONObject()
                            .put("sourceSha256", hash)
                            .put("sourceBytes", input.length())
                            .put("sourceUnchanged", true)
                            .put("patchBytes", bytes)
                            .put("reused", next.reused)
                            .put("generated", next.generated)
                            .put("report", report)
                            .toString(2)
                    )
            } finally {
                database.close()
            }
        }
}
