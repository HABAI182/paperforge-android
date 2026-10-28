package cn.paperforge.app.pdf

import cn.paperforge.app.model.CjkSearch
import cn.paperforge.app.model.PointN
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Strict native text removal. Unsupported structures are rejected before any output is saved. */
object PdfRedaction {
    data class Report(val mode: String, val detail: String)

    private data class State(
        var font: PDFont? = null,
        var size: Float = 0f,
        var charSpace: Float = 0f,
        var wordSpace: Float = 0f,
        var render: Int = 0,
    )

    private data class Glyph(val start: Int, val end: Int, val text: String, val advance: Float)

    private data class Patch(
        val page: Int,
        val tokens: MutableList<Any>,
        val index: Int,
        val arrayIndex: Int?,
        val replacement: COSArray,
    )

    private fun glyphs(s: COSString, state: State): List<Glyph> {
        val font = requireNotNull(state.font) { "文字缺少字体" }
        require(
            !font.isVertical && font.subType != "Type3" && state.size != 0f && state.render < 4
        ) {
            "不支持竖排、Type3、零字号或文字裁剪"
        }
        val bytes = s.bytes
        val stream = ByteArrayInputStream(bytes)
        val result = mutableListOf<Glyph>()
        while (stream.available() > 0) {
            val start = bytes.size - stream.available()
            val code = font.readCode(stream)
            val end = bytes.size - stream.available()
            require(end > start) { "字体编码无法解析" }
            val unicode = font.toUnicode(code)
            require(!unicode.isNullOrEmpty()) { "字体缺少可核验的 Unicode 映射" }
            val word = if (end - start == 1 && code == 32) state.wordSpace else 0f
            val advance = font.getWidth(code) + (state.charSpace + word) * 1000f / state.size
            require(advance.isFinite()) { "文字推进无法计算" }
            result.add(Glyph(start, end, unicode, advance))
        }
        return result
    }

    private fun replacement(s: COSString, state: State, target: String): List<COSArray> {
        val glyphs = glyphs(s, state)
        val pieces =
            glyphs.map {
                Normalizer.normalize(it.text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            }
        val text = pieces.joinToString("")
        val normalizedTarget = CjkSearch.normalize(target)
        val result = mutableListOf<COSArray>()
        var pos = 0
        while (true) {
            val at = text.indexOf(normalizedTarget, pos)
            if (at < 0) break
            var cursor = 0
            var first = -1
            var end = -1
            pieces.forEachIndexed { i, piece ->
                if (cursor == at) first = i
                cursor += piece.length
                if (cursor == at + normalizedTarget.length) end = i + 1
            }
            require(first >= 0 && end > first) { "选中文字跨越字形编码边界" }
            val b = s.bytes
            val a = glyphs[first].start
            val z = glyphs[end - 1].end
            result.add(
                COSArray().apply {
                    if (a > 0) add(COSString(b.copyOfRange(0, a)))
                    add(
                        COSFloat(
                            -glyphs.subList(first, end).sumOf { it.advance.toDouble() }.toFloat()
                        )
                    )
                    if (z < b.size) add(COSString(b.copyOfRange(z, b.size)))
                }
            )
            pos = at + normalizedTarget.length
        }
        return result
    }

    private fun inspect(pdf: PDDocument, target: String, selectedPage: Int): Patch {
        require(CjkSearch.normalize(target).isNotBlank()) { "请输入要删除的文字" }
        require(selectedPage in 0 until pdf.numberOfPages) { "页码无效" }
        val patches = mutableListOf<Patch>()
        listOf(pdf.getPage(selectedPage)).forEach { page ->
            val pageIndex = selectedPage
            val resources = page.resources
            if (resources != null) {
                val allowed = setOf("Font", "ProcSet")
                require(resources.cosObject.keySet().all { it.name in allowed }) {
                    "页面包含图像、表单或复杂资源，不能可靠地语义脱敏"
                }
            }
            val parser = PDFStreamParser(page)
            parser.parse()
            val tokens = parser.tokens.toMutableList()
            var state = State()
            val stack = mutableListOf<State>()
            var start = 0
            tokens.forEachIndexed { index, token ->
                if (token !is Operator) return@forEachIndexed
                val args = tokens.subList(start, index)
                fun number(i: Int) =
                    (args.getOrNull(i) as? COSNumber)?.floatValue() ?: error("PDF 文字状态无效")
                when (token.name) {
                    "q" -> stack.add(state.copy())
                    "Q" -> {
                        require(stack.isNotEmpty()) { "图形状态不平衡" }
                        state = stack.removeAt(stack.lastIndex)
                    }
                    "Tf" -> {
                        state.font =
                            resources?.getFont(args.getOrNull(0) as? COSName ?: error("字体名称无效"))
                        state.size = number(1)
                    }
                    "Tc" -> state.charSpace = number(0)
                    "Tw" -> state.wordSpace = number(0)
                    "Tr" -> state.render = number(0).toInt()
                    "Tj",
                    "TJ" -> {
                        val value = args.singleOrNull() ?: error("文字操作数无效")
                        val strings =
                            when (value) {
                                is COSString -> listOf(null to value)
                                is COSArray -> {
                                    require(
                                        (0 until value.size()).all {
                                            value.getObject(it) is COSString ||
                                                value.getObject(it) is COSNumber
                                        }
                                    ) {
                                        "文字数组包含未知对象"
                                    }
                                    (0 until value.size()).mapNotNull { i ->
                                        (value.getObject(i) as? COSString)?.let { i to it }
                                    }
                                }
                                else -> error("文字操作数无效")
                            }
                        for ((arrayIndex, s) in strings) {
                            val found = replacement(s, state, target)
                            found.forEach {
                                patches.add(Patch(pageIndex, tokens, start, arrayIndex, it))
                            }
                        }
                    }
                    "'",
                    "\"",
                    "BI",
                    "Do",
                    "BDC",
                    "BMC",
                    "DP",
                    "MP",
                    "gs",
                    "sh" -> error("页面包含暂不支持的文字或图形结构")
                    else ->
                        require(
                            args.none { it is COSString || it is COSDictionary || it is COSArray }
                        ) {
                            "页面包含无法核验的内容"
                        }
                }
                start = index + 1
            }
            require(stack.isEmpty() && start == tokens.size) { "页面内容流不完整" }
        }
        require(patches.size == 1 && patches.single().page == selectedPage) {
            "目标必须在所选页的单个文字片段中唯一出现；跨片段或重复内容请使用栅格副本"
        }
        return patches.single()
    }

    /** Never writes the source and never performs an incremental save. */
    fun semantic(input: File, output: File, page: Int, target: String): Report {
        require(input.canonicalFile != output.canonicalFile && !output.exists()) { "请选择新的输出文件" }
        val part = File(output.path + ".part")
        require(part.canonicalFile != input.canonicalFile && !part.exists()) {
            "临时输出路径已存在，请重新选择文件名"
        }
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                require(!pdf.isEncrypted) { "加密文件请先另存为解密副本" }
                val patch = inspect(pdf, target, page)
                val current = patch.tokens[patch.index]
                if (patch.arrayIndex == null) {
                    patch.tokens[patch.index] = patch.replacement
                    patch.tokens[patch.index + 1] = Operator.getOperator("TJ")
                } else {
                    val original = current as COSArray
                    val changed = COSArray()
                    for (i in 0 until original.size()) {
                        if (i == patch.arrayIndex) for (v in patch.replacement) changed.add(v)
                        else changed.add(original.get(i))
                    }
                    patch.tokens[patch.index] = changed
                }
                val stream = PDStream(pdf)
                stream.createOutputStream(COSName.FLATE_DECODE).use {
                    ContentStreamWriter(it).writeTokens(patch.tokens)
                }
                pdf.getPage(page).setContents(stream)
                // Keep page content/resources but drop auxiliary document content which can carry
                // secrets.
                pdf.documentInformation = PDDocumentInformation()
                val catalog = pdf.documentCatalog.cosObject
                catalog
                    .keySet()
                    .toList()
                    .filterNot { it.name in setOf("Type", "Pages") }
                    .forEach(catalog::removeItem)
                val pageKeys =
                    setOf(
                        "Type",
                        "Parent",
                        "Contents",
                        "Resources",
                        "MediaBox",
                        "CropBox",
                        "Rotate",
                        "BleedBox",
                        "TrimBox",
                        "ArtBox",
                        "UserUnit",
                        "Group",
                    )
                val treeKeys = pageKeys + setOf("Kids", "Count")
                val seen =
                    java.util.Collections.newSetFromMap(
                        java.util.IdentityHashMap<COSDictionary, Boolean>()
                    )
                pdf.pages.forEach { p ->
                    val dict = p.cosObject
                    dict
                        .keySet()
                        .toList()
                        .filterNot { it.name in pageKeys }
                        .forEach(dict::removeItem)
                    var parent = dict.getDictionaryObject(COSName.PARENT) as? COSDictionary
                    while (parent != null && seen.add(parent)) {
                        val node = parent
                        node
                            .keySet()
                            .toList()
                            .filterNot { it.name in treeKeys }
                            .forEach(node::removeItem)
                        parent = node.getDictionaryObject(COSName.PARENT) as? COSDictionary
                    }
                }
                pdf.save(part)
            }
            PDDocument.load(part).use { saved ->
                val stripper =
                    PDFTextStripper().apply {
                        startPage = page + 1
                        endPage = page + 1
                    }
                val result =
                    Normalizer.normalize(stripper.getText(saved), Normalizer.Form.NFKC)
                        .lowercase(Locale.ROOT)
                require(!result.contains(CjkSearch.normalize(target))) { "所选页仍能提取目标文字，未生成副本" }
            }
            check(part.renameTo(output)) { "无法保存副本" }
            return Report("语义文字删除", "仅处理所选页，保留页面原生文字、图片和矢量内容；其他页可能仍含相同文字。移除文档信息、批注、表单、附件及其他目录附加内容。")
        } finally {
            part.delete()
        }
    }

    suspend fun raster(
        context: android.content.Context,
        input: File,
        output: File,
        page: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        stages: JobFileStages? = null,
        progress: suspend (Int) -> Unit = {},
    ): Report {
        require(input.canonicalFile != output.canonicalFile && !output.exists()) { "请选择新的输出文件" }
        val part = File(output.path + ".part")
        require(part.canonicalFile != input.canonicalFile && !part.exists()) {
            "临时输出路径已存在，请重新选择文件名"
        }
        require(
            page >= 0 &&
                left in 0f..1f &&
                top in 0f..1f &&
                right in 0f..1f &&
                bottom in 0f..1f &&
                left < right &&
                top < bottom
        ) {
            "请选择有效区域"
        }
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use {
            require(page < it.numberOfPages) { "页码无效" }
        }
        PdfMarks.save(
            context,
            input,
            output,
            listOf(PdfMark(page, "redact", listOf(PointN(left, top), PointN(right, bottom)))),
            stages = stages,
            progress = progress,
        )
        return Report("整份栅格脱敏", "所有页面重绘为图像；原生文字、矢量、表单、批注、附件与元数据不保留。请目视检查遮盖范围。")
    }
}
