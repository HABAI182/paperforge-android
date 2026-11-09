package cn.paperforge.app.model

import kotlin.math.hypot

/**
 * Comparison of the viewfinder quad with a second detection on the saved still.
 *
 * The frame quad and the saved photo come from different sensor frames and different analysis
 * resolutions, so the page can move between them. This decision is pure geometry: it picks the
 * still quad only when the two disagree by more than [shiftThreshold] and the still detection is
 * confident enough ([minConfidence]), otherwise the viewfinder-based (refined) quad is kept.
 *
 * Corner shift is measured as the largest per-corner Euclidean distance in normalized image
 * coordinates: x and y are both divided by their own image dimension, then compared as one
 * distance. A 0.01 shift is 1% of the shorter frame dimension for an axis-aligned move.
 */
data class StillCropDecision(
    val quad: Quad,
    val adoptedStill: Boolean,
    /** -1 when no still quad was available and the shift could not be measured. */
    val maxCornerShift: Float,
    val shiftThreshold: Float,
    val previewConfidence: Float,
    val stillConfidence: Float,
    val stillAreaRatio: Float,
    val reason: String,
) {
    /** Numeric form for [cn.paperforge.app.imaging.DetectionResult.diagnostics]. */
    val reasonCode: Int
        get() =
            when (reason) {
                StillCrop.ReasonFailed -> 0
                StillCrop.ReasonStillLowConfidence -> 1
                StillCrop.ReasonWithinThreshold -> 2
                StillCrop.ReasonAdopted -> 3
                StillCrop.ReasonPreviewLowConfidence -> 4
                else -> -1
            }

    fun diagnostics(): Map<String, Float> =
        mapOf(
            "stillMaxCornerShift" to maxCornerShift,
            "stillShiftThreshold" to shiftThreshold,
            "stillAdopted" to if (adoptedStill) 1f else 0f,
            "stillPreviewConfidence" to previewConfidence,
            "stillConfidence" to stillConfidence,
            "stillAreaRatio" to stillAreaRatio,
            "stillReasonCode" to reasonCode.toFloat(),
        )
}

object StillCrop {
    // Calibrated on DocUNet 20 photos (preview 720 px vs still 1800 px): corner shifts p50 .0079,
    // p90 .0116, max .0122 of the frame; a .01 gate adopts the clearly disagreeing tail. The
    // value also ships in assets/scan_params.json ("stillRefineShift") for Android.
    const val DefaultShiftThreshold = .01f
    const val DefaultMinConfidence = .6f

    const val ReasonFailed = "still-detection-failed"
    const val ReasonStillLowConfidence = "still-low-confidence"
    const val ReasonPreviewLowConfidence = "preview-low-confidence"
    const val ReasonWithinThreshold = "still-within-threshold"
    const val ReasonAdopted = "still-adopted"

    /** Largest per-corner distance between two normalized quads, or -1 if either is invalid. */
    fun maxCornerShift(first: Quad, second: Quad): Float {
        if (!first.valid() || !second.valid()) return -1f
        return first.points.zip(second.points).maxOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) }
    }

    /**
     * @param refinedPreview viewfinder quad after the full-resolution edge refine; used as the
     *   fallback when the still result is unavailable or not trusted. Falls back to [preview].
     */
    fun decide(
        preview: Quad,
        previewConfidence: Float,
        still: Quad?,
        stillConfidence: Float,
        refinedPreview: Quad? = null,
        shiftThreshold: Float = DefaultShiftThreshold,
        minConfidence: Float = DefaultMinConfidence,
    ): StillCropDecision {
        val fallback = refinedPreview?.takeIf { it.valid() } ?: preview
        fun keep(maxShift: Float, stillAreaRatio: Float, reason: String) =
            StillCropDecision(
                fallback,
                false,
                maxShift,
                shiftThreshold,
                previewConfidence,
                stillConfidence,
                stillAreaRatio,
                reason,
            )

        if (still == null || !still.valid())
            return keep(-1f, -1f, ReasonFailed)
        val shift = maxCornerShift(preview, still)
        val areaRatio = if (preview.area > 0f) still.area / preview.area else -1f
        if (shift < 0f) return keep(-1f, areaRatio, ReasonFailed)
        // An unsure still must not override the viewfinder. An unsure viewfinder is the opposite case:
        // it is the weaker of the two, from a small and possibly moving frame, so it never outvotes a
        // confident still. Keeping it here is how a page ended up cropped to part of the sheet.
        if (!stillConfidence.isFinite() || stillConfidence < minConfidence)
            return keep(shift, areaRatio, ReasonStillLowConfidence)
        val previewTrusted = previewConfidence.isFinite() && previewConfidence >= minConfidence
        if (previewTrusted && shift <= shiftThreshold)
            return keep(shift, areaRatio, ReasonWithinThreshold)
        return StillCropDecision(
            still,
            true,
            shift,
            shiftThreshold,
            previewConfidence,
            stillConfidence,
            areaRatio,
            ReasonAdopted,
        )
    }
}
