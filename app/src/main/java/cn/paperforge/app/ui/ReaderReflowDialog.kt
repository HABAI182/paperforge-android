package cn.paperforge.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.paperforge.app.*
import cn.paperforge.app.pdf.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderReflowDialog(
    file: File,
    documentId: String,
    title: String,
    initialPage: Int,
    count: Int,
    onOriginal: (ReaderDestination) -> Unit,
    onDismiss: (Int) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PaperForgeApp
    val preferences = remember { ReaderPreferences(context) }
    val saved = remember(documentId) { preferences.reflow(documentId) }
    var page by rememberSaveable(documentId) { mutableIntStateOf(initialPage) }
    var font by rememberSaveable { mutableFloatStateOf(saved.fontSize) }
    var spacing by rememberSaveable { mutableFloatStateOf(saved.lineSpacing) }
    var columns by rememberSaveable { mutableIntStateOf(saved.columns) }
    var styles by remember { mutableStateOf(false) }
    var recognize by remember { mutableIntStateOf(0) }
    var recognizePage by remember { mutableIntStateOf(-1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val content by
        produceState<ReflowPage?>(null, file, page, columns, recognize) {
            loading = true
            error = null
            value = null
            try {
                value =
                    ReaderReflow.load(
                        context,
                        file,
                        page,
                        app.ocrEngine.snapshot(),
                        columns,
                        recognizePage == page,
                    )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.message ?: "重排未完成"
            } finally {
                recognizePage = -1
                loading = false
            }
        }
    val list =
        key(page) {
            rememberLazyListState(
                if (saved.page == page) saved.index else 0,
                if (saved.page == page) saved.offset else 0,
            )
        }
    val position by
        rememberUpdatedState(
            ReflowPosition(
                page,
                list.firstVisibleItemIndex,
                list.firstVisibleItemScrollOffset,
                font,
                spacing,
                columns,
            )
        )
    DisposableEffect(documentId) { onDispose { preferences.reflow(documentId, position) } }
    LaunchedEffect(list, font, spacing, columns, page) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collectLatest {
                delay(250)
                preferences.reflow(documentId, position)
            }
    }
    fun copy(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
            ClipData.newPlainText("PaperForge", text)
        )
    }
    Dialog(
        onDismissRequest = { onDismiss(page) },
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            Modifier.fillMaxSize().testTag("reader-reflow"),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("阅读重排")
                            Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { onDismiss(page) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "退出重排")
                        }
                    },
                    actions = {
                        TextButton(onClick = { styles = !styles }) { Text("字号") }
                        TextButton(onClick = { onOriginal(ReaderDestination(page, "Fit")) }) {
                            Text("原页")
                        }
                    },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = { page-- }, enabled = page > 0 && !loading) {
                            Text("上一页")
                        }
                        Text("${page+1} / $count", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { page++ }, enabled = page + 1 < count && !loading) {
                            Text("下一页")
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (styles)
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            "字号 ${font.toInt()} · 行距 ${"%.1f".format(spacing)}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { font = (font - 2).coerceAtLeast(14f) }) {
                                Text("A−")
                            }
                            Slider(
                                font,
                                { font = it },
                                Modifier.weight(1f),
                                valueRange = 14f..36f,
                                steps = 10,
                            )
                            TextButton(onClick = { font = (font + 2).coerceAtMost(36f) }) {
                                Text("A＋")
                            }
                        }
                        Slider(spacing, { spacing = it }, valueRange = 1.2f..2f, steps = 3)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf("按行" to 0, "左栏优先" to 1, "右栏优先" to 2).forEach { (label, value) ->
                                FilterChip(
                                    columns == value,
                                    { columns = value },
                                    label = { Text(label) },
                                )
                            }
                            TextButton(
                                onClick = {
                                    recognizePage = page
                                    recognize++
                                },
                                enabled = !loading,
                            ) {
                                Text("补识别本页")
                            }
                        }
                    }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {
                    Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = list,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Text(
                            "单栏阅读 · 段落、图片和表格均可回到原页。复杂版式可直接查看原样。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val loaded = content
                    if (loaded != null)
                        itemsIndexed(
                            loaded.items,
                            key = { index, item -> "$page:${item.kind}:$index" },
                        ) { index, item ->
                            var originalTable by remember(item) { mutableStateOf(false) }
                            var reference by remember(item) { mutableStateOf(false) }
                            Column(Modifier.fillMaxWidth().testTag("reflow-item-$index")) {
                                when (item.kind) {
                                    "paragraph" ->
                                        SelectionContainer {
                                            Text(
                                                item.text,
                                                fontSize = font.sp,
                                                lineHeight = (font * spacing).sp,
                                            )
                                        }
                                    "image" -> ReflowImage(item.image, "图片 · 原页 ${page+1}")
                                    "table" -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "表格",
                                                Modifier.weight(1f),
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            TextButton(
                                                onClick = { originalTable = !originalTable }
                                            ) {
                                                Text(if (originalTable) "单元格阅读" else "原样对照")
                                            }
                                        }
                                        if (originalTable) ReflowImage(item.image, "表格原样")
                                        else item.table?.let { ReflowTable(it, font, spacing) }
                                    }
                                    "reference" -> {
                                        TextButton(onClick = { reference = !reference }) {
                                            Text(if (reference) "收起完整原页" else "查看完整原页")
                                        }
                                        if (reference) ReflowImage(item.image, "完整原页 ${page+1}")
                                    }
                                }
                                if (item.kind != "reference" || reference)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            onClick = { onOriginal(item.destination(page)) },
                                            modifier = Modifier.testTag("reflow-source-$index"),
                                        ) {
                                            Text("原页位置")
                                        }
                                        if (item.text.isNotEmpty() || item.table != null)
                                            TextButton(
                                                onClick = {
                                                    copy(
                                                        item.table?.rows?.joinToString("\n") {
                                                            it.joinToString("\t")
                                                        } ?: item.text
                                                    )
                                                }
                                            ) {
                                                Text("复制内容")
                                            }
                                        if (item.source == "ocr")
                                            Text(
                                                "离线识别",
                                                Modifier.align(Alignment.CenterVertically),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                    }
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun ReflowImage(file: File?, description: String) {
    val bitmap by
        produceState<Bitmap?>(null, file) {
            value =
                withContext(Dispatchers.IO) {
                    file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                }
        }
    bitmap?.let {
        Image(
            it.asImageBitmap(),
            description,
            Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height),
        )
    }
}

@Composable
private fun ReflowTable(table: PhoneTables.Table, font: Float, spacing: Float) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style =
        MaterialTheme.typography.bodyMedium.copy(
            fontSize = font.sp,
            lineHeight = (font * spacing).sp,
        )
    BoxWithConstraints(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        val columns = (table.cells.maxOfOrNull { it.columnEnd } ?: 0) + 1
        val rows = (table.cells.maxOfOrNull { it.rowEnd } ?: 0) + 1
        val columnWidth = 180.dp
        val pad = with(density) { 24.dp.toPx() }
        val unit = with(density) { columnWidth.toPx() }
        val heights = FloatArray(rows) { with(density) { 48.dp.toPx() } }
        table.cells
            .sortedBy { it.rowEnd - it.row }
            .forEach { cell ->
                val needed =
                    measurer
                        .measure(
                            cell.text,
                            style,
                            constraints =
                                Constraints(
                                    maxWidth =
                                        ((cell.columnEnd - cell.column + 1) * unit - pad).toInt()
                                ),
                        )
                        .size
                        .height + pad
                val available = (cell.row..cell.rowEnd).sumOf { heights[it].toDouble() }.toFloat()
                if (needed > available)
                    for (r in cell.row..cell.rowEnd) heights[r] +=
                        (needed - available) / (cell.rowEnd - cell.row + 1)
            }
        val offsets = heights.runningFold(0f) { sum, h -> sum + h }
        Box(Modifier.width(columnWidth * columns).height(with(density) { offsets.last().toDp() })) {
            table.cells.forEach { cell ->
                Box(
                    Modifier.offset(
                            x = columnWidth * cell.column,
                            y = with(density) { offsets[cell.row].toDp() },
                        )
                        .width(columnWidth * (cell.columnEnd - cell.column + 1))
                        .height(
                            with(density) { (offsets[cell.rowEnd + 1] - offsets[cell.row]).toDp() }
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        .padding(12.dp)
                ) {
                    SelectionContainer { Text(cell.text, style = style) }
                }
            }
        }
    }
}
