package cn.paperforge.app

import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BasicConversionsFlowTest {
    @Test
    fun editableUnicodeTextKeepsOriginalAndUsesChosenPaper() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val source = File(app.cacheDir, "正文排版验收.txt")
            source.writeText(
                (1..130).joinToString("\n") { "中文正文第 $it 行，编号 00123。" },
                Charsets.UTF_16LE,
            )
            source.outputStream().use { stream ->
                stream.write(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
                stream.write(
                    (1..130)
                        .joinToString("\n") { "中文正文第 $it 行，编号 00123。" }
                        .toByteArray(Charsets.UTF_16LE)
                )
            }
            val hash = PdfAssembly.sha256(source)
            val store = ViewModelStore()
            val vm =
                withContext(Dispatchers.Main) {
                    MainViewModel(app).also { store.put("conversion", it) }
                }
            try {
                withContext(Dispatchers.Main) {
                    vm.convertText(Uri.fromFile(source), TextPdfOptions(14f, "A5", true, 18f, 1.4f))
                }
                val draft = withTimeout(10000) { vm.textConversion.filterNotNull().first() }
                assertTrue(draft.chunks.joinToString("").startsWith("中文正文"))
                withContext(Dispatchers.Main) {
                    vm.updateTextConversion(0, draft.chunks.first() + "\n用户追加的结尾正文")
                    vm.generateTextConversion()
                }
                val finished =
                    withTimeout(30000) {
                        repo.dao
                            .observeTasks()
                            .first { list ->
                                list.any {
                                    it.id == draft.taskId && it.state == LocalTaskState.SUCCEEDED
                                }
                            }
                            .single { it.id == draft.taskId }
                    }
                val workInfo =
                    WorkManager.getInstance(app)
                        .getWorkInfoById(UUID.fromString(finished.workId))
                        .get()
                DocumentJobs.reconcile(
                    repo,
                    finished.copy(state = LocalTaskState.RUNNING, progress = 50),
                    workInfo,
                )
                assertEquals(LocalTaskState.SUCCEEDED, repo.dao.task(finished.id)!!.state)
                val result = File(finished.resourceEntries().single { it.id == "result" }.path)
                PDDocument.load(result).use { pdf ->
                    assertTrue(pdf.numberOfPages > 1)
                    assertEquals(PDRectangle.A5.height, pdf.getPage(0).mediaBox.width, .01f)
                    assertEquals(PDRectangle.A5.width, pdf.getPage(0).mediaBox.height, .01f)
                }
                assertTrue(PdfEngine.text(result).contains("用户追加的结尾正文"))
                assertEquals(hash, PdfAssembly.sha256(source))
                val original = File(finished.resourceEntries().single { it.id == "input-0" }.path)
                assertEquals(hash, PdfAssembly.sha256(original))
                val doc = repo.dao.documentByPath(result.path)!!
                assertTrue(DocumentResources.entries(repo, doc.id).any { it.path == original.path })
                result.copyTo(File(app.cacheDir, "text-layout-proof.pdf"), overwrite = true)
                // A separate persisted draft remains for direct UI continuation checking.
                withTimeout(10000) { vm.busy.first { !it } }
                withContext(Dispatchers.Main) {
                    vm.convertText(Uri.fromFile(source), TextPdfOptions())
                }
                withTimeout(10000) { vm.textConversion.filterNotNull().first() }
            } finally {
                withContext(Dispatchers.Main) { store.clear() }
                source.delete()
            }
        }

    @Test
    fun partialImageBatchRetryReusesGoodOutputAndHonorsEncodingOptions() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val folder = File(app.cacheDir, "image-options-${System.nanoTime()}").apply { mkdirs() }
            var rowId: String? = null
            suspend fun finished(id: UUID) =
                withTimeout(30000) {
                    WorkManager.getInstance(app).getWorkInfoByIdFlow(id).filterNotNull().first {
                        it.state.isFinished
                    }
                }
            try {
                val good = File(folder, "alpha.png")
                val bad = File(folder, "broken.png").apply { writeText("not an image") }
                val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
                Canvas(bitmap)
                    .drawRect(200f, 100f, 600f, 300f, Paint().apply { color = Color.BLUE })
                good.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val hash = PdfAssembly.sha256(good)
                val settings = ImageConversionOptions("png", 90, 600, true)
                val id =
                    DocumentJobs.enqueue(
                        repo,
                        "CONVERT",
                        JobInput(
                            JSONObject()
                                .put("kind", "IMAGES")
                                .put("name", "两张图片验收")
                                .put("inputs", JSONArray(listOf(good.path, bad.path)))
                                .put("names", JSONArray(listOf(good.name, bad.name)))
                                .put("options", settings.json().toString())
                        ),
                    )
                assertEquals(WorkInfo.State.SUCCEEDED, finished(id).state)
                val row = repo.dao.allTasks().single { it.workId == id.toString() }
                rowId = row.id
                assertEquals(LocalTaskState.PARTIAL, row.state)
                val firstZip = File(row.resourceEntries().single { it.id == "result" }.path)
                val firstZipHash = PdfAssembly.sha256(firstZip)
                val cached = File(row.resourceEntries().single { it.id == "item-0" }.path)
                val cachedTime = cached.lastModified()
                BitmapFactory.decodeFile(cached.path).let { image ->
                    assertEquals(600, image.width)
                    assertEquals(300, image.height)
                    assertEquals(0, Color.alpha(image.getPixel(0, 0)))
                    image.recycle()
                }
                good.copyTo(bad, overwrite = true)
                val retry = DocumentJobs.retry(repo, row)
                assertEquals(WorkInfo.State.SUCCEEDED, finished(retry).state)
                val complete = repo.dao.task(row.id)!!
                assertEquals(LocalTaskState.SUCCEEDED, complete.state)
                assertEquals(cachedTime, cached.lastModified())
                assertEquals(firstZipHash, PdfAssembly.sha256(firstZip))
                val zip = File(complete.resourceEntries().single { it.id == "result" }.path)
                ZipFile(zip).use { archive ->
                    assertNotNull(archive.getEntry("image-001.png"))
                    assertNotNull(archive.getEntry("image-002.png"))
                    val report =
                        JSONObject(
                            archive
                                .getInputStream(archive.getEntry("conversion-report.json"))
                                .bufferedReader()
                                .readText()
                        )
                    assertTrue(report.getJSONArray("items").getJSONObject(0).getBoolean("reused"))
                }
                val jpg = File(folder, "opaque.jpg")
                LocalConversions.image(
                    app,
                    Uri.fromFile(good),
                    jpg,
                    ImageConversionOptions("jpg", 95, 200, false, Color.MAGENTA),
                )
                BitmapFactory.decodeFile(jpg.path).let { image ->
                    assertEquals(200, image.width)
                    assertEquals(100, image.height)
                    val pixel = image.getPixel(0, 0)
                    assertEquals(255, Color.alpha(pixel))
                    assertTrue(Color.red(pixel) > 240 && Color.blue(pixel) > 240)
                    image.recycle()
                }
                val webp = File(folder, "lossless.webp")
                LocalConversions.image(
                    app,
                    Uri.fromFile(good),
                    webp,
                    ImageConversionOptions("webp", 100, 800, true, losslessWebp = true),
                )
                val bytes = webp.readBytes()
                assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
                assertEquals("WEBP", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
                BitmapFactory.decodeFile(webp.path).let { image ->
                    assertEquals(0, Color.alpha(image.getPixel(0, 0)))
                    image.recycle()
                }
                val rotated = File(folder, "rotated.jpg")
                Bitmap.createBitmap(80, 140, Bitmap.Config.ARGB_8888).let { image ->
                    rotated.outputStream().use {
                        image.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    }
                    image.recycle()
                }
                ExifInterface(rotated).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, "6")
                    saveAttributes()
                }
                val oriented = File(folder, "oriented.png")
                LocalConversions.image(
                    app,
                    Uri.fromFile(rotated),
                    oriented,
                    ImageConversionOptions("png", edge = 600),
                )
                BitmapFactory.decodeFile(oriented.path).let { image ->
                    assertEquals(140, image.width)
                    assertEquals(80, image.height)
                    image.recycle()
                }
                assertEquals(hash, PdfAssembly.sha256(good))
                zip.copyTo(File(app.cacheDir, "image-options-proof.zip"), overwrite = true)
            } finally {
                rowId?.let { id ->
                    repo.dao.task(id)?.let { row ->
                        if (row.state in LocalTaskRow.active) DocumentJobs.cancel(repo, row)
                        DocumentJobs.forget(repo, id)
                    }
                }
                folder.listFiles()?.forEach { it.delete() }
                folder.delete()
            }
        }
}
