package cn.paperforge.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.UserSettings
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import java.io.File
import java.util.UUID
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkflowScreen(vm: MainViewModel) {
    val draft by vm.workflowDraft.collectAsState()
    val runId by vm.workflowRun.collectAsState()
    val tasks by vm.localTasks.collectAsState()
    val busy by vm.busy.collectAsState()
    val revision by UserSettings.revision.collectAsState()
    val saved = remember(revision) { WorkflowRecipes.all() }
    val builtins = remember { WorkflowRecipes.builtin() }
    val documents by vm.repo.documents.collectAsState(emptyList())
    val runs =
        tasks.filter {
            it.type == "AUTO_EXPORT" ||
                it.type == "CONVERT" &&
                    JSONObject(it.payload).optJSONObject("input")?.optString("kind") == "RECIPE"
        }
    val current = runs.firstOrNull { it.id == runId }
    val locked = busy || current?.state in LocalTaskRow.active
    var presets by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var types by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val directory =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let(vm::rememberWorkflowDirectory)
        }
    val input =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let { uri -> vm.runWorkflow(uri) }
        }
    fun pickInput() {
        input.launch(
            when (draft.input) {
                "PDF" -> arrayOf("application/pdf")
                "IMAGE" -> arrayOf("image/*")
                "TEXT" -> arrayOf("text/plain")
                else -> arrayOf("*/*")
            }
        )
    }
    fun update(step: RecipeStep, key: String, value: Any) {
        vm.workflowDraft.value =
            draft.copy(
                steps =
                    draft.steps.map {
                        if (it.id == step.id)
                            it.copy(options = JSONObject(it.options.toString()).put(key, value))
                        else it
                    }
            )
    }
    fun open(recipe: WorkflowRecipe) {
        vm.workflowDraft.value = WorkflowRecipe.decode(recipe.json())
        vm.workflowRun.value = null
        presets = false
        notice = ""
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工作流配方") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "convert" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { presets = true }, enabled = !locked) { Text("配方库") }
                },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                val issue = draft.issue()
                if (issue != null)
                    Text(
                        issue,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                Button(
                    onClick = { if (runId != null) vm.runWorkflow() else pickInput() },
                    enabled = !locked && issue == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (runId != null) "继续此任务 · 复用已完成步骤" else "选择文件并运行配方")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("workflow-editor"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("一次设置，顺序处理", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "相同输入和参数的已完成步骤会保留。只改命名或保存目录时，前面的 OCR 和排版可以直接复用。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                OutlinedTextField(
                    draft.name,
                    { vm.workflowDraft.value = draft.copy(name = it) },
                    label = { Text("配方名称") },
                    enabled = !locked,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    TextButton(onClick = { types = true }, enabled = !locked) {
                        Text("输入类型：${WorkflowRecipes.typeLabel(draft.input)}")
                    }
                    DropdownMenu(types, { types = false }) {
                        listOf("PDF", "OFFICE", "IMAGE", "TEXT").forEach { type ->
                            DropdownMenuItem(
                                text = { Text(WorkflowRecipes.typeLabel(type)) },
                                onClick = {
                                    vm.workflowDraft.value = draft.copy(input = type)
                                    types = false
                                },
                            )
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            WorkflowRecipes.save(draft)
                            notice = "已保存配方"
                        },
                        enabled = !locked && draft.name.isNotBlank(),
                    ) {
                        Text("保存配方")
                    }
                    TextButton(
                        onClick = {
                            val copied =
                                draft.copy(
                                    id = UUID.randomUUID().toString(),
                                    name = "${draft.name} 副本",
                                )
                            WorkflowRecipes.save(copied)
                            open(copied)
                            notice = "已复制配方"
                        },
                        enabled = !locked,
                    ) {
                        Text("复制")
                    }
                    TextButton(onClick = { open(WorkflowRecipe()) }, enabled = !locked) {
                        Text("新配方")
                    }
                }
                if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
            }
            if (current != null)
                item {
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text("继续已有输入", style = MaterialTheme.typography.titleSmall)
                            Text(current.title)
                            Text(
                                current.error ?: current.detail,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(
                                onClick = { vm.workflowRun.value = null },
                                enabled = !locked,
                            ) {
                                Text("改用新文件")
                            }
                        }
                    }
                }
            if (runId == null && draft.input == "PDF")
                item {
                    TextButton(
                        onClick = { library = true },
                        enabled = !locked && draft.issue() == null,
                    ) {
                        Text("从文档库选择 PDF 运行")
                    }
                }
            items(draft.steps, key = { it.id }) { step ->
                val index = draft.steps.indexOfFirst { it.id == step.id }
                val operation = WorkflowRecipes.operations.firstOrNull { it.id == step.operation }
                val checkpoint =
                    current?.let {
                        JSONObject(it.payload)
                            .optJSONObject("checkpoint")
                            ?.optJSONObject("recipe-step-${step.id}")
                    }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "${index+1}. ${operation?.title ?: step.operation}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${WorkflowRecipes.typeLabel(operation?.input ?: "")} → ${WorkflowRecipes.typeLabel(operation?.output ?: "")}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        when (step.operation) {
                            "compress" -> {
                                Text("图片质量：${step.options.optInt("quality", 84)}")
                                Slider(
                                    step.options.optInt("quality", 84).toFloat(),
                                    { update(step, "quality", it.toInt()) },
                                    enabled = !locked,
                                    valueRange = 40f..100f,
                                )
                            }
                            "text-pdf",
                            "image-pdf" -> {
                                val paper =
                                    step.options.optString(
                                        "paper",
                                        if (step.operation == "text-pdf") "A4" else "Auto",
                                    )
                                TextButton(
                                    onClick = {
                                        update(
                                            step,
                                            "paper",
                                            if (paper == "A4")
                                                if (step.operation == "text-pdf") "Letter"
                                                else "Auto"
                                            else "A4",
                                        )
                                    },
                                    enabled = !locked,
                                ) {
                                    Text(
                                        "纸张：$paper · 点击切换 ${if(step.operation == "text-pdf") "A4 / Letter" else "A4 / 原比例"}"
                                    )
                                }
                            }
                            "rename" -> {
                                OutlinedTextField(
                                    step.options.optString("template", "{title}_{date}"),
                                    { update(step, "template", it) },
                                    enabled = !locked,
                                    label = { Text("文件名模板") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    "{title} 标题 · {date} 日期 · {time} 时间 · {pages} 来源页数",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            "save" -> {
                                val destination =
                                    ExportDestinations(vm.repo.context)
                                        .get(step.options.optString("destination"))
                                Text("目录：${destination?.name ?: "尚未选择"}")
                                TextButton(
                                    onClick = {
                                        vm.workflowDirectoryStep.value = step.id
                                        directory.launch(null)
                                    },
                                    enabled = !locked,
                                ) {
                                    Text("选择此步骤的保存目录")
                                }
                            }
                            "pdf-png" -> {
                                Text("长边：${step.options.optInt("edge", 2200)} px")
                                Slider(
                                    step.options.optInt("edge", 2200).toFloat(),
                                    { update(step, "edge", it.toInt()) },
                                    enabled = !locked,
                                    valueRange = 600f..4000f,
                                )
                            }
                            "searchable" ->
                                Text(
                                    "保留原页，补充透明文字层；本次任务会记住 OCR 引擎和语言。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            "pdf-docx" ->
                                Text(
                                    "文字、表格和嵌入图片的结构重建；复杂版式可保留原 PDF 对照。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    val order = draft.steps.toMutableList()
                                    java.util.Collections.swap(order, index, index - 1)
                                    vm.workflowDraft.value = draft.copy(steps = order)
                                },
                                enabled = !locked && index > 0,
                            ) {
                                Text("上移")
                            }
                            TextButton(
                                onClick = {
                                    val order = draft.steps.toMutableList()
                                    java.util.Collections.swap(order, index, index + 1)
                                    vm.workflowDraft.value = draft.copy(steps = order)
                                },
                                enabled = !locked && index < draft.steps.lastIndex,
                            ) {
                                Text("下移")
                            }
                            TextButton(
                                onClick = {
                                    vm.workflowDraft.value =
                                        draft.copy(
                                            steps = draft.steps.filterNot { it.id == step.id }
                                        )
                                },
                                enabled = !locked,
                            ) {
                                Text("移除步骤")
                            }
                        }
                        if (checkpoint?.optBoolean("complete") == true) {
                            Text("已有结果，运行时核对是否可复用", style = MaterialTheme.typography.labelMedium)
                            TextButton(
                                onClick = {
                                    vm.showWorkflowStage(File(checkpoint.getString("path")))
                                },
                                enabled = !busy,
                            ) {
                                Text("查看这一步的结果")
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { adding = true },
                    enabled = !locked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, null)
                    Text("添加处理步骤")
                }
            }
            if (runs.isNotEmpty())
                item { Text("最近的配方任务", style = MaterialTheme.typography.titleMedium) }
            items(runs.take(6), key = { it.id }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.title)
                        Text(row.error ?: row.detail, style = MaterialTheme.typography.bodySmall)
                        TextButton(
                            onClick = { vm.editWorkflowRun(row) },
                            enabled = !busy && row.state !in LocalTaskRow.active,
                        ) {
                            Text("调整并继续")
                        }
                    }
                }
            }
        }
    }
    if (adding)
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("添加步骤") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(WorkflowRecipes.operations, key = { it.id }) { operation ->
                        TextButton(
                            onClick = {
                                val options = JSONObject()
                                if (operation.id == "save")
                                    options.put(
                                        "destination",
                                        UserSettings.string("exportDestination", ""),
                                    )
                                vm.workflowDraft.value =
                                    draft.copy(
                                        steps =
                                            draft.steps +
                                                RecipeStep(
                                                    operation = operation.id,
                                                    options = options,
                                                )
                                    )
                                adding = false
                            }
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(operation.title)
                                Text(
                                    "${WorkflowRecipes.typeLabel(operation.input)} → ${WorkflowRecipes.typeLabel(operation.output)}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text("关闭") } },
        )
    if (presets)
        AlertDialog(
            onDismissRequest = { presets = false },
            title = { Text("配方库") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    item { Text("内置示例") }
                    items(builtins, key = { it.id }) { recipe ->
                        TextButton(onClick = { open(recipe) }) { Text(recipe.name) }
                    }
                    if (saved.isNotEmpty()) item { Text("我的配方") }
                    items(saved, key = { it.id }) { recipe ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { open(recipe) }, modifier = Modifier.weight(1f)) {
                                Text(recipe.name)
                            }
                            TextButton(onClick = { WorkflowRecipes.remove(recipe.id) }) {
                                Text("删除")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { presets = false }) { Text("关闭") } },
        )
    if (library)
        AlertDialog(
            onDismissRequest = { library = false },
            title = { Text("选择 PDF 输入") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    val pdfs = documents.filter { it.kind == "pdf" && it.pdfPath != null }
                    if (pdfs.isEmpty()) item { Text("文档库还没有 PDF，可以从手机文件选择。") }
                    items(pdfs, key = { it.id }) { doc ->
                        TextButton(
                            onClick = {
                                library = false
                                vm.runWorkflow(Uri.fromFile(File(doc.pdfPath!!)))
                            }
                        ) {
                            Text(doc.title)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        library = false
                        pickInput()
                    }
                ) {
                    Text("手机文件")
                }
            },
            dismissButton = { TextButton(onClick = { library = false }) { Text("关闭") } },
        )
}
