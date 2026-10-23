package cn.paperforge.app.imaging

import android.graphics.*
import cn.paperforge.app.model.DeskewGeometry

object PageOrientation {
    fun rotate(source: Bitmap, degrees: Float): Bitmap {
        val plan = DeskewGeometry(source.width, source.height, degrees)
        return Bitmap.createBitmap(plan.outputWidth, plan.outputHeight, Bitmap.Config.ARGB_8888)
            .also { output ->
                val canvas = Canvas(output)
                canvas.drawColor(Color.WHITE)
                val matrix =
                    Matrix().apply {
                        setValues(
                            floatArrayOf(
                                plan.cosine,
                                -plan.sine,
                                plan.translateX,
                                plan.sine,
                                plan.cosine,
                                plan.translateY,
                                0f,
                                0f,
                                1f,
                            )
                        )
                    }
                canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                output.setHasAlpha(false)
            }
    }
}
