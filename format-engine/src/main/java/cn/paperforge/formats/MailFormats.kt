package cn.paperforge.formats

import java.io.File
import org.apache.james.mime4j.dom.Entity
import org.apache.james.mime4j.dom.Multipart
import org.apache.james.mime4j.dom.SingleBody
import org.apache.james.mime4j.dom.TextBody
import org.apache.james.mime4j.message.DefaultMessageBuilder
import org.json.JSONArray
import org.jsoup.Jsoup

/** RFC MIME decoding is delegated to Apache Mime4j, including encoded headers and charsets. */
object MailFormats {
    data class Prepared(val file: File, val attachments: JSONArray, val warnings: JSONArray)

    fun prepare(source: File, folder: File, includeHeaders: Boolean): Prepared {
        val message = source.inputStream().use { DefaultMessageBuilder().parseMessage(it) }
        try {
            val attachments = JSONArray()
            val inline = mutableMapOf<String, String>()
            fun escape(text: String) = org.jsoup.nodes.Entities.escape(text)
            fun body(entity: Entity): String {
                val content = entity.body
                val attachment = entity.dispositionType.equals("attachment", true)
                if (content is Multipart) {
                    val parts = content.bodyParts.map { body(it) }
                    return if (content.subType.equals("alternative", true))
                        parts.lastOrNull { it.isNotBlank() }.orEmpty()
                    else parts.filter { it.isNotBlank() }.joinToString("\n")
                }
                if (
                    !attachment &&
                        content is TextBody &&
                        entity.mimeType.lowercase() in setOf("text/html", "text/plain")
                ) {
                    val value = content.reader.use { it.readText() }
                    return if (entity.mimeType.equals("text/html", true)) value
                    else "<pre style='white-space:pre-wrap'>${escape(value)}</pre>"
                }
                if (content is SingleBody) {
                    val name = entity.filename ?: "未命名附件"
                    val cid =
                        entity.header
                            ?.getField("Content-ID")
                            ?.body
                            ?.trim()
                            ?.removeSurrounding("<", ">")
                    if (!attachment && cid != null && entity.mimeType.startsWith("image/")) {
                        val extension =
                            when (entity.mimeType.lowercase()) {
                                "image/png" -> "png"
                                "image/jpeg" -> "jpg"
                                "image/gif" -> "gif"
                                "image/svg+xml" -> "svg"
                                else -> null
                            }
                        if (extension != null) {
                            val file = File(folder, "inline-${inline.size}.$extension")
                            content.inputStream.use { from ->
                                file.outputStream().use { from.copyTo(it) }
                            }
                            inline[cid] = file.name
                        }
                    }
                    if (attachment || cid == null || inline[cid] == null) attachments.put(name)
                }
                return ""
            }
            val html = body(message)
            val document = Jsoup.parse(html)
            document.select("script,iframe,object,embed,form,link,base").remove()
            var omitted = 0
            document.select("img").forEach { image ->
                val src = image.attr("src")
                val local = if (src.startsWith("cid:", true)) inline[src.substring(4)] else null
                if (local != null) image.attr("src", local)
                else if (!src.startsWith("data:image/", true)) {
                    image.replaceWith(
                        org.jsoup.nodes.TextNode("[图片未载入：${image.attr("alt").ifBlank { "外部图片" }}]")
                    )
                    omitted++
                }
            }
            document.select("*").forEach { element ->
                element
                    .attributes()
                    .asList()
                    .filter { it.key.startsWith("on", true) }
                    .forEach { element.removeAttr(it.key) }
            }
            document.head().appendElement("meta").attr("charset", "utf-8")
            document
                .head()
                .appendElement("style")
                .text(
                    "body{font-family:sans-serif;color:#172b40}img{max-width:100%}pre{white-space:pre-wrap}.mail-header{border-bottom:1px solid #ccd5df;padding-bottom:1em;margin-bottom:1em}.mail-header p{margin:0.25em 0}"
                )
            if (includeHeaders) {
                val header = document.body().prependElement("div").addClass("mail-header")
                header.appendElement("h1").text(message.subject ?: "未命名邮件")
                listOf(
                        "发件人" to message.from?.toString(),
                        "收件人" to message.to?.toString(),
                        "抄送" to message.cc?.toString(),
                        "日期" to message.date?.toString(),
                    )
                    .forEach { (label, value) ->
                        if (!value.isNullOrBlank()) header.appendElement("p").text("$label：$value")
                    }
            }
            if (attachments.length() > 0)
                document
                    .body()
                    .appendElement("p")
                    .text(
                        "附件保留在 EML 原件：${(0 until attachments.length()).joinToString("、") { attachments.getString(it) }}"
                    )
            val warnings = JSONArray()
            if (omitted > 0) warnings.put("$omitted 项外部图片未离线载入；内嵌 CID 图片保留")
            if (attachments.length() > 0) warnings.put("附件名称列于正文，附件原始数据保留在 EML 原件，未转成页面")
            val result = File(folder, "mail.html")
            result.writeText(document.outerHtml())
            return Prepared(result, attachments, warnings)
        } finally {
            message.dispose()
        }
    }
}
