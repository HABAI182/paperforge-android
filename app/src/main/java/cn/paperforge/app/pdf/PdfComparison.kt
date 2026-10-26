package cn.paperforge.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import cn.paperforge.app.data.*
import cn.paperforge.app.ui.PdfSession
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

data class PdfDiffOptions(
    val threshold: Int = 24,
    val ignoreWhitespace: Boolean = true,
    val ignoreCase: Boolean = false,
    val ignoreColor: Boolean = false,
    val top: Float = 0f,
    val bottom: Float = 0f,
    val leftStart: Int = 0,
    val rightStart: Int = 0,
) {
    fun json() =
        JSONObject()
            .put("threshold", threshold)
            .put("ignoreWhitespace", ignoreWhitespace)
            .put("ignoreCase", ignoreCase)
            .put("ignoreColor", ignoreColor)
            .put("top", top)
            .put("bottom", bottom)
            .put("leftStart", leftStart)
            .put("rightStart", rightStart)

    fun normalized(text: String): String {
        var value = if (ignoreWhitespace) text.replace(Regex("\\s+"), "") else text
        if (ignoreCase) value = value.lowercase(java.util.Locale.ROOT)
        return value
    }

    companion object {
        fun decode(j: JSONObject) =
            PdfDiffOptions(
                j.optInt("threshold", 24).coerceIn(1, 255),
                j.optBoolean("ignoreWhitespace", true),
                j.optBoolean("ignoreCase"),
                j.optBoolean("ignoreColor"),
                j.optDouble("top").toFloat().coerceIn(0f, .4f),
                j.optDouble("bottom").toFloat().coerceIn(0f, .4f),
                j.optInt("leftStart").coerceAtLeast(0),
                j.optInt("rightStart").coerceAtLeast(0),
            )
    }
}

data class DiffRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun json() = JSONArray(listOf(left, top, right, bottom))

    companion object {
        val Full = DiffRect(0f, 0f, 1f, 1f)

        fun decode(j: JSONArray) =
            DiffRect(
                j.getDouble(0).toFloat(),
                j.getDouble(1).toFloat(),
                j.getDouble(2).toFloat(),
                j.getDouble(3).toFloat(),
            )
    }
}

data class PdfDifference(
    val id: String,
    val kind: String,
    val left: DiffRect?,
    val right: DiffRect?,
    val before: String = "",
    val after: String = "",
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("kind", kind)
            .put("left", left?.json() ?: JSONObject.NULL)
            .put("right", right?.json() ?: JSONObject.NULL)
            .put("before", before)
            .put("after", after)

    companion object {
        fun decode(j: JSONObject) =
            PdfDifference(
                j.getString("id"),
                j.getString("kind"),
                j.optJSONArray("left")?.let(DiffRect::decode),
                j.optJSONArray("right")?.let(DiffRect::decode),
                j.optString("before"),
                j.optString("after"),
            )
    }
}

data class PdfDiffPage(
    val index: Int,
    val leftPage: Int?,
    val rightPage: Int?,
    val leftImage: String?,
    val rightImage: String?,
    val differences: List<PdfDifference>,
    val notes: List<String>,
) {
    fun json() =
        JSONObject()
            .put("index", index)
            .put("leftPage", leftPage ?: JSONObject.NULL)
            .put("rightPage", rightPage ?: JSONObject.NULL)
            .put("leftImage", leftImage ?: JSONObject.NULL)
            .put("rightImage", rightImage ?: JSONObject.NULL)
            .put("differences", JSONArray().apply { differences.forEach { put(it.json()) } })
            .put("notes", JSONArray(notes))

    companion object {
        fun decode(j: JSONObject): PdfDiffPage {
            val differences = j.getJSONArray("differences")
            val notes = j.getJSONArray("notes")
            return PdfDiffPage(
                j.getInt("index"),
                if (j.isNull("leftPage")) null else j.getInt("leftPage"),
                if (j.isNull("rightPage")) null else j.getInt("rightPage"),
                j.optString("leftImage").takeUnless { it.isBlank() || it == "null" },
                j.optString("rightImage").takeUnless { it.isBlank() || it == "null" },
                List(differences.length()) { PdfDifference.decode(differences.getJSONObject(it)) },
                List(notes.length()) { notes.getString(it) },
            )
        }
    }
}

/** Read-only, normalized-page comparison. No source is rewritten or inferred from a thumbnail. */
object PdfComparison {
    const val VERSION = "native-lines-normalized-grid-v1"

    private fun atomicWrite(file: File, write: (java.io.OutputStream) -> Unit) {
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            write(stream)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun bounds(lines: List<ReaderTextBlock>): DiffRect? =
        lines
            .takeIf { it.isNotEmpty() }
            ?.let {
                DiffRect(
                    it.minOf { b -> b.left },
                    it.minOf { b -> b.top },
                    it.maxOf { b -> b.right },
                    it.maxOf { b -> b.bottom },
                )
            }

    fun textDifferences(
        left: List<ReaderTextBlock>,
        right: List<ReaderTextBlock>,
        options: PdfDiffOptions,
    ): List<PdfDifference> {
        fun eligible(blocks: List<ReaderTextBlock>) =
            blocks.filter {
                val center = (it.top + it.bottom) / 2
                center >= options.top &&
                    center <= 1 - options.bottom &&
                    options.normalized(it.text).isNotEmpty()
            }
        val initialA = eligible(left)
        val initialB = eligible(right)
        var start = 0
        var endA = initialA.size
        var endB = initialB.size
        while (
            start < endA &&
                start < endB &&
                options.normalized(initialA[start].text) == options.normalized(initialB[start].text)
        ) start++
        while (
            endA > start &&
                endB > start &&
                options.normalized(initialA[endA - 1].text) ==
                    options.normalized(initialB[endB - 1].text)
        ) {
            endA--
            endB--
        }
        val a = initialA.subList(start, endA)
        val b = initialB.subList(start, endB)
        val ak = a.map { options.normalized(it.text) }
        val bk = b.map { options.normalized(it.text) }
        // Hunt-Szymanski LCS uses a sparse match graph instead of a quadratic text matrix.
        val positions = bk.withIndex().groupBy({ it.value }, { it.index })
        data class Match(val a: Int, val b: Int, val previous: Int)
        val links = mutableListOf<Match>()
        val tails = mutableListOf<Int>()
        ak.forEachIndexed { i, key ->
            positions[key].orEmpty().asReversed().forEach { j ->
                var lo = 0
                var hi = tails.size
                while (lo < hi) {
                    val mid = (lo + hi) / 2
                    if (links[tails[mid]].b < j) lo = mid + 1 else hi = mid
                }
                val link = links.size
                links += Match(i, j, if (lo == 0) -1 else tails[lo - 1])
                if (lo == tails.size) tails += link else tails[lo] = link
            }
        }
        val matches = mutableListOf<Pair<Int, Int>>()
        var link = tails.lastOrNull() ?: -1
        while (link >= 0) {
            val m = links[link]
            matches += m.a to m.b
            link = m.previous
        }
        matches.reverse()
        matches += a.size to b.size
        val result = mutableListOf<PdfDifference>()
        var ai = 0
        var bi = 0
        matches.forEach { (i, j) ->
            if (ai < i || bi < j) {
                val removed = a.subList(ai, i)
                val added = b.subList(bi, j)
                result +=
                    PdfDifference(
                        "text-${result.size}",
                        "text",
                        bounds(removed),
                        bounds(added),
                        removed.joinToString("\n") { it.text },
                        added.joinToString("\n") { it.text },
                    )
            }
            ai = i + 1
            bi = j + 1
        }
        return result
    }

    fun visualDifferences(left: Bitmap, right: Bitmap, options: PdfDiffOptions): List<DiffRect> {
        val width = 768
        val height = 1088
        val block = 16
        val columns = width / block
        val rows = height / block
        val a = Bitmap.createScaledBitmap(left, width, height, true)
        val b = Bitmap.createScaledBitmap(right, width, height, true)
        try {
            val ap = IntArray(width * height)
            val bp = IntArray(ap.size)
            a.getPixels(ap, 0, width, 0, 0, width, height)
            b.getPixels(bp, 0, width, 0, 0, width, height)
            val changed = IntArray(columns * rows)
            for (y in
                (height * options.top).toInt() until
                    (height * (1 - options.bottom)).toInt()) for (x in 0 until width) {
                val ac = ap[y * width + x]
                val bc = bp[y * width + x]
                val delta =
                    if (options.ignoreColor)
                        abs(
                            (Color.red(ac) * 299 + Color.green(ac) * 587 + Color.blue(ac) * 114) -
                                (Color.red(bc) * 299 + Color.green(bc) * 587 + Color.blue(bc) * 114)
                        ) / 1000
                    else
                        maxOf(
                            abs(Color.red(ac) - Color.red(bc)),
                            abs(Color.green(ac) - Color.green(bc)),
                            abs(Color.blue(ac) - Color.blue(bc)),
                        )
                if (delta > options.threshold) changed[(y / block) * columns + x / block]++
            }
            val active = BooleanArray(changed.size) { changed[it] >= 4 }
            val boxes = mutableListOf<DiffRect>()
            for (seed in active.indices) {
                if (!active[seed]) continue
                val queue = java.util.ArrayDeque<Int>()
                queue.add(seed)
                active[seed] = false
                var l = columns
                var t = rows
                var r = 0
                var d = 0
                while (queue.isNotEmpty()) {
                    val cell = queue.removeFirst()
                    val x = cell % columns
                    val y = cell / columns
                    l = min(l, x)
                    r = max(r, x + 1)
                    t = min(t, y)
                    d = max(d, y + 1)
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until columns || ny !in 0 until rows) continue
                        val next = ny * columns + nx
                        if (active[next]) {
                            active[next] = false
                            queue.add(next)
                        }
                    }
                }
                boxes +=
                    DiffRect(
                        l.toFloat() / columns,
                        t.toFloat() / rows,
                        r.toFloat() / columns,
                        d.toFloat() / rows,
                    )
            }
            return boxes
        } finally {
            if (a !== left) a.recycle()
            if (b !== right) b.recycle()
        }
    }

    suspend fun run(
        repo: Repository,
        job: JobHandle,
        output: File,
        progress: suspend (Int, String) -> Unit,
    ) {
        val files = job.input.getStringArray("inputs")!!.map(::File)
        require(files.size == 2) { "请选择两份 PDF" }
        val names = job.input.getStringArray("names") ?: arrayOf("左侧文档", "右侧文档")
        val options = PdfDiffOptions.decode(JSONObject(job.input.getString("options") ?: "{}"))
        val hashes = files.map { PdfAssembly.sha256(it) }
        val signature =
            "$VERSION|${PdfGlyphGeometry.VERSION}|${hashes.joinToString()}|${options.json()}"
        val directory = File(repo.context.filesDir, "job-items/${job.row.id}").apply { mkdirs() }
        val pages = mutableListOf<PdfDiffPage>()
        PdfSession(files[0], 1).use { left ->
            PdfSession(files[1], 1).use { right ->
                require(options.leftStart < left.count && options.rightStart < right.count) {
                    "起始页超出文档页数"
                }
                val count = max(left.count - options.leftStart, right.count - options.rightStart)
                for (index in 0 until count) {
                    currentCoroutineContext().ensureActive()
                    val key = "diff-page-$index"
                    val metadata =
                        JSONObject(repo.dao.task(job.row.id)!!.payload)
                            .getJSONObject("checkpoint")
                            .optJSONObject(key)
                    val cached =
                        metadata
                            ?.takeIf { it.optString("signature") == signature }
                            ?.optString("path")
                            ?.let(::File)
                            ?.takeIf { it.isFile }
                            ?.let { file ->
                                runCatching {
                                        PdfDiffPage.decode(
                                            JSONObject(
                                                android.util
                                                    .AtomicFile(file)
                                                    .openRead()
                                                    .bufferedReader()
                                                    .use { it.readText() }
                                            )
                                        )
                                    }
                                    .getOrNull()
                            }
                    val imageFiles =
                        listOfNotNull(cached?.leftImage, cached?.rightImage).map {
                            File(directory, it)
                        }
                    if (cached != null && imageFiles.all { it.isFile }) pages += cached
                    else {
                        val li = (index + options.leftStart).takeIf { it < left.count }
                        val ri = (index + options.rightStart).takeIf { it < right.count }
                        val lb = li?.let { left.render(it, 1100) }
                        val rb = ri?.let { right.render(it, 1100) }
                        try {
                            val differences = mutableListOf<PdfDifference>()
                            val notes = mutableListOf<String>()
                            if (lb == null || rb == null)
                                differences +=
                                    PdfDifference(
                                        "page",
                                        if (lb == null) "added-page" else "removed-page",
                                        lb?.let { DiffRect.Full },
                                        rb?.let { DiffRect.Full },
                                    )
                            else {
                                val lt = ReaderDocument.text(files[0], li!!)
                                val rt = ReaderDocument.text(files[1], ri!!)
                                if (lt.isNotEmpty() && rt.isNotEmpty())
                                    differences += textDifferences(lt, rt, options)
                                differences +=
                                    visualDifferences(lb, rb, options).mapIndexed { n, rect ->
                                        PdfDifference("visual-$n", "visual", rect, rect)
                                    }
                                if (lt.isEmpty() || rt.isEmpty())
                                    notes += "至少一侧没有可用文字层，此侧只核对外观；可先为扫描 PDF 添加 OCR。"
                                if (
                                    abs(
                                        lb.width.toFloat() / lb.height -
                                            rb.width.toFloat() / rb.height
                                    ) > .005f
                                ) {
                                    differences +=
                                        PdfDifference(
                                            "page-size",
                                            "page-size",
                                            DiffRect.Full,
                                            DiffRect.Full,
                                        )
                                    notes += "页面比例不同；外观按归一化页面比较，原尺寸可回原 PDF 核对。"
                                }
                            }
                            suspend fun saveImage(bitmap: Bitmap?, side: String): String? {
                                if (bitmap == null) return null
                                val name = "$side-$index.png"
                                val image = File(directory, name)
                                atomicWrite(image) {
                                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                                }
                                DocumentJobs.checkpointFile(
                                    repo,
                                    job,
                                    "$key-$side",
                                    image,
                                    JSONObject().put("signature", signature),
                                )
                                return name
                            }
                            val page =
                                PdfDiffPage(
                                    index,
                                    li,
                                    ri,
                                    saveImage(lb, "left"),
                                    saveImage(rb, "right"),
                                    differences,
                                    notes,
                                )
                            val saved = File(directory, "$key.json")
                            atomicWrite(saved) {
                                it.write(page.json().toString().toByteArray(Charsets.UTF_8))
                            }
                            DocumentJobs.checkpointFile(
                                repo,
                                job,
                                key,
                                saved,
                                JSONObject().put("signature", signature),
                            )
                            pages += page
                        } finally {
                            lb?.recycle()
                            rb?.recycle()
                        }
                    }
                    progress((index + 1) * 90 / count, "比较 ${index+1} / $count 组页面")
                }
            }
        }
        val report =
            JSONObject()
                .put("format", 1)
                .put("engine", VERSION)
                .put("options", options.json())
                .put("names", JSONArray(names.toList()))
                .put("sourceSha256", JSONArray(hashes))
                .put("sources", JSONArray(listOf("left.pdf", "right.pdf")))
                .put("pages", JSONArray().apply { pages.forEach { put(it.json()) } })
                .put("ignored", JSONArray())
                .put(
                    "notes",
                    JSONArray(
                        listOf(
                            "按所选起始页依次配对，可调整页偏移核对插页；不自动声称完成语义页面对齐。",
                            "外观差异使用1100像素参考图和归一化网格；不是逐字节或印刷色彩鉴定。",
                        )
                    ),
                )
        writeArchive(output, directory, report, files)
        progress(100, "比较报告已生成")
    }

    fun html(report: JSONObject): String {
        fun escape(value: String) = org.jsoup.nodes.TextNode(value).outerHtml()
        val names = report.getJSONArray("names")
        val pages = report.getJSONArray("pages")
        val ignored =
            report
                .optJSONArray("ignored")
                ?.let { j -> (0 until j.length()).map { j.getString(it) }.toSet() }
                .orEmpty()
        return buildString {
            append(
                "<!doctype html><html lang=zh><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>PaperForge 文档比较</title><style>body{font-family:sans-serif;margin:24px;line-height:1.6;color:#1c2822}section{break-before:page}.pair{display:flex;gap:12px}.pair>div{width:50%;position:relative}.pair img{width:100%;border:1px solid #aaa}.box{position:absolute;background:#f5373744;outline:1px solid #e44}pre{white-space:pre-wrap}small{color:#526258}</style><h1>文档比较</h1><p>${escape(names.getString(0))} ↔ ${escape(names.getString(1))}</p>"
            )
            append("<p>按起始页逐页配对；红框为已检测且未忽略的区域。文字与外观记录可能对应同一处修改。</p>")
            append(
                "<p><a href='left.pdf'>左侧原 PDF</a> · <a href='right.pdf'>右侧原 PDF</a> · 已忽略 ${ignored.size} 项</p>"
            )
            report.optJSONArray("notes")?.let { notes ->
                for (n in 0 until notes.length()) append(
                    "<p><small>${escape(notes.getString(n))}</small></p>"
                )
            }
            for (i in 0 until pages.length()) {
                val page = PdfDiffPage.decode(pages.getJSONObject(i))
                val visible = page.differences.filter { "${page.index}:${it.id}" !in ignored }
                append(
                    "<section><h2>左 ${(page.leftPage?.plus(1)) ?: "无"} / 右 ${(page.rightPage?.plus(1)) ?: "无"}</h2><div class=pair>"
                )
                listOf(page.leftImage to true, page.rightImage to false).forEach { (image, left) ->
                    append("<div>")
                    if (image != null) append("<img src='$image'>") else append("<p>此侧没有对应页面</p>")
                    visible.forEach { finding ->
                        val rect = if (left) finding.left else finding.right
                        if (rect != null)
                            append(
                                "<span class=box style='left:${rect.left*100}%;top:${rect.top*100}%;width:${(rect.right-rect.left)*100}%;height:${(rect.bottom-rect.top)*100}%'></span>"
                            )
                    }
                    append("</div>")
                }
                append("</div>")
                page.notes.forEach { append("<p><small>${escape(it)}</small></p>") }
                visible.forEach {
                    append(
                        "<p>${escape(it.kind)}</p><pre>左：${escape(it.before)}\n右：${escape(it.after)}</pre>"
                    )
                }
                if (visible.isEmpty()) append("<p>当前设置下没有未忽略的差异。</p>")
                append("</section>")
            }
            append("<p>参数：${escape(report.getJSONObject("options").toString())}</p></html>")
        }
    }

    fun writeArchive(output: File, directory: File, report: JSONObject, originals: List<File>) {
        val part = File(output.path + ".part")
        try {
            ZipOutputStream(part.outputStream().buffered()).use { zip ->
                fun add(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                add("pdf-comparison.json", report.toString(2).toByteArray(Charsets.UTF_8))
                add("index.html", html(report).toByteArray(Charsets.UTF_8))
                originals.forEachIndexed { index, source ->
                    zip.putNextEntry(ZipEntry(if (index == 0) "left.pdf" else "right.pdf"))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                val pages = report.getJSONArray("pages")
                for (i in 0 until pages.length()) {
                    val page = PdfDiffPage.decode(pages.getJSONObject(i))
                    listOfNotNull(page.leftImage, page.rightImage).forEach { name ->
                        zip.putNextEntry(ZipEntry(name))
                        File(directory, name).inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            check(part.renameTo(output)) { "无法保存比较报告" }
        } finally {
            part.delete()
        }
    }
}
