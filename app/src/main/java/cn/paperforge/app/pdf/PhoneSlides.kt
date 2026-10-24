package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Full-page picture slides: preserves appearance and explicitly does not claim editable source
 * shapes.
 */
object PhoneSlides {
    suspend fun convert(
        context: Context,
        input: File,
        output: File,
        progress: suspend (Int) -> Unit = {},
    ) {
        val temporary = File(output.path + ".part")
        try {
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val count = renderer.pageCount
                    val size =
                        renderer.openPage(0).use {
                            it.width.toLong() * 12700 to it.height.toLong() * 12700
                        }
                    ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                        fun text(name: String, value: String) {
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(value.toByteArray(Charsets.UTF_8))
                            zip.closeEntry()
                        }
                        ZipInputStream(context.assets.open("templates/blank.pptx")).use { template
                            ->
                            while (true) {
                                val entry = template.nextEntry ?: break
                                if (entry.isDirectory) continue
                                val bytes = template.readBytes()
                                when (entry.name) {
                                    "ppt/presentation.xml" -> {
                                        var xml = bytes.toString(Charsets.UTF_8)
                                        val ids =
                                            "<p:sldIdLst>" +
                                                (0 until count).joinToString("") {
                                                    "<p:sldId id=\"${256+it}\" r:id=\"pfSlide${it+1}\"/>"
                                                } +
                                                "</p:sldIdLst>"
                                        xml =
                                            xml.replace(
                                                Regex("<p:sldSz[^>]*/>"),
                                                "<p:sldSz cx=\"${size.first}\" cy=\"${size.second}\"/>",
                                            )
                                        xml = xml.replace("<p:sldSz", ids + "<p:sldSz")
                                        text(entry.name, xml)
                                    }
                                    "ppt/_rels/presentation.xml.rels" -> {
                                        val rels =
                                            (1..count).joinToString("") {
                                                "<Relationship Id=\"pfSlide$it\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide$it.xml\"/>"
                                            }
                                        text(
                                            entry.name,
                                            bytes
                                                .toString(Charsets.UTF_8)
                                                .replace(
                                                    "</Relationships>",
                                                    rels + "</Relationships>",
                                                ),
                                        )
                                    }
                                    "[Content_Types].xml" -> {
                                        val defaults =
                                            "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                                        val slides =
                                            (1..count).joinToString("") {
                                                "<Override PartName=\"/ppt/slides/slide$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
                                            }
                                        text(
                                            entry.name,
                                            bytes
                                                .toString(Charsets.UTF_8)
                                                .replace("</Types>", defaults + slides + "</Types>"),
                                        )
                                    }
                                    else -> {
                                        zip.putNextEntry(ZipEntry(entry.name))
                                        zip.write(bytes)
                                        zip.closeEntry()
                                    }
                                }
                            }
                        }
                        repeat(count) { index ->
                            currentCoroutineContext().ensureActive()
                            val number = index + 1
                            renderer.openPage(index).use { page ->
                                val scale = 2200f / maxOf(page.width, page.height)
                                val bitmap =
                                    Bitmap.createBitmap(
                                        (page.width * scale).toInt(),
                                        (page.height * scale).toInt(),
                                        Bitmap.Config.ARGB_8888,
                                    )
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(
                                        bitmap,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                                    )
                                    zip.putNextEntry(ZipEntry("ppt/media/page$number.png"))
                                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                                    zip.closeEntry()
                                } finally {
                                    bitmap.recycle()
                                }
                                val ratio =
                                    minOf(
                                        size.first.toDouble() / page.width,
                                        size.second.toDouble() / page.height,
                                    )
                                val width = (page.width * ratio).toLong()
                                val height = (page.height * ratio).toLong()
                                val x = (size.first - width) / 2
                                val y = (size.second - height) / 2
                                text(
                                    "ppt/slides/slide$number.xml",
                                    """<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr><p:pic><p:nvPicPr><p:cNvPr id="2" name="Original page $number"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="image"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$width" cy="$height"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>""",
                                )
                                text(
                                    "ppt/slides/_rels/slide$number.xml.rels",
                                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="layout" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout7.xml"/><Relationship Id="image" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/page$number.png"/></Relationships>""",
                                )
                            }
                            progress(number * 100 / count)
                        }
                    }
                }
            }
            check(temporary.renameTo(output))
        } finally {
            temporary.delete()
        }
    }
}
