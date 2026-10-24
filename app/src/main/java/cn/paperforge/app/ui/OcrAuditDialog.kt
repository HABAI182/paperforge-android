package cn.paperforge.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.paperforge.app.data.OcrTextVersion

@Composable
fun OcrAuditDialog(versions: List<OcrTextVersion>, onDismiss: () -> Unit, onUse: (String) -> Unit) {
    var selected by remember(versions) { mutableIntStateOf(versions.lastIndex.coerceAtLeast(0)) }
    val version = versions.getOrNull(selected)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("识别与校正记录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    versions.forEachIndexed { i, item ->
                        FilterChip(
                            selected = i == selected,
                            onClick = { selected = i },
                            label = { Text(item.label) },
                        )
                    }
                }
                SelectionContainer {
                    Text(
                        version?.text ?: "暂无记录",
                        Modifier.fillMaxWidth()
                            .heightIn(min = 120.dp, max = 350.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { version?.let { onUse(it.text) } }, enabled = version != null) {
                Text("使用此文字")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回校正") } },
    )
}
