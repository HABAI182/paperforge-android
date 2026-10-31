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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.SmartNaming
import kotlinx.coroutines.launch

fun scanFilterName(filter: String) =
    when (filter) {
        "ORIGINAL" -> "原图"
        "AUTO" -> "自然"
        "TEXT" -> "清晰文字"
        "WORKBOOK" -> "练习册"
        "CLEAN" -> "强力去背景"
        "GRAY" -> "灰度"
        "BW" -> "柔和黑白"
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
    suggestName: (suspend () -> SmartNaming.Suggestion?)? = null,
    highlights: Map<String, List<List<PointN>>> = emptyMap(),
    taskStatus: String? = null,
    renameRequest: Int = 0,
    fullscreenRequest: Int = 0,
) {
    val compactHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    val motion = LocalPaperMotion.current
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var enhance by rememberSaveable { mutableStateOf(false) }
    // #31: the thumbnail strip is persistent (collapsed only on explicit request or fullscreen).
    var thumbnails by rememberSaveable { mutableStateOf(true) }
    var compare by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val active = pages.getOrNull(pager.currentPage)
    val stripIndex = if (pages.isEmpty()) 0 else pager.currentPage.coerceIn(0, pages.lastIndex)
    val scope = rememberCoroutineScope()
    // The "more" sheet lives in EditorScreen; these counters let it open the dialogs owned here.
    LaunchedEffect(renameRequest) { if (renameRequest > 0) rename = true }
    LaunchedEffect(fullscreenRequest) { if (fullscreenRequest > 0) fullscreen = true }
    FocusSystemBars(fullscreen)
    val requestDocumentFocus = LocalDocumentFocus.current
    DisposableEffect(fullscreen, requestDocumentFocus) {
        requestDocumentFocus(fullscreen)
        onDispose { requestDocumentFocus(false) }
    }
    // The strip is expanded by default, so system back still leaves the editor; only the
    // transient in-place surfaces (fullscreen, filter panel) intercept it, as before.
    BackHandler(fullscreen || enhance) {
        when {
            fullscreen -> fullscreen = false
            else -> enhance = false
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
                            if (LocalDensity.current.fontScale <= 1.5f)
                                Text(
                                    "整理扫描 · 点击名称重命名",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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
                        // #31: one row of six primary actions. Adding a page is still one tap to
                        // the camera; long-press opens the alternative add-page sheet.
                        val showLabel = LocalDensity.current.fontScale <= 1.3f
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ToolAction(
                                Icons.Default.Add,
                                "加页",
                                !busy,
                                Modifier.weight(1f),
                                showLabel,
                                longPressLabel = "更多加页方式",
                                onLongPress = { if (!busy) onAction("addPageSheet") },
                            ) {
                                onAction("capture")
                            }
                            ToolAction(
                                Icons.Default.Crop,
                                "裁剪",
                                active != null && !busy,
                                Modifier.weight(1f),
                                showLabel,
                            ) {
                                onAction("crop")
                            }
                            ToolAction(
                                Icons.Default.AutoFixHigh,
                                "滤镜",
                                active != null && !busy,
                                Modifier.weight(1f),
                                showLabel,
                            ) {
                                enhance = !enhance
                            }
                            ToolAction(
                                Icons.Default.TextFields,
                                "识字",
                                active != null && !busy,
                                Modifier.weight(1f),
                                showLabel,
                            ) {
                                onAction("ocr")
                            }
                            ToolAction(
                                Icons.Default.Delete,
                                "删除",
                                active != null && !busy,
                                Modifier.weight(1f),
                                showLabel,
                            ) {
                                onAction("delete")
                            }
                            ToolAction(
                                Icons.Default.MoreHoriz,
                                "更多",
                                active != null && !busy,
                                Modifier.weight(1f),
                                showLabel,
                            ) {
                                onAction("more")
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
                        // Page count can shrink while the pager still holds the previous range
                        // (split → undo). Read the current list defensively instead of crashing on
                        // a stale index.
                        key = { pages.getOrNull(it)?.id ?: "pending-$it" },
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = !zoomed,
                        beyondViewportPageCount = 0,
                    ) { index ->
                        val page = pages.getOrNull(index) ?: return@HorizontalPager
                        ScanPageCanvas(
                            page,
                            compare && index == pager.currentPage,
                            onTap = { fullscreen = !fullscreen },
                            onZoom = { if (index == pager.currentPage) zoomed = it },
                            highlights = highlights[page.id].orEmpty(),
                            previousPage =
                                if (index > 0)
                                    ({
                                        scope.launch { pager.animateScrollToPage(index - 1) }
                                        Unit
                                    })
                                else null,
                            nextPage =
                                if (index + 1 < pages.size)
                                    ({
                                        scope.launch { pager.animateScrollToPage(index + 1) }
                                        Unit
                                    })
                                else null,
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
                // #31 decision 5: the page badge stays; it also expands/collapses the strip.
                if (active != null && !fullscreen)
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                        onClick = { thumbnails = !thumbnails },
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            "${stripIndex + 1} / ${pages.size}",
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
            }
            if (!fullscreen && thumbnails && !enhance && !compactHeight && pages.isNotEmpty())
                ThumbnailStrip(
                    pages = pages,
                    current = stripIndex,
                    onSelect = { index ->
                        // #23 guard: the strip can outlive a page removal for one frame.
                        val target = index.coerceIn(0, pages.lastIndex)
                        scope.launch {
                            if (motion.reduced) pager.scrollToPage(target)
                            else pager.animateScrollToPage(target)
                        }
                    },
                    onManage = { onAction("pages") },
                    onCollapse = { thumbnails = false },
                )
            androidx.compose.animation.AnimatedVisibility(
                visible = !fullscreen && enhance && active != null,
                enter =
                    androidx.compose.animation.expandVertically(
                        androidx.compose.animation.core.tween(motion.panelMs)
                    ) +
                        androidx.compose.animation.fadeIn(
                            androidx.compose.animation.core.tween(motion.stateMs)
                        ),
                exit =
                    androidx.compose.animation.shrinkVertically(
                        androidx.compose.animation.core.tween(motion.panelMs)
                    ) +
                        androidx.compose.animation.fadeOut(
                            androidx.compose.animation.core.tween(motion.stateMs)
                        ),
            ) {
                val filterPage = active
                if (filterPage != null)
                    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(ScanProcessor.filters) { _, filter ->
                                Surface(
                                    onClick = { if (!busy) onFilter(filterPage, filter) },
                                    enabled = !busy,
                                    modifier =
                                        Modifier.semantics {
                                            selected = filterPage.filter == filter
                                        },
                                    shape = RoundedCornerShape(10.dp),
                                    border =
                                        BorderStroke(
                                            if (filterPage.filter == filter) 2.dp else 1.dp,
                                            if (filterPage.filter == filter)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                        ),
                                ) {
                                    Column(
                                        Modifier.width(62.dp).padding(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        pageBitmap(filterPage.copy(filter = filter), 130)?.let {
                                            Image(
                                                it.asImageBitmap(),
                                                null,
                                                Modifier.height(if (compactHeight) 28.dp else 54.dp),
                                            )
                                        }
                                        Text(
                                            scanFilterName(filter),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
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
                        Text(
                            "按住画布可临时查看原图；「应用全部」沿用既有语义（一次撤销回退一页）",
                            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { onAction("retouch") },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("清理与修复 · 涂抹、去噪、超分")
                        }
                    }
            }
        }
    }
    if (rename) {
        var draft by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("文档名称") },
            text = {
                Column {
                    OutlinedTextField(
                        draft,
                        { draft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    suggestName?.let {
                        SmartNamingAssist(load = it, onPick = { name -> draft = name })
                    }
                }
            },
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

/** #31: always-on filmstrip under the canvas. Tap = jump (coerced), icons = grid / collapse. */
@Composable
private fun ThumbnailStrip(
    pages: List<PageRow>,
    current: Int,
    onSelect: (Int) -> Unit,
    onManage: () -> Unit,
    onCollapse: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().testTag("thumbnail-strip"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                    val selected = index == current
                    Surface(
                        onClick = { onSelect(index) },
                        border =
                            BorderStroke(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(
                            Modifier.width(48.dp).padding(3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            pageBitmap(page, 180)?.let {
                                Image(
                                    it.asImageBitmap(),
                                    "第 ${index + 1} 页",
                                    Modifier.height(44.dp),
                                )
                            }
                            Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            IconButton(onClick = onManage) { Icon(Icons.Default.GridView, "页面管理") }
            IconButton(onClick = onCollapse) { Icon(Icons.Default.KeyboardArrowDown, "收起缩略图条") }
        }
    }
}
