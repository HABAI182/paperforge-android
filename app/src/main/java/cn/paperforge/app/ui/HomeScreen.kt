package cn.paperforge.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.UserSettings
import cn.paperforge.app.data.DocumentRow
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onImages: () -> Unit,
    onPdf: () -> Unit,
    onMerge: () -> Unit,
    onAbout: () -> Unit,
    onRestoreBackup: () -> Unit = {},
    onPdfTool: (String) -> Unit = { onPdf() },
    onOcrImages: () -> Unit = onImages,
) {
    val docs by vm.documents.collectAsState()
    val busy by vm.busy.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var toolQuery by rememberSaveable { mutableStateOf("") }
    val search by vm.query.collectAsState()
    val trash by vm.trash.collectAsState()
    var showTrash by remember { mutableStateOf(false) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf("") }
    var categorizing by remember { mutableStateOf<DocumentRow?>(null) }
    var deleting by remember { mutableStateOf<DocumentRow?>(null) }
    var renaming by remember { mutableStateOf<DocumentRow?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var bulkDelete by remember { mutableStateOf(emptyList<String>()) }
    var bulkCategory by remember { mutableStateOf(emptyList<String>()) }
    var selectionMenu by remember { mutableStateOf(false) }
    val inTrash = showTrash && tab == 1
    val filtered =
        (if (inTrash) trash else docs)
            .filter {
                (tab == 0 || !favoritesOnly || it.favorite) &&
                    (tab == 0 || folder.isEmpty() || it.folder == folder) &&
                    (!inTrash ||
                        search.isBlank() ||
                        listOf(it.title, it.folder, it.tags).any { value ->
                            value.contains(search, true)
                        })
            }
            .let {
                if (tab == 0)
                    it.sortedByDescending { row -> maxOf(row.lastOpened, row.modified) }
                        .take(UserSettings.number("recentCount", 6f).toInt())
                else it
            }
    val chosen = filtered.filter { it.id in selection }
    fun finishSelection() {
        selection = emptyList()
        selecting = false
        selectionMenu = false
    }
    fun toggle(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }
    BackHandler(selecting) { finishSelection() }
    LaunchedEffect(tab, search, showTrash, favoritesOnly, folder) { finishSelection() }
    Scaffold(
        topBar = {
            if (selecting)
                TopAppBar(
                    title = { Text("已选 ${chosen.size} 项") },
                    navigationIcon = {
                        IconButton(onClick = { finishSelection() }) {
                            Icon(Icons.Default.Close, "退出多选")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                selection =
                                    if (chosen.size == filtered.size) emptyList()
                                    else filtered.map { it.id }
                            },
                            enabled = !busy,
                        ) {
                            Text(
                                if (chosen.size == filtered.size && filtered.isNotEmpty()) "取消全选"
                                else "全选"
                            )
                        }
                    },
                )
        },
        bottomBar = {
            if (selecting) {
                Surface(tonalElevation = 4.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        if (inTrash) {
                            ToolAction(Icons.Default.Restore, "恢复", chosen.isNotEmpty() && !busy) {
                                vm.restoreDocuments(chosen.map { it.id }.toSet())
                                finishSelection()
                            }
                        } else {
                            ToolAction(Icons.Default.Share, "分享", chosen.isNotEmpty() && !busy) {
                                vm.shareDocuments(chosen.map { it.id })
                                finishSelection()
                            }
                            ToolAction(
                                Icons.Default.DeleteOutline,
                                "回收站",
                                chosen.isNotEmpty() && !busy,
                            ) {
                                vm.trashDocuments(chosen.map { it.id }.toSet())
                                finishSelection()
                            }
                        }
                        ToolAction(
                            Icons.Default.DeleteForever,
                            "直接删除",
                            chosen.isNotEmpty() && !busy,
                        ) {
                            bulkDelete = chosen.map { it.id }
                        }
                        if (!inTrash)
                            Box {
                                ToolAction(
                                    Icons.Default.MoreHoriz,
                                    "更多",
                                    chosen.isNotEmpty() && !busy,
                                ) {
                                    selectionMenu = true
                                }
                                DropdownMenu(selectionMenu, { selectionMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("收藏所选") },
                                        onClick = {
                                            vm.favoriteDocuments(chosen.map { it.id }.toSet(), true)
                                            finishSelection()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("取消收藏") },
                                        onClick = {
                                            vm.favoriteDocuments(
                                                chosen.map { it.id }.toSet(),
                                                false,
                                            )
                                            finishSelection()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("文件夹与标签") },
                                        onClick = {
                                            bulkCategory = chosen.map { it.id }
                                            selectionMenu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("合并 PDF") },
                                        enabled =
                                            chosen.size >= 2 && chosen.all { it.kind == "pdf" },
                                        onClick = {
                                            vm.mergeDocuments(chosen.map { it.id })
                                            finishSelection()
                                        },
                                    )
                                }
                            }
                    }
                }
            } else
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    listOf(
                            "首页" to Icons.Default.Home,
                            "文档" to Icons.AutoMirrored.Filled.Article,
                            "转换" to Icons.Default.SwapHoriz,
                            "工具" to Icons.Default.GridView,
                        )
                        .forEachIndexed { i, (label, icon) ->
                            NavigationBarItem(
                                selected = tab == (if (i == 3) 2 else i) && i != 2,
                                onClick = {
                                    if (i == 2) vm.screen.value = "convert"
                                    else tab = if (i == 3) 2 else i
                                    if (i != 1) vm.query.value = ""
                                },
                                icon = { Icon(icon, label) },
                                label = { Text(label) },
                                colors =
                                    NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                    ),
                            )
                        }
                }
        },
        floatingActionButton = {
            if (tab != 0 && !selecting)
                ExtendedFloatingActionButton(
                    onClick = { if (!busy) vm.newScan() },
                    icon = { Icon(Icons.Default.DocumentScanner, null) },
                    text = { Text("扫描") },
                )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().testTag("document-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "纸匠",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "PAPERFORGE  /  离线文档工作台",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.screen.value = "settings" }) {
                        Icon(Icons.Default.Settings, "个性化设置")
                    }
                    IconButton(onClick = onAbout) { Icon(Icons.Default.Info, "关于与隐私") }
                }
            }
            if (tab == 0) {
                item {
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                        shape = RoundedCornerShape(26.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "扫描文档",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "自动裁边 · 连续拍摄",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button(
                                onClick = { vm.newScan() },
                                enabled = !busy,
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = MaterialTheme.colorScheme.primary,
                                    ),
                            ) {
                                Icon(Icons.Default.DocumentScanner, null)
                                Spacer(Modifier.width(8.dp))
                                Text("开始扫描")
                            }
                        }
                    }
                }
                val shortcuts =
                    UserSettings.string("homeTools", "images,pdf,ocr,merge,compress,tools")
                        .split(',')
                        .filter {
                            it in setOf("images", "pdf", "ocr", "merge", "compress", "tools")
                        }
                        .distinct()
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        shortcuts.forEach { key ->
                            val (label, icon, action) =
                                when (key) {
                                    "images" ->
                                        Triple("相册导入", Icons.Default.AddPhotoAlternate, onImages)
                                    "pdf" -> Triple("打开 PDF", Icons.Default.PictureAsPdf, onPdf)
                                    "ocr" -> Triple("图片识字", Icons.Default.TextFields, onOcrImages)
                                    "merge" -> Triple("合并 PDF", Icons.Default.Merge, onMerge)
                                    "compress" ->
                                        Triple(
                                            "压缩 PDF",
                                            Icons.Default.Compress,
                                            { onPdfTool("compress") },
                                        )
                                    else -> Triple("全部工具", Icons.Default.Handyman, { tab = 2 })
                                }
                            QuickCard(label, icon, Modifier.width(112.dp), !busy, action)
                        }
                    }
                }
            }
            if (tab == 2) {
                item {
                    Text(
                        "小工具，大用处",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("搜索名称，直接选择文件开始。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    OutlinedTextField(
                        toolQuery,
                        { toolQuery = it },
                        label = { Text("搜索工具，如水印、压缩、备份") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val toolEntries =
                    listOf(
                        HomeTool("转换中心", "PDF、图片、文字、Office 与表格", Icons.Default.SwapHoriz) {
                            vm.screen.value = "convert"
                        },
                        HomeTool("合并 PDF", "选择多份文件并调整顺序", Icons.Default.Merge, onMerge),
                        HomeTool("图片转 PDF", "排序、裁边、滤镜和纸张尺寸", Icons.Default.Collections, onImages),
                        HomeTool("离线 OCR 图片识字", "导入图片，识别中英文", Icons.Default.Translate, onOcrImages),
                        HomeTool("抽页 / 拆分 / 旋转", "输入页码创建新副本", Icons.Default.ContentCut) {
                            onPdfTool("range")
                        },
                        HomeTool("压缩 PDF", "保留文字、链接和表单", Icons.Default.Compress) {
                            onPdfTool("compress")
                        },
                        HomeTool("PDF 批注与手写签名", "画笔、荧光笔、形状和文字", Icons.Default.Draw) {
                            onPdfTool("annotate")
                        },
                        HomeTool("离线备份", "原图、PDF、OCR、分类与编辑历史", Icons.Default.Backup, vm::backup),
                        HomeTool("恢复备份", "选择纸匠 ZIP 并保留现有文档", Icons.Default.Restore, onRestoreBackup),
                    ) +
                        listOf(
                                "searchable" to "扫描 PDF 转可搜索 PDF",
                                "watermark" to "添加中文水印",
                                "number" to "添加页码",
                                "crop" to "裁切 PDF 页面边缘",
                                "blank" to "插入空白页",
                                "nup2" to "2 合 1 拼版",
                                "nup4" to "4 合 1 拼版",
                                "encrypt" to "PDF 密码加密",
                                "forms" to "填写 PDF 表单",
                                "metadata" to "修改标题与作者",
                                "extract-images" to "提取嵌入图片",
                                "txt" to "提取 PDF 文字",
                                "docx" to "PDF 转 Word 段落",
                                "images-jpg" to "PDF 转 JPG 图片包",
                                "images-png" to "PDF 转 PNG 图片包",
                            )
                            .map { (mode, title) ->
                                HomeTool(title, "选择 PDF 后设置参数", Icons.Default.PictureAsPdf) {
                                    onPdfTool(mode)
                                }
                            }
                val found =
                    toolEntries.filter {
                        it.title.contains(toolQuery, true) ||
                            it.description.contains(toolQuery, true)
                    }
                if (found.isEmpty()) item { Text("没有找到匹配的工具", Modifier.padding(24.dp)) }
                items(found, key = { it.title }) { tool ->
                    ToolCard(tool.title, tool.description, tool.icon, !busy, tool.action)
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (tab == 0) "最近文档" else "我的文档",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (!selecting) TextButton(onClick = { selecting = true }) { Text("多选") }
                        if (tab == 0) TextButton(onClick = { tab = 1 }) { Text("全部文档") }
                        else
                            Text(
                                "${if(inTrash) trash.size else docs.size} 份",
                                style = MaterialTheme.typography.labelMedium,
                            )
                    }
                }
                item {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { vm.query.value = it },
                        label = { Text("搜索文档与文字") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                if (tab == 1) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                favoritesOnly,
                                { favoritesOnly = !favoritesOnly },
                                label = { Text("收藏") },
                            )
                            FilterChip(
                                showTrash,
                                { showTrash = !showTrash },
                                label = { Text("回收站 ${trash.size}") },
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = folder,
                            onValueChange = { folder = it },
                            label = { Text("按文件夹筛选，留空显示全部") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (filtered.isEmpty())
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 34.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Default.Inbox,
                                null,
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                if (search.isEmpty()) "第一份文档，从这里开始" else "没有匹配的文档",
                                modifier = Modifier.padding(10.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (search.isEmpty())
                                Text("扫描、导入，全程留在设备。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                items(filtered, key = { it.id }) { doc ->
                    Card(
                        modifier =
                            Modifier.combinedClickable(
                                enabled = !busy,
                                onClick = {
                                    if (selecting) toggle(doc.id)
                                    else if (inTrash) vm.restore(doc) else vm.open(doc)
                                },
                                onLongClick = {
                                    selecting = true
                                    toggle(doc.id)
                                },
                            ),
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    if (doc.id in selection && selecting)
                                        MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(
                                    if (UserSettings.bool("compactRows", false)) 8.dp else 14.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selecting)
                                Checkbox(
                                    checked = doc.id in selection,
                                    onCheckedChange = { if (!busy) toggle(doc.id) },
                                    enabled = !busy,
                                    modifier =
                                        Modifier.semantics {
                                            contentDescription = "选择文档 ${doc.title}"
                                        },
                                )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Icon(
                                    if (doc.kind == "scan") Icons.Default.Description
                                    else Icons.Default.PictureAsPdf,
                                    null,
                                    modifier = Modifier.padding(14.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(
                                    (if (doc.favorite) "★ " else "") + doc.title,
                                    maxLines = 2,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    (if (doc.kind == "scan") "扫描草稿" else "PDF 文档") +
                                        " · " +
                                        SimpleDateFormat("MM/dd HH:mm", Locale.CHINA)
                                            .format(
                                                Date(
                                                    if (tab == 0)
                                                        maxOf(doc.lastOpened, doc.modified)
                                                    else doc.modified
                                                )
                                            ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (doc.folder.isNotEmpty() || doc.tags.isNotEmpty())
                                    Text(
                                        listOf(doc.folder, doc.tags)
                                            .filter { it.isNotEmpty() }
                                            .joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                            }
                            var menu by remember { mutableStateOf(false) }
                            if (!selecting)
                                Box {
                                    IconButton(onClick = { menu = true }) {
                                        Icon(Icons.Default.MoreVert, "文档操作")
                                    }
                                    DropdownMenu(
                                        expanded = menu,
                                        onDismissRequest = { menu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("重命名") },
                                            onClick = {
                                                menu = false
                                                renaming = doc
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (doc.favorite) "取消收藏" else "收藏") },
                                            onClick = {
                                                menu = false
                                                vm.favorite(doc)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("文件夹与标签") },
                                            onClick = {
                                                menu = false
                                                categorizing = doc
                                            },
                                        )
                                        if (showTrash && tab == 1)
                                            DropdownMenuItem(
                                                text = { Text("恢复") },
                                                onClick = {
                                                    menu = false
                                                    vm.restore(doc)
                                                },
                                            )
                                        if (!(showTrash && tab == 1))
                                            DropdownMenuItem(
                                                text = { Text("移入回收站") },
                                                onClick = {
                                                    menu = false
                                                    vm.delete(doc)
                                                },
                                            )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "直接删除",
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                            onClick = {
                                                menu = false
                                                deleting = doc
                                            },
                                        )
                                    }
                                }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
    if (bulkDelete.isNotEmpty())
        AlertDialog(
            onDismissRequest = { bulkDelete = emptyList() },
            title = { Text("直接删除 ${bulkDelete.size} 份文档？") },
            text = { Text("永久删除所选文档及应用内原图，无法从回收站恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.purgeDocuments(bulkDelete.toSet())
                        bulkDelete = emptyList()
                        finishSelection()
                    },
                    enabled = !busy,
                ) {
                    Text("直接删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { bulkDelete = emptyList() }) { Text("取消") } },
        )
    if (bulkCategory.isNotEmpty()) {
        var targetFolder by remember { mutableStateOf("") }
        var targetTags by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { bulkCategory = emptyList() },
            title = { Text("分类 ${bulkCategory.size} 份文档") },
            text = {
                Column {
                    OutlinedTextField(
                        targetFolder,
                        { targetFolder = it },
                        label = { Text("文件夹，留空不改") },
                    )
                    OutlinedTextField(
                        targetTags,
                        { targetTags = it },
                        label = { Text("标签，逗号分隔；留空不改") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.categorizeDocuments(bulkCategory.toSet(), targetFolder, targetTags)
                        bulkCategory = emptyList()
                        finishSelection()
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { bulkCategory = emptyList() }) { Text("取消") } },
        )
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("直接删除「${d.title}」？") },
            text = { Text("永久删除应用内文档和原图，无法从回收站恢复。已另存或分享的文件保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.purge(d)
                        deleting = null
                    }
                ) {
                    Text("直接删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    renaming?.let { d ->
        var name by remember { mutableStateOf(d.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名文档") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.rename(d, name)
                        renaming = null
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }
    categorizing?.let { d ->
        var f by remember { mutableStateOf(d.folder) }
        var tags by remember { mutableStateOf(d.tags) }
        AlertDialog(
            onDismissRequest = { categorizing = null },
            title = { Text("文件夹与标签") },
            text = {
                Column {
                    OutlinedTextField(f, { f = it }, label = { Text("文件夹") })
                    OutlinedTextField(tags, { tags = it }, label = { Text("标签，逗号分隔") })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.categorize(d, f, tags)
                        categorizing = null
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { categorizing = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun QuickCard(
    label: String,
    icon: ImageVector,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ToolCard(
    title: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

private data class HomeTool(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val action: () -> Unit,
)
