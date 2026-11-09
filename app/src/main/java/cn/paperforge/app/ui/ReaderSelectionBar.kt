package cn.paperforge.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    onIntelligence: () -> Unit = {},
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
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // One line, read as running text: line breaks in a selection only waste the space here.
                Text(
                    selection.text.replace(Regex("\\s+"), " ").trim(),
                    Modifier.weight(1f).testTag("reader-selected-text"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${selection.text.count { !it.isWhitespace() }} 字",
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onClose, Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, "完成选择", Modifier.size(20.dp))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                val tight = PaddingValues(horizontal = 2.dp)
                TextButton(onClick = onCopy, Modifier.weight(1f), contentPadding = tight) { Text("复制", maxLines = 1) }
                TextButton(onClick = { onMark("highlight") }, Modifier.weight(1f), contentPadding = tight) { Text("高亮", maxLines = 1) }
                TextButton(onClick = { onChange(selection.line()) }, Modifier.weight(1f), contentPadding = tight) { Text("选本行", maxLines = 1) }
                TextButton(onClick = { onChange(selection.all()) }, Modifier.weight(1f), contentPadding = tight) { Text("选全页", maxLines = 1) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { more = true }, Modifier.fillMaxWidth(), contentPadding = tight) { Text("更多", maxLines = 1) }
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
                        listOf(
                                "自动顺序" to 0,
                                "按行顺序" to 5,
                                "先左栏后右栏" to 1,
                                "先右栏后左栏" to 2,
                                "竖排从右至左" to 3,
                                "竖排从左至右" to 4,
                            )
                            .forEach { (name, order) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (selection.model.order == order) "✓ " else "") +
                                                name
                                        )
                                    },
                                    onClick = {
                                        more = false
                                        onChange(selection.reordered(order))
                                    },
                                )
                            }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("翻译与智能工具") },
                            onClick = {
                                more = false
                                onIntelligence()
                            },
                        )
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
            }
            // Only worth the space when selection really works differently.
            if (selection.model.ordered.any { it.source == "ocr" && it.units.isEmpty() })
                Text(
                    "这页的识别结果只有整行信息，所以按整行选择",
                    Modifier.padding(bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}
