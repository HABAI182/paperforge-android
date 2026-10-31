package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.data.origins
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun pageBitmap(page: PageRow, edge: Int = 1200, original: Boolean = false): Bitmap? {
    return pagePreview(page, edge, original).bitmap
}

data class PagePreview(val bitmap: Bitmap? = null, val error: String? = null)

@Composable
fun pagePreview(page: PageRow, edge: Int = 1200, original: Boolean = false): PagePreview {
    val preview by
        produceState(PagePreview(), page, edge, original) {
            value =
                withContext(Dispatchers.Default) {
                    runCatching {
                            if (original) ScanProcessor.decode(File(page.original), edge)
                            else ScanProcessor.render(page, edge)
                        }
                        .fold(
                            onSuccess = { PagePreview(bitmap = it) },
                            onFailure = { PagePreview(error = it.message ?: "预览无法打开") },
                        )
                }
        }
    return preview
}

@Composable
fun EditorScreen(vm: MainViewModel, onImport: () -> Unit) {
    val pages by vm.pages.collectAsState()
    val doc by vm.selected.collectAsState()
    val busy by vm.busy.collectAsState()
    val tasks by vm.localTasks.collectAsState()
    val pager = rememberPagerState(pageCount = { pages.size })
    val active = pages.getOrNull(pager.currentPage)
    var intelligence by remember { mutableStateOf(false) }
    if (intelligence && doc != null)
        IntelligenceDialog(doc!!, pages.size, pager.currentPage, pages = pages) {
            intelligence = false
        }
    val target by vm.editorTarget.collectAsState()
    val pageTarget by vm.editorPageTarget.collectAsState()
    LaunchedEffect(pageTarget, pages, doc?.id) {
        val anchor = pageTarget
        if (
            anchor != null &&
                anchor.first == doc?.id &&
                pages.isNotEmpty() &&
                pages.all { it.documentId == anchor.first }
        ) {
            val index = pages.indexOfFirst { it.id == anchor.second }
            if (index >= 0) {
                pager.scrollToPage(index)
                vm.editorPageTarget.value = null
            }
        }
    }
    val focus by vm.libraryFocus.collectAsState()
    val query by vm.query.collectAsState()
    LaunchedEffect(target, pages, doc?.id) {
        val value = target
        if (
            value != null &&
                value.first == doc?.id &&
                pages.isNotEmpty() &&
                pages.all { it.documentId == doc?.id }
        ) {
            pager.scrollToPage(value.second.coerceIn(0, pages.lastIndex))
            vm.editorTarget.value = null
        }
    }
    val highlights =
        remember(focus, pages, query) {
            val page = pages.firstOrNull { it.id == focus?.pageId }
            val terms = CjkSearch.normalize(query).split(Regex("\\s+")).filter { it.isNotBlank() }
            if (page?.ocrLayout == null || terms.isEmpty()) emptyMap()
            else
                mapOf(
                    page.id to
                        OcrLayout.decode(page.ocrLayout)
                            .lines
                            .filter { line ->
                                terms.any { CjkSearch.normalize(line.text).contains(it) }
                            }
                            .map { it.corners }
                )
        }
    var crop by rememberSaveable(doc?.id) { mutableStateOf(false) }
    var name by remember(doc?.id) { mutableStateOf(doc?.title ?: "") }
    var export by rememberSaveable(doc?.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var adjust by rememberSaveable(doc?.id) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var addPage by remember { mutableStateOf(false) }
    var receipts by remember { mutableStateOf(false) }
    // #31: the grouped menu lives here but the rename / fullscreen dialogs belong to the layout.
    var renameTick by remember { mutableIntStateOf(0) }
    var fullscreenTick by remember { mutableIntStateOf(0) }
    var bookSplit by remember { mutableStateOf(false) }
    var dewarp by remember { mutableStateOf(false) }
    var trace by remember { mutableStateOf(false) }
    var origins by remember { mutableStateOf(false) }
    var retouch by remember(doc?.id) { mutableStateOf<PageRow?>(null) }
    val repairPage = retouch
    if (repairPage != null) {
        ScanRetouchScreen(
            vm.repo,
            repairPage,
            onBack = { retouch = null },
            onSubmit = { request ->
                retouch = null
                vm.retouch(repairPage, request)
            },
        )
        return
    }
    ScanEditorLayout(
        pages,
        pager,
        name,
        busy,
        onAction = { action ->
            when (action) {
                "back" -> vm.closeDocument()
                "tasks" -> vm.screen.value = "tasks"
                "capture" -> vm.screen.value = "capture"
                "addPageSheet" -> addPage = true
                "import" -> onImport()
                "pages" -> vm.screen.value = "pages"
                "export" -> export = true
                "undo" -> vm.undo()
                "redo" -> vm.redo()
                "crop" -> crop = true
                "delete" -> confirmDelete = true
                "adjust" -> adjust = true
                "retouch" -> retouch = active
                "more" -> more = true
                "applyAll" -> active?.let(vm::applyAll)
                "ocr" -> active?.let(vm::openOcr)
            }
        },
        onRename = { value ->
            name = value
            doc?.let { vm.rename(it, value) }
        },
        onFilter = { page, filter -> vm.save(page.copy(filter = filter)) },
        suggestName = { doc?.let { vm.namingSuggestion(it) } },
        highlights = highlights,
        taskStatus =
            tasks
                .firstOrNull {
                    it.documentId == doc?.id &&
                        (it.pageId == active?.id ||
                            it.targetPageId == active?.id ||
                            active == null) &&
                        it.state != cn.paperforge.app.data.LocalTaskState.SUCCEEDED
                }
                ?.let { "${taskStateName(it.state)} · ${it.detail}" },
        renameRequest = renameTick,
        fullscreenRequest = fullscreenTick,
    )
    if (crop && active != null)
        CropDialog(
            active,
            onDismiss = { crop = false },
            onSave = { quad, aspect ->
                vm.save(
                    active.copy(
                        corners = quad.encode(),
                        outputAspect = aspect,
                        detectionConfidence = null,
                        detectionMethod = "manual",
                    )
                )
                crop = false
            },
        )
    if (bookSplit && active != null)
        BookSplitDialog(
            active,
            onDismiss = { bookSplit = false },
            onApply = { top, bottom, keep ->
                vm.splitBook(active, top, bottom, keep)
                bookSplit = false
            },
        )
    if (trace && active != null) DewarpTraceDialog(active) { trace = false }
    if (origins && active != null) PageOriginsDialog(vm, active) { origins = false }
    if (dewarp && active != null) {
        val page = active
        var strength by
            rememberSaveable(active.id) {
                mutableFloatStateOf(UserSettings[SettingKeys.DewarpStrength])
            }
        var previewStrength by remember { mutableFloatStateOf(strength) }
        var previewError by remember { mutableStateOf<String?>(null) }
        var dewarpNote by remember { mutableStateOf("") }
        val preview by
            produceState<Bitmap?>(null, page, previewStrength) {
                value = null
                previewError = null
                value =
                    withContext(Dispatchers.Default) {
                        runCatching {
                                val image =
                                    ScanProcessor.render(
                                        page.copy(
                                            filter = "ORIGINAL",
                                            brightness = 0f,
                                            contrast = 1f,
                                        ),
                                        1000,
                                    )
                                try {
                                    val result =
                                        cn.paperforge.app.imaging.PageDewarp.process(
                                            image,
                                            previewStrength,
                                        )
                                    dewarpNote = result.note
                                    result.bitmap
                                } finally {
                                    image.recycle()
                                }
                            }
                            .onFailure { previewError = it.message ?: "曲面预览未完成" }
                            .getOrNull()
                    }
            }
        val previewBitmap = preview
        DisposableEffect(previewBitmap) { onDispose { previewBitmap?.recycle() } }
        AlertDialog(
            onDismissRequest = { dewarp = false },
            title = { Text("曲面校正") },
            text = {
                Column {
                    Box(
                        Modifier.fillMaxWidth().height(240.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (preview != null)
                            Image(
                                preview!!.asImageBitmap(),
                                "曲面校正预览",
                                Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        else if (previewError != null) Text(previewError!!)
                        else CircularProgressIndicator()
                    }
                    Text("强度 %.0f%%".format(strength * 100))
                    Slider(
                        strength,
                        { strength = it },
                        valueRange = 0f..1f,
                        onValueChangeFinished = { previewStrength = strength },
                    )
                    Text(
                        dewarpNote.ifBlank { "正在估计文字行曲率" } + "；预览满意后应用，可撤销。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = preview != null && !busy,
                    onClick = {
                        dewarp = false
                        vm.dewarpPage(page, strength)
                    },
                ) {
                    Text("应用")
                }
            },
            dismissButton = { TextButton(onClick = { dewarp = false }) { Text("取消") } },
        )
    }
    if (confirmDelete && active != null)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这一页？") },
            text = { Text("移除本页，可通过撤销恢复。原图保留在编辑历史中。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deletePage(active)
                        confirmDelete = false
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("保留") } },
        )
    if (adjust && active != null) {
        var brightness by rememberSaveable(active.id) { mutableFloatStateOf(active.brightness) }
        var contrast by rememberSaveable(active.id) { mutableFloatStateOf(active.contrast) }
        var strength by rememberSaveable(active.id) { mutableFloatStateOf(active.enhanceStrength) }
        val adjusted =
            active.copy(brightness = brightness, contrast = contrast, enhanceStrength = strength)
        // Debounce expensive rendering while dragging, without persisting preview edits.
        var previewPage by remember(active.id) { mutableStateOf(adjusted) }
        LaunchedEffect(adjusted) {
            kotlinx.coroutines.delay(100)
            previewPage = adjusted
        }
        val preview = pagePreview(previewPage, edge = 1000)
        SpaciousToolDialog(
            onDismissRequest = { adjust = false },
            title = { Text("增强与明暗") },
            text = {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val controlsHeight = maxHeight * .48f
                    val previewContent: @Composable (Modifier) -> Unit = { modifier ->
                        Box(modifier, contentAlignment = Alignment.Center) {
                            preview.bitmap?.let {
                                Image(
                                    it.asImageBitmap(),
                                    "增强效果实时预览",
                                    Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                            if (preview.error != null) Text(preview.error)
                            else if (preview.bitmap == null) CircularProgressIndicator()
                        }
                    }
                    val controls: @Composable (Modifier) -> Unit = { modifier ->
                        Column(modifier.verticalScroll(rememberScrollState())) {
                            Text("去阴影强度 %.0f%%".format(strength * 100))
                            Slider(
                                strength,
                                { strength = it },
                                valueRange = 0f..1f,
                                enabled = active.filter != "ORIGINAL",
                            )
                            Text("亮度 %+.0f".format(brightness * 100))
                            Slider(brightness, { brightness = it }, valueRange = -0.5f..0.5f)
                            Text("对比度 %.2f".format(contrast))
                            Slider(contrast, { contrast = it }, valueRange = 0.5f..1.8f)
                        }
                    }
                    if (maxWidth > 500.dp && maxWidth > maxHeight) {
                        Row(
                            Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            previewContent(Modifier.weight(1f).fillMaxHeight())
                            controls(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            previewContent(Modifier.fillMaxWidth().weight(1f))
                            controls(Modifier.fillMaxWidth().heightIn(max = controlsHeight))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.save(
                            active.copy(
                                brightness = brightness,
                                contrast = contrast,
                                enhanceStrength = strength,
                            )
                        )
                        adjust = false
                    }
                ) {
                    Text("应用")
                }
            },
            dismissButton = { TextButton(onClick = { adjust = false }) { Text("取消") } },
        )
    }
    if (export) ScanExportDialog(vm, name, pages.size, { export = false })
    if (more && active != null)
        EditorMoreSheet(
            active = active,
            busy = busy,
            onDismiss = { more = false },
            onOcr = { vm.openOcr(active) },
            onProofread = { vm.openProofreading(active) },
            onRecognize = { vm.recognize(active) },
            onRecognizeAll = { vm.recognizeAll() },
            onPageGrid = { vm.screen.value = "pages" },
            onAddPage = { addPage = true },
            onAutoSplit = { vm.autoSplitSpread(active) },
            onBookSplit = { bookSplit = true },
            onStitch = { vm.stitchScanPages() },
            onOrigins = { origins = true },
            onRotate = { degrees ->
                vm.save(active.copy(rotation = (active.rotation + degrees) % 360))
            },
            onAutoDetect = { vm.detectPage(active) },
            onDewarp = { dewarp = true },
            onDewarpTrace = { trace = true },
            onRetouch = { retouch = active },
            onIntelligence = { intelligence = true },
            onExport = { export = true },
            onReceipt = { receipts = true },
            onExportImages = { format -> vm.images(format) },
            onDiagnostics = { vm.exportScanCase(active) },
            onRename = { renameTick++ },
            onFullscreen = { fullscreenTick++ },
        )
    if (addPage)
        EditorAddPageSheet(
            hasActive = active != null,
            busy = busy,
            onDismiss = { addPage = false },
            onCapture = { vm.screen.value = "capture" },
            onImport = onImport,
            onRetake = {
                active?.let { vm.replacement.value = it.id }
                vm.screen.value = "capture"
            },
            onDuplicate = { active?.let { vm.duplicate(it) } },
        )
    val document = doc
    if (receipts && document != null) ReceiptDocumentDialog(vm, document) { receipts = false }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    longPressLabel: String? = null,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val longPressAction = onLongPress
    Column(
        modifier
            .widthIn(min = 48.dp)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                if (enabled) {
                    onClick(label) {
                        onClick()
                        true
                    }
                    longPressAction?.let { action ->
                        onLongClick(longPressLabel ?: "更多选项") {
                            action()
                            true
                        }
                    }
                } else disabled()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                null,
                tint =
                    if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
            )
        }
        if (showLabel) Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
