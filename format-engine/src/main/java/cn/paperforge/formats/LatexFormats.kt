package cn.paperforge.formats

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Entities
import ru.noties.jlatexmath.JLatexMathDrawable

/** A stated common-document subset plus the mature JLaTeXMath formula renderer, fully offline. */
object LatexFormats {
    data class Prepared(val file: File, val formulas: JSONArray, val warnings: JSONArray)

    fun prepare(source: File, folder: File, options: JSONObject): Prepared {
        val assets = File(folder, "tex-assets").apply { mkdirs() }
        val zip = source.inputStream().use { it.read() == 0x50 && it.read() == 0x4b }
        val main =
            if (zip) {
                ZipFile(source).use { archive ->
                    archive
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory }
                        .forEach { entry ->
                            val destination = File(assets, entry.name).canonicalFile
                            require(
                                destination.path.startsWith(assets.canonicalPath + File.separator)
                            ) {
                                "TeX 资源路径超出项目目录"
                            }
                            destination.parentFile!!.mkdirs()
                            archive.getInputStream(entry).use { from ->
                                destination.outputStream().use { from.copyTo(it) }
                            }
                        }
                }
                val sources =
                    assets
                        .walkTopDown()
                        .filter { it.isFile && it.extension.equals("tex", true) }
                        .toList()
                sources.firstOrNull { it.name.equals("main.tex", true) }
                    ?: sources.singleOrNull()
                    ?: error("TeX 资源包请包含 main.tex，或仅保留一个主 TeX 文件")
            } else File(assets, "main.tex").apply { writeBytes(source.readBytes()) }
        val warnings =
            linkedSetOf("LaTeX 常用正文与数学排版，非完整 TeX 引擎；不运行宏包、TikZ、外部命令或参考文献工具。未支持的内容保留原文并列入报告。")
        val formulas = JSONArray()
        val footnotes = mutableListOf<String>()
        fun escape(value: String) = Entities.escape(value)
        fun read(file: File, stack: Set<String>): String {
            val canonical = file.canonicalFile
            require(canonical.path.startsWith(assets.canonicalPath + File.separator)) {
                "TeX 引用超出项目目录"
            }
            require(canonical.path !in stack) { "TeX 项目存在循环引用：${file.name}" }
            val text = canonical.readText().replace(Regex("(?m)(?<!\\\\)%.*$"), "")
            return Regex("\\\\(?:input|include)\\s*\\{([^}]+)\\}").replace(text) { match ->
                val name = match.groupValues[1]
                val included =
                    File(canonical.parentFile, if (name.endsWith(".tex")) name else "$name.tex")
                if (included.isFile) read(included, stack + canonical.path)
                else {
                    warnings.add("引用文件未找到：$name")
                    "[引用文件未找到：${escape(name)}]"
                }
            }
        }
        val all = read(main, emptySet())
        val title = Regex("\\\\title\\s*\\{([^}]*)\\}").find(all)?.groupValues?.get(1).orEmpty()
        val author = Regex("\\\\author\\s*\\{([^}]*)\\}").find(all)?.groupValues?.get(1).orEmpty()
        val body = all.substringAfter("\\begin{document}", all).substringBefore("\\end{document}")
        val fontSize = options.optDouble("fontSize", 12.0).coerceIn(6.0, 48.0)
        val scale = 300.0 / 72.0
        val sourceCaption = options.optBoolean("mathSource", false)
        fun formula(value: String, display: Boolean): String {
            return try {
                val drawable =
                    JLatexMathDrawable.builder(if (display) "\\displaystyle $value" else value)
                        .textSize((fontSize * scale).toFloat())
                        .color(Color.BLACK)
                        .background(Color.WHITE)
                        .padding(4)
                        .build()
                val bitmap =
                    Bitmap.createBitmap(
                        drawable.intrinsicWidth.coerceAtLeast(1),
                        drawable.intrinsicHeight.coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                val image = File(folder, "math-${formulas.length()}.png")
                try {
                    bitmap.eraseColor(Color.WHITE)
                    drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                    drawable.draw(Canvas(bitmap))
                    image.outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    bitmap.recycle()
                }
                val naturalWidth = drawable.intrinsicWidth / scale
                val fit = minOf(1.0, (options.optDouble("widthPt", 595.0) - 72.0) / naturalWidth)
                val width = naturalWidth * fit
                val height = drawable.intrinsicHeight / scale * fit
                formulas.put(
                    JSONObject()
                        .put("latex", value)
                        .put("renderedAs", "300 DPI PNG embedded in native PDF")
                        .put("widthPt", width)
                        .put("heightPt", height)
                )
                val imageHtml =
                    "<img src='${image.name}' style='width:${width}pt;height:${height}pt' alt='${escape(value)}'/>"
                (if (display) "<div class='equation'>$imageHtml</div>" else imageHtml) +
                    if (sourceCaption) "<small>${escape(value)}</small>" else ""
            } catch (error: Exception) {
                warnings.add("公式未能排版，保留 LaTeX 原文：${value.take(120)}（${error.message?.take(120)}）")
                "<code>${escape(value)}</code>"
            }
        }
        fun render(value: String): String {
            val html = StringBuilder()
            var at = 0
            fun argument(): String {
                while (at < value.length && value[at].isWhitespace()) at++
                if (at >= value.length || value[at] != '{') return ""
                val start = ++at
                var depth = 1
                while (at < value.length && depth > 0) {
                    if (value[at] == '\\') {
                        at += minOf(2, value.length - at)
                        continue
                    }
                    if (value[at] == '{') depth++
                    if (value[at] == '}') depth--
                    at++
                }
                return value.substring(start, if (depth == 0) at - 1 else at)
            }
            while (at < value.length) {
                val char = value[at++]
                if (char == '$') {
                    val display = at < value.length && value[at] == '$'
                    if (display) at++
                    val end = value.indexOf(if (display) "$$" else "$", at)
                    if (end < 0) html.append('$')
                    else {
                        html.append(formula(value.substring(at, end), display))
                        at = end + if (display) 2 else 1
                    }
                } else if (char == '\\') {
                    if (at >= value.length) {
                        html.append('\\')
                        break
                    }
                    val symbol = value[at]
                    if (symbol in "([") {
                        at++
                        val end = value.indexOf(if (symbol == '[') "\\]" else "\\)", at)
                        if (end >= 0) {
                            html.append(formula(value.substring(at, end), symbol == '['))
                            at = end + 2
                        } else html.append(escape("\\$symbol"))
                        continue
                    }
                    if (!symbol.isLetter()) {
                        at++
                        html.append(if (symbol == '\\') "<br/>" else escape(symbol.toString()))
                        continue
                    }
                    val start = at
                    while (at < value.length && value[at].isLetter()) at++
                    val command = value.substring(start, at)
                    if (at < value.length && value[at] == '*') at++
                    when (command) {
                        "maketitle" ->
                            html.append("<h1>${escape(title)}</h1><p>${escape(author)}</p>")
                        "chapter",
                        "section",
                        "subsection",
                        "subsubsection",
                        "paragraph" -> {
                            val tag =
                                when (command) {
                                    "chapter",
                                    "section" -> "h2"
                                    "subsection" -> "h3"
                                    else -> "h4"
                                }
                            html.append("<$tag>${render(argument())}</$tag>")
                        }
                        "textbf",
                        "emph",
                        "textit",
                        "underline",
                        "texttt" -> {
                            val tag =
                                when (command) {
                                    "textbf" -> "b"
                                    "underline" -> "u"
                                    "texttt" -> "code"
                                    else -> "i"
                                }
                            html.append("<$tag>${render(argument())}</$tag>")
                        }
                        "footnote" -> {
                            footnotes.add(render(argument()))
                            html.append("<sup>[${footnotes.size}]</sup>")
                        }
                        "href" -> {
                            val url = argument()
                            html.append("<a href='${escape(url)}'>${render(argument())}</a>")
                        }
                        "url" -> html.append(escape(argument()))
                        "label" -> html.append("<a id='${escape(argument())}'></a>")
                        "item" -> html.append("</li><li>")
                        "par",
                        "newline" -> html.append("<br/><br/>")
                        "newpage",
                        "clearpage" -> html.append("<!--pf-page-break-->")
                        "includegraphics" -> {
                            if (at < value.length && value[at] == '[')
                                at = (value.indexOf(']', at).takeIf { it >= 0 } ?: at) + 1
                            val name = argument()
                            val candidate = File(main.parentFile, name)
                            val image =
                                (listOf(candidate) +
                                        listOf("png", "jpg", "jpeg", "svg").map {
                                            File(candidate.path + ".$it")
                                        })
                                    .firstOrNull {
                                        it.isFile &&
                                            it.canonicalPath.startsWith(
                                                assets.canonicalPath + File.separator
                                            )
                                    }
                            if (image != null)
                                html.append(
                                    "<img style='max-width:100%' src='tex-assets/${image.relativeTo(assets).invariantSeparatorsPath}'/>"
                                )
                            else {
                                warnings.add("图片未找到或格式不可用：$name")
                                html.append("[图片：${escape(name)}]")
                            }
                        }
                        "begin" -> {
                            val environment = argument()
                            val close = "\\end{$environment}"
                            val end = value.indexOf(close, at)
                            if (end < 0) {
                                warnings.add("环境未闭合：$environment")
                                html.append(escape("\\begin{$environment}"))
                                continue
                            }
                            val content = value.substring(at, end)
                            at = end + close.length
                            when (environment) {
                                "equation",
                                "equation*",
                                "align",
                                "align*",
                                "gather",
                                "gather*",
                                "displaymath" ->
                                    html.append(
                                        formula(
                                            if (environment.startsWith("align"))
                                                "\\begin{aligned}$content\\end{aligned}"
                                            else content,
                                            true,
                                        )
                                    )
                                "itemize",
                                "enumerate" -> {
                                    val tag = if (environment == "itemize") "ul" else "ol"
                                    html.append(
                                        "<$tag><li>${render(content.trimStart().removePrefix("\\item"))}</li></$tag>"
                                    )
                                }
                                "center",
                                "quote",
                                "quotation",
                                "abstract",
                                "figure" -> html.append("<div>${render(content)}</div>")
                                "verbatim",
                                "lstlisting" -> html.append("<pre>${escape(content)}</pre>")
                                "tabular" -> {
                                    val rows =
                                        content
                                            .replaceFirst(Regex("^\\s*\\{[^}]*\\}"), "")
                                            .replace("\\hline", "")
                                    html.append("<table border='1'>")
                                    rows
                                        .split(Regex("\\\\\\\\"))
                                        .filter { it.isNotBlank() }
                                        .forEach { row ->
                                            html.append(
                                                "<tr>${row.split('&').joinToString("") { "<td>${render(it.trim())}</td>" }}</tr>"
                                            )
                                        }
                                    html.append("</table>")
                                }
                                else -> {
                                    warnings.add("环境保留原文：$environment")
                                    html.append(
                                        "<pre>${escape("\\begin{$environment}$content$close")}</pre>"
                                    )
                                }
                            }
                        }
                        else -> {
                            warnings.add("命令保留原文：\\$command")
                            html.append(escape("\\$command"))
                        }
                    }
                } else if (char == '\n' && at < value.length && value[at] == '\n')
                    html.append("<br/><br/>")
                else if (char == '~') html.append("&#160;")
                else if (char != '{' && char != '}') html.append(escape(char.toString()))
            }
            return html.toString()
        }
        val content = render(body)
        val result = File(folder, "latex.html")
        result.writeText(
            "<html><head><meta charset='utf-8'/><style>body{font-family:sans-serif;font-size:${fontSize}pt;color:#172b40}h1,h2,h3{page-break-after:avoid}table{border-collapse:collapse}td{padding:0.3em;border:0.5pt solid #b0bcc8}img{max-width:100%}.equation{text-align:center;margin:0.8em 0}pre{white-space:pre-wrap}small{font-size:0.7em}</style></head><body>$content${if(footnotes.isEmpty()) "" else "<hr/>" + footnotes.mapIndexed { index, note -> "<p>[${index + 1}] $note</p>" }.joinToString("")}</body></html>"
        )
        // HTML's paged-layout path does not honor explicit breaks consistently. EPUB spine
        // chapters provide an actual new page without changing the original TeX or rasterizing
        // text.
        val chapters =
            result
                .readText()
                .substringAfter("<body>")
                .substringBeforeLast("</body>")
                .split("<!--pf-page-break-->")
                .filter { it.isNotBlank() }
        val head = result.readText().substringAfter("<head>").substringBefore("</head>")
        val pictures =
            folder.listFiles().orEmpty().filter { it.name.matches(Regex("math-[0-9]+\\.png")) } +
                assets
                    .walkTopDown()
                    .filter {
                        it.isFile &&
                            it.extension.lowercase() in setOf("png", "jpg", "jpeg", "svg", "gif")
                    }
                    .toList()
        val book = File(folder, "latex.epub")
        ZipOutputStream(book.outputStream()).use { zipOut ->
            fun entry(name: String, value: String) {
                zipOut.putNextEntry(ZipEntry(name))
                zipOut.write(value.toByteArray())
                zipOut.closeEntry()
            }
            val mime = "application/epub+zip".toByteArray()
            zipOut.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mime.size.toLong()
                    compressedSize = size
                    crc = java.util.zip.CRC32().apply { update(mime) }.value
                }
            )
            zipOut.write(mime)
            zipOut.closeEntry()
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            val manifest = StringBuilder()
            chapters.forEachIndexed { index, chapter ->
                val xhtml =
                    org.jsoup.Jsoup.parse(
                        "<html xmlns='http://www.w3.org/1999/xhtml'><head>$head<title>${escape(title)}</title></head><body>$chapter</body></html>"
                    )
                xhtml.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
                entry("page-$index.xhtml", xhtml.outerHtml())
                manifest.append(
                    "<item id='p$index' href='page-$index.xhtml' media-type='application/xhtml+xml'/>"
                )
            }
            pictures.forEachIndexed { index, image ->
                val path = image.relativeTo(folder).invariantSeparatorsPath
                val mimeType =
                    when (image.extension.lowercase()) {
                        "jpg",
                        "jpeg" -> "image/jpeg"
                        "svg" -> "image/svg+xml"
                        "gif" -> "image/gif"
                        else -> "image/png"
                    }
                manifest.append(
                    "<item id='i$index' href='${escape(path)}' media-type='$mimeType'/>"
                )
                zipOut.putNextEntry(ZipEntry(path))
                image.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
            }
            entry(
                "book.opf",
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">paperforge-latex</dc:identifier><dc:title>${escape(title.ifBlank { "LaTeX" })}</dc:title><dc:language>zh</dc:language></metadata><manifest>$manifest</manifest><spine>${chapters.indices.joinToString("") { "<itemref idref='p$it'/>" }}</spine></package>""",
            )
        }
        return Prepared(book, formulas, JSONArray(warnings.toList()))
    }
}
