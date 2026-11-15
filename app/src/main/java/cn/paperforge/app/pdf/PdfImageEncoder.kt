package cn.paperforge.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import cn.paperforge.app.imaging.ScanConfig
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
    // 3: quality applies to every lossy page (练习册 pages used to switch to lossless at quality 80 and above, so the
    // slider did nothing there); lossless is its own choice; grey pages are stored as one channel; grey 练习册 pages
    // are compressed by reducing tone levels instead of JPEG. 4: pages are enhanced at full size, then downscaled.
    const val VERSION = "scan-image-codec-4"

    /**
     * One page image.
     *
     * - [lossless]: Flate, exact samples (grey pages as one channel).
     * - 练习册 pages that came out grey (white paper, dark ink): [quality] sets how many tone levels are kept, stored
     *   with Flate (see [toneLevels]). On such pages Flate is smaller than JPEG at any quality and leaves no ringing
     *   around thin lines, so this is the lossy compression that suits them: fewer levels, smaller file, edges intact.
     *   At 95 and above every level is kept (exact).
     * - Any other page: JPEG at [quality], one channel when the page has no colour.
     * - 黑白 (BW) pages are 1-bit Flate either way: they are already two-tone.
     */
    fun scan(
        doc: PDDocument,
        bitmap: Bitmap,
        filter: String,
        quality: Int,
        dpi: Int,
        lossless: Boolean = false,
    ): PDImageXObject {
        if (filter == "BW") return binary(doc, bitmap)
        if (lossless) return lossless(doc, bitmap)
        val gray = filter == "GRAY" || isGray(bitmap)
        if (gray && filter in setOf("WORKBOOK", "CLEAN") && ScanConfig.int("workbookToneQuantize", 1) != 0) {
            val levels = toneLevels(quality)
            return if (levels >= 256) lossless(doc, bitmap) else quantizedGray(doc, bitmap, levels)
        }
        return jpeg(doc, bitmap, gray, quality, dpi)
    }

    /** Tone levels kept for an image-quality value on grey document pages: 95+ all, then 64, 32, 16, 8. */
    fun toneLevels(quality: Int): Int =
        when {
            quality >= 95 -> 256
            quality >= 80 -> 64
            quality >= 65 -> 32
            quality >= 45 -> 16
            else -> 8
        }

    /**
     * Grey samples reduced to [levels] evenly spaced tones (black and pure white kept exactly), Flate-compressed,
     * packed to 4 bits a sample when 16 or fewer levels remain. No dithering: strokes keep crisp edges, and a faint
     * pencil line stays a distinct tone from the paper down to 8 levels.
     */
    fun quantizedGray(doc: PDDocument, bitmap: Bitmap, levels: Int): PDImageXObject {
        val width = bitmap.width
        val bits = if (levels <= 16) 4 else 8
        val stride = if (bits == 4) (width + 1) / 2 else width
        val row = IntArray(width)
        val packed = ByteArray(stride)
        val step = 255f / (levels - 1)
        // Sample value written for each grey: the nearest level, as an index in the 4-bit range or as the level's grey.
        val map = IntArray(256) { v ->
            val index = Math.round(v / step).coerceIn(0, levels - 1)
            if (bits == 4) Math.round(index * 15f / (levels - 1)) else Math.round(index * step).coerceIn(0, 255)
        }
        val bytes = ByteArrayOutputStream()
        DeflaterOutputStream(bytes).use { stream ->
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                if (bits == 4) {
                    packed.fill(0)
                    for (x in 0 until width) {
                        val v = map[Color.red(row[x])]
                        packed[x / 2] = (packed[x / 2].toInt() or if (x % 2 == 0) v shl 4 else v).toByte()
                    }
                } else for (x in 0 until width) packed[x] = map[Color.red(row[x])].toByte()
                stream.write(packed)
            }
        }
        return PDImageXObject(
            doc,
            ByteArrayInputStream(bytes.toByteArray()),
            COSName.FLATE_DECODE,
            width,
            bitmap.height,
            bits,
            PDDeviceGray.INSTANCE,
        )
    }

    /** [bitmap] area-averaged so its long edge is [maxEdge] (thin strokes become lighter, not broken). */
    fun downscale(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val ratio = maxEdge.toDouble() / maxOf(bitmap.width, bitmap.height)
        val width = Math.round(bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = Math.round(bitmap.height * ratio).toInt().coerceAtLeast(1)
        val source = Mat()
        val resized = Mat()
        try {
            Utils.bitmapToMat(bitmap, source)
            Imgproc.resize(source, resized, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(resized, it) }
        } finally {
            source.release()
            resized.release()
        }
    }

    /** Whether every pixel is neutral (R = G = B), so one channel holds the page exactly. */
    fun isGray(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val row = IntArray(width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (color in row) if (Color.red(color) != Color.green(color) || Color.red(color) != Color.blue(color)) return false
        }
        return true
    }

    /** Exact opaque scan samples, no JPEG ringing, alpha mask or character reconstruction. */
    fun lossless(doc: PDDocument, bitmap: Bitmap): PDImageXObject {
        val width = bitmap.width
        val row = IntArray(width)
        var gray = true
        loop@ for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (color in row) if (
                Color.red(color) != Color.green(color) || Color.red(color) != Color.blue(color)
            ) {
                gray = false
                break@loop
            }
        }
        val buffer = ByteArray(width * if (gray) 1 else 3)
        val bytes = ByteArrayOutputStream()
        DeflaterOutputStream(bytes).use { stream ->
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    if (gray) buffer[x] = Color.red(row[x]).toByte()
                    else {
                        buffer[x * 3] = Color.red(row[x]).toByte()
                        buffer[x * 3 + 1] = Color.green(row[x]).toByte()
                        buffer[x * 3 + 2] = Color.blue(row[x]).toByte()
                    }
                }
                stream.write(buffer)
            }
        }
        return PDImageXObject(
            doc,
            ByteArrayInputStream(bytes.toByteArray()),
            COSName.FLATE_DECODE,
            width,
            bitmap.height,
            8,
            if (gray) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE,
        )
    }

    fun jpeg(
        doc: PDDocument,
        bitmap: Bitmap,
        gray: Boolean,
        quality: Int,
        dpi: Int,
    ): PDImageXObject =
        PDImageXObject(
            doc,
            ByteArrayInputStream(jpegBytes(bitmap, gray, quality, dpi)),
            COSName.DCT_DECODE,
            bitmap.width,
            bitmap.height,
            8,
            if (gray) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE,
        )

    fun jpegBytes(bitmap: Bitmap, gray: Boolean, quality: Int, dpi: Int): ByteArray {
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
        return jfifDpi(bytes, dpi)
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
