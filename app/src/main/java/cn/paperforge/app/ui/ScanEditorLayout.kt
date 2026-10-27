package cn.paperforge.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.PointN
import kotlinx.coroutines.launch

fun scanFilterName(filter: String) =
    when (filter) {
        "ORIGINAL" -> "自然"
        "AUTO" -> "自动"
        "TEXT" -> "清晰"
        "CLEAN" -> "去阴影"
        "GRAY" -> "灰度"
        "BW" -> "黑白"
        "COLOR" -> "彩色"
        "WHITEBOARD" -> "白板"
        else -> filter
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanEditorLayout(
    pages: List<PageRow>,
    pager: PagerState,
    name: String,
    busy: Boolean,
    onAction: (String) -> Unit,
    onRename: (String) -> Unit,
    onFilter: (PageRow, String) -> Unit,
    highlights: Map<String, List<List<PointN>>> = emptyMap(),
    taskStatus: String? = null,
) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var enhance by rememberSaveable { mutableStateOf(false) }
    var thumbnails by rememberSaveable { mutableStateOf(false) }
    var compare by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val active = pages.getOrNull(pager.currentPage)
    val scope = rememberCoroutineScope()
    FocusSystemBars(fullscreen)
    val requestDocumentFocus = LocalDocumentFocus.current
    DisposableEffect(fullscreen, requestDocumentFocus) {
        requestDocumentFocus(fullscreen)
        onDispose { requestDocumentFocus(false) }
    }
    BackHandler(fullscreen || enhance || thumbnails) {
        when {
            fullscreen -> fullscreen = false
            enhance -> enhance = false
            else -> thumbnails = false
        }
    }
    LaunchedEffect(pager.currentPage) {
        zoomed = false
        compare = false
    }
    Scaffold(
        contentWindowInsets =
            if (fullscreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            if (!fullscreen)
                TopAppBar(
                    title = {
                        Column(Modifier.clickable { rename = true }) {
                            Text(
                                name.ifBlank { "扫描文档" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "整理扫描 · 点击名称重命名",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { onAction("back") }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { onAction("undo") }, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Filled.Undo, "撤销")
                        }
                        IconButton(onClick = { onAction("redo") }, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Filled.Redo, "重做")
                        }
                        IconButton(onClick = { fullscreen = true }) {
                            Icon(Icons.Default.Fullscreen, "全屏检查")
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                )
        },
        bottomBar = {
            if (!fullscreen)
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.navigationBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            ToolAction(Icons.Default.Crop, "裁切", active != null && !busy) {
                                onAction("crop")
                            }
                            ToolAction(Icons.Default.AutoFixHigh, "增强", active != null && !busy) {
                                enhance = !enhance
                                thumbnails = false
                            }
                            ToolAction(Icons.Default.TextFields, "识字", active != null && !busy) {
                                onAction("ocr")
                            }
                            ToolAction(Icons.Default.MoreHoriz, "更多", active != null && !busy) {
                                onAction("more")
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(onClick = { onAction("capture") }, enabled = !busy) {
                                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                                Text("继续拍")
                            }
                            OutlinedButton(onClick = { onAction("import") }, enabled = !busy) {
                                Text("导图")
                            }
                            Button(
                                onClick = { onAction("export") },
                                enabled = active != null && !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("导出")
                            }
                        }
                    }
                }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!fullscreen && taskStatus != null)
                Surface(
                    onClick = { onAction("tasks") },
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            taskStatus,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text("查看任务", style = MaterialTheme.typography.labelMedium)
                    }
                }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("scan-canvas")) {
                if (pages.isEmpty())
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Default.NoteAdd, null, Modifier.size(48.dp))
                        Text("草稿已保存，添加第一页吧", Modifier.padding(20.dp))
                    }
                else
                    HorizontalPager(
                        pager,
                        key = { pages[it].id },
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = !zoomed,
                        beyondViewportPageCount = 0,
                    ) { index ->
                        ScanPageCanvas(
                            pages[index],
                            compare && index == pager.currentPage,
                            onTap = { fullscreen = !fullscreen },
                            onZoom = { if (index == pager.currentPage) zoomed = it },
                            highlights = highlights[pages[index].id].orEmpty(),
                        )
                    }
                if (fullscreen)
                    IconButton(
                        onClick = { fullscreen = false },
                        modifier =
                            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(),
                    ) {
                        Icon(Icons.Default.FullscreenExit, "退出全屏检查")
                    }
                if (active != null)
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                        onClick = {
                            if (!fullscreen) {
                                thumbnails = !thumbnails
                                enhance = false
                            }
                        },
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            "${pager.currentPage + 1} / ${pages.size}",
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
            }
            if (!fullscreen && thumbnails)
                LazyRow(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
                    contentPadding = PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                        Surface(
                            onClick = { scope.launch { pager.animateScrollToPage(index) } },
                            border =
                                BorderStroke(
                                    if (index == pager.currentPage) 2.dp else 1.dp,
                                    if (index == pager.currentPage)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Column(
                                Modifier.width(52.dp).padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                pageBitmap(page, 180)?.let {
                                    Image(
                                        it.asImageBitmap(),
                                        "第 ${index + 1} 页",
                                        Modifier.height(58.dp),
                                    )
                                }
                                Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    item { TextButton(onClick = { onAction("pages") }) { Text("页面管理") } }
                }
            if (!fullscreen && enhance && active != null)
                Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(ScanProcessor.filters) { _, filter ->
                            Surface(
                                onClick = { if (!busy) onFilter(active, filter) },
                                shape = RoundedCornerShape(10.dp),
                                border =
                                    BorderStroke(
                                        if (active.filter == filter) 2.dp else 1.dp,
                                        if (active.filter == filter)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                            ) {
                                Column(
                                    Modifier.width(62.dp).padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    pageBitmap(active.copy(filter = filter), 130)?.let {
                                        Image(it.asImageBitmap(), null, Modifier.height(54.dp))
                                    }
                                    Text(
                                        scanFilterName(filter),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { compare = !compare }) {
                            Text(if (compare) "查看增强" else "对比原图")
                        }
                        TextButton(onClick = { onAction("adjust") }, enabled = !busy) {
                            Text("调节强度")
                        }
                        TextButton(onClick = { onAction("applyAll") }, enabled = !busy) {
                            Text("应用全部")
                        }
                        TextButton(onClick = { enhance = false }) { Text("收起") }
                    }
                }
        }
    }
    if (rename) {
        var draft by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("文档名称") },
            text = { OutlinedTextField(draft, { draft = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(draft)
                        rename = false
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("取消") } },
        )
    }
}
