package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.paperforge.app.data.PageResources
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.data.origins

/**
 * #31: the grouped「更多」sheet. It only re-hosts the existing editor actions; every callback is
 * the same ViewModel method the old single-column dialog called. The sheet closes first so a global
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
    onAutoSplit: () -> Unit,
    onBookSplit: () -> Unit,
    onStitch: () -> Unit,
    onOrigins: () -> Unit,
    onRotate: (Int) -> Unit,
    onAutoDetect: () -> Unit,
    onDewarp: () -> Unit,
    onDewarpTrace: () -> Unit,
    onRetouch: () -> Unit,
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxHeight(0.92f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("扫描页面工具", style = MaterialTheme.typography.titleLarge)
            Text(
                "高频操作保留在底部一级；其余按组收纳。",
                Modifier.padding(top = 2.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            MoreGroup("识别与文字")
            MoreItem("识字（OCR 识别／查看结果）", !busy) { fire(onOcr) }
            MoreItem("框选识别与同屏校对", !busy) { fire(onProofread) }
            MoreItem("重新识别本页", !busy) { fire(onRecognize) }
            MoreItem("识别全部页面文字", !busy) { fire(onRecognizeAll) }
            if (active.ocrText != null)
                MoreItem("识别记录与文字校正", !busy) { fire(onOcr) }
            Box(Modifier.padding(vertical = 6.dp)) { OcrOptionsControl() }

            MoreGroup("页面与结构")
            MoreItem("页面网格与排序", !busy) { fire(onPageGrid) }
            MoreItem("添加页面…（拍摄／导入／重拍／复制）", !busy) { fire(onAddPage) }
            MoreItem("拆分双页 · 自动书脊", !busy) { fire(onAutoSplit) }
            MoreItem("书籍双页与书脊", !busy) { fire(onBookSplit) }
            MoreItem("长票据拼接与接缝调整", !busy) { fire(onStitch) }
            if (active.origins().isNotEmpty())
                MoreItem("页面来源与原件关系", !busy) { fire(onOrigins) }

            MoreGroup("旋转")
            MoreItem("顺时针旋转 90°", !busy) { fire { onRotate(90) } }
            MoreItem("旋转 180°", !busy) { fire { onRotate(180) } }
            MoreItem("旋转 270°", !busy) { fire { onRotate(270) } }

            MoreGroup("修复与增强")
            MoreItem("自动裁边", !busy) { fire(onAutoDetect) }
            MoreItem("曲面校正", !busy) { fire(onDewarp) }
            if (PageResources.entries(active).any { it.role == "dewarp-grid" })
                MoreItem("曲面来源与网格", !busy) { fire(onDewarpTrace) }
            MoreItem("清理与修复 · 另存副本", !busy) { fire(onRetouch) }

            MoreGroup("智能")
            MoreItem("翻译与智能工具", !busy) { fire(onIntelligence) }

            MoreGroup("导出与记录")
            MoreItem("导出 PDF / 图片包…", !busy) { fire(onExport) }
            MoreItem("票据信息（OCR 字段核对／CSV、XLSX）", !busy) { fire(onReceipt) }
            MoreItem("全部页面导出 JPG 图片包", !busy) { fire { onExportImages("jpg") } }
            MoreItem("全部页面导出 PNG 图片包", !busy) { fire { onExportImages("png") } }
            MoreItem("保存本页诊断包（含原图）", !busy) { fire(onDiagnostics) }

            MoreGroup("文档")
            MoreItem("重命名文档", !busy) { fire(onRename) }
            MoreItem("全屏检查（隐藏工具栏）", true) { fire(onFullscreen) }

            Text(
                "删除 / 裁剪 / 滤镜 / 识字 / 加页 仍在底部一级；旋转已按裁决移入本菜单。",
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * #31: the alternative add-page entry. One tap on「加页」still goes straight to the camera; this
 * sheet is reached by long-pressing「加页」or from「更多 · 添加页面…」.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorAddPageSheet(
    hasActive: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCapture: () -> Unit,
    onImport: () -> Unit,
    onRetake: () -> Unit,
    onDuplicate: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            Text("添加页面", style = MaterialTheme.typography.titleLarge)
            Text(
                "点「加页」直接进入拍摄；这里提供其余方式。",
                Modifier.padding(top = 2.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun MoreGroup(title: String) {
    Spacer(Modifier.height(10.dp))
    Text(
        title,
        Modifier.padding(top = 2.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider()
}

@Composable
private fun MoreItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text(label, Modifier.weight(1f), textAlign = TextAlign.Start)
    }
}
