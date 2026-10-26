package cn.paperforge.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.pdf.*
import java.io.File
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfDiffScreen(vm: MainViewModel, onImport: () -> Unit) {
    val review by vm.pdfDiffReview.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF 差异比较") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "convert" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (review != null)
                        TextButton(onClick = { vm.pdfDiffReview.value = null }) { Text("新比较") }
                },
            )
        }
    ) { padding ->
        val result = review
        if (result == null) PdfDiffSetup(vm, onImport, Modifier.padding(padding))
        else PdfDiffResult(vm, result, Modifier.padding(padding))
    }
}

@Composable
private fun PdfDiffSetup(vm: MainViewModel, onImport: () -> Unit, modifier: Modifier) {
    val documents by vm.repo.documents.collectAsState(emptyList())
    val pdfs = documents.filter { it.kind == "pdf" && it.pdfPath != null }
    val inputs by vm.pdfDiffInputs.collectAsState()
    val options by vm.pdfDiffOptions.collectAsState()
    val busy by vm.busy.collectAsState()
    var picker by remember { mutableIntStateOf(-1) }
    var leftPage by rememberSaveable { mutableStateOf((options.leftStart + 1).toString()) }
    var rightPage by rememberSaveable { mutableStateOf((options.rightStart + 1).toString()) }
    fun update(value: PdfDiffOptions) {
        vm.pdfDiffOptions.value = value
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("核对修订，直接看到变化", style = MaterialTheme.typography.headlineSmall) }
        item { Text("原生文字与页面外观同时比较。扫描件可直接核对外观；需要文字差异时，先添加 OCR 文字层。") }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("左侧 · 原版", "右侧 · 修订版").forEachIndexed { index, label ->
                        val uri = inputs.getOrNull(index)
                        val name =
                            pdfs.firstOrNull { Uri.fromFile(File(it.pdfPath!!)) == uri }?.title
                                ?: uri?.lastPathSegment?.substringAfterLast('/')
                                ?: "从文档库选择"
                        OutlinedButton(
                            onClick = { picker = index },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("$label\n$name", maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    TextButton(onClick = onImport) { Text("从手机选择两份 PDF") }
                    if (inputs.size == 2)
                        TextButton(onClick = { vm.pdfDiffInputs.value = inputs.reversed() }) {
                            Text("交换左右文档")
                        }
                }
            }
        }
        item { Text("比较范围", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    leftPage,
                    { leftPage = it },
                    label = { Text("左侧起始页") },
                    singleLine = true,
                    isError = leftPage.toIntOrNull()?.let { it > 0 } != true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    rightPage,
                    { rightPage = it },
                    label = { Text("右侧起始页") },
                    singleLine = true,
                    isError = rightPage.toIntOrNull()?.let { it > 0 } != true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Text(
                "从起始页逐页配对，尾部增删页单独标记。插页后可调整起始页，重新核对后续部分。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Text("忽略顶部 ${(options.top*100).toInt()}% · 页眉")
            Slider(
                options.top,
                { update(options.copy(top = it)) },
                valueRange = 0f..0.4f,
                steps = 39,
            )
            Text("忽略底部 ${(options.bottom*100).toInt()}% · 页脚")
            Slider(
                options.bottom,
                { update(options.copy(bottom = it)) },
                valueRange = 0f..0.4f,
                steps = 39,
            )
            Text("外观容差 ${options.threshold} / 255")
            Slider(
                options.threshold.toFloat(),
                { update(options.copy(threshold = it.toInt())) },
                valueRange = 1f..100f,
            )
        }
        item {
            Column {
                DiffSwitch("文字忽略空白", options.ignoreWhitespace) {
                    update(options.copy(ignoreWhitespace = it))
                }
                DiffSwitch("文字忽略大小写", options.ignoreCase) { update(options.copy(ignoreCase = it)) }
                DiffSwitch("外观按灰度比较", options.ignoreColor) {
                    update(options.copy(ignoreColor = it))
                }
            }
        }
        item {
            Button(
                onClick = {
                    val value =
                        options.copy(
                            leftStart = (leftPage.toInt() - 1),
                            rightStart = (rightPage.toInt() - 1),
                        )
                    update(value)
                    vm.startPdfComparison(inputs, value)
                },
                enabled =
                    !busy &&
                        inputs.size == 2 &&
                        leftPage.toIntOrNull()?.let { it > 0 } == true &&
                        rightPage.toIntOrNull()?.let { it > 0 } == true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("开始 PDF 比较")
            }
        }
    }
    if (picker >= 0)
        AlertDialog(
            onDismissRequest = { picker = -1 },
            title = { Text(if (picker == 0) "选择原版 PDF" else "选择修订版 PDF") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (pdfs.isEmpty()) item { Text("文档库还没有 PDF，可从手机选择两份。") }
                    items(pdfs, key = { it.id }) { doc ->
                        TextButton(
                            onClick = {
                                val uri = Uri.fromFile(File(doc.pdfPath!!))
                                val current = inputs.toMutableList()
                                while (current.size < 2) current += uri
                                current[picker] = uri
                                vm.pdfDiffInputs.value = current
                                picker = -1
                            }
                        ) {
                            Text(doc.title, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        picker = -1
                        onImport()
                    }
                ) {
                    Text("手机文件")
                }
            },
            dismissButton = { TextButton(onClick = { picker = -1 }) { Text("关闭") } },
        )
}

@Composable
private fun DiffSwitch(title: String, checked: Boolean, update: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title)
        Switch(checked, update)
    }
}

@Composable
private fun PdfDiffResult(vm: MainViewModel, review: PdfDiffReview, modifier: Modifier) {
    val pages = remember(review.report) { review.pages }
    val location by vm.pdfDiffPosition.collectAsState()
    val index = location.first
    val page = pages[index.coerceIn(pages.indices)]
    val selected = location.second
    val finding = page.differences.firstOrNull { it.id == selected }
    var zoom by remember(review.taskId, index) { mutableFloatStateOf(1f) }
    var pan by remember(review.taskId, index) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize(1, 1)) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsState()
    fun fit() {
        zoom = 1f
        pan = Offset.Zero
    }
    LaunchedEffect(selected, index) {
        val rect = finding?.left ?: finding?.right
        if (rect != null) {
            zoom =
                min(
                        1f / (rect.right - rect.left).coerceAtLeast(.16f),
                        1f / (rect.bottom - rect.top).coerceAtLeast(.16f),
                    )
                    .coerceIn(1f, 5f)
            pan = Offset(.5f - (rect.left + rect.right) / 2, .5f - (rect.top + rect.bottom) / 2)
        }
    }
    val transform = rememberTransformableState { factor, delta, _ ->
        zoom = (zoom * factor).coerceIn(1f, 6f)
        val limit = (zoom - 1) / (2 * zoom)
        pan =
            Offset(
                (pan.x + delta.x / (viewport.width.coerceAtLeast(1) / 2f * zoom)).coerceIn(
                    -limit,
                    limit,
                ),
                (pan.y + delta.y / (viewport.height.coerceAtLeast(1) * zoom)).coerceIn(
                    -limit,
                    limit,
                ),
            )
    }
    val names = review.report.getJSONArray("names")
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = { if (index > 0) vm.pdfDiffPosition.value = (index - 1) to null },
                enabled = index > 0,
            ) {
                Text("上一组")
            }
            Text("${index+1} / ${pages.size}")
            TextButton(
                onClick = {
                    if (index < pages.lastIndex) vm.pdfDiffPosition.value = (index + 1) to null
                },
                enabled = index < pages.lastIndex,
            ) {
                Text("下一组")
            }
        }
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(
                onClick = {
                    zoom = (zoom / 1.4f).coerceAtLeast(1f)
                    if (zoom == 1f) pan = Offset.Zero
                }
            ) {
                Text("−")
            }
            TextButton(onClick = ::fit) { Text("适合页面") }
            TextButton(onClick = { zoom = (zoom * 1.4f).coerceAtMost(6f) }) { Text("＋") }
            TextButton(onClick = { fullscreen = !fullscreen }) {
                Text(if (fullscreen) "显示差异" else "放大查看")
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .weight(if (fullscreen) 1f else .52f)
                .onSizeChanged { viewport = it }
                .transformable(transform)
                .testTag("diff-viewer"),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DiffPanel(review, page, true, zoom, pan, selected, Modifier.weight(1f).fillMaxHeight())
            DiffPanel(review, page, false, zoom, pan, selected, Modifier.weight(1f).fillMaxHeight())
        }
        Row(
            Modifier.padding(horizontal = 8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = { vm.openPdfDifferenceSource(true, page, finding) },
                enabled = page.leftPage != null && !busy,
            ) {
                Text("定位左侧原文")
            }
            TextButton(
                onClick = { vm.openPdfDifferenceSource(false, page, finding) },
                enabled = page.rightPage != null && !busy,
            ) {
                Text("定位右侧原文")
            }
        }
        if (!fullscreen)
            LazyColumn(
                Modifier.weight(.48f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "${names.getString(0)} ↔ ${names.getString(1)}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                    )
                    Text(
                        "${page.differences.count { !review.isIgnored(page, it) }} 处差异 · ${page.differences.count { review.isIgnored(page, it) }} 处已忽略",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "双指同步缩放 / 拖动，点差异可放大定位。文字和外观可能标记同一处修改。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    page.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (page.differences.isEmpty()) Text("当前参数下没有检测到差异。")
                }
                items(page.differences, key = { it.id }) { diff ->
                    val ignored = review.isIgnored(page, diff)
                    OutlinedCard(
                        onClick = { vm.pdfDiffPosition.value = index to diff.id },
                        colors =
                            CardDefaults.outlinedCardColors(
                                containerColor =
                                    if (selected == diff.id)
                                        MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surface
                            ),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    diffKind(diff.kind) + (if (ignored) " · 已忽略" else ""),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                TextButton(
                                    onClick = { vm.ignorePdfDifference(page, diff) },
                                    enabled = !busy,
                                ) {
                                    Text(if (ignored) "恢复差异" else "忽略差异")
                                }
                            }
                            if (diff.before.isNotEmpty() || diff.after.isNotEmpty()) {
                                Text(
                                    "左：${diff.before.ifEmpty { "（空）" }}",
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "右：${diff.after.ifEmpty { "（空）" }}",
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        FilledTonalButton(
            onClick = vm::exportPdfComparison,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("导出比较报告包")
        }
    }
}

private fun diffKind(kind: String) =
    when (kind) {
        "text" -> "文字变化"
        "visual" -> "外观变化"
        "added-page" -> "新增页面"
        "removed-page" -> "删除页面"
        "page-size" -> "页面比例变化"
        else -> kind
    }

@Composable
private fun DiffPanel(
    review: PdfDiffReview,
    page: PdfDiffPage,
    left: Boolean,
    zoom: Float,
    pan: Offset,
    selected: String?,
    modifier: Modifier,
) {
    val image = if (left) page.leftImage else page.rightImage
    val bitmap by
        produceState<android.graphics.Bitmap?>(null, review.directory, image) {
            value =
                withContext(Dispatchers.IO) {
                    image?.let { BitmapFactory.decodeFile(File(review.directory, it).path) }
                }
        }
    val current = bitmap
    DisposableEffect(current) { onDispose { current?.recycle() } }
    Column(modifier) {
        Text(
            "${if(left) "左" else "右"} · ${(if(left) page.leftPage else page.rightPage)?.plus(1) ?: "无对应页"}",
            Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.labelSmall,
        )
        BoxWithConstraints(
            Modifier.weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (current != null) {
                val ratio = current.width.toFloat() / current.height
                val width = min(maxWidth.value, maxHeight.value * ratio).dp
                Box(
                    Modifier.width(width).aspectRatio(ratio).graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x * size.width * zoom
                        translationY = pan.y * size.height * zoom
                    }
                ) {
                    Image(
                        current.asImageBitmap(),
                        "${if(left) "左侧" else "右侧"}比较页面",
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                    Canvas(Modifier.matchParentSize()) {
                        page.differences
                            .filterNot { review.isIgnored(page, it) }
                            .forEach { finding ->
                                val rect = if (left) finding.left else finding.right
                                if (rect != null)
                                    drawRect(
                                        if (finding.id == selected) Color(0x6687A900)
                                        else Color(0x44FF3030),
                                        Offset(rect.left * size.width, rect.top * size.height),
                                        Size(
                                            (rect.right - rect.left) * size.width,
                                            (rect.bottom - rect.top) * size.height,
                                        ),
                                    )
                            }
                    }
                }
            } else Text(if (image == null) "没有对应页面" else "载入页面…")
        }
    }
}
