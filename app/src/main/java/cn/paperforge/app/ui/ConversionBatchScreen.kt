package cn.paperforge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import org.json.JSONObject

private data class BatchEdit(val ids: List<String>, val preset: BatchPreset)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConversionBatchScreen(vm: MainViewModel) {
    val tasks by vm.localTasks.collectAsState()
    val selected by vm.batches.selected.collectAsState()
    val busy by vm.batches.busy.collectAsState()
    val groups = tasks.filter(BatchConversions::member).groupBy(BatchConversions::batchId)
    val members =
        groups[selected].orEmpty().sortedBy { BatchConversions.input(it).optInt("batchIndex") }
    val related =
        tasks.filter {
            selected.isNotBlank() &&
                it.type == "CONVERT" &&
                BatchConversions.batchId(it) == selected &&
                BatchConversions.input(it).optBoolean("batchRelated")
        }
    var kind by rememberSaveable { mutableStateOf("TEXT_PDF") }
    var options by rememberSaveable { mutableStateOf(TextPdfOptions().json().toString()) }
    val common = BatchPreset(kind, options)
    var edit by remember { mutableStateOf<BatchEdit?>(null) }
    var recent by remember { mutableStateOf(false) }
    var replaceId by rememberSaveable { mutableStateOf("") }
    var pickerPreset by remember { mutableStateOf(common) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            vm.batches.add(uris, pickerPreset)
        }
    val replacement =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && replaceId.isNotBlank()) vm.batches.replace(replaceId, uri)
            replaceId = ""
        }
    edit?.let { request ->
        BatchPresetDialog(request.preset, onDismiss = { edit = null }) { value ->
            if (request.ids.isEmpty()) {
                kind = value.kind
                options = value.options
            } else vm.batches.configure(request.ids, value)
            edit = null
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("批量转换") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "convert" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回转换中心")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { recent = true }) {
                            Icon(Icons.Default.MoreVert, "选择批次")
                        }
                        DropdownMenu(expanded = recent, onDismissRequest = { recent = false }) {
                            DropdownMenuItem(
                                text = { Text("新建批次") },
                                onClick = {
                                    vm.batches.selected.value = ""
                                    recent = false
                                },
                                enabled = !busy,
                            )
                            groups.entries
                                .sortedByDescending { it.value.maxOf { row -> row.created } }
                                .forEach { (id, rows) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${rows.size} 项 · ${BatchConversions.input(rows.minBy { it.created }).optString("name")}",
                                                maxLines = 2,
                                            )
                                        },
                                        onClick = {
                                            vm.batches.selected.value = id
                                            recent = false
                                        },
                                        enabled = !busy,
                                    )
                                }
                        }
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
                Text(
                    "一次选多份文件，逐项保留原件和结果。完成后点开查看，失败项可以单独重试。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("共用预设", style = MaterialTheme.typography.titleMedium)
                        Text(common.summary())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { edit = BatchEdit(emptyList(), common) },
                                enabled = !busy,
                            ) {
                                Text("调整预设")
                            }
                            Button(
                                onClick = {
                                    pickerPreset = common
                                    picker.launch(arrayOf("*/*"))
                                },
                                enabled = !busy,
                            ) {
                                Text(if (members.isEmpty()) "选择多个文件" else "添加文件")
                            }
                        }
                        if (members.any { BatchConversions.draft(it) })
                            TextButton(
                                onClick = {
                                    edit =
                                        BatchEdit(
                                            members
                                                .filter {
                                                    BatchConversions.draft(it) &&
                                                        BatchConversions.editable(it)
                                                }
                                                .map { it.id },
                                            common,
                                        )
                                },
                                enabled = !busy,
                            ) {
                                Text("套用到待提交项")
                            }
                        if (kind == "TEXT_PDF")
                            Text(
                                "直接排版各文件的正文；需要先编辑正文时可使用单文件转换。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        if (kind == "TABLE_ANALYZE")
                            Text(
                                "识别完成后逐份校对，再导出 Excel 或 CSV。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (members.isNotEmpty())
                item {
                    val completed = members.count { it.state == LocalTaskState.SUCCEEDED }
                    val pending =
                        members.count {
                            BatchConversions.draft(it) && it.state != LocalTaskState.FAILED
                        }
                    val failed =
                        members.count {
                            it.state in setOf(LocalTaskState.FAILED, LocalTaskState.CANCELLED)
                        }
                    Text(
                        "共 ${members.size} 项 · 完成 $completed · 待提交 $pending · 待处理 $failed",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.batches.run() },
                            enabled =
                                !busy &&
                                    members.any {
                                        BatchConversions.draft(it) &&
                                            BatchConversions.source(it) != null &&
                                            BatchConversions.editable(it)
                                    },
                        ) {
                            Text("提交待处理项")
                        }
                        OutlinedButton(
                            onClick = { vm.batches.run(true) },
                            enabled =
                                !busy &&
                                    members.any {
                                        BatchConversions.retryable(it) ||
                                            (it.state == LocalTaskState.FAILED &&
                                                BatchConversions.source(it) != null)
                                    },
                        ) {
                            Text("只重试失败项")
                        }
                    }
                }
            items(members, key = { it.id }) { row ->
                val input = BatchConversions.input(row)
                val draft = BatchConversions.draft(row)
                val table = input.optString("kind") == "TABLE_ANALYZE"
                val result = row.resourceEntries().any { it.id == "result" }
                val editable = BatchConversions.editable(row)
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${input.optInt("batchIndex") + 1}. ${input.optString("name")}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            BatchPreset.from(input).summary(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (table && row.state == LocalTaskState.SUCCEEDED) "待校对表格"
                            else if (draft && row.state != LocalTaskState.FAILED) "待提交"
                            else taskStateName(row.state),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (row.state in LocalTaskRow.active)
                            LinearProgressIndicator(
                                progress = { row.progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        if (row.error != null)
                            Text(row.error, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (result)
                                TextButton(onClick = { vm.openTask(row) }) {
                                    Text(if (table) "校对表格" else "查看结果")
                                }
                            if (ConversionPreviews.files(row) != null)
                                TextButton(onClick = { vm.compareConversion(row) }) {
                                    Text("原件 / 结果对照")
                                }
                            if (editable)
                                TextButton(
                                    onClick = {
                                        edit = BatchEdit(listOf(row.id), BatchPreset.from(input))
                                    },
                                    enabled = !busy,
                                ) {
                                    Text("单项参数")
                                }
                            if (editable)
                                TextButton(
                                    onClick = {
                                        replaceId = row.id
                                        replacement.launch(arrayOf("*/*"))
                                    },
                                    enabled = !busy,
                                ) {
                                    Text("重选原件")
                                }
                            if (editable && BatchConversions.source(row) != null)
                                TextButton(
                                    onClick = { vm.batches.retry(row.id) },
                                    enabled = !busy,
                                ) {
                                    Text(if (draft) "提交此项" else "重试此项")
                                }
                            if (row.state in LocalTaskRow.active)
                                TextButton(onClick = { vm.cancelLocalTask(row) }) { Text("停止") }
                        }
                        related
                            .filter {
                                BatchConversions.input(it).optString("batchSourceTask") == row.id
                            }
                            .forEach { child ->
                                val childInput = BatchConversions.input(child)
                                val failedChild =
                                    child.state in
                                        setOf(LocalTaskState.FAILED, LocalTaskState.CANCELLED)
                                TextButton(
                                    onClick = {
                                        if (failedChild) vm.retryTask(child) else vm.openTask(child)
                                    },
                                    enabled = child.state !in LocalTaskRow.active,
                                ) {
                                    Text(
                                        if (childInput.optString("kind") == "TABLE_REVIEW") "继续校对"
                                        else
                                            (when (childInput.optString("kind")) {
                                                "TABLE_ANALYZE" -> "区域表格识别"
                                                "CONVERSION_PREVIEW" -> "原件 / 结果对照"
                                                else -> "表格导出"
                                            }) +
                                                " · " +
                                                (if (failedChild) "重试"
                                                else taskStateName(child.state))
                                    )
                                }
                            }
                    }
                }
            }
            if (members.isEmpty())
                item {
                    Text(
                        "右上角可找回以前的批次。关闭界面后，已提交的任务会继续处理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}

@Composable
private fun BatchChoice(
    label: String,
    value: String,
    choices: List<String>,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label · $value")
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice) },
                    onClick = {
                        onChange(choice)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BatchPresetDialog(
    initial: BatchPreset,
    onDismiss: () -> Unit,
    onSave: (BatchPreset) -> Unit,
) {
    var kind by remember { mutableStateOf(initial.kind) }
    var encoded by remember { mutableStateOf(initial.options) }
    val options = JSONObject(encoded)
    fun update(key: String, value: Any) {
        encoded = JSONObject(encoded).put(key, value).toString()
    }
    var number by
        remember(kind) {
            mutableStateOf(
                when (kind) {
                    "LAYOUT" -> options.optInt("dpi", 200).toString()
                    "IMAGES" -> options.optInt("edge", 4096).toString()
                    "OFFICE_IMAGES" -> options.optInt("edge", 2200).toString()
                    else -> options.optDouble("fontSize", 12.0).toInt().toString()
                }
            )
        }
    val valid =
        when (kind) {
            "LAYOUT" -> number.toIntOrNull()?.let { it in 72..600 } == true
            "IMAGES",
            "OFFICE_IMAGES" -> number.toIntOrNull()?.let { it in 128..8192 } == true
            "TEXT_PDF" -> number.toIntOrNull()?.let { it in 6..72 } == true
            else -> true
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转换预设") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BatchChoice(
                    "处理方式",
                    BatchPreset.kinds.getValue(kind),
                    BatchPreset.kinds.values.toList(),
                ) { label ->
                    kind = BatchPreset.kinds.entries.first { it.value == label }.key
                    encoded = "{}"
                }
                when (kind) {
                    "PDF_TOOL" -> {
                        BatchChoice(
                            "输出",
                            BatchPreset.pdfModes[options.optString("mode", "txt")] ?: "TXT",
                            BatchPreset.pdfModes.values.toList(),
                        ) { label ->
                            update(
                                "mode",
                                BatchPreset.pdfModes.entries.first { it.value == label }.key,
                            )
                        }
                        if (options.optString("mode") == "pdf-pptx-native")
                            Row {
                                Checkbox(
                                    options.optBoolean("preferEditable"),
                                    { update("preferEditable", it) },
                                )
                                Text("优先编辑，简化复杂效果", Modifier.padding(top = 10.dp))
                            }
                        if (
                            options.optString("mode") in
                                setOf("images-jpg", "images-png", "images-webp")
                        ) {
                            BatchChoice(
                                "最长边像素",
                                options.optInt("edge", 2200).toString(),
                                listOf("1280", "2200", "4096", "6000"),
                            ) {
                                update("edge", it.toInt())
                            }
                        }
                        if (options.optString("mode") in setOf("compress", "flatten")) {
                            Text("图像质量 ${options.optInt("quality", 75)}%")
                            Slider(
                                options.optInt("quality", 75).toFloat(),
                                { update("quality", it.toInt()) },
                                valueRange = 20f..100f,
                            )
                        }
                        if (options.optString("mode") == "compress") {
                            val bytes = options.optLong("targetBytes")
                            BatchChoice(
                                "目标大小",
                                if (bytes == 0L) "按质量" else "${bytes/1024} KB",
                                listOf("按质量", "200 KB", "500 KB", "1024 KB", "2048 KB"),
                            ) {
                                update(
                                    "targetBytes",
                                    it.substringBefore(' ').toLongOrNull()?.times(1024) ?: 0L,
                                )
                            }
                        }
                    }
                    "IMAGES",
                    "OFFICE_IMAGES" -> {
                        BatchChoice(
                            "输出",
                            options.optString(
                                "format",
                                if (kind == "OFFICE_IMAGES") "png" else "jpg",
                            ),
                            listOf("jpg", "png", "webp"),
                        ) {
                            update("format", it)
                        }
                        OutlinedTextField(
                            number,
                            { number = it },
                            label = { Text("最长边像素（128–8192）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("图像质量 ${options.optInt("quality", 92)}%")
                        Slider(
                            options.optInt("quality", 92).toFloat(),
                            { update("quality", it.toInt()) },
                            valueRange = 20f..100f,
                        )
                        if (kind == "IMAGES")
                            Row {
                                Checkbox(
                                    options.optBoolean("keepAlpha", true),
                                    { update("keepAlpha", it) },
                                )
                                Text("保留透明背景", Modifier.padding(top = 12.dp))
                            }
                    }
                    "OFFICE" -> {
                        Text("使用手机 Office 引擎；原件保留。")
                        BatchChoice(
                            "输出",
                            options.optString("format", "pdf"),
                            listOf("pdf", "docx", "odt", "rtf", "xlsx", "ods", "csv", "pptx", "odp"),
                        ) {
                            update("format", it)
                        }
                    }
                    "LAYOUT" -> {
                        BatchChoice(
                            "输出",
                            if (options.optString("target", "PDF") == "IMAGES") "PNG 页包" else "PDF",
                            listOf("PDF", "PNG 页包"),
                        ) {
                            update("target", if (it == "PDF") "PDF" else "IMAGES")
                        }
                        OutlinedTextField(
                            number,
                            { number = it },
                            label = { Text("分辨率 DPI（72–600）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    "MARKUP" -> {
                        BatchChoice(
                            "输出",
                            options.optString("target", "HTML"),
                            listOf("HTML", "MD", "TXT", "EPUB"),
                        ) {
                            update("target", it)
                        }
                        Text(
                            "HTML、Markdown、EPUB 互转；PDF 或纯文本可生成 EPUB。复杂版式保留原件供对照。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (options.optString("target") == "EPUB") {
                            OutlinedTextField(
                                options.optString("author"),
                                { update("author", it) },
                                label = { Text("作者（可留空）") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                options.optString("language", "zh"),
                                { update("language", it) },
                                label = { Text("语言，如 zh / en") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    "TABLE_ANALYZE" -> {
                        BatchChoice(
                            "校对后导出",
                            if (options.optBoolean("outputCsv")) "CSV" else "XLSX",
                            listOf("XLSX", "CSV"),
                        ) {
                            update("outputCsv", it == "CSV")
                        }
                        val strategies =
                            linkedMapOf("auto" to "自动", "lines_strict" to "仅网格线", "text" to "按文字对齐")
                        BatchChoice(
                            "表格识别",
                            strategies[options.optString("tableStrategy", "auto")] ?: "自动",
                            strategies.values.toList(),
                        ) { label ->
                            update(
                                "tableStrategy",
                                strategies.entries.first { it.value == label }.key,
                            )
                        }
                        Row {
                            Checkbox(options.optBoolean("ocr", true), { update("ocr", it) })
                            Text("补识别扫描文字", Modifier.padding(top = 12.dp))
                        }
                        Row {
                            Checkbox(options.optBoolean("joinTables"), { update("joinTables", it) })
                            Text("连接跨页表格", Modifier.padding(top = 12.dp))
                        }
                    }
                    else -> {
                        BatchChoice(
                            "纸张",
                            options.optString("paper", "A4"),
                            listOf("A4", "A5", "Letter"),
                        ) {
                            update("paper", it)
                        }
                        OutlinedTextField(
                            number,
                            { number = it },
                            label = { Text("字号 pt（6–72）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row {
                            Checkbox(options.optBoolean("landscape"), { update("landscape", it) })
                            Text("横向页面", Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (kind == "LAYOUT") options.put("dpi", number.toInt())
                    if (kind == "TEXT_PDF") options.put("fontSize", number.toInt())
                    if (kind in setOf("IMAGES", "OFFICE_IMAGES"))
                        options.put("edge", number.toInt())
                    onSave(BatchPreset(kind, options.toString()))
                },
            ) {
                Text("应用")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
