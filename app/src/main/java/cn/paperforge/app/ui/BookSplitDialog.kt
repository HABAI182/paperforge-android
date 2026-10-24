package cn.paperforge.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.*

@Composable
fun BookSplitDialog(page: PageRow, onDismiss: () -> Unit, onApply: (Float, Float, String) -> Unit) {
    val bitmap = pageBitmap(page, 1400, true)
    var top by remember { mutableFloatStateOf(.5f) }
    var bottom by remember { mutableFloatStateOf(.5f) }
    var linked by remember { mutableStateOf(true) }
    var keep by remember { mutableStateOf("both") }
    val color = MaterialTheme.colorScheme.primary
    val split = CropGeometry.split(Quad.decode(page.corners), top, bottom)
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.systemBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("调整书脊", style = MaterialTheme.typography.headlineSmall)
                Text("移动上下落点，分开左右页；不必从正中硬切。", style = MaterialTheme.typography.bodyMedium)
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
                            Canvas(Modifier.fillMaxSize()) {
                                val a = split.first.points[1]
                                val z = split.first.points[2]
                                val from = Offset(a.x * size.width, a.y * size.height)
                                val to = Offset(z.x * size.width, z.y * size.height)
                                drawLine(
                                    androidx.compose.ui.graphics.Color.White,
                                    from,
                                    to,
                                    6.dp.toPx(),
                                )
                                drawLine(color, from, to, 3.dp.toPx())
                                listOf(from, to).forEach { drawCircle(color, 8.dp.toPx(), it) }
                            }
                        }
                    } ?: CircularProgressIndicator()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(linked, { linked = it })
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
