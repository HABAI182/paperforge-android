package cn.paperforge.app

import android.graphics.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class RasterStagesFlowTest {
    @Test
    fun interruptedRasterRedactionAndLongImagesReuseCompletedParts() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val database = Room.inMemoryDatabaseBuilder(app, PaperDatabase::class.java).build()
            val repo = Repository(app, database)
            val proof =
                File(app.filesDir, "raster-stages-proof-${UUID.randomUUID()}").apply { mkdirs() }
            val metrics = JSONObject()
            suspend fun job(mode: String): JobHandle {
                val row =
                    DocumentJobs.prepare(repo, "PDF", JobInput(JSONObject().put("mode", mode)))
                return JobHandle(row, JobInput(JSONObject(row.payload).getJSONObject("input")))
            }
            suspend fun resume(handle: JobHandle): JobHandle {
                val row =
                    checkNotNull(repo.dao.task(handle.row.id))
                        .copy(state = LocalTaskState.RUNNING, workId = UUID.randomUUID().toString())
                repo.dao.putTask(row)
                return JobHandle(row, JobInput(JSONObject(row.payload).getJSONObject("input")))
            }
            suspend fun interrupted(action: suspend () -> Unit) {
                var stopped = false
                try {
                    action()
                } catch (_: CancellationException) {
                    stopped = true
                }
                assertTrue(stopped)
            }
            fun counts(stages: JobFileStages) =
                JSONObject().put("reused", stages.reused).put("generated", stages.generated)
            try {
                val source = File(proof, "source.pdf")
                PDDocument().use { document ->
                    repeat(3) { index ->
                        val page = PDPage(PDRectangle(400f, 600f))
                        document.addPage(page)
                        PDPageContentStream(document, page).use { stream ->
                            val rgb = listOf(Color.RED, Color.BLUE, Color.GREEN)[index]
                            stream.setNonStrokingColor(
                                Color.red(rgb) / 255f,
                                Color.green(rgb) / 255f,
                                Color.blue(rgb) / 255f,
                            )
                            stream.addRect(20f, 400f, 360f, 130f)
                            stream.fill()
                            stream.setNonStrokingColor(0f, 0f, 0f)
                            stream.beginText()
                            stream.setFont(PDType1Font.HELVETICA, 30f)
                            stream.newLineAtOffset(30f, 320f)
                            stream.showText("SECRET PAGE ${index+1}")
                            stream.endText()
                        }
                    }
                    document.save(source)
                }
                val sourceHash = PdfAssembly.sha256(source)
                val rasterJob = job("flatten")
                PdfEngine.flatten(source, File(proof, "direct-raster.pdf"), 65)
                val first = JobFileStages(repo, rasterJob)
                interrupted {
                    PdfEngine.flatten(source, File(proof, "interrupted-raster.pdf"), 65, first) {
                        if (it < 100) throw CancellationException("after completed raster page")
                    }
                }
                assertEquals(1, first.generated)
                assertFalse(File(proof, "interrupted-raster.pdf").exists())
                val next = JobFileStages(repo, resume(rasterJob))
                val raster = File(proof, "resumed-raster.pdf")
                PdfEngine.flatten(source, raster, 65, next)
                assertEquals(1, next.reused)
                assertEquals(2, next.generated)
                assertTrue(PdfEngine.text(raster).isBlank())
                metrics.put("rasterAfterNewWorkId", counts(next))

                val options = LongImageOptions(width = 320, gap = 9, segmentHeight = 256)
                val longJob = job("long-image")
                val direct =
                    PdfLongImages.convert(
                        source,
                        File(proof, "direct-pdf-long.zip"),
                        "3,1,2",
                        options,
                    )
                val longFirst = JobFileStages(repo, longJob)
                interrupted {
                    PdfLongImages.convert(
                        source,
                        File(proof, "interrupted-pdf-long.zip"),
                        "3,1,2",
                        options,
                        longFirst,
                    ) {
                        if (it < 100) throw CancellationException("after first source page")
                    }
                }
                assertEquals(1, longFirst.generated)
                val longNext = JobFileStages(repo, resume(longJob))
                val longReport =
                    PdfLongImages.convert(
                        source,
                        File(proof, "resumed-pdf-long.zip"),
                        "3,1,2",
                        options,
                        longNext,
                    )
                assertEquals(1, longNext.reused)
                assertTrue(longReport.getInt("reusedStrips") > 0)
                assertEquals(
                    direct.getJSONArray("sourceMapping").toString(),
                    longReport.getJSONArray("sourceMapping").toString(),
                )
                metrics.put(
                    "pdfLongAfterNewWorkId",
                    counts(longNext)
                        .put("renderedStrips", longReport.getInt("renderedStrips"))
                        .put("reusedStrips", longReport.getInt("reusedStrips")),
                )

                val inputs =
                    (0..2).map { index ->
                        File(proof, "source-$index.png").also { file ->
                            val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(listOf(Color.RED, Color.BLUE, Color.GREEN)[index])
                                Canvas(bitmap)
                                    .drawRect(
                                        30f,
                                        100f,
                                        370f,
                                        180f,
                                        Paint().apply { color = Color.WHITE },
                                    )
                                file.outputStream().use {
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                val names = listOf("A", "B", "C")
                val seams = listOf(StitchSeam(.18f, .04f), StitchSeam(.11f, -.02f))
                ImageLongImages.convert(
                    inputs,
                    names,
                    File(proof, "direct-image-long.zip"),
                    options,
                    seams,
                )
                val imageJob = job("image-long")
                val imageFirst = JobFileStages(repo, imageJob)
                interrupted {
                    ImageLongImages.convert(
                        inputs,
                        names,
                        File(proof, "interrupted-image-long.zip"),
                        options,
                        seams,
                        imageFirst,
                    ) {
                        if (it < 100) throw CancellationException("after first image")
                    }
                }
                val imageNext = JobFileStages(repo, resume(imageJob))
                val imageReport =
                    ImageLongImages.convert(
                        inputs,
                        names,
                        File(proof, "resumed-image-long.zip"),
                        options,
                        seams,
                        imageNext,
                    )
                assertEquals(1, imageNext.reused)
                assertTrue(imageReport.getInt("reusedStrips") > 0)
                metrics.put(
                    "imageLongAfterNewWorkId",
                    counts(imageNext).put("reusedStrips", imageReport.getInt("reusedStrips")),
                )
                for (kind in listOf("pdf", "image")) {
                    ZipFile(File(proof, "direct-$kind-long.zip")).use { before ->
                        ZipFile(File(proof, "resumed-$kind-long.zip")).use { after ->
                            before
                                .entries()
                                .asSequence()
                                .filter { it.name.endsWith(".png") }
                                .forEach { entry ->
                                    assertArrayEquals(
                                        before.getInputStream(entry).readBytes(),
                                        after.getInputStream(after.getEntry(entry.name)).readBytes(),
                                    )
                                }
                        }
                    }
                }

                val redactJob = job("redact-raster")
                PdfRedaction.raster(
                    app,
                    source,
                    File(proof, "direct-redacted.pdf"),
                    1,
                    .1f,
                    .35f,
                    .8f,
                    .65f,
                )
                val redactFirst = JobFileStages(repo, redactJob)
                interrupted {
                    PdfRedaction.raster(
                        app,
                        source,
                        File(proof, "interrupted-redacted.pdf"),
                        1,
                        .1f,
                        .35f,
                        .8f,
                        .65f,
                        redactFirst,
                    ) {
                        if (it < 100)
                            throw CancellationException("after appearance and first redacted page")
                    }
                }
                assertEquals(2, redactFirst.generated)
                val redactNext = JobFileStages(repo, resume(redactJob))
                val redacted = File(proof, "resumed-redacted.pdf")
                PdfRedaction.raster(app, source, redacted, 1, .1f, .35f, .8f, .65f, redactNext)
                assertEquals(2, redactNext.reused)
                assertEquals(2, redactNext.generated)
                assertTrue(PdfEngine.text(redacted).isBlank())
                metrics.put("redactionAfterNewWorkId", counts(redactNext))
                val changed = JobFileStages(repo, resume(redactJob))
                PdfRedaction.raster(
                    app,
                    source,
                    File(proof, "changed-redacted.pdf"),
                    1,
                    .2f,
                    .35f,
                    .8f,
                    .65f,
                    changed,
                )
                assertEquals(3, changed.reused)
                assertEquals(1, changed.generated)
                metrics.put("onePageRedactionChanged", counts(changed))
                assertEquals(sourceHash, PdfAssembly.sha256(source))
                metrics.put("sourceUnchanged", true)
                File(proof, "proof.json").writeText(metrics.toString(2))
                File(app.filesDir, "raster-stages-latest.txt").writeText(proof.name)
                android.util.Log.i("RasterStagesFlowTest", "Proof: ${proof.path}")
            } finally {
                for (row in repo.dao.allTasks()) {
                    repo.dao.putTask(row.copy(state = LocalTaskState.CANCELLED))
                    DocumentJobs.forget(repo, row.id)
                }
                database.close()
            }
        }
}
