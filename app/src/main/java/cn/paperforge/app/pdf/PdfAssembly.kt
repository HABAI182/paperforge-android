package cn.paperforge.app.pdf

import android.content.Context
import cn.paperforge.app.data.Repository
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.*
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

data class AssemblySource(
    val documentId: String,
    val title: String,
    val count: Int,
    val sha256: String,
) {
    fun json() =
        JSONObject()
            .put("documentId", documentId)
            .put("title", title)
            .put("count", count)
            .put("sha256", sha256)
}

data class AssemblyPage(
    val key: String = UUID.randomUUID().toString(),
    val sourceId: String? = null,
    val page: Int = 0,
    val rotation: Int = 0,
) {
    fun json() =
        JSONObject()
            .put("key", key)
            .put("sourceId", sourceId ?: JSONObject.NULL)
            .put("page", page)
            .put("rotation", rotation)
}

data class AssemblyPlan(
    val sources: List<AssemblySource> = emptyList(),
    val pages: List<AssemblyPage> = emptyList(),
    val title: String = "组合文档",
    val subtitle: String = "",
    val cover: Boolean = false,
    val contents: Boolean = false,
    val formMode: String = "EDITABLE",
    val keepPageLabels: Boolean = true,
    val keepAttachments: Boolean = true,
    val keepOriginalOutlines: Boolean = true,
) {
    val chapters
        get() =
            pages
                .mapNotNull { it.sourceId }
                .distinct()
                .mapNotNull { id -> sources.firstOrNull { it.documentId == id } }

    val contentsPages
        get() = if (contents) (chapters.size + 19) / 20 else 0

    val prefixPages
        get() = (if (cover) 1 else 0) + contentsPages

    fun json() =
        JSONObject()
            .put("format", 1)
            .put("sources", JSONArray().apply { sources.forEach { put(it.json()) } })
            .put("pages", JSONArray().apply { pages.forEach { put(it.json()) } })
            .put("title", title)
            .put("subtitle", subtitle)
            .put("cover", cover)
            .put("contents", contents)
            .put("formMode", formMode)
            .put("keepPageLabels", keepPageLabels)
            .put("keepAttachments", keepAttachments)
            .put("keepOriginalOutlines", keepOriginalOutlines)

    fun remap(ids: Map<String, String>): AssemblyPlan =
        copy(
            sources =
                sources.mapNotNull { source ->
                    ids[source.documentId]?.let { source.copy(documentId = it) }
                },
            pages =
                pages.mapNotNull { page ->
                    if (page.sourceId == null) page.copy(key = UUID.randomUUID().toString())
                    else
                        ids[page.sourceId]?.let {
                            page.copy(key = UUID.randomUUID().toString(), sourceId = it)
                        }
                },
        )

    fun interleave(first: String, second: String, reverseSecond: Boolean): AssemblyPlan {
        require(first != second) { "请选择两份不同来源" }
        val front = pages.filter { it.sourceId == first }
        val back =
            pages.filter { it.sourceId == second }.let { if (reverseSecond) it.reversed() else it }
        require(front.isNotEmpty() && back.isNotEmpty()) { "两份来源都需要选页" }
        val mixed = buildList {
            for (index in 0 until maxOf(front.size, back.size)) {
                front.getOrNull(index)?.let(::add)
                back.getOrNull(index)?.let(::add)
            }
        }
        val selected = front.map { it.key }.toSet() + back.map { it.key }
        val insertion = pages.indexOfFirst { it.key in selected }
        val rest = pages.filter { it.key !in selected }.toMutableList()
        rest.addAll(insertion.coerceAtMost(rest.size), mixed)
        return copy(pages = rest)
    }

    companion object {
        fun decode(j: JSONObject): AssemblyPlan {
            require(j.optInt("format", 1) == 1) { "不支持的页面组合草稿" }
            val s = j.optJSONArray("sources") ?: JSONArray()
            val p = j.optJSONArray("pages") ?: JSONArray()
            return AssemblyPlan(
                (0 until s.length()).map {
                    s.getJSONObject(it).let { r ->
                        AssemblySource(
                            r.getString("documentId"),
                            r.getString("title"),
                            r.getInt("count"),
                            r.getString("sha256"),
                        )
                    }
                },
                (0 until p.length()).map {
                    p.getJSONObject(it).let { r ->
                        AssemblyPage(
                            r.getString("key"),
                            r.optString("sourceId").takeUnless { it.isBlank() || it == "null" },
                            r.getInt("page"),
                            r.getInt("rotation"),
                        )
                    }
                },
                j.optString("title", "组合文档"),
                j.optString("subtitle"),
                j.optBoolean("cover"),
                j.optBoolean("contents"),
                j.optString("formMode", "APPEARANCE"),
                j.optBoolean("keepPageLabels", true),
                j.optBoolean("keepAttachments", true),
                j.optBoolean("keepOriginalOutlines", true),
            )
        }
    }
}

object AssemblyDraft {
    private fun file(context: Context) =
        android.util.AtomicFile(File(context.filesDir, "assembly-draft.json"))

    @Synchronized
    fun read(context: Context): AssemblyPlan =
        runCatching {
                file(context).openRead().bufferedReader().use {
                    AssemblyPlan.decode(JSONObject(it.readText()))
                }
            }
            .getOrDefault(AssemblyPlan())

    @Synchronized
    fun write(context: Context, plan: AssemblyPlan) {
        val target = file(context)
        val stream = target.startWrite()
        try {
            stream.write(plan.json().toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }
}

object PdfAssembly {
    suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    suspend fun write(
        repo: Repository,
        plan: AssemblyPlan,
        output: File,
        progress: suspend (Int) -> Unit = {},
    ): JSONObject {
        val files =
            plan.chapters.associate { source ->
                val document =
                    repo.dao.document(source.documentId) ?: error("来源“${source.title}”已移除")
                source.documentId to File(document.pdfPath ?: error("请选择 PDF 来源"))
            }
        return writeFiles(repo.context, plan, files, output, progress)
    }

    suspend fun writeFiles(
        context: Context?,
        plan: AssemblyPlan,
        files: Map<String, File>,
        output: File,
        progress: suspend (Int) -> Unit = {},
    ): JSONObject {
        require(plan.formMode in listOf("EDITABLE", "APPEARANCE")) { "不支持的表单方式" }
        require(plan.pages.isNotEmpty() || plan.cover) { "请至少加入一页" }
        val temporary = File(output.path + ".part")
        val inputs = mutableMapOf<String, PDDocument>()
        val imported = mutableListOf<Pair<AssemblyPage, PDPage>>()
        var flattened = 0
        var semanticReport = JSONObject()
        try {
            for (source in plan.chapters) {
                currentCoroutineContext().ensureActive()
                val file = files.getValue(source.documentId)
                require(source.sha256.isBlank() || sha256(file) == source.sha256) {
                    "来源“${source.title}”已有变化，请刷新来源"
                }
                val input = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
                inputs[source.documentId] = input
                input.documentCatalog.acroForm?.let { form ->
                    require(!form.hasXFA()) { "“${source.title}”含动态表单，请先另存为普通PDF" }
                    if (plan.formMode == "APPEARANCE") {
                        flattened += form.fieldTree.count()
                        form.refreshAppearances()
                        form.flatten()
                    } else if (form.needAppearances) form.refreshAppearances()
                }
            }
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { destination ->
                val cloner = PDFCloneUtility(destination)
                val font =
                    if (plan.cover || plan.contentsPages > 0)
                        PdfTextLayer.font(requireNotNull(context), destination)
                    else null
                if (plan.cover) {
                    val page = PDPage(PDRectangle.A4).also(destination::addPage)
                    PDPageContentStream(destination, page).use { stream ->
                        stream.setNonStrokingColor(23, 91, 76)
                        stream.addRect(42f, 716f, 52f, 5f)
                        stream.fill()
                        drawText(
                            stream,
                            font!!,
                            plan.title.ifBlank { "组合文档" },
                            32f,
                            42f,
                            660f,
                            500f,
                            6,
                        )
                        drawText(stream, font, plan.subtitle, 14f, 42f, 416f, 500f, 8)
                    }
                }
                val tocPages =
                    List(plan.contentsPages) { PDPage(PDRectangle.A4).also(destination::addPage) }
                for ((index, entry) in plan.pages.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val page =
                        if (entry.sourceId == null) PDPage(PDRectangle.A4)
                        else {
                            val input = inputs.getValue(entry.sourceId)
                            require(entry.page in 0 until input.numberOfPages) { "来源页码已变化" }
                            val original = input.getPage(entry.page)
                            PDPage(rectangle(original.mediaBox)).apply {
                                cropBox = rectangle(original.cropBox)
                                bleedBox = rectangle(original.bleedBox)
                                trimBox = rectangle(original.trimBox)
                                artBox = rectangle(original.artBox)
                                rotation = original.rotation
                                original.resources?.let {
                                    resources =
                                        PDResources(
                                            cloner.cloneForNewDocument(it.cosObject)
                                                as COSDictionary
                                        )
                                }
                                for (name in
                                    listOf(
                                        COSName.CONTENTS,
                                        COSName.GROUP,
                                        COSName.USER_UNIT,
                                    )) original.cosObject.getDictionaryObject(name)?.let {
                                    cosObject.setItem(name, cloner.cloneForNewDocument(it))
                                }
                            }
                        }
                    page.rotation = ((page.rotation + entry.rotation) % 360 + 360) % 360
                    destination.addPage(page)
                    imported += entry to page
                    progress((index + 1) * 85 / plan.pages.size.coerceAtLeast(1))
                }
                val semantics = PdfSemantics(destination, inputs, imported, cloner, plan)
                semantics.copyAnnotationsAndForms()
                val outline =
                    PDDocumentOutline().also { destination.documentCatalog.documentOutline = it }
                for ((index, source) in plan.chapters.withIndex()) {
                    val first = imported.first { it.first.sourceId == source.documentId }.second
                    val pageNumber = destination.pages.indexOf(first) + 1
                    val target = PDPageFitDestination().apply { page = first }
                    val heading =
                        PDOutlineItem().apply {
                            title = source.title
                            setDestination(target)
                        }
                    outline.addLast(heading)
                    if (plan.keepOriginalOutlines) semantics.copyOutline(source.documentId, heading)
                    if (tocPages.isNotEmpty()) {
                        val toc = tocPages[index / 20]
                        val y = 720f - (index % 20) * 29f
                        PDPageContentStream(
                                destination,
                                toc,
                                PDPageContentStream.AppendMode.APPEND,
                                true,
                                true,
                            )
                            .use { stream ->
                                if (index % 20 == 0)
                                    drawText(stream, font!!, "目录", 24f, 42f, 780f, 500f, 1)
                                drawText(stream, font!!, source.title, 11f, 42f, y, 442f, 2)
                                drawText(stream, font, pageNumber.toString(), 11f, 514f, y, 45f, 1)
                            }
                        toc.annotations.add(
                            PDAnnotationLink().apply {
                                rectangle = PDRectangle(42f, y - 17f, 510f, 28f)
                                action = PDActionGoTo().apply { this.destination = target }
                            }
                        )
                    }
                }
                semanticReport = semantics.finishCatalog()
                destination.documentInformation.title = plan.title
                destination.documentInformation.creator = "PaperForge"
                currentCoroutineContext().ensureActive()
                destination.save(temporary)
            }
            currentCoroutineContext().ensureActive()
            check(temporary.renameTo(output)) { "无法保存组合结果" }
            progress(100)
            return JSONObject()
                .put("format", 1)
                .put("title", plan.title)
                .put("formsSavedAsAppearance", flattened)
                .put("omittedLinksToUnselectedPages", semanticReport.optInt("omittedLinks"))
                .put("semantics", semanticReport)
                .put(
                    "sources",
                    JSONArray().apply {
                        plan.chapters.forEach {
                            put(JSONObject().put("title", it.title).put("sha256", it.sha256))
                        }
                    },
                )
                .put(
                    "pages",
                    JSONArray().apply {
                        plan.pages.forEach { row ->
                            put(
                                JSONObject()
                                    .put(
                                        "source",
                                        plan.sources
                                            .firstOrNull { it.documentId == row.sourceId }
                                            ?.title ?: "空白页",
                                    )
                                    .put("page", row.page + 1)
                                    .put("rotation", row.rotation)
                            )
                        }
                    },
                )
        } finally {
            inputs.values.forEach { it.close() }
            temporary.delete()
        }
    }

    private fun rectangle(box: PDRectangle) =
        PDRectangle(box.lowerLeftX, box.lowerLeftY, box.width, box.height)

    private fun targetIndex(document: PDDocument, destination: PDDestination?): Int? {
        val resolved =
            if (destination is PDNamedDestination)
                document.documentCatalog.findNamedDestinationPage(destination)
            else destination
        return (resolved as? PDPageDestination)
            ?.let { target ->
                target.page?.let { document.pages.indexOf(it) } ?: target.retrievePageNumber()
            }
            ?.takeIf { it in 0 until document.numberOfPages }
    }

    private fun drawText(
        stream: PDPageContentStream,
        font: PDType0Font,
        text: String,
        size: Float,
        x: Float,
        y: Float,
        width: Float,
        maxLines: Int,
    ) {
        val lines = mutableListOf<String>()
        var line = ""
        for (code in PdfTextLayer.safe(font, text).codePoints().toArray()) {
            val next = String(Character.toChars(code))
            if (font.getStringWidth(line + next) * size / 1000 > width && line.isNotEmpty()) {
                lines += line
                line = ""
            }
            line += next
        }
        if (line.isNotEmpty()) lines += line
        stream.setNonStrokingColor(35, 45, 42)
        for ((index, value) in lines.take(maxLines).withIndex()) {
            stream.beginText()
            stream.setFont(font, size)
            stream.newLineAtOffset(x, y - index * size * 1.25f)
            stream.showText(value)
            stream.endText()
        }
    }
}
