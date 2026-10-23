package cn.paperforge.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfSession(file: File) : AutoCloseable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    val count = renderer.pageCount
    // A bounded cache plus LazyColumn prevents rendering an entire document into memory.
    private val cache =
        object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
        }

    @Synchronized
    fun render(n: Int): Bitmap =
        cache.get(n)
            ?: renderer.openPage(n).use { p ->
                val scale = 1100f / maxOf(p.width, p.height)
                Bitmap.createBitmap(
                        (p.width * scale).toInt().coerceAtLeast(1),
                        (p.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    .also { b ->
                        b.eraseColor(AndroidColor.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(n, b)
                    }
            }

    @Synchronized
    override fun close() {
        cache.evictAll()
        renderer.close()
        fd.close()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: MainViewModel, onShare: (File) -> Unit) {
    val doc by vm.selected.collectAsState()
    val busy by vm.busy.collectAsState()
    val file = doc?.pdfPath?.let { File(it) }
    val session by
        produceState<PdfSession?>(null, file) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { file?.let { PdfSession(it) } }.getOrNull()
                }
        }
    val currentSession = session
    DisposableEffect(currentSession) { onDispose { currentSession?.close() } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var rangeDialog by remember { mutableStateOf(false) }
    var compressDialog by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf("") }
    var rotation by remember { mutableIntStateOf(0) }
    var jump by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("1") }
    var tools by remember { mutableStateOf(false) }
    var thumbnails by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hitIndex by remember { mutableIntStateOf(0) }
    val hits by vm.searchHits.collectAsState()
    LaunchedEffect(hits) {
        hitIndex = 0
        hits.firstOrNull()?.let { list.scrollToItem(it.page) }
    }
    LaunchedEffect(list.firstVisibleItemIndex) {
        vm.annotationPage.value = list.firstVisibleItemIndex
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc?.title ?: "PDF", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "home" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { file?.let { onShare(it) } }) {
                        Icon(Icons.Default.Share, "分享 PDF")
                    }
                    IconButton(onClick = { search = !search }) {
                        Icon(Icons.Default.Search, "搜索文字")
                    }
                    IconButton(onClick = { tools = true }) {
                        Icon(Icons.Default.MoreVert, "PDF 工具")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolAction(Icons.Default.ContentCut, "抽页 / 旋转", !busy) { rangeDialog = true }
                ToolAction(Icons.Default.Compress, "压缩图片", !busy) { compressDialog = true }
                ToolAction(Icons.Default.Draw, "批注签名", !busy) { vm.beginMarks() }
                ToolAction(Icons.Default.FindInPage, "跳页", session != null) { jump = true }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (search)
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        singleLine = true,
                        label = { Text("搜索 PDF 文字层") },
                        trailingIcon = {
                            TextButton(enabled = !busy, onClick = { vm.searchPdf(query) }) {
                                Text("查找")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            if (hits.isEmpty()) "${hits.size} 个结果；扫描件可先添加 OCR 文字层"
                            else "${hitIndex+1} / ${hits.size}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            enabled = hits.isNotEmpty(),
                            onClick = {
                                hitIndex = (hitIndex + 1) % hits.size
                                scope.launch { list.scrollToItem(hits[hitIndex].page) }
                            },
                        ) {
                            Text("下一处")
                        }
                    }
                }
            Box(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                val s = session
                if (s == null)
                    Text(
                        "无法读取 PDF 或仍在加载。加密文件暂不支持在此打开。",
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                else {
                    LazyColumn(
                        state = list,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        items(s.count, key = { it }) { index ->
                            val bitmap by
                                produceState<Bitmap?>(null, s, index) {
                                    value =
                                        withContext(Dispatchers.IO) {
                                            runCatching { s.render(index) }.getOrNull()
                                        }
                                }
                            var zoom by remember { mutableFloatStateOf(1f) }
                            var panX by remember { mutableFloatStateOf(0f) }
                            var panY by remember { mutableFloatStateOf(0f) }
                            Column {
                                val b = bitmap
                                if (b != null)
                                    Box(
                                        Modifier.fillMaxWidth()
                                            .aspectRatio(b.width.toFloat() / b.height)
                                            .clipToBounds()
                                            .pointerInput(Unit) {
                                                awaitEachGesture {
                                                    awaitFirstDown(requireUnconsumed = false)
                                                    do {
                                                        val event = awaitPointerEvent()
                                                        if (
                                                            event.changes.count { it.pressed } >
                                                                1 || zoom > 1f
                                                        ) {
                                                            val pan = event.calculatePan()
                                                            val scale = event.calculateZoom()
                                                            zoom = (zoom * scale).coerceIn(1f, 4f)
                                                            val limitX = (zoom - 1) * size.width / 2
                                                            val limitY =
                                                                (zoom - 1) * size.height / 2
                                                            panX =
                                                                (panX + pan.x).coerceIn(
                                                                    -limitX,
                                                                    limitX,
                                                                )
                                                            panY =
                                                                (panY + pan.y).coerceIn(
                                                                    -limitY,
                                                                    limitY,
                                                                )
                                                            event.changes.forEach { it.consume() }
                                                        }
                                                    } while (event.changes.any { it.pressed })
                                                }
                                            }
                                    ) {
                                        Box(
                                            Modifier.fillMaxSize().graphicsLayer {
                                                scaleX = zoom
                                                scaleY = zoom
                                                translationX = panX
                                                translationY = panY
                                            }
                                        ) {
                                            Image(
                                                b.asImageBitmap(),
                                                "第 ${index+1} 页",
                                                Modifier.fillMaxSize(),
                                            )
                                            Canvas(Modifier.fillMaxSize()) {
                                                hits
                                                    .filter { it.page == index }
                                                    .forEach { hit ->
                                                        drawRect(
                                                            Color(0x88FFD54F),
                                                            Offset(
                                                                hit.left * size.width,
                                                                hit.top * size.height,
                                                            ),
                                                            Size(
                                                                (hit.right - hit.left) * size.width,
                                                                (hit.bottom - hit.top) * size.height,
                                                            ),
                                                        )
                                                    }
                                            }
                                        }
                                    }
                                else
                                    Box(
                                        Modifier.fillMaxWidth().height(360.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "${index+1} / ${s.count}",
                                        modifier = Modifier.padding(8.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    if (zoom > 1f)
                                        TextButton(
                                            onClick = {
                                                zoom = 1f
                                                panX = 0f
                                                panY = 0f
                                            }
                                        ) {
                                            Text("恢复缩放")
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (rangeDialog)
        AlertDialog(
            onDismissRequest = { rangeDialog = false },
            title = { Text("抽取页面与旋转") },
            text = {
                Column {
                    OutlinedTextField(
                        value = range,
                        onValueChange = { range = it },
                        label = { Text("页码，例如 1-3,5；留空为全部") },
                    )
                    Text(
                        "按输入顺序输出，源文件保留。若源 PDF 有数字签名，生成副本会改变签名状态。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    Row {
                        listOf(0, 90, 180, 270).forEach { angle ->
                            FilterChip(
                                selected = rotation == angle,
                                onClick = { rotation = angle },
                                label = { Text("$angle°") },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        rangeDialog = false
                        vm.extract(range, rotation)
                    },
                    enabled = !busy,
                ) {
                    Text("创建副本")
                }
            },
            dismissButton = { TextButton(onClick = { rangeDialog = false }) { Text("取消") } },
        )
    if (compressDialog)
        AlertDialog(
            onDismissRequest = { compressDialog = false },
            title = { Text("压缩嵌入图片") },
            text = {
                Text("保留原生文字、矢量、链接和表单，只重编码可安全处理的嵌入图片。透明、特殊色彩和签名文档会保留，无法减小时输出原件副本。生成后显示实际体积。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        compressDialog = false
                        vm.compress(75)
                    },
                    enabled = !busy,
                ) {
                    Text("均衡画质")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            compressDialog = false
                            vm.compress(45)
                        },
                        enabled = !busy,
                    ) {
                        Text("小体积")
                    }
                    TextButton(onClick = { compressDialog = false }) { Text("取消") }
                }
            },
        )
    if (jump)
        AlertDialog(
            onDismissRequest = { jump = false },
            title = { Text("跳到页码") },
            text = {
                OutlinedTextField(
                    value = jumpText,
                    onValueChange = { jumpText = it },
                    singleLine = true,
                    label = { Text("1 – ${session?.count ?: 0}") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val n = jumpText.toIntOrNull()
                        val s = session
                        if (n != null && s != null && n in 1..s.count) {
                            scope.launch { list.scrollToItem(n - 1) }
                            jump = false
                        }
                    }
                ) {
                    Text("跳转")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            jump = false
                            thumbnails = true
                        }
                    ) {
                        Text("缩略图")
                    }
                    TextButton(
                        onClick = {
                            jump = false
                            vm.screen.value = "pdf-pages"
                        }
                    ) {
                        Text("编排")
                    }
                    TextButton(onClick = { jump = false }) { Text("取消") }
                }
            },
        )
    if (tools) PdfToolsDialog(vm) { tools = false }
    if (thumbnails)
        AlertDialog(
            onDismissRequest = { thumbnails = false },
            title = { Text("页面缩略图") },
            text = {
                val s = session
                if (s != null)
                    LazyVerticalGrid(
                        GridCells.Fixed(3),
                        modifier = Modifier.height(420.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(s.count) { n ->
                            val thumb by
                                produceState<Bitmap?>(null, s, n) {
                                    value =
                                        withContext(Dispatchers.IO) {
                                            runCatching { s.render(n) }.getOrNull()
                                        }
                                }
                            Column(
                                Modifier.clickable {
                                    scope.launch { list.scrollToItem(n) }
                                    thumbnails = false
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                thumb?.let {
                                    Image(
                                        it.asImageBitmap(),
                                        "跳到第 ${n+1} 页",
                                        Modifier.fillMaxWidth().height(100.dp),
                                    )
                                }
                                Text("${n+1}")
                            }
                        }
                    }
            },
            confirmButton = { TextButton(onClick = { thumbnails = false }) { Text("关闭") } },
        )
}
