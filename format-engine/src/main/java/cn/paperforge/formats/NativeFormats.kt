package cn.paperforge.formats

import android.util.AtomicFile
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Matrix
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Native layout reader, isolated in the optional signature-bound formats process. */
object NativeFormats {
    const val VERSION = "mupdf-1.28.5-layout6"
    val types = setOf("xps", "oxps", "svg", "cbz", "fb2", "eml", "caj", "kdh", "tex")

    suspend fun render(
        source: File,
        folder: File,
        hash: String,
        type: String,
        dpi: Int,
        options: JSONObject,
        progress: (Int, String) -> Unit,
    ): JSONObject {
        val manifest = File(folder, "document.json")
        if (manifest.isFile) {
            val cached = JSONObject(manifest.readText())
            val pages = cached.getJSONArray("pages")
            if (
                File(folder, "native.pdf").isFile &&
                    (0 until pages.length()).all {
                        File(folder, pages.getJSONObject(it).getString("image")).isFile
                    }
            )
                return cached
        }
        val mail =
            if (type == "eml")
                MailFormats.prepare(source, folder, options.optBoolean("includeHeaders", true))
            else null
        val caj = if (type in setOf("caj", "kdh")) CajFormats.prepare(source, folder) else null
        val latex = if (type == "tex") LatexFormats.prepare(source, folder, options) else null
        val document =
            Document.openDocument(
                mail?.file?.path ?: caj?.file?.path ?: latex?.file?.path ?: source.path
            )
        try {
            require(!document.needsPassword()) { "此文件需要密码，请先解密后转换" }
            val reflowable = document.isReflowable
            if (reflowable)
                document.layout(
                    options.optDouble("widthPt", 595.0).toFloat().coerceIn(144f, 1440f),
                    options.optDouble("heightPt", 842.0).toFloat().coerceIn(144f, 1440f),
                    options.optDouble("fontSize", 12.0).toFloat().coerceIn(6f, 48f),
                )
            val count = document.countPages()
            require(count > 0) { "文档没有可转换页面" }
            caj?.expectedPages?.let {
                require(it == count) { "CAJ 容器声明 $it 页，但实际读取 $count 页；原件保留，请核对后使用兼容工具" }
            }
            val pages = JSONArray()
            val temporary = File(folder, "native.pdf.part")
            val writer = DocumentWriter(temporary.path, "pdf", "compress,compress-fonts")
            try {
                for (index in 0 until count) {
                    currentCoroutineContext().ensureActive()
                    progress(8 + 78 * index / count, "转换版面 · 第 ${index + 1} / $count 页")
                    val page = document.loadPage(index)
                    try {
                        val bounds = page.bounds
                        val width = bounds.x1 - bounds.x0
                        val height = bounds.y1 - bounds.y0
                        require(width > 0 && height > 0 && width.isFinite() && height.isFinite()) {
                            "第 ${index + 1} 页尺寸不可用"
                        }
                        val device = writer.beginPage(bounds)
                        try {
                            page.run(device, Matrix())
                        } finally {
                            device.destroy()
                        }
                        writer.endPage()
                        val image = File(folder, "page-${index + 1}.png")
                        val metadata = File(folder, "page-${index + 1}.json")
                        val info =
                            if (image.isFile && metadata.isFile) JSONObject(metadata.readText())
                            else {
                                val pixmap =
                                    page.toPixmap(Matrix(dpi / 72f), ColorSpace.DeviceRGB, false)
                                try {
                                    val part = File(image.path + ".part")
                                    pixmap.setResolution(dpi, dpi)
                                    pixmap.saveAsPNG(part.path)
                                    check(part.renameTo(image)) { "无法保存第 ${index + 1} 页" }
                                    val zones = JSONArray()
                                    val text = page.toStructuredText()
                                    try {
                                        for (block in text.blocks) for (line in block.lines) {
                                            val value = buildString {
                                                line.chars.forEach { appendCodePoint(it.c) }
                                            }
                                            if (value.isNotBlank())
                                                zones.put(
                                                    JSONObject()
                                                        .put("text", value)
                                                        .put(
                                                            "left",
                                                            (line.bbox.x0 - bounds.x0) / width,
                                                        )
                                                        .put(
                                                            "top",
                                                            (line.bbox.y0 - bounds.y0) / height,
                                                        )
                                                        .put(
                                                            "right",
                                                            (line.bbox.x1 - bounds.x0) / width,
                                                        )
                                                        .put(
                                                            "bottom",
                                                            (line.bbox.y1 - bounds.y0) / height,
                                                        )
                                                )
                                        }
                                    } finally {
                                        text.destroy()
                                    }
                                    JSONObject()
                                        .put("image", image.name)
                                        .put("width", pixmap.width)
                                        .put("height", pixmap.height)
                                        .put("widthPt", width)
                                        .put("heightPt", height)
                                        .put("zones", zones)
                                        .put(
                                            "warnings",
                                            mail?.warnings
                                                ?: caj?.warnings
                                                ?: latex?.warnings
                                                ?: JSONArray(),
                                        )
                                        .also { atomic(metadata, it) }
                                } finally {
                                    pixmap.destroy()
                                }
                            }
                        pages.put(info)
                    } finally {
                        page.destroy()
                    }
                }
                writer.close()
            } finally {
                writer.destroy()
            }
            val beforeSubsetting = temporary.length()
            val compact = File(folder, "subset.pdf.part")
            val written = Document.openDocument(temporary.path)
            try {
                val pdf = written.asPDF()
                try {
                    NativeFontPrograms.unwrapCff(pdf)
                    pdf.subsetFonts()
                    NativeUnicode.normalize(pdf)
                    pdf.save(compact.path, "garbage=compact,compress,compress-fonts")
                } finally {
                    if (pdf !== written) pdf.destroy()
                }
            } finally {
                written.destroy()
            }
            check(compact.renameTo(File(folder, "native.pdf"))) { "无法保存 PDF 版面" }
            temporary.delete()
            return JSONObject()
                .put("adapter", VERSION)
                .put("type", type)
                .put("sourceSha256", hash)
                .put("dpi", dpi)
                .put("pages", pages)
                .put("nativePdf", "native.pdf")
                .put("pdfBeforeFontSubsetting", beforeSubsetting)
                .put("pdfBytes", File(folder, "native.pdf").length())
                .put(
                    "pdfOptimization",
                    "Embedded font subsets; original text and vector page content retained",
                )
                .put("reflowable", reflowable)
                .put("attachments", mail?.attachments ?: JSONArray())
                .put("formulas", latex?.formulas ?: JSONArray())
                .put(
                    "contentMode",
                    "Mapped text, images and basic vectors through MuPDF PDF device; unsupported effects follow the renderer",
                )
                .also { atomic(manifest, it) }
        } finally {
            document.destroy()
        }
    }

    private fun atomic(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
