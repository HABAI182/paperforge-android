package cn.paperforge.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings
import cn.paperforge.app.data.*
import cn.paperforge.app.model.ToolCatalog
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
    val settingsVersion by UserSettings.revision.collectAsState()
    val documentLayout = remember(settingsVersion) { UserSettings[SettingKeys.DocumentLayout] }
    val compactCards = remember(settingsVersion) { UserSettings[SettingKeys.CompactRows] }
    val homeCollection = remember(settingsVersion) { UserSettings[SettingKeys.HomeCollection] }
    val gridSetting = remember(settingsVersion) { UserSettings[SettingKeys.GridColumns] }
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val fontScale = LocalDensity.current.fontScale
    val maxColumns =
        ((screenWidth - 32) / (144 * fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 4)
    val covers = documentLayout == "COVERS"
    val columns =
        if (!covers) 1
        else
            (gridSetting.toIntOrNull() ?: ((screenWidth - 32) / 200).coerceAtLeast(2)).coerceIn(
                1,
                maxColumns,
            )
    var importMenu by remember { mutableStateOf(false) }
    var libraryDisplay by remember { mutableStateOf(false) }
    val docs by vm.documents.collectAsState()
    val busy by vm.busy.collectAsState()
    val tab by vm.homeTab.collectAsState()
    LaunchedEffect(tab) { if (tab < 2) vm.documentTab.value = tab }
    val toolQuery by vm.workspaceQuery.collectAsState()
    val toolGroup by vm.workspaceGroup.collectAsState()
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    val recentScroll = rememberLazyListState()
    val documentsScroll = rememberLazyListState()
    val workspaceScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()
    LaunchedEffect(toolGroup, toolQuery) { workspaceScroll.scrollToItem(0) }
    val pinnedTools = remember(settingsVersion) { UserSettings.pinnedTools() }
    var toolCapability by remember { mutableStateOf<cn.paperforge.app.model.ToolCapability?>(null) }
    toolCapability?.let { ToolCapabilityDialog(it) { toolCapability = null } }
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
    val families by vm.comparison.families.collectAsState()
    val familyByDocument = families.flatMap { group -> group.members.map { it to group } }.toMap()
    var groupSelection by remember { mutableStateOf<List<String>?>(null) }
    val inTrash = showTrash && tab == 1
    val folderPath = libraryFolder(folder)
    val filtered =
        (if (inTrash) trash else docs)
            .filter {
                (tab != 0 ||
                    search.isNotBlank() ||
                    when (homeCollection) {
                        "FAVORITES" -> it.favorite
                        "INBOX" -> it.folder.isBlank() && it.tags.isBlank()
                        "NO_OCR" ->
                            factsByDocument[it.id]?.let { f ->
                                f.pageCount > 0 && f.recognizedPages < f.pageCount
                            } == true
                        "NEEDS_EXPORT" ->
                            it.kind == "scan" && factsByDocument[it.id]?.hasExport != true
                        else -> true
                    }) &&
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
                    it.filter { row -> homeCollection != "RECENT" || row.lastOpened > 0 }
                        .sortedByDescending { row -> row.lastOpened }
                        .take(UserSettings[SettingKeys.RecentCount].toInt())
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
    BackHandler(tab == 2 && (toolGroup.isNotBlank() || toolQuery.isNotBlank())) {
        if (toolQuery.isBlank()) vm.workspaceGroup.value = toolGroup.substringBeforeLast('/', "")
        vm.workspaceQuery.value = ""
    }
    BackHandler(tab == 3 || (tab == 2 && toolGroup.isBlank() && toolQuery.isBlank())) {
        vm.homeTab.value = vm.documentTab.value
    }
    BackHandler(inTrash && !selecting) { showTrash = false }
    BackHandler(selecting) { finishSelection() }
    LaunchedEffect(
        tab,
        search,
        showTrash,
        favoritesOnly,
        folder,
        collection,
        sort,
        tagQuery,
        homeCollection,
    ) {
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
    if (libraryDisplay)
        AlertDialog(
            onDismissRequest = { libraryDisplay = false },
            title = { Text("文档视图") },
            text = {
                SectionCardGrid(
                    listOf(
                        WorkspaceSection("LIST", "列表", "紧凑排列，便于查找名称", Icons.Default.ViewList),
                        WorkspaceSection("COVERS", "封面", "展示文档首页，直观浏览", Icons.Default.GridView),
                    )
                ) {
                    UserSettings.set(SettingKeys.DocumentLayout, it)
                    libraryDisplay = false
                }
            },
            confirmButton = { TextButton(onClick = { libraryDisplay = false }) { Text("完成") } },
        )
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
                                    2 -> "工作台"
                                    3 -> "设置"
                                    else -> "PaperForge"
                                },
                            style =
                                if (tab < 2) MaterialTheme.typography.headlineMedium
                                else MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (inTrash)
                            IconButton(onClick = { showTrash = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回文档")
                            }
                    },
                    actions = {
                        if (tab < 2 && !inTrash)
                            TextButton(onClick = { libraryDisplay = true }) {
                                Icon(Icons.Default.ViewQuilt, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("视图")
                            }
                        if (tab < 2 && !inTrash)
                            IconButton(
                                onClick = {
                                    vm.homeTab.value = 1
                                    showTrash = true
                                    favoritesOnly = false
                                    folder = ""
                                    vm.query.value = ""
                                }
                            ) {
                                Icon(Icons.Default.DeleteOutline, "回收站")
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
                                        text = { Text("新建页面组合") },
                                        enabled =
                                            chosen.isNotEmpty() && chosen.all { it.kind == "pdf" },
                                        onClick = {
                                            vm.beginAssembly(chosen.map { it.id }, fresh = true)
                                            finishSelection()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("比较所选文档") },
                                        enabled = chosen.size >= 2,
                                        onClick = {
                                            vm.beginComparison(chosen.map { it.id })
                                            finishSelection()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("归为同一版本组") },
                                        enabled = chosen.size >= 2,
                                        onClick = {
                                            groupSelection = chosen.map { it.id }
                                            finishSelection()
                                        },
                                    )
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
                            "文档" to Icons.AutoMirrored.Filled.Article,
                            "工作台" to Icons.Default.GridView,
                            "设置" to Icons.Default.Settings,
                        )
                        .forEachIndexed { i, (label, icon) ->
                            val destination = if (i == 0) vm.documentTab.value else i + 1
                            NavigationBarItem(
                                selected = if (i == 0) tab < 2 else tab == destination,
                                onClick = {
                                    vm.homeTab.value = destination
                                    showTrash = false
                                },
                                icon = { Icon(icon, null) },
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
            if (tab < 2 && !selecting && !inTrash)
                ExtendedFloatingActionButton(
                    onClick = { if (!busy) vm.newScan() },
                    icon = { Icon(Icons.Default.DocumentScanner, null) },
                    text = { Text("扫描") },
                )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding)
                .fillMaxSize()
                .testTag(
                    when (tab) {
                        2 -> "workspace"
                        3 -> "settings-hub"
                        else -> "document-list"
                    }
                ),
            state =
                when (tab) {
                    0 -> recentScroll
                    1 -> documentsScroll
                    2 -> workspaceScroll
                    else -> settingsScroll
                },
            contentPadding =
                PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tab < 2)
                item {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { vm.query.value = it },
                        placeholder = {
                            Text(
                                if (inTrash) "搜索回收站" else "搜索文件、文字或标签",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            Row {
                                Box {
                                    IconButton(onClick = { importMenu = true }, enabled = !busy) {
                                        Icon(Icons.Default.Add, "导入文件")
                                    }
                                    DropdownMenu(importMenu, { importMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("相册导入") },
                                            onClick = {
                                                importMenu = false
                                                onImages()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("打开 PDF") },
                                            onClick = {
                                                importMenu = false
                                                onPdf()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("图片识字") },
                                            onClick = {
                                                importMenu = false
                                                onOcrImages()
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                    )
                }
            if (tab < 2 && !inTrash)
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            tab == 0,
                            {
                                vm.homeTab.value = 0
                                showTrash = false
                            },
                            label = {
                                Text(
                                    when (homeCollection) {
                                        "FAVORITES" -> "收藏"
                                        "INBOX" -> "未整理"
                                        "NO_OCR" -> "未识字"
                                        "NEEDS_EXPORT" -> "待导出"
                                        else -> "最近"
                                    }
                                )
                            },
                        )
                        FilterChip(
                            tab == 1 && !favoritesOnly,
                            {
                                vm.homeTab.value = 1
                                collection = "ALL"
                                favoritesOnly = false
                                folder = ""
                                tagQuery = ""
                            },
                            label = { Text("全部") },
                        )
                        if (homeCollection != "FAVORITES")
                            FilterChip(
                                tab == 1 && favoritesOnly,
                                {
                                    vm.homeTab.value = 1
                                    collection = "ALL"
                                    favoritesOnly = true
                                    folder = ""
                                    tagQuery = ""
                                },
                                label = { Text("收藏") },
                            )
                        Spacer(Modifier.weight(1f))
                        if (tab == 1)
                            IconButton(onClick = { filtersExpanded = !filtersExpanded }) {
                                val active =
                                    collection != "ALL" ||
                                        folder.isNotBlank() ||
                                        tagQuery.isNotBlank() ||
                                        sort != "MODIFIED"
                                Icon(
                                    if (filtersExpanded) Icons.Default.ExpandLess
                                    else Icons.Default.Tune,
                                    if (filtersExpanded) "收起筛选与布局" else "筛选与布局",
                                    tint =
                                        if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }
                }
            if (tab == 3) {
                item {
                    SettingsHub(vm, onAbout) {
                        cn.paperforge.app.system.NetworkTools.updates(context, checkOnOpen = true)
                    }
                }
            } else if (tab == 2) {
                val toolEntries =
                    listOfNotNull(
                        HomeTool(
                            "action:scan",
                            "扫描纸张",
                            "自动识边、连续拍摄和图像增强",
                            Icons.Default.DocumentScanner,
                        ) {
                            vm.newScan()
                        },
                        HomeTool(
                            "action:open-pdf",
                            "打开 PDF",
                            "导入 PDF，阅读、检查或编辑页面",
                            Icons.Default.FileOpen,
                            action = onPdf,
                        ),
                        HomeTool(
                            "action:rename-batch",
                            "批量命名",
                            "多选重命名，日期、序号、自定义字段，重名自动编号",
                            Icons.Default.DriveFileRenameOutline,
                        ) {
                            vm.beginBatchRename(emptyList())
                        },
                        if (canUndoNames)
                            HomeTool(
                                "action:rename-undo",
                                "撤销批量命名",
                                "保留之后手工修改的名称",
                                Icons.Default.Undo,
                            ) {
                                vm.undoBatchNames()
                            }
                        else null,
                        HomeTool(
                            "action:tasks",
                            "任务中心",
                            "查看扫描处理进度、失败页与原图，单独重试",
                            Icons.Default.PendingActions,
                        ) {
                            vm.screen.value = "tasks"
                        },
                        HomeTool(
                            "action:outputs",
                            "导出记录",
                            "找回生成结果、来源与保存位置",
                            Icons.Default.History,
                        ) {
                            vm.outputSource.value = null
                            vm.screen.value = "outputs"
                        },
                        HomeTool(
                            "action:compare",
                            "重复与版本",
                            "比较相同原件、相似页面，管理首选版本",
                            Icons.Default.Compare,
                        ) {
                            vm.beginComparison()
                        },
                        HomeTool(
                            "action:assembly",
                            "页面组合",
                            "跨文件选页、双面交错、封面和目录",
                            Icons.Default.ViewModule,
                        ) {
                            vm.beginAssembly()
                        },
                        HomeTool(
                            "action:notes",
                            "学习摘录与笔记",
                            "来源页定位、Markdown、Word、Anki TSV",
                            Icons.Default.AutoStories,
                        ) {
                            vm.screen.value = "notes"
                        },
                        HomeTool(
                            "tool:merge",
                            "合并 PDF",
                            "选择多份文件并调整顺序",
                            Icons.Default.Merge,
                            action = onMerge,
                        ),
                        HomeTool(
                            "tool:image-pdf",
                            "图片转 PDF",
                            "排序、裁边、滤镜和纸张尺寸",
                            Icons.Default.Collections,
                            action = onImages,
                        ),
                        HomeTool(
                            "action:ocr-images",
                            "离线 OCR 图片识字",
                            "导入图片，识别中英文",
                            Icons.Default.Translate,
                            action = onOcrImages,
                        ),
                        HomeTool(
                            "tool:range",
                            "抽页 / 拆分 / 旋转",
                            "输入页码创建新副本",
                            Icons.Default.ContentCut,
                        ) {
                            onPdfTool("range")
                        },
                        HomeTool("tool:compress", "压缩 PDF", "保留文字、链接和表单", Icons.Default.Compress) {
                            onPdfTool("compress")
                        },
                        HomeTool(
                            "tool:annotate",
                            "PDF 批注与手写签名",
                            "画笔、荧光笔、形状和文字",
                            Icons.Default.Draw,
                        ) {
                            onPdfTool("annotate")
                        },
                        HomeTool(
                            "action:lan",
                            "局域网传输",
                            "手机、平板和电脑浏览器互传，配对与断点保留",
                            Icons.Default.Wifi,
                        ) {
                            cn.paperforge.app.system.NetworkTools.lan(context)
                        },
                        HomeTool(
                            "action:intelligence",
                            "翻译与智能工具",
                            "本机翻译、带页码摘录和自定义服务，按需启用",
                            Icons.Default.AutoAwesome,
                        ) {
                            cn.paperforge.app.system.NetworkTools.intelligence(context)
                        },
                        HomeTool(
                            "action:backup",
                            "离线备份",
                            "原图、PDF、OCR、分类与编辑历史",
                            Icons.Default.Backup,
                            action = vm::backup,
                        ),
                        HomeTool(
                            "action:restore",
                            "恢复备份",
                            "选择PaperForge ZIP 并保留现有文档",
                            Icons.Default.Restore,
                            action = onRestoreBackup,
                        ),
                        HomeTool(
                            "action:storage",
                            "存储空间与缓存",
                            "查看原件、历史、导出与可清理容量",
                            Icons.Default.Storage,
                        ) {
                            vm.storageReturn.value = "home"
                            vm.screen.value = "storage"
                        },
                    ) +
                        ToolCatalog.conversions
                            .filter { it.group != "电脑引擎" }
                            .map { entry ->
                                HomeTool(
                                    entry.id,
                                    entry.title,
                                    entry.detail,
                                    Icons.Default.SwapHoriz,
                                    entry.group,
                                ) {
                                    if (
                                        entry.mode in
                                            setOf("workflows", "auto-export", "conversion-batch")
                                    ) {
                                        vm.toolHubReturn.value = "home"
                                        vm.screen.value = entry.mode
                                    } else vm.openConversion(entry.mode)
                                }
                            } +
                        ToolCatalog.pdfTitles
                            .filterKeys { mode -> ToolCatalog.conversion(mode) == null }
                            .map { (mode, title) ->
                                HomeTool(
                                    "tool:$mode",
                                    title,
                                    "选择 PDF 后设置参数",
                                    Icons.Default.PictureAsPdf,
                                    "页面",
                                ) {
                                    onPdfTool(mode)
                                }
                            }
                val shortcuts =
                    UserSettings[SettingKeys.HomeTools].split(',').mapNotNull {
                        when (it) {
                            "images" -> "tool:image-pdf"
                            "pdf" -> "action:open-pdf"
                            "ocr" -> "action:ocr-images"
                            "merge" -> "tool:merge"
                            "compress" -> "tool:compress"
                            else -> null
                        }
                    }
                workspaceContent(
                    toolEntries,
                    toolGroup,
                    toolQuery,
                    pinnedTools,
                    shortcuts,
                    !busy,
                    onGroup = { vm.workspaceGroup.value = it },
                    onQuery = { vm.workspaceQuery.value = it },
                    onInfo = { toolCapability = it },
                )
            } else {
                if (
                    tab == 0 &&
                        homeCollection == "RECENT" &&
                        !inTrash &&
                        !selecting &&
                        search.isBlank()
                )
                    item(key = "continue-reading") { ContinueReadingCard(vm, docs, busy) }
                item {
                    LibraryCollectionHeader(
                        title =
                            if (inTrash) "已移入回收站"
                            else if (search.isNotBlank()) "搜索结果"
                            else if (tab == 0)
                                when (homeCollection) {
                                    "FAVORITES" -> "收藏文档"
                                    "INBOX" -> "未整理文档"
                                    "NO_OCR" -> "未识字文档"
                                    "NEEDS_EXPORT" -> "待导出文档"
                                    else -> "最近文档"
                                }
                            else "全部文档",
                        selecting = selecting,
                        count = filtered.size,
                        largeFont = fontScale > 1.3f,
                        onSelect = { selecting = true },
                        onAll = null,
                    )
                }
                if (tab == 1 && !inTrash && filtersExpanded) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "筛选与布局",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            IconButton(
                                onClick = {
                                    UserSettings.set(
                                        SettingKeys.DocumentLayout,
                                        if (covers) "LIST" else "COVERS",
                                    )
                                }
                            ) {
                                Icon(
                                    if (covers) Icons.Default.ViewList else Icons.Default.GridView,
                                    if (covers) "切换列表布局" else "切换封面布局",
                                )
                            }
                        }
                    }
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
                                else if (search.isNotBlank()) "没有匹配的文档"
                                else if (docs.isNotEmpty()) "这个集合暂时没有文档" else "第一份文档，从这里开始",
                                modifier = Modifier.padding(10.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (search.isEmpty() && !inTrash) {
                                if (docs.isNotEmpty())
                                    TextButton(
                                        onClick = {
                                            vm.homeTab.value = 1
                                            collection = "ALL"
                                            favoritesOnly = false
                                            folder = ""
                                            tagQuery = ""
                                        }
                                    ) {
                                        Text("查看全部文档")
                                    }
                                else
                                    Text(
                                        "扫描、导入，全程留在设备。",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                            }
                        }
                    }
                val grouped =
                    if (search.isBlank() && (tab == 0 || sort != "TITLE"))
                        filtered.groupBy { doc ->
                            libraryDateGroup(
                                if (tab == 0 || sort == "OPENED") doc.lastOpened else doc.modified
                            )
                        }
                    else linkedMapOf("" to filtered)
                grouped.forEach { (dateGroup, documents) ->
                    if (dateGroup.isNotBlank())
                        item(key = "date:$dateGroup") {
                            Text(
                                dateGroup,
                                Modifier.padding(top = 10.dp, bottom = 6.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    items(
                        documents.chunked(columns),
                        key = { row -> "documents:" + row.joinToString(",") { it.id } },
                    ) { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            row.forEach { doc ->
                                key(doc.id) {
                                    LibraryDocumentCard(
                                        doc,
                                        vm.repo,
                                        covers,
                                        compactCards,
                                        selecting,
                                        doc.id in selection,
                                        !busy,
                                        Modifier.weight(1f),
                                        onOpen = {
                                            if (selecting) toggle(doc.id)
                                            else if (inTrash) vm.restore(doc) else vm.open(doc)
                                        },
                                        onSelect = { if (!busy) toggle(doc.id) },
                                        onLongClick = {
                                            selecting = true
                                            toggle(doc.id)
                                        },
                                        menu = {
                                            var menu by remember { mutableStateOf(false) }
                                            if (!selecting)
                                                Box {
                                                    IconButton(onClick = { menu = true }) {
                                                        Icon(
                                                            Icons.Default.MoreVert,
                                                            "${doc.title}的更多操作",
                                                        )
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
                                                            text = {
                                                                Text(
                                                                    if (doc.favorite) "取消收藏"
                                                                    else "收藏"
                                                                )
                                                            },
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
                                                                    color =
                                                                        MaterialTheme.colorScheme
                                                                            .error,
                                                                )
                                                            },
                                                            onClick = {
                                                                menu = false
                                                                deleting = doc
                                                            },
                                                        )
                                                    }
                                                }
                                        },
                                        details = {
                                            Text(
                                                (if (doc.favorite) "★ " else "") + doc.title,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            Text(
                                                (if (doc.kind == "scan") "扫描草稿" else "PDF 文档") +
                                                    " · " +
                                                    SimpleDateFormat("MM/dd HH:mm", Locale.CHINA)
                                                        .format(
                                                            Date(
                                                                if (tab == 0 || sort == "OPENED")
                                                                    doc.lastOpened
                                                                else doc.modified
                                                            )
                                                        ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            factsByDocument[doc.id]?.let { f ->
                                                Text(
                                                    (if (doc.kind == "pdf" && f.pageCount == 0)
                                                        "文字索引待更新"
                                                    else
                                                        "${f.pageCount} 页 · ${f.recognizedPages} 页含文字") +
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
                                            familyByDocument[doc.id]?.let { group ->
                                                TextButton(
                                                    onClick = {
                                                        vm.beginComparison(
                                                            versions = true,
                                                            familyId = group.id,
                                                        )
                                                    },
                                                    contentPadding = PaddingValues(0.dp),
                                                ) {
                                                    Text(
                                                        "${if(group.preferred==doc.id) "首选版本 · " else "版本组 · "}${group.name}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
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
                                        },
                                    )
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
    groupSelection?.let { ids ->
        FamilyNameDialog(
            "建立版本分组",
            docs.firstOrNull { it.id == ids.firstOrNull() }?.title ?: "文档版本",
            { groupSelection = null },
        ) { name ->
            vm.beginComparison(ids, versions = true)
            vm.comparison.group(name, ids)
            groupSelection = null
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

internal data class HomeTool(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val group: String =
        ToolCatalog.conversion(id.removePrefix("tool:"))?.group
            ?: if (id in setOf("action:backup", "action:restore", "action:storage")) "存储"
            else if (id == "action:ocr-images") "图片"
            else if (id.startsWith("tool:")) "页面" else "文档",
    val action: () -> Unit,
) {
    val aliases
        get() =
            when (id) {
                "action:ocr-images" -> "文字识别 识字 OCR 图片 照片"
                "action:backup",
                "action:restore" -> "backup restore 导入 导出 备份 恢复"
                "action:notes" -> "笔记 摘录 word markdown anki"
                "action:rename-batch" -> "重命名 rename 批量"
                else -> ""
            }
}
