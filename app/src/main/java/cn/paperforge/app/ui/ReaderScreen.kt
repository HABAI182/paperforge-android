package cn.paperforge.app.ui

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.ReaderPosition
import cn.paperforge.app.ReaderPreferences
import cn.paperforge.app.model.PageSpreads
import cn.paperforge.app.model.PointN
import cn.paperforge.app.pdf.PdfSearch
import cn.paperforge.app.pdf.ReaderDocument
import cn.paperforge.app.pdf.ReaderInfo
import cn.paperforge.app.pdf.ReaderLink
import cn.paperforge.app.pdf.ReaderTextBlock
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: MainViewModel, onShare: (File) -> Unit) {
    val doc by vm.selected.collectAsState()
    key(doc?.id) { ReaderContent(vm, onShare) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(vm: MainViewModel, onShare: (File) -> Unit) {
    val doc by vm.selected.collectAsState()
    val busy by vm.busy.collectAsState()
    val context = LocalContext.current
    val preferences = remember { ReaderPreferences(context) }
    val saved = remember(doc?.id) { preferences.load(doc?.id ?: "") }
    var viewOptions by remember(doc?.id) { mutableStateOf(preferences.view(doc?.id ?: "")) }
    val file = doc?.pdfPath?.let { File(it) }
    var session by remember(file) { mutableStateOf<PdfSession?>(null) }
    var loadError by remember(file) { mutableStateOf<String?>(null) }
    // Close even when opening is cancelled between IO completion and state publication.
    LaunchedEffect(file) {
        var opened: PdfSession? = null
        try {
            withContext(Dispatchers.IO) { opened = file?.let(::PdfSession) }
            session = opened
            kotlinx.coroutines.awaitCancellation()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            loadError = error.message ?: "无法打开 PDF"
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { opened?.close() }
        }
    }
    val list = rememberLazyListState(saved.page, saved.offset)
    var activePage by rememberSaveable(doc?.id) { mutableIntStateOf(saved.page) }
    var horizontal by rememberSaveable(doc?.id) { mutableStateOf(saved.horizontal) }
    val facing =
        horizontal && viewOptions.facingPages && LocalConfiguration.current.screenWidthDp >= 600
    val spreads =
        PageSpreads.groups(session?.count ?: saved.page + 1, facing, viewOptions.coverAlone)
    val pager =
        key(facing, viewOptions.coverAlone) {
            rememberPagerState(initialPage = PageSpreads.groupOf(spreads, activePage)) {
                spreads.size
            }
        }
    LaunchedEffect(pager.currentPage, spreads, horizontal) {
        if (horizontal)
            spreads.getOrNull(pager.currentPage)?.let {
                if (activePage !in it) activePage = it.first()
            }
    }
    val scope = rememberCoroutineScope()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var appearance by remember { mutableStateOf(false) }
    var paperTone by rememberSaveable { mutableIntStateOf(saved.tone) }
    var bookmarks by remember(doc?.id) { mutableStateOf(preferences.bookmarks(doc?.id ?: "")) }
    var bookmarkDialog by remember { mutableStateOf(false) }
    var edgeNotice by remember { mutableStateOf("") }
    LaunchedEffect(edgeNotice) {
        if (edgeNotice.isNotBlank()) {
            delay(2200)
            edgeNotice = ""
        }
    }
    var edgeTurning by remember {
        mutableStateOf(cn.paperforge.app.UserSettings.bool("edgeTurn", true))
    }
    var navigation by remember { mutableStateOf(false) }
    var textPage by remember { mutableStateOf<Int?>(null) }
    var textPoint by remember { mutableStateOf<PointN?>(null) }
    var focusedText by remember { mutableStateOf<Pair<Int, ReaderTextBlock>?>(null) }
    val navigationBack = remember { mutableStateListOf<ReaderPosition>() }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val documentInfo by
        produceState(ReaderInfo(), file) {
            value =
                withContext(Dispatchers.IO) {
                    file?.let { runCatching { ReaderDocument.info(it) }.getOrNull() }
                        ?: ReaderInfo()
                }
        }
    val transforms =
        remember(doc?.id) {
            mutableStateMapOf<Int, PageTransform>().apply {
                if (preferences.hasPosition(doc?.id ?: ""))
                    put(saved.page, PageTransform(saved.zoom, saved.panX, saved.panY))
            }
        }
    val visiblePage by remember { derivedStateOf { list.firstVisibleItemIndex } }
    val page = if (horizontal) activePage else visiblePage
    val livePage by rememberUpdatedState(page)
    val transform = transforms[page] ?: PageTransform()
    fun changeView(next: cn.paperforge.app.reader.ReaderViewOptions) {
        val source =
            viewOptions
                .viewport(page)
                .source(
                    PointN(.5f - transform.x / transform.zoom, .5f - transform.y / transform.zoom)
                )
        val center = next.viewport(page).view(source)
        transforms[page] =
            transform.copy(
                x = (.5f - center.x) * transform.zoom,
                y = (.5f - center.y) * transform.zoom,
            )
        viewOptions = next
        doc?.id?.let { preferences.view(it, next) }
    }
    val latestPosition by
        rememberUpdatedState(
            ReaderPosition(page, horizontal, transform.zoom, transform.x, transform.y, 0, paperTone)
        )
    val latestId by rememberUpdatedState(doc?.id)
    fun persist() {
        latestId?.let {
            preferences.save(
                it,
                latestPosition.copy(
                    offset = if (latestPosition.horizontal) 0 else list.firstVisibleItemScrollOffset
                ),
            )
        }
    }
    DisposableEffect(doc?.id) { onDispose { persist() } }
    LaunchedEffect(latestPosition) {
        delay(350)
        persist()
    }
    LaunchedEffect(list, horizontal) {
        if (!horizontal)
            snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
                .collectLatest {
                    delay(350)
                    persist()
                }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) persist()
            }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val window = (context as? Activity)?.window
    DisposableEffect(window, fullscreen) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) controller?.hide(WindowInsetsCompat.Type.systemBars())
        else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(fullscreen, controls, interaction, appearance) {
        if (fullscreen && controls && !appearance) {
            delay(3500)
            controls = false
        }
    }
    var rangeDialog by remember { mutableStateOf(false) }
    var compressDialog by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf("") }
    var rotation by remember { mutableIntStateOf(0) }
    var jump by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("1") }
    var tools by remember { mutableStateOf(false) }
    var toolMode by remember { mutableStateOf("") }
    var thumbnails by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hitIndex by remember { mutableIntStateOf(0) }
    val hits by vm.searchHits.collectAsState()
    val readerTarget by vm.readerTarget.collectAsState()
    fun goTo(index: Int) {
        val n = index.coerceIn(0, ((session?.count ?: 1) - 1).coerceAtLeast(0))
        activePage = n
        scope.launch {
            if (horizontal) pager.scrollToPage(PageSpreads.groupOf(spreads, n))
            else list.scrollToItem(n)
        }
        interaction++
    }
    val linkCache = remember(file) { mutableMapOf<Int, List<ReaderLink>>() }
    LaunchedEffect(file, page) {
        if (file != null && !linkCache.containsKey(page)) {
            linkCache[page] =
                withContext(Dispatchers.IO) {
                    runCatching { ReaderDocument.links(file, page) }.getOrDefault(emptyList())
                }
        }
    }
    fun navigate(index: Int) {
        navigationBack.add(
            latestPosition.copy(offset = if (horizontal) 0 else list.firstVisibleItemScrollOffset)
        )
        goTo(index)
    }
    fun returnToAnchor() {
        val previous = navigationBack.removeLastOrNull() ?: return
        transforms[previous.page] = PageTransform(previous.zoom, previous.panX, previous.panY)
        horizontal = previous.horizontal
        activePage = previous.page
        scope.launch {
            withFrameNanos {}
            if (previous.horizontal) pager.scrollToPage(PageSpreads.groupOf(spreads, previous.page))
            else list.scrollToItem(previous.page, previous.offset)
        }
    }
    fun activateLink(index: Int, point: PointN): Boolean {
        if (horizontal && index !in spreads.getOrNull(pager.currentPage).orEmpty()) return false
        if (!horizontal && index != livePage) return false
        activePage = index
        fun activate(links: List<ReaderLink>): Boolean {
            val link =
                links.firstOrNull { point.x in it.left..it.right && point.y in it.top..it.bottom }
                    ?: return false
            if (link.page != null) navigate(link.page)
            else
                link.uri?.let { uri ->
                    runCatching { uriHandler.openUri(uri) }
                        .onFailure { scope.launch { vm.messages.send("系统无法打开这个链接") } }
                }
            return true
        }
        val known = linkCache[index]
        if (known != null) return activate(known)
        if (file == null) return false
        scope.launch {
            val loaded =
                withContext(Dispatchers.IO) {
                    runCatching { ReaderDocument.links(file, index) }.getOrDefault(emptyList())
                }
            linkCache[index] = loaded
            if (livePage == index && !activate(loaded)) {
                controls = !controls
                interaction++
            }
        }
        return true
    }
    fun nextAtEdge(direction: Int): Int {
        val step = if (horizontal && viewOptions.rightToLeft) -direction else direction
        return if (horizontal) spreads.getOrNull(pager.currentPage + step)?.first() ?: -1
        else page + step
    }
    fun flipAtEdge(direction: Int) {
        edgeNotice = ""
        val next = nextAtEdge(direction)
        if (next in 0 until (session?.count ?: 0)) {
            transforms[next] = PageTransform()
            goTo(next)
        }
    }
    fun edgeHint(direction: Int) {
        edgeNotice =
            when {
                nextAtEdge(direction) !in 0 until (session?.count ?: 0) -> "已经到达文档边界"
                direction > 0 -> "已到页边，再次向左滑动翻页"
                else -> "已到页边，再次向右滑动翻页"
            }
    }
    fun changeMode(value: Boolean) {
        val current = page
        horizontal = value
        activePage = current
        scope.launch {
            if (value) pager.scrollToPage(PageSpreads.groupOf(spreads, current))
            else list.scrollToItem(current)
        }
    }
    LaunchedEffect(readerTarget, session) {
        val target = readerTarget
        if (target != null && target.first == doc?.id && session != null) {
            goTo(target.second)
            vm.readerTarget.value = null
        }
    }
    BackHandler(fullscreen || search) {
        if (search) search = false
        else {
            fullscreen = false
            controls = true
        }
    }
    LaunchedEffect(session) {
        val count = session?.count ?: return@LaunchedEffect
        if (page >= count) goTo(count - 1)
    }
    LaunchedEffect(doc?.id) {
        val entry = vm.pdfEntry.value
        vm.pdfEntry.value = null
        when (entry) {
            "compress" -> compressDialog = true
            "range" -> rangeDialog = true
            "annotate" -> vm.beginMarks()
            null -> Unit
            else -> {
                toolMode = entry
                tools = true
            }
        }
    }
    LaunchedEffect(hits) {
        hitIndex = 0
        hits.firstOrNull()?.let { goTo(it.page) }
    }
    LaunchedEffect(page) { vm.annotationPage.value = page }
    LaunchedEffect(session, page, transform.zoom) {
        val renderer = session
        if (renderer != null && transform.zoom <= 1.01f && page + 1 < renderer.count) {
            delay(300)
            withContext(Dispatchers.IO) { runCatching { renderer.render(page + 1, 1600) } }
        }
    }

    val showChrome = !fullscreen || controls || search
    Scaffold(
        contentWindowInsets = if (fullscreen) WindowInsets(0, 0, 0, 0) else WindowInsets.systemBars,
        topBar = {
            if (showChrome)
                TopAppBar(
                    title = {
                        Text(
                            doc?.title ?: "PDF",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (fullscreen) {
                                    fullscreen = false
                                    controls = true
                                } else vm.closeDocument()
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                search = !search
                                interaction++
                            }
                        ) {
                            Icon(Icons.Default.Search, "搜索文字")
                        }
                        IconButton(onClick = { appearance = true }) {
                            Icon(Icons.Default.Tune, "阅读设置")
                        }
                        IconButton(
                            onClick = {
                                toolMode = ""
                                tools = true
                            }
                        ) {
                            Icon(Icons.Default.MoreVert, "PDF 工具")
                        }
                    },
                )
        },
        bottomBar = {
            if (showChrome)
                Surface(tonalElevation = 2.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        ToolAction(Icons.Default.GridView, "页面", session != null) {
                            thumbnails = true
                        }
                        ToolAction(Icons.Default.Draw, "批注", !busy) { vm.beginMarks() }
                        ToolAction(Icons.Default.SwapHoriz, "转换", !busy) {
                            toolMode = ""
                            tools = true
                        }
                        ToolAction(Icons.Default.Share, "分享", file != null) { file?.let(onShare) }
                        ToolAction(
                            if (fullscreen) Icons.Default.FullscreenExit
                            else Icons.Default.Fullscreen,
                            if (fullscreen) "退出全屏" else "全屏",
                            true,
                        ) {
                            fullscreen = !fullscreen
                            controls = !fullscreen
                            interaction++
                        }
                    }
                }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (search)
                Column(Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        singleLine = true,
                        label = { Text("搜索 PDF 文字层") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            TextButton(enabled = !busy, onClick = { vm.searchPdf(query) }) {
                                Text("查找")
                            }
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (hits.isEmpty()) "无结果 · 扫描件可先添加 OCR"
                            else "${hitIndex + 1} / ${hits.size}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            enabled = hits.isNotEmpty(),
                            onClick = {
                                hitIndex = (hitIndex + 1) % hits.size
                                goTo(hits[hitIndex].page)
                            },
                        ) {
                            Text("下一处")
                        }
                    }
                }
            Box(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .background(
                        if (paperTone == 2) Color(0xFF181C1D)
                        else MaterialTheme.colorScheme.surfaceContainer
                    )
            ) {
                val s = session
                if (s == null)
                    Text(loadError ?: "正在打开 PDF…", Modifier.align(Alignment.Center).padding(32.dp))
                else if (horizontal)
                    HorizontalPager(
                        pager,
                        userScrollEnabled =
                            spreads.getOrNull(pager.currentPage).orEmpty().all {
                                (transforms[it]?.zoom ?: 1f) <= 1.01f
                            },
                        reverseLayout = viewOptions.rightToLeft,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 0,
                    ) { group ->
                        val pages = spreads[group]
                        Row(
                            Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            (if (viewOptions.rightToLeft) pages.reversed() else pages).forEach {
                                index ->
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    ReaderPage(
                                        s,
                                        index,
                                        transforms[index] ?: PageTransform(),
                                        paperTone,
                                        true,
                                        hits.filter { it.page == index } +
                                            listOfNotNull(
                                                focusedText
                                                    ?.takeIf { it.first == index }
                                                    ?.second
                                                    ?.let {
                                                        PdfSearch.Hit(
                                                            index,
                                                            it.text,
                                                            it.left,
                                                            it.top,
                                                            it.right,
                                                            it.bottom,
                                                        )
                                                    }
                                            ),
                                        {
                                            transforms[index] = it
                                            interaction++
                                        },
                                        {
                                            activePage = index
                                            controls = !controls
                                            interaction++
                                        },
                                        onLongPress = { p ->
                                            activePage = index
                                            textPage = index
                                            textPoint = p
                                            interaction++
                                        },
                                        onLinkTap = { p -> activateLink(index, p) },
                                        edgeTurning = edgeTurning,
                                        onEdgeTurn = ::flipAtEdge,
                                        onEdgeArm = ::edgeHint,
                                        viewport = viewOptions.viewport(index),
                                        fitWidth = viewOptions.fitWidth,
                                        initializeAtTop = !transforms.containsKey(index),
                                        preserveImageColor = viewOptions.preserveImageColor,
                                    )
                                }
                            }
                        }
                    }
                else
                    LazyColumn(
                        state = list,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        userScrollEnabled = transform.zoom <= 1.01f,
                    ) {
                        items(s.count, key = { it }) { index ->
                            ReaderPage(
                                s,
                                index,
                                transforms[index] ?: PageTransform(),
                                paperTone,
                                false,
                                hits.filter { it.page == index } +
                                    listOfNotNull(
                                        focusedText
                                            ?.takeIf { it.first == index }
                                            ?.second
                                            ?.let {
                                                PdfSearch.Hit(
                                                    index,
                                                    it.text,
                                                    it.left,
                                                    it.top,
                                                    it.right,
                                                    it.bottom,
                                                )
                                            }
                                    ),
                                {
                                    transforms[index] = it
                                    interaction++
                                },
                                {
                                    activePage = index
                                    controls = !controls
                                    interaction++
                                },
                                onLongPress = { p ->
                                    activePage = index
                                    textPage = index
                                    textPoint = p
                                    interaction++
                                },
                                onLinkTap = { p -> activateLink(index, p) },
                                edgeTurning = edgeTurning,
                                onEdgeTurn = ::flipAtEdge,
                                onEdgeArm = ::edgeHint,
                                viewport = viewOptions.viewport(index),
                                fitWidth = viewOptions.fitWidth,
                                initializeAtTop = !transforms.containsKey(index),
                                preserveImageColor = viewOptions.preserveImageColor,
                            )
                        }
                    }
                if (edgeNotice.isNotBlank())
                    Text(
                        edgeNotice,
                        modifier =
                            Modifier.align(Alignment.BottomCenter)
                                .padding(bottom = 80.dp)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                                    MaterialTheme.shapes.medium,
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                if (navigationBack.isNotEmpty())
                    TextButton(
                        onClick = { returnToAnchor() },
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        colors =
                            ButtonDefaults.textButtonColors(
                                containerColor =
                                    MaterialTheme.colorScheme.surface.copy(alpha = .94f)
                            ),
                    ) {
                        Text("返回阅读处")
                    }
                if (s != null)
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    jumpText = "${page + 1}"
                                    jump = true
                                }
                            ) {
                                Text("${page + 1} / ${s.count}")
                            }
                            if (transform.zoom > 1.01f)
                                TextButton(onClick = { transforms[page] = PageTransform() }) {
                                    Text("恢复缩放")
                                }
                            if (!showChrome)
                                IconButton(
                                    onClick = {
                                        fullscreen = false
                                        controls = true
                                    }
                                ) {
                                    Icon(Icons.Default.FullscreenExit, "退出全屏")
                                }
                        }
                    }
            }
        }
    }
    if (navigation && file != null)
        ReaderNavigationSheet(file, documentInfo, bookmarks, page, ::navigate) {
            navigation = false
        }
    textPage?.let { selectedPage ->
        if (file != null && doc != null)
            ReaderTextSheet(
                file,
                selectedPage,
                doc!!.id,
                doc!!.title,
                textPoint,
                onFocus = { focusedText = selectedPage to it },
                onLocate = { goTo(selectedPage) },
                onDismiss = {
                    textPage = null
                    textPoint = null
                },
            )
    }
    if (appearance)
        AlertDialog(
            onDismissRequest = { appearance = false },
            title = { Text("阅读设置") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("翻页方式", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "放大到页边，再滑一次翻页",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            edgeTurning,
                            {
                                edgeTurning = it
                                cn.paperforge.app.UserSettings.set("edgeTurn", it)
                            },
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(horizontal, { changeMode(true) }, label = { Text("左右翻页") })
                        FilterChip(!horizontal, { changeMode(false) }, label = { Text("连续滚动") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                bookmarks =
                                    if (page in bookmarks) bookmarks - page else bookmarks + page
                                doc?.id?.let { preferences.bookmarks(it, bookmarks) }
                            }
                        ) {
                            Text(if (page in bookmarks) "移除此页书签" else "收藏此页")
                        }
                        TextButton(
                            onClick = {
                                appearance = false
                                bookmarkDialog = true
                            }
                        ) {
                            Text("书签 ${bookmarks.size}")
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        OutlinedButton(
                            onClick = {
                                appearance = false
                                navigation = true
                            }
                        ) {
                            Text("目录与链接")
                        }
                        OutlinedButton(
                            onClick = {
                                appearance = false
                                textPage = page
                                textPoint = null
                            }
                        ) {
                            Text("文字与朗读")
                        }
                    }
                    Text(
                        documentInfo.labels.getOrNull(page)?.let { "页面标签：$it · 物理页 ${page + 1}" }
                            ?: "物理页 ${page + 1}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    ReaderDisplaySettings(viewOptions, page, ::changeView)
                    Text("纸张显示", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("原色", "暖纸", "夜读").forEachIndexed { index, title ->
                            FilterChip(
                                paperTone == index,
                                { paperTone = index },
                                label = { Text(title) },
                            )
                        }
                    }
                    Text(
                        "双指缩放，放大后单指移动；双击放大或恢复。全屏轻触页面显示工具。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = {
                            appearance = false
                            rangeDialog = true
                        }
                    ) {
                        Text("抽页 / 旋转")
                    }
                    OutlinedButton(
                        onClick = {
                            appearance = false
                            compressDialog = true
                        }
                    ) {
                        Text("压缩 PDF")
                    }
                    OutlinedButton(
                        onClick = {
                            appearance = false
                            doc?.let { vm.beginAssembly(listOf(it.id)) }
                        }
                    ) {
                        Text("页面编排")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { appearance = false }) { Text("完成") } },
        )
    if (bookmarkDialog)
        AlertDialog(
            onDismissRequest = { bookmarkDialog = false },
            title = { Text("阅读书签") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (bookmarks.isEmpty()) item { Text("在阅读设置中收藏当前页") }
                    items(bookmarks.size) { index ->
                        val n = bookmarks.sorted()[index]
                        TextButton(
                            onClick = {
                                goTo(n)
                                bookmarkDialog = false
                            }
                        ) {
                            Text("第 ${n + 1} 页")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { bookmarkDialog = false }) { Text("关闭") } },
        )
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
                    label = { Text("物理页 1 – ${session?.count ?: 0}，或标签:iii") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val label =
                            documentInfo.labels.indexOfFirst {
                                it.equals(jumpText.removePrefix("标签:"), true)
                            }
                        val n =
                            if (jumpText.startsWith("标签:")) (label + 1).takeIf { it > 0 }
                            else jumpText.toIntOrNull() ?: (label + 1).takeIf { it > 0 }
                        val s = session
                        if (n != null && s != null && n in 1..s.count) {
                            goTo(n - 1)
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
                            doc?.let { vm.beginAssembly(listOf(it.id)) }
                        }
                    ) {
                        Text("编排")
                    }
                    TextButton(onClick = { jump = false }) { Text("取消") }
                }
            },
        )
    if (tools) PdfToolsDialog(vm, toolMode) { tools = false }
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
                                            runCatching { s.render(n, 320) }.getOrNull()
                                        }
                                }
                            Column(
                                Modifier.clickable {
                                    goTo(n)
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
