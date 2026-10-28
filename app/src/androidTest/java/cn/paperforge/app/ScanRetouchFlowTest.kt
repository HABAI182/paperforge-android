package cn.paperforge.app

import android.graphics.*
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.*
import cn.paperforge.app.pdf.*
import java.io.File
import java.util.UUID
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanRetouchFlowTest {
    private val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
    private val proof = File(app.filesDir, "retouch-proof").apply { mkdirs() }

    private fun save(name: String, bitmap: Bitmap) =
        File(proof, "$name.png").apply {
            outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }

    private fun pixels(bitmap: Bitmap) =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun photo(): Bitmap =
        InstrumentationRegistry.getInstrumentation().context.assets.open("retouch/sudoku.png").use {
            BitmapFactory.decodeStream(it)!!
        }

    @Test
    fun learnedRepairUsesSelectionAndNeverChangesUnselectedPixels() = runBlocking {
        val imported = File(app.filesDir, "retouch-proof-input/migan.onnx")
        assertTrue("Push the pinned MI-GAN model before this focused check", imported.isFile)
        RetouchModels.install(app, Uri.fromFile(imported))
        val bitmap = Bitmap.createBitmap(384, 512, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(240, 236, 220))
            val pen =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 22f
                }
            drawText("PAPERFORGE 012345", 20f, 60f, pen)
            pen.color = Color.rgb(115, 68, 42)
            drawRoundRect(300f, 170f, 383f, 350f, 32f, 32f, pen)
            pen.color = Color.BLUE
            pen.strokeWidth = 4f
            drawLine(50f, 240f, 250f, 300f, pen)
        }
        save("repair-source", bitmap)
        val stroke = RetouchStroke(listOf(RetouchPoint(.94f, .34f), RetouchPoint(.94f, .68f)), .17f)
        val mask = ScanRetouch.mask(bitmap.width, bitmap.height, listOf(stroke))
        val selected = pixels(mask)
        mask.recycle()
        val before = pixels(bitmap)
        val results = JSONArray()
        for (mode in listOf("REPAIR", "MIGAN", "DENOISE", "GLARE")) {
            val started = System.nanoTime()
            val output = ScanRetouch.apply(app, bitmap, RetouchRequest(mode, 1f, listOf(stroke)))
            val after = pixels(output)
            assertEquals(
                0,
                before.indices.count { (selected[it] and 255) == 0 && before[it] != after[it] },
            )
            val changed = before.indices.count { before[it] != after[it] }
            assertTrue("$mode should produce actual changed selected pixels", changed > 0)
            if (mode == "MIGAN")
                assertTrue(
                    "The selected simulated obstruction should be filled from paper context",
                    (output.getPixel(340, 250) shr 16 and 255) > 170,
                )
            results.put(
                JSONObject()
                    .put("mode", mode)
                    .put("changed", changed)
                    .put("unselectedChanges", 0)
                    .put("milliseconds", (System.nanoTime() - started) / 1000000)
            )
            save("repair-$mode", output)
            output.recycle()
        }
        val ink =
            ScanRetouch.apply(
                app,
                bitmap,
                RetouchRequest(
                    "INK",
                    1f,
                    listOf(
                        RetouchStroke(
                            listOf(RetouchPoint(.1f, .46f), RetouchPoint(.7f, .61f)),
                            .08f,
                        )
                    ),
                ),
            )
        assertEquals(before[40 * bitmap.width + 30], ink.getPixel(30, 40))
        save("repair-INK", ink)
        ink.recycle()
        File(proof, "repair.json")
            .writeText(
                JSONObject()
                    .put("models", results)
                    .put("miganSha256", RetouchModels.MIGAN_SHA)
                    .toString(2)
            )
        bitmap.recycle()
    }

    @Test
    fun photographedDigitsAndNoisyRegionsHaveActualModelOutput() = runBlocking {
        val original = photo()
        val small = Bitmap.createScaledBitmap(original, 360, 360, true)
        val clean = pixels(small)
        val random = java.util.Random(91)
        val noisy =
            Bitmap.createBitmap(
                IntArray(clean.size) { i ->
                    val c = clean[i]
                    val noise = (random.nextGaussian() * 8).roundToInt()
                    Color.rgb(
                        ((c shr 16 and 255) + noise).coerceIn(0, 255),
                        ((c shr 8 and 255) + noise).coerceIn(0, 255),
                        ((c and 255) + noise).coerceIn(0, 255),
                    )
                },
                360,
                360,
                Bitmap.Config.ARGB_8888,
            )
        val denoised = ScanRetouch.apply(app, noisy, RetouchRequest("DENOISE"))
        fun mse(a: IntArray, b: IntArray) =
            a.indices.sumOf { i ->
                (0..2).sumOf { axis ->
                    val difference = (a[i] shr (axis * 8) and 255) - (b[i] shr (axis * 8) and 255)
                    difference.toDouble() * difference
                }
            } / (a.size * 3)
        val before = mse(clean, pixels(noisy))
        val after = mse(clean, pixels(denoised))
        assertTrue(
            "Denoising should reduce seeded noise against the photographed source",
            after < before,
        )
        val glare = ScanRetouch.apply(app, small, RetouchRequest("GLARE", .65f))
        val sr = ScanRetouch.apply(app, small, RetouchRequest("SR", .7f))
        assertEquals(1080, sr.width)
        assertEquals(1080, sr.height)
        val interpolation = Bitmap.createScaledBitmap(small, 1080, 1080, true)
        val modelPixels = pixels(sr)
        val baseline = pixels(interpolation)
        val changed = modelPixels.indices.count { modelPixels[it] != baseline[it] }
        assertTrue(changed > modelPixels.size / 10)
        save("photo-source", small)
        save("photo-noisy", noisy)
        save("photo-denoised", denoised)
        save("photo-glare", glare)
        save("photo-sr", sr)
        save("photo-interpolation", interpolation)
        File(proof, "photo.json")
            .writeText(
                JSONObject()
                    .put("source", "OpenCV 4.12.0 samples/data/sudoku.png")
                    .put("seededNoiseMse", before)
                    .put("denoisedMse", after)
                    .put("srChangesAgainstInterpolation", changed)
                    .put("srSha256", RetouchModels.SR_SHA)
                    .put("ownerPhonePhoto", false)
                    .toString(2)
            )
        listOf(original, small, noisy, denoised, glare, sr, interpolation).distinct().forEach {
            it.recycle()
        }
    }

    @Test
    fun draftAndQueuedCopyKeepOriginalAndUseDurableResult() = runBlocking {
        val repo = app.repository
        val source = repo.newScan()
        val image = photo()
        val path = File(repo.folder(source.id), "source.png")
        path.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val page =
            PageRow(UUID.randomUUID().toString(), source.id, 0, path.path, filter = "ORIGINAL")
        repo.dao.putPage(page)
        val key = RetouchDrafts.sourceKey(page)
        val hash = PdfAssembly.sha256(path)
        val request =
            RetouchRequest(
                "DENOISE",
                .65f,
                listOf(RetouchStroke(listOf(RetouchPoint(.2f, .2f), RetouchPoint(.4f, .4f)), .05f)),
            )
        RetouchDrafts.save(repo, page, key, request)
        val current = repo.dao.pages(source.id).single()
        assertEquals(
            request.json().toString(),
            RetouchDrafts.load(current, key)!!.json().toString(),
        )
        assertTrue(PageResources.paths(current).any { it.endsWith(".json") })
        val id = RetouchJobs.enqueue(repo, current, request)
        val work =
            withTimeout(120000) {
                WorkManager.getInstance(app).getWorkInfoByIdFlow(id).filterNotNull().first {
                    it.state.isFinished
                }
            }
        assertEquals(work.outputData.toString(), WorkInfo.State.SUCCEEDED, work.state)
        val task = repo.dao.observeTasks().first().single { it.workId == id.toString() }
        val documentId = RetouchJobs.publishedDocument(task)
        val copy = repo.dao.pages(documentId).single()
        assertEquals(hash, PdfAssembly.sha256(path))
        assertEquals(current, repo.dao.pages(source.id).single())
        assertNotEquals(source.id, documentId)
        assertNotEquals(path.path, copy.original)
        assertEquals(source.id, copy.origins().single().documentId)
        assertTrue(
            PageResources.entries(copy).any { it.role == "retouch-source" && File(it.path).isFile }
        )
        assertTrue(JSONObject(task.payload).getJSONObject("checkpoint").has("retouch-image"))
        val remapped = DocumentJobs.remapPayload(task.payload, mapOf(documentId to "restored-copy"))
        assertEquals("restored-copy", RetouchJobs.publishedDocument(task.copy(payload = remapped)))
        val stages = JSONArray()
        val cached =
            RetouchJobs.run(
                repo,
                JobHandle(task, JobInput(JSONObject(task.payload).getJSONObject("input"))),
            ) { _, detail ->
                stages.put(detail)
            }
        assertTrue(stages.toString().contains("复用"))
        assertEquals(work.outputData.getString("path"), cached.path)
        File(proof, "job.json")
            .writeText(
                JSONObject()
                    .put("sourceDocument", source.id)
                    .put("copyDocument", documentId)
                    .put("taskId", task.id)
                    .put("sourceSha256", hash)
                    .put("sourceUnchanged", true)
                    .put("draftRetained", true)
                    .put("resumedFromCheckpoint", true)
                    .put("resultDocumentRemappable", true)
                    .toString(2)
            )
    }
}
