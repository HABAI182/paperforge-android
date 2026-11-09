package cn.paperforge.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.pdf.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentWatermarkScreen(vm: MainViewModel) {
    var uri by remember { mutableStateOf(vm.watermarkInput.value) }
    var source by remember { mutableStateOf<File?>(null) }
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("AUTO") }
    var text by remember { mutableStateOf("") }
    var background by remember { mutableStateOf(true) }
    var allPages by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(1) }
    var region by remember { mutableStateOf<JSONObject?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var processed by remember { mutableStateOf<Bitmap?>(null) }
    var afterView by remember { mutableStateOf(false) }
    var previewBusy by remember { mutableStateOf(false) }
    var previewNote by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsState()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val picker = rememberPaperDocumentPicker { uri = it }
    LaunchedEffect(uri) {
        val input = uri ?: return@LaunchedEffect
        loading = true
        error = null
        source = null
        preview = null
        region = null
        page = 0
        pageCount = 1
        try {
            val retained =
                withContext(Dispatchers.IO) {
                    ConversionInputs.retain(vm.repo, listOf(input), temporary = true).single()
                }
            source = retained.first
            name = retained.second
            val ext = name.substringAfterLast('.').lowercase()
            if (
                ext !in DocumentWatermarks.packages &&
                    ext !in DocumentWatermarks.legacy &&
                    ext != "pdf"
            )
                mode = "REGION"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }
    DisposableEffect(source) {
        val file = source
        onDispose { file?.delete() }
    }
    LaunchedEffect(source, page) {
        val file = source ?: return@LaunchedEffect
        loading = true
        error = null
        preview = null
        var generated: Bitmap? = null
        try {
            withContext(Dispatchers.IO) {
                val original = WatermarkPreviews.original(vm.repo.context, file, page)
                generated = original.bitmap
                pageCount = original.pages
            }
            preview = generated
            generated = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "预览无法打开"
        } finally {
            generated?.recycle()
            loading = false
        }
    }
    val bitmap = preview
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    val previewOptions =
        JSONObject()
            .put("mode", mode)
            .put("text", text.trim())
            .put("background", background)
            .apply { region?.let { put("region", it) } }
            .toString()
    LaunchedEffect(source, bitmap, page, previewOptions) {
        val file = source ?: return@LaunchedEffect
        val before = bitmap ?: return@LaunchedEffect
        processed = null
        previewNote = ""
        previewBusy = true
        var copy: Bitmap? = null
        var generated: Bitmap? = null
        try {
            delay(300)
            copy = before.copy(Bitmap.Config.ARGB_8888, false)
            withContext(Dispatchers.IO) {
                val result =
                    WatermarkPreviews.after(
                        vm.repo.context,
                        file,
                        page,
                        JSONObject(previewOptions),
                        copy!!,
                    )
                generated = result.bitmap
                previewNote = result.detail
            }
            processed = generated
            generated = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            previewNote = e.message ?: "效果预览未能生成"
        } finally {
            generated?.recycle()
            copy?.recycle()
            previewBusy = false
        }
    }
    val afterBitmap = processed
    DisposableEffect(afterBitmap) { onDispose { afterBitmap?.recycle() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文档去水印") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = vm.watermarkReturn.value }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (name.isBlank()) "选择需要处理的文档" else name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "PDF、Word、Excel、PowerPoint、ODF 与图片；原件保留，处理结果另存",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !busy,
                    ) {
                        Text(if (source == null) "从文档库或手机导入" else "更换文档")
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("AUTO" to "自动对象", "TEXT" to "指定文字", "REGION" to "框选修复").forEach {
                    (value, label) ->
                    FilterChip(mode == value, { mode = value }, label = { Text(label) })
                }
            }
            when (mode) {
                "AUTO" -> {
                    Text("清理可识别的 PDF 角落标记、Office 水印绘图及页面背景。若未发现对象，会明确提示，可改用文字匹配或框选。")
                    Row {
                        Checkbox(background, { background = it })
                        Text("同时清理 Office 页面或表格背景")
                    }
                }
                "TEXT" -> {
                    OutlinedTextField(
                        text,
                        { text = it },
                        label = { Text("要移除的完整水印文字") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("PDF 支持页面与嵌套对象中的文字；Office 只处理绘图/艺术字对象，保留正文段落。")
                }
                else -> {
                    Text("在预览上拖动框选水印。适合空白处、页边及照片中的标记；与正文重叠的原文字无法凭空还原。")
                    if (
                        source?.extension?.lowercase() in
                            DocumentWatermarks.packages + DocumentWatermarks.legacy
                    )
                        Text(
                            "Office 的框选修复请先转成 PDF；原格式可选自动对象或指定文字。",
                            color = MaterialTheme.colorScheme.error,
                        )

                    Text(
                        "图片输出 PNG；PDF 框选修复输出页图 PDF，文字层与表单不保留。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (bitmap != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        !afterView,
                        {
                            focus.clearFocus()
                            keyboard?.hide()
                            afterView = false
                        },
                        label = { Text("原图") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        afterView,
                        {
                            focus.clearFocus()
                            keyboard?.hide()
                            afterView = true
                        },
                        label = { Text("处理后") },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!afterView && mode == "REGION")
                    WatermarkRegionCanvas(bitmap, region, { region = it })
                else
                    WatermarkPreviewImage(
                        if (afterView) processed else bitmap,
                        if (afterView) "处理后效果" else "原图效果",
                    )
                if (pageCount > 1)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        TextButton(
                            {
                                page--
                                region = null
                            },
                            enabled = page > 0,
                        ) {
                            Text("上一页")
                        }
                        Text("${page+1} / $pageCount")
                        TextButton(
                            {
                                page++
                                region = null
                            },
                            enabled = page + 1 < pageCount,
                        ) {
                            Text("下一页")
                        }
                    }
                if (mode == "REGION" && pageCount > 1)
                    Row {
                        Checkbox(allPages, { allPages = it })
                        Text("修复所有页面同一位置")
                    }
                if (previewBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (previewNote.isNotBlank())
                    Text(previewNote, style = MaterialTheme.typography.bodySmall)
                Text("双指放大检查，预览按屏幕缩放", style = MaterialTheme.typography.bodySmall)
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    val options =
                        JSONObject()
                            .put("mode", mode)
                            .put("text", text.trim())
                            .put("background", background)
                            .put("page", page)
                            .put("allPages", allPages)
                    region?.let { options.put("region", it) }
                    vm.cleanDocument(Uri.fromFile(source), options, name)
                },
                enabled =
                    source != null &&
                        !busy &&
                        !loading &&
                        (mode != "TEXT" || text.isNotBlank()) &&
                        (mode != "REGION" || region != null && bitmap != null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("生成去水印副本")
            }
        }
    }
}

@Composable
private fun WatermarkRegionCanvas(
    bitmap: Bitmap,
    region: JSONObject?,
    onRegion: (JSONObject) -> Unit,
) {
    var width by remember { mutableIntStateOf(1) }
    var height by remember { mutableIntStateOf(1) }
    var start by remember { mutableStateOf<Offset?>(null) }
    val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
    val w = bitmap.width * scale
    val h = bitmap.height * scale
    val left = (width - w) / 2
    val top = (height - h) / 2
    fun point(p: Offset) =
        Offset(((p.x - left) / w).coerceIn(0f, 1f), ((p.y - top) / h).coerceIn(0f, 1f))
    Canvas(
        Modifier.fillMaxWidth()
            .height(340.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .onSizeChanged {
                width = it.width
                height = it.height
            }
            .pointerInput(bitmap, width, height) {
                detectDragGestures(
                    onDragStart = { start = point(it) },
                    onDrag = { change, _ ->
                        change.consume()
                        val a = start ?: return@detectDragGestures
                        val b = point(change.position)
                        onRegion(
                            JSONObject()
                                .put("left", minOf(a.x, b.x))
                                .put("top", minOf(a.y, b.y))
                                .put("right", maxOf(a.x, b.x))
                                .put("bottom", maxOf(a.y, b.y))
                        )
                    },
                )
            }
    ) {
        drawImage(
            bitmap.asImageBitmap(),
            dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
            dstSize =
                androidx.compose.ui.unit.IntSize(
                    w.toInt().coerceAtLeast(1),
                    h.toInt().coerceAtLeast(1),
                ),
        )
        region?.let { r ->
            val p =
                Offset(
                    left + r.getDouble("left").toFloat() * w,
                    top + r.getDouble("top").toFloat() * h,
                )
            val size =
                androidx.compose.ui.geometry.Size(
                    (r.getDouble("right") - r.getDouble("left")).toFloat() * w,
                    (r.getDouble("bottom") - r.getDouble("top")).toFloat() * h,
                )
            drawRect(Color(0x4431A382), p, size)
            drawRect(Color(0xFF168269), p, size, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun WatermarkPreviewImage(bitmap: Bitmap?, label: String) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxWidth()
            .height(360.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clipToBounds()
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, scale, _ ->
                    zoom = (zoom * scale).coerceIn(1f, 5f)
                    offset =
                        Offset(
                            (offset.x + pan.x).coerceIn(
                                -size.width * (zoom - 1) / 2,
                                size.width * (zoom - 1) / 2,
                            ),
                            (offset.y + pan.y).coerceIn(
                                -size.height * (zoom - 1) / 2,
                                size.height * (zoom - 1) / 2,
                            ),
                        )
                }
            },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        if (bitmap == null) CircularProgressIndicator()
        else
            Image(
                bitmap.asImageBitmap(),
                label,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                },
                contentScale = ContentScale.Fit,
            )
    }
}
