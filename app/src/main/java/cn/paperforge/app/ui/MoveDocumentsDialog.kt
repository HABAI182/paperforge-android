package cn.paperforge.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.DocumentRow
import cn.paperforge.app.data.libraryFolder

/** A visible destination list shared by single-document and multi-selection moves. */
@Composable
fun MoveDocumentsDialog(
    vm: MainViewModel,
    ids: List<String>,
    documents: List<DocumentRow>,
    paths: List<String>,
    onMoved: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val members = documents.filter { it.id in ids }
    var target by
        remember(ids) {
            mutableStateOf(members.map { libraryFolder(it.folder) }.distinct().singleOrNull() ?: "")
        }
    var search by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    val folders =
        (paths + documents.map { libraryFolder(it.folder) })
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移入文件夹 · ${ids.size} 份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    members.take(2).joinToString("、") { it.title } +
                        if (members.size > 2) "等" else "",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    search,
                    { search = it },
                    label = { Text("查找分类文件夹") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        (listOf("") + folders).filter {
                            it.contains(search, true) || (it.isBlank() && search.isBlank())
                        },
                        key = { it },
                    ) { path ->
                        val count = documents.count { libraryFolder(it.folder) == path }
                        Card(
                            onClick = {
                                target = path
                                creating = false
                            },
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (!creating && target == path)
                                            MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    if (path.isBlank()) Icons.Default.FolderOff
                                    else Icons.Default.Folder,
                                    null,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        path.substringAfterLast('/').ifBlank { "未分类" },
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        if ('/' in path) "$path · $count 份" else "$count 份文档",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (!creating && target == path)
                                    Icon(Icons.Default.CheckCircle, "目标文件夹")
                            }
                        }
                    }
                }
                TextButton(onClick = { creating = !creating }) {
                    Icon(Icons.Default.CreateNewFolder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("新建文件夹并移入")
                }
                if (creating)
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("新文件夹名称，可用 / 分级") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && (!creating || libraryFolder(name).isNotBlank()),
                onClick = {
                    vm.categorizeDocuments(ids.toSet(), if (creating) name else target, null)
                    onMoved()
                    onDismiss()
                },
            ) {
                Text(
                    "移入${if (creating) "新文件夹" else target.substringAfterLast('/').ifBlank { "未分类" }}"
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun ExistingFolderDocuments(
    documents: List<DocumentRow>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    OutlinedTextField(
        search,
        { search = it },
        label = { Text("选择已有文件 · 已选 ${selected.size} 份") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
        items(documents.filter { it.title.contains(search, true) }, key = { it.id }) { doc ->
            val checked = doc.id in selected
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onChange(if (checked) selected - doc.id else selected + doc.id) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked, null)
                Column(Modifier.weight(1f)) {
                    Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "分类 · ${doc.folder.ifBlank { "未分类" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
