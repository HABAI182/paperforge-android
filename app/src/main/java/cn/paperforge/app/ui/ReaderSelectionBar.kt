package cn.paperforge.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.pdf.ReaderTextSelection

@Composable
internal fun ReaderSelectionBar(
    selection: ReaderTextSelection,
    onChange: (ReaderTextSelection) -> Unit,
    onCopy: () -> Unit,
    onMark: (String) -> Unit,
    onText: () -> Unit,
    onRegion: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var more by remember { mutableStateOf(false) }
    Surface(
        modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                selection.text,
                Modifier.fillMaxWidth().testTag("reader-selected-text"),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onCopy) { Text("复制") }
                TextButton(onClick = { onMark("highlight") }) { Text("高亮") }
                TextButton(onClick = { onChange(selection.line()) }) { Text("选本行") }
                TextButton(onClick = { onChange(selection.all()) }) { Text("选全页") }
                Box {
                    TextButton(onClick = { more = true }) { Text("更多") }
                    DropdownMenu(more, { more = false }) {
                        listOf("下划线" to "underline", "删除线" to "strikeout").forEach { (name, kind) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    more = false
                                    onMark(kind)
                                },
                            )
                        }
                        HorizontalDivider()
                        listOf("按行顺序" to 0, "先左栏后右栏" to 1, "先右栏后左栏" to 2).forEach { (name, order) ->
                            DropdownMenuItem(
                                text = {
                                    Text((if (selection.model.order == order) "✓ " else "") + name)
                                },
                                onClick = {
                                    more = false
                                    onChange(selection.reordered(order))
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("文字、摘录与朗读") },
                            onClick = {
                                more = false
                                onText()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("区域识字") },
                            onClick = {
                                more = false
                                onRegion()
                            },
                        )
                    }
                }
                TextButton(onClick = onClose) { Text("完成选择") }
            }
            val precise = selection.model.ordered.any { it.source == "ocr" && it.units.isEmpty() }
            Text(
                if (precise) "拖动手柄调整；仅有行框的 OCR 按整行选择" else "拖动两端手柄调整选区 · 双指可放大",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
