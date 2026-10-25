package cn.paperforge.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.data.libraryFolder

@Composable
fun FolderField(
    value: String,
    onValueChange: (String) -> Unit,
    paths: List<String>,
    label: String = "文件夹，使用 / 分级",
    rootLabel: String = "未分类",
) {
    var choosing by remember { mutableStateOf(false) }
    OutlinedTextField(
        value,
        onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { choosing = true }) { Icon(Icons.Default.FolderOpen, "浏览文件夹") }
        },
    )
    if (choosing)
        FolderPickerDialog(
            paths,
            value,
            rootLabel,
            onDismiss = { choosing = false },
            onChoose = {
                choosing = false
                onValueChange(it)
            },
        )
}

@Composable
private fun FolderPickerDialog(
    paths: List<String>,
    value: String,
    rootLabel: String,
    onDismiss: () -> Unit,
    onChoose: (String) -> Unit,
) {
    var location by remember { mutableStateOf(libraryFolder(value)) }
    val folders = remember(paths) { paths.map(::libraryFolder).filter { it.isNotBlank() } }
    val prefix = if (location.isBlank()) "" else "$location/"
    val children =
        folders
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    val segments = location.split('/').filter { it.isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择文件夹") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { location = "" }) { Text(rootLabel) }
                    segments.forEachIndexed { index, part ->
                        Text("/")
                        TextButton(
                            onClick = { location = segments.take(index + 1).joinToString("/") }
                        ) {
                            Text(part)
                        }
                    }
                }
                Text(
                    if (location.isBlank()) rootLabel else location,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (children.isEmpty())
                    Text("没有下级文件夹。新路径可在分类栏中输入。", style = MaterialTheme.typography.bodySmall)
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(children) { name ->
                        val path = prefix + name
                        OutlinedButton(
                            onClick = { location = path },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                name,
                                Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${folders.count {it==path || it.startsWith("$path/")}}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(location) }) { Text("选择此位置") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
