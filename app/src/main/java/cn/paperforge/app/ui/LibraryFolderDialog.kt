package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.DocumentGroup
import cn.paperforge.app.data.libraryFolder

/** Uses existing virtual paths; removing a folder never deletes its documents. */
@Composable
fun LibraryFolderDialog(vm: MainViewModel, groups: List<DocumentGroup>, onDismiss: () -> Unit) {
    val busy by vm.busy.collectAsState()
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("") }
    val paths = groups.filterNot { it.isUnfiled }.map { it.key }
    SpaciousToolDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理文件夹") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    FilledTonalButton(
                        onClick = {
                            source = null
                            path = ""
                            mode = "create"
                        },
                        enabled = !busy,
                    ) {
                        Text("新建文件夹")
                    }
                    Text("移除文件夹只取消归类，文档保留在上一级；支持撤销。", style = MaterialTheme.typography.bodySmall)
                }
                if (paths.isEmpty()) item { Text("还没有文件夹。可以先创建，再从文档的分类菜单移入。") }
                items(groups.filterNot { it.isUnfiled }, key = { it.key }) { group ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(group.key, style = MaterialTheme.typography.titleSmall)
                            Text("${group.count} 份文档", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        source = group.key
                                        path = group.key.substringAfterLast('/')
                                        mode = "rename"
                                    },
                                    enabled = !busy,
                                ) {
                                    Text("重命名")
                                }
                                TextButton(
                                    onClick = {
                                        source = group.key
                                        path = group.key.substringBeforeLast('/', "")
                                        mode = "move"
                                    },
                                    enabled = !busy,
                                ) {
                                    Text("移动")
                                }
                                TextButton(
                                    onClick = {
                                        source = group.key
                                        mode = "delete"
                                    },
                                    enabled = !busy,
                                ) {
                                    Text("移除文件夹")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
    if (mode.isNotBlank()) {
        val current = source
        val title =
            when (mode) {
                "rename" -> "重命名文件夹"
                "move" -> "移动文件夹"
                "delete" -> "移除文件夹"
                else -> "新建文件夹"
            }
        AlertDialog(
            onDismissRequest = { mode = "" },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    current?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    when (mode) {
                        "delete" -> Text("本文件夹及子文件夹会被移除，所有文档保留并移到上一级。可以通过提示栏撤销。")
                        "move" ->
                            FolderField(
                                path,
                                { path = it },
                                paths.filter { it != current && !it.startsWith("$current/") },
                                "目标上级文件夹",
                                "根目录",
                            )
                        else ->
                            OutlinedTextField(
                                path,
                                { path = it },
                                singleLine = true,
                                label = { Text(if (mode == "create") "名称，可用 / 分级" else "新名称") },
                            )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled =
                        !busy &&
                            (mode in setOf("delete", "move") || libraryFolder(path).isNotBlank()),
                    onClick = {
                        when (mode) {
                            "create" -> vm.createLibraryFolder(path)
                            "delete" -> current?.let { vm.changeLibraryFolder(it, null) }
                            "rename" ->
                                current?.let {
                                    vm.changeLibraryFolder(
                                        it,
                                        listOf(
                                                it.substringBeforeLast('/', ""),
                                                path.trim().replace('/', '_').replace('\\', '_'),
                                            )
                                            .filter(String::isNotBlank)
                                            .joinToString("/"),
                                    )
                                }
                            "move" ->
                                current?.let {
                                    vm.changeLibraryFolder(
                                        it,
                                        listOf(libraryFolder(path), it.substringAfterLast('/'))
                                            .filter(String::isNotBlank)
                                            .joinToString("/"),
                                    )
                                }
                        }
                        mode = ""
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = { TextButton(onClick = { mode = "" }) { Text("取消") } },
        )
    }
}
