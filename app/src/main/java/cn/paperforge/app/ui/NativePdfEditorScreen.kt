package cn.paperforge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.model.PointN
import cn.paperforge.app.pdf.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NativePdfEditorScreen(vm: MainViewModel) {
    val source by vm.nativeEditSource.collectAsState()
    val doc = source ?: return
    val input = doc.pdfPath?.let(::File) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable(doc.id) { mutableIntStateOf(0) }
    var draft by remember(doc.id) { mutableStateOf<NativeEditDraft?>(null) }
    var fonts by
        remember(doc.id) {
            mutableStateOf(
                listOf(
                    NativeFontChoice("ORIGINAL", "沿用原字体"),
                    NativeFontChoice("CJK", "PaperForge 中英字体"),
                )
            )
        }
    var attempt by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    var selectedKey by remember(doc.id, page) { mutableStateOf<String?>(null) }
    var query by remember(doc.id, page) { mutableStateOf("") }
    var preview by remember(doc.id) { mutableStateOf<File?>(null) }
    var showPreview by remember(doc.id) { mutableStateOf(false) }
    val previewFiles = remember(doc.id) { mutableListOf<File>() }
    DisposableEffect(doc.id) { onDispose { previewFiles.forEach { it.delete() } } }

    LaunchedEffect(doc.id, input, attempt) {
        try {
            val loaded =
                withContext(Dispatchers.IO) {
                    val sha = PdfAssembly.sha256(input)
                    NativeEditDraft.load(vm.repo, doc, sha) to NativeEditFonts.list(vm.repo, doc)
                }
            draft = loaded.first
            fonts = loaded.second
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            message = error.message ?: "编辑草稿无法读取"
        }
    }
    val inspected by
        produceState<Result<Pair<Int, List<NativePdfEditor.TextObject>>>?>(
            null,
            input,
            page,
            attempt,
        ) {
            value = null
            value =
                withContext(Dispatchers.IO) {
                    runCatching { NativePdfEditor.inspect(input, page) }
                        .onFailure { if (it is CancellationException) throw it }
                }
        }
    val objects =
        inspected?.getOrNull()?.second.orEmpty().filter {
            it.text.isNotEmpty() || it.reason !in setOf("空文字对象", "不可见文字层，请使用OCR校对")
        }
    val count = inspected?.getOrNull()?.first ?: 0
    val selected = objects.firstOrNull { it.key == selectedKey }
    var text by remember { mutableStateOf("") }
    var fontId by remember { mutableStateOf("ORIGINAL") }
    var fontSize by remember { mutableStateOf("") }
    var fit by remember { mutableStateOf(true) }
    val controlsState = rememberLazyListState()
    LaunchedEffect(selectedKey) { if (selectedKey != null) controlsState.animateScrollToItem(1) }
    LaunchedEffect(selectedKey, draft?.cursor, draft?.states) {
        val item = selected
        val edit = draft?.edits?.firstOrNull { it.key == selectedKey }
        text = edit?.text ?: item?.text.orEmpty()
        fontId = edit?.font ?: "ORIGINAL"
        fontSize = (edit?.size ?: item?.size)?.toString().orEmpty()
        fit = edit?.fit ?: true
    }
    fun update(value: NativeEditDraft) {
        draft = value
        showPreview = false
        preview = null
        message = ""
    }
    LaunchedEffect(draft) {
        draft?.let { value ->
            try {
                withContext(Dispatchers.IO) { NativeEditDraft.save(vm.repo, doc, value) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "草稿未能保存"
            }
        }
    }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                scope.launch {
                    busy = true
                    try {
                        val choice =
                            withContext(Dispatchers.IO) {
                                NativeEditFonts.install(vm.repo, doc, uri)
                            }
                        fonts = (fonts.filter { it.id != choice.id } + choice)
                        fontId = choice.id
                        message = "已导入 ${choice.label}"
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        message = error.message ?: "字体无法导入，请使用静态TrueType字体"
                    } finally {
                        busy = false
                    }
                }
        }
    val shownFile = if (showPreview) preview ?: input else input
    val sessionResult by
        produceState<Result<PdfSession>?>(null, shownFile) {
            value = null
            var pending: PdfSession? = null
            try {
                val session =
                    withContext(Dispatchers.IO) { PdfSession(shownFile).also { pending = it } }
                value = Result.success(session)
                pending = null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                value = Result.failure(error)
            } finally {
                pending?.close()
            }
        }
    val session = sessionResult?.getOrNull()
    DisposableEffect(session) { onDispose { session?.close() } }
    var transform by remember(shownFile, page) { mutableStateOf(PageTransform()) }
    fun pick(point: PointN): Boolean {
        if (showPreview) return false
        val item =
            objects
                .filter { it.corners.size == 4 }
                .filter {
                    point.x in it.corners[0].x..it.corners[2].x &&
                        point.y in it.corners[0].y..it.corners[2].y
                }
                .minByOrNull {
                    (it.corners[2].x - it.corners[0].x) * (it.corners[2].y - it.corners[0].y)
                }
        selectedKey = item?.key
        if (item != null) toolsOpen = true
        return item != null
    }
    val pendingDraft =
        draft?.let { value ->
            if (selected == null || !selected.editable) value
            else {
                val points = fontSize.toFloatOrNull()
                val choice = fonts.firstOrNull { it.id == fontId }
                if (points == null || points <= 0f || points > 1000f || choice == null) null
                else {
                    val changed =
                        text != selected.text || fontId != "ORIGINAL" || points != selected.size
                    val edit =
                        NativePdfEditor.Edit(
                            page,
                            selected.index,
                            selected.text,
                            text,
                            fontId,
                            points,
                            fit,
                            choice.sha256,
                        )
                    val existing = value.edits.any { it.key == selected.key }
                    val next =
                        if (!changed) value.edits.filter { it.key != selected.key }
                        else if (existing)
                            value.edits.map { if (it.key == selected.key) edit else it }
                        else value.edits + edit
                    value.change(next)
                }
            }
        }
    val saveable = pendingDraft?.edits?.isNotEmpty() == true && !busy
    Scaffold(
        topBar = {
            if (!fullscreen)
                TopAppBar(
                    title = { Text("原生文字编辑") },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                vm.selected.value = doc
                                vm.screen.value = "reader"
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回阅读")
                        }
                    },
                    actions = {
                        IconButton(onClick = { fullscreen = true }) {
                            Icon(Icons.Default.Fullscreen, "放大页面")
                        }
                    },
                )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { page-- }, enabled = page > 0 && !busy) { Text("上一页") }
                Text("${page + 1} / ${count.coerceAtLeast(1)}", Modifier.padding(vertical = 12.dp))
                TextButton(onClick = { page++ }, enabled = page + 1 < count && !busy) {
                    Text("下一页")
                }
                FilterChip(!showPreview, { showPreview = false }, label = { Text("原页") })
                FilterChip(
                    showPreview,
                    { showPreview = true },
                    enabled = preview != null,
                    label = { Text("修改预览") },
                )
                if (fullscreen) TextButton(onClick = { fullscreen = false }) { Text("返回编辑") }
            }
            @Composable
            fun pageView(modifier: Modifier) {
                Box(modifier) {
                    if (session != null)
                        ReaderPage(
                            session,
                            page,
                            transform,
                            0,
                            true,
                            if (!showPreview)
                                selected
                                    ?.corners
                                    ?.takeIf { it.size == 4 }
                                    ?.let { p ->
                                        listOf(
                                            PdfSearch.Hit(
                                                page,
                                                selected.text,
                                                p[0].x,
                                                p[0].y,
                                                p[2].x,
                                                p[2].y,
                                            )
                                        )
                                    }
                                    .orEmpty()
                            else emptyList(),
                            onTransform = { transform = it },
                            onTap = {},
                            onLongPress = { pick(it) },
                            onLinkTap = { pick(it) },
                            edgeTurning = false,
                            fitWidth = true,
                            initializeAtTop = true,
                        )
                    else if (sessionResult?.isFailure == true)
                        Text("页面读取失败：${sessionResult?.exceptionOrNull()?.message}")
                    else CircularProgressIndicator(Modifier.padding(24.dp))
                }
            }
            @Composable
            fun controls(modifier: Modifier) {
                LazyColumn(
                    modifier.padding(horizontal = 16.dp),
                    state = controlsState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item {
                        Text(
                            "点选原页文字，或从下方对象列表选择。只改选中的对象；其他文字、图片、链接和页面位置保留。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = draft?.canUndo == true && !busy,
                                onClick = { update(draft!!.undo()) },
                            ) {
                                Text("撤销")
                            }
                            OutlinedButton(
                                enabled = draft?.canRedo == true && !busy,
                                onClick = { update(draft!!.redo()) },
                            ) {
                                Text("重做")
                            }
                            Text(
                                "${draft?.edits?.size ?: 0} 个对象已修改",
                                Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                    if (selected != null)
                        item {
                            Card {
                                Column(
                                    Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "对象 ${selected.index + 1} · ${selected.font.substringAfter('+')}",
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    if (!selected.editable) Text(selected.reason)
                                    OutlinedTextField(
                                        text,
                                        { text = it },
                                        label = { Text("对象文字（单行）") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        enabled = selected.editable && !busy,
                                    )
                                    var expanded by remember { mutableStateOf(false) }
                                    OutlinedButton(
                                        enabled = selected.editable && !busy,
                                        onClick = { expanded = true },
                                    ) {
                                        Text(fonts.firstOrNull { it.id == fontId }?.label ?: "导入字体")
                                    }
                                    DropdownMenu(expanded, { expanded = false }) {
                                        fonts.forEach { font ->
                                            DropdownMenuItem(
                                                text = { Text(font.label) },
                                                onClick = {
                                                    fontId = font.id
                                                    expanded = false
                                                },
                                            )
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            importer.launch(
                                                arrayOf(
                                                    "font/ttf",
                                                    "application/x-font-ttf",
                                                    "application/octet-stream",
                                                )
                                            )
                                        },
                                        enabled = !busy,
                                    ) {
                                        Text("导入 TTF 字体")
                                    }
                                    OutlinedTextField(
                                        fontSize,
                                        { fontSize = it },
                                        label = { Text("字号 pt") },
                                        singleLine = true,
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row {
                                        Checkbox(fit, { fit = it }, enabled = !busy)
                                        Text("过宽时缩小文字", Modifier.padding(top = 12.dp))
                                    }
                                    val points = fontSize.toFloatOrNull()
                                    val choice = fonts.firstOrNull { it.id == fontId }
                                    val valid =
                                        selected.editable &&
                                            !busy &&
                                            draft != null &&
                                            choice != null &&
                                            points != null &&
                                            points > 0f &&
                                            points <= 1000f
                                    Button(
                                        enabled = valid && pendingDraft != draft,
                                        onClick = { update(pendingDraft!!) },
                                    ) {
                                        Text("应用到草稿")
                                    }
                                    if (fontId != "ORIGINAL")
                                        TextButton(
                                            enabled = valid,
                                            onClick = {
                                                val changed =
                                                    objects
                                                        .filter { it.editable }
                                                        .map { item ->
                                                            val old =
                                                                draft!!.edits.firstOrNull {
                                                                    it.key == item.key
                                                                }
                                                            NativePdfEditor.Edit(
                                                                page,
                                                                item.index,
                                                                item.text,
                                                                old?.text ?: item.text,
                                                                fontId,
                                                                old?.size ?: item.size,
                                                                fit,
                                                                choice!!.sha256,
                                                            )
                                                        }
                                                update(
                                                    draft!!.change(
                                                        draft!!.edits.filter { it.page != page } +
                                                            changed
                                                    )
                                                )
                                            },
                                        ) {
                                            Text("将此字体用于本页文字")
                                        }
                                    if (draft?.edits?.any { it.key == selected.key } == true)
                                        TextButton(
                                            onClick = {
                                                update(
                                                    draft!!.change(
                                                        draft!!.edits.filter {
                                                            it.key != selected.key
                                                        }
                                                    )
                                                )
                                            },
                                            enabled = !busy,
                                        ) {
                                            Text("恢复此对象原样")
                                        }
                                }
                            }
                        }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = saveable,
                                onClick = {
                                    val value = pendingDraft!!
                                    update(value)
                                    val request = value.request()
                                    busy = true
                                    message = ""
                                    scope.launch {
                                        try {
                                            val file =
                                                File(
                                                    context.cacheDir,
                                                    "native-preview-${UUID.randomUUID()}.pdf",
                                                )
                                            previewFiles += file
                                            withContext(Dispatchers.IO) {
                                                NativePdfEditor.apply(
                                                    context,
                                                    input,
                                                    file,
                                                    request,
                                                    fonts
                                                        .filter { it.path.isNotEmpty() }
                                                        .associate { it.sha256 to File(it.path) },
                                                )
                                            }
                                            preview = file
                                            showPreview = true
                                            toolsOpen = false
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (error: Exception) {
                                            message = error.message ?: "修改预览未能生成"
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                            ) {
                                Text("预览修改")
                            }
                            Button(
                                enabled = saveable,
                                onClick = {
                                    val value = pendingDraft!!
                                    update(value)
                                    vm.queueNativeEdit(value.request())
                                },
                            ) {
                                Text("生成修改副本")
                            }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (message.isNotBlank())
                            Text(message, style = MaterialTheme.typography.bodySmall)
                        if (draft == null && message.isNotBlank())
                            TextButton(onClick = { attempt++ }) { Text("重新读取") }
                    }
                    item {
                        OutlinedTextField(
                            query,
                            { query = it },
                            label = { Text("筛选本页文字对象") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (inspected == null) item { Text("正在读取文字对象…") }
                    inspected?.exceptionOrNull()?.let { error ->
                        item {
                            Text("文字读取失败：${error.message}")
                            TextButton(onClick = { attempt++ }) { Text("重试") }
                        }
                    }
                    if (objects.isEmpty() && inspected?.isSuccess == true)
                        item { Text("本页没有可直接改写的文字对象。扫描图或嵌套Form中的文字保留原件，可使用OCR与其他转换工具处理。") }
                    items(
                        objects.filter { query.isBlank() || it.text.contains(query, true) },
                        key = { it.key },
                    ) { item ->
                        OutlinedCard(
                            Modifier.fillMaxWidth().clickable {
                                selectedKey = item.key
                                toolsOpen = true
                            }
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    draft?.edits?.firstOrNull { it.key == item.key }?.text
                                        ?: item.text.ifEmpty { "文字对象 ${item.index + 1}" },
                                    maxLines = 3,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    "#${item.index + 1} · ${item.size} pt · ${item.font.substringAfter('+')}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                if (item.reason.isNotEmpty())
                                    Text(item.reason, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val panelHeight = maxHeight * .88f
                if (fullscreen) pageView(Modifier.fillMaxSize())
                else if (maxWidth >= 720.dp)
                    Row(Modifier.fillMaxSize()) {
                        pageView(Modifier.weight(1.3f).fillMaxHeight())
                        controls(Modifier.weight(1f).fillMaxHeight())
                    }
                else
                    Column(Modifier.fillMaxSize()) {
                        pageView(Modifier.weight(1f).fillMaxWidth())
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = { toolsOpen = true }) { Text("文字与字体工具") }
                            Button(
                                enabled = saveable,
                                onClick = {
                                    val value = pendingDraft!!
                                    update(value)
                                    vm.queueNativeEdit(value.request())
                                },
                            ) {
                                Text("保存副本")
                            }
                        }
                        if (message.isNotBlank())
                            Text(
                                message,
                                Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (toolsOpen)
                            ModalBottomSheet(
                                onDismissRequest = { toolsOpen = false },
                                sheetState =
                                    rememberModalBottomSheetState(skipPartiallyExpanded = true),
                            ) {
                                controls(Modifier.fillMaxWidth().heightIn(max = panelHeight))
                            }
                    }
            }
        }
    }
}
