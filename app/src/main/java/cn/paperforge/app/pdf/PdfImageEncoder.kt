package cn.paperforge.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.color.*
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Pinned PdfBox 2.0.27.0: JPEGFactory byte-array API always declares RGB. Construct grayscale
 * XObjects explicitly. Only opaque derived scan bitmaps enter this API.
 */
object PdfImageEncoder {
    fun scan(
        doc: PDDocument,
        bitmap: Bitmap,
        filter: String,
        quality: Int,
        dpi: Int,
    ): PDImageXObject =
        when (filter) {
            "BW" -> binary(doc, bitmap)
            "GRAY",
            "CLEAN" -> jpeg(doc, bitmap, true, quality, dpi)
            else -> jpeg(doc, bitmap, false, quality, dpi)
        }

    fun jpeg(
        doc: PDDocument,
        bitmap: Bitmap,
        gray: Boolean,
        quality: Int,
        dpi: Int,
    ): PDImageXObject {
        val bytes =
            if (gray) {
                val source = Mat()
                val mono = Mat()
                val encoded = MatOfByte()
                val options = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality.coerceIn(1, 100))
                try {
                    Utils.bitmapToMat(bitmap, source)
                    Imgproc.cvtColor(source, mono, Imgproc.COLOR_RGBA2GRAY)
                    check(Imgcodecs.imencode(".jpg", mono, encoded, options)) { "灰度 JPEG 编码失败" }
                    encoded.toArray()
                } finally {
                    source.release()
                    mono.release()
                    encoded.release()
                    options.release()
                }
            } else
                ByteArrayOutputStream().use { output ->
                    // Scans were matted to white before enhancement; do not apply this to arbitrary
                    // imported transparency.
                    bitmap.setHasAlpha(false)
                    check(
                        bitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            quality.coerceIn(1, 100),
                            output,
                        )
                    )
                    output.toByteArray()
                }
        return PDImageXObject(
            doc,
            ByteArrayInputStream(jfifDpi(bytes, dpi)),
            COSName.DCT_DECODE,
            bitmap.width,
            bitmap.height,
            8,
            if (gray) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE,
        )
    }

    private fun binary(doc: PDDocument, bitmap: Bitmap): PDImageXObject {
        val width = bitmap.width
        val stride = (width + 7) / 8
        val row = IntArray(width)
        val packed = ByteArray(stride)
        val bytes = ByteArrayOutputStream()
        DeflaterOutputStream(bytes).use { stream ->
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                packed.fill(0)
                for (x in 0 until width) if (Color.red(row[x]) >= 128)
                    packed[x / 8] = (packed[x / 8].toInt() or (1 shl (7 - x % 8))).toByte()
                stream.write(packed)
            }
        }
        return PDImageXObject(
            doc,
            ByteArrayInputStream(bytes.toByteArray()),
            COSName.FLATE_DECODE,
            width,
            bitmap.height,
            1,
            PDDeviceGray.INSTANCE,
        )
    }

    private fun jfifDpi(input: ByteArray, dpi: Int): ByteArray {
        val density = dpi.coerceIn(1, 65535)
        var position = 2
        while (position + 4 < input.size && input[position] == 0xff.toByte()) {
            val marker = input[position + 1].toInt() and 255
            if (marker == 0xda || marker == 0xd9) break
            val length =
                ((input[position + 2].toInt() and 255) shl 8) +
                    (input[position + 3].toInt() and 255)
            if (length < 2 || position + length + 2 > input.size) break
            if (
                marker == 0xe0 &&
                    length >= 16 &&
                    input
                        .copyOfRange(position + 4, position + 9)
                        .contentEquals(byteArrayOf(74, 70, 73, 70, 0))
            ) {
                input[position + 11] = 1
                input[position + 12] = (density ushr 8).toByte()
                input[position + 13] = density.toByte()
                input[position + 14] = (density ushr 8).toByte()
                input[position + 15] = density.toByte()
                return input
            }
            position += length + 2
        }
        val app0 =
            byteArrayOf(
                0xff.toByte(),
                0xe0.toByte(),
                0,
                16,
                74,
                70,
                73,
                70,
                0,
                1,
                2,
                1,
                (density ushr 8).toByte(),
                density.toByte(),
                (density ushr 8).toByte(),
                density.toByte(),
                0,
                0,
            )
        return input.copyOfRange(0, 2) + app0 + input.copyOfRange(2, input.size)
    }
}
