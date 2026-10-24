package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.UserSettings
import cn.paperforge.app.data.PageRow
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: MainViewModel, onImport: () -> Unit) {
    val pages by vm.pages.collectAsState()
    val doc by vm.selected.collectAsState()
    val busy by vm.busy.collectAsState()
    val tasks by vm.localTasks.collectAsState()
    val pager = rememberPagerState(pageCount = { pages.size })
    val active = pages.getOrNull(pager.currentPage)
    val target by vm.editorTarget.collectAsState()
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
    var crop by remember { mutableStateOf(false) }
    var name by remember(doc?.id) { mutableStateOf(doc?.title ?: "") }
    var export by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var paper by remember { mutableStateOf(UserSettings.string("paper", "Auto")) }
    var resolution by remember { mutableStateOf(UserSettings.string("resolution", "STANDARD")) }
    var quality by remember { mutableIntStateOf(UserSettings.number("quality", 90f).toInt()) }
    var adjust by remember { mutableStateOf(false) }
    var searchable by remember { mutableStateOf(UserSettings.bool("searchable", true)) }
    var more by remember { mutableStateOf(false) }
    var bookSplit by remember { mutableStateOf(false) }
    var dewarp by remember { mutableStateOf(false) }
    var trace by remember { mutableStateOf(false) }
    ScanEditorLayout(
        pages,
        pager,
        name,
        busy,
        onAction = { action ->
            when (action) {
                "back" -> vm.screen.value = "home"
                "tasks" -> vm.screen.value = "tasks"
                "capture" -> vm.screen.value = "capture"
                "import" -> onImport()
                "pages" -> vm.screen.value = "pages"
                "export" -> export = true
                "undo" -> vm.undo()
                "redo" -> vm.redo()
                "crop" -> crop = true
                "adjust" -> adjust = true
                "more" -> more = true
                "applyAll" -> active?.let(vm::applyAll)
                "ocr" -> active?.let { vm.recognize(it, true) }
            }
        },
        onRename = { value ->
            name = value
            doc?.let { vm.rename(it, value) }
        },
        onFilter = { page, filter -> vm.save(page.copy(filter = filter)) },
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
    if (dewarp && active != null) {
        val page = active
        var strength by remember { mutableFloatStateOf(UserSettings.number("dewarpStrength", 1f)) }
        var previewStrength by remember { mutableFloatStateOf(strength) }
        var previewError by remember { mutableStateOf<String?>(null) }
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
                                    cn.paperforge.app.imaging.PageDewarp.apply(
                                        image,
                                        previewStrength,
                                    )
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
                    Text("适合弯曲书页。预览满意后应用，可用撤销恢复。", style = MaterialTheme.typography.bodySmall)
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
        var brightness by remember { mutableFloatStateOf(active.brightness) }
        var contrast by remember { mutableFloatStateOf(active.contrast) }
        var strength by remember { mutableFloatStateOf(active.enhanceStrength) }
        AlertDialog(
            onDismissRequest = { adjust = false },
            title = { Text("增强与明暗") },
            text = {
                Column {
                    Text("去阴影强度 %.0f%%".format(strength * 100))
                    Slider(
                        value = strength,
                        onValueChange = { strength = it },
                        valueRange = 0f..1f,
                        enabled = active.filter != "ORIGINAL",
                    )
                    Text("亮度 %+.0f".format(brightness * 100))
                    Slider(
                        value = brightness,
                        onValueChange = { brightness = it },
                        valueRange = -0.5f..0.5f,
                    )
                    Text("对比度 %.2f".format(contrast))
                    Slider(
                        value = contrast,
                        onValueChange = { contrast = it },
                        valueRange = 0.5f..1.8f,
                    )
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
    if (export)
        AlertDialog(
            onDismissRequest = { export = false },
            title = { Text("导出多页 PDF") },
            text = {
                Column {
                    Text("所有 ${pages.size} 页")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(searchable, { searchable = it })
                        Text("离线 OCR · 可搜索文字层")
                    }
                    TextButton(onClick = { paper = "ID" }) {
                        Text(if (paper == "ID") "✓ 证件双面 A4 合版" else "证件双面 A4 合版")
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf("Auto", "A4", "A5", "Letter").forEach { p ->
                            TextButton(onClick = { paper = p }) {
                                Text(
                                    if (p == "Auto") "匹配扫描尺寸" else p,
                                    color =
                                        if (p == paper) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                                "DRAFT" to "草稿 150 dpi",
                                "STANDARD" to "标准 220 dpi",
                                "HIGH" to "高清 300 dpi",
                                "CUSTOM" to
                                    "自定义 ${UserSettings.number("customDpi",300f).toInt()} dpi",
                            )
                            .forEach { (value, label) ->
                                FilterChip(
                                    resolution == value,
                                    { resolution = value },
                                    label = { Text(label) },
                                )
                            }
                    }
                    Text(
                        "分辨率与压缩画质分别设置；源照片像素不足时保留实际清晰度，不虚构像素。固定纸张按比例留边。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("图像质量 $quality")
                    Slider(
                        value = quality.toFloat(),
                        onValueChange = { quality = it.toInt() },
                        valueRange = 50f..100f,
                        steps = 9,
                    )
                    Text("后台生成后可预览、保存到任意文件夹或分享。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        export = false
                        vm.export(paper, quality, name, searchable, resolution)
                    },
                    enabled = !busy,
                ) {
                    Text("生成 PDF")
                }
            },
            dismissButton = { TextButton(onClick = { export = false }) { Text("取消") } },
        )
    if (more && active != null)
        AlertDialog(
            onDismissRequest = { more = false },
            title = { Text("扫描页面工具") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            more = false
                            vm.detectPage(active)
                        }
                    ) {
                        Text("自动裁边")
                    }
                    TextButton(
                        onClick = {
                            more = false
                            dewarp = true
                        }
                    ) {
                        Text("曲面校正")
                    }
                    if (active.processingMetadata != "{}")
                        TextButton(
                            onClick = {
                                more = false
                                trace = true
                            }
                        ) {
                            Text("曲面来源与网格")
                        }
                    TextButton(
                        onClick = {
                            more = false
                            vm.save(active.copy(rotation = (active.rotation + 90) % 360))
                        }
                    ) {
                        Text("旋转本页")
                    }
                    TextButton(
                        onClick = {
                            more = false
                            vm.replacement.value = active.id
                            vm.screen.value = "capture"
                        }
                    ) {
                        Text("重拍本页")
                    }
                    TextButton(
                        onClick = {
                            more = false
                            vm.screen.value = "pages"
                        }
                    ) {
                        Text("页面管理与排序")
                    }
                    TextButton(
                        onClick = {
                            more = false
                            confirmDelete = true
                        }
                    ) {
                        Text("删除本页")
                    }
                    TextButton(
                        onClick = {
                            more = false
                            vm.exportScanCase(active)
                        }
                    ) {
                        Text("保存本页诊断包（含原图）")
                    }
                    TextButton(
                        onClick = {
                            more = false
                            vm.duplicate(active)
                        }
                    ) {
                        Text("复制当前页")
                    }
                    TextButton(
                        onClick = {
                            more = false
                            bookSplit = true
                        }
                    ) {
                        Text("书籍双页与书脊")
                    }
                    TextButton(
                        onClick = {
                            more = false
                            vm.recognizeAll()
                        }
                    ) {
                        Text("识别全部页面文字")
                    }
                    TextButton(
                        onClick = {
                            more = false
                            vm.images("jpg")
                        }
                    ) {
                        Text("全部页面导出 JPG 图片包")
                    }
                    TextButton(
                        onClick = {
                            more = false
                            vm.images("png")
                        }
                    ) {
                        Text("全部页面导出 PNG 图片包")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { more = false }) { Text("关闭") } },
        )
}

@Composable
fun ToolAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        Modifier.widthIn(min = 56.dp)
            .clickable(
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                label,
                tint =
                    if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
