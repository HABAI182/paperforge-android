package cn.paperforge.app.imaging

import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Negative evidence must agree: weak source, low learned score, no neighbouring front core. The
 * mask is an operational cleanup decision, not a calibrated semantic probability.
 */
internal object WorkbookBackground {
    const val VERSION = "workbook-background-4"

    fun mask(
        ink: ByteArray,
        soft: ByteArray,
        rules: ByteArray,
        support: ByteArray,
        learned: ByteArray?,
        width: Int,
        height: Int,
        strong: Boolean = false,
        amount: Float = 1f,
    ): ByteArray {
        val result = ByteArray(ink.size)
        if (ScanConfig.int("workbookConfidentBackground", 1) == 0) return result
        val seed = Mat(height, width, CvType.CV_8UC1)
        val nearby = Mat()
        val localSeeds = Mat(height, width, CvType.CV_8UC1)
        val localSupport = Mat()
        val radius = ScanConfig.int("workbookBackgroundAnchorRadius", 1).coerceIn(1, 3)
        val localKernel =
            Imgproc.getStructuringElement(
                if (ScanConfig.int("workbookBackgroundDiagonalSupport", 1) != 0) Imgproc.MORPH_RECT
                else Imgproc.MORPH_ELLIPSE,
                Size((radius * 2 + 1).toDouble(), (radius * 2 + 1).toDouble()),
            )
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        try {
            seed.put(
                0,
                0,
                ByteArray(ink.size) {
                    if ((ink[it].toInt() and 255) >= 75 && (rules[it].toInt() and 255) < 4)
                        255.toByte()
                    else 0
                },
            )
            Imgproc.dilate(seed, nearby, kernel)
            val near = ByteArray(ink.size).also { nearby.get(0, 0, it) }
            val localized = ScanConfig.int("workbookBackgroundLocalSupport", 1) != 0
            val anchorFloor = ScanConfig.float("workbookBackgroundAnchorFloor", 1f)
            val anchorFraction = ScanConfig.float("workbookBackgroundAnchorFraction", .12f)
            localSeeds.put(
                0,
                0,
                ByteArray(ink.size) { i ->
                    val v = ink[i].toInt() and 255
                    val supported = support[i].toInt() and (4 or 8) != 0
                    if (
                        supported &&
                            v >= 3 &&
                            v - (soft[i].toInt() and 255) >= max(anchorFloor, v * anchorFraction)
                    )
                        255.toByte()
                    else 0
                },
            )
            Imgproc.dilate(localSeeds, localSupport, localKernel)
            val local = ByteArray(ink.size).also { localSupport.get(0, 0, it) }
            // The user slider is labelled "去透页强度". It has to drive the suppression depth:
            // before this change the ceiling was constant, so 100% removed exactly as much reverse
            // print as 80% and the setting only changed the blend. The extra range on top of the
            // tuned base is bounded (10/14 levels) and the ambiguity guard in WorkbookContent still
            // vetoes the aggressive policy when unclassified faint source components exist.
            val baseCeiling =
                ScanConfig.int(
                    if (strong) "cleanBackgroundCeiling" else "workbookBackgroundCeiling",
                    if (strong) 60 else 38,
                )
            val strengthExtra =
                ScanConfig.int(
                    if (strong) "cleanGhostStrengthExtra" else "workbookGhostStrengthExtra",
                    if (strong) 14 else 10,
                )
            val scaled =
                (((amount.coerceIn(0f, 1f) - .8f) / .2f).coerceIn(0f, 1f) * strengthExtra)
                    .roundToInt()
            val ceiling = (baseCeiling + scaled).coerceIn(10, 88)
            val scoreCeiling =
                ScanConfig.int(
                        if (strong) "cleanBackgroundScore" else "workbookBackgroundScore",
                        if (strong) 90 else 80,
                    )
                    .coerceIn(20, 110)
            val protectionBits =
                2 or 4 or (if (ScanConfig.int("cleanBackgroundProtectAnchors", 1) != 0) 8 else 0)
            val smoothFraction = ScanConfig.float("workbookBackgroundSmoothFraction", .22f)
            for (i in result.indices) {
                val v = ink[i].toInt() and 255
                val s = support[i].toInt() and 255
                if ((rules[i].toInt() and 255) >= 4 || near[i] != 0.toByte()) continue
                val score = learned?.get(i)?.toInt()?.and(255) ?: 255
                val smooth = v - (soft[i].toInt() and 255) < max(3f, v * smoothFraction)
                // A texture bridge can join faint reverse print to a dark front glyph. Whole
                // component membership must not protect every smooth pixel in that bridge.
                // Keep fills, real local edge anchors and their one-pixel antialias fringe.
                if (
                    s and 2 != 0 ||
                        (if (localized) s and protectionBits != 0 && local[i] != 0.toByte()
                        else s and protectionBits != 0)
                )
                    continue
                // No area-only deletion: faint punctuation next to real ink remains in the guard.
                if ((v <= 2 && s == 0) || (v <= ceiling && smooth && score < scoreCeiling))
                    result[i] = 255.toByte()
            }
            return result
        } finally {
            seed.release()
            nearby.release()
            kernel.release()
            localSeeds.release()
            localSupport.release()
            localKernel.release()
        }
    }
}
