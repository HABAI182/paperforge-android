package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
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
class SpecialFormatsFlowTest {
    @Test
    fun pdfBackedCajAndLatexProjectHaveExplicitResults() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val folder = File(app.cacheDir, "special-formats-proof").apply { mkdirs() }
        val original = File(folder, "caj-original.pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { pdf ->
            repeat(2) { number ->
                val page = com.tom_roush.pdfbox.pdmodel.PDPage()
                pdf.addPage(page)
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(pdf, page).use { stream ->
                    stream.beginText()
                    stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 18f)
                    stream.newLineAtOffset(30f, 720f)
                    stream.showText("PaperForge CAJ 00123 page ${number + 1}")
                    stream.endText()
                    stream.addRect(30f, 300f, 200f, 60f)
                    stream.fill()
                }
            }
            pdf.save(original)
        }
        val bytes = original.readBytes()
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val objects =
            Regex("(?:^|[\\r\\n])(\\d+) 0 obj\\b.*?endobj", RegexOption.DOT_MATCHES_ALL)
                .findAll(raw)
                .map { it.value }
                .toList()
        val containers = mutableListOf<File>()
        for (missing in listOf(false, true)) {
            val kept =
                objects.filter {
                    !Regex("/Type\\s*/Catalog\\b").containsMatchIn(it) &&
                        (!missing || !Regex("/Type\\s*/Pages\\b").containsMatchIn(it))
                }
            val header = ByteArray(512)
            "CAJ\u0000".toByteArray().copyInto(header)
            val buffer = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0x10, 2)
            buffer.putInt(0x14, 0x40)
            buffer.putInt(0x40, 512)
            containers.add(
                File(folder, if (missing) "missing-pages.caj" else "complete-pages.caj").apply {
                    outputStream().use {
                        it.write(header)
                        it.write(kept.joinToString("\r\n").toByteArray(Charsets.ISO_8859_1))
                    }
                }
            )
        }
        val key = "FZHMEI".toByteArray()
        containers.add(
            File(folder, "source.kdh").apply {
                outputStream().use { out ->
                    val header = ByteArray(254)
                    "KDH ".toByteArray().copyInto(header)
                    out.write(header)
                    out.write(
                        ByteArray(bytes.size) {
                            (bytes[it].toInt() xor key[it % key.size].toInt()).toByte()
                        }
                    )
                }
            }
        )
        containers.add(File(folder, "renamed.caj").apply { writeBytes(bytes) })
        val latex =
            """\documentclass{article}
\usepackage{amsmath}
\title{纸匠 LaTeX 00123}\author{PaperForge}
\begin{document}\maketitle
\section{中文正文}保留中文段落、金额 200.50，以及\textbf{加粗文字}。
内联公式 \(a^2+b^2=c^2\)。
\[\frac{1}{2}+\sqrt{x}+\alpha+\int_0^1 x^2\,dx\]
\begin{align}x+y&=3\\ x-y&=1\end{align}
\begin{itemize}\item 第一项\item 第二项\end{itemize}
\begin{tabular}{lr}项目 & 金额 \\ 甲 & 200.50 \\ \end{tabular}
\includegraphics{figure.png}
\footnote{中文脚注} \unknownmacro{未支持内容要保留}
\newpage\section{第二页}结束 00123。
\end{document}"""
        val project = File(folder, "latex-project.zip")
        ZipOutputStream(project.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("main.tex"))
            zip.write(latex.toByteArray())
            zip.closeEntry()
            val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLUE)
            try {
                zip.putNextEntry(ZipEntry("figure.png"))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            } finally {
                bitmap.recycle()
            }
        }
        containers.add(project)
        val proof = JSONArray()
        for (source in containers) {
            val type = if (source == project) "tex" else source.extension
            val hash = PdfAssembly.sha256(source)
            val output = File(folder, "${source.nameWithoutExtension}-container.zip")
            output.outputStream().use {
                FormatBridge.convert(
                    app,
                    "container-${source.nameWithoutExtension}",
                    source,
                    it,
                    JSONObject()
                        .put("type", type)
                        .put("dpi", 150)
                        .put("fontSize", 12)
                        .put("mathSource", true)
                        .toString(),
                ) { _, _ ->
                }
            }
            ZipFile(output).use { zip ->
                val report =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("document.json")).bufferedReader().use {
                            it.readText()
                        }
                    )
                val pdf = File(folder, "${source.nameWithoutExtension}-container.pdf")
                zip.getInputStream(zip.getEntry("native.pdf")).use { from ->
                    pdf.outputStream().use { from.copyTo(it) }
                }
                val text =
                    (0 until report.getJSONArray("pages").length()).joinToString("\n") {
                        ReaderDocument.text(pdf, it).joinToString("") { it.text }
                    }
                assertTrue(text, text.contains("00123"))
                if (type == "tex") {
                    assertTrue(
                        "Explicit LaTeX page break",
                        report.getJSONArray("pages").length() >= 2,
                    )
                    assertTrue(text, text.contains("纸匠 LaTeX"))
                    assertTrue(text.contains("中文脚注"))
                    assertTrue(text.contains("未支持内容要保留"))
                    assertTrue(report.getJSONArray("formulas").length() >= 3)
                    val warnings =
                        report
                            .getJSONArray("pages")
                            .getJSONObject(0)
                            .getJSONArray("warnings")
                            .toString()
                    assertTrue(warnings, warnings.contains("unknownmacro"))
                    assertFalse(warnings, warnings.contains("公式未能排版"))
                    assertFalse(warnings, warnings.contains("图片未找到"))
                } else assertEquals(2, report.getJSONArray("pages").length())
                assertEquals(hash, PdfAssembly.sha256(source))
                proof.put(
                    report
                        .put("name", source.name)
                        .put("extractedText", text)
                        .put("originalUnchanged", true)
                )
            }
        }
        val unsupported =
            File(folder, "unsupported-hn.caj").apply {
                writeBytes("HN\u0000\u0000\u0090\u0001".toByteArray())
            }
        val hash = PdfAssembly.sha256(unsupported)
        val failure =
            runCatching {
                    File(folder, "unsupported.zip").outputStream().use {
                        FormatBridge.convert(
                            app,
                            "unsupported-hn",
                            unsupported,
                            it,
                            JSONObject().put("type", "caj").toString(),
                        ) { _, _ ->
                        }
                    }
                }
                .exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure?.message.orEmpty().contains("HN"))
        assertEquals(hash, PdfAssembly.sha256(unsupported))
        File(folder, "caj-latex-proof.json")
            .writeText(
                JSONObject()
                    .put("formats", proof)
                    .put("unsupportedHn", failure?.message)
                    .put("originalUnchanged", true)
                    .toString(2)
            )
    }

    @Test
    fun emailMimeAndDxfUseActualPhoneEngines() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val folder = File(app.cacheDir, "special-formats-proof").apply { mkdirs() }
        fun base64(bytes: ByteArray) = java.util.Base64.getMimeEncoder().encodeToString(bytes)
        val bitmap = Bitmap.createBitmap(160, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        val image =
            java.io
                .ByteArrayOutputStream()
                .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        bitmap.recycle()
        val subject = java.util.Base64.getEncoder().encodeToString("纸匠邮件 00123".toByteArray())
        val html =
            "<html><body><h2>中文邮件正文 00123</h2><table border='1'><tr><td>金额</td><td>200.50</td></tr></table><img src='cid:blue' alt='内嵌蓝图'/><img src='https://invalid.example/pixel.png' alt='远程图'/></body></html>"
        val eml =
            File(folder, "source.eml").apply {
                writeText(
                    """
From: =?UTF-8?B?$subject?= <author@example.invalid>
To: Reader <reader@example.invalid>
Subject: =?UTF-8?B?$subject?=
Date: Tue, 27 Oct 2026 12:30:00 +0800
MIME-Version: 1.0
Content-Type: multipart/mixed; boundary="mixed-proof"

--mixed-proof
Content-Type: multipart/related; boundary="related-proof"

--related-proof
Content-Type: text/html; charset=UTF-8
Content-Transfer-Encoding: base64

${base64(html.toByteArray())}
--related-proof
Content-Type: image/png
Content-ID: <blue>
Content-Transfer-Encoding: base64

${base64(image)}
--related-proof--
--mixed-proof
Content-Type: text/plain; charset=UTF-8
Content-Disposition: attachment; filename="attachment.txt"
Content-Transfer-Encoding: base64

${base64("Original attachment bytes".toByteArray())}
--mixed-proof--
"""
                        .trimIndent()
                        .replace("\n", "\r\n")
                )
            }
        val plain =
            File(folder, "gb18030.eml").apply {
                writeBytes(
                    ("Subject: Plain email 00123\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=GB18030\r\nContent-Transfer-Encoding: base64\r\n\r\n" +
                            base64("中文邮件正文 00123 金额 200.50".toByteArray(charset("GB18030"))))
                        .toByteArray()
                )
            }
        val proof = JSONArray()
        for (source in listOf(eml, plain)) {
            val hash = PdfAssembly.sha256(source)
            val result = File(folder, "${source.nameWithoutExtension}-email.zip")
            result.outputStream().use {
                FormatBridge.convert(
                    app,
                    "email-${source.nameWithoutExtension}",
                    source,
                    it,
                    JSONObject().put("type", "eml").put("dpi", 150).toString(),
                ) { _, _ ->
                }
            }
            ZipFile(result).use { zip ->
                val report =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("document.json")).bufferedReader().use {
                            it.readText()
                        }
                    )
                val pdf = File(folder, "${source.nameWithoutExtension}-email.pdf")
                zip.getInputStream(zip.getEntry("native.pdf")).use { from ->
                    pdf.outputStream().use { from.copyTo(it) }
                }
                val text =
                    (0 until report.getJSONArray("pages").length()).joinToString("") {
                        ReaderDocument.text(pdf, it).joinToString("") { it.text }
                    }
                assertTrue(text, text.contains("中文邮件正文 00123"))
                assertTrue(text.contains("200.50"))
                if (source == eml) {
                    assertTrue(text.contains("纸匠邮件"))
                    assertEquals("attachment.txt", report.getJSONArray("attachments").getString(0))
                    assertTrue(text.contains("图片未载入"))
                }
                assertEquals(hash, PdfAssembly.sha256(source))
                proof.put(report.put("extractedText", text).put("originalUnchanged", true))
            }
        }
        val dxf =
            File(folder, "source.dxf").apply {
                writeText(
                    listOf(
                            "0",
                            "SECTION",
                            "2",
                            "HEADER",
                            "9",
                            "\$ACADVER",
                            "1",
                            "AC1009",
                            "0",
                            "ENDSEC",
                            "0",
                            "SECTION",
                            "2",
                            "ENTITIES",
                            "0",
                            "LINE",
                            "8",
                            "0",
                            "10",
                            "10.0",
                            "20",
                            "10.0",
                            "11",
                            "180.0",
                            "21",
                            "100.0",
                            "62",
                            "1",
                            "0",
                            "CIRCLE",
                            "8",
                            "0",
                            "10",
                            "90.0",
                            "20",
                            "50.0",
                            "40",
                            "30.0",
                            "62",
                            "5",
                            "0",
                            "TEXT",
                            "8",
                            "0",
                            "10",
                            "20.0",
                            "20",
                            "120.0",
                            "40",
                            "8.0",
                            "1",
                            "PaperForge DXF 00123",
                            "0",
                            "ENDSEC",
                            "0",
                            "EOF",
                        )
                        .joinToString("\r\n") + "\r\n"
                )
            }
        val hash = PdfAssembly.sha256(dxf)
        val pdf = File(folder, "dxf.pdf")
        pdf.outputStream().use {
            OfficeBridge.convert(app, "special-dxf", dxf, it, "pdf") { _, _ -> }
        }
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdf).use { assertEquals(1, it.numberOfPages) }
        val dxfText = ReaderDocument.text(pdf, 0).joinToString("") { it.text }
        assertTrue(dxfText, dxfText.contains("PaperForge DXF 00123"))
        assertEquals(hash, PdfAssembly.sha256(dxf))
        File(folder, "email-dxf-proof.json")
            .writeText(
                JSONObject()
                    .put("emails", proof)
                    .put("dxfText", dxfText)
                    .put("dxfSourceSha256", hash)
                    .put("originalUnchanged", true)
                    .toString(2)
            )
    }

    @Test
    fun nativeFormatsKeepTextVectorsPagesAndReuseResults() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val folder = File(app.cacheDir, "special-formats-proof").apply { mkdirs() }
        val sources = fixtures(app, folder)
        val proof = JSONArray()
        for (source in sources) {
            val hash = PdfAssembly.sha256(source)
            val output = File(folder, "${source.extension}-pages.zip")
            output.outputStream().use { stream ->
                FormatBridge.convert(
                    app,
                    "special-${source.extension}",
                    source,
                    stream,
                    JSONObject()
                        .put("type", source.extension)
                        .put("dpi", 150)
                        .put("fontSize", 16)
                        .put("widthPt", 420)
                        .put("heightPt", 595)
                        .toString(),
                ) { _, _ ->
                }
            }
            assertEquals(hash, PdfAssembly.sha256(source))
            ZipFile(output).use { zip ->
                val manifest =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("document.json")).bufferedReader().use {
                            it.readText()
                        }
                    )
                val pages = manifest.getJSONArray("pages")
                assertTrue(pages.length() > 0)
                if (source.extension in setOf("xps", "oxps", "cbz")) assertEquals(2, pages.length())
                val pdf = File(folder, "${source.extension}.pdf")
                zip.getInputStream(zip.getEntry("native.pdf")).use { from ->
                    pdf.outputStream().use { from.copyTo(it) }
                }
                val text =
                    (0 until pages.length()).joinToString("\n") {
                        ReaderDocument.text(pdf, it).joinToString("") { it.text }
                    }
                if (source.extension != "cbz")
                    assertTrue("${source.extension} text: $text", text.contains("00123"))
                if (source.extension in setOf("xps", "oxps", "fb2")) assertTrue(text.contains("纸匠"))
                if (source.extension == "fb2") {
                    assertTrue(pages.length() > 1)
                    assertTrue(manifest.getBoolean("reflowable"))
                }
                proof.put(manifest.put("originalUnchanged", true).put("extractedText", text))
            }
        }
        suspend fun wait(id: java.util.UUID) =
            withTimeout(120000) {
                WorkManager.getInstance(app).getWorkInfoByIdFlow(id).filterNotNull().first {
                    it.state.isFinished
                }
            }
        val first =
            wait(
                DocumentJobs.enqueue(
                    repo,
                    "CONVERT",
                    JobInput(
                        JSONObject()
                            .put("kind", "LAYOUT")
                            .put("name", "Special Formats Proof.xps")
                            .put("inputs", JSONArray(listOf(sources.first().path)))
                            .put("names", JSONArray(listOf("Special Formats Proof.xps")))
                            .put(
                                "options",
                                JSONObject().put("dpi", 150).put("target", "PDF").toString(),
                            )
                    ),
                )
            )
        assertEquals(first.outputData.getString("error"), WorkInfo.State.SUCCEEDED, first.state)
        val id = requireNotNull(first.outputData.getString("taskId"))
        val originalPdf = File(requireNotNull(first.outputData.getString("path")))
        assertEquals("Special Formats Proof.pdf", originalPdf.name)
        val originalPdfHash = PdfAssembly.sha256(originalPdf)
        val row = requireNotNull(repo.dao.task(id))
        val checkpoint = JSONObject(row.payload).getJSONObject("checkpoint")
        val key = checkpoint.keys().asSequence().first { it.startsWith("layout-pages-") }
        val stage = File(checkpoint.getJSONObject(key).getString("path"))
        val stageTime = stage.lastModified()
        val changed =
            repo.database.withTransaction {
                val payload = JSONObject(row.payload)
                payload
                    .getJSONObject("input")
                    .put("options", JSONObject().put("dpi", 150).put("target", "IMAGES").toString())
                row.copy(payload = payload.toString()).also { repo.dao.putTask(it) }
            }
        val images = wait(DocumentJobs.retry(repo, changed))
        assertEquals(images.outputData.getString("error"), WorkInfo.State.SUCCEEDED, images.state)
        assertEquals(stageTime, stage.lastModified())
        assertEquals(originalPdfHash, PdfAssembly.sha256(originalPdf))
        ZipFile(File(requireNotNull(images.outputData.getString("path")))).use { zip ->
            assertNull(zip.getEntry("native.pdf"))
            assertFalse(
                JSONObject(
                        zip.getInputStream(zip.getEntry("document.json")).bufferedReader().use {
                            it.readText()
                        }
                    )
                    .has("nativePdf")
            )
        }
        val latest = requireNotNull(repo.dao.task(id))
        val preview = ConversionPreviews.prepare(repo, latest)
        val previewInfo = wait(java.util.UUID.fromString(requireNotNull(preview.workId)))
        assertEquals(
            previewInfo.outputData.getString("error"),
            WorkInfo.State.SUCCEEDED,
            previewInfo.state,
        )
        File(folder, "proof.json")
            .writeText(
                JSONObject()
                    .put("formats", proof)
                    .put("task", id)
                    .put("stageReused", true)
                    .put("publishedPdfUnchanged", true)
                    .put("actualSourceAndZipPreview", true)
                    .toString(2)
            )
    }

    private fun fixtures(app: PaperForgeApp, folder: File): List<File> {
        fun zipEntry(zip: ZipOutputStream, path: String, text: String) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
        fun xps(type: String): File =
            File(folder, "source.$type").also { file ->
                val ns =
                    if (type == "oxps") "http://schemas.openxps.org/oxps/v1.0"
                    else "http://schemas.microsoft.com/xps/2005/06"
                ZipOutputStream(file.outputStream()).use { zip ->
                    zipEntry(
                        zip,
                        "[Content_Types].xml",
                        """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="fdseq" ContentType="application/vnd.ms-package.xps-fixeddocumentsequence+xml"/><Default Extension="fdoc" ContentType="application/vnd.ms-package.xps-fixeddocument+xml"/><Default Extension="fpage" ContentType="application/vnd.ms-package.xps-fixedpage+xml"/><Default Extension="ttf" ContentType="application/vnd.ms-opentype"/></Types>""",
                    )
                    zipEntry(
                        zip,
                        "_rels/.rels",
                        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="$ns/fixedrepresentation" Target="FixedDocumentSequence.fdseq"/></Relationships>""",
                    )
                    zipEntry(
                        zip,
                        "FixedDocumentSequence.fdseq",
                        """<FixedDocumentSequence xmlns="$ns"><DocumentReference Source="/Documents/1/FixedDocument.fdoc"/></FixedDocumentSequence>""",
                    )
                    zipEntry(
                        zip,
                        "Documents/1/FixedDocument.fdoc",
                        """<FixedDocument xmlns="$ns"><PageContent Source="Pages/1.fpage"/><PageContent Source="Pages/2.fpage"/></FixedDocument>""",
                    )
                    for (i in 1..2) zipEntry(
                        zip,
                        "Documents/1/Pages/$i.fpage",
                        """<FixedPage xmlns="$ns" Width="${if(i==1)400 else 600}" Height="${if(i==1)600 else 400}" xml:lang="zh-CN"><Glyphs FontUri="/Resources/source.ttf" FontRenderingEmSize="24" OriginX="30" OriginY="60" UnicodeString="纸匠 XPS 00123 page $i" Fill="#FF172B40"/><Path Data="M 30,100 L 280,100 280,180 30,180 Z" Fill="#FF2477B3"/><Path Data="M 50,220 L 250,300" Stroke="#FFD13030" StrokeThickness="4"/></FixedPage>""",
                    )
                    zip.putNextEntry(ZipEntry("Resources/source.ttf"))
                    app.assets.open("fonts/PaperForgeCJK-Regular.ttf").use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        val svg =
            File(folder, "source.svg").apply {
                writeText(
                    """<svg xmlns="http://www.w3.org/2000/svg" width="480" height="320" viewBox="0 0 480 320"><rect width="480" height="320" fill="white"/><text x="20" y="50" font-size="22">PaperForge SVG 00123</text><rect x="30" y="90" width="240" height="70" fill="#2477b3"/><path d="M 30 220 Q 220 100 420 220" stroke="#d13030" fill="none" stroke-width="4"/></svg>"""
                )
            }
        val cbz =
            File(folder, "source.cbz").apply {
                ZipOutputStream(outputStream()).use { zip ->
                    for (i in 1..2) {
                        val bitmap =
                            Bitmap.createBitmap(
                                if (i == 1) 400 else 600,
                                if (i == 1) 600 else 400,
                                Bitmap.Config.ARGB_8888,
                            )
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            Canvas(bitmap)
                                .drawRect(
                                    30f,
                                    90f,
                                    270f,
                                    160f,
                                    Paint().apply { color = if (i == 1) Color.BLUE else Color.RED },
                                )
                            zip.putNextEntry(ZipEntry("page-$i.png"))
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                            zip.closeEntry()
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        val fb2 =
            File(folder, "source.fb2").apply {
                writeText(
                    """<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><genre>science</genre><author><first-name>Paper</first-name><last-name>Forge</last-name></author><book-title>纸匠 FB2 00123</book-title><lang>zh</lang></title-info></description><body><section><title><p>纸匠 FB2 00123</p></title>${(1..60).joinToString("") { "<p>第 $it 段：手机电子书转换，中文字符、页序和 200.50 金额。</p>" }}</section></body></FictionBook>"""
                )
            }
        return listOf(xps("xps"), xps("oxps"), svg, cbz, fb2)
    }
}
