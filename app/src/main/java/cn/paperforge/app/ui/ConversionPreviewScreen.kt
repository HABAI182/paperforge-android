package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import kotlin.math.max
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConversionPreviewScreen(vm: MainViewModel) {
    val id by vm.conversionPreviewTask.collectAsState()
    val tasks by vm.localTasks.collectAsState()
    val row = tasks.firstOrNull { it.id == id }
    var preview by remember(id) { mutableStateOf<ConversionPreview?>(null) }
    var message by remember(id) { mutableStateOf("") }
    var leftPage by rememberSaveable(id) { mutableIntStateOf(0) }
    var rightPage by rememberSaveable(id) { mutableIntStateOf(0) }
    var linked by rememberSaveable(id) { mutableStateOf(true) }
    var right by rememberSaveable(id) { mutableStateOf(false) }
    var layout by rememberSaveable { mutableStateOf("auto") }
    var zoom by rememberSaveable(id) { mutableFloatStateOf(1f) }
    var panX by rememberSaveable(id) { mutableFloatStateOf(0f) }
    var panY by rememberSaveable(id) { mutableFloatStateOf(0f) }
    var jump by remember { mutableStateOf(false) }
    LaunchedEffect(id, row?.updated) {
        if (row?.state == LocalTaskState.SUCCEEDED) {
            try {
                preview = withContext(Dispatchers.IO) { ConversionPreviews.load(row) }
                message = ""
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                message = failure.message ?: "预览无法读取"
            }
        } else {
            preview = null
            message = row?.error.orEmpty()
        }
    }
    val active = row?.state in LocalTaskRow.active
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("原件 / 结果对照") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = vm.conversionPreviewReturn.value }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (row != null)
                        IconButton(onClick = { vm.retryTask(row) }, enabled = !active) {
                            Icon(Icons.Default.Refresh, "重新生成预览")
                        }
                },
            )
        }
    ) { padding ->
        val value = preview
        if (value == null || active) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "分别读取原件和实际转换结果，Office 文件由手机引擎生成排版预览。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (active) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(row?.detail.orEmpty())
                    TextButton(onClick = { row?.let(vm::cancelLocalTask) }) { Text("停止生成") }
                } else {
                    Text(message.ifBlank { row?.error ?: "请从转换任务打开对照" })
                    if (row != null) Button(onClick = { vm.retryTask(row) }) { Text("重试预览") }
                }
                Text("可以先返回，任务会保留在任务中心。", style = MaterialTheme.typography.bodySmall)
            }
        } else
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val dual = layout == "dual" || layout == "auto" && maxWidth >= 600.dp
                val compact = maxHeight < 420.dp
                val captionLines = if (compact || !dual) 1 else 2
                val currentPage = if (right) rightPage else leftPage
                val lastPage =
                    if (linked) max(value.left.pages, value.right.pages) - 1
                    else (if (right) value.right.pages else value.left.pages) - 1
                val pageLabel =
                    "${if(leftPage < value.left.pages) (leftPage+1).toString() else "—"}/${value.left.pages}  ↔  ${if(rightPage < value.right.pages) (rightPage+1).toString() else "—"}/${value.right.pages}"
                fun step(delta: Int) {
                    if (linked) {
                        val p =
                            ((if (right) rightPage else leftPage) + delta).coerceIn(
                                0,
                                max(value.left.pages, value.right.pages) - 1,
                            )
                        leftPage = p
                        rightPage = p
                    } else if (right)
                        rightPage = (rightPage + delta).coerceIn(0, value.right.pages - 1)
                    else leftPage = (leftPage + delta).coerceIn(0, value.left.pages - 1)
                }
                Column(Modifier.fillMaxSize()) {
                    if (!compact)
                        Text(
                            value.operation,
                            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                    FlowRow(
                        Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(!right, { right = false }, label = { Text("原件") })
                        FilterChip(right, { right = true }, label = { Text("结果") })
                        FilterChip(
                            dual,
                            { layout = if (dual) "single" else "dual" },
                            label = { Text(if (dual) "切换大图" else "并排") },
                        )
                        TextButton(
                            onClick = {
                                zoom = 1f
                                panX = 0f
                                panY = 0f
                            }
                        ) {
                            Text("重置缩放")
                        }
                        if (compact) {
                            FilterChip(
                                linked,
                                {
                                    linked = !linked
                                    if (linked) rightPage = leftPage
                                },
                                label = { Text("同步") },
                            )
                            TextButton(onClick = { step(-1) }, enabled = currentPage > 0) {
                                Text("上一页")
                            }
                            TextButton(onClick = { jump = true }) { Text(pageLabel) }
                            TextButton(onClick = { step(1) }, enabled = currentPage < lastPage) {
                                Text("下一页")
                            }
                        }
                    }
                    if (!compact) {
                        Row(
                            Modifier.padding(horizontal = 12.dp)
                                .toggleable(
                                    linked,
                                    role = Role.Checkbox,
                                    onValueChange = {
                                        linked = it
                                        if (it) rightPage = leftPage
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(linked, null)
                            Text("同步翻页", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(12.dp))
                            Text("缩放与拖动同步", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { step(-1) }, enabled = currentPage > 0) {
                                Text("上一页")
                            }
                            TextButton(onClick = { jump = true }) { Text(pageLabel) }
                            TextButton(onClick = { step(1) }, enabled = currentPage < lastPage) {
                                Text("下一页")
                            }
                        }
                    }
                    val pan = Offset(panX, panY)
                    val gesture: (Float, Offset) -> Unit = { scale, drag ->
                        zoom = (zoom * scale).coerceIn(1f, 6f)
                        panX = if (zoom <= 1f) 0f else (panX + drag.x).coerceIn(-1f, 1f)
                        panY = if (zoom <= 1f) 0f else (panY + drag.y).coerceIn(-1f, 1f)
                    }
                    if (dual)
                        Row(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            ConversionPreviewPane(
                                value.left,
                                leftPage,
                                "原件",
                                zoom,
                                pan,
                                gesture,
                                captionLines,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                            ConversionPreviewPane(
                                value.right,
                                rightPage,
                                "结果",
                                zoom,
                                pan,
                                gesture,
                                captionLines,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    else
                        ConversionPreviewPane(
                            if (right) value.right else value.left,
                            if (right) rightPage else leftPage,
                            if (right) "结果" else "原件",
                            zoom,
                            pan,
                            gesture,
                            captionLines,
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                }
                if (jump) {
                    var left by remember {
                        mutableStateOf((leftPage + 1).coerceAtMost(value.left.pages).toString())
                    }
                    var result by remember {
                        mutableStateOf((rightPage + 1).coerceAtMost(value.right.pages).toString())
                    }
                    val a = left.toIntOrNull()
                    val b = result.toIntOrNull()
                    AlertDialog(
                        onDismissRequest = { jump = false },
                        title = { Text("分别定位页面") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    left,
                                    { left = it },
                                    label = { Text("原件页（1–${value.left.pages}）") },
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    result,
                                    { result = it },
                                    label = { Text("结果页（1–${value.right.pages}）") },
                                    singleLine = true,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled =
                                    a != null &&
                                        a in 1..value.left.pages &&
                                        b != null &&
                                        b in 1..value.right.pages,
                                onClick = {
                                    leftPage = a!! - 1
                                    rightPage = b!! - 1
                                    linked = a == b
                                    jump = false
                                },
                            ) {
                                Text("定位")
                            }
                        },
                        dismissButton = { TextButton(onClick = { jump = false }) { Text("取消") } },
                    )
                }
            }
    }
}

@Composable
private fun ConversionPreviewPane(
    side: ConversionPreviewSide,
    page: Int,
    label: String,
    zoom: Float,
    pan: Offset,
    gesture: (Float, Offset) -> Unit,
    captionLines: Int,
    modifier: Modifier,
) {
    var bitmap by remember(side.pdf, page) { mutableStateOf<Bitmap?>(null) }
    var failure by remember(side.pdf, page) { mutableStateOf("") }
    LaunchedEffect(side.pdf, page) {
        if (page >= side.pages) return@LaunchedEffect
        var pending: Bitmap? = null
        try {
            val image =
                withContext(Dispatchers.IO) {
                    PdfSession(side.pdf).use { session ->
                        session.render(page, 1600).copy(Bitmap.Config.ARGB_8888, false).also {
                            pending = it
                        }
                    }
                }
            bitmap = image
            pending = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            failure = error.message ?: "页面无法读取"
        } finally {
            pending?.recycle()
        }
    }
    val rendered = bitmap
    DisposableEffect(rendered) { onDispose { rendered?.recycle() } }
    Column(modifier) {
        val captionHeight = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp() } * captionLines + 8.dp
        Text(
            "$label · ${side.original.extension.uppercase()} · ${if(side.original.extension.equals("pdf",true)) "PDF 直接预览" else side.representation}",
            Modifier.height(captionHeight).padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            minLines = captionLines,
            maxLines = captionLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Box(
            Modifier.weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(side.pdf) {
                    detectTransformGestures { _, drag, scale, _ ->
                        gesture(scale, Offset(drag.x / size.width, drag.y / size.height))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (rendered != null)
                Image(
                    rendered.asImageBitmap(),
                    "$label 第 ${page+1} 页",
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x * size.width
                        translationY = pan.y * size.height
                    },
                    contentScale = ContentScale.Fit,
                )
            else Text(if (page >= side.pages) "${label}没有这一页" else failure.ifBlank { "载入页面…" })
        }
    }
}
