package cn.paperforge.app.pdf

import android.content.Context
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object SlidesPackage {
    fun xml(value: String) =
        value
            .filter { it >= ' ' || it in "\n\t\r" }
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    fun text(zip: ZipOutputStream, path: String, value: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(value.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    fun header(context: Context, zip: ZipOutputStream, count: Int, width: Long, height: Long) {
        ZipInputStream(context.assets.open("templates/blank.pptx")).use { source ->
            while (true) {
                val entry = source.nextEntry ?: break
                if (entry.isDirectory) continue
                val bytes = source.readBytes()
                val raw = bytes.toString(Charsets.UTF_8)
                val value =
                    when (entry.name) {
                        "ppt/presentation.xml" -> {
                            val ids =
                                "<p:sldIdLst>" +
                                    (1..count).joinToString("") {
                                        "<p:sldId id=\"${255+it}\" r:id=\"pfSlide$it\"/>"
                                    } +
                                    "</p:sldIdLst>"
                            raw.replace(
                                    Regex("<p:sldSz[^>]*/>"),
                                    "<p:sldSz cx=\"$width\" cy=\"$height\"/>",
                                )
                                .replace("<p:sldSz", ids + "<p:sldSz")
                        }
                        "ppt/_rels/presentation.xml.rels" ->
                            raw.replace(
                                "</Relationships>",
                                (1..count).joinToString("") {
                                    "<Relationship Id=\"pfSlide$it\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide$it.xml\"/>"
                                } + "</Relationships>",
                            )
                        "[Content_Types].xml" ->
                            raw.replace(
                                "</Types>",
                                "<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"json\" ContentType=\"application/json\"/>" +
                                    (1..count).joinToString("") {
                                        "<Override PartName=\"/ppt/slides/slide$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
                                    } +
                                    "</Types>",
                            )
                        "docProps/app.xml" ->
                            raw.replace(Regex("<Slides>[^<]*</Slides>"), "<Slides>$count</Slides>")
                        else -> null
                    }
                if (value != null) text(zip, entry.name, value)
                else {
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    fun slide(body: String) =
        """<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>$body</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""

    fun relationships(images: List<String>) =
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="layout" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout7.xml"/>""" +
            images
                .mapIndexed { i, path ->
                    "<Relationship Id=\"image${i+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/${xml(path)}\"/>"
                }
                .joinToString("") +
            "</Relationships>"
}
