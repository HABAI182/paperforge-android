package cn.paperforge.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.pdf.*
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.*

private val swatches =
    listOf(
        0xFF000000.toInt() to "黑",
        0xFF404040.toInt() to "深灰",
        0xFFC62828.toInt() to "红",
        0xFF1565C0.toInt() to "蓝",
        0xFF2E7D32.toInt() to "绿",
        0xFFE65100.toInt() to "橙",
    )

/**
 * Native text editor. The page is the editing surface: tap a text object, or press and hold to select words and
 * drag to extend; the bar below formats the selection. Every change is checked by rendering the edited page before
 * it enters the draft, so what is shown is what will be saved.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NativePdfEditorScreen(vm: MainViewModel) {
    val source by vm.nativeEditSource.collectAsState()
    val doc = source ?: return
    val input = doc.pdfPath?.let(::File) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val motion = LocalPaperMotion.current
    val clipboard = LocalClipboardManager.current
    val feedback = selectionFeedback()
    val session = remember(doc.id, input) { NativeEditSession(context.applicationContext, input) }
    DisposableEffect(session) { onDispose { session.close() } }
    var page by rememberSaveable(doc.id) { mutableIntStateOf(vm.nativeEditPage.value.coerceAtLeast(0)) }
    var count by remember(doc.id) { mutableIntStateOf(0) }
    var draft by remember(doc.id) { mutableStateOf<NativeEditDraft?>(null) }
    var fonts by remember(doc.id) { mutableStateOf(listOf<NativeFontChoice>()) }
    var attempt by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var saved by remember(doc.id) { mutableStateOf<NativeEditDraft?>(null) }

    LaunchedEffect(doc.id, input, attempt) {
        try {
            val loaded =
                withContext(Dispatchers.IO) {
                    val sha = PdfAssembly.sha256(input)
                    Triple(session.open(), NativeEditDraft.load(vm.repo, doc, sha), NativeEditFonts.list(vm.repo, doc))
                }
            count = loaded.first
            draft = loaded.second
            fonts = loaded.third
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            message = error.message ?: "编辑草稿无法读取"
        }
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
    LaunchedEffect(count) { if (count > 0 && page >= count) page = 0 }
    val fontFiles = fonts.filter { it.path.isNotEmpty() }.associate { it.sha256 to File(it.path) }

    // The source page's objects (original text and editability).
    val originals by
        produceState<Result<List<NativePdfEditor.TextObject>>?>(null, session, page, attempt) {
            value = null
            value = runCatching { session.original(page) }.onFailure { if (it is CancellationException) throw it }
        }
    val originalList = originals?.getOrNull().orEmpty()
    val originalMap = remember(originalList) { originalList.associateBy { it.key } }

    // The shown page: the source page, or a one-page preview with this page's edits.
    var shown by remember(doc.id, page) { mutableStateOf<NativeEditSession.Shown?>(null) }
    val pageEdits = draft?.edits.orEmpty().filter { it.page == page }
    LaunchedEffect(page, pageEdits, fonts) {
        if (pageEdits.isEmpty()) {
            shown = null
            return@LaunchedEffect
        }
        if (shown != null && shownEdits(shown) == pageEdits) return@LaunchedEffect
        try {
            busy = true
            val next = session.preview(page, pageEdits, fontFiles)
            shown?.let { session.discard(it.file) }
            shown = next.also { it.file.setLastModified(System.currentTimeMillis()) }
            shownEditsCache[next.file.path] = pageEdits
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            message = error.message ?: "预览未能生成"
        } finally {
            busy = false
        }
    }
    val objects = shown?.objects ?: originalList
    val shownFile = shown?.file ?: input
    val shownIndex = if (shown != null) 0 else page
    val pdf by
        produceState<Result<PdfSession>?>(null, shownFile) {
            var pending: PdfSession? = null
            try {
                val s = withContext(Dispatchers.IO) { PdfSession(shownFile).also { pending = it } }
                value = Result.success(s)
                pending = null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                value = Result.failure(error)
            } finally {
                pending?.close()
            }
        }
    val pdfSession = pdf?.getOrNull()
    DisposableEffect(pdfSession) { onDispose { pdfSession?.close() } }
    var transform by remember(doc.id, page) { mutableStateOf(PageTransform()) }

    // Selection: a character range over shown objects, or a set of whole objects from the list.
    val model = remember(objects, page) { NativeSelection.page(page, objects) }
    var selection by remember(doc.id, page) { mutableStateOf<ReaderTextSelection?>(null) }
    var checked by remember(doc.id, page) { mutableStateOf(setOf<String>()) }
    var pendingSegments by remember { mutableStateOf<List<NativeSegment>?>(null) }
    LaunchedEffect(model) {
        // Keep the selection on the same characters after a change re-reads the page.
        val keep = pendingSegments ?: selection?.let { NativeSelection.segments(it) }
        pendingSegments = null
        selection =
            keep?.let { segs ->
                val indices =
                    model.units.indices.filter { i ->
                        val u = model.units[i]
                        val key = model.ordered[u.line].source.removePrefix("obj:")
                        segs.any { it.key == key && u.span.start < it.end && u.span.end > it.start }
                    }
                if (indices.isEmpty()) null else ReaderTextSelection(model, indices.first(), indices.last())
            }
    }
    val segments: List<NativeSegment> =
        selection?.let { NativeSelection.segments(it) } ?: NativeSelection.whole(objects, checked)
    val selectedObjects = segments.mapNotNull { s -> objects.firstOrNull { it.key == s.key } }
    val blocked = selectedObjects.filter { originalMap[it.key]?.editable == false }
    val selectedText = NativeSelection.text(objects, segments)
    val edits = draft?.edits.orEmpty()
    val dirty = draft != null && edits.isNotEmpty() && draft != saved

    /** Checks [next] by rendering this page, then puts it into the draft. */
    fun commit(next: List<NativePdfEditor.Edit>, keep: List<NativeSegment>? = null, done: String = "") {
        val current = draft ?: return
        if (next == current.edits) return
        scope.launch {
            busy = true
            try {
                val nextPage = next.filter { it.page == page }
                val result = if (nextPage.isEmpty()) null else session.preview(page, nextPage, fontFiles)
                shown?.let { session.discard(it.file) }
                if (result != null) shownEditsCache[result.file.path] = nextPage
                shown = result
                pendingSegments = keep
                draft = current.change(next)
                val squeezed = result?.outcomes?.filter { it.scale < .999f }.orEmpty()
                val overflow = result?.outcomes?.any { it.overflow } == true
                message =
                    listOfNotNull(
                            done.ifEmpty { null },
                            if (squeezed.isNotEmpty())
                                "为放进原宽度，新文字横向压缩到 ${(squeezed.minOf { it.scale } * 100).roundToInt()}%"
                            else null,
                            if (overflow) "有文字超出原来的宽度，可能与后面的内容重叠，请检查" else null,
                        )
                        .joinToString("；")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "这项修改无法安全应用，已保持原样"
            } finally {
                busy = false
            }
        }
    }

    fun restyle(style: NativeStyle, label: String) {
        val current = draft ?: return
        if (blocked.isNotEmpty() && blocked.size == selectedObjects.size) {
            message = blocked.first().reason.ifEmpty { "所选文字不能直接修改" }
            return
        }
        commit(NativeEditOps.restyle(current.edits, originalMap, segments, style), segments, label)
    }

    var sheet by remember { mutableStateOf("") }
    var editText by remember { mutableStateOf("") }
    var fit by remember { mutableStateOf(true) }
    var leaving by remember { mutableStateOf(false) }
    var originalMissing by remember { mutableStateOf<List<String>?>(null) }

    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                scope.launch {
                    busy = true
                    try {
                        val choice = withContext(Dispatchers.IO) { NativeEditFonts.install(vm.repo, doc, uri) }
                        fonts = fonts.filter { it.id != choice.id } + choice
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

    fun applyText() {
        val current = draft ?: return
        val clean = editText.replace("\r", "")
        scope.launch {
            // New text in a subset font often lacks glyphs: use a matching built-in font and say so.
            val first = selectedObjects.firstOrNull { originalMap[it.key]?.editable == true }
            val runsAtStart =
                first?.let { item ->
                    val seg = segments.first { it.key == item.key }
                    var at = 0
                    NativeEditOps.runs(current.edits, originalMap.getValue(item.key)).firstOrNull { r ->
                        val hit = seg.start < at + r.text.length || r.text.isEmpty()
                        at += r.text.length
                        hit
                    }
                }
            var style: NativeStyle? = null
            var note = "已修改文字"
            if (first != null && (runsAtStart == null || runsAtStart.font == NativeRun.ORIGINAL)) {
                val lacking = session.missingInOriginal(first.key, clean)
                if (lacking.isNotEmpty()) {
                    val sub = NativeFontLibrary.substitute(context, originalMap.getValue(first.key), clean)
                    if (sub != null) {
                        style = NativeStyle(font = sub.id)
                        note = "原字体只嵌入了用到的字，缺少「${lacking.take(4).joinToString("")}」，新文字改用「${sub.nameZh}」"
                    }
                }
            }
            val next = NativeEditOps.replace(current.edits, originalMap, segments, clean) { style }
            val withFit = next.map { e -> if (segments.any { it.key == e.key }) e.copy(fit = fit) else e }
            sheet = ""
            commit(withFit, null, note)
        }
    }

    BackHandler(enabled = selection != null || checked.isNotEmpty() || sheet.isNotEmpty()) {
        when {
            sheet.isNotEmpty() -> sheet = ""
            else -> {
                selection = null
                checked = emptySet()
            }
        }
    }
    BackHandler(enabled = selection == null && checked.isEmpty() && sheet.isEmpty() && dirty) { leaving = true }
    fun leave() {
        vm.selected.value = doc
        vm.screen.value = "reader"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("编辑文字")
                        Text(
                            buildString {
                                append("第 ${page + 1} / ${count.coerceAtLeast(1)} 页")
                                if (edits.isNotEmpty())
                                    append(if (dirty) " · ${edits.size} 处修改未保存" else " · 已保存")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("native-edit-status"),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (dirty) leaving = true else leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回阅读")
                    }
                },
                actions = {
                    IconButton(enabled = draft?.canUndo == true && !busy, onClick = { draft?.let { draft = it.undo(); message = "已撤销" } }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "撤销")
                    }
                    IconButton(enabled = draft?.canRedo == true && !busy, onClick = { draft?.let { draft = it.redo(); message = "已重做" } }) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "重做")
                    }
                    TextButton(
                        enabled = edits.isNotEmpty() && !busy && dirty,
                        onClick = {
                            val value = draft ?: return@TextButton
                            vm.queueNativeEdit(value.request())
                            saved = value
                            message = "正在保存为新的 PDF「${doc.title} · 原生文字编辑」，原文件保持不变；完成后在文库中打开"
                        },
                        modifier = Modifier.testTag("native-edit-save"),
                    ) {
                        Text("保存")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // What kind of page this is, when it is not ordinary native text.
            val kind =
                when {
                    originals == null -> null
                    originals?.isFailure == true -> "这一页的文字无法读取：${originals?.exceptionOrNull()?.message}"
                    originalList.none { it.text.isNotBlank() } ->
                        "这一页没有可编辑的文字：它是扫描图片，或文字已转成曲线。可在阅读器中识别文字（OCR）后复制。"
                    originalList.filter { it.text.isNotBlank() }.all { it.invisible } ->
                        "这一页是扫描图片，文字只是隐藏的识别层（OCR）。可以选择、复制，但修改它不会改变看到的图像，所以不在这里编辑。"
                    else -> null
                }
            AnimatedVisibility(kind != null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(kind.orEmpty(), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (pdfSession != null)
                    ReaderPage(
                        pdfSession,
                        shownIndex,
                        transform,
                        0,
                        true,
                        // Objects chosen in the list are outlined on the page.
                        if (selection == null)
                            objects.filter { it.key in checked && it.corners.size == 4 }.map {
                                PdfSearch.Hit(page, it.text, it.corners[0].x, it.corners[0].y, it.corners[2].x, it.corners[2].y)
                            }
                        else emptyList(),
                        onTransform = { transform = it },
                        onTap = {
                            selection = null
                            checked = emptySet()
                        },
                        onLinkTap = { p ->
                            // A tap selects the whole text object under the finger.
                            val item =
                                model.units.indices
                                    .firstOrNull { i ->
                                        val u = model.units[i].span
                                        p.x in (u.left - .004f)..(u.right + .004f) && p.y in (u.top - .004f)..(u.bottom + .004f)
                                    }
                                    ?.let { model.ordered[model.units[it].line].source.removePrefix("obj:") }
                            if (item != null) {
                                checked = emptySet()
                                selection = NativeSelection.objectSelection(model, item)
                                feedback()
                                true
                            } else false
                        },
                        onSelectStart = { p ->
                            val s = model.select(p)
                            if (s != null) {
                                checked = emptySet()
                                selection = s
                            }
                            s != null
                        },
                        onSelectDrag = { p ->
                            selection =
                                selection?.let { s ->
                                    val at = s.model.nearest(p)
                                    if (at != null && at < s.first) s.move(true, p) else s.move(false, p)
                                }
                        },
                        edgeTurning = false,
                        fitWidth = true,
                        initializeAtTop = true,
                        selection = selection,
                        onSelectionMove = { start, p -> selection = selection?.move(start, p) },
                    )
                else if (pdf?.isFailure == true)
                    Text("页面读取失败：${pdf?.exceptionOrNull()?.message}", Modifier.padding(16.dp))
                else CircularProgressIndicator(Modifier.align(Alignment.Center))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            AnimatedVisibility(message.isNotBlank(), enter = fadeIn(motion.settle()), exit = fadeOut(motion.settle())) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).testTag("native-edit-message"))
                    IconButton(onClick = { message = "" }) { Icon(Icons.Default.Close, "关闭提示") }
                }
            }
            // The contextual bar: page navigation without a selection, tools with one.
            val hasSelection = segments.isNotEmpty()
            AnimatedContent(
                hasSelection,
                transitionSpec = {
                    (slideInVertically(motion.lively()) { it / 2 } + fadeIn(motion.settle())) togetherWith
                        (slideOutVertically(motion.settle()) { it / 2 } + fadeOut(motion.settle()))
                },
                label = "native-edit-bar",
            ) { active ->
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    if (!active)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(enabled = page > 0, onClick = { page-- }) { Icon(Icons.Default.ChevronLeft, "上一页") }
                            Text("${page + 1} / ${count.coerceAtLeast(1)}")
                            IconButton(enabled = page + 1 < count, onClick = { page++ }) { Icon(Icons.Default.ChevronRight, "下一页") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { sheet = "list" }, modifier = Modifier.testTag("native-edit-list")) {
                                Icon(Icons.AutoMirrored.Filled.ViewList, null)
                                Spacer(Modifier.width(4.dp))
                                Text("文字列表")
                            }
                            TextButton(
                                enabled = model.units.isNotEmpty(),
                                onClick = { selection = ReaderTextSelection(model, 0, model.units.lastIndex) },
                            ) {
                                Text("全选本页")
                            }
                            TextButton(onClick = { vm.openWatermarks(doc) }, modifier = Modifier.testTag("native-edit-watermark")) {
                                Text("去水印")
                            }
                        }
                    else
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "已选 ${selectedText.replace("\n", "").length} 字 · ${segments.size} 个对象" +
                                        if (blocked.isNotEmpty()) "（${blocked.size} 个不可改）" else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f).testTag("native-edit-count"),
                                )
                                IconButton(onClick = { selection = null; checked = emptySet() }) {
                                    Icon(Icons.Default.Close, "取消选择")
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val canEdit = blocked.size < selectedObjects.size && !busy
                                ToolChip("编辑文字", Icons.Default.Edit, canEdit, "native-edit-text") {
                                    editText = selectedText
                                    fit = edits.firstOrNull { e -> segments.any { it.key == e.key } }?.fit ?: true
                                    sheet = "text"
                                }
                                ToolChip("字体", Icons.Default.FontDownload, canEdit, "native-edit-font") {
                                    originalMissing = null
                                    scope.launch {
                                        val first = selectedObjects.firstOrNull { originalMap[it.key]?.editable == true }
                                        originalMissing = first?.let { session.missingInOriginal(it.key, selectedText) }
                                    }
                                    sheet = "font"
                                }
                                val sizes = selectedObjects.map { o -> edits.firstOrNull { it.key == o.key }?.effectiveRuns?.firstNotNullOfOrNull { it.size } ?: o.size }
                                IconButton(enabled = canEdit, onClick = { restyle(NativeStyle(sizeFactor = 1 / 1.1f), "字号缩小") }, modifier = Modifier.testTag("native-edit-smaller")) {
                                    Icon(Icons.Default.TextDecrease, "缩小字号")
                                }
                                Text(
                                    sizes.distinct().let { if (it.size == 1) "%.1f pt".format(it.first()) else "多种字号" },
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.clip(MaterialTheme.shapes.small).combinedClickable(enabled = canEdit) { sheet = "size" }.padding(6.dp),
                                )
                                IconButton(enabled = canEdit, onClick = { restyle(NativeStyle(sizeFactor = 1.1f), "字号放大") }, modifier = Modifier.testTag("native-edit-bigger")) {
                                    Icon(Icons.Default.TextIncrease, "放大字号")
                                }
                                ToolChip("颜色", Icons.Default.Palette, canEdit, "native-edit-color") { sheet = "color" }
                                ToolChip("复制", Icons.Default.ContentCopy, true, "native-edit-copy") {
                                    clipboard.setText(AnnotatedString(selectedText))
                                    message = "已复制 ${selectedText.length} 个字符"
                                }
                                ToolChip("恢复原样", Icons.Default.Restore, segments.any { s -> edits.any { it.key == s.key } }, "native-edit-restore") {
                                    draft?.let { d -> commit(NativeEditOps.restore(d.edits, segments.map { it.key }.toSet()), segments, "已恢复所选对象") }
                                }
                                if (selection != null)
                                    ToolChip("选整行", Icons.Default.HorizontalRule, true, "native-edit-line") { selection = selection?.line() }
                            }
                            if (blocked.isNotEmpty())
                                Text(
                                    "不可改：" + blocked.map { it.reason }.distinct().joinToString("；"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                        }
                }
            }
        }
    }

    when (sheet) {
        "text" ->
            ModalBottomSheet(onDismissRequest = { sheet = "" }, sheetState = rememberModalBottomSheetState(true)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("编辑所选文字", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (segments.size > 1) "每行对应页面上的一行；行数不变时逐行替换，否则合并到第一行。文字不会自动换行。"
                        else "替换后的文字放在原来的位置，后面的文字不会移动。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(editText, { editText = it }, Modifier.fillMaxWidth().testTag("native-edit-field"), minLines = 1, maxLines = 8)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(fit, { fit = it })
                        Text("超出原宽度时横向压缩")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { sheet = "" }) { Text("取消") }
                        Button(onClick = { applyText() }, enabled = !busy, modifier = Modifier.testTag("native-edit-apply")) { Text("应用") }
                    }
                }
            }
        "font" ->
            NativeFontPicker(
                selectedText,
                fonts,
                selectedObjects.firstNotNullOfOrNull { o -> edits.firstOrNull { it.key == o.key }?.effectiveRuns?.firstOrNull()?.font } ?: "ORIGINAL",
                selectedObjects.firstOrNull()?.font.orEmpty(),
                originalMissing,
                onPick = { id, sha ->
                    sheet = ""
                    if (id != "ORIGINAL") NativeFontPrefs.use(context, id)
                    restyle(NativeStyle(font = id, fontSha256 = sha), "已更换字体")
                },
                onImport = { importer.launch(arrayOf("font/ttf", "application/x-font-ttf", "application/octet-stream")) },
                onDismiss = { sheet = "" },
            )
        "color" ->
            ModalBottomSheet(onDismissRequest = { sheet = "" }) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("文字颜色", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                        swatches.forEach { (argb, name) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape).background(Color(argb))
                                        .combinedClickable {
                                            sheet = ""
                                            restyle(NativeStyle(color = argb), "已改为$name")
                                        }
                                        .testTag("native-color-$name")
                                )
                                Text(name, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Text("只改填充颜色；描边文字的描边一起改。", style = MaterialTheme.typography.bodySmall)
                }
            }
        "size" -> {
            var value by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { sheet = "" },
                title = { Text("字号") },
                text = {
                    OutlinedTextField(value, { value = it }, label = { Text("pt（1–200）") }, singleLine = true, modifier = Modifier.testTag("native-size-field"))
                },
                confirmButton = {
                    TextButton(onClick = {
                        val v = value.toFloatOrNull()
                        if (v != null && v in 1f..200f) {
                            sheet = ""
                            restyle(NativeStyle(size = v), "字号改为 $v pt")
                        } else message = "字号应为 1–200 之间的数字"
                    }) { Text("应用") }
                },
                dismissButton = { TextButton(onClick = { sheet = "" }) { Text("取消") } },
            )
        }
        "list" ->
            NativeObjectList(
                objects = objects,
                originals = originalMap,
                edits = edits,
                checked = checked,
                onChecked = { checked = it; selection = null },
                onShow = { sheet = "" },
                onDismiss = { sheet = "" },
            )
    }
    if (leaving)
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("还有未导出的修改") },
            text = { Text("修改已作为草稿保留在这份文档里，下次打开可继续。要现在保存为新的 PDF 吗？原文件不会改变。") },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    draft?.let { vm.queueNativeEdit(it.request()); saved = it }
                    leave()
                }) { Text("保存并返回") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { leaving = false; leave() }) { Text("稍后再说") }
                }
            },
        )
}

private val shownEditsCache = java.util.concurrent.ConcurrentHashMap<String, List<NativePdfEditor.Edit>>()

private fun shownEdits(shown: NativeEditSession.Shown?) = shown?.let { shownEditsCache[it.file.path] }

@Composable
private fun ToolChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, tag: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
        modifier = Modifier.testTag(tag),
    )
}

/**
 * The page's text objects with multi-selection: tap toggles, long press selects the range from the last tapped row,
 * plus select all / none / invert. What is checked is outlined on the page and formatted together.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NativeObjectList(
    objects: List<NativePdfEditor.TextObject>,
    originals: Map<String, NativePdfEditor.TextObject>,
    edits: List<NativePdfEditor.Edit>,
    checked: Set<String>,
    onChecked: (Set<String>) -> Unit,
    onShow: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var anchor by remember { mutableStateOf<String?>(null) }
    val listed = objects.filter { it.text.isNotBlank() && !it.invisible }
    val shown = listed.filter { query.isBlank() || it.text.contains(query, true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本页文字 · 已选 ${checked.size}/${listed.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("native-list-count"))
                Button(onClick = onShow, enabled = checked.isNotEmpty()) { Text("在页面上编辑") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onChecked(shown.filter { originals[it.key]?.editable != false }.map { it.key }.toSet() + checked) }, modifier = Modifier.testTag("native-list-all")) { Text("全选") }
                TextButton(onClick = { onChecked(emptySet()) }) { Text("全不选") }
                TextButton(onClick = { onChecked(shown.map { it.key }.toSet() - checked + (checked - shown.map { it.key }.toSet())) }) { Text("反选") }
            }
            Text("点按勾选；长按另一行，选中从上次点按到这一行的连续范围。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(query, { query = it }, label = { Text("筛选") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.key }) { item ->
                    val on = item.key in checked
                    val editable = originals[item.key]?.editable != false
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .combinedClickable(
                                onClick = {
                                    anchor = item.key
                                    onChecked(if (on) checked - item.key else checked + item.key)
                                },
                                onLongClick = {
                                    val a = shown.indexOfFirst { it.key == anchor }.takeIf { it >= 0 } ?: shown.indexOf(item)
                                    val b = shown.indexOf(item)
                                    val range = shown.subList(minOf(a, b), maxOf(a, b) + 1).filter { originals[it.key]?.editable != false }
                                    onChecked(checked + range.map { it.key })
                                    anchor = item.key
                                },
                            )
                            .padding(vertical = 4.dp)
                            .testTag("native-row-${item.index}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(on, null, enabled = editable)
                        Column(Modifier.weight(1f)) {
                            Text(item.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                buildString {
                                    append("%.1f pt · ".format(item.size))
                                    append(item.font.substringAfter('+').ifEmpty { "未知字体" })
                                    if (edits.any { it.key == item.key }) append(" · 已修改")
                                    if (!editable) append(" · ").append(originals[item.key]?.reason)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
