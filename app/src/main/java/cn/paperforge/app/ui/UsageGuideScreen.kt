package cn.paperforge.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.model.ToolCapability
import cn.paperforge.app.model.UsageGuide

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageGuideScreen(vm: MainViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf("") }
    var capability by remember { mutableStateOf<ToolCapability?>(null) }
    fun back() {
        vm.homeTab.value = 3
        vm.screen.value = "home"
    }
    BackHandler(onBack = ::back)
    capability?.let { ToolCapabilityDialog(it) { capability = null } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("使用说明") },
                navigationIcon = {
                    IconButton(onClick = ::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("搜索使用说明") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty())
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, "清除搜索")
                            }
                    },
                )
            }
            item {
                Text(
                    "离线指南 · 点开卡片查看步骤",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val found = UsageGuide.search(query)
            if (found.isEmpty()) item { Text("没有找到相关说明，试试扫描、翻页、Word 或备份。") }
            items(found, key = { it.id }) { topic ->
                val open = expanded == topic.id || query.isNotBlank()
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                ) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { expanded = if (expanded == topic.id) "" else topic.id }
                                .semantics(mergeDescendants = true) {
                                    heading()
                                    stateDescription = if (open) "已展开" else "已收起"
                                }
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(topic.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    topic.purpose,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null,
                            )
                        }
                        if (open)
                            Column(
                                Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                topic.steps.forEachIndexed { index, step ->
                                    Text("${index+1}. $step")
                                }
                                Text(
                                    "提示：${topic.note}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = {
                                            vm.workspaceQuery.value = ""
                                            if (topic.destination == "documents")
                                                vm.homeTab.value = vm.documentTab.value
                                            else {
                                                vm.homeTab.value = 2
                                                vm.workspaceGroup.value = topic.destination
                                            }
                                            vm.screen.value = "home"
                                        }
                                    ) {
                                        Text(topic.destinationLabel)
                                    }
                                    topic.capability?.let { info ->
                                        TextButton(onClick = { capability = info }) {
                                            Text("能力与格式说明")
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }
    }
}
