package cn.paperforge.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import cn.paperforge.app.pdf.*
import java.io.File
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TableReviewScreen(vm: MainViewModel) {
    val model by vm.tableWorkspace.draft.collectAsState()
    val busy by vm.busy.collectAsState()
    val current = model
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        (context.applicationContext as PaperForgeApp).backgroundScope.launch {
            vm.tableWorkspace.persist()
        }
    }
    var selected by remember(current?.taskId) { mutableIntStateOf(0) }
    var editing by remember(current?.taskId) { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectedCell by
        remember(current?.taskId, selected) { mutableStateOf<Pair<Int, Int>?>(null) }
    var text by remember { mutableStateOf("") }
    var sourceVisible by remember { mutableStateOf(true) }
    var selecting by remember(current?.taskId) { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val table = current?.tables?.getOrNull(selected)
    val owner =
        selectedCell?.let { (r, c) ->
            table?.cells?.firstOrNull { r in it.row..it.rowEnd && c in it.column..it.columnEnd }
        }
    val page = owner?.sourcePage ?: table?.page ?: 1
    var region by
        remember(current?.taskId, selected, page) { mutableStateOf(OcrRegion(0f, 0f, 1f, 1f)) }
    val image by
        produceState<Result<Bitmap>?>(null, current?.source, page) {
            value =
                current?.let {
                    withContext(Dispatchers.IO) {
                        runCatching { tableSource(File(it.source), page) }
                    }
                }
        }
    val ownedImage = image?.getOrNull()
    DisposableEffect(ownedImage) { onDispose { ownedImage?.recycle() } }
    val options = current?.options ?: TableExportOptions()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("表格校对", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = vm.tableWorkspace::close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { sourceVisible = !sourceVisible }) {
                        Text(if (sourceVisible) "收起原图" else "原图对照")
                    }
                },
            )
        },
        bottomBar = {
            if (current != null && table != null)
                FlowRow(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(onClick = { exportDialog = true }, enabled = !busy) { Text("导出表格") }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    "PaperForge TSV",
                                    TableData.delimited(
                                        table,
                                        selected,
                                        options.copy(
                                            format = "TSV",
                                            excelText = true,
                                            columnTypes = emptyMap(),
                                        ),
                                    ),
                                )
                            )
                            copied = true
                        },
                        enabled = !busy,
                    ) {
                        Text(if (copied) "已复制 TSV" else "复制 TSV")
                    }
                }
        },
    ) { padding ->
        if (current == null || table == null)
            Text("请从转换中心导入 PDF 或图片", Modifier.padding(padding).padding(24.dp))
        else
            Column(Modifier.fillMaxSize().padding(padding)) {
                ScrollableTabRow(selectedTabIndex = selected) {
                    current.tables.forEachIndexed { i, t ->
                        Tab(
                            selected == i,
                            {
                                selected = i
                                selectedCell = null
                                selecting = false
                            },
                            text = { Text("表 ${i+1} · P${t.sourcePages.joinToString(",")}") },
                        )
                    }
                }
                Text(
                    "${table.rows.size} 行 × ${table.rows.first().size} 列 · ${table.method} · 自动保存",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (sourceVisible) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 260.dp).weight(.42f)) {
                        image?.fold(
                            onSuccess = { bitmap ->
                                TableSourceCanvas(
                                    bitmap,
                                    table,
                                    page,
                                    owner,
                                    selecting,
                                    region,
                                    { region = it },
                                ) { cell ->
                                    selectedCell = cell.row to cell.column
                                    editing = selectedCell
                                    text = cell.text
                                }
                            },
                            onFailure = { Text("原件暂时无法显示：${it.message}", Modifier.padding(16.dp)) },
                        ) ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    FlowRow(
                        Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selecting,
                            onClick = { selecting = !selecting },
                            label = { Text(if (selecting) "拖动选择表格区域" else "框选重识别") },
                        )
                        if (selecting)
                            TextButton(
                                onClick = { vm.tableWorkspace.region(page, region) },
                                enabled = region.valid() && !busy,
                            ) {
                                Text("识别选区")
                            }
                        Text(
                            "第 $page 页 · 点击框核对单元格",
                            Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    table.rows.first().indices.forEach { column ->
                        var menu by remember(selected, column) { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { menu = true }) {
                                Text(
                                    "${column+1}列：${when(options.type(selected,column)) {"NUMBER" -> "数字"
"DECIMAL" -> "金额"
else -> "文本"}}"
                                )
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                listOf(
                                        "TEXT" to "文本（保留前导零）",
                                        "NUMBER" to "数字",
                                        "DECIMAL" to "金额（两位小数）",
                                    )
                                    .forEach { (type, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                vm.tableWorkspace.options(
                                                    options.copy(
                                                        columnTypes =
                                                            options.columnTypes +
                                                                ("$selected:$column" to type)
                                                    )
                                                )
                                                menu = false
                                            },
                                        )
                                    }
                            }
                        }
                    }
                }
                Box(Modifier.weight(.58f).horizontalScroll(rememberScrollState())) {
                    LazyColumn(
                        Modifier.width((table.rows.first().size * 144).dp),
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        items(table.rows.size) { r ->
                            Row {
                                table.rows[r].forEachIndexed { c, value ->
                                    val cell =
                                        table.cells.firstOrNull {
                                            r in it.row..it.rowEnd && c in it.column..it.columnEnd
                                        }
                                    val covered =
                                        cell != null && (cell.row != r || cell.column != c)
                                    Surface(
                                        Modifier.width(144.dp)
                                            .heightIn(min = 58.dp)
                                            .border(.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                            .clickable(enabled = !busy) {
                                                selectedCell =
                                                    (cell?.row ?: r) to (cell?.column ?: c)
                                                editing = selectedCell
                                                text = cell?.text ?: value
                                            },
                                        color =
                                            if (
                                                selectedCell ==
                                                    ((cell?.row ?: r) to (cell?.column ?: c))
                                            )
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else if (r == 0)
                                                MaterialTheme.colorScheme.surfaceContainerHigh
                                            else MaterialTheme.colorScheme.surface,
                                    ) {
                                        Text(
                                            if (covered) "↖ 合并" else value,
                                            Modifier.padding(12.dp),
                                            maxLines = 4,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }
    editing?.let { (r, c) ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("第 ${r+1} 行，第 ${c+1} 列") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    image?.getOrNull()?.let { bitmap ->
                        owner?.let { cell ->
                            val x =
                                (cell.box.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                            val y =
                                (cell.box.top * bitmap.height)
                                    .toInt()
                                    .coerceIn(0, bitmap.height - 1)
                            val w =
                                ((cell.box.width() * bitmap.width).toInt()).coerceIn(
                                    1,
                                    bitmap.width - x,
                                )
                            val h =
                                ((cell.box.height() * bitmap.height).toInt()).coerceIn(
                                    1,
                                    bitmap.height - y,
                                )
                            val crop =
                                remember(bitmap, cell.box) {
                                    Bitmap.createBitmap(bitmap, x, y, w, h)
                                }
                            DisposableEffect(crop) {
                                onDispose { if (crop !== bitmap) crop.recycle() }
                            }
                            Image(
                                crop.asImageBitmap(),
                                "原件单元格",
                                Modifier.fillMaxWidth().heightIn(max = 150.dp),
                            )
                        }
                    }
                    OutlinedTextField(text, { text = it }, label = { Text("单元格内容") })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.tableWorkspace.cell(selected, r, c, text)
                        editing = null
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
    if (exportDialog)
        AlertDialog(
            onDismissRequest = { exportDialog = false },
            title = { Text("导出表格") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("XLSX", "CSV", "TSV").forEach { format ->
                            FilterChip(
                                selected = options.format == format,
                                onClick = {
                                    vm.tableWorkspace.options(options.copy(format = format))
                                },
                                label = { Text(format) },
                            )
                        }
                    }
                    Text(
                        "默认文本列保留编号和金额原文。数字 / 金额列只转换能解析的值。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (options.format != "XLSX") {
                        if (options.format == "CSV")
                            FlowRow {
                                listOf("," to "逗号", ";" to "分号", "\t" to "Tab").forEach {
                                    (value, label) ->
                                    val separator = value
                                    FilterChip(
                                        selected = options.separator == separator,
                                        onClick = {
                                            vm.tableWorkspace.options(
                                                options.copy(separator = separator)
                                            )
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        FlowRow {
                            listOf(
                                    "UTF8_BOM" to "UTF-8",
                                    "UTF16LE" to "UTF-16",
                                    "GB18030" to "GB18030",
                                )
                                .forEach { (value, label) ->
                                    FilterChip(
                                        selected = options.encoding == value,
                                        onClick = {
                                            vm.tableWorkspace.options(
                                                options.copy(encoding = value)
                                            )
                                        },
                                        label = { Text(label) },
                                    )
                                }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                options.excelText,
                                { vm.tableWorkspace.options(options.copy(excelText = it)) },
                            )
                            Text("Excel 文本包装", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            "开启后用常量文本公式保护前导零；原始值仍保存在结构 JSON。CSV/TSV 打包导出。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        exportDialog = false
                        vm.tableWorkspace.export(options)
                    },
                    enabled = !busy,
                ) {
                    Text("生成")
                }
            },
            dismissButton = { TextButton(onClick = { exportDialog = false }) { Text("取消") } },
        )
}

private fun tableSource(file: File, page: Int): Bitmap {
    if (!file.extension.equals("pdf", true)) return ScanProcessor.decode(file, 2200)
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            renderer.openPage((page - 1).coerceIn(0, renderer.pageCount - 1)).use { source ->
                val scale = 2200f / maxOf(source.width, source.height)
                Bitmap.createBitmap(
                        (source.width * scale).toInt(),
                        (source.height * scale).toInt(),
                        Bitmap.Config.ARGB_8888,
                    )
                    .also {
                        it.eraseColor(android.graphics.Color.WHITE)
                        source.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
            }
        }
    }
}

@Composable
private fun TableSourceCanvas(
    bitmap: Bitmap,
    table: PhoneTables.Table,
    page: Int,
    selected: PhoneTables.Cell?,
    selecting: Boolean,
    region: OcrRegion,
    onRegion: (OcrRegion) -> Unit,
    onCell: (PhoneTables.Cell) -> Unit,
) {
    val color = MaterialTheme.colorScheme.primary
    val boxes = table.cells.filter { (it.sourcePage ?: table.page) == page }.map { it.box }
    val view =
        if (selecting || boxes.isEmpty()) OcrRegion(0f, 0f, 1f, 1f)
        else
            OcrRegion(
                (boxes.minOf { it.left } - .02f).coerceAtLeast(0f),
                (boxes.minOf { it.top } - .02f).coerceAtLeast(0f),
                (boxes.maxOf { it.right } + .02f).coerceAtMost(1f),
                (boxes.maxOf { it.bottom } + .02f).coerceAtMost(1f),
            )
    val left = (view.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
    val top = (view.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
    val width = ((view.right * bitmap.width).toInt() - left).coerceIn(1, bitmap.width - left)
    val height = ((view.bottom * bitmap.height).toInt() - top).coerceIn(1, bitmap.height - top)
    val actual =
        OcrRegion(
            left.toFloat() / bitmap.width,
            top.toFloat() / bitmap.height,
            (left + width).toFloat() / bitmap.width,
            (top + height).toFloat() / bitmap.height,
        )
    val display =
        remember(bitmap, left, top, width, height) {
            Bitmap.createBitmap(bitmap, left, top, width, height)
        }
    DisposableEffect(display) { onDispose { if (display !== bitmap) display.recycle() } }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        val ratio = display.width.toFloat() / display.height
        val frameModifier =
            if (maxWidth / maxHeight > ratio) Modifier.fillMaxHeight().aspectRatio(ratio)
            else Modifier.fillMaxWidth().aspectRatio(ratio)
        Box(frameModifier) {
            Image(display.asImageBitmap(), "表格原件，第 $page 页", Modifier.fillMaxSize())
            Canvas(
                Modifier.fillMaxSize().pointerInput(selecting, table, page, actual) {
                    if (selecting)
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val first =
                                PointN(down.position.x / size.width, down.position.y / size.height)
                            onRegion(OcrRegion.between(first, first))
                            drag(down.id) { change ->
                                change.consume()
                                onRegion(
                                    OcrRegion.between(
                                        first,
                                        PointN(
                                            change.position.x / size.width,
                                            change.position.y / size.height,
                                        ),
                                    )
                                )
                            }
                        }
                    else
                        detectTapGestures { position ->
                            table.cells
                                .firstOrNull {
                                    (it.sourcePage ?: table.page) == page &&
                                        it.box.contains(
                                            actual.left + position.x / size.width * actual.width,
                                            actual.top + position.y / size.height * actual.height,
                                        )
                                }
                                ?.let(onCell)
                        }
                }
            ) {
                table.cells
                    .filter { (it.sourcePage ?: table.page) == page }
                    .forEach { cell ->
                        val b = cell.box
                        drawRect(
                            color.copy(alpha = if (cell == selected) .9f else .35f),
                            Offset(
                                (b.left - actual.left) / actual.width * size.width,
                                (b.top - actual.top) / actual.height * size.height,
                            ),
                            Size(
                                b.width() / actual.width * size.width,
                                b.height() / actual.height * size.height,
                            ),
                            style = Stroke(if (cell == selected) 3.dp.toPx() else 1.dp.toPx()),
                        )
                    }
                if (selecting)
                    drawRect(
                        color,
                        Offset(region.left * size.width, region.top * size.height),
                        Size(region.width * size.width, region.height * size.height),
                        style = Stroke(2.dp.toPx()),
                    )
            }
        }
    }
}
