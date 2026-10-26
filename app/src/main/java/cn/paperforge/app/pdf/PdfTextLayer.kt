package cn.paperforge.app.pdf

import android.content.Context
import cn.paperforge.app.model.OcrLayout
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.*

object PdfTextLayer {
    const val VERSION = "ocr-unicode-geometry-2"

    class Mapping {
        private val mappings = mutableMapOf<String, MutableMap<Int, String>>()

        internal fun record(font: PDType0Font, text: String) {
            val tag =
                font.cosObject.getString(PdfTextUnicode.fontTag)
                    ?: java.util.UUID.randomUUID().toString().also {
                        font.cosObject.setString(PdfTextUnicode.fontTag, it)
                    }
            val characters = mappings.getOrPut(tag) { mutableMapOf() }
            text.codePoints().toArray().forEach { cp ->
                val value = String(Character.toChars(cp))
                val encoded = font.encode(value)
                if (encoded.size == 2)
                    characters.putIfAbsent(
                        ((encoded[0].toInt() and 255) shl 8) or (encoded[1].toInt() and 255),
                        value,
                    )
            }
        }

        fun finish(file: java.io.File) = PdfTextUnicode.rewriteTagged(file, mappings)
    }

    fun font(context: Context, doc: PDDocument) =
        context.assets.open("fonts/PaperForgeCJK-Regular.ttf").use {
            PDType0Font.load(doc, it, true)
        }

    fun safe(font: PDType0Font, text: String): String = buildString {
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
            .codePoints()
            .toArray()
            .forEach { code ->
                val s = String(Character.toChars(code))
                if (!Character.isISOControl(code))
                    append(
                        runCatching {
                                font.encode(s)
                                s
                            }
                            .getOrDefault("?")
                    )
            }
    }

    fun append(
        doc: PDDocument,
        page: PDPage,
        font: PDType0Font,
        layout: OcrLayout,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        mapping: Mapping? = null,
    ) {
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use {
            stream ->
            layout.lines
                .flatMap { line ->
                    if (line.elements.isEmpty() || line.corrected) listOf(line)
                    else
                        line.elements.map { e ->
                            line.copy(text = e.text, corners = e.corners, elements = emptyList())
                        }
                }
                .forEach { line ->
                    if (line.corners.size != 4) return@forEach
                    val p = line.corners
                    val dx = (p[1].x - p[0].x) * w
                    val dy = -(p[1].y - p[0].y) * h
                    val lineWidth = hypot(dx, dy).coerceAtLeast(1f)
                    val lineHeight =
                        hypot((p[3].x - p[0].x) * w, (p[3].y - p[0].y) * h).coerceAtLeast(1f)
                    val text = safe(font, line.text)
                    if (text.isBlank()) return@forEach
                    val ascent = font.fontDescriptor.ascent / 1000f
                    val descent = font.fontDescriptor.descent / 1000f
                    val size =
                        (lineHeight / (ascent - descent).coerceAtLeast(.1f)).coerceAtLeast(1f)
                    val natural = (font.getStringWidth(text) / 1000 * size).coerceAtLeast(1f)
                    val angle = atan2(dy, dx)
                    val upX = (p[0].x - p[3].x) * w / lineHeight
                    val upY = -(p[0].y - p[3].y) * h / lineHeight
                    mapping?.record(font, text)
                    val actualText =
                        com.tom_roush.pdfbox.cos.COSDictionary().apply {
                            setString(com.tom_roush.pdfbox.cos.COSName.ACTUAL_TEXT, text)
                        }
                    stream.beginMarkedContent(
                        com.tom_roush.pdfbox.cos.COSName.getPDFName("Span"),
                        com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent
                            .PDPropertyList
                            .create(actualText),
                    )
                    stream.beginText()
                    stream.setFont(font, size)
                    stream.setRenderingMode(RenderingMode.NEITHER)
                    stream.setHorizontalScaling(lineWidth / natural * 100)
                    stream.setTextMatrix(
                        Matrix(
                            cos(angle),
                            sin(angle),
                            upX,
                            upY,
                            x + p[3].x * w - upX * descent * size,
                            y + (1 - p[3].y) * h - upY * descent * size,
                        )
                    )
                    stream.showText(text)
                    stream.endText()
                    stream.endMarkedContent()
                }
        }
    }
}
