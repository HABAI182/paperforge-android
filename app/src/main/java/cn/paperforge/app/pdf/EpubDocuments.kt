package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import cn.paperforge.app.ocr.OcrEngine
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.parser.Parser

/** OCF/OPF order is authoritative; auxiliary documents remain addressable for notes. */
object EpubDocuments {
    const val VERSION = "epub-ocf-spine-v1"

    private data class Item(
        val id: String,
        val file: File,
        val type: String,
        val properties: Set<String>,
        val fallback: String,
    )

    private fun Element.local(name: String) =
        getAllElements().filter { it.tagName().substringAfter(':') == name }

    private fun xml(file: File) = Jsoup.parse(file.readText(), "", Parser.xmlParser())

    private fun escaped(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun relative(file: File, root: File) =
        file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath

    private fun refFile(base: File, ref: String) =
        File(base.parentFile, Uri.decode(ref.substringBefore('#').substringBefore('?')))
            .canonicalFile

    private fun external(ref: String) =
        ref.startsWith("//") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(ref)

    private fun pathUrl(file: File, root: File) = Uri.encode(relative(file, root), "/")

    private fun read(context: Context, file: File) =
        LocalConversions.readText(context, Uri.fromFile(file), file.name, preserveMarkup = true)

    private fun cssUrls(css: String, base: File, map: (File, String) -> String): String =
        Regex("url\\(\\s*(['\"]?)(.*?)\\1\\s*\\)", RegexOption.IGNORE_CASE).replace(css) { match ->
            "url(\"${map(base, match.groupValues[2])}\")"
        }

    /**
     * All resource references are resolved against their own original chapter, not the book root.
     */
    private fun rewrite(
        doc: Element,
        base: File,
        map: (File, String) -> String,
        resources: (File, String) -> String = map,
    ) {
        doc.getAllElements().forEach { element ->
            listOf("href", "src", "poster", "xlink:href").forEach { attribute ->
                if (element.hasAttr(attribute))
                    element.attr(
                        attribute,
                        (if (attribute == "href" && element.normalName() in setOf("a", "area")) map
                        else resources)(base, element.attr(attribute)),
                    )
            }
            if (element.hasAttr("style"))
                element.attr("style", cssUrls(element.attr("style"), base, resources))
            if (element.hasAttr("srcset"))
                element.removeAttr("srcset") // Static primary src remains.
            if (element.normalName() == "style")
                element.html(cssUrls(element.data(), base, resources))
        }
    }

    suspend fun open(context: Context, input: File): MarkupSource =
        withContext(Dispatchers.IO) {
            val root = MarkupDocuments.unpack(context, input)
            val container = xml(File(root, "META-INF/container.xml"))
            val packagePath =
                container
                    .local("rootfile")
                    .firstOrNull { it.attr("media-type") == "application/oebps-package+xml" }
                    ?.attr("full-path")
                    ?: container.local("rootfile").firstOrNull()?.attr("full-path")
                    ?: error("EPUB 缺少 OPF 主文档")
            val opfFile = File(root, Uri.decode(packagePath)).canonicalFile
            require(opfFile.isFile && opfFile.path.startsWith(root.path + File.separator)) {
                "EPUB 主文档不存在"
            }
            val opf = xml(opfFile)
            val warnings = linkedSetOf<String>()
            val encrypted =
                File(root, "META-INF/encryption.xml")
                    .takeIf { it.isFile }
                    ?.let { encryption ->
                        xml(encryption)
                            .local("CipherReference")
                            .map { File(root, Uri.decode(it.attr("URI"))).canonicalFile }
                            .toSet()
                    }
                    .orEmpty()
            if (encrypted.isNotEmpty()) warnings += "书中有加密或混淆资源；无法直接读取的字体使用系统字体，未对资源进行解密。"
            val items =
                opf.local("manifest").firstOrNull()?.local("item").orEmpty().mapNotNull { e ->
                    val href = e.attr("href")
                    if (external(href)) {
                        warnings += "未下载外部资源：$href"
                        null
                    } else
                        Item(
                            e.id(),
                            refFile(opfFile, href),
                            e.attr("media-type"),
                            e.attr("properties").split(Regex("\\s+")).toSet(),
                            e.attr("fallback"),
                        )
                }
            val byId = items.associateBy { it.id }
            val spine = opf.local("spine").firstOrNull() ?: error("EPUB 缺少章节顺序")
            val references = spine.local("itemref")
            val ordered =
                references.mapNotNull { reference ->
                    var item =
                        byId[reference.attr("idref")]
                            ?: error("EPUB 章节缺失：${reference.attr("idref")}")
                    fun supported(value: Item) =
                        value.type in
                            setOf("application/xhtml+xml", "text/html", "image/svg+xml") ||
                            MarkupDocuments.isHtml(value.file.name)
                    if (!supported(item) && reference.attr("linear") == "no") return@mapNotNull null
                    val visited = mutableSetOf<String>()
                    while (!supported(item)) {
                        require(visited.add(item.id)) { "EPUB 章节回退存在循环" }
                        item = byId[item.fallback] ?: error("此 EPUB 章节没有可读回退：${item.type}")
                    }
                    item
                }
            require(ordered.isNotEmpty()) { "EPUB 没有可读章节" }
            val htmlItems =
                items.filter {
                    it.type in setOf("application/xhtml+xml", "text/html") ||
                        MarkupDocuments.isHtml(it.file.name)
                }
            val chapters =
                (ordered + htmlItems.filter { "nav" !in it.properties }).distinctBy { it.file.path }
            val anchors =
                chapters.mapIndexed { i, it -> it.file.path to "epub-chapter-${i + 1}" }.toMap()
            val idMap = mutableMapOf<String, String>()
            val documents =
                chapters.associateWith { item ->
                    require(
                        item.file.isFile && item.file.path.startsWith(root.path + File.separator)
                    ) {
                        "EPUB 章节资源不存在"
                    }
                    require(item.file !in encrypted) { "章节内容无法直接读取，请先在来源软件中导出无加密副本" }
                    if (item.type == "image/svg+xml" || item.type.startsWith("image/"))
                        Jsoup.parse("<img src=\"${escaped(item.file.name)}\" alt=\"章节图片\">")
                    else {
                        require(item in htmlItems) { "此 EPUB 章节类型尚不支持：${item.type}" }
                        Jsoup.parse(read(context, item.file))
                    }
                }
            documents.forEach { (item, doc) ->
                doc.select("[id]").forEach { e ->
                    idMap[item.file.path + "#" + e.id()] =
                        anchors.getValue(item.file.path) + "-" + e.id()
                }
            }
            val map: (File, String) -> String = { base, ref ->
                if (external(ref) || ref.isBlank()) ref
                else {
                    val file = if (ref.startsWith('#')) base.canonicalFile else refFile(base, ref)
                    val fragment = ref.substringAfter('#', "").let(Uri::decode)
                    val anchor =
                        if (fragment.isNotBlank()) idMap[file.path + "#" + fragment]
                        else anchors[file.path]
                    if (anchor != null) "#$anchor"
                    else if (file.path.startsWith(root.path + File.separator))
                        pathUrl(file, root) +
                            if (fragment.isNotBlank()) "#${Uri.encode(fragment)}" else ""
                    else {
                        warnings += "资源位于书籍包外：$ref"
                        ref
                    }
                }
            }
            val resources: (File, String) -> String = { base, ref ->
                if (external(ref) || ref.isBlank()) ref
                else if (ref.startsWith('#')) map(base, ref)
                else {
                    val file = refFile(base, ref)
                    if (file.path.startsWith(root.path + File.separator))
                        pathUrl(file, root) +
                            ref.substringAfter('#', "").let { if (it.isBlank()) "" else "#$it" }
                    else ref
                }
            }
            val title =
                opf.local("title").firstOrNull()?.text().orEmpty().ifBlank {
                    input.nameWithoutExtension
                }
            val language = opf.local("language").firstOrNull()?.text().orEmpty().ifBlank { "und" }
            val book =
                Jsoup.parse(
                    "<!doctype html><html lang=\"${escaped(language)}\"><head><meta charset=\"utf-8\"></head><body></body></html>"
                )
            book.title(title)
            book
                .head()
                .appendElement("style")
                .text(
                    ".epub-chapter {break-before:page} .epub-cover {max-height:85vh;display:block;margin:auto} nav li {margin:.35em 0}"
                )
            val navItem = items.firstOrNull { "nav" in it.properties }
            val toc = book.body().appendElement("nav").attr("aria-label", "目录")
            toc.appendElement("h1").text(title)
            var navFound = false
            if (navItem != null && navItem.file.isFile) {
                val navDoc = Jsoup.parse(read(context, navItem.file))
                val nav =
                    navDoc.select("nav").firstOrNull {
                        "toc" in it.attr("epub:type").split(' ') || it.attr("role") == "doc-toc"
                    }
                if (nav != null) {
                    val copy = nav.clone()
                    rewrite(copy, navItem.file, map)
                    copy
                        .children()
                        .filter { it.normalName() in setOf("ol", "ul") }
                        .forEach { toc.appendChild(it) }
                    navFound = true
                }
            }
            if (!navFound) {
                val ncx =
                    byId[spine.attr("toc")]
                        ?: items.firstOrNull { it.type == "application/x-dtbncx+xml" }
                val list = toc.appendElement("ol")
                if (ncx != null && ncx.file.isFile) {
                    fun points(parent: Element, target: Element) {
                        parent
                            .children()
                            .filter { it.tagName().substringAfter(':') == "navPoint" }
                            .forEach { point ->
                                val li = target.appendElement("li")
                                val src =
                                    point.local("content").firstOrNull()?.attr("src").orEmpty()
                                li.appendElement("a")
                                    .attr("href", map(ncx.file, src))
                                    .text(point.local("navLabel").firstOrNull()?.text().orEmpty())
                                if (
                                    point.children().any {
                                        it.tagName().substringAfter(':') == "navPoint"
                                    }
                                )
                                    points(point, li.appendElement("ol"))
                            }
                    }
                    xml(ncx.file).local("navMap").firstOrNull()?.let { points(it, list) }
                }
                if (list.children().isEmpty())
                    ordered.forEachIndexed { i, item ->
                        list
                            .appendElement("li")
                            .appendElement("a")
                            .attr("href", "#${anchors.getValue(item.file.path)}")
                            .text(
                                documents[item]
                                    ?.selectFirst("h1,h2,title")
                                    ?.text()
                                    .orEmpty()
                                    .ifBlank { "第 ${i + 1} 章" }
                            )
                    }
            }
            val coverId =
                opf.local("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
            val cover = items.firstOrNull { "cover-image" in it.properties } ?: byId[coverId]
            if (
                cover != null &&
                    cover.file.isFile &&
                    cover.file !in encrypted &&
                    documents.values.none { d ->
                        d.select("img").any {
                            refFile(
                                chapters.firstOrNull { documents[it] === d }!!.file,
                                it.attr("src"),
                            ) == cover.file
                        }
                    }
            ) {
                toc.before(
                    Element("img")
                        .attr("class", "epub-cover")
                        .attr("alt", "$title · 封面")
                        .attr("src", pathUrl(cover.file, root))
                )
            }
            documents.forEach { (item, doc) ->
                currentCoroutineContext().ensureActive()
                doc.select("base").remove()
                doc.select("[id]").forEach { e ->
                    e.attr("id", idMap.getValue(item.file.path + "#" + e.id()))
                }
                rewrite(doc, item.file, map, resources)
                doc.head().select("style,link[rel=stylesheet]").forEach {
                    book.head().appendChild(it.clone())
                }
                val section =
                    book
                        .body()
                        .appendElement("section")
                        .attr("class", "epub-chapter")
                        .attr("id", anchors.getValue(item.file.path))
                        .attr("data-epub-path", relative(item.file, root))
                if (
                    item !in ordered ||
                        references.getOrNull(ordered.indexOf(item))?.attr("linear") == "no"
                )
                    section.attr("data-auxiliary", "true")
                doc.body().childNodes().toList().forEach { section.appendChild(it.clone()) }
            }
            if (
                opf.local("meta").any {
                    it.attr("property") == "rendition:layout" && it.text() == "pre-paginated"
                }
            )
                warnings += "固定版面 EPUB 按原始静态 HTML/CSS 展示；纸张 PDF 会重新分页，请对照原书检查。"
            if (documents.values.any { it.select("script").isNotEmpty() })
                warnings += "脚本和多媒体交互仅保留静态内容。"
            warnings += "各章原样式在合并预览中共享，复杂选择器或多媒体请对照原书。"
            val ownReport =
                File(root, "paperforge-conversion.json")
                    .takeIf { it.isFile }
                    ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            val report =
                (ownReport ?: JSONObject())
                    .put(
                        "epub",
                        JSONObject()
                            .put("engine", VERSION)
                            .put("title", title)
                            .put("language", language)
                            .put("spine", JSONArray(ordered.map { relative(it.file, root) }))
                            .put(
                                "auxiliary",
                                JSONArray(
                                    chapters
                                        .filter { it !in ordered }
                                        .map { relative(it.file, root) }
                                ),
                            ),
                    )
                    .put("warnings", JSONArray(warnings))
            var name = "paperforge-book-${VERSION}.html"
            while (items.any { relative(it.file, root) == name }) name = "_$name"
            val main = File(root, name)
            main.writeText(MarkupDocuments.styledHtml(book.outerHtml(), false, title))
            MarkupSource(main, root, name, PdfAssembly.sha256(input), report)
        }

    private fun xhtml(doc: Document, language: String): String {
        doc.selectFirst("html")!!
            .attr("xmlns", "http://www.w3.org/1999/xhtml")
            .attr("xmlns:epub", "http://www.idpf.org/2007/ops")
            .attr("lang", language)
            .attr("xml:lang", language)
        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + doc.outerHtml()
    }

    suspend fun write(
        context: Context,
        input: File,
        name: String,
        output: File,
        options: JSONObject,
        engine: OcrEngine,
        progress: suspend (Int, String) -> Unit,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val source =
                if (input.extension.equals("pdf", true))
                    pdfSource(context, input, name, engine, options, progress)
                else MarkupDocuments.open(context, input)
            val raw = read(context, source.main)
            val html =
                if (MarkupDocuments.isHtml(source.main.name)) raw
                else if (source.main.extension.equals("txt", true)) "<pre>${escaped(raw)}</pre>"
                else MarkupDocuments.markdownToHtml(raw)
            val warnings = linkedSetOf<String>()
            source.report?.optJSONArray("warnings")?.let { array ->
                for (i in 0 until array.length()) warnings += array.getString(i)
            }
            val clean =
                Jsoup.parse(MarkupDocuments.styledHtml(html, false, name, warnings = warnings))
            clean.select("base").remove()
            val title =
                options.optString("title").ifBlank {
                    clean.title().ifBlank { name.substringBeforeLast('.') }
                }
            val language = options.optString("language", "und").ifBlank { "und" }
            val author = options.optString("author")
            clean.select("h1,h2,h3,h4,h5,h6").forEachIndexed { i, h ->
                if (h.id().isBlank()) h.attr("id", "paperforge-heading-${i + 1}")
            }
            val groups = mutableListOf<MutableList<org.jsoup.nodes.Node>>()
            val existing = clean.body().children().any { it.hasAttr("data-epub-path") }
            val splitTag =
                if (clean.select("h1").size <= 1 && clean.select("h2").size > 1) "h2" else "h1"
            clean
                .body()
                .childNodes()
                .filterNot { it is org.jsoup.nodes.TextNode && it.isBlank }
                .forEach { node ->
                    if (
                        groups.isEmpty() ||
                            node is Element &&
                                (if (existing) node.hasAttr("data-epub-path")
                                else node.normalName() == splitTag) &&
                                groups.last().isNotEmpty()
                    )
                        groups += mutableListOf<org.jsoup.nodes.Node>()
                    groups.last() += node.clone()
                }
            require(groups.isNotEmpty()) { "文档没有可转换的内容" }
            val chapterNames =
                groups.indices.map { "chapter-${(it + 1).toString().padStart(4, '0')}.xhtml" }
            val idChapters = mutableMapOf<String, String>()
            groups.forEachIndexed { i, nodes ->
                nodes.filterIsInstance<Element>().forEach { e ->
                    e.getAllElements().forEach {
                        if (it.id().isNotBlank()) idChapters.putIfAbsent(it.id(), chapterNames[i])
                    }
                }
            }
            val map: (File, String) -> String = { base, ref ->
                if (external(ref) || ref.isBlank()) ref
                else {
                    val file = if (ref.startsWith('#')) base.canonicalFile else refFile(base, ref)
                    val fragment = Uri.decode(ref.substringAfter('#', ""))
                    if (file == source.main.canonicalFile && fragment in idChapters)
                        "${idChapters.getValue(fragment)}#${Uri.encode(fragment)}"
                    else if (
                        source.root != null &&
                            file.isFile &&
                            file.path.startsWith(source.root.canonicalPath + File.separator)
                    )
                        "resources/${pathUrl(file, source.root)}" +
                            if (fragment.isNotBlank()) "#${Uri.encode(fragment)}" else ""
                    else {
                        warnings += "资源未打包：$ref"
                        ref
                    }
                }
            }
            val chapters =
                groups.mapIndexed { i, nodes ->
                    val doc = Jsoup.parse("<!doctype html><html><head></head><body></body></html>")
                    clean
                        .head()
                        .children()
                        .filter { it.normalName() in setOf("style", "link", "meta") }
                        .forEach { doc.head().appendChild(it.clone()) }
                    nodes.forEach { doc.body().appendChild(it) }
                    rewrite(doc, source.main, map)
                    val chapterTitle =
                        doc.selectFirst("h1,h2,h3")?.text().orEmpty().ifBlank {
                            if (i == 0) title else "第 ${i + 1} 节"
                        }
                    doc.title(chapterTitle)
                    chapterTitle to xhtml(doc, language)
                }
            val used = linkedSetOf<File>()
            fun resource(base: File, ref: String) {
                if (ref.isBlank() || ref.startsWith('#') || external(ref)) return
                val file = refFile(base, ref)
                if (
                    source.root != null &&
                        file.isFile &&
                        file != source.main.canonicalFile &&
                        file.path.startsWith(source.root.canonicalPath + File.separator)
                )
                    used += file
            }
            clean.getAllElements().forEach { e ->
                listOf("href", "src", "poster", "xlink:href").forEach {
                    if (e.hasAttr(it)) resource(source.main, e.attr(it))
                }
                if (e.hasAttr("style"))
                    cssUrls(e.attr("style"), source.main) { base, ref ->
                        resource(base, ref)
                        ref
                    }
                if (e.normalName() == "style")
                    cssUrls(e.data(), source.main) { base, ref ->
                        resource(base, ref)
                        ref
                    }
            }
            val inspected = mutableSetOf<File>()
            while (used.any { it !in inspected }) {
                val file = used.first { it !in inspected }
                inspected += file
                if (file.extension.equals("css", true)) {
                    val css = file.readText()
                    cssUrls(css, file) { base, ref ->
                        resource(base, ref)
                        ref
                    }
                    Regex("@import\\s+['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                        .findAll(css)
                        .forEach { resource(file, it.groupValues[1]) }
                }
            }
            val assets = used.toList()
            val firstImage =
                if (options.optBoolean("cover", true))
                    clean
                        .selectFirst("img[src]")
                        ?.attr("src")
                        ?.takeUnless(::external)
                        ?.let { refFile(source.main, it) }
                        ?.takeIf { it in assets }
                else null
            val nav =
                Jsoup.parse(
                    "<!doctype html><html><head><title>目录</title></head><body><nav epub:type=\"toc\" id=\"toc\"><h1>目录</h1><ol></ol></nav></body></html>"
                )
            val ol = nav.selectFirst("ol")!!
            chapters.forEachIndexed { i, c ->
                ol.appendElement("li")
                    .appendElement("a")
                    .attr("href", chapterNames[i])
                    .text(c.first)
            }
            val id = "urn:uuid:${UUID.randomUUID()}"
            val manifest = buildString {
                append(
                    "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>"
                )
                chapters.indices.forEach {
                    append(
                        "<item id=\"c$it\" href=\"${chapterNames[it]}\" media-type=\"application/xhtml+xml\"/>"
                    )
                }
                assets.forEachIndexed { i, file ->
                    val fallback =
                        if (file.extension.equals("pdf", true)) " fallback=\"c0\"" else ""
                    append(
                        "<item id=\"r$i\" href=\"resources/${escaped(pathUrl(file, source.root!!))}\" media-type=\"${media(file)}\"$fallback${if (file == firstImage) " properties=\"cover-image\"" else ""}/>"
                    )
                }
            }
            val opf =
                """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">$id</dc:identifier><dc:title>${escaped(title)}</dc:title><dc:language>${escaped(language)}</dc:language>${if (author.isNotBlank()) "<dc:creator>${escaped(author)}</dc:creator>" else ""}<meta property="dcterms:modified">${Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)}</meta></metadata><manifest>$manifest</manifest><spine>${chapters.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }}${assets.mapIndexedNotNull { i, file -> if (file.extension.equals("pdf", true)) "<itemref idref=\"r$i\" linear=\"no\"/>" else null }.joinToString("")}</spine></package>"""
            val report =
                JSONObject()
                    .put("format", "paperforge-epub")
                    .put("engine", VERSION)
                    .put("sourceSha256", PdfAssembly.sha256(input))
                    .put("sourceMain", source.relative)
                    .put("title", title)
                    .put("language", language)
                    .put("chapters", JSONArray(chapters.map { it.first }))
                    .put("warnings", JSONArray(warnings))
                    .put("sourceDetails", source.report)
                    .put(
                        "assets",
                        JSONArray(
                            assets.map {
                                JSONObject()
                                    .put("path", "EPUB/resources/${relative(it, source.root!!)}")
                                    .put("sha256", PdfAssembly.sha256(it))
                            }
                        ),
                    )
            val part = File(output.path + ".part")
            try {
                ZipOutputStream(part.outputStream().buffered()).use { zip ->
                    fun entry(path: String, bytes: ByteArray, stored: Boolean = false) {
                        val e = ZipEntry(path)
                        if (stored) {
                            e.method = ZipEntry.STORED
                            e.size = bytes.size.toLong()
                            e.crc = CRC32().apply { update(bytes) }.value
                        }
                        zip.putNextEntry(e)
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                    entry("mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII), true)
                    entry(
                        "META-INF/container.xml",
                        """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
                            .toByteArray(),
                    )
                    entry("EPUB/package.opf", opf.toByteArray())
                    entry("EPUB/nav.xhtml", xhtml(nav, language).toByteArray())
                    chapters.forEachIndexed { i, c ->
                        entry("EPUB/${chapterNames[i]}", c.second.toByteArray())
                    }
                    assets.forEach { file ->
                        currentCoroutineContext().ensureActive()
                        zip.putNextEntry(
                            ZipEntry("EPUB/resources/${relative(file, source.root!!)}")
                        )
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    entry("paperforge-conversion.json", report.toString(2).toByteArray())
                }
                currentCoroutineContext().ensureActive()
                check(part.renameTo(output)) { "无法保存 EPUB" }
            } finally {
                part.delete()
            }
            report
        }

    private fun media(file: File): String =
        when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg",
            "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "css" -> "text/css"
            "html",
            "htm",
            "xhtml" -> "application/xhtml+xml"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }

    private suspend fun pdfSource(
        context: Context,
        input: File,
        name: String,
        engine: OcrEngine,
        options: JSONObject,
        progress: suspend (Int, String) -> Unit,
    ): MarkupSource {
        val root =
            File(context.cacheDir, "markup-resources/${PdfAssembly.sha256(input)}-pdf-epub")
                .canonicalFile
                .apply { mkdirs() }
        val doc = Jsoup.parse("<!doctype html><html><head></head><body></body></html>")
        doc.title(name.substringBeforeLast('.'))
        val info = ReaderDocument.info(input)
        val report =
            JSONObject()
                .put("source", "PDF")
                .put(
                    "layout",
                    "Editable text in declared reading order; original page images and embedded PDF provide explicit layout fallback",
                )
        val pages = JSONArray()
        input.copyTo(File(root, "original.pdf"), overwrite = true)
        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    currentCoroutineContext().ensureActive()
                    progress(
                        10 + 70 * i / renderer.pageCount,
                        "正在重排第 ${i + 1} / ${renderer.pageCount} 页",
                    )
                    val section =
                        doc.body()
                            .appendElement("section")
                            .attr("data-epub-path", "page-${i + 1}")
                            .attr("id", "source-page-${i + 1}")
                    section
                        .appendElement("h1")
                        .text(info.outline.firstOrNull { it.page == i }?.title ?: "第 ${i + 1} 页")
                    var blocks =
                        ReaderDocument.text(input, i).filter {
                            it.text.isNotBlank() && '\uFFFD' !in it.text
                        }
                    var usedOcr = false
                    val imageName = "page-${i + 1}.jpg"
                    renderer.openPage(i).use { page ->
                        val scale = 1600f / maxOf(page.width, page.height)
                        val image =
                            Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                        try {
                            image.eraseColor(Color.WHITE)
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            File(root, imageName).outputStream().use {
                                image.compress(Bitmap.CompressFormat.JPEG, 90, it)
                            }
                            if (blocks.sumOf { it.text.length } < 5) {
                                val result = engine.recognizeBitmap(image)
                                blocks =
                                    result.lines.mapNotNull { line ->
                                        if (line.corners.isEmpty()) null
                                        else
                                            ReaderTextBlock(
                                                line.text,
                                                line.corners.minOf { it.x },
                                                line.corners.minOf { it.y },
                                                line.corners.maxOf { it.x },
                                                line.corners.maxOf { it.y },
                                                "ocr",
                                            )
                                    }
                                usedOcr = true
                            }
                        } finally {
                            image.recycle()
                        }
                    }
                    ReaderDocument.order(blocks, options.optInt("columns", 0)).forEach {
                        section
                            .appendElement("p")
                            .attr("data-source-page", "${i + 1}")
                            .text(it.text)
                    }
                    val details = section.appendElement("details")
                    details.appendElement("summary").text("原页对照 · 表格 / 公式 / 图片")
                    details
                        .appendElement("img")
                        .attr("src", imageName)
                        .attr("alt", "原始第 ${i + 1} 页")
                    details
                        .appendElement("a")
                        .attr("href", "original.pdf#page=${i + 1}")
                        .text("打开原 PDF 第 ${i + 1} 页")
                    pages.put(
                        JSONObject()
                            .put("page", i + 1)
                            .put("textBlocks", blocks.size)
                            .put("ocr", usedOcr)
                            .put("reference", imageName)
                    )
                }
            }
        }
        report.put("pages", pages)
        val main = File(root, "book.html").apply { writeText(doc.outerHtml()) }
        return MarkupSource(main, root, main.name, PdfAssembly.sha256(input), report)
    }
}
