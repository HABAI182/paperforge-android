package cn.paperforge.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.*
import kotlin.math.*
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanRetouchScreen(
    repo: Repository,
    page: PageRow,
    onBack: () -> Unit,
    onSubmit: (RetouchRequest) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember(page.id) { mutableStateOf<Bitmap?>(null) }
    var preview by remember(page.id) { mutableStateOf<Bitmap?>(null) }
    var request by remember(page.id) { mutableStateOf(RetouchRequest()) }
    var sourceKey by remember(page.id) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var processing by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var brush by remember { mutableStateOf(true) }
    var erase by remember { mutableStateOf(false) }
    var radius by remember { mutableFloatStateOf(.025f) }
    var modelInfo by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(RetouchModels.installed(context)) }
    var dirty by remember { mutableStateOf(false) }
    val modeList = rememberLazyListState()
    LaunchedEffect(request.mode) {
        modeList.animateScrollToItem(ScanRetouch.modes.keys.indexOf(request.mode).coerceAtLeast(0))
    }
    LaunchedEffect(page.id) {
        try {
            val loaded =
                withContext(Dispatchers.IO) {
                    val key = RetouchDrafts.sourceKey(page)
                    Triple(ScanProcessor.render(page, 1400), key, RetouchDrafts.load(page, key))
                }
            source = loaded.first
            sourceKey = loaded.second
            loaded.third?.let { request = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "无法打开修复页面"
        }
    }
    val currentSource = source
    val currentPreview = preview
    DisposableEffect(currentSource) { onDispose { currentSource?.recycle() } }
    DisposableEffect(currentPreview) { onDispose { currentPreview?.recycle() } }
    val requestText = request.json().toString()
    LaunchedEffect(requestText, sourceKey) {
        if (dirty && sourceKey != null) {
            try {
                withContext(Dispatchers.IO + NonCancellable) {
                    RetouchDrafts.save(repo, page, sourceKey!!, request)
                }
            } catch (e: Exception) {
                error = e.message ?: "草稿未能保存"
            }
        }
    }
    fun change(value: RetouchRequest) {
        request = value
        dirty = true
        preview = null
        error = null
        showOriginal = false
    }
    fun leave(submit: Boolean) {
        if (processing) return
        processing = true
        scope.launch {
            try {
                sourceKey?.let { key ->
                    withContext(Dispatchers.IO) { RetouchDrafts.save(repo, page, key, request) }
                }
                if (submit) onSubmit(request) else onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "草稿未能保存"
            } finally {
                processing = false
            }
        }
    }
    BackHandler { leave(false) }
    val importModel =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                processing = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { RetouchModels.install(context, uri) }
                        installed = true
                        error = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "模型导入失败"
                    } finally {
                        processing = false
                    }
                }
            }
        }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("清理与修复") },
                navigationIcon = {
                    IconButton(onClick = { leave(false) }, enabled = !processing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "保存草稿并返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showOriginal = !showOriginal },
                        enabled = preview != null,
                    ) {
                        Text(if (showOriginal) "看结果" else "看原图")
                    }
                    IconButton(
                        onClick = {
                            if (controls) brush = false
                            controls = !controls
                        }
                    ) {
                        Icon(
                            if (controls) Icons.Default.Fullscreen
                            else Icons.Default.FullscreenExit,
                            if (controls) "全屏检查" else "显示修复工具",
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (controls)
                Surface(tonalElevation = 3.dp) {
                    Column(
                        Modifier.navigationBarsPadding()
                            .heightIn(max = 348.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        LazyRow(
                            state = modeList,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ScanRetouch.modes.forEach { (mode, name) ->
                                item {
                                    FilterChip(
                                        request.mode == mode,
                                        {
                                            change(
                                                RetouchRequest(
                                                    mode,
                                                    request.strength,
                                                    request.strokes,
                                                )
                                            )
                                        },
                                        enabled = !processing,
                                        label = { Text(name) },
                                    )
                                }
                            }
                        }
                        Text(
                            when (request.mode) {
                                "MIGAN" -> "涂抹手指或遮挡处。模型填补周围纹理，不能还原被遮住的原文字。"
                                "REPAIR" -> "涂抹小污点或边缘杂物，使用周围像素修补。"
                                "INK" -> "涂抹彩色批注，只修补明显彩色笔迹；黑色手写请用局部修补。"
                                "GLARE" -> "压制仍有纹理的高光、改善局部对比。纯白过曝区域无法还原。"
                                "SR" -> "实验性 3× 亮度超分，最长边 4096。请放大核对细字；未涂抹时处理整页。"
                                else -> "去除彩色细噪点。可只处理涂抹区域，未涂抹时处理整页。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (request.mode == "MIGAN")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (installed) "MI-GAN 已就绪" else "需导入 28 MB 模型",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                TextButton(onClick = { modelInfo = true }) { Text("模型说明") }
                                TextButton(
                                    onClick = { importModel.launch(arrayOf("*/*")) },
                                    enabled = !processing,
                                ) {
                                    Text("导入")
                                }
                            }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                brush,
                                { brush = !brush },
                                enabled = !processing,
                                label = { Text(if (brush) "涂抹选区" else "浏览缩放") },
                            )
                            FilterChip(
                                erase,
                                {
                                    erase = !erase
                                    brush = true
                                },
                                enabled = !processing,
                                label = { Text("擦除选区") },
                            )
                            IconButton(
                                onClick = {
                                    change(request.copy(strokes = request.strokes.dropLast(1)))
                                },
                                enabled = !processing && request.strokes.isNotEmpty(),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Undo, "撤销涂抹")
                            }
                            TextButton(
                                onClick = { change(request.copy(strokes = emptyList())) },
                                enabled = !processing && request.strokes.isNotEmpty(),
                            ) {
                                Text("清空")
                            }
                        }
                        if (brush)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("笔刷", style = MaterialTheme.typography.labelMedium)
                                Slider(
                                    radius,
                                    { radius = it },
                                    Modifier.weight(1f),
                                    valueRange = 0.005f..0.12f,
                                    enabled = !processing,
                                )
                            }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "强度 ${(request.strength * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Slider(
                                request.strength,
                                { change(request.copy(strength = it)) },
                                Modifier.weight(1f),
                                valueRange = 0f..1f,
                                enabled = !processing,
                            )
                        }
                        if (error != null)
                            Text(
                                error!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val original = source ?: return@OutlinedButton
                                    processing = true
                                    error = null
                                    scope.launch {
                                        var result: Bitmap? = null
                                        try {
                                            result =
                                                withContext(Dispatchers.Default) {
                                                    val owned =
                                                        original.copy(
                                                            Bitmap.Config.ARGB_8888,
                                                            false,
                                                        )
                                                    try {
                                                        ScanRetouch.apply(context, owned, request)
                                                    } finally {
                                                        owned.recycle()
                                                    }
                                                }
                                            ensureActive()
                                            preview = result
                                            result = null
                                            showOriginal = false
                                            brush = false
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            error = e.message ?: "处理未完成"
                                        } finally {
                                            result?.recycle()
                                            processing = false
                                        }
                                    }
                                },
                                enabled =
                                    source != null &&
                                        !processing &&
                                        (request.mode != "MIGAN" || installed),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (processing) "处理中" else "生成预览")
                            }
                            Button(
                                onClick = { leave(true) },
                                enabled = preview != null && !processing,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("另存副本")
                            }
                        }
                    }
                }
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).background(Color(0xff182522)),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = if (showOriginal) source else preview ?: source
            if (bitmap != null)
                RetouchCanvas(
                    bitmap,
                    request.strokes,
                    brush && !processing && preview == null,
                    erase,
                    radius,
                ) { stroke ->
                    change(request.copy(strokes = request.strokes + stroke))
                }
            else if (error != null)
                Text(error!!, color = Color.White, modifier = Modifier.padding(24.dp))
            else CircularProgressIndicator()
            if (processing)
                LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
            if (!brush || preview != null)
                Text(
                    if (showOriginal) "原图 · 双指缩放"
                    else if (preview != null) "处理预览 · 双指缩放" else "浏览 · 双指缩放",
                    Modifier.align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = .65f))
                        .padding(8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
        }
    }
    if (modelInfo)
        AlertDialog(
            onDismissRequest = { modelInfo = false },
            title = { Text("本地修复模型") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "MI-GAN · Picsart AI Research · MIT\n版本 406830d0 / pipeline v2\n28,079,181 字节\n\n模型由你导入，在手机离线运行。删除模型不影响原图、草稿和已生成的副本。手写和遮挡下的真实内容需要原件核对。"
                    )
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(RetouchModels.MIGAN_URL))
                            )
                        }
                    ) {
                        Text("打开指定模型下载页")
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/Picsart-AI-Research/MI-GAN"),
                                )
                            )
                        }
                    ) {
                        Text("项目与许可")
                    }
                    if (installed)
                        TextButton(
                            onClick = {
                                RetouchModels.remove(context)
                                installed = false
                            },
                            enabled = !processing,
                        ) {
                            Text("移除修复模型")
                        }
                }
            },
            confirmButton = { TextButton(onClick = { modelInfo = false }) { Text("完成") } },
        )
}

@Composable
private fun RetouchCanvas(
    bitmap: Bitmap,
    strokes: List<RetouchStroke>,
    brush: Boolean,
    erase: Boolean,
    radius: Float,
    onStroke: (RetouchStroke) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var points by remember { mutableStateOf<List<RetouchPoint>>(emptyList()) }
    val aspect = bitmap.width.toFloat() / bitmap.height
    val base = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
    val width = bitmap.width * base * zoom
    val height = bitmap.height * base * zoom
    val left = (size.width - width) / 2 + pan.x
    val top = (size.height - height) / 2 + pan.y
    val all =
        strokes +
            if (points.isNotEmpty()) listOf(RetouchStroke(points, radius, erase)) else emptyList()
    val overlay =
        remember(all, aspect) {
            val mw = max(1, min(420f, 420 * aspect).roundToInt())
            val mh = max(1, min(420f, 420 / aspect).roundToInt())
            ScanRetouch.mask(mw, mh, all).also { mask ->
                val pixels = IntArray(mask.width * mask.height)
                mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
                for (i in pixels.indices) pixels[i] =
                    ((pixels[i] and 255) * .4f).roundToInt().shl(24) or 0x00d9c3
                mask.setPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
            }
        }
    DisposableEffect(overlay) { onDispose { overlay.recycle() } }
    fun point(p: Offset) =
        RetouchPoint(
            ((p.x - left) / width).coerceIn(0f, 1f),
            ((p.y - top) / height).coerceIn(0f, 1f),
        )
    val mapPoint by rememberUpdatedState<(Offset) -> RetouchPoint>({ p -> point(p) })
    val appendStroke by rememberUpdatedState(onStroke)
    val rect by rememberUpdatedState(android.graphics.RectF(left, top, left + width, top + height))
    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .semantics { contentDescription = "修复图像，浏览模式可双指缩放，涂抹模式选择处理区域" }
            .pointerInput(brush, radius, erase) {
                if (brush)
                    detectTapGestures { p ->
                        if (rect.contains(p.x, p.y))
                            appendStroke(RetouchStroke(listOf(mapPoint(p)), radius, erase))
                    }
            }
            .pointerInput(brush, radius, erase) {
                if (brush)
                    detectDragGestures(
                        onDragStart = { p ->
                            if (rect.contains(p.x, p.y)) points = listOf(mapPoint(p))
                        },
                        onDragEnd = {
                            if (points.isNotEmpty())
                                appendStroke(RetouchStroke(points, radius, erase))
                            points = emptyList()
                        },
                        onDragCancel = { points = emptyList() },
                    ) { change, _ ->
                        change.consume()
                        if (points.isNotEmpty()) points = points + mapPoint(change.position)
                    }
                else
                    detectTransformGestures { _, delta, factor, _ ->
                        zoom = (zoom * factor).coerceIn(1f, 6f)
                        pan =
                            if (zoom == 1f) Offset.Zero
                            else
                                Offset(
                                    (pan.x + delta.x).coerceIn(-rect.width() / 2, rect.width() / 2),
                                    (pan.y + delta.y).coerceIn(
                                        -rect.height() / 2,
                                        rect.height() / 2,
                                    ),
                                )
                    }
            }
    ) {
        if (width > 0 && height > 0) {
            val dst = IntSize(max(1, width.roundToInt()), max(1, height.roundToInt()))
            val offset = IntOffset(left.roundToInt(), top.roundToInt())
            drawImage(bitmap.asImageBitmap(), dstOffset = offset, dstSize = dst)
            if (brush) drawImage(overlay.asImageBitmap(), dstOffset = offset, dstSize = dst)
        }
    }
}
