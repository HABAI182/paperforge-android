package cn.paperforge.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import java.io.File
import kotlinx.coroutines.*

/** Small native sessions shared by grid thumbnails; UI owns a copy of each cached bitmap. */
private class AssemblyPreviewPool {
    private val sessions = LinkedHashMap<String, PdfSession>(4, .75f, true)
    private var closed = false

    @Synchronized
    fun render(key: String, file: File, page: Int, rotation: Int): Bitmap {
        check(!closed)
        val source = sessions.getOrPut(key) { PdfSession(file, 8 * 1024 * 1024) }
        while (sessions.size > 2) {
            val first = sessions.entries.first()
            sessions.remove(first.key)
            first.value.close()
        }
        val cached = source.render(page, 360)
        return if (rotation % 360 == 0) cached.copy(Bitmap.Config.ARGB_8888, false)
        else
            Bitmap.createBitmap(
                cached,
                0,
                0,
                cached.width,
                cached.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true,
            )
    }

    @Synchronized
    fun close() {
        closed = true
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfAssemblyScreen(vm: MainViewModel, onImport: () -> Unit) {
    val controller = vm.assembly
    val plan by controller.plan.collectAsState()
    val loading by controller.loading.collectAsState()
    val busy by vm.busy.collectAsState()
    val canUndo by controller.canUndo.collectAsState()
    val canRedo by controller.canRedo.collectAsState()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var picker by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var duplex by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var order by remember(plan.pages) { mutableStateOf(plan.pages) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    val grid = rememberLazyGridState()
    val currentOrder by rememberUpdatedState(order)
    val currentPlan by rememberUpdatedState(plan)
    val pool = remember { AssemblyPreviewPool() }
    DisposableEffect(pool) { onDispose { pool.close() } }
    LaunchedEffect(plan.pages) { selected = selected.intersect(plan.pages.map { it.key }.toSet()) }
    val enabled = !busy && !loading
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "页面组合 · ${plan.pages.size+plan.prefixPages} 页",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = vm::closeAssembly) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = controller::undo, enabled = canUndo && enabled) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "撤销组合操作")
                    }
                    IconButton(onClick = controller::redo, enabled = canRedo && enabled) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "重做组合操作")
                    }
                    TextButton(
                        onClick = vm::exportAssembly,
                        enabled = enabled && (plan.pages.isNotEmpty() || plan.cover),
                    ) {
                        Text("生成 PDF")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.navigationBarsPadding().fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(
                    onClick = {
                        selected =
                            if (selected.size == plan.pages.size) emptySet()
                            else plan.pages.map { it.key }.toSet()
                    },
                    enabled = enabled && plan.pages.isNotEmpty(),
                ) {
                    Text(
                        if (selected.size == plan.pages.size && selected.isNotEmpty()) "取消全选"
                        else "全选"
                    )
                }
                TextButton(
                    onClick = { controller.rotate(selected) },
                    enabled = enabled && selected.isNotEmpty(),
                ) {
                    Text("旋转")
                }
                TextButton(
                    onClick = { controller.remove(selected) },
                    enabled = enabled && selected.isNotEmpty(),
                ) {
                    Text("移除")
                }
                Box {
                    TextButton(onClick = { more = true }, enabled = enabled) { Text("更多") }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(
                            text = { Text("移动所选页到…") },
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                more = false
                                moving = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("复制所选页") },
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                more = false
                                controller.duplicate(selected)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (selected.isEmpty()) "反序全部内容页" else "反序所选页") },
                            enabled = plan.pages.isNotEmpty(),
                            onClick = {
                                more = false
                                controller.reverse(selected)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("插入空白页") },
                            onClick = {
                                more = false
                                controller.blank(plan.pages.lastOrNull { it.key in selected }?.key)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("清空组合草稿") },
                            onClick = {
                                more = false
                                controller.change(AssemblyPlan())
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { picker = true }, enabled = enabled) {
                        Text("添加来源")
                    }
                    OutlinedButton(
                        onClick = { sources = true },
                        enabled = enabled && plan.sources.isNotEmpty(),
                    ) {
                        Text("来源与页码")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { duplex = true },
                        enabled = enabled && plan.chapters.size >= 2,
                    ) {
                        Text("正反交错")
                    }
                    TextButton(onClick = { options = true }, enabled = enabled) { Text("封面与目录") }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                if (selected.isEmpty())
                    (if (plan.formMode == "EDITABLE") "长按拖动页序；保留可编辑表单。" else "长按拖动页序；表单仅保留外观。")
                else "已选 ${selected.size} 页",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            if (plan.pages.isEmpty() && !plan.cover) {
                Column(
                    Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.LibraryAdd, null, Modifier.size(48.dp))
                    Text("把不同 PDF 的页面放在一起", style = MaterialTheme.typography.titleMedium)
                    Text("选择已有文档，或导入多份 PDF。", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { picker = true }, enabled = enabled) { Text("选择来源") }
                }
            } else
                LazyVerticalGrid(
                    GridCells.Adaptive(110.dp),
                    state = grid,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier =
                        Modifier.fillMaxSize().pointerInput(enabled, plan.prefixPages) {
                            if (enabled)
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { point ->
                                        pointer = point
                                        dragging =
                                            grid.layoutInfo.visibleItemsInfo
                                                .firstOrNull {
                                                    point.x >= it.offset.x &&
                                                        point.x <= it.offset.x + it.size.width &&
                                                        point.y >= it.offset.y &&
                                                        point.y <= it.offset.y + it.size.height
                                                }
                                                ?.key as? String
                                        if (dragging?.startsWith("generated:") == true)
                                            dragging = null
                                    },
                                    onDragEnd = {
                                        if (dragging != null)
                                            controller.change(
                                                currentPlan.copy(pages = currentOrder)
                                            )
                                        dragging = null
                                    },
                                    onDragCancel = {
                                        order = currentPlan.pages
                                        dragging = null
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    pointer += amount
                                    val target =
                                        grid.layoutInfo.visibleItemsInfo
                                            .firstOrNull {
                                                pointer.x >= it.offset.x &&
                                                    pointer.x <= it.offset.x + it.size.width &&
                                                    pointer.y >= it.offset.y &&
                                                    pointer.y <= it.offset.y + it.size.height
                                            }
                                            ?.key as? String
                                    val from = currentOrder.indexOfFirst { it.key == dragging }
                                    val to = currentOrder.indexOfFirst { it.key == target }
                                    if (from >= 0 && to >= 0 && from != to)
                                        order =
                                            currentOrder.toMutableList().apply {
                                                add(to, removeAt(from))
                                            }
                                }
                        },
                ) {
                    if (plan.cover)
                        item(key = "generated:cover") {
                            Card(onClick = { options = true }) {
                                Column(
                                    Modifier.padding(12.dp).height(190.dp),
                                    verticalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("封面", style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        plan.title,
                                        maxLines = 4,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text("1", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    if (plan.contentsPages > 0)
                        item(key = "generated:contents") {
                            Card(onClick = { options = true }) {
                                Column(
                                    Modifier.padding(12.dp).height(190.dp),
                                    verticalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("可点击目录", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${plan.chapters.size} 个来源 · ${plan.contentsPages} 页",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "${if(plan.cover) 2 else 1} 起",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    itemsIndexed(order, key = { _, page -> page.key }) { position, page ->
                        val source = plan.sources.firstOrNull { it.documentId == page.sourceId }
                        val preview by
                            produceState<Pair<Bitmap?, Boolean>>(
                                null to false,
                                page.sourceId,
                                page.page,
                                page.rotation,
                                source?.sha256,
                            ) {
                                value =
                                    withContext(Dispatchers.IO) {
                                        try {
                                            (if (page.sourceId == null) null
                                            else
                                                vm.repo.dao.document(page.sourceId)?.pdfPath?.let {
                                                    pool.render(
                                                        page.sourceId + source?.sha256,
                                                        File(it),
                                                        page.page,
                                                        page.rotation,
                                                    )
                                                }) to true
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (error: Exception) {
                                            null to true
                                        }
                                    }
                            }
                        val currentBitmap = preview.first
                        DisposableEffect(currentBitmap) { onDispose { currentBitmap?.recycle() } }
                        Card(
                            onClick = {
                                selected =
                                    if (page.key in selected) selected - page.key
                                    else selected + page.key
                            },
                            border =
                                BorderStroke(
                                    if (page.key in selected || dragging == page.key) 3.dp
                                    else 1.dp,
                                    if (page.key in selected || dragging == page.key)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                        ) {
                            Column(
                                Modifier.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().height(145.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (currentBitmap != null)
                                        Image(
                                            currentBitmap.asImageBitmap(),
                                            "组合第 ${position+plan.prefixPages+1} 页",
                                            Modifier.fillMaxSize(),
                                        )
                                    else
                                        Text(
                                            if (page.sourceId == null) "空白页"
                                            else if (preview.second) "无法预览，请刷新来源" else "预览加载中",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        page.key in selected,
                                        { checked ->
                                            selected =
                                                if (checked) selected + page.key
                                                else selected - page.key
                                        },
                                    )
                                    Text("${position+plan.prefixPages+1}")
                                }
                                Text(
                                    if (source == null) "空白页"
                                    else "${source.title} · ${page.page+1}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
        }
    }
    if (picker)
        AssemblySourcePicker(
            vm,
            onImport = {
                picker = false
                onImport()
            },
            onDismiss = { picker = false },
        ) { ids ->
            picker = false
            controller.addSources(ids)
        }
    if (sources) AssemblySourcesDialog(vm, plan, onDismiss = { sources = false })
    if (options)
        AssemblyOptions(plan, onDismiss = { options = false }) {
            controller.change(it)
            options = false
        }
    if (duplex)
        AssemblyDuplexDialog(plan, onDismiss = { duplex = false }) { first, second, reverse ->
            controller.interleave(first, second, reverse)
            duplex = false
        }
    if (moving)
        AssemblyMoveDialog(
            plan.prefixPages + 1,
            plan.prefixPages + plan.pages.size - selected.size + 1,
            { moving = false },
        ) { number ->
            controller.move(selected, number - plan.prefixPages - 1)
            moving = false
        }
}

@Composable
private fun AssemblyMoveDialog(
    minimum: Int,
    maximum: Int,
    onDismiss: () -> Unit,
    onChoose: (Int) -> Unit,
) {
    var position by remember { mutableStateOf(minimum.toString()) }
    val number = position.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动所选页") },
        text = {
            Column {
                Text("移至组合第几页（$minimum–$maximum），所选页按当前顺序成组移动。")
                OutlinedTextField(
                    position,
                    { position = it },
                    singleLine = true,
                    label = { Text("目标页码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onChoose(number!!) },
                enabled = number != null && number in minimum..maximum,
            ) {
                Text("移动")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AssemblySourcePicker(
    vm: MainViewModel,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
    onChoose: (List<String>) -> Unit,
) {
    val plan by vm.assembly.plan.collectAsState()
    val documents by
        produceState<List<DocumentRow>>(emptyList()) {
            value =
                withContext(Dispatchers.IO) {
                    vm.repo.dao
                        .allDocuments()
                        .filter { it.kind == "pdf" && it.trashedAt == null }
                        .sortedByDescending { it.modified }
                }
        }
    var selected by remember { mutableStateOf(emptyList<String>()) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入 PDF 来源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("搜索文档或文件夹") },
                    singleLine = true,
                )
                TextButton(onClick = onImport) { Text("从手机导入 PDF") }
                if (documents.isEmpty()) Text("扫描草稿可先导出为 PDF 后加入。")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(
                        documents.filter {
                            query.isBlank() ||
                                it.title.contains(query, true) ||
                                it.folder.contains(query, true)
                        },
                        key = { it.id },
                    ) { doc ->
                        val added = plan.sources.any { it.documentId == doc.id }
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !added) {
                                    selected =
                                        if (doc.id in selected) selected - doc.id
                                        else selected + doc.id
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                doc.id in selected || added,
                                {
                                    if (!added)
                                        selected =
                                            if (doc.id in selected) selected - doc.id
                                            else selected + doc.id
                                },
                                enabled = !added,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(doc.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (added) "已加入"
                                    else if (!doc.listed) "导出结果" else doc.folder.ifBlank { "文档库" },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(selected) }, enabled = selected.isNotEmpty()) {
                Text("加入 ${selected.size} 份")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AssemblySourcesDialog(vm: MainViewModel, plan: AssemblyPlan, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<AssemblySource?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("来源与选页") },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(plan.sources, key = { it.documentId }) { source ->
                    Column {
                        Text(source.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "已选 ${plan.pages.count {it.sourceId==source.documentId}} / ${source.count} 页",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            TextButton(onClick = { editing = source }) { Text("标题与页码") }
                            TextButton(onClick = { vm.assembly.refresh(source.documentId) }) {
                                Text("刷新来源")
                            }
                            TextButton(
                                onClick = {
                                    vm.assembly.change(
                                        plan.copy(
                                            sources = plan.sources - source,
                                            pages =
                                                plan.pages.filter {
                                                    it.sourceId != source.documentId
                                                },
                                        )
                                    )
                                }
                            ) {
                                Text("移除来源")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
    editing?.let { source ->
        val initial =
            remember(source.documentId) {
                plan.pages
                    .filter { it.sourceId == source.documentId }
                    .joinToString(",") { "${it.page+1}" }
            }
        var title by remember(source.documentId) { mutableStateOf(source.title) }
        var range by remember(source.documentId) { mutableStateOf(initial) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("来源标题与页码") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text("目录中的来源名称") })
                    OutlinedTextField(
                        range,
                        {
                            range = it
                            error = null
                        },
                        label = { Text("例如1-3,6；留空选全部") },
                        isError = error != null,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        error =
                            vm.assembly.sourcePages(
                                source.documentId,
                                title,
                                range.takeIf { it != initial },
                            )
                        if (error == null) editing = null
                    }
                ) {
                    Text("应用")
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun AssemblyOptions(
    plan: AssemblyPlan,
    onDismiss: () -> Unit,
    onChoose: (AssemblyPlan) -> Unit,
) {
    var title by remember { mutableStateOf(plan.title) }
    var subtitle by remember { mutableStateOf(plan.subtitle) }
    var cover by remember { mutableStateOf(plan.cover) }
    var contents by remember { mutableStateOf(plan.contents) }
    var editable by remember { mutableStateOf(plan.formMode == "EDITABLE") }
    var labels by remember { mutableStateOf(plan.keepPageLabels) }
    var attachments by remember { mutableStateOf(plan.keepAttachments) }
    var outlines by remember { mutableStateOf(plan.keepOriginalOutlines) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("封面与目录") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("文档名称 / 封面标题") })
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(cover, role = Role.Checkbox, onValueChange = { cover = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(cover, null)
                    Text("添加简洁封面")
                }
                if (cover)
                    OutlinedTextField(subtitle, { subtitle = it }, label = { Text("封面副标题，可留空") })
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(
                            contents,
                            role = Role.Checkbox,
                            onValueChange = { contents = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(contents, null)
                    Text("添加可点击目录")
                }
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(
                            editable,
                            role = Role.Checkbox,
                            onValueChange = { editable = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(editable, null)
                    Text("保留可编辑表单")
                }
                Text(
                    if (editable) "同名字段自动编号；复制页可独立填写。" else "表单保存为可见外观，副本不再填写。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(labels, role = Role.Checkbox, onValueChange = { labels = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(labels, null)
                    Text("保留来源页码标签")
                }
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(
                            outlines,
                            role = Role.Checkbox,
                            onValueChange = { outlines = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(outlines, null)
                    Text("保留来源原目录")
                }
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(
                            attachments,
                            role = Role.Checkbox,
                            onValueChange = { attachments = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(attachments, null)
                    Text("保留来源内嵌附件")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onChoose(
                        plan.copy(
                            title = title.ifBlank { "组合文档" },
                            subtitle = subtitle,
                            cover = cover,
                            contents = contents,
                            formMode = if (editable) "EDITABLE" else "APPEARANCE",
                            keepPageLabels = labels,
                            keepAttachments = attachments,
                            keepOriginalOutlines = outlines,
                        )
                    )
                }
            ) {
                Text("应用")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AssemblyDuplexDialog(
    plan: AssemblyPlan,
    onDismiss: () -> Unit,
    onChoose: (String, String, Boolean) -> Unit,
) {
    var first by remember { mutableStateOf(plan.chapters[0].documentId) }
    var second by remember { mutableStateOf(plan.chapters[1].documentId) }
    var reverse by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复双面扫描顺序") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将正面与反面逐页交错，多出的页面保留在末尾。", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { menu = 1 }) {
                        Text("正面：" + plan.sources.first { it.documentId == first }.title)
                    }
                    DropdownMenu(menu == 1, { menu = 0 }) {
                        plan.chapters.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.title) },
                                onClick = {
                                    first = source.documentId
                                    menu = 0
                                },
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { menu = 2 }) {
                        Text("反面：" + plan.sources.first { it.documentId == second }.title)
                    }
                    DropdownMenu(menu == 2, { menu = 0 }) {
                        plan.chapters.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.title) },
                                onClick = {
                                    second = source.documentId
                                    menu = 0
                                },
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(
                            reverse,
                            role = Role.Checkbox,
                            onValueChange = { reverse = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(reverse, null)
                    Text("先将反面反序（末页先扫时）")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(first, second, reverse) }, enabled = first != second) {
                Text("交错排列")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
