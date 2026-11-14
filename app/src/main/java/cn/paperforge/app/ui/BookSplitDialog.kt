package cn.paperforge.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.*

/**
 * Split an open book or notebook into its pages. The split line starts where the gutter was found ([initialTop] and
 * [initialBottom], fractions of the crop's width at its top and bottom edges) and is moved by dragging either end on
 * the photo or with the sliders. What will be dropped is shaded, and nothing is cut until 分割 is pressed; the photo is
 * kept, and undo restores the page.
 */
@Composable
fun BookSplitDialog(
    page: PageRow,
    onDismiss: () -> Unit,
    initialTop: Float = .5f,
    initialBottom: Float = initialTop,
    initialKeep: String = "both",
    message: String = "拖动书脊两端或用下面的滑块，分开左右页；不必从正中硬切。",
    onApply: (Float, Float, String) -> Unit,
) {
    val bitmap = pageBitmap(page, 1400, true)
    var top by remember { mutableFloatStateOf(initialTop.coerceIn(.15f, .85f)) }
    var bottom by remember { mutableFloatStateOf(initialBottom.coerceIn(.15f, .85f)) }
    var linked by remember { mutableStateOf(kotlin.math.abs(initialTop - initialBottom) < .005f) }
    var keep by remember { mutableStateOf(initialKeep) }
    val color = MaterialTheme.colorScheme.primary
    val quad = Quad.decode(page.corners)
    val split = CropGeometry.split(quad, top, bottom)
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.systemBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("调整书脊", style = MaterialTheme.typography.headlineSmall)
                Text(message, style = MaterialTheme.typography.bodyMedium)
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let { b ->
                        val ratio = b.width.toFloat() / b.height
                        val imageModifier =
                            if (maxWidth / maxHeight > ratio)
                                Modifier.fillMaxHeight().aspectRatio(ratio)
                            else Modifier.fillMaxWidth().aspectRatio(ratio)
                        Box(imageModifier) {
                            Image(b.asImageBitmap(), "书脊预览", Modifier.fillMaxSize())
                            Canvas(
                                Modifier.fillMaxSize().testTag("book-split-canvas").pointerInput(page.id) {
                                    var dragging = 0
                                    detectDragGestures(
                                        onDragStart = { at ->
                                            val (a, z) = CropGeometry.split(quad, top, bottom).first.points.let { it[1] to it[2] }
                                            val da = (Offset(a.x * size.width, a.y * size.height) - at).getDistance()
                                            val dz = (Offset(z.x * size.width, z.y * size.height) - at).getDistance()
                                            dragging = if (da <= dz) 1 else 2
                                        },
                                        onDragEnd = { dragging = 0 },
                                    ) { change, _ ->
                                        // The end moves along its own edge of the page: top end on the top edge.
                                        val p = quad.points
                                        val (from, to) = if (dragging == 1) p[0] to p[1] else p[3] to p[2]
                                        val ex = (to.x - from.x) * size.width
                                        val ey = (to.y - from.y) * size.height
                                        val px = change.position.x - from.x * size.width
                                        val py = change.position.y - from.y * size.height
                                        val t = ((px * ex + py * ey) / (ex * ex + ey * ey).coerceAtLeast(1f)).coerceIn(.15f, .85f)
                                        if (dragging == 1) {
                                            top = t
                                            if (linked) bottom = t
                                        } else {
                                            bottom = t
                                            linked = false
                                        }
                                    }
                                }
                            ) {
                                fun at(n: PointN) = Offset(n.x * size.width, n.y * size.height)
                                // Shade what will be dropped.
                                val dropped =
                                    when (keep) {
                                        "left" -> listOf(split.second)
                                        "right" -> listOf(split.first)
                                        else -> emptyList()
                                    }
                                dropped.forEach { q ->
                                    val path = Path().apply {
                                        moveTo(at(q.points[0]).x, at(q.points[0]).y)
                                        q.points.drop(1).forEach { lineTo(at(it).x, at(it).y) }
                                        close()
                                    }
                                    drawPath(path, Color.Black.copy(alpha = .55f))
                                }
                                val outline = Path().apply {
                                    moveTo(at(quad.points[0]).x, at(quad.points[0]).y)
                                    quad.points.drop(1).forEach { lineTo(at(it).x, at(it).y) }
                                    close()
                                }
                                drawPath(outline, Color.White, style = Stroke(2.dp.toPx()))
                                val from = at(split.first.points[1])
                                val to = at(split.first.points[2])
                                drawLine(Color.White, from, to, 6.dp.toPx())
                                drawLine(color, from, to, 3.dp.toPx())
                                listOf(from, to).forEach {
                                    drawCircle(Color.White, 13.dp.toPx(), it)
                                    drawCircle(color, 10.dp.toPx(), it)
                                }
                            }
                        }
                    } ?: CircularProgressIndicator()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(linked, {
                        linked = it
                        if (it) bottom = top
                    })
                    Text("上下联动")
                }
                Text("书脊上端 ${(top * 100).toInt()}%")
                Slider(
                    top,
                    {
                        top = it
                        if (linked) bottom = it
                    },
                    valueRange = 0.15f..0.85f,
                )
                if (!linked) {
                    Text("书脊下端 ${(bottom * 100).toInt()}%")
                    Slider(bottom, { bottom = it }, valueRange = 0.15f..0.85f)
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("both" to "保留双页", "left" to "只留左页", "right" to "只留右页").forEach {
                        (value, label) ->
                        FilterChip(keep == value, { keep = value }, label = { Text(label) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = { onApply(top, bottom, keep) },
                        enabled = split.first.valid() && split.second.valid(),
                    ) {
                        Text("分割并保留原图")
                    }
                }
            }
        }
    }
}
