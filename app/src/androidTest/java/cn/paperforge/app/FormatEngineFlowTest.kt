package cn.paperforge.app

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.FormatBridge
import cn.paperforge.app.pdf.PdfAssembly
import cn.paperforge.app.pdf.ReaderDocument
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormatEngineFlowTest {
    @Test
    fun optionalEngineRendersEmbeddedCjkOfdAndRotatedDjvuPages() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val folder = File(app.cacheDir, "format-engine-proof").apply { mkdirs() }
        val sources = fixtures(app, folder)
        val proof = JSONArray()
        for (source in sources) {
            val hash = PdfAssembly.sha256(source)
            val output = File(folder, "${source.extension}-pages.zip")
            output.outputStream().use { stream ->
                FormatBridge.convert(
                    app,
                    "layout-${source.extension}",
                    source,
                    stream,
                    JSONObject().put("type", source.extension).put("dpi", 150).toString(),
                ) { _, _ ->
                }
            }
            assertEquals(hash, PdfAssembly.sha256(source))
            ZipFile(output).use { zip ->
                val manifest =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("document.json"))
                            .bufferedReader()
                            .readText()
                    )
                val pages = manifest.getJSONArray("pages")
                assertEquals(2, pages.length())
                for (i in 0 until pages.length()) {
                    val page = pages.getJSONObject(i)
                    val bitmap =
                        BitmapFactory.decodeStream(
                            zip.getInputStream(zip.getEntry(page.getString("image")))
                        )
                    assertNotNull(bitmap)
                    assertEquals(page.getInt("width"), bitmap.width)
                    assertEquals(page.getInt("height"), bitmap.height)
                    val sample =
                        if (source.extension == "ofd")
                            bitmap.getPixel(
                                (85f / (if (i == 0) 100 else 140) * bitmap.width).toInt(),
                                (60f / (if (i == 0) 140 else 100) * bitmap.height).toInt(),
                            )
                        else bitmap.getPixel(bitmap.width / 4, bitmap.height / 4)
                    assertTrue(
                        "Rendered page must not be blank",
                        Color.red(sample) < 245 ||
                            Color.green(sample) < 245 ||
                            Color.blue(sample) < 245,
                    )
                    File(folder, "${source.extension}-${i+1}.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                    if (source.extension == "ofd") {
                        assertEquals(0, page.getJSONArray("warnings").length())
                        assertTrue(
                            page
                                .getJSONArray("zones")
                                .toString()
                                .contains(if (i == 0) "00123" else "200.50")
                        )
                    }
                }
                assertTrue(
                    pages.getJSONObject(0).getDouble("widthPt") <
                        pages.getJSONObject(0).getDouble("heightPt")
                )
                assertTrue(
                    pages.getJSONObject(1).getDouble("widthPt") >
                        pages.getJSONObject(1).getDouble("heightPt")
                )
                proof.put(manifest.put("originalUnchanged", true))
            }
        }
        File(folder, "proof.json").writeText(proof.toString(2))
    }

    @Test
    fun queuedTargetsRetainOriginalTextAndReusePageArchive() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val folder = File(app.cacheDir, "format-job-proof").apply { mkdirs() }
        val sources = fixtures(app, folder)
        suspend fun wait(id: java.util.UUID) =
            withTimeout(60000) {
                WorkManager.getInstance(app).getWorkInfoByIdFlow(id).filterNotNull().first {
                    it.state.isFinished
                }
            }
        suspend fun enqueue(source: File) =
            DocumentJobs.enqueue(
                repo,
                "CONVERT",
                JobInput(
                    JSONObject()
                        .put("kind", "LAYOUT")
                        .put("name", source.name)
                        .put("inputs", JSONArray(listOf(source.path)))
                        .put("names", JSONArray(listOf(source.name)))
                        .put(
                            "options",
                            JSONObject().put("dpi", 150).put("target", "PDF").toString(),
                        )
                ),
            )
        val hash = PdfAssembly.sha256(sources[0])
        val first = wait(enqueue(sources[0]))
        assertEquals(first.outputData.getString("error"), WorkInfo.State.SUCCEEDED, first.state)
        val pdf = File(first.outputData.getString("path")!!)
        val pdfHash = PdfAssembly.sha256(pdf)
        val text = ReaderDocument.text(pdf, 0).joinToString("") { it.text }
        assertTrue(text.contains("纸匠"))
        assertTrue(text.contains("00123"))
        assertTrue(ReaderDocument.text(pdf, 1).joinToString("") { it.text }.contains("第二页 200.50"))
        val id = first.outputData.getString("taskId")!!
        val row = requireNotNull(repo.dao.task(id))
        val checkpoints = JSONObject(row.payload).getJSONObject("checkpoint")
        val key = checkpoints.keys().asSequence().first { it.startsWith("layout-pages-") }
        val stage = File(checkpoints.getJSONObject(key).getString("path"))
        val stageHash = PdfAssembly.sha256(stage)
        val stageTime = stage.lastModified()
        suspend fun retarget(target: String): WorkInfo {
            val updated =
                repo.database.withTransaction {
                    val current = requireNotNull(repo.dao.task(id))
                    val payload = JSONObject(current.payload)
                    payload
                        .getJSONObject("input")
                        .put(
                            "options",
                            JSONObject().put("dpi", 150).put("target", target).toString(),
                        )
                    current.copy(payload = payload.toString()).also { repo.dao.putTask(it) }
                }
            return wait(DocumentJobs.retry(repo, updated))
        }
        val images = retarget("IMAGES")
        assertEquals(images.outputData.getString("error"), WorkInfo.State.SUCCEEDED, images.state)
        val zipFile = File(images.outputData.getString("path")!!)
        assertNotEquals(pdf.parentFile, zipFile.parentFile)
        ZipFile(zipFile).use { zip ->
            val info =
                JSONObject(
                    zip.getInputStream(zip.getEntry("document.json")).bufferedReader().readText()
                )
            assertEquals(2, info.getJSONArray("pages").length())
            assertTrue(info.getString("signatureStatus").contains("Appearance copy"))
        }
        assertEquals(stageHash, PdfAssembly.sha256(stage))
        assertEquals(stageTime, stage.lastModified())
        assertEquals(pdfHash, PdfAssembly.sha256(pdf))
        val again = retarget("PDF")
        assertEquals(WorkInfo.State.SUCCEEDED, again.state)
        assertEquals(pdf.path, again.outputData.getString("path"))
        assertEquals(pdfHash, PdfAssembly.sha256(pdf))
        val djvu = wait(enqueue(sources[1]))
        assertEquals(djvu.outputData.getString("error"), WorkInfo.State.SUCCEEDED, djvu.state)
        val djvuPdf = File(djvu.outputData.getString("path")!!)
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(djvuPdf).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertTrue(doc.getPage(1).cropBox.width > doc.getPage(1).cropBox.height)
        }
        assertEquals(hash, PdfAssembly.sha256(sources[0]))
        pdf.copyTo(File(folder, "ofd.pdf"), overwrite = true)
        zipFile.copyTo(File(folder, "ofd-images.zip"), overwrite = true)
        djvuPdf.copyTo(File(folder, "djvu.pdf"), overwrite = true)
        File(folder, "proof.json")
            .writeText(
                JSONObject()
                    .put("ofdTask", id)
                    .put("djvuTask", djvu.outputData.getString("taskId"))
                    .put("sourceSha256", hash)
                    .put("originalUnchanged", true)
                    .put("nativeText", text)
                    .put("stageReused", true)
                    .put("publishedPdfUnchanged", true)
                    .put("cachedOutputReused", true)
                    .put("pdfPath", pdf.path)
                    .put("imagePath", zipFile.path)
                    .toString(2)
            )
    }

    private fun fixtures(app: PaperForgeApp, folder: File): List<File> {
        val ofd = File(folder, "source.ofd")
        val ns = "http://www.ofdspec.org/2016"
        ZipOutputStream(ofd.outputStream()).use { zip ->
            fun entry(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry(
                "OFD.xml",
                """<ofd:OFD xmlns:ofd="$ns" Version="1.0" DocType="OFD"><ofd:DocBody><ofd:DocInfo><ofd:DocID>00000000000000000000000000000001</ofd:DocID></ofd:DocInfo><ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot></ofd:DocBody></ofd:OFD>""",
            )
            entry(
                "Doc_0/Document.xml",
                """<ofd:Document xmlns:ofd="$ns"><ofd:CommonData><ofd:MaxUnitID>100</ofd:MaxUnitID><ofd:PageArea><ofd:PhysicalBox>0 0 100 140</ofd:PhysicalBox></ofd:PageArea><ofd:PublicRes>PublicRes.xml</ofd:PublicRes></ofd:CommonData><ofd:Pages><ofd:Page ID="1" BaseLoc="Pages/0.xml"/><ofd:Page ID="2" BaseLoc="Pages/1.xml"/></ofd:Pages></ofd:Document>""",
            )
            entry(
                "Doc_0/PublicRes.xml",
                """<ofd:Res xmlns:ofd="$ns" BaseLoc="Res"><ofd:Fonts><ofd:Font ID="10" FontName="PaperForgeCJK" FamilyName="PaperForgeCJK"><ofd:FontFile>source.ttf</ofd:FontFile></ofd:Font></ofd:Fonts></ofd:Res>""",
            )
            zip.putNextEntry(ZipEntry("Doc_0/Res/source.ttf"))
            app.assets.open("fonts/PaperForgeCJK-Regular.ttf").use { it.copyTo(zip) }
            zip.closeEntry()
            repeat(2) { page ->
                val text = if (page == 0) "纸匠 OFD 00123" else "第二页 200.50"
                val delta = List(text.length - 1) { "5" }.joinToString(" ")
                entry(
                    "Doc_0/Pages/$page.xml",
                    """<ofd:Page xmlns:ofd="$ns"><ofd:Area><ofd:PhysicalBox>0 0 ${if(page==0)"100 140" else "140 100"}</ofd:PhysicalBox></ofd:Area><ofd:Content><ofd:Layer ID="20"><ofd:TextObject ID="21" Boundary="10 12 110 12" Font="10" Size="5"><ofd:FillColor Value="0 0 0"/><ofd:TextCode X="0" Y="6" DeltaX="$delta">$text</ofd:TextCode></ofd:TextObject><ofd:PathObject ID="22" Boundary="10 45 80 30" Fill="true" Stroke="false"><ofd:FillColor Value="20 110 180"/><ofd:AbbreviatedData>M 0 0 L 80 0 L 80 30 L 0 30 C</ofd:AbbreviatedData></ofd:PathObject><ofd:PathObject ID="23" Boundary="65 80 24 16" LineWidth="1" Fill="false" Stroke="true"><ofd:StrokeColor Value="220 20 20"/><ofd:AbbreviatedData>M 0 0 L 24 0 L 24 16 L 0 16 C</ofd:AbbreviatedData></ofd:PathObject></ofd:Layer></ofd:Content></ofd:Page>""",
                )
            }
        }
        val djvu = File(folder, "source.djvu")
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("layout-two-pages.djvu")
            .use { from -> djvu.outputStream().use { from.copyTo(it) } }
        return listOf(ofd, djvu)
    }
}
