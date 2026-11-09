package cn.paperforge.app.imaging

import android.graphics.Bitmap
import cn.paperforge.app.model.DewarpGrid

/** Page curvature correction. Applied explicitly, with undo support. */
object PageDewarp {
    data class Result(
        val bitmap: Bitmap,
        val grid: DewarpGrid,
        val note: String = "现有曲面校正",
        val lines: Int = 0,
        val algorithm: String = IndependentPageDewarp.VERSION,
        val diagnostics: String? = null,
    )

    fun apply(bitmap: Bitmap, strength: Float): Bitmap = process(bitmap, strength).bitmap

    fun fingerprint(): String = IndependentPageDewarp.VERSION

    fun process(bitmap: Bitmap, strength: Float): Result {
        val result = IndependentPageDewarp.process(bitmap, strength)
        return Result(
            result.bitmap,
            result.grid,
            result.note,
            result.lines,
            IndependentPageDewarp.VERSION,
        )
    }
}
