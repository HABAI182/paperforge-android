package cn.paperforge.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cn.paperforge.app.model.PointN

/**
 * The crop loupe: the photo around [target] magnified [zoom] times relative to the crop view, with the crop outline
 * drawn thin over it and a crosshair whose open centre leaves the exact point visible.
 *
 * [shown] is the size the photo is drawn at in the crop view; [image] may be a sharper copy than the view's.
 */
internal fun DrawScope.drawCropLoupe(
    image: ImageBitmap,
    center: Offset,
    radius: Float,
    zoom: Float,
    shown: Size,
    target: PointN,
    outline: List<PointN>,
    accent: Color,
) {
    val k = zoom * shown.width / image.width
    val spot = Offset(target.x * image.width, target.y * image.height)
    drawCircle(Color.Black.copy(alpha = .22f), radius + 4.dp.toPx(), center + Offset(0f, 2.dp.toPx()))
    val lens = Path().apply { addOval(Rect(center, radius)) }
    clipPath(lens) {
        drawRect(Color(0xFF303030), center - Offset(radius, radius), Size(radius * 2, radius * 2))
        withTransform({
            translate(center.x - spot.x * k, center.y - spot.y * k)
            scale(k, k, Offset.Zero)
        }) {
            drawImage(
                image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(image.width, image.height),
                filterQuality = FilterQuality.Low,
            )
        }
        val path =
            Path().apply {
                outline.forEachIndexed { i, p ->
                    val x = center.x + (p.x - target.x) * shown.width * zoom
                    val y = center.y + (p.y - target.y) * shown.height * zoom
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        // Thin and slightly see-through, so the paper edge under it stays visible.
        drawPath(path, accent.copy(alpha = .85f), style = Stroke(1.25.dp.toPx()))
        val gap = 5.dp.toPx()
        val arm = 11.dp.toPx()
        listOf(Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f)).forEach { d ->
            val a = center + d * gap
            val b = center + d * (gap + arm)
            drawLine(Color.White, a, b, 3.5.dp.toPx(), StrokeCap.Round)
            drawLine(accent, a, b, 1.5.dp.toPx(), StrokeCap.Round)
        }
    }
    drawCircle(Color.White, radius, center, style = Stroke(3.dp.toPx()))
    drawCircle(accent, radius + 1.5.dp.toPx(), center, style = Stroke(1.dp.toPx()))
}
