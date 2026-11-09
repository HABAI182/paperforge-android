package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.paperforge.app.data.PageResources
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.data.origins

/**
 * #31: the grouped「更多」sheet. It only re-hosts the existing editor actions; every callback is the
 * same ViewModel method the old single-column dialog called. The sheet closes first so a global
 * dialog (OCR result, export, intelligence) never stacks on top of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorMoreSheet(
    active: PageRow,
    busy: Boolean,
    onDismiss: () -> Unit,
    onOcr: () -> Unit,
    onProofread: () -> Unit,
    onRecognize: () -> Unit,
    onRecognizeAll: () -> Unit,
    onPageGrid: () -> Unit,
    onAddPage: () -> Unit,
    onDelete: () -> Unit,
    onAutoSplit: () -> Unit,
    onBookSplit: () -> Unit,
    onStitch: () -> Unit,
    onOrigins: () -> Unit,
    onRotate: (Int) -> Unit,
    onAutoDetect: () -> Unit,
    onDewarp: () -> Unit,
    onDewarpTrace: () -> Unit,
    onAnnotate: () -> Unit,
    onRetouch: () -> Unit,
    onWatermark: () -> Unit,
    onIntelligence: () -> Unit,
    onExport: () -> Unit,
    onReceipt: () -> Unit,
    onExportImages: (String) -> Unit,
    onDiagnostics: () -> Unit,
    onRename: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val fire: (() -> Unit) -> Unit = { action ->
        onDismiss()
        action()
    }
    var group by remember { mutableStateOf("") }
    val sections =
        listOf(
            WorkspaceSection("recognize", "识别与文字", "识字、校对和识别设置", Icons.Default.TextFields),
            WorkspaceSection("pages", "页面编排", "加页、排序、双页与旋转", Icons.Default.ViewModule),
            WorkspaceSection("enhance", "画面与修复", "去水印、展平和文字高清", Icons.Default.AutoFixHigh),
            WorkspaceSection("export", "导出与票据", "PDF、图片包与字段核对", Icons.Default.IosShare),
            WorkspaceSection("smart", "智能工具", "翻译与自选智能服务", Icons.Default.AutoAwesome),
            WorkspaceSection("document", "文档与记录", "命名、全屏和处理记录", Icons.Default.Description),
        )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxHeight(.92f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("扫描工具", style = MaterialTheme.typography.headlineSmall)
            if (group.isBlank()) {
                Text("按用途选择，常用操作仍可从编辑器直接使用。", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton({ fire(onWatermark) }, Modifier.weight(1f), enabled = !busy) {
                        Text("去水印")
                    }
                    FilledTonalButton({ fire(onRetouch) }, Modifier.weight(1f), enabled = !busy) {
                        Text("高清与修复")
                    }
                }
                SectionCardGrid(sections) { group = it }
            } else {
                TextButton(onClick = { group = "" }) {
                    Icon(Icons.Default.ChevronLeft, null)
                    Text("全部分类")
                }
                Text(
                    sections.first { it.id == group }.title,
                    style = MaterialTheme.typography.titleLarge,
                )
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        when (group) {
                            "recognize" -> {
                                MoreItem("识字与识别结果", !busy) { fire(onOcr) }
                                MoreItem("框选识别与同屏校对", !busy) { fire(onProofread) }
                                MoreItem("重新识别本页", !busy) { fire(onRecognize) }
                                MoreItem("识别全部页面", !busy) { fire(onRecognizeAll) }
                                OcrOptionsControl()
                            }
                            "pages" -> {
                                MoreItem("页面网格与排序", !busy) { fire(onPageGrid) }
                                MoreItem("添加页面", !busy) { fire(onAddPage) }
                                MoreItem("删除本页", !busy) { fire(onDelete) }
                                MoreItem("自动拆分双页", !busy) { fire(onAutoSplit) }
                                MoreItem("书籍双页与书脊", !busy) { fire(onBookSplit) }
                                MoreItem("长票据拼接", !busy) { fire(onStitch) }
                                MoreItem("顺时针旋转 90°", !busy) { fire { onRotate(90) } }
                                MoreItem("旋转 180°", !busy) { fire { onRotate(180) } }
                                MoreItem("逆时针旋转 90°", !busy) { fire { onRotate(270) } }
                            }
                            "enhance" -> {
                                MoreItem("标注与打码 · 马赛克/文字/画笔", !busy) {
                                    fire(onAnnotate)
                                }
                                MoreItem("去水印", !busy) { fire(onWatermark) }
                                MoreItem("自动裁边", !busy) { fire(onAutoDetect) }
                                MoreItem("曲面校正", !busy) { fire(onDewarp) }
                                MoreItem("高清与修复", !busy) { fire(onRetouch) }
                                if (PageResources.entries(active).any { it.role == "dewarp-grid" })
                                    MoreItem("曲面来源与网格", !busy) { fire(onDewarpTrace) }
                            }
                            "export" -> {
                                MoreItem("导出 PDF", !busy) { fire(onExport) }
                                MoreItem("票据信息核对", !busy) { fire(onReceipt) }
                                MoreItem("导出 JPG 图片包", !busy) { fire { onExportImages("jpg") } }
                                MoreItem("导出 PNG 图片包", !busy) { fire { onExportImages("png") } }
                            }
                            "smart" -> MoreItem("翻译与智能工具", !busy) { fire(onIntelligence) }
                            "document" -> {
                                MoreItem("重命名文档", !busy) { fire(onRename) }
                                MoreItem("全屏检查", true) { fire(onFullscreen) }
                                if (active.origins().isNotEmpty())
                                    MoreItem("页面来源与原件关系", !busy) { fire(onOrigins) }
                                MoreItem("保存本页诊断包（含原图）", !busy) { fire(onDiagnostics) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * #31: the alternative add-page entry. One tap on「加页」still goes straight to the camera; this sheet
 * is reached by long-pressing「加页」or from「更多 · 添加页面…」.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorAddPageSheet(
    hasActive: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCapture: () -> Unit,
    onImport: () -> Unit,
    onLibrary: () -> Unit,
    onRetake: () -> Unit,
    onDuplicate: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("添加页面", style = MaterialTheme.typography.titleLarge)
            Text(
                "点「加页」直接进入拍摄；这里提供其余方式。",
                Modifier.padding(top = 2.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MoreItem("从 PaperForge 文档库加页", !busy) {
                onDismiss()
                onLibrary()
            }
            MoreItem("继续拍摄（新页）", !busy) {
                onDismiss()
                onCapture()
            }
            MoreItem("导入图片（可多选）", !busy) {
                onDismiss()
                onImport()
            }
            if (hasActive) {
                MoreItem("重拍本页（替换当前页）", !busy) {
                    onDismiss()
                    onRetake()
                }
                MoreItem("复制当前页", !busy) {
                    onDismiss()
                    onDuplicate()
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    }
}

@Composable
private fun MoreItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    var info by remember { mutableStateOf(false) }
    if (info) ToolCapabilityDialog(scanToolDescription(label)) { info = false }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            Text(label, Modifier.weight(1f), textAlign = TextAlign.Start)
        }
        IconButton(onClick = { info = true }) {
            Icon(Icons.Default.Info, "$label · 能力与格式说明", Modifier.size(20.dp))
        }
    }
}
