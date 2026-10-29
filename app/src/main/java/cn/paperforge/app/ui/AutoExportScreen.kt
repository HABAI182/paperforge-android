package cn.paperforge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.UserSettings
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import java.io.File
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AutoExportScreen(vm: MainViewModel) {
    val revision by UserSettings.revision.collectAsState()
    val rules = remember(revision) { AutoExportRules.all() }
    val recipes = remember(revision) { WorkflowRecipes.all().filter { it.input == "PDF" } }
    val draft by vm.autoExportDraft.collectAsState()
    val busy by vm.busy.collectAsState()
    val tasks by vm.localTasks.collectAsState()
    val history = tasks.filter { it.type == "AUTO_EXPORT" }
    var chooseRecipe by remember { mutableStateOf(false) }
    val directory =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let(vm::rememberAutoExportDirectory)
        }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自动导出") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = vm.toolHubReturn.value }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { vm.autoExportDraft.value = AutoExportRule() },
                        enabled = !busy,
                    ) {
                        Icon(Icons.Default.Add, "新建自动导出规则")
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
                Text("让常用归档自己完成", style = MaterialTheme.typography.headlineSmall)
                Text("规则默认关闭。启用后处理当前匹配的文档和后续变化；扫描处理结束后触发，相同内容只自动处理一次。目录写入失败会保留本地结果，等待手动重试。")
                FilledTonalButton(
                    onClick = { vm.autoExportDraft.value = AutoExportRule() },
                    enabled = !busy,
                ) {
                    Text("新建规则")
                }
            }
            draft?.let { rule ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("编辑规则", style = MaterialTheme.typography.titleLarge)
                            OutlinedTextField(
                                rule.name,
                                { vm.autoExportDraft.value = rule.copy(name = it) },
                                label = { Text("规则名称") },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                rule.folder,
                                { vm.autoExportDraft.value = rule.copy(folder = it) },
                                label = { Text("文件夹条件（包括子文件夹）") },
                                supportingText = { Text("留空则不限制文件夹") },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                rule.tags,
                                { vm.autoExportDraft.value = rule.copy(tags = it) },
                                label = { Text("标签条件") },
                                supportingText = { Text("多个标签用空格或逗号分开，需同时满足；留空则不限") },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("处理配方：${rule.recipe.name}")
                            Text(
                                rule.recipe.steps.joinToString(" → ") { step ->
                                    WorkflowRecipes.operations
                                        .firstOrNull { it.id == step.operation }
                                        ?.title ?: step.operation
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { chooseRecipe = true }, enabled = !busy) {
                                Text("选择已存 PDF 配方")
                            }
                            val target =
                                rule.recipe.steps
                                    .lastOrNull { it.operation == "save" }
                                    ?.options
                                    ?.optString("destination")
                                    ?.let { ExportDestinations(vm.repo.context).get(it) }
                            Text(
                                "保存：${target?.name ?: if(rule.recipe.steps.any { it.operation=="save" }) "需要重新选择目录" else "仅保存在应用导出记录"}"
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { directory.launch(null) }, enabled = !busy) {
                                    Text("选择保存目录")
                                }
                                TextButton(
                                    onClick = {
                                        vm.autoExportDraft.value =
                                            rule.copy(
                                                recipe =
                                                    rule.recipe.copy(
                                                        steps =
                                                            rule.recipe.steps.filterNot {
                                                                it.operation == "save"
                                                            }
                                                    )
                                            )
                                    },
                                    enabled = !busy,
                                ) {
                                    Text("仅保存在应用")
                                }
                            }
                            Text(
                                "扫描页：${rule.export.optString("paper","Auto")} · 质量 ${rule.export.optInt("quality",90)} · ${if(rule.export.optBoolean("searchable",true)) "含 OCR 文字层" else "仅页面图像"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "已有 PDF 保留原页；规则使用这里保存的扫描参数。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(
                                onClick = {
                                    vm.autoExportDraft.value =
                                        rule.copy(export = ScanExportOptions.current().values())
                                },
                                enabled = !busy,
                            ) {
                                Text("套用当前导出设置")
                            }
                            Text("等待文档稳定：${rule.delaySeconds} 秒")
                            Slider(
                                rule.delaySeconds.toFloat(),
                                { vm.autoExportDraft.value = rule.copy(delaySeconds = it.toInt()) },
                                valueRange = 0f..60f,
                                enabled = !busy,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("保存后启用规则")
                                Switch(
                                    rule.enabled,
                                    { vm.autoExportDraft.value = rule.copy(enabled = it) },
                                    enabled = !busy,
                                )
                            }
                            rule.issue()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { vm.saveAutoExportRule(rule) },
                                    enabled = !busy && (!rule.enabled || rule.issue() == null),
                                ) {
                                    Text(if (rule.enabled) "保存并启用" else "保存关闭的规则")
                                }
                                TextButton(
                                    onClick = { vm.autoExportDraft.value = null },
                                    enabled = !busy,
                                ) {
                                    Text("取消编辑")
                                }
                            }
                        }
                    }
                }
            }
            if (rules.isEmpty()) item { Text("还没有规则。可先为一个归档文件夹或标签建立规则。") }
            items(rules, key = { it.id }) { rule ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                rule.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Switch(
                                rule.enabled,
                                { vm.saveAutoExportRule(rule.copy(enabled = it)) },
                                enabled = !busy && rule.issue() == null,
                            )
                        }
                        Text(
                            "文件夹：${rule.folder.ifBlank { "不限" }} · 标签：${rule.tags.ifBlank { "不限" }}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("配方：${rule.recipe.name}", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    vm.autoExportDraft.value = AutoExportRule.decode(rule.json())
                                },
                                enabled = !busy,
                            ) {
                                Text("编辑规则")
                            }
                            TextButton(
                                onClick = { vm.runAutoExportRule(rule) },
                                enabled =
                                    !busy &&
                                        rule.issue() == null &&
                                        history.none { row ->
                                            row.state in LocalTaskRow.active &&
                                                JSONObject(row.payload)
                                                    .getJSONObject("input")
                                                    .optString("automationRule") == rule.id
                                        },
                            ) {
                                Text("匹配文档运行一次")
                            }
                            TextButton(
                                onClick = { vm.removeAutoExportRule(rule) },
                                enabled = !busy,
                            ) {
                                Text("删除规则")
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("自动导出记录", style = MaterialTheme.typography.titleLarge)
                    TextButton(
                        onClick = {
                            vm.taskFilter.value = "ALL"
                            vm.screen.value = "tasks"
                        }
                    ) {
                        Text("任务中心")
                    }
                }
            }
            if (history.isEmpty()) item { Text("触发后会在这里显示进度、结果和失败原因。") }
            items(history.take(20), key = { it.id }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(row.title, style = MaterialTheme.typography.titleSmall)
                        Text(row.error ?: row.detail, style = MaterialTheme.typography.bodySmall)
                        if (row.state in LocalTaskRow.active)
                            LinearProgressIndicator(
                                progress = { row.progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (
                                row.resourceEntries().any {
                                    it.id == "result" && File(it.path).isFile
                                }
                            )
                                TextButton(onClick = { vm.openTask(row) }, enabled = !busy) {
                                    Text("查看结果")
                                }
                            if (
                                row.state in
                                    setOf(
                                        LocalTaskState.FAILED,
                                        LocalTaskState.PARTIAL,
                                        LocalTaskState.CANCELLED,
                                    )
                            ) {
                                TextButton(onClick = { vm.retryAutoExport(row) }, enabled = !busy) {
                                    Text("继续重试")
                                }
                                TextButton(onClick = { vm.editWorkflowRun(row) }, enabled = !busy) {
                                    Text("调整配方并继续")
                                }
                            }
                            if (row.state in LocalTaskRow.active)
                                TextButton(onClick = { vm.cancelLocalTask(row) }) { Text("停止任务") }
                        }
                    }
                }
            }
        }
    }
    if (chooseRecipe)
        AlertDialog(
            onDismissRequest = { chooseRecipe = false },
            title = { Text("选择配方副本") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    item {
                        Text(
                            "规则会保留所选配方的副本。原配方更新后，可在这里重新选择。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(recipes, key = { it.id }) { recipe ->
                        TextButton(
                            onClick = {
                                vm.autoExportDraft.value =
                                    draft?.copy(recipe = WorkflowRecipe.decode(recipe.json()))
                                chooseRecipe = false
                            }
                        ) {
                            Text(recipe.name)
                        }
                    }
                    if (recipes.isEmpty()) item { Text("请先在工作流配方中保存一个 PDF 输入配方，或使用当前默认的命名副本步骤。") }
                }
            },
            confirmButton = { TextButton(onClick = { chooseRecipe = false }) { Text("关闭") } },
        )
}
