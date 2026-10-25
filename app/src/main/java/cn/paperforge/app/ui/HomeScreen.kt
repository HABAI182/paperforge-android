package cn.paperforge.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.UserSettings
import cn.paperforge.app.data.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var tab by rememberSaveable { mutableIntStateOf(vm.homeTab.value) }
    LaunchedEffect(tab) { vm.homeTab.value = tab }
    var toolQuery by rememberSaveable { mutableStateOf("") }
    val search by vm.query.collectAsState()
    val facts by vm.libraryFacts.collectAsState()
    val allHits by vm.librarySearchHits.collectAsState()
    val hitsByDocument = remember(allHits) { allHits.groupBy { it.documentId } }
    val factsByDocument = remember(facts) { facts.associateBy { it.documentId } }
    val context = LocalContext.current
    val library = remember { LibraryPreferences(context) }
    val savedView = remember { library.view() }
    var collection by rememberSaveable { mutableStateOf(savedView.collection) }
    var sort by rememberSaveable { mutableStateOf(savedView.sort) }
    var tagQuery by rememberSaveable { mutableStateOf(savedView.tags) }
    var savedFilters by remember { mutableStateOf(library.saved()) }
    var savingFilter by remember { mutableStateOf(false) }
    var filterName by remember { mutableStateOf("") }
    var moreHits by remember { mutableStateOf<DocumentRow?>(null) }
    val trash by vm.trash.collectAsState()
    var showTrash by remember { mutableStateOf(false) }
    var favoritesOnly by rememberSaveable { mutableStateOf(savedView.favorites) }
    var folder by rememberSaveable { mutableStateOf(savedView.folder) }
    val annotated by
        produceState(emptySet<String>(), docs) {
            value =
                withContext(Dispatchers.IO) {
                    docs
                        .filter { doc ->
                            val f = File(vm.repo.folder(doc.id), "annotations.json")
                            f.exists() && f.readText().trim() !in setOf("", "[]")
                        }
                        .map { it.id }
                        .toSet()
                }
        }
    LaunchedEffect(docs) {
        docs.filter { it.kind == "pdf" }.forEach { LibraryIndexer.enqueue(context, it.id) }
    }
    LaunchedEffect(collection, sort, tagQuery, favoritesOnly, folder, search) {
        library.view(
            LibraryFilter(
                collection = collection,
                sort = sort,
                folder = folder,
                tags = tagQuery,
                favorites = favoritesOnly,
                query = search,
            )
        )
    }
    var categorizing by remember { mutableStateOf<DocumentRow?>(null) }
    var deleting by remember { mutableStateOf<DocumentRow?>(null) }
    var renaming by remember { mutableStateOf<DocumentRow?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var bulkDelete by remember { mutableStateOf(emptyList<String>()) }
    var bulkCategory by remember { mutableStateOf(emptyList<String>()) }
    var selectionMenu by remember { mutableStateOf(false) }
    val canUndoNames by vm.canUndoNames.collectAsState()
    val inTrash = showTrash && tab == 1
    val folderPath = libraryFolder(folder)
    val filtered =
        (if (inTrash) trash else docs)
            .filter {
                (tab == 0 || inTrash || !favoritesOnly || it.favorite) &&
                    (tab == 0 ||
                        inTrash ||
                        folderPath.isEmpty() ||
                        libraryFolder(it.folder) == folderPath ||
                        libraryFolder(it.folder).startsWith("$folderPath/")) &&
                    (tab == 0 ||
                        inTrash ||
                        tagQuery.isBlank() ||
                        it.tags.contains(tagQuery, true)) &&
                    (tab == 0 ||
                        inTrash ||
                        when (collection) {
                            "INBOX" -> it.folder.isBlank() && it.tags.isBlank()
                            "NO_OCR" ->
                                factsByDocument[it.id]?.let { f ->
                                    f.pageCount > 0 && f.recognizedPages < f.pageCount
                                } == true
                            "NEEDS_EXPORT" ->
                                it.kind == "scan" && factsByDocument[it.id]?.hasExport != true
                            "ANNOTATED" ->
                                it.id in annotated || (factsByDocument[it.id]?.annotations ?: 0) > 0
                            else -> true
                        }) &&
                    (!inTrash ||
                        search.isBlank() ||
                        listOf(it.title, it.folder, it.tags).any { value ->
                            value.contains(search, true)
                        })
            }
            .let {
                if (search.isNotBlank() && !inTrash)
                    it.sortedBy { row -> if (hitsByDocument[row.id].isNullOrEmpty()) 1 else 0 }
                else if (inTrash) it.sortedByDescending { row -> row.trashedAt }
                else if (tab == 0 && search.isBlank())
                    it.filter { row -> row.lastOpened > 0 }
                        .sortedByDescending { row -> row.lastOpened }
                        .take(UserSettings.number("recentCount", 6f).toInt())
                else
                    when (sort) {
                        "OPENED" -> it.sortedByDescending { row -> row.lastOpened }
                        "TITLE" -> it.sortedBy { row -> row.title.lowercase() }
                        else -> it.sortedByDescending { row -> row.modified }
                    }
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
    BackHandler(inTrash && !selecting) { showTrash = false }
    BackHandler(selecting) { finishSelection() }
    LaunchedEffect(tab, search, showTrash, favoritesOnly, folder, collection, sort, tagQuery) {
        finishSelection()
    }
    if (savingFilter)
        AlertDialog(
            onDismissRequest = { savingFilter = false },
            title = { Text("保存筛选") },
            text = {
                OutlinedTextField(
                    filterName,
                    { filterName = it },
                    label = { Text("筛选名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = filterName.isNotBlank(),
                    onClick = {
                        library.save(
                            LibraryFilter(
                                filterName.trim(),
                                collection,
                                sort,
                                folder,
                                tagQuery,
                                favoritesOnly,
                                search,
                            )
                        )
                        savedFilters = library.saved()
                        savingFilter = false
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { savingFilter = false }) { Text("取消") } },
        )
    moreHits?.let { doc ->
        AlertDialog(
            onDismissRequest = { moreHits = null },
            title = { Text("${doc.title} · 命中页") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    hitsByDocument[doc.id].orEmpty().forEach { hit ->
                        LibrarySnippet(hit, search) {
                            moreHits = null
                            vm.openSearchHit(doc, hit, search)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moreHits = null }) { Text("关闭") } },
        )
    }
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
            else
                TopAppBar(
                    title = {
                        Text(
                            if (inTrash) "回收站"
                            else
                                when (tab) {
                                    1 -> "我的文档"
                                    2 -> "工具"
                                    else -> "纸匠"
                                }
                        )
                    },
                    navigationIcon = {
                        if (inTrash)
                            IconButton(onClick = { showTrash = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回文档")
                            }
                    },
                    actions = {
                        IconButton(onClick = { vm.screen.value = "tasks" }) {
                            Icon(Icons.Default.PendingActions, "任务中心")
                        }
                        if (!inTrash)
                            TextButton(
                                onClick = {
                                    tab = 1
                                    showTrash = true
                                    favoritesOnly = false
                                    folder = ""
                                    vm.query.value = ""
                                }
                            ) {
                                Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp))
                                Text("回收站")
                            }
                        IconButton(onClick = { vm.screen.value = "settings" }) {
                            Icon(Icons.Default.Settings, "个性化设置")
                        }
                        IconButton(onClick = onAbout) { Icon(Icons.Default.Info, "关于与隐私") }
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
                                "移入回收站",
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
                                        text = { Text("批量命名") },
                                        onClick = {
                                            vm.beginBatchRename(chosen.map { it.id })
                                            finishSelection()
                                        },
                                    )
                                    if (canUndoNames)
                                        DropdownMenuItem(
                                            text = { Text("撤销上次批量命名") },
                                            onClick = {
                                                vm.undoBatchNames()
                                                finishSelection()
                                            },
                                        )
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
                                    else {
                                        tab = if (i == 3) 2 else i
                                        showTrash = false
                                    }
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
            if (!selecting && !inTrash)
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
            if (tab != 2)
                item {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { vm.query.value = it },
                        placeholder = { Text(if (inTrash) "搜索回收站" else "搜索文件、文字或标签") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                    )
                }
            if (tab == 0) {
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
                        "文档工具",
                        style = MaterialTheme.typography.titleLarge,
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
                    listOfNotNull(
                        HomeTool(
                            "批量命名",
                            "多选重命名，日期、序号、自定义字段，重名自动编号",
                            Icons.Default.DriveFileRenameOutline,
                        ) {
                            vm.beginBatchRename(emptyList())
                        },
                        if (canUndoNames)
                            HomeTool("撤销批量命名", "保留之后手工修改的名称", Icons.Default.Undo) {
                                vm.undoBatchNames()
                            }
                        else null,
                        HomeTool("任务中心", "查看扫描处理进度、失败页与原图，单独重试", Icons.Default.PendingActions) {
                            vm.screen.value = "tasks"
                        },
                        HomeTool("导出记录", "找回生成结果、来源与保存位置", Icons.Default.History) {
                            vm.outputSource.value = null
                            vm.screen.value = "outputs"
                        },
                        HomeTool(
                            "学习摘录与笔记",
                            "来源页定位、Markdown、Word、Anki TSV",
                            Icons.Default.AutoStories,
                        ) {
                            vm.screen.value = "notes"
                        },
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
                        HomeTool(
                            "恢复备份",
                            "选择纸匠 ZIP 并保留现有文档",
                            Icons.Default.Restore,
                            onRestoreBackup,
                        ),
                        HomeTool("存储空间与缓存", "查看原件、历史、导出与可清理容量", Icons.Default.Storage) {
                            vm.screen.value = "storage"
                        },
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
                            if (inTrash) "已移入回收站"
                            else if (search.isNotBlank()) "搜索结果"
                            else if (tab == 0) "最近文档" else "全部文档",
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
                if (tab == 1 && !inTrash) {
                    item {
                        LibraryFilterControls(collection, sort, { collection = it }, { sort = it })
                    }
                    if (savedFilters.isNotEmpty())
                        item {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                savedFilters.forEach { preset ->
                                    InputChip(
                                        false,
                                        onClick = {
                                            collection = preset.collection
                                            sort = preset.sort
                                            folder = preset.folder
                                            tagQuery = preset.tags
                                            favoritesOnly = preset.favorites
                                            vm.query.value = preset.query
                                        },
                                        label = { Text(preset.name) },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Default.Close,
                                                "删除筛选 ${preset.name}",
                                                Modifier.size(16.dp).clickable {
                                                    library.remove(preset.name)
                                                    savedFilters = library.saved()
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                favoritesOnly,
                                { favoritesOnly = !favoritesOnly },
                                label = { Text("收藏") },
                            )
                            TextButton(
                                onClick = {
                                    filterName = ""
                                    savingFilter = true
                                }
                            ) {
                                Text("保存筛选")
                            }
                            TextButton(
                                onClick = {
                                    collection = "ALL"
                                    sort = "MODIFIED"
                                    favoritesOnly = false
                                    folder = ""
                                    tagQuery = ""
                                    vm.query.value = ""
                                }
                            ) {
                                Text("重置筛选")
                            }
                        }
                    }
                    item {
                        FolderField(
                            value = folder,
                            onValueChange = { folder = it },
                            paths = docs.map { it.folder },
                            label = "按文件夹筛选，含下级",
                            rootLabel = "全部文档",
                        )
                        OutlinedTextField(
                            tagQuery,
                            { tagQuery = it },
                            label = { Text("按标签筛选") },
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
                                if (inTrash) "回收站是空的"
                                else if (search.isEmpty()) "第一份文档，从这里开始" else "没有匹配的文档",
                                modifier = Modifier.padding(10.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (search.isEmpty() && !inTrash)
                                Text("扫描、导入，全程留在设备。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                itemsIndexed(filtered, key = { _, doc -> doc.id }) { index, doc ->
                    val opened = tab == 0 || sort == "OPENED"
                    val date = if (opened) doc.lastOpened else doc.modified
                    if (
                        search.isBlank() &&
                            (tab == 0 || sort != "TITLE") &&
                            (index == 0 ||
                                libraryDateGroup(date) !=
                                    libraryDateGroup(
                                        if (opened) filtered[index - 1].lastOpened
                                        else filtered[index - 1].modified
                                    ))
                    )
                        Text(
                            libraryDateGroup(date),
                            Modifier.padding(top = 10.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
                                    else MaterialTheme.colorScheme.surfaceContainerLowest
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
                            DocumentCover(doc, vm.repo)
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
                                                    if (tab == 0 || sort == "OPENED") doc.lastOpened
                                                    else doc.modified
                                                )
                                            ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                factsByDocument[doc.id]?.let { f ->
                                    Text(
                                        (if (doc.kind == "pdf" && f.pageCount == 0) "文字索引待更新"
                                        else "${f.pageCount} 页 · ${f.recognizedPages} 页含文字") +
                                            (if (f.hasExport) " · 已导出" else ""),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                if (doc.folder.isNotEmpty() || doc.tags.isNotEmpty())
                                    Text(
                                        listOf(doc.folder, doc.tags)
                                            .filter { it.isNotEmpty() }
                                            .joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                if (!selecting && search.isNotBlank() && !inTrash) {
                                    val matches = hitsByDocument[doc.id].orEmpty()
                                    matches.take(2).forEach { hit ->
                                        LibrarySnippet(hit, search) {
                                            vm.openSearchHit(doc, hit, search)
                                        }
                                    }
                                    if (matches.size > 2)
                                        TextButton(onClick = { moreHits = doc }) {
                                            Text("查看 ${matches.size} 个命中页")
                                        }
                                }
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
                                            text = { Text("导出记录") },
                                            onClick = {
                                                menu = false
                                                vm.outputSource.value = doc.id
                                                vm.screen.value = "outputs"
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
        var changeFolder by remember { mutableStateOf(false) }
        var changeTags by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { bulkCategory = emptyList() },
            title = { Text("分类 ${bulkCategory.size} 份文档") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(changeFolder, { changeFolder = it })
                        Text("修改文件夹")
                    }
                    if (changeFolder)
                        FolderField(
                            targetFolder,
                            { targetFolder = it },
                            docs.map { it.folder },
                            label = "目标文件夹，留空移至未分类",
                        )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(changeTags, { changeTags = it })
                        Text("修改标签")
                    }
                    if (changeTags)
                        OutlinedTextField(
                            targetTags,
                            { targetTags = it },
                            label = { Text("标签，逗号分隔；留空清除") },
                        )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.categorizeDocuments(
                            bulkCategory.toSet(),
                            targetFolder.takeIf { changeFolder },
                            targetTags.takeIf { changeTags },
                        )
                        bulkCategory = emptyList()
                        finishSelection()
                    },
                    enabled = changeFolder || changeTags,
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
                    FolderField(f, { f = it }, docs.map { it.folder })
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
