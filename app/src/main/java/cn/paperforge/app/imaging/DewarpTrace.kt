package cn.paperforge.app.imaging

import android.graphics.BitmapFactory
import android.graphics.Matrix
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import java.io.File
import kotlin.math.*

data class DewarpTrace(
    val input: File,
    val grid: DewarpGrid,
    val sourceFile: File,
    val version: String,
) {
    /** OCR coordinates refer to the current rendered page, including later crop/rotation. */
    fun inputPoint(page: PageRow, point: PointN, renderWidth: Int, renderHeight: Int): PointN {
        var p = point
        if (page.deskewDegrees != 0f) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(page.original, bounds)
            val ratio =
                page.outputAspect.takeIf { it > 0f }
                    ?: ImageGeometry.aspect(
                        Quad.decode(page.corners),
                        bounds.outWidth,
                        bounds.outHeight,
                    )
            val rotatedRatio = if (page.rotation % 180 == 0) ratio else 1 / ratio
            val angle = page.deskewDegrees * PI / 180
            val h =
                ((renderHeight / (abs(cos(angle)) + abs(sin(angle)) * rotatedRatio) +
                        renderWidth / (abs(cos(angle)) * rotatedRatio + abs(sin(angle)))) / 2)
                    .roundToInt()
            p = DeskewGeometry((h * rotatedRatio).roundToInt(), h, page.deskewDegrees).inverse(p)
        }
        p =
            when ((page.rotation % 360 + 360) % 360) {
                90 -> PointN(p.y, 1 - p.x)
                180 -> PointN(1 - p.x, 1 - p.y)
                270 -> PointN(1 - p.y, p.x)
                else -> p
            }
        val matrix = Matrix()
        check(
            matrix.setPolyToPoly(
                floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
                0,
                Quad.decode(page.corners).points.flatMap { listOf(it.x, it.y) }.toFloatArray(),
                0,
                4,
            )
        )
        val xy = floatArrayOf(p.x, p.y)
        matrix.mapPoints(xy)
        return grid.inputPoint(PointN(xy[0], xy[1]))
    }

    companion object {
        fun load(page: PageRow): DewarpTrace? {
            val meta = PageResources.metadata(page)
            val steps = meta.optJSONArray("steps") ?: return null
            val resources = PageResources.entries(page).associateBy { it.id }
            for (i in steps.length() - 1 downTo 0) {
                val step = steps.getJSONObject(i)
                if (
                    step.optString("kind") == "dewarp" &&
                        resources[step.optString("output")]?.path == page.original
                ) {
                    return DewarpTrace(
                        File(resources.getValue(step.getString("input")).path),
                        DewarpGrid.read(File(resources.getValue(step.getString("grid")).path)),
                        File(resources.getValue(step.getString("original")).path),
                        step.getString("algorithm"),
                    )
                }
            }
            return null
        }
    }
}
