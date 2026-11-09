package cn.paperforge.app.imaging

import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

internal object InkComponents {
    /**
     * Reuse OpenCV components to protect complete observed strokes, including small punctuation.
     * Rules are separate evidence; excluding them prevents a table from joining all ghost text.
     */
    fun protection(
        ink: ByteArray,
        soft: ByteArray,
        rules: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val mask = Mat()
        val labels = Mat()
        val stats = Mat()
        val centers = Mat()
        try {
            fun components(threshold: Int): ByteArray {
                val foreground =
                    ByteArray(ink.size) { i ->
                        if ((ink[i].toInt() and 255) >= threshold && (rules[i].toInt() and 255) < 4)
                            255.toByte()
                        else 0
                    }
                mask.create(height, width, CvType.CV_8UC1)
                mask.put(0, 0, foreground)
                val count =
                    Imgproc.connectedComponentsWithStats(
                        mask,
                        labels,
                        stats,
                        centers,
                        8,
                        CvType.CV_32S,
                    )
                val ids = IntArray(ink.size).also { labels.get(0, 0, it) }
                val regions = IntArray(count * 5).also { stats.get(0, 0, it) }
                return PaperPixelEngine.componentProtection(
                    ink,
                    soft,
                    ids,
                    regions,
                    width,
                    height,
                    floatArrayOf(
                        ScanConfig.float("workbookComponentAnchorFloor", 4f),
                        ScanConfig.float("workbookComponentAnchorFraction", .15f),
                        ScanConfig.float("workbookSharpFraction", .22f),
                        ScanConfig.float("workbookWeakAnchorFraction", .16f),
                    ),
                )
            }
            val rowCoverage = IntArray(height)
            for (i in rules.indices) if ((rules[i].toInt() and 255) >= 4) rowCoverage[i / width]++
            var bands = 0
            var previous = -height
            for (y in 0 until height) if (
                rowCoverage[y] > width * .45 && y - previous > max(6, height / 120)
            ) {
                bands++
                previous = y
            }
            // Printed pages do not need a high component cutoff to disconnect ruling/paper
            // texture. Keep their softer connected components at the original threshold.
            val threshold =
                if (bands >= 3) ScanConfig.int("workbookComponentContrast", 8)
                else ScanConfig.int("workbookPrintedComponentContrast", 3)
            val protected = components(threshold.coerceIn(3, 12))
            if (ScanConfig.int("workbookSupportedComponents", 1) != 0) {
                // A second threshold separates a soft front glyph from a low-contrast connected
                // web of paper texture/bleed. It adds positive evidence; it never deletes ink.
                val detail =
                    components(ScanConfig.int("workbookSupportContrast", 20).coerceIn(12, 35))
                for (i in protected.indices) if (detail[i].toInt() and 4 != 0)
                    protected[i] = (protected[i].toInt() or 4).toByte()
            }
            return protected
        } finally {
            listOf(mask, labels, stats, centers).forEach(Mat::release)
        }
    }

    /**
     * Adapted dark-core criterion from the supplied scan-code-v2 restore.py; rules and the learned
     * matte independently protect faint front ink. Only neutral residuals are cleared.
     */
    fun cleanFaint(
        output: ByteArray,
        rules: ByteArray,
        foreground: ByteArray?,
        width: Int,
        height: Int,
        amount: Float,
    ) {
        if (amount <= 0f || ScanConfig.int("paperFaintCleanup", 1) == 0) return
        PaperPixelEngine.faintClean(
            output,
            rules,
            foreground,
            width,
            height,
            floatArrayOf(
                ScanConfig.float("paperFaintLow", 200f),
                ScanConfig.float("paperFaintHigh", 252f),
                ScanConfig.float("paperFaintCore", 195f),
                ScanConfig.float("paperFaintRadius", 2f),
                amount * ScanConfig.float("paperFaintStrength", 1f),
                ScanConfig.float("paperFaintConfidence", .85f),
            ),
        )
    }

    fun clean(
        output: ByteArray,
        ink: ByteArray,
        rules: ByteArray,
        width: Int,
        height: Int,
        amount: Float,
    ) {
        if (amount <= 0f) return
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val mask = mat()
        val labels = mat()
        val stats = mat()
        val centers = mat()
        val seeds = mat()
        val nearby = mat()
        try {
            val bytes =
                ByteArray(output.size) {
                    if ((output[it].toInt() and 255) < 240 && (rules[it].toInt() and 255) < 10)
                        255.toByte()
                    else 0
                }
            mask.create(height, width, CvType.CV_8UC1)
            mask.put(0, 0, bytes)
            val count =
                Imgproc.connectedComponentsWithStats(mask, labels, stats, centers, 8, CvType.CV_32S)
            val ids = IntArray(output.size)
            labels.get(0, 0, ids)
            val componentStats = IntArray(count * 5)
            stats.get(0, 0, componentStats)
            val maximum =
                max(6, (output.size * ScanConfig.float("workbookSpeckArea", .000012f)).roundToInt())
            seeds.create(height, width, CvType.CV_8UC1)
            seeds.put(0, 0, PaperPixelEngine.componentSeeds(ink, ids, componentStats))
            val radius = max(3, (min(width, height) * .025f).roundToInt()) or 1
            val kernel =
                Imgproc.getStructuringElement(
                        Imgproc.MORPH_ELLIPSE,
                        Size(radius.toDouble(), radius.toDouble()),
                    )
                    .also(owned::add)
            Imgproc.dilate(seeds, nearby, kernel)
            nearby.get(0, 0, bytes)
            PaperPixelEngine.componentClean(
                output,
                ink,
                ids,
                componentStats,
                bytes,
                amount,
                maximum,
            )
        } finally {
            owned.forEach(Mat::release)
        }
    }
}
