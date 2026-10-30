package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CropDialog(page: PageRow, onDismiss: () -> Unit, onSave: (Quad, Float) -> Unit) {
    val bitmap = pageBitmap(page, 1400, true)
    val pointSaver = remember {
        Saver<List<PointN>, String>(
            save = { Quad(it).encode() },
            restore = { Quad.decode(it).points },
        )
    }
    var points by
        rememberSaveable(page.id, stateSaver = pointSaver) {
            mutableStateOf(Quad.decode(page.corners).points)
        }
    var aspect by rememberSaveable(page.id) { mutableFloatStateOf(page.outputAspect) }
    val touchExploration = rememberTouchExploration()
    var precise by rememberSaveable(page.id) { mutableStateOf(touchExploration) }
    LaunchedEffect(touchExploration) { if (touchExploration) precise = true }
    var corner by rememberSaveable(page.id) { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    val outlineColor = MaterialTheme.colorScheme.primary
    var dragPoint by remember { mutableStateOf(Offset.Unspecified) }
    var detecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
            color = MaterialTheme.colorScheme.background,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val canvasHeight = (maxHeight - 270.dp).coerceIn(180.dp, 700.dp)
                Column(
                    Modifier.fillMaxSize().systemBarsPadding().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("沿纸边拖动四角或整边", style = MaterialTheme.typography.headlineSmall)
                        Text("拖动圆点或边线中点；放大镜辅助对齐。", style = MaterialTheme.typography.bodyMedium)
                        Box(
                            Modifier.height(canvasHeight).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            bitmap?.let { b ->
                                BoxWithConstraints(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val ratio = b.width.toFloat() / b.height
                                    val imageModifier =
                                        if (maxWidth / maxHeight > ratio)
                                            Modifier.fillMaxHeight().aspectRatio(ratio)
                                        else Modifier.fillMaxWidth().aspectRatio(ratio)
                                    Box(
                                        imageModifier.magnifier(
                                            sourceCenter = { dragPoint },
                                            zoom = 2f,
                                        )
                                    ) {
                                        Image(
                                            b.asImageBitmap(),
                                            "裁切原图",
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                        Canvas(
                                            Modifier.fillMaxSize()
                                                .testTag("crop-canvas")
                                                .semantics {
                                                    contentDescription = "裁切区域"
                                                    stateDescription = "已选择四角，可用位置调整代替拖动"
                                                    onClick("调整四角位置") {
                                                        precise = true
                                                        true
                                                    }
                                                    customActions =
                                                        listOf(
                                                            CustomAccessibilityAction("调整四角位置") {
                                                                precise = true
                                                                true
                                                            },
                                                            CustomAccessibilityAction("使用整张图片") {
                                                                points = Quad.Full.points
                                                                true
                                                            },
                                                        )
                                                }
                                                .pointerInput(b) {
                                                    awaitEachGesture {
                                                        val down =
                                                            awaitFirstDown(
                                                                requireUnconsumed = false
                                                            )
                                                        val handle =
                                                            CropGeometry.hit(
                                                                Quad(points),
                                                                size.width.toFloat(),
                                                                size.height.toFloat(),
                                                                down.position.x,
                                                                down.position.y,
                                                                36.dp.toPx(),
                                                                24.dp.toPx(),
                                                            )
                                                        var last = down.position
                                                        if (handle >= 0) {
                                                            down.consume()
                                                            dragPoint = down.position
                                                        }
                                                        try {
                                                            do {
                                                                val event = awaitPointerEvent()
                                                                val change =
                                                                    event.changes.firstOrNull {
                                                                        it.id == down.id
                                                                    } ?: break
                                                                if (handle >= 0) {
                                                                    change.consume()
                                                                    val amount =
                                                                        change.position - last
                                                                    if (handle >= 4)
                                                                        points =
                                                                            CropGeometry.moveEdge(
                                                                                    Quad(points),
                                                                                    handle - 4,
                                                                                    amount.x /
                                                                                        size.width,
                                                                                    amount.y /
                                                                                        size.height,
                                                                                )
                                                                                .points
                                                                    else {
                                                                        val candidate =
                                                                            points.toMutableList()
                                                                        candidate[handle] =
                                                                            PointN(
                                                                                (change.position.x /
                                                                                        size.width)
                                                                                    .coerceIn(
                                                                                        0f,
                                                                                        1f,
                                                                                    ),
                                                                                (change.position.y /
                                                                                        size.height)
                                                                                    .coerceIn(
                                                                                        0f,
                                                                                        1f,
                                                                                    ),
                                                                            )
                                                                        if (Quad(candidate).valid())
                                                                            points = candidate
                                                                    }
                                                                    dragPoint = change.position
                                                                }
                                                                last = change.position
                                                            } while (
                                                                event.changes.any { it.pressed }
                                                            )
                                                        } finally {
                                                            dragPoint = Offset.Unspecified
                                                        }
                                                    }
                                                }
                                        ) {
                                            val path =
                                                Path().apply {
                                                    points.forEachIndexed { i, p ->
                                                        if (i == 0)
                                                            moveTo(
                                                                p.x * size.width,
                                                                p.y * size.height,
                                                            )
                                                        else
                                                            lineTo(
                                                                p.x * size.width,
                                                                p.y * size.height,
                                                            )
                                                    }
                                                    close()
                                                }
                                            drawPath(path, outlineColor.copy(alpha = .15f))
                                            drawPath(path, Color.White, style = Stroke(5.dp.toPx()))
                                            drawPath(
                                                path,
                                                outlineColor,
                                                style = Stroke(2.5.dp.toPx()),
                                            )
                                            points.indices.forEach { i ->
                                                val a = points[i]
                                                val b = points[(i + 1) % 4]
                                                val middle =
                                                    Offset(
                                                        (a.x + b.x) * .5f * size.width,
                                                        (a.y + b.y) * .5f * size.height,
                                                    )
                                                drawCircle(Color.White, 9.dp.toPx(), middle)
                                                drawCircle(outlineColor, 5.dp.toPx(), middle)
                                            }
                                            points.forEach { p ->
                                                drawCircle(
                                                    Color.White,
                                                    10.dp.toPx(),
                                                    Offset(p.x * size.width, p.y * size.height),
                                                )
                                                drawCircle(
                                                    outlineColor,
                                                    5.dp.toPx(),
                                                    Offset(p.x * size.width, p.y * size.height),
                                                )
                                            }
                                        }
                                    }
                                }
                            } ?: CircularProgressIndicator()
                        }
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                    "原比例" to 0f,
                                    "A4/A5" to .7071068f,
                                    "方形" to 1f,
                                    "证件" to (85.6f / 54f),
                                )
                                .forEach { (label, ratio) ->
                                    FilterChip(
                                        aspect == ratio,
                                        { aspect = ratio },
                                        label = { Text(label) },
                                    )
                                }
                            TextButton(
                                onClick = {
                                    points = CropGeometry.expand(Quad(points), .025f).points
                                }
                            ) {
                                Text("外扩")
                            }
                            TextButton(
                                onClick = {
                                    points = CropGeometry.expand(Quad(points), -.025f).points
                                }
                            ) {
                                Text("内缩")
                            }
                            TextButton(
                                onClick = {
                                    points = Quad.decode(page.corners).points
                                    aspect = page.outputAspect
                                }
                            ) {
                                Text("重置")
                            }
                        }
                        TextButton(onClick = { precise = !precise }) {
                            Text(if (precise) "收起位置调整" else "按位置调整")
                        }
                        if (precise) {
                            val names = listOf("左上角", "右上角", "右下角", "左下角")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                names.forEachIndexed { index, label ->
                                    FilterChip(
                                        corner == index,
                                        { corner = index },
                                        label = { Text(label) },
                                        modifier = Modifier.testTag("crop-corner-$index"),
                                    )
                                }
                            }
                            fun move(x: Float, y: Float) {
                                val candidate =
                                    points.toMutableList().also { it[corner] = PointN(x, y) }
                                if (Quad(candidate).valid()) {
                                    points = candidate
                                    notice = ""
                                } else notice = "角点不能交叉，请先调整相邻角点"
                            }
                            val selected = points[corner]
                            Text(
                                "${names[corner]} · 水平 ${(selected.x*100).toInt()}%，垂直 ${(selected.y*100).toInt()}%",
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            Slider(
                                selected.x,
                                { move(it, selected.y) },
                                steps = 99,
                                modifier =
                                    Modifier.testTag("crop-x").semantics {
                                        contentDescription = "${names[corner]}水平位置"
                                    },
                            )
                            Slider(
                                selected.y,
                                { move(selected.x, it) },
                                steps = 99,
                                modifier =
                                    Modifier.testTag("crop-y").semantics {
                                        contentDescription = "${names[corner]}垂直位置"
                                    },
                            )
                            if (notice.isNotBlank())
                                Text(
                                    notice,
                                    modifier =
                                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    color = MaterialTheme.colorScheme.error,
                                )
                        }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(onClick = { points = Quad.Full.points }) { Text("全图") }
                        TextButton(
                            enabled = bitmap != null && !detecting,
                            onClick = {
                                detecting = true
                                scope.launchIoDetect(bitmap!!) { q ->
                                    q?.let { points = it.points }
                                    detecting = false
                                }
                            },
                        ) {
                            Text(if (detecting) "检测中…" else "重新检测")
                        }
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Button(
                            onClick = { onSave(Quad(points), aspect) },
                            enabled = Quad(points).valid(),
                        ) {
                            Text("应用")
                        }
                    }
                }
            }
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchIoDetect(
    bitmap: Bitmap,
    done: (Quad?) -> Unit,
) = launch {
    done(
        withContext(Dispatchers.Default) {
            runCatching { ScanProcessor.detect(bitmap) }.getOrNull()
        }
    )
}
