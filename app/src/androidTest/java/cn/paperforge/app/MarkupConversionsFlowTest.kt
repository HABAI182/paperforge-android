package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import java.io.File
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class MarkupConversionsFlowTest {
    private suspend fun convert(
        app: PaperForgeApp,
        source: File,
        target: String,
        name: String,
    ): LocalTaskRow {
        val input =
            JobInput(
                JSONObject()
                    .put("kind", "MARKUP")
                    .put("name", name)
                    .put("inputs", JSONArray(listOf(source.path)))
                    .put(
                        "options",
                        JSONObject()
                            .put("target", target)
                            .put("print", TextPdfOptions(14f, "A5", true, 28f, 1.5f).json())
                            .toString(),
                    )
            )
        val row = DocumentJobs.prepare(app.repository, "CONVERT", input)
        DocumentJobs.schedule(app.repository, row)
        val info =
            withTimeout(30000) {
                WorkManager.getInstance(app)
                    .getWorkInfoByIdFlow(UUID.fromString(row.workId))
                    .filterNotNull()
                    .first { it.state.isFinished }
            }
        assertEquals(info.outputData.getString("error"), WorkInfo.State.SUCCEEDED, info.state)
        assertEquals("MARKUP", info.outputData.getString("mode"))
        return app.repository.dao.task(row.id)!!
    }

    @Test
    fun packagedMarkdownHtmlRoundTripKeepsActualStructureAndAssetBytes() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val before = repo.dao.allTasks().map { it.id }.toSet()
            val source =
                File(app.filesDir, "conversion-inputs/markup-structure-proof.zip").apply {
                    parentFile!!.mkdirs()
                }
            val image =
                Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.rgb(204, 34, 68))
                }
            val buffer = java.io.ByteArrayOutputStream()
            image.compress(Bitmap.CompressFormat.PNG, 100, buffer)
            image.recycle()
            val png = buffer.toByteArray()
            val markdown =
                """# 客户汇总 Report

**加粗** 与 *斜体*，~~旧值~~。 [项目链接](https://example.com/project)

- 第一项
  1. 嵌套一
  2. 嵌套二
- 第二项

| 编号 | 金额 |
| --- | --- |
| 00123 | 00077.50 |

\`inline\` 与脚注[^1]。

\`\`\`txt
x < y && token | value
\`\`\`

![红色印章](../images/red.png)

[^1]: 原始脚注 2026
"""
                    .replace("\\`", "`")
            ZipOutputStream(source.outputStream()).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry("doc/index.md", markdown.toByteArray())
                entry("doc/index.html", "<p>KEEP EXISTING HTML</p>".toByteArray())
                entry("images/red.png", png)
            }
            val originalHash = PdfAssembly.sha256(source)
            var keep: String? = null
            try {
                val row = convert(app, source, "HTML", "Markdown 结构与资源验收.zip")
                keep = row.id
                val file = File(row.resourceEntries().first { it.id == "result" }.path)
                val opened = MarkupDocuments.open(app, file)
                assertEquals("doc/index-converted-1.html", opened.relative)
                val html = opened.main.readText()
                val doc = Jsoup.parse(html)
                assertEquals("客户汇总 Report", doc.selectFirst("h1")!!.text())
                assertEquals(
                    listOf("编号", "金额", "00123", "00077.50"),
                    doc.select("th,td").map { it.text() },
                )
                assertTrue(doc.select("ul ol").isNotEmpty())
                assertTrue(doc.select("strong").any { it.text() == "加粗" })
                assertEquals(
                    "x < y && token | value",
                    doc.selectFirst("pre code")!!.wholeText().trim(),
                )
                assertEquals("../images/red.png", doc.selectFirst("img")!!.attr("src"))
                assertTrue(html.contains("@page"))
                assertTrue(html.contains("A5 landscape"))
                assertTrue(doc.text().contains("原始脚注 2026"))
                ZipFile(file).use { zip ->
                    assertArrayEquals(
                        png,
                        zip.getInputStream(zip.getEntry("images/red.png")).readBytes(),
                    )
                    assertEquals(
                        "<p>KEEP EXISTING HTML</p>",
                        zip.getInputStream(zip.getEntry("doc/index.html")).bufferedReader().use {
                            it.readText()
                        },
                    )
                }
                val round = convert(app, file, "MD", "HTML 回到 Markdown.zip")
                val result =
                    MarkupDocuments.open(
                        app,
                        File(round.resourceEntries().first { it.id == "result" }.path),
                    )
                val actual = Jsoup.parse(MarkupDocuments.markdownToHtml(result.main.readText()))
                assertEquals("客户汇总 Report", actual.selectFirst("h1")!!.text())
                assertEquals(
                    listOf("编号", "金额", "00123", "00077.50"),
                    actual.select("th,td").map { it.text() },
                )
                assertTrue(actual.text().contains("原始脚注 2026"))
                assertEquals("../images/red.png", actual.selectFirst("img")!!.attr("src"))
                assertEquals(originalHash, PdfAssembly.sha256(source))
                file.copyTo(File(app.cacheDir, "markup-html-proof.zip"), overwrite = true)
                File(round.resourceEntries().first { it.id == "result" }.path)
                    .copyTo(File(app.cacheDir, "markup-md-proof.zip"), overwrite = true)
                File(app.cacheDir, "markup-proof.json").writeText(opened.report!!.toString(2))
            } finally {
                repo.dao
                    .allTasks()
                    .filter { it.id !in before && it.id != keep }
                    .forEach { row ->
                        if (row.state in LocalTaskRow.active) DocumentJobs.cancel(repo, row)
                        DocumentJobs.forget(repo, row.id)
                    }
            }
        }

    @Test
    fun utf16HtmlKeepsMergedTableAndStaticFallbacks() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val source = File(app.cacheDir, "markup-utf16.html")
            val body =
                """<html><head><style>td {color:#225533}</style></head><body><h2 id="source-anchor">客户 &amp; 汇总</h2><p><strong>编号</strong> 00123 <a href="#source-anchor">回到标题</a></p><table><tr><th colspan="2">合并表头</th></tr><tr><td rowspan="2">00077</td><td>120.50</td></tr><tr><td>80.00</td></tr></table><p>1. literal &amp;copy;</p><table><tr><th>A</th><th>B</th></tr><tr><td><code>v|k</code></td><td>a | b</td></tr></table><script>document.write('dynamic')</script></body></html>"""
            source.writeBytes(
                byteArrayOf(0xff.toByte(), 0xfe.toByte()) + body.toByteArray(Charsets.UTF_16LE)
            )
            val output = File(app.cacheDir, "markup-utf16-result.zip")
            try {
                val report =
                    MarkupDocuments.convert(
                        app,
                        source,
                        "UTF16.html",
                        output,
                        "MD",
                        TextPdfOptions(),
                    )
                val actual = MarkupDocuments.open(app, output)
                val text = actual.main.readText()
                assertTrue(text.contains("## 客户"))
                assertTrue(text.contains("colspan=\"2\""))
                assertTrue(text.contains("rowspan=\"2\""))
                val rendered = Jsoup.parse(MarkupDocuments.markdownToHtml(text))
                assertEquals("00077", rendered.select("td")[0].text())
                assertEquals("客户 & 汇总", rendered.selectFirst("h2")!!.text())
                assertTrue(rendered.select("p").any { it.text() == "1. literal &copy;" })
                assertEquals(
                    listOf("v|k", "a | b"),
                    rendered.select("table").last()!!.select("td").map { it.text() },
                )
                assertEquals("120.50", rendered.select("td")[1].text())
                assertTrue(rendered.select("script").isEmpty())
                assertTrue(rendered.select("a[href='#source-anchor']").isNotEmpty())
                assertTrue(report.getJSONArray("warnings").length() >= 2)
                File(app.cacheDir, "markup-utf16-proof.json").writeText(report.toString(2))
            } finally {
                source.delete()
            }
        }
}
