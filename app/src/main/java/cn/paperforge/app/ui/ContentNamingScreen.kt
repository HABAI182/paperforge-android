package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.*
import cn.paperforge.app.model.SmartNaming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Optional content mode inside the existing batch rename flow, with editable per-file previews. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContentNamingScreen(vm: MainViewModel, onTemplates: () -> Unit) {
    val documents by vm.repo.documents.collectAsState(emptyList())
    val requested by vm.renameSelection.collectAsState()
    val busy by vm.busy.collectAsState()
    val ordered =
        remember(documents, requested) {
            if (requested.isEmpty()) documents
            else requested.mapNotNull { id -> documents.find { it.id == id } }
        }
    var selected by rememberSaveable(requested) { mutableStateOf(requested.toSet()) }
    val names = remember { mutableStateMapOf<String, String>() }
    val suggestions = remember { mutableStateMapOf<String, SmartNaming.Suggestion>() }
    val problems = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    val changes =
        remember(ordered, documents, selected, names.toMap()) {
            NameTemplate.previewNames(
                    ordered.filter { it.id in selected },
                    documents,
                    names.toMap(),
                )
                .filter { it.before != it.after }
        }
    val previews = changes.associateBy { it.documentId }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("内容命名") },
                navigationIcon = {
                    IconButton(onClick = returnAction { vm.screen.value = "home" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = { TextButton(onClick = onTemplates) { Text("模板命名") } },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 2.dp) {
                Button(
                    onClick = {
                        vm.applyBatchNames(changes, LibraryPreferences(vm.repo.context).naming())
                    },
                    enabled = changes.isNotEmpty() && progress == null && !busy,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) {
                    Text("应用 ${changes.size} 份预览名称")
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
                Text(
                    "只在点选后离线读取首页；已有校对结果直接复用。建议可修改，不自动改名或移动文件。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    FilledTonalButton(
                        enabled = selected.isNotEmpty() && progress == null && !busy,
                        onClick = {
                            val targets = ordered.filter { it.id in selected }
                            job =
                                scope.launch {
                                    try {
                                        targets.forEachIndexed { index, doc ->
                                            progress =
                                                "正在分析 ${index + 1}/${targets.size}：${doc.title}"
                                            problems.remove(doc.id)
                                            try {
                                                val result =
                                                    vm.namingSuggestion(
                                                        doc,
                                                        recognizeMissing = true,
                                                    )
                                                if (result != null) suggestions[doc.id] = result
                                                val proposed = result?.names?.firstOrNull()
                                                if (proposed != null) names[doc.id] = proposed
                                                else problems[doc.id] = "没有足够的首页依据，可手动填写或跳过。"
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (failure: Exception) {
                                                problems[doc.id] = failure.message ?: "分析未完成，可重试"
                                            }
                                        }
                                    } finally {
                                        progress = null
                                        job = null
                                    }
                                }
                        },
                    ) {
                        Text("分析所选首页")
                    }
                    if (progress != null) TextButton(onClick = { job?.cancel() }) { Text("停止") }
                    TextButton(
                        onClick = { selected = ordered.map { it.id }.toSet() },
                        enabled = progress == null,
                    ) {
                        Text("全选")
                    }
                    TextButton(onClick = { selected = emptySet() }, enabled = progress == null) {
                        Text("跳过全部")
                    }
                }
                progress?.let {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(ordered, key = { it.id }) { doc ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = doc.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + doc.id else selected - doc.id
                                },
                                enabled = progress == null,
                            )
                            Text(
                                doc.title,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = names[doc.id].orEmpty(),
                            onValueChange = { names[doc.id] = it },
                            label = { Text("预览名称 · 可修改") },
                            singleLine = true,
                            enabled = progress == null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        suggestions[doc.id]?.let { result ->
                            Text(
                                "分类建议：${result.category.label} · ${if (result.confidence < .7f) "依据较少，请确认" else "请核对首页依据"}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (result.names.size > 1)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    result.names.forEach { name ->
                                        AssistChip(
                                            onClick = { names[doc.id] = name },
                                            label = { Text(name) },
                                            enabled = progress == null,
                                        )
                                    }
                                }
                            Text(
                                result.evidence.take(3).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            result.reviewNotes.forEach { note ->
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        problems[doc.id]?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        previews[doc.id]
                            ?.takeIf { it.collision }
                            ?.let {
                                Text(
                                    "同名处理：${it.after}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                    }
                }
            }
            if (ordered.isEmpty()) item { Text("没有可命名的文档") }
        }
    }
}
