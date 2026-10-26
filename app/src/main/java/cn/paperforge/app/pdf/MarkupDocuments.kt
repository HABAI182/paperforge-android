package cn.paperforge.app.pdf

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.renderer.markdown.MarkdownRenderer
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

data class MarkupSource(
    val main: File,
    val root: File?,
    val relative: String,
    val hash: String,
    val report: JSONObject?,
)

data class MarkupReview(val source: String, val result: String, val name: String)

/** Portable static documents: the original archive owns all relative assets. */
object MarkupDocuments {
    const val VERSION = "commonmark-0.30-jsoup-1.23-static-v1"
    private val extensions =
        listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            FootnotesExtension.create(),
            HeadingAnchorExtension.create(),
        )
    private val parser = Parser.builder().extensions(extensions).build()
    private val html = HtmlRenderer.builder().extensions(extensions).build()

    fun markdownToHtml(source: String) = html.render(parser.parse(source))

    fun isHtml(name: String) =
        name.substringAfterLast('.').lowercase() in setOf("html", "htm", "xhtml")

    suspend fun open(
        context: Context,
        source: File,
        preferred: String? = null,
        mainPath: String? = null,
    ): MarkupSource =
        withContext(Dispatchers.IO) {
            val hash = PdfAssembly.sha256(source)
            if (!source.extension.equals("zip", true))
                return@withContext MarkupSource(source, null, source.name, hash, null)
            val base = File(context.cacheDir, "markup-resources").canonicalFile.apply { mkdirs() }
            val directory = File(base, hash)
            val marker = File(base, "$hash.ready")
            var metadata: JSONObject? = null
            ZipFile(source).use { zip ->
                metadata =
                    zip.comment
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                        ?.takeIf { it.optString("format") == "paperforge-markup" }
                if (!marker.isFile || !directory.isDirectory) {
                    check(directory.canonicalFile.parentFile == base.canonicalFile)
                    directory.deleteRecursively()
                    directory.mkdirs()
                    var bytes = 0L
                    var count = 0
                    for (entry in zip.entries()) {
                        ensureActive()
                        count++
                        require(count <= 5000) { "资源包文件过多" }
                        val target = File(directory, entry.name).canonicalFile
                        require(target.path.startsWith(directory.canonicalPath + File.separator)) {
                            "资源包路径不合适"
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                            continue
                        }
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    bytes += n
                                    require(bytes <= 400L * 1024 * 1024) { "资源包过大" }
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                    }
                    marker.writeText(hash)
                }
            }
            val main =
                (mainPath ?: metadata?.optString("main"))
                    ?.takeIf { it.isNotBlank() }
                    ?.let { File(directory, it).canonicalFile }
                    ?: directory
                        .walkTopDown()
                        .filter {
                            it.isFile &&
                                (isHtml(it.name) ||
                                    it.extension.lowercase() in setOf("md", "markdown"))
                        }
                        .sortedWith(
                            compareBy<File>(
                                {
                                    if (
                                        preferred == null ||
                                            preferred == "HTML" && isHtml(it.name) ||
                                            preferred == "MD" &&
                                                it.extension.lowercase() in setOf("md", "markdown")
                                    )
                                        0
                                    else 1
                                },
                                { if (it.nameWithoutExtension.equals("index", true)) 0 else 1 },
                                {
                                    it.relativeTo(directory).path.count { c ->
                                        c == File.separatorChar
                                    }
                                },
                                { it.path },
                            )
                        )
                        .firstOrNull()
                    ?: error("资源包内没有 Markdown 或 HTML 文档")
            require(main.isFile && main.path.startsWith(directory.canonicalPath + File.separator)) {
                "资源包的主文档不存在"
            }
            val report =
                metadata
                    ?.optString("report")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { File(directory, it).canonicalFile }
                    ?.takeIf {
                        it.path.startsWith(directory.canonicalPath + File.separator) && it.isFile
                    }
                    ?.let { JSONObject(it.readText()) }
            MarkupSource(
                main,
                directory,
                main.relativeTo(directory).invariantSeparatorsPath,
                hash,
                report,
            )
        }

    private val markdown = MarkdownRenderer.builder().build()

    private fun escaped(text: String) = markdown.render(Text(text))

    private fun tablePipes(text: String) = buildString {
        var slashes = 0
        for (char in text) {
            if (char == '|' && slashes % 2 == 0) append('\\')
            append(char)
            slashes = if (char == '\\') slashes + 1 else 0
        }
    }

    fun htmlToMarkdown(source: String, warnings: MutableSet<String> = linkedSetOf()): String {
        val doc = Jsoup.parse(source)
        val removed = doc.select("script,iframe,object,embed").size
        doc.select("script,iframe,object,embed").remove()
        if (removed > 0) warnings += "脚本与交互嵌入不属于静态文档，已忽略；脚本绘制内容需先静态导出。"
        fun fence(text: String, min: Int = 1) =
            "`"
                .repeat(
                    maxOf(min, (Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0) + 1)
                )
        fun inline(node: Node): String {
            if (node is TextNode) return escaped(node.wholeText.replace(Regex("\\s+"), " "))
            if (node !is Element) return ""
            fun children() = node.childNodes().joinToString("") { inline(it) }
            return when (node.normalName()) {
                "strong",
                "b" -> "**${children()}**"
                "em",
                "i" -> "*${children()}*"
                "del",
                "s",
                "strike" -> "~~${children()}~~"
                "code" -> {
                    val text = node.wholeText()
                    val f = fence(text)
                    "$f $text $f"
                }
                "br" -> "  \n"
                "a" ->
                    if (node.hasAttr("href"))
                        "[${children()}](<${node.attr("href").replace("|","%7C")}>)"
                    else node.outerHtml()
                "img" -> "![${escaped(node.attr("alt"))}](<${node.attr("src").replace("|","%7C")}>)"
                "sup",
                "sub",
                "u",
                "mark" -> node.outerHtml()
                "span" -> if (node.attributes().size() > 0) node.outerHtml() else children()
                else -> children()
            }
        }
        fun block(node: Node, depth: Int = 0): String {
            if (node is TextNode) return inline(node)
            if (node !is Element) return ""
            if (depth > 120) {
                warnings += "深层嵌套作为原始 HTML 块保留。"
                return "\n\n${node.outerHtml()}\n\n"
            }
            fun nested() = node.childNodes().joinToString("") { block(it, depth + 1) }
            fun content() = node.childNodes().joinToString("") { inline(it) }.trim()
            val anchor =
                node
                    .id()
                    .takeIf { it.isNotBlank() }
                    ?.let { "<a id=\"${it.replace("\"","&quot;")}\"></a>\n\n" } ?: ""
            return anchor +
                when (node.normalName()) {
                    "h1",
                    "h2",
                    "h3",
                    "h4",
                    "h5",
                    "h6" ->
                        "\n\n${"#".repeat(node.normalName().last().digitToInt())} ${content()}\n\n"
                    "p" -> "\n\n${content()}\n\n"
                    "pre" -> {
                        val text = node.wholeText()
                        val f = fence(text, 3)
                        val language =
                            node
                                .selectFirst("code")
                                ?.classNames()
                                ?.firstOrNull { it.startsWith("language-") }
                                ?.removePrefix("language-") ?: ""
                        "\n\n$f$language\n${text.trimEnd('\n')}\n$f\n\n"
                    }
                    "ul",
                    "ol" -> {
                        var index = node.attr("start").toIntOrNull() ?: 1
                        "\n\n" +
                            node
                                .children()
                                .filter { it.normalName() == "li" }
                                .joinToString("\n") { li ->
                                    val prefix =
                                        if (node.normalName() == "ol") "${index++}. " else "- "
                                    val text =
                                        li.childNodes()
                                            .joinToString("") { block(it, depth + 1) }
                                            .trim()
                                    prefix +
                                        text
                                            .lineSequence()
                                            .joinToString("\n" + " ".repeat(prefix.length))
                                } +
                            "\n\n"
                    }
                    "blockquote" ->
                        "\n\n" +
                            nested().trim().lineSequence().joinToString("\n") { "> $it" } +
                            "\n\n"
                    "hr" -> "\n\n---\n\n"
                    "table" -> {
                        if (node.select("[rowspan],[colspan],table table").isNotEmpty()) {
                            warnings += "合并或嵌套表格保留原始 HTML，避免伪造 Markdown 单元格。"
                            "\n\n${node.outerHtml()}\n\n"
                        } else {
                            if (
                                node.selectFirst("tr")?.children()?.none {
                                    it.normalName() == "th"
                                } == true
                            )
                                warnings += "Markdown 表格使用首行作为表头。"
                            val rows =
                                node.select("tr").map { row ->
                                    row.children()
                                        .filter { it.normalName() in setOf("td", "th") }
                                        .map { cell ->
                                            cell
                                                .childNodes()
                                                .joinToString("") { inline(it) }
                                                .trim()
                                                .replace("\n", "<br>")
                                                .let(::tablePipes)
                                        }
                                }
                            val columns = rows.maxOfOrNull { it.size } ?: 0
                            if (columns == 0) ""
                            else
                                buildString {
                                    append("\n\n")
                                    rows.forEachIndexed { i, row ->
                                        append(
                                            "| " +
                                                (0 until columns).joinToString(" | ") {
                                                    row.getOrElse(it) { "" }
                                                } +
                                                " |\n"
                                        )
                                        if (i == 0)
                                            append(
                                                "| " +
                                                    List(columns) { "---" }.joinToString(" | ") +
                                                    " |\n"
                                            )
                                    }
                                    append('\n')
                                }
                        }
                    }
                    "div",
                    "section",
                    "article",
                    "main",
                    "body" ->
                        if (node.hasAttr("style") || node.hasAttr("class")) {
                            warnings += "带样式容器保留 HTML 块。"
                            "\n\n${node.outerHtml()}\n\n"
                        } else nested()
                    "style",
                    "link",
                    "svg",
                    "details",
                    "figure" -> "\n\n${node.outerHtml()}\n\n"
                    else ->
                        if (
                            node.normalName() in
                                setOf(
                                    "a",
                                    "img",
                                    "strong",
                                    "b",
                                    "em",
                                    "i",
                                    "del",
                                    "s",
                                    "code",
                                    "br",
                                    "span",
                                    "sup",
                                    "sub",
                                    "u",
                                    "mark",
                                )
                        )
                            inline(node)
                        else nested()
                }
        }
        val styles =
            doc.head().select("style,link[rel=stylesheet]").joinToString("\n") { it.outerHtml() }
        return (styles + "\n\n" + block(doc.body())).trim() + "\n"
    }

    fun styledHtml(
        source: String,
        markdown: Boolean,
        title: String,
        options: TextPdfOptions = TextPdfOptions(),
        warnings: MutableSet<String>? = null,
    ): String {
        val paper = options.paper.takeIf { it in setOf("A4", "A5", "Letter") } ?: "A4"
        val doc = Jsoup.parse(if (markdown) markdownToHtml(source) else source)
        doc.outputSettings().charset(Charsets.UTF_8)
        if (doc.title().isBlank()) doc.title(title)
        if (doc.select("script,iframe,object,embed").isNotEmpty())
            warnings?.add("脚本与交互嵌入已忽略；脚本绘制内容需先静态导出。")
        doc.select("script,iframe,object,embed").remove()
        for (element in doc.allElements) element
            .attributes()
            .asList()
            .filter { it.key.startsWith("on", true) }
            .forEach { element.removeAttr(it.key) }
        doc.head().appendElement("meta").attr("charset", "utf-8")
        doc.head()
            .appendElement("meta")
            .attr("name", "viewport")
            .attr("content", "width=device-width, initial-scale=1")
        doc.head()
            .appendElement("style")
            .attr("id", "paperforge-print-style")
            .text(
                """
            body { font-family: sans-serif; font-size:${options.fontSize}pt; line-height:${options.lineSpacing}; overflow-wrap:anywhere; margin:24px; }
            img,svg { max-width:100%; height:auto; } table { border-collapse:collapse; max-width:100%; } th,td { border:1px solid #8c9890; padding:.45em .65em; vertical-align:top; }
            pre { white-space:pre-wrap; background:#f1f3f2; padding:12px; } blockquote { border-left:3px solid #9caaa1; padding-left:12px; } a { color:#176b56; }
            @media print { body {margin:0;} thead {display:table-header-group;} tr,img,pre {break-inside:avoid;} h1,h2,h3 {break-after:avoid;} }
            @page {size:$paper ${if(options.landscape) "landscape" else "portrait"}; margin:${options.margin}pt; }
        """
                    .trimIndent()
            )
        return "<!DOCTYPE html>\n" + doc.outerHtml()
    }

    suspend fun convert(
        context: Context,
        sourceFile: File,
        name: String,
        output: File,
        target: String,
        options: TextPdfOptions,
    ): JSONObject {
        require(target in setOf("HTML", "MD"))
        val source = open(context, sourceFile, preferred = if (target == "HTML") "MD" else "HTML")
        require(
            isHtml(source.main.name) ||
                source.main.extension.lowercase() in setOf("md", "markdown", "txt")
        ) {
            "此入口支持 Markdown、HTML 和包含它们的 ZIP 资源包"
        }
        val body =
            LocalConversions.readText(
                context,
                Uri.fromFile(source.main),
                source.main.name,
                preserveMarkup = true,
            )
        val warnings = linkedSetOf<String>()
        val sourceHtml = isHtml(source.main.name)
        val converted =
            if (target == "HTML") styledHtml(body, !sourceHtml, name, options, warnings)
            else if (sourceHtml) htmlToMarkdown(body, warnings) else body
        val stem =
            if (source.root == null)
                cn.paperforge.app.model.safeFileName(name.substringBeforeLast('.'))
            else source.relative.substringBeforeLast('.')
        val extension = if (target == "HTML") "html" else "md"
        var main = "$stem.$extension"
        if (source.root != null && main != source.relative && File(source.root, main).exists()) {
            var index = 1
            do {
                main = "$stem-converted-${index++}.$extension"
            } while (File(source.root, main).exists())
        }
        val assets =
            source.root
                ?.walkTopDown()
                ?.filter {
                    it.isFile &&
                        (it.canonicalFile != source.main.canonicalFile || main != source.relative)
                }
                ?.toList()
                .orEmpty()
        val rendered = Jsoup.parse(if (target == "HTML") converted else markdownToHtml(converted))
        for (element in rendered.select("img[src],link[rel=stylesheet][href]")) {
            val ref = element.attr(if (element.normalName() == "img") "src" else "href")
            if (ref.startsWith("data:") || ref.startsWith("#")) continue
            if (ref.startsWith("http:") || ref.startsWith("https:") || ref.startsWith("//")) {
                warnings += "外部资源需联网，未打包：$ref"
                continue
            }
            val file =
                File(
                        source.main.parentFile,
                        Uri.decode(ref.substringBefore('#').substringBefore('?')),
                    )
                    .canonicalFile
            if (
                source.root == null ||
                    !file.path.startsWith(source.root.canonicalPath + File.separator) ||
                    !file.isFile
            )
                warnings += "缺少包内资源：$ref"
        }
        var reportName = "paperforge-conversion.json"
        var number = 1
        while (
            assets.any { it.relativeTo(source.root!!).invariantSeparatorsPath == reportName } ||
                reportName == main
        ) reportName = "paperforge-conversion-${++number}.json"
        val report =
            JSONObject()
                .put("format", "paperforge-markup")
                .put("version", 1)
                .put("engine", VERSION)
                .put("sourceSha256", source.hash)
                .put("sourceMain", source.relative)
                .put("main", main)
                .put("target", target)
                .put("warnings", JSONArray(warnings))
                .put("print", options.json())
                .put(
                    "assets",
                    JSONArray().apply {
                        assets.forEach {
                            put(
                                JSONObject()
                                    .put(
                                        "path",
                                        it.relativeTo(source.root!!).invariantSeparatorsPath,
                                    )
                                    .put("bytes", it.length())
                                    .put("sha256", PdfAssembly.sha256(it))
                            )
                        }
                    },
                )
        val temporary = File(output.path + ".part")
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                zip.setComment(
                    JSONObject()
                        .put("format", "paperforge-markup")
                        .put("main", main)
                        .put("report", reportName)
                        .toString()
                )
                fun entry(path: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry(main, converted.toByteArray(Charsets.UTF_8))
                entry(reportName, report.toString(2).toByteArray())
                for (file in assets) {
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(
                        ZipEntry(file.relativeTo(source.root!!).invariantSeparatorsPath)
                    )
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            currentCoroutineContext().ensureActive()
            check(temporary.renameTo(output)) { "无法保存转换结果" }
        } finally {
            temporary.delete()
        }
        return report
    }
}
