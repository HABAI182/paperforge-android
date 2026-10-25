package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.*
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BatchRenameScreen(vm: MainViewModel) {
    val documents by vm.repo.documents.collectAsState(emptyList())
    val requested by vm.renameSelection.collectAsState()
    val busy by vm.busy.collectAsState()
    val preferences = remember { LibraryPreferences(vm.repo.context) }
    val saved = remember { preferences.naming() }
    var pattern by rememberSaveable { mutableStateOf(saved.pattern) }
    var start by rememberSaveable { mutableStateOf(saved.start.toString()) }
    val fieldSaver = remember {
        listSaver<List<Pair<String, String>>, String>(
            save = { it.flatMap { row -> listOf(row.first, row.second) } },
            restore = { it.chunked(2).map { row -> row[0] to row[1] } },
        )
    }
    var fields by
        rememberSaveable(stateSaver = fieldSaver) { mutableStateOf(saved.fields.toList()) }
    var selected by rememberSaveable(requested) { mutableStateOf(requested.toSet()) }
    var help by rememberSaveable { mutableStateOf(false) }
    var showFields by rememberSaveable { mutableStateOf(false) }
    val timestamp = rememberSaveable { LocalDateTime.now().toString() }
    val time = remember(timestamp) { LocalDateTime.parse(timestamp) }
    val ordered =
        remember(documents, requested) {
            val byId = documents.associateBy { it.id }
            requested.mapNotNull(byId::get) + documents.filter { it.id !in requested }
        }
    val preview =
        remember(documents, ordered, pattern, start, fields, selected) {
            runCatching {
                if (pattern.contains("{field")) {
                    require(fields.all { it.first.isNotBlank() }) { "请给每个字段填写名称" }
                    require(fields.map { it.first }.distinct().size == fields.size) { "字段名称需要区分" }
                }
                val first = start.toIntOrNull() ?: error("请填写起始序号")
                val options = NamingOptions(pattern, first, fields.toMap())
                options to
                    NameTemplate.preview(
                        ordered.filter { it.id in selected },
                        documents,
                        options,
                        time,
                    )
            }
        }
    val rows = preview.getOrNull()?.second.orEmpty()
    val byId = remember(rows) { rows.associateBy { it.documentId } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("批量命名") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "home" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${rows.size} 份", style = MaterialTheme.typography.labelLarge)
                    Button(
                        onClick = {
                            preview.getOrNull()?.let { (options, changes) ->
                                vm.applyBatchNames(changes, options)
                            }
                        },
                        enabled = rows.isNotEmpty() && preview.isSuccess && !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("应用预览名称")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    pattern,
                    { pattern = it },
                    label = { Text("命名模板") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                            "日期＋类型＋序号" to "{date:yyyyMMdd}-{type}-{n:03}",
                            "原名＋序号" to "{name}-{n:03}",
                            "字段＋序号" to "{field}-{n:03}",
                        )
                        .forEach { (label, value) ->
                            AssistChip(onClick = { pattern = value }, label = { Text(label) })
                        }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        start,
                        { start = it },
                        label = { Text("起始序号") },
                        modifier = Modifier.width(130.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    TextButton(onClick = { help = !help }) { Text("变量说明") }
                    TextButton(onClick = { showFields = !showFields }) { Text("自定义字段") }
                }
                if (help)
                    Text(
                        "{name} 原名\n{type} 类型，{folder} 文件夹\n{date:yyyyMMdd} 日期\n{n:03} 三位补零序号\n{field:项目} 取名为“项目”的字段，{field} 取第一个字段。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
            }
            if (showFields || pattern.contains("{field")) {
                items(fields.indices.toList(), key = { "field-$it" }) { index ->
                    val field = fields[index]
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            field.first,
                            { value ->
                                fields =
                                    fields.toMutableList().apply {
                                        set(index, value to field.second)
                                    }
                            },
                            label = { Text("字段名") },
                            modifier = Modifier.weight(.8f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            field.second,
                            { value ->
                                fields =
                                    fields.toMutableList().apply {
                                        set(index, field.first to value)
                                    }
                            },
                            label = { Text("内容") },
                            modifier = Modifier.weight(1.2f),
                            singleLine = true,
                        )
                        IconButton(
                            onClick = { fields = fields.filterIndexed { i, _ -> i != index } }
                        ) {
                            Icon(Icons.Default.Close, "移除字段")
                        }
                    }
                }
                item {
                    TextButton(onClick = { fields = fields + ("字段${fields.size+1}" to "") }) {
                        Text("添加字段")
                    }
                }
            }
            item {
                preview.exceptionOrNull()?.let {
                    Text(it.message ?: "请检查命名模板", color = MaterialTheme.colorScheme.error)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("最终名称预览", style = MaterialTheme.typography.titleMedium)
                    Row {
                        TextButton(onClick = { selected = ordered.map { it.id }.toSet() }) {
                            Text("全选")
                        }
                        TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                    }
                }
            }
            items(ordered, key = { it.id }) { doc ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        selected = if (doc.id in selected) selected - doc.id else selected + doc.id
                    },
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = doc.id in selected,
                            onCheckedChange = { value ->
                                selected = if (value) selected + doc.id else selected - doc.id
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                doc.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            byId[doc.id]?.let { change ->
                                Text(change.after, style = MaterialTheme.typography.titleMedium)
                                if (change.collision)
                                    Text(
                                        "同名已自动编号",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                            }
                        }
                    }
                }
            }
            if (ordered.isEmpty()) item { Text("文档库中暂时没有可命名的文档") }
        }
    }
}
