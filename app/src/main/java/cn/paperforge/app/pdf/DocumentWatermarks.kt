package cn.paperforge.app.pdf

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.data.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.util.zip.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlinx.coroutines.*
import org.json.JSONObject
import org.w3c.dom.Element

/** Original package-level Office cleanup. Only selected drawing/background nodes change. */
object DocumentWatermarks {
    val packages = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "docm", "xlsm", "pptm")
    val legacy = setOf("doc", "xls", "ppt", "rtf")

    data class Report(val removed: Int, val format: String)

    fun office(input: File, output: File, text: String = "", background: Boolean = true): Report {
        var removed = 0
        ZipFile(input).use { source ->
            ZipOutputStream(output.outputStream().buffered()).use { target ->
                source.entries().asSequence().forEach { entry ->
                    var bytes = source.getInputStream(entry).use { it.readBytes() }
                    if (
                        entry.name.endsWith(".xml") &&
                            (entry.name.startsWith("word/") ||
                                entry.name.startsWith("xl/") ||
                                entry.name.startsWith("ppt/") ||
                                entry.name in setOf("content.xml", "styles.xml"))
                    ) {
                        // Android's bundled DOM does not expose every Xerces feature.
                        // OOXML/ODF parts have no DTD; reject declarations before parsing.
                        require(
                            !Regex("<!DOCTYPE", RegexOption.IGNORE_CASE)
                                .containsMatchIn(bytes.toString(Charsets.UTF_8))
                        ) {
                            "文档包含不支持的 XML 文档类型声明"
                        }
                        val factory =
                            DocumentBuilderFactory.newInstance().apply {
                                isNamespaceAware = true
                                for ((feature, enabled) in
                                    listOf(
                                        "http://apache.org/xml/features/disallow-doctype-decl" to
                                            true,
                                        "http://xml.org/sax/features/external-general-entities" to
                                            false,
                                        "http://xml.org/sax/features/external-parameter-entities" to
                                            false,
                                    )) {
                                    try {
                                        setFeature(feature, enabled)
                                    } catch (_: javax.xml.parsers.ParserConfigurationException) {
                                        /* Android DOM */
                                    }
                                }
                            }
                        val builder =
                            factory.newDocumentBuilder().apply {
                                setEntityResolver { _, _ ->
                                    throw org.xml.sax.SAXException(
                                        "External entities are unsupported"
                                    )
                                }
                            }
                        val doc = builder.parse(bytes.inputStream())
                        val candidates =
                            doc.getElementsByTagName("*").let { nodes ->
                                (0 until nodes.length)
                                    .map { nodes.item(it) }
                                    .filterIsInstance<Element>()
                            }
                        val drop = mutableListOf<Element>()
                        fun normalized(v: String) =
                            v.trim().replace(Regex("\\s+"), "").lowercase(java.util.Locale.ROOT)
                        for (node in candidates) {
                            val local = node.localName ?: node.nodeName.substringAfter(':')
                            val drawing =
                                local in
                                    setOf("shape", "sp", "graphicFrame", "pic", "drawing", "frame")
                            if (
                                !drawing &&
                                    !(background &&
                                        local in setOf("background", "picture", "background-image"))
                            )
                                continue
                            val attrs = buildString {
                                for (i in 0 until node.attributes.length) append(
                                        node.attributes.item(i).nodeValue
                                    )
                                    .append(' ')
                                val nested = node.getElementsByTagName("*")
                                for (i in 0 until nested.length) {
                                    val e = nested.item(i) as? Element ?: continue
                                    if (e.localName in setOf("cNvPr", "docPr", "textpath"))
                                        for (j in 0 until e.attributes.length) append(
                                                e.attributes.item(j).nodeValue
                                            )
                                            .append(' ')
                                }
                            }
                            val named =
                                Regex("(?i)(watermark|powerpluswatermark|水印)")
                                    .containsMatchIn(attrs)
                            val selected =
                                text.isNotBlank() &&
                                    (normalized(node.textContent) == normalized(text) ||
                                        candidates.any {
                                            it.parentNode == node &&
                                                it.localName == "textpath" &&
                                                normalized(it.getAttribute("string")) ==
                                                    normalized(text)
                                        } ||
                                        node.getElementsByTagNameNS("*", "textpath").let { list ->
                                            (0 until list.length).any {
                                                normalized(
                                                    (list.item(it) as Element).getAttribute(
                                                        "string"
                                                    )
                                                ) == normalized(text)
                                            }
                                        })
                            val sheetBackground =
                                background &&
                                    (local == "picture" &&
                                        node.namespaceURI?.contains("spreadsheetml") == true ||
                                        local == "background-image" ||
                                        local == "background" &&
                                            node.namespaceURI?.contains("wordprocessingml") == true)
                            if (named || selected || sheetBackground) drop += node
                        }
                        val roots =
                            drop.filter { node ->
                                var parent = node.parentNode
                                var nested = false
                                while (parent != null) {
                                    if (parent in drop) nested = true
                                    parent = parent.parentNode
                                }
                                !nested
                            }
                        roots.forEach {
                            it.parentNode?.removeChild(it)
                            removed++
                        }
                        if (roots.isNotEmpty()) {
                            val stream = java.io.ByteArrayOutputStream()
                            TransformerFactory.newInstance()
                                .newTransformer()
                                .transform(DOMSource(doc), StreamResult(stream))
                            bytes = stream.toByteArray()
                        }
                    }
                    val item = ZipEntry(entry.name)
                    if (entry.name == "mimetype") {
                        item.method = ZipEntry.STORED
                        item.size = bytes.size.toLong()
                        item.crc = CRC32().apply { update(bytes) }.value
                    }
                    target.putNextEntry(item)
                    target.write(bytes)
                    target.closeEntry()
                }
            }
        }
        return Report(removed, input.extension.lowercase())
    }

    suspend fun notice(repo: Repository, job: JobHandle): String {
        val report =
            JSONObject(repo.dao.task(job.row.id)!!.payload)
                .getJSONObject("checkpoint")
                .optJSONObject("watermark-report") ?: return ""
        val count = report.optInt("removed")
        return if (count == 0) "未找到匹配水印，已保留原内容。可尝试指定文字或框选修复。" else "已清理 $count 项水印；原件保留。"
    }

    suspend fun run(
        repo: Repository,
        job: JobHandle,
        progress: suspend (Int, String) -> Unit,
    ): File {
        val source = File(job.input.getStringArray("inputs")!!.single())
        val options = JSONObject(job.input.getString("options") ?: "{}")
        val mode = options.optString("mode", "AUTO")
        var input = source
        val extension = source.extension.lowercase()
        val title = OutputNames.sourceStem(job.input.getString("name") ?: "文档") + "-已去水印"
        if (extension in legacy)
            input =
                OfficeJobs.convert(
                    repo,
                    job,
                    File(repo.exports, "${job.row.id}/office-source.pdf"),
                ) { p, d ->
                    progress(p / 3, d)
                }
        val output =
            OutputNames.create(
                repo,
                job.row.id,
                title,
                if (input.extension.lowercase() in packages) input.extension
                else if (input.extension.equals("pdf", true)) "pdf" else "png",
            )
        val part = File(output.path + ".part")
        var count = 0
        try {
            progress(35, "正在清理所选水印")
            if (input.extension.lowercase() in packages) {
                require(mode != "REGION") { "Office 框选修复请先转成 PDF；原格式支持自动对象和指定文字" }
                count =
                    office(
                            input,
                            part,
                            if (mode == "TEXT") options.optString("text") else "",
                            options.optBoolean("background", true),
                        )
                        .removed
            } else if (input.extension.equals("pdf", true) && mode != "REGION") {
                count =
                    if (mode == "TEXT")
                        PdfWatermarks.removeMatching(input, part, options.getString("text"))
                    else PdfWatermarks.remove(input, part).let { it.imagesRemoved + it.textCovers }
            } else {
                require(mode == "REGION") { "图片水印请先框选修复区域" }
                if (input.extension.equals("pdf", true)) {
                    PDDocument().use { pdf ->
                        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
                            fd ->
                            PdfRenderer(fd).use { renderer ->
                                for (index in 0 until renderer.pageCount) {
                                    currentCoroutineContext().ensureActive()
                                    renderer.openPage(index).use { page ->
                                        val scale = 1800f / maxOf(page.width, page.height)
                                        val raw =
                                            Bitmap.createBitmap(
                                                    (page.width * scale).toInt(),
                                                    (page.height * scale).toInt(),
                                                    Bitmap.Config.ARGB_8888,
                                                )
                                                .apply { eraseColor(Color.WHITE) }
                                        page.render(
                                            raw,
                                            null,
                                            null,
                                            PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                                        )
                                        val cleaned =
                                            if (
                                                options.optBoolean("allPages") ||
                                                    index == options.optInt("page", 0)
                                            )
                                                repair(raw, options)
                                            else raw.copy(Bitmap.Config.ARGB_8888, false)
                                        try {
                                            val target =
                                                PDPage(
                                                    PDRectangle(
                                                        page.width.toFloat(),
                                                        page.height.toFloat(),
                                                    )
                                                )
                                            pdf.addPage(target)
                                            PDPageContentStream(pdf, target).use {
                                                it.drawImage(
                                                    LosslessFactory.createFromImage(pdf, cleaned),
                                                    0f,
                                                    0f,
                                                    page.width.toFloat(),
                                                    page.height.toFloat(),
                                                )
                                            }
                                        } finally {
                                            raw.recycle()
                                            cleaned.recycle()
                                        }
                                    }
                                    progress(
                                        35 + (index + 1) * 60 / renderer.pageCount,
                                        "修复 ${index+1}/${renderer.pageCount} 页",
                                    )
                                }
                            }
                        }
                        pdf.save(part)
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(input.path, bounds)
                    bounds.inSampleSize = 1
                    while (
                        maxOf(bounds.outWidth, bounds.outHeight) / bounds.inSampleSize > 4096
                    ) bounds.inSampleSize *= 2
                    bounds.inJustDecodeBounds = false
                    val raw = BitmapFactory.decodeFile(input.path, bounds) ?: error("图片无法读取")
                    val oriented =
                        cn.paperforge.app.imaging.PageDecoder.orient(
                            raw,
                            android.media
                                .ExifInterface(input.path)
                                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1),
                        )
                    val cleaned =
                        try {
                            repair(oriented, options)
                        } finally {
                            oriented.recycle()
                        }
                    try {
                        part.outputStream().use {
                            check(cleaned.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally {
                        cleaned.recycle()
                    }
                }
                count = 1
            }
            currentCoroutineContext().ensureActive()
            DocumentJobs.checkCurrent(repo, job)
            check(part.renameTo(output)) { "无法保存处理副本" }
            DocumentJobs.checkpoint(
                repo,
                job,
                "watermark-report",
                JSONObject().put("removed", count).put("mode", mode).put("format", output.extension),
            )
            progress(100, if (count == 0) "未找到匹配水印，已保留原内容；可改用按文字或框选修复" else "已清理 $count 项水印，原件保留")
            return output
        } finally {
            part.delete()
        }
    }

    fun repair(source: Bitmap, options: JSONObject): Bitmap {
        val rect = options.getJSONObject("region")
        val rgba = org.opencv.core.Mat()
        val rgb = org.opencv.core.Mat()
        val mask =
            org.opencv.core.Mat.zeros(source.height, source.width, org.opencv.core.CvType.CV_8UC1)
        val result = org.opencv.core.Mat()
        try {
            org.opencv.android.Utils.bitmapToMat(source, rgba)
            org.opencv.imgproc.Imgproc.cvtColor(
                rgba,
                rgb,
                org.opencv.imgproc.Imgproc.COLOR_RGBA2RGB,
            )
            val left = (rect.getDouble("left").coerceIn(0.0, 1.0) * (source.width - 1)).toInt()
            val top = (rect.getDouble("top").coerceIn(0.0, 1.0) * (source.height - 1)).toInt()
            val right = (rect.getDouble("right").coerceIn(0.0, 1.0) * (source.width - 1)).toInt()
            val bottom = (rect.getDouble("bottom").coerceIn(0.0, 1.0) * (source.height - 1)).toInt()
            require(right > left && bottom > top) { "请框选需要修复的区域" }
            org.opencv.imgproc.Imgproc.rectangle(
                mask,
                org.opencv.core.Point(left.toDouble(), top.toDouble()),
                org.opencv.core.Point(right.toDouble(), bottom.toDouble()),
                org.opencv.core.Scalar(255.0),
                -1,
            )
            org.opencv.photo.Photo.inpaint(
                rgb,
                mask,
                result,
                3.0,
                org.opencv.photo.Photo.INPAINT_TELEA,
            )
            return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
                org.opencv.android.Utils.matToBitmap(result, it)
            }
        } finally {
            listOf(rgba, rgb, mask, result).forEach { it.release() }
        }
    }
}
