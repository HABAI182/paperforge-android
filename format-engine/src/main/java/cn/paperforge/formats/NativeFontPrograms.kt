package cn.paperforge.formats

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.PDFDocument
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Unwrap the tested builtin Noto CJK CFF1: MuPDF dispatches OpenType into its TTF subsetter. */
object NativeFontPrograms {
    fun unwrapCff(pdf: PDFDocument) {
        val visited = mutableSetOf<Int>()
        for (index in 0 until pdf.countPages()) {
            val page = pdf.findPage(index)
            val resources = page.getInheritable("Resources")
            val fonts = resources.get("Font")
            try {
                for (key in fonts) {
                    val font = fonts.get(key)
                    val base = font.get("BaseFont")
                    val descendants = font.get("DescendantFonts")
                    val child = descendants.get(0)
                    val descriptor = child.get("FontDescriptor")
                    val stream = descriptor.get("FontFile3")
                    val type = stream.get("Subtype")
                    val childType = child.get("Subtype")
                    try {
                        if (
                            !base.asName().replace(" ", "").contains("NotoSerifCJK") ||
                                type.asName() != "OpenType" ||
                                childType.asName() != "CIDFontType0" ||
                                !visited.add(stream.asIndirect())
                        )
                            continue
                        val bytes = stream.readStream()
                        if (
                            bytes.size < 12 ||
                                bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "OTTO"
                        )
                            continue
                        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                        val count = data.getShort(4).toInt() and 65535
                        for (table in 0 until count) {
                            val entry = 12 + table * 16
                            if (entry + 16 > bytes.size) break
                            if (data.getInt(entry) != 0x43464620) continue // 'CFF '
                            val offset = data.getInt(entry + 8)
                            val length = data.getInt(entry + 12)
                            if (offset < 0 || length <= 0 || offset.toLong() + length > bytes.size)
                                continue
                            val buffer = Buffer()
                            val subtype = pdf.newName("CIDFontType0C")
                            try {
                                buffer.writeBytes(bytes.copyOfRange(offset, offset + length))
                                stream.writeStream(buffer)
                                stream.put("Subtype", subtype)
                                stream.put("Length1", length)
                            } finally {
                                subtype.destroy()
                                buffer.destroy()
                            }
                            break
                        }
                    } finally {
                        childType.destroy()
                        base.destroy()
                        type.destroy()
                        stream.destroy()
                        descriptor.destroy()
                        child.destroy()
                        descendants.destroy()
                        font.destroy()
                        key.destroy()
                    }
                }
            } finally {
                fonts.destroy()
                resources.destroy()
                page.destroy()
            }
        }
    }
}
