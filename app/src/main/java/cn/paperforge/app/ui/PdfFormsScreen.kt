package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.DocumentResources
import cn.paperforge.app.pdf.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PdfFormsScreen(vm: MainViewModel) {
    val source by vm.formSource.collectAsState()
    val doc = source ?: return
    val input = doc.pdfPath?.let(::File) ?: return
    val scope = rememberCoroutineScope()
    val busy by vm.busy.collectAsState()
    var info by remember(doc.id) { mutableStateOf<PdfForms.Inspection?>(null) }
    var values by remember(doc.id) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var dropStaticXfa by rememberSaveable(doc.id) { mutableStateOf(false) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable(doc.id) { mutableIntStateOf(0) }
    var preview by remember(doc.id) { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var draftReady by remember(doc.id) { mutableStateOf(false) }
    var savedDraft by remember(doc.id) { mutableStateOf("") }
    val previewFiles = remember(doc.id) { mutableListOf<File>() }
    DisposableEffect(doc.id) { onDispose { previewFiles.forEach { it.delete() } } }
    val request = info?.let { PdfForms.request(it, values, dropStaticXfa) }
    val changed = request?.getJSONObject("values")?.length() ?: 0
    val canFill =
        info?.let {
            it.mdpPermission != 1 && it.xfa != "dynamic" && (it.xfa == "none" || dropStaticXfa)
        } == true
    LaunchedEffect(doc.id) {
        working = true
        try {
            val loaded =
                withContext(Dispatchers.IO) {
                    val descriptor = PdfForms.inspect(input)
                    val saved =
                        DocumentResources.entries(vm.repo, doc.id)
                            .filter { it.role == "form-draft" }
                            .asReversed()
                            .firstNotNullOfOrNull { resource ->
                                runCatching { JSONObject(File(resource.path).readText()) }
                                    .getOrNull()
                                    ?.takeIf { it.optString("sourceSha256") == descriptor.sha256 }
                            }
                    descriptor to saved
                }
            info = loaded.first
            values = loaded.first.fields.associate { it.name to it.values }
            loaded.second?.let { draft ->
                dropStaticXfa = draft.optBoolean("dropStaticXfa")
                val saved = draft.getJSONObject("values")
                values =
                    values +
                        saved.keys().asSequence().associateWith { key ->
                            saved.getJSONArray(key).let { array ->
                                List(array.length()) { array.getString(it) }
                            }
                        }
            }
            draftReady = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            message = error.message ?: "无法读取表单"
        } finally {
            working = false
        }
    }
    val draftSnapshot = request?.toString().orEmpty()
    LaunchedEffect(draftReady, draftSnapshot) {
        if (draftReady && request != null) {
            try {
                PdfForms.saveDraft(vm.repo, doc, JSONObject(draftSnapshot))
                savedDraft = draftSnapshot
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "草稿保存失败"
            }
        }
    }
    fun act(action: suspend () -> Unit) {
        scope.launch {
            working = true
            message = ""
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "操作未完成"
            } finally {
                working = false
            }
        }
    }
    fun update(name: String, value: List<String>) {
        values = values + (name to value)
        preview = null
    }
    val shown = preview ?: input
    val sessionResult by
        produceState<Result<PdfSession>?>(null, shown) {
            value = null
            value = withContext(Dispatchers.IO) { runCatching { PdfSession(shown) } }
        }
    val session = sessionResult?.getOrNull()
    DisposableEffect(session) { onDispose { session?.close() } }
    var transform by remember(session, page) { mutableStateOf(PageTransform()) }

    @Composable
    fun controls(modifier: Modifier) {
        LazyColumn(
            modifier.padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("按字段填写", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (savedDraft == draftSnapshot) "填写草稿已自动保存" else "正在保留填写草稿",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)

                if (info?.fields?.isEmpty() == true) Text("这份 PDF 没有 AcroForm 字段")
                if ((info?.signatures ?: 0) > 0)
                    Text(
                        "已有证书签名的修订会保留。填写产生追加修订，原签名不覆盖新增内容；请在签署与核验中检查。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                when (info?.xfa) {
                    "dynamic" -> Text("检测到动态 XFA。当前可看原页，填写需要 XFA 专用引擎；保留原文件。")
                    "static-hybrid" -> {
                        Text(
                            "检测到含 AcroForm 字段的 XFA 混合表单。兼容副本使用现有 AcroForm 字段并移除 XFA，外部 XFA 脚本与数据绑定不保留。"
                        )
                        Row {
                            Checkbox(
                                dropStaticXfa,
                                {
                                    dropStaticXfa = it
                                    preview = null
                                },
                            )
                            Text("另存 AcroForm 兼容副本", Modifier.padding(top = 12.dp))
                        }
                    }
                }
                if (info?.mdpPermission == 1) Text("此文档的认证签名禁止后续修改")
            }
            items(info?.fields.orEmpty(), key = { it.name }) { field ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            field.label + if (field.required) " *" else "",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (field.name != field.label)
                            Text(field.name, style = MaterialTheme.typography.bodySmall)
                        if (field.kind == "signature") {
                            Text(if (field.readOnly) "已签署 / 只读签名字段" else "未签署的数字签名字段")
                            TextButton(
                                onClick = {
                                    vm.selected.value = doc
                                    vm.queueTool("certificates")
                                }
                            ) {
                                Text("进入证书签署与核验")
                            }
                        } else
                            FormFieldControl(
                                field,
                                values[field.name] ?: field.values,
                                canFill && !field.readOnly && !working,
                            ) {
                                update(field.name, it)
                            }
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !working && info != null,
                        onClick = {
                            val frozen = request ?: return@OutlinedButton
                            act {
                                PdfForms.saveDraft(vm.repo, doc, frozen)
                                savedDraft = frozen.toString()
                                message = "填写草稿已保存，随文档备份"
                            }
                        },
                    ) {
                        Text("保存填写草稿")
                    }
                    OutlinedButton(
                        enabled = canFill && changed > 0 && !working,
                        onClick = {
                            val frozen = request ?: return@OutlinedButton
                            act {
                                val result =
                                    File(
                                        vm.repo.context.cacheDir,
                                        "form-preview-${UUID.randomUUID()}.pdf",
                                    )
                                previewFiles += result
                                withContext(Dispatchers.IO) {
                                    PdfForms.fill(vm.repo.context, input, result, frozen)
                                }
                                preview = result
                                tools = false
                            }
                        },
                    ) {
                        Text("预览填写结果")
                    }
                    Button(
                        enabled = canFill && changed > 0 && !working && !busy,
                        onClick = { request?.let(vm::queueFormFill) },
                    ) {
                        Text("保存表单副本")
                    }
                }
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("填写 PDF 表单") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            vm.selected.value = doc
                            vm.screen.value = "reader"
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回阅读")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(enabled = page > 0, onClick = { page-- }) { Text("上一页") }
                Text("${page + 1} / ${info?.pages ?: 1}", Modifier.padding(vertical = 12.dp))
                TextButton(enabled = page + 1 < (info?.pages ?: 1), onClick = { page++ }) {
                    Text("下一页")
                }
                if (preview != null) TextButton(onClick = { preview = null }) { Text("查看原件") }
            }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank())
                Text(
                    message,
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            Text(
                if (preview != null) "实际填写结果 · $changed 个字段" else "原件 · $changed 个字段待保存",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val wide = maxWidth >= 720.dp
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (session != null)
                            ReaderPage(
                                session,
                                page,
                                transform,
                                0,
                                true,
                                emptyList(),
                                { transform = it },
                                {},
                                fitWidth = true,
                                initializeAtTop = true,
                                edgeTurning = true,
                                onEdgeTurn = { step ->
                                    page = (page + step).coerceIn(0, (info?.pages ?: 1) - 1)
                                },
                            )
                        else if (sessionResult?.isFailure == true)
                            Text(
                                "原页无法预览：${sessionResult?.exceptionOrNull()?.message}",
                                Modifier.padding(16.dp),
                            )
                    }
                    if (wide) controls(Modifier.width(360.dp).fillMaxHeight())
                }
                if (!wide && tools)
                    ModalBottomSheet(
                        onDismissRequest = { tools = false },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    ) {
                        controls(Modifier.fillMaxWidth().fillMaxHeight(.9f))
                    }
            }
            Button(onClick = { tools = true }, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text("填写字段 · $changed 项修改")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormFieldControl(
    field: PdfForms.Field,
    values: List<String>,
    enabled: Boolean,
    onChange: (List<String>) -> Unit,
) {
    val value = values.firstOrNull().orEmpty()
    when (field.kind) {
        "text" ->
            OutlinedTextField(
                value,
                { onChange(listOf(it)) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                singleLine = !field.multiline,
                minLines = if (field.multiline) 3 else 1,
                visualTransformation =
                    if (field.password) PasswordVisualTransformation()
                    else VisualTransformation.None,
                supportingText = {
                    if (field.maxLength > 0)
                        Text("${value.codePointCount(0, value.length)} / ${field.maxLength}")
                },
            )
        "checkbox" -> {
            val checked = value != "Off" && value.isNotEmpty()
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(checked, enabled, Role.Checkbox) { selected ->
                        onChange(
                            listOf(
                                if (selected) field.options.firstOrNull()?.value ?: "Yes" else "Off"
                            )
                        )
                    }
                    .semantics(mergeDescendants = true) { contentDescription = field.label }
            ) {
                Checkbox(checked, null, enabled = enabled)
                Text(if (checked) "已勾选" else "未勾选", Modifier.padding(top = 12.dp))
            }
        }
        "radio" ->
            Column(Modifier.selectableGroup()) {
                field.options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            value == option.value,
                            enabled,
                            Role.RadioButton,
                        ) {
                            onChange(listOf(option.value))
                        }
                    ) {
                        RadioButton(value == option.value, null, enabled = enabled)
                        Text(option.label, Modifier.padding(top = 12.dp))
                    }
                }
                TextButton(enabled = enabled, onClick = { onChange(listOf("Off")) }) {
                    Text("清除选择")
                }
            }
        "choice" -> {
            var choosing by remember(field.name) { mutableStateOf(false) }
            var query by remember(field.name) { mutableStateOf("") }
            if (field.editableChoice)
                OutlinedTextField(
                    value,
                    { onChange(listOf(it)) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("自定义选项") },
                )
            val selected =
                values.map { value ->
                    field.options.firstOrNull { it.value == value }?.label ?: value
                }
            OutlinedButton(
                enabled = enabled,
                onClick = {
                    query = ""
                    choosing = true
                },
            ) {
                Text(
                    selected.joinToString("、").ifBlank {
                        if (field.multiSelect) "选择多个选项" else "选择选项"
                    }
                )
            }
            if (choosing)
                AlertDialog(
                    onDismissRequest = { choosing = false },
                    title = { Text(field.label) },
                    text = {
                        Column {
                            OutlinedTextField(
                                query,
                                { query = it },
                                label = { Text("搜索选项") },
                                singleLine = true,
                            )
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                                items(
                                    field.options.filter {
                                        query.isBlank() ||
                                            it.label.contains(query, true) ||
                                            it.value.contains(query, true)
                                    }
                                ) { option ->
                                    Row(
                                        Modifier.fillMaxWidth().selectable(
                                            option.value in values,
                                            role =
                                                if (field.multiSelect) Role.Checkbox
                                                else Role.RadioButton,
                                        ) {
                                            onChange(
                                                if (field.multiSelect) {
                                                    if (option.value in values)
                                                        values - option.value
                                                    else values + option.value
                                                } else listOf(option.value)
                                            )
                                            if (!field.multiSelect) choosing = false
                                        }
                                    ) {
                                        if (field.multiSelect)
                                            Checkbox(option.value in values, null)
                                        else RadioButton(option.value in values, null)
                                        Text(option.label, Modifier.padding(top = 12.dp))
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { choosing = false }) { Text("完成") } },
                    dismissButton = {
                        if (field.multiSelect)
                            TextButton(onClick = { onChange(emptyList()) }) { Text("清除全部选择") }
                    },
                )
        }
        else -> Text(values.joinToString("、").ifBlank { "此字段类型只读" })
    }
    if (field.readOnly) Text("只读字段", style = MaterialTheme.typography.bodySmall)
}
