package cn.paperforge.app.pdf

/**
 * How page images are written into an exported PDF: three independent choices.
 *
 * - [quality] (图像质量): the JPEG quality of lossy page images, 30–100. It changes compression only, never the pixel
 *   size. It does not apply to [lossless] export, nor to 黑白 pages (stored as 1-bit lossless images).
 * - [resolution] (输出分辨率): the most pixels a page image may have on its long edge. [ORIGINAL] keeps the page's own
 *   pixels; [HIGH], [MEDIUM] and [SMALL] cap the long edge at A4 300, 220 and 150 dpi. The page is enhanced at its own
 *   pixels first and the finished image is area-averaged down, so thin and faint strokes stay. It does not change
 *   the page's physical size.
 * - [lossless] (无损保存): page images are stored with Flate (lossless) at their original pixel size; quality and
 *   resolution do not apply. A camera photo that was not edited at all is embedded as the camera's own JPEG file, byte
 *   for byte: that is the original, while a lossless copy of an already processed page only keeps what the page holds.
 *
 * Older settings used the names DRAFT, STANDARD, HIGH and CUSTOM / "DPI:n"; they still work: DRAFT = [SMALL],
 * STANDARD = [MEDIUM], HIGH = [HIGH], a custom DPI caps the long edge at A4 at that DPI.
 */
data class PdfImageSettings(val quality: Int = 85, val resolution: String = ORIGINAL, val lossless: Boolean = false) {
    companion object {
        const val ORIGINAL = "ORIGINAL"
        const val HIGH = "HIGH"
        const val MEDIUM = "MEDIUM"
        const val SMALL = "SMALL"
        val resolutions = listOf(ORIGINAL, HIGH, MEDIUM, SMALL)

        /** The physical page size of "match the photo" paper is fixed at this density, whatever the pixels. */
        const val PAGE_DPI = 220
        /** A4 long edge at [PAGE_DPI]: pages with more pixels than this keep the size of an A4 sheet. */
        val PAGE_EDGE = edgeAt(PAGE_DPI)

        fun edgeAt(dpi: Int) = (dpi * 297f / 25.4f).toInt()

        /** Long-edge cap in pixels for a resolution name (new or legacy); null keeps the page's own pixels. */
        fun maxEdge(resolution: String): Int? =
            when {
                resolution == ORIGINAL -> null
                resolution == HIGH -> edgeAt(cn.paperforge.app.imaging.ScanConfig.int("highDpi", 300))
                resolution == MEDIUM || resolution == "STANDARD" -> edgeAt(cn.paperforge.app.imaging.ScanConfig.int("standardDpi", 220))
                resolution == SMALL || resolution == "DRAFT" -> edgeAt(cn.paperforge.app.imaging.ScanConfig.int("draftDpi", 150))
                resolution.startsWith("DPI:") -> edgeAt(resolution.removePrefix("DPI:").toIntOrNull()?.coerceIn(72, 600) ?: PAGE_DPI)
                resolution == "CUSTOM" -> edgeAt(cn.paperforge.app.UserSettings[cn.paperforge.app.SettingKeys.CustomDpi].toInt().coerceIn(72, 600))
                else -> edgeAt(PAGE_DPI)
            }

        /**
         * The current name for a stored resolution (migration of saved preferences, presets and rules). A custom
         * density (CUSTOM, "DPI:n") is the user's own choice and stays exactly as it is.
         */
        fun normalize(resolution: String?): String =
            when (resolution) {
                null, "" -> ORIGINAL
                "DRAFT" -> SMALL
                "STANDARD" -> MEDIUM
                ORIGINAL, HIGH, MEDIUM, SMALL, "CUSTOM" -> resolution
                else -> if (resolution.startsWith("DPI:")) resolution else ORIGINAL
            }

        /**
         * Enhancement size used for 高/中/小 when the page at its own pixels did not fit in memory (never for 原始 or
         * 无损, which fail clearly instead).
         */
        /** Render edge meaning "the page's own pixels": render never enlarges, so this only lifts the cap. */
        const val NATIVE_EDGE = 1 shl 15

        /** Allocation failure, from the Java heap or from OpenCV's native allocator. */
        fun outOfMemory(failure: Throwable): Boolean =
            failure is OutOfMemoryError ||
                (failure is org.opencv.core.CvException && failure.message.orEmpty().contains("memory", ignoreCase = true))

        fun fallbackEdge(): Int {
            // Measured on the emulator: about 10 bytes of Java heap a pixel while a page is enhanced and encoded; 16
            // leaves headroom. A small heap gets a smaller cap rather than an OutOfMemoryError.
            val perPixel = cn.paperforge.app.imaging.ScanConfig.int("exportHeapBytesPerPixel", 16).coerceAtLeast(8)
            val fits = kotlin.math.sqrt(Runtime.getRuntime().maxMemory() * .6 / perPixel / .75).toInt()
            return minOf(cn.paperforge.app.imaging.ScanConfig.int("exportOriginalEdgeCap", 6000), fits)
                .coerceAtLeast(edgeAt(PAGE_DPI))
        }

        /**
         * The IJG quality a JPEG was saved with, estimated from its luminance quantisation table (as libjpeg scales
         * the standard table), or null when the file has no readable table. Used to decide whether re-encoding an
         * untouched camera photo at [quality] could gain anything.
         */
        fun jpegQuality(bytes: ByteArray): Int? {
            var i = 2
            while (i + 4 < bytes.size && bytes[i] == 0xff.toByte()) {
                val marker = bytes[i + 1].toInt() and 255
                val length = ((bytes[i + 2].toInt() and 255) shl 8) or (bytes[i + 3].toInt() and 255)
                if (marker == 0xda || length < 2) break
                if (marker == 0xdb) {
                    var p = i + 4
                    val end = i + 2 + length
                    while (p < end) {
                        val pq = (bytes[p].toInt() and 255) shr 4
                        val tq = bytes[p].toInt() and 15
                        val table = IntArray(64) { k -> if (pq == 0) bytes[p + 1 + k].toInt() and 255 else ((bytes[p + 1 + 2 * k].toInt() and 255) shl 8) or (bytes[p + 2 + 2 * k].toInt() and 255) }
                        if (tq == 0) return qualityOf(table)
                        p += 1 + if (pq == 0) 64 else 128
                    }
                }
                i += 2 + length
            }
            return null
        }

        // Standard JPEG luminance table (Annex K), in zig-zag order as stored in DQT.
        private val standardLuma = intArrayOf(
            16, 11, 12, 14, 12, 10, 16, 14, 13, 14, 18, 17, 16, 19, 24, 40, 26, 24, 22, 22, 24, 49, 35, 37, 29, 40, 58, 51, 61, 60, 57, 51,
            56, 55, 64, 72, 92, 78, 64, 68, 87, 69, 55, 56, 80, 109, 81, 87, 95, 98, 103, 104, 103, 62, 77, 113, 121, 112, 100, 120, 92, 101, 103, 99,
        )

        private fun qualityOf(table: IntArray): Int {
            // libjpeg: scaled = (std * scale + 50) / 100, scale = 5000/q (q<50) or 200 - 2q.
            val scale = table.indices.sumOf { table[it] * 100.0 / standardLuma[it] } / table.size
            val q = if (scale <= 100) (200 - scale) / 2 else 5000 / scale
            return q.toInt().coerceIn(1, 100)
        }
    }
}
