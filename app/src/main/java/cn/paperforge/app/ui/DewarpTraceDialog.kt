package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.*
import kotlinx.coroutines.*

@Composable
fun DewarpTraceDialog(page: PageRow, onDismiss: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    val trace by
        produceState<DewarpTrace?>(null, page) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { DewarpTrace.load(page) }
                        .onFailure { error = it.message }
                        .getOrNull()
                }
        }
    val images by
        produceState<Pair<Bitmap, Bitmap>?>(null, page, trace) {
            var owned: Pair<Bitmap, Bitmap>? = null
            try {
                val t = trace ?: return@produceState
                withContext(Dispatchers.IO) {
                    var input: Bitmap? = null
                    try {
                        input = ScanProcessor.decode(t.input, 1200)
                        owned = input to ScanProcessor.render(page, 1200)
                    } catch (e: Exception) {
                        input?.recycle()
                        error = e.message
                    }
                }
                value = owned
                awaitCancellation()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    owned?.let {
                        it.first.recycle()
                        it.second.recycle()
                    }
                }
            }
        }
    val lines =
        remember(page.ocrLayout) { page.ocrLayout?.let { OcrLayout.decode(it).lines }.orEmpty() }
    var line by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableStateOf<PointN?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("曲面来源与网格") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("点击结果，对照校正前位置；识别框沿保存的非线性网格映射。", style = MaterialTheme.typography.bodySmall)
                val pair = images
                val t = trace
                if (pair != null && t != null) {
                    fun map(p: PointN) =
                        t.inputPoint(page, p, pair.second.width, pair.second.height)
                    val quad = lines.getOrNull(line)?.corners.orEmpty()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("校正前")
                            TraceImage(pair.first, quad.map(::map), cursor?.let(::map))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("当前结果")
                            TraceImage(pair.second, quad, cursor) { cursor = it }
                        }
                    }
                    if (lines.isNotEmpty()) {
                        Text(lines[line].text)
                        Row {
                            TextButton(
                                onClick = {
                                    line = (line - 1 + lines.size) % lines.size
                                    cursor = null
                                }
                            ) {
                                Text("上一处识别")
                            }
                            TextButton(
                                onClick = {
                                    line = (line + 1) % lines.size
                                    cursor = null
                                }
                            ) {
                                Text("下一处识别")
                            }
                        }
                    } else Text("识别后，这里还会显示文字框。")
                    Text(
                        "${t.version} · 网格 ${t.grid.side} × ${t.grid.side} · 强度 ${(t.grid.strength*100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else Text(error ?: if (trace == null) "本页还没有保存的曲面网格" else "正在加载对照…")
                Text(
                    "原件、校正前像素、网格和来源版本都随备份保存。继续裁切或旋转后仍可对照；可用撤销恢复。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun TraceImage(
    bitmap: Bitmap,
    quad: List<PointN>,
    cursor: PointN?,
    onTap: (PointN) -> Unit = {},
) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height).pointerInput(
            bitmap
        ) {
            detectTapGestures { onTap(PointN(it.x / size.width, it.y / size.height)) }
        }
    ) {
        Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            if (quad.isNotEmpty()) {
                val path =
                    Path().apply {
                        quad.forEachIndexed { i, p ->
                            if (i == 0) moveTo(p.x * size.width, p.y * size.height)
                            else lineTo(p.x * size.width, p.y * size.height)
                        }
                        close()
                    }
                drawPath(path, Color(0xFFEF5350), style = Stroke(2.dp.toPx()))
            }
            cursor?.let { p ->
                val center = Offset(p.x * size.width, p.y * size.height)
                drawCircle(Color(0xFF00B8D4), 8.dp.toPx(), center, style = Stroke(2.dp.toPx()))
            }
        }
    }
}
