package cn.paperforge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cn.paperforge.app.data.PageRow
import kotlin.math.round
import kotlinx.coroutines.delay

/** Uses the same expanded-canvas geometry as export, so the preview never clips page content. */
@Composable
internal fun ScanRotationDialog(
    page: PageRow,
    onDismiss: () -> Unit,
    onSave: (PageRow) -> Unit,
    embedded: Boolean = false,
    onDraft: (PageRow) -> Unit = {},
) {
    var quarter by remember(page.id) { mutableIntStateOf(page.rotation) }
    var angle by remember(page.id) { mutableFloatStateOf(page.deskewDegrees) }
    var previewPage by remember(page.id) { mutableStateOf(page) }
    LaunchedEffect(quarter, angle) {
        if (embedded) onDraft(page.copy(rotation = quarter, deskewDegrees = angle))
        delay(120)
        previewPage = page.copy(rotation = quarter, deskewDegrees = angle)
    }
    val preview = pagePreview(previewPage, 1200)
    val pending =
        preview.loading || previewPage.rotation != quarter || previewPage.deskewDegrees != angle
    ScanToolWindow(embedded, onDismiss) {
        Surface(
            Modifier.fillMaxSize().then(if (embedded) Modifier else Modifier.systemBarsPadding()),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!embedded)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "旋转与校正",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        TextButton(onClick = onDismiss) { Text("取消") }
                        TextButton(
                            onClick = {
                                onSave(page.copy(rotation = quarter, deskewDegrees = angle))
                            }
                        ) {
                            Text("保存")
                        }
                    }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    preview.bitmap?.let {
                        Image(
                            it.asImageBitmap(),
                            if (pending) "正在旋转预览" else "旋转预览 · $quarter° · $angle°",
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Canvas(Modifier.fillMaxSize()) {
                        for (i in 1..5) {
                            drawLine(
                                Color.Gray.copy(alpha = .3f),
                                Offset(size.width * i / 6, 0f),
                                Offset(size.width * i / 6, size.height),
                            )
                            drawLine(
                                Color.Gray.copy(alpha = .3f),
                                Offset(0f, size.height * i / 6),
                                Offset(size.width, size.height * i / 6),
                            )
                        }
                    }
                    if (pending) CircularProgressIndicator()
                    preview.error?.let { Text(it) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    OutlinedButton(onClick = { quarter = (quarter + 270) % 360 }) { Text("左转 90°") }
                    TextButton(
                        onClick = {
                            quarter = 0
                            angle = 0f
                        }
                    ) {
                        Text("复位")
                    }
                    OutlinedButton(onClick = { quarter = (quarter + 90) % 360 }) { Text("右转 90°") }
                }
                Text(
                    "微调角度  ${String.format(java.util.Locale.ROOT, "%+.1f°", angle)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = angle.coerceIn(-45f, 45f),
                    onValueChange = { angle = round(it * 10) / 10 },
                    valueRange = -45f..45f,
                )
                Text("对齐参考网格，页面内容完整保留", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
