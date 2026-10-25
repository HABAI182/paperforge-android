package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.*

@Composable
fun CopyPagesDialog(
    vm: MainViewModel,
    sourceId: String?,
    sourceTitle: String,
    count: Int,
    move: Boolean,
    onDismiss: () -> Unit,
    onChoose: (String?, String) -> Unit,
) {
    val documents by vm.repo.documents.collectAsState(emptyList())
    var title by remember { mutableStateOf(sourceTitle + " 副本") }
    var creating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val targets =
        documents.filter {
            it.kind == "scan" &&
                it.id != sourceId &&
                (query.isBlank() ||
                    it.title.contains(query, true) ||
                    it.folder.contains(query, true))
        }
    val action = if (move) "移动" else "复制"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$action $count 页到文档") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (move) "保留原图与识别文字，完成后从来源文档移除。两份文档均可撤销这次移动。" else "保留原图、裁边、增强和识别文字。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !creating,
                        onClick = { creating = false },
                        label = { Text("已有文档") },
                    )
                    FilterChip(
                        selected = creating,
                        onClick = { creating = true },
                        label = { Text("新建文档") },
                    )
                }
                if (creating) {
                    OutlinedTextField(
                        title,
                        { title = it },
                        label = { Text("新文档名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onChoose(null, title) },
                        enabled = title.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("新建扫描文档并$action")
                    }
                } else {
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text("搜索文档或文件夹") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (targets.isEmpty())
                        Text(
                            if (query.isBlank()) "暂无其他扫描文档，可切换到新建文档" else "未找到匹配的扫描文档",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(targets, key = { it.id }) { doc ->
                            OutlinedButton(
                                onClick = { onChoose(doc.id, "") },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(doc.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (doc.folder.isNotBlank())
                                        Text(
                                            doc.folder,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun PageOriginsDialog(vm: MainViewModel, page: PageRow, onDismiss: () -> Unit) {
    val origins = remember(page.processingMetadata) { page.origins().asReversed() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("页面来源") },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(origins) { origin ->
                    Column {
                        Text(origin.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "来源文档第 ${origin.ordinal} 页（整理时）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (origin.documentId != null)
                            TextButton(
                                onClick = {
                                    onDismiss()
                                    vm.openPageOrigin(origin)
                                }
                            ) {
                                Text("打开来源页")
                            }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
