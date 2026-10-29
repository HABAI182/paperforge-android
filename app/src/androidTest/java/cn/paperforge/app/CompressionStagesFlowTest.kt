package cn.paperforge.app

import android.graphics.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class CompressionStagesFlowTest {
    @Test
    fun interruptedImagesAndQualitySearchResumeWithoutReencoding() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val database = Room.inMemoryDatabaseBuilder(app, PaperDatabase::class.java).build()
            val repo = Repository(app, database)
            val proof = File(app.filesDir, "compression-stages-proof").apply { mkdirs() }
            val source = File(proof, "source.pdf")
            PDDocument().use { pdf ->
                val random = java.util.Random(2910)
                val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(
                    IntArray(800000) {
                        Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                    },
                    0,
                    800,
                    0,
                    0,
                    800,
                    1000,
                )
                bitmap.setHasAlpha(false)
                val rgb = LosslessFactory.createFromImage(pdf, bitmap)
                val gray = PdfImageEncoder.jpeg(pdf, bitmap, true, 99, 300)
                bitmap.recycle()
                repeat(3) { index ->
                    val page = PDPage()
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { c ->
                        c.drawImage(if (index == 1) gray else rgb, 70f, 140f, 240f, 300f)
                        c.beginText()
                        c.setFont(PDType1Font.HELVETICA, 20f)
                        c.newLineAtOffset(60f, 710f)
                        c.showText("KEEP_NATIVE_00${index+1}")
                        c.endText()
                    }
                }
                pdf.save(source)
            }
            val hash = PdfAssembly.sha256(source)
            val row =
                DocumentJobs.prepare(repo, "PDF", JobInput(JSONObject().put("mode", "compress")))
            var handle = JobHandle(row, JobInput(JSONObject(row.payload).getJSONObject("input")))
            suspend fun resume(): JobFileStages {
                val latest =
                    checkNotNull(repo.dao.task(row.id))
                        .copy(workId = UUID.randomUUID().toString(), state = LocalTaskState.RUNNING)
                repo.dao.putTask(latest)
                handle =
                    JobHandle(latest, JobInput(JSONObject(latest.payload).getJSONObject("input")))
                return JobFileStages(repo, handle)
            }
            fun stats(stage: JobFileStages) =
                JSONObject()
                    .put("reused", stage.reused)
                    .put("generated", stage.generated)
                    .put("entries", stage.entries)
            try {
                val first = JobFileStages(repo, handle)
                var stopped = false
                try {
                    PdfImageOptimizer.compress(source, File(proof, "interrupted.pdf"), 75, first) {
                        if (first.generated > 0 && it < 100)
                            throw CancellationException("after persisted image")
                    }
                } catch (_: CancellationException) {
                    stopped = true
                }
                assertTrue(stopped)
                assertEquals(1, first.generated)
                val next = resume()
                val resumed = File(proof, "resumed.pdf")
                PdfImageOptimizer.compress(source, resumed, 75, next)
                assertTrue(next.reused >= 1)
                val cold = File(proof, "cold.pdf")
                PdfImageOptimizer.compress(source, cold, 75)
                assertTrue(PdfEngine.text(resumed).contains("KEEP_NATIVE_003"))
                val complete = resume()
                val second = File(proof, "candidate-reused.pdf")
                PdfImageOptimizer.compress(source, second, 75, complete)
                assertEquals(1, complete.reused)
                assertEquals(0, complete.generated)
                assertEquals(PdfAssembly.sha256(resumed), PdfAssembly.sha256(second))
                val searchFirst = resume()
                stopped = false
                try {
                    PdfSizeTarget.compress(
                        source,
                        File(proof, "search-interrupted.pdf"),
                        1,
                        searchFirst,
                    ) {
                        if (it >= 15) throw CancellationException("during second quality")
                    }
                } catch (_: CancellationException) {
                    stopped = true
                }
                assertTrue(stopped)
                val search = resume()
                val target =
                    PdfSizeTarget.compress(source, File(proof, "search-resumed.pdf"), 1, search)
                assertTrue(search.reused >= 1)
                assertFalse(target.getBoolean("targetAchieved"))
                assertEquals(hash, PdfAssembly.sha256(source))
                fun images(file: File): JSONArray =
                    PDDocument.load(file).use { pdf ->
                        val result = JSONArray()
                        for (n in 0 until pdf.numberOfPages) {
                            val resources = pdf.getPage(n).resources
                            val rows = JSONArray()
                            for (name in resources.xObjectNames) {
                                val image =
                                    resources.getXObject(name) as? PDImageXObject ?: continue
                                val digest = java.security.MessageDigest.getInstance("SHA-256")
                                image.cosObject.createRawInputStream().use { input ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        digest.update(buffer, 0, count)
                                    }
                                }
                                rows.put(
                                    JSONObject()
                                        .put("width", image.width)
                                        .put("height", image.height)
                                        .put("colorSpace", image.colorSpace.name)
                                        .put(
                                            "sha256",
                                            digest.digest().joinToString("") { "%02x".format(it) },
                                        )
                                )
                            }
                            result.put(JSONObject().put("page", n).put("images", rows))
                        }
                        result
                    }
                val coldImages = images(cold)
                val resumedImages = images(resumed)
                assertEquals(coldImages.toString(), resumedImages.toString())
                File(proof, "proof.json")
                    .writeText(
                        JSONObject()
                            .put("sourceSha256", hash)
                            .put("sourceUnchanged", true)
                            .put("imageAfterNewWorkId", stats(next))
                            .put("wholeQualityAfterNewWorkId", stats(complete))
                            .put("targetAfterNewWorkId", stats(search))
                            .put("targetReport", target)
                            .put("coldImages", coldImages)
                            .put("resumedImages", resumedImages)
                            .toString(2)
                    )
            } finally {
                database.close()
            }
        }
}
