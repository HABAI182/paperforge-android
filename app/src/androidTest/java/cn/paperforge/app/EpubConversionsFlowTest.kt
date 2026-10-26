package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class EpubConversionsFlowTest {
    private suspend fun job(app: PaperForgeApp, file: File, target: String): LocalTaskRow {
        val row =
            DocumentJobs.prepare(
                app.repository,
                "CONVERT",
                JobInput(
                    JSONObject()
                        .put("kind", "MARKUP")
                        .put("name", "纸匠电子书 ${file.name}")
                        .put("inputs", JSONArray(listOf(file.path)))
                        .put(
                            "options",
                            JSONObject()
                                .put("target", target)
                                .put("title", "纸匠结构书")
                                .put("author", "PaperForge")
                                .put("language", "zh")
                                .toString(),
                        )
                ),
            )
        DocumentJobs.schedule(app.repository, row)
        val info =
            withTimeout(90000) {
                WorkManager.getInstance(app)
                    .getWorkInfoByIdFlow(UUID.fromString(row.workId))
                    .filterNotNull()
                    .first { it.state.isFinished }
            }
        assertEquals(info.outputData.getString("error"), WorkInfo.State.SUCCEEDED, info.state)
        return app.repository.dao.task(row.id)!!
    }

    private fun result(row: LocalTaskRow) =
        File(row.resourceEntries().first { it.id == "result" }.path)

    private fun fixture(file: File, legacy: Boolean): ByteArray {
        val bitmap =
            Bitmap.createBitmap(30, 45, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(25, 130, 90))
            }
        val png =
            java.io
                .ByteArrayOutputStream()
                .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        bitmap.recycle()
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, value: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value)
                zip.closeEntry()
            }
            fun text(name: String, value: String) = entry(name, value.toByteArray())
            text("mimetype", "application/epub+zip")
            text(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            text(
                "OPS/book.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="${if (legacy) "2.0" else "3.0"}"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>顺序与脚注</dc:title><dc:language>zh</dc:language><meta name="cover" content="cover"/></metadata><manifest><item id="a" href="a-last.xhtml" media-type="application/xhtml+xml"/><item id="z" href="z-first.xhtml" media-type="application/xhtml+xml"/><item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/><item id="cover" href="images/cover.png" media-type="image/png" properties="cover-image"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" ${if (!legacy) "properties=\"nav\"" else ""}/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="css" href="styles/book.css" media-type="text/css"/></manifest><spine toc="ncx"><itemref idref="z"/><itemref idref="a"/></spine></package>""",
            )
            text(
                "OPS/a-last.xhtml",
                """<html><head><title>第二章</title></head><body><h1 id="same">第二章</h1><p>SECOND 00077.50 <a href="z-first.xhtml#same">返回第一章</a></p></body></html>""",
            )
            text(
                "OPS/z-first.xhtml",
                """<html><head><title>第一章</title><link rel="stylesheet" href="styles/book.css"/></head><body><h1 id="same">第一章</h1><p>FIRST 00123 <a id="ref" href="notes.xhtml#foot">注一</a></p><img src="images/cover.png" alt="原书封面"/></body></html>""",
            )
            text(
                "OPS/notes.xhtml",
                """<html><body><aside id="foot" epub:type="footnote"><p>脚注原文 <a href="z-first.xhtml#ref">返回注标</a></p></aside></body></html>""",
            )
            text(
                "OPS/nav.xhtml",
                """<html><body><nav epub:type="toc"><ol><li><a href="z-first.xhtml#same">一</a><ol><li><a href="notes.xhtml#foot">注一</a></li></ol></li><li><a href="a-last.xhtml#same">二</a></li></ol></nav></body></html>""",
            )
            text(
                "OPS/toc.ncx",
                """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint id="one"><navLabel><text>旧目录一</text></navLabel><content src="z-first.xhtml#same"/><navPoint id="note"><navLabel><text>脚注</text></navLabel><content src="notes.xhtml#foot"/></navPoint></navPoint><navPoint id="two"><navLabel><text>旧目录二</text></navLabel><content src="a-last.xhtml#same"/></navPoint></navMap></ncx>""",
            )
            text(
                "OPS/styles/book.css",
                "body {color:#12553f} p {background-image:url('../images/cover.png')}",
            )
            entry("OPS/images/cover.png", png)
        }
        return png
    }

    @Test
    fun epubSpineNavigationNotesAndResourcesSurviveTextConversions() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val before = repo.dao.allTasks().map { it.id }.toSet()
            var keep: String? = null
            val source =
                File(app.filesDir, "conversion-inputs/epub-spine-proof.epub").apply {
                    parentFile!!.mkdirs()
                }
            val png = fixture(source, false)
            val hash = PdfAssembly.sha256(source)
            try {
                val opened = EpubDocuments.open(app, source)
                val dom = Jsoup.parse(opened.main.readText())
                val sections = dom.select("section.epub-chapter")
                assertEquals(
                    listOf("第一章", "第二章"),
                    sections.take(2).map { it.selectFirst("h1")!!.text() },
                )
                assertEquals("true", sections.last()!!.attr("data-auxiliary"))
                assertEquals(
                    "#epub-chapter-3-foot",
                    dom.selectFirst("a[id='epub-chapter-1-ref']")!!.attr("href"),
                )
                assertTrue(dom.select("nav ol ol").isNotEmpty())
                assertEquals(
                    "#epub-chapter-1-ref",
                    sections.last()!!.selectFirst("a")!!.attr("href"),
                )
                assertEquals("OPS/images/cover.png", dom.selectFirst("img")!!.attr("src"))
                assertEquals("OPS/styles/book.css", dom.selectFirst("link")!!.attr("href"))
                for (target in listOf("HTML", "MD", "TXT")) {
                    val row = job(app, source, target)
                    if (target == "HTML") keep = row.id
                    val converted = MarkupDocuments.open(app, result(row))
                    val raw = converted.main.readText()
                    val text =
                        if (target == "MD") Jsoup.parse(MarkupDocuments.markdownToHtml(raw)).text()
                        else if (target == "HTML") Jsoup.parse(raw).text() else raw
                    assertTrue(text.indexOf("FIRST 00123") < text.indexOf("SECOND 00077.50"))
                    assertTrue(text.contains("脚注原文"))
                    ZipFile(result(row)).use { zip ->
                        assertArrayEquals(
                            png,
                            zip.getInputStream(zip.getEntry("OPS/images/cover.png")).readBytes(),
                        )
                    }
                    result(row)
                        .copyTo(File(app.cacheDir, "epub-$target-proof.zip"), overwrite = true)
                }
                val legacy = File(app.cacheDir, "epub-ncx-proof.epub")
                fixture(legacy, true)
                val ncx = Jsoup.parse(EpubDocuments.open(app, legacy).main.readText())
                assertTrue(ncx.select("nav").text().contains("旧目录一"))
                assertTrue(ncx.select("nav ol ol").isNotEmpty())
                assertEquals(hash, PdfAssembly.sha256(source))
                File(app.cacheDir, "epub-spine-proof.json").writeText(opened.report!!.toString(2))
            } finally {
                repo.dao
                    .allTasks()
                    .filter { it.id !in before && it.id != keep }
                    .forEach { DocumentJobs.forget(repo, it.id) }
            }
        }

    @Test
    fun editableEpubPackagesContainRealChaptersNotesAndPdfLayoutFallback() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val before = repo.dao.allTasks().map { it.id }.toSet()
            var keep: String? = null
            try {
                val archive = File(app.cacheDir, "book-markdown.zip")
                val bitmap =
                    Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.GREEN)
                    }
                val buffer = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
                bitmap.recycle()
                ZipOutputStream(archive.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("index.md"))
                    zip.write(
                        "# 第一章\n\nFIRST **00123** [^1]\n\n![封面](images/cover.png)\n\n# 第二章\n\nSECOND 00077.50\n\n[^1]: 跨章脚注"
                            .toByteArray()
                    )
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("images/cover.png"))
                    zip.write(buffer.toByteArray())
                    zip.closeEntry()
                }
                val row = job(app, archive, "EPUB")
                keep = row.id
                val epub = result(row)
                epub.copyTo(File(app.cacheDir, "epub-created-proof.epub"), overwrite = true)
                ZipFile(epub).use { zip ->
                    val first = zip.entries().nextElement()
                    assertEquals("mimetype", first.name)
                    assertEquals(ZipEntry.STORED, first.method)
                    assertEquals(
                        "application/epub+zip",
                        zip.getInputStream(first).bufferedReader().readText(),
                    )
                    val opf =
                        zip.getInputStream(zip.getEntry("EPUB/package.opf"))
                            .bufferedReader()
                            .readText()
                    assertTrue(opf.contains("cover-image"))
                    assertTrue(opf.contains("纸匠结构书"))
                    assertTrue(opf.contains("dc:creator"))
                }
                val rendered = Jsoup.parse(EpubDocuments.open(app, epub).main.readText())
                assertEquals(
                    listOf("第一章", "第二章"),
                    rendered.select("section.epub-chapter h1").map { it.text() },
                )
                val noteLink =
                    rendered.select("a").first {
                        it.attr("href").contains("fn") && it.text() == "1"
                    }
                assertNotNull(
                    rendered.getElementById(
                        android.net.Uri.decode(noteLink.attr("href").removePrefix("#"))
                    )
                )
                assertTrue(rendered.text().contains("跨章脚注"))
                val pdf =
                    File(app.filesDir, "conversion-inputs/ebook-source-proof.pdf").apply {
                        parentFile!!.mkdirs()
                    }
                PDDocument().use { doc ->
                    val native = PDPage()
                    doc.addPage(native)
                    PDPageContentStream(doc, native).use { out ->
                        out.beginText()
                        out.setFont(PDType1Font.HELVETICA, 20f)
                        out.newLineAtOffset(50f, 730f)
                        out.showText("NATIVE 00123")
                        out.endText()
                    }
                    val scan = Bitmap.createBitmap(900, 1100, Bitmap.Config.ARGB_8888)
                    scan.eraseColor(Color.WHITE)
                    Canvas(scan)
                        .drawText(
                            "SCANNED 00077.50",
                            50f,
                            150f,
                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.BLACK
                                textSize = 58f
                            },
                        )
                    val page = PDPage()
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use {
                        it.drawImage(LosslessFactory.createFromImage(doc, scan), 0f, 0f, 612f, 748f)
                    }
                    scan.recycle()
                    doc.save(pdf)
                }
                val sourceHash = PdfAssembly.sha256(pdf)
                val pdfJob = job(app, pdf, "EPUB")
                assertTrue(JSONObject(pdfJob.payload).getJSONObject("input").has("ocrRequest"))
                val pdfBook = result(pdfJob)
                pdfBook.copyTo(File(app.cacheDir, "epub-pdf-proof.epub"), overwrite = true)
                val pdfOpened = EpubDocuments.open(app, pdfBook)
                val actual = Jsoup.parse(pdfOpened.main.readText())
                assertTrue(actual.select("p").text().contains("NATIVE 00123"))
                assertTrue(actual.select("p").text().contains("SCANNED 00077.50"))
                assertEquals(2, actual.select("details img").size)
                ZipFile(pdfBook).use { zip ->
                    assertEquals(
                        sourceHash,
                        PdfAssembly.sha256(
                            File(app.cacheDir, "epub-original-copy.pdf").apply {
                                writeBytes(
                                    zip.getInputStream(zip.getEntry("EPUB/resources/original.pdf"))
                                        .readBytes()
                                )
                            }
                        ),
                    )
                }
                File(app.cacheDir, "epub-created-proof.json")
                    .writeText(pdfOpened.report!!.toString(2))
            } finally {
                repo.dao
                    .allTasks()
                    .filter { it.id !in before && it.id != keep }
                    .forEach { DocumentJobs.forget(repo, it.id) }
            }
        }
}
