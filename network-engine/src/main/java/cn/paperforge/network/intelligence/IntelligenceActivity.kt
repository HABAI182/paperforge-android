package cn.paperforge.network.intelligence

import android.app.Application
import android.content.*
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.paperforge.network.networkContent
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

class IntelligenceModel(app: Application) : AndroidViewModel(app) {
    val source = MutableStateFlow<SourceBundle?>(null)
    val result = MutableStateFlow<IntelligenceResult?>(null)
    val history = MutableStateFlow<List<IntelligenceHistory>>(emptyList())
    val busy = MutableStateFlow("")
    val message = MutableStateFlow("")
    val installed = MutableStateFlow<Set<String>>(emptySet())
    private var job: Job? = null
    private var serial = 0
    private val draft = android.util.AtomicFile(File(app.filesDir, "intelligence/source.json"))

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (draft.baseFile.exists())
                    runCatching {
                            SourceBundle.decode(
                                JSONObject(draft.openRead().bufferedReader().use { it.readText() })
                            )
                        }
                        .onSuccess { source.compareAndSet(null, it) }
            }
            reload()
        }
    }

    fun reload() =
        viewModelScope.launch {
            history.value =
                withContext(Dispatchers.IO) { IntelligenceFiles.history(getApplication()) }
            runCatching { LocalTranslation.downloaded() }.onSuccess { installed.value = it }
        }

    fun use(value: SourceBundle) {
        job?.cancel()
        result.value = null
        source.value = value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { persist(value) }.onFailure { message.value = it.message ?: "快照未能保存" }
        }
    }

    private fun persist(value: SourceBundle) =
        synchronized(draft) {
            if (source.value !== value) return@synchronized
            draft.baseFile.parentFile!!.mkdirs()
            val stream = draft.startWrite()
            try {
                stream.write(value.json().toString().toByteArray(Charsets.UTF_8))
                draft.finishWrite(stream)
            } catch (e: Throwable) {
                draft.failWrite(stream)
                throw e
            }
        }

    fun import(uri: Uri) {
        val current = ++serial
        job?.cancel()
        job =
            viewModelScope.launch {
                busy.value = "正在读取原文快照"
                try {
                    val value =
                        withContext(Dispatchers.IO) {
                            getApplication<Application>()
                                .contentResolver
                                .openInputStream(uri)
                                ?.use { input ->
                                    val output = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        require(output.size() + n <= 12 * 1024 * 1024) {
                                            "快照过大，请减少页数"
                                        }
                                        output.write(buffer, 0, n)
                                    }
                                    SourceBundle.decode(JSONObject(output.toString("UTF-8")))
                                } ?: error("无法读取原文快照")
                        }
                    // Avoid cancelling this import coroutine when adopting its finished snapshot.
                    source.value = value
                    result.value = null
                    withContext(Dispatchers.IO) { persist(value) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message.value = e.message ?: "读取未完成"
                } finally {
                    if (current == serial) busy.value = ""
                }
            }
    }

    fun run(label: String, action: suspend () -> IntelligenceResult) {
        val current = ++serial
        job?.cancel()
        job =
            viewModelScope.launch {
                busy.value = label
                message.value = ""
                try {
                    val answer = action()
                    withContext(Dispatchers.IO) { IntelligenceFiles.save(getApplication(), answer) }
                    result.value = answer
                    reload()
                } catch (e: CancellationException) {
                    message.value = "已停止本次运行，已保存的历史结果仍保留"
                    throw e
                } catch (e: Exception) {
                    message.value = e.message ?: "运行未完成"
                } finally {
                    if (current == serial) busy.value = ""
                }
            }
    }

    fun models(from: String, to: String, wifi: Boolean) {
        val current = ++serial
        job?.cancel()
        job =
            viewModelScope.launch {
                busy.value = "正在下载离线语言包"
                try {
                    LocalTranslation.download(from, to, wifi)
                    message.value = "语言包已就绪，可断网翻译"
                    reload()
                } catch (e: CancellationException) {
                    message.value = "已停止等待；SDK可能继续完成已开始的下载，可刷新语言包列表查看"
                    throw e
                } catch (e: Exception) {
                    message.value = e.message ?: "语言包下载未完成"
                } finally {
                    if (current == serial) busy.value = ""
                }
            }
    }

    fun openResult(id: String) =
        viewModelScope.launch {
            runCatching {
                    withContext(Dispatchers.IO) { IntelligenceFiles.read(getApplication(), id) }
                }
                .onSuccess { result.value = it }
                .onFailure { message.value = it.message ?: "无法读取结果" }
        }

    fun cancel() {
        job?.cancel()
    }
}

class IntelligenceActivity : ComponentActivity() {
    private lateinit var model: IntelligenceModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[IntelligenceModel::class.java]
        if (savedInstanceState == null) intent.data?.let(model::import)
        networkContent { IntelligenceScreen(model) { finish() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let(model::import)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun IntelligenceScreen(vm: IntelligenceModel, back: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { IntelligencePrefs(context) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val source by vm.source.collectAsState()
    val result by vm.result.collectAsState()
    val history by vm.history.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val installed by vm.installed.collectAsState()
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var mode by rememberSaveable { mutableStateOf("本地摘录") }
    var operation by rememberSaveable { mutableStateOf("摘要") }
    var question by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf(prefs.endpoint) }
    var model by rememberSaveable { mutableStateOf(prefs.model) }
    var key by remember { mutableStateOf(runCatching { prefs.key() }.getOrDefault("")) }
    var instruction by rememberSaveable { mutableStateOf(prefs.instruction) }
    var tokens by rememberSaveable { mutableStateOf("2048") }
    var settings by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf("en") }
    var to by rememberSaveable { mutableStateOf("zh") }
    var wifi by rememberSaveable { mutableStateOf(true) }
    var range by
        rememberSaveable(source?.sha256) {
            mutableStateOf(source?.pages?.joinToString(",") { it.page.toString() } ?: "")
        }
    var original by remember { mutableStateOf<Pair<SourceBundle, SourcePage>?>(null) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<String?>(null) }
    val save =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/markdown")
        ) { uri ->
            val text = exporting
            exporting = null
            if (uri != null && text != null)
                scope.launch {
                    runCatching {
                            withContext(Dispatchers.IO) {
                                context.contentResolver
                                    .openOutputStream(uri)
                                    ?.bufferedWriter()
                                    ?.use { it.write(text) } ?: error("无法写入文件")
                            }
                        }
                        .onSuccess { vm.message.value = "已导出结果与原文快照" }
                        .onFailure { vm.message.value = it.message ?: "导出未完成" }
                }
        }
    val selected =
        remember(source, range) {
            val numbers = range.split(Regex("[,，\\s]+")).mapNotNull { it.toIntOrNull() }.toSet()
            source?.copy(pages = source!!.pages.filter { it.page in numbers })
        }
    fun run() {
        val snapshot = selected ?: return
        if (snapshot.pages.isEmpty() || snapshot.pages.all { it.text.isBlank() }) {
            vm.message.value = "请选择有文字的原文页"
            return
        }
        if (mode == "自定义服务" && snapshot.pages.sumOf { it.text.length } > 80000) {
            vm.message.value = "本次超过8万字，请减少页码分批运行"
            return
        }
        if (operation == "问答" && question.isBlank()) {
            vm.message.value = "请输入问题或关键词"
            return
        }
        if (mode == "自定义服务") {
            prefs.endpoint = endpoint
            prefs.model = model
            prefs.instruction = instruction
            try {
                prefs.key(key)
            } catch (e: Exception) {
                vm.message.value = e.message ?: "凭据保存未完成"
                return
            }
        }
        val currentMode = mode
        val currentOperation = operation
        val currentQuestion = question
        vm.run("正在$currentOperation") {
            when (currentMode) {
                "本机翻译" -> LocalTranslation.translate(snapshot, from, to)
                "自定义服务" ->
                    IntelligenceEngine.remote(
                        snapshot,
                        currentOperation,
                        if (currentOperation == "翻译" && currentQuestion.isBlank())
                            "译为${LocalTranslation.languages[to]}"
                        else currentQuestion,
                        endpoint,
                        model,
                        key,
                        instruction,
                        tokens.toIntOrNull() ?: 2048,
                    )
                else ->
                    withContext(Dispatchers.Default) {
                        IntelligenceEngine.local(snapshot, currentOperation, currentQuestion)
                    }
            }
        }
    }
    LaunchedEffect(result?.id) {
        if (result != null) {
            withFrameNanos {}
            val answerIndex =
                5 +
                    (if (message.isNotEmpty()) 1 else 0) +
                    (if (mode != "本地摘录") 1 else 0) +
                    (if (mode != "本机翻译") 1 else 0)
            list.animateScrollToItem(answerIndex)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("翻译与智能工具") },
                navigationIcon = { TextButton(onClick = back) { Text("返回") } },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = list,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("读懂文档，保留依据。", style = MaterialTheme.typography.headlineSmall)
                Text("手动选择原文与运行方式。结果独立保存，原始 OCR 和文档保持不变。")
            }
            item {
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("启用智能工具", Modifier.weight(1f))
                        Switch(
                            modifier = Modifier.semantics { contentDescription = "启用智能工具" },
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                prefs.enabled = it
                                if (!it) vm.cancel()
                            },
                        )
                    }
                }
            }
            if (message.isNotEmpty())
                item { Text(message, color = MaterialTheme.colorScheme.primary) }
            item {
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(source?.title ?: "选择原文", style = MaterialTheme.typography.titleMedium)
                        if (source != null) {
                            Text(
                                "${source!!.pages.size} 页快照 · ${source!!.pages.sumOf {it.text.length}} 字"
                            )
                            OutlinedTextField(
                                value = range,
                                onValueChange = { range = it },
                                label = { Text("本次页码，逗号分隔") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "本次处理 ${selected?.pages?.size ?: 0} 页、${selected?.pages?.sumOf {it.text.length} ?: 0} 字。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            LazyRow {
                                items(source!!.pages, key = { it.page }) { page ->
                                    TextButton(onClick = { original = source!! to page }) {
                                        Text("原文 ${page.page}")
                                    }
                                }
                            }
                        } else Text("在 PaperForge 阅读器或扫描页打开此工具，也可粘贴一段文字。")
                        TextButton(onClick = { editing = !editing }) {
                            Text(if (editing) "收起文字输入" else "粘贴其他文字")
                        }
                        if (editing) {
                            OutlinedTextField(
                                value = pasted,
                                onValueChange = { pasted = it },
                                label = { Text("独立文字片段") },
                                modifier =
                                    Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 300.dp),
                            )
                            Button(
                                onClick = {
                                    if (pasted.isNotBlank()) {
                                        vm.use(
                                            SourceBundle("文字片段", "", listOf(SourcePage(1, pasted)))
                                        )
                                        editing = false
                                    }
                                },
                                enabled = busy.isEmpty(),
                            ) {
                                Text("使用这段文字")
                            }
                        }
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("本地摘录", "本机翻译", "自定义服务").forEach { value ->
                        FilterChip(
                            selected = mode == value,
                            onClick = {
                                mode = value
                                operation = if (value == "本机翻译") "翻译" else "摘要"
                            },
                            enabled = busy.isEmpty(),
                            label = { Text(value) },
                        )
                    }
                }
                Text(
                    when (mode) {
                        "本地摘录" -> "手机内按关键词查找、摘取重点原句，无生成模型。"
                        "本机翻译" -> "语言包下载一次后，文字在手机本机翻译。"
                        else -> "兼容 Chat Completions 的局域网模型或自带密钥服务。点击运行才发送本次选定文字。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (mode != "本机翻译")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (if (mode == "本地摘录") listOf("摘要", "问答")
                            else listOf("摘要", "问答", "翻译", "命名与标签"))
                            .forEach { value ->
                                FilterChip(
                                    selected = operation == value,
                                    onClick = { operation = value },
                                    enabled = busy.isEmpty(),
                                    label = { Text(value) },
                                )
                            }
                    }
            }
            if (mode == "本机翻译")
                item {
                    Card {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LanguageChoice("原文", from, busy.isEmpty()) { from = it }
                                LanguageChoice("译文", to, busy.isEmpty()) { to = it }
                            }
                            Text(
                                "语言包：${installed.joinToString {LocalTranslation.languages[it] ?: it}.ifEmpty {"尚未下载"}}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = wifi,
                                    onCheckedChange = { wifi = it },
                                    enabled = busy.isEmpty(),
                                )
                                Text("只在 Wi-Fi 下载")
                            }
                            FlowRow {
                                OutlinedButton(
                                    onClick = { vm.models(from, to, wifi) },
                                    enabled = enabled && busy.isEmpty() && from != to,
                                ) {
                                    Text("下载离线语言包")
                                }
                                TextButton(onClick = { vm.reload() }) { Text("刷新语言包") }
                            }
                            installed.forEach { id ->
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            runCatching { LocalTranslation.remove(id) }
                                                .onSuccess { vm.reload() }
                                                .onFailure {
                                                    vm.message.value = it.message ?: "移除未完成"
                                                }
                                        }
                                    },
                                    enabled = busy.isEmpty(),
                                ) {
                                    Text("移除${LocalTranslation.languages[id] ?: id}语言包")
                                }
                            }
                        }
                    }
                }
            if (mode == "自定义服务")
                item {
                    Card {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = endpoint,
                                onValueChange = { endpoint = it },
                                enabled = busy.isEmpty(),
                                label = { Text("服务地址，如 http://手机或电脑地址:端口/v1") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = model,
                                onValueChange = { model = it },
                                enabled = busy.isEmpty(),
                                label = { Text("模型名称") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = key,
                                onValueChange = { key = it },
                                enabled = busy.isEmpty(),
                                label = { Text("API Key，本地服务可留空") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(
                                enabled = busy.isEmpty(),
                                onClick = {
                                    try {
                                        prefs.endpoint = endpoint
                                        prefs.model = model
                                        prefs.instruction = instruction
                                        prefs.key(key)
                                        vm.message.value = "连接设置已保存，尚未发送原文"
                                    } catch (e: Exception) {
                                        vm.message.value = e.message ?: "连接设置保存未完成"
                                    }
                                },
                            ) {
                                Text("保存连接设置")
                            }
                            TextButton(onClick = { settings = !settings }) {
                                Text(if (settings) "收起自定义" else "更多自定义")
                            }
                            if (settings) {
                                OutlinedTextField(
                                    value = instruction,
                                    onValueChange = { instruction = it },
                                    enabled = busy.isEmpty(),
                                    label = { Text("回答偏好 / 附加指令") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = tokens,
                                    onValueChange = { tokens = it.filter(Char::isDigit) },
                                    enabled = busy.isEmpty(),
                                    label = { Text("最大输出 token，128–16384") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            if (mode != "本机翻译")
                item {
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        enabled = busy.isEmpty(),
                        label = { Text(if (operation == "问答") "问题 / 关键词" else "补充要求，可留空") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    )
                }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = ::run,
                        enabled = enabled && busy.isEmpty() && selected?.pages?.isNotEmpty() == true,
                    ) {
                        Text(if (busy.isEmpty()) "运行$operation" else busy)
                    }
                    if (busy.isNotEmpty()) TextButton(onClick = { vm.cancel() }) { Text("取消") }
                    TextButton(onClick = { historyOpen = !historyOpen }) {
                        Text("历史 · ${history.size}")
                    }
                }
                if (busy.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            result?.let { answer ->
                item {
                    Card {
                        Column(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "${answer.operation} · ${answer.mode}",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            SelectionContainer { Text(answer.answer) }
                            if (answer.pages.isEmpty() && answer.mode == "自定义服务")
                                Text(
                                    "服务未给出有效页码引用，请对照原文核实。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            if (answer.unknownPages.isNotEmpty())
                                Text(
                                    "未收录的引用页：${answer.unknownPages.joinToString()}。这些页码无法作为本次原文依据。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            FlowRow {
                                answer.pages.forEach { page ->
                                    TextButton(
                                        onClick = {
                                            original =
                                                answer.source to
                                                    answer.source.pages.first { it.page == page }
                                        }
                                    ) {
                                        Text("核对第 $page 页")
                                    }
                                }
                            }
                            Text(
                                "来源：${answer.source.title} · ${answer.model}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            FlowRow {
                                TextButton(
                                    onClick = {
                                        (context.getSystemService(Context.CLIPBOARD_SERVICE)
                                                as ClipboardManager)
                                            .setPrimaryClip(
                                                ClipData.newPlainText("PaperForge", answer.answer)
                                            )
                                        vm.message.value = "已复制结果"
                                    }
                                ) {
                                    Text("复制结果")
                                }
                                TextButton(
                                    onClick = {
                                        exporting = answer.markdown()
                                        save.launch("${answer.source.title}-${answer.operation}.md")
                                    }
                                ) {
                                    Text("导出 Markdown")
                                }
                            }
                        }
                    }
                }
            }
            if (historyOpen)
                items(history, key = { it.id }) { answer ->
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("${answer.title} · ${answer.operation}")
                            FlowRow {
                                TextButton(onClick = { vm.openResult(answer.id) }) { Text("查看结果") }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                IntelligenceFiles.remove(context, answer.id)
                                            }
                                            if (vm.result.value?.id == answer.id)
                                                vm.result.value = null
                                            vm.reload()
                                        }
                                    }
                                ) {
                                    Text("移除记录")
                                }
                            }
                        }
                    }
                }
            item {
                Text(
                    "快照与结果保存在本工具私有目录，卸载会移除；需要保留时导出 Markdown。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    original?.let { (bundle, page) ->
        AlertDialog(
            onDismissRequest = { original = null },
            title = { Text("原文快照 · 第 ${page.page} 页") },
            text = {
                SelectionContainer {
                    androidx.compose.foundation.rememberScrollState().let { state ->
                        Column(
                            Modifier.heightIn(max = 420.dp).then(Modifier.verticalScroll(state))
                        ) {
                            Text(page.text)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { original = null }) { Text("关闭") } },
            dismissButton = {
                val doc = bundle.documentId
                if (doc.isNotEmpty())
                    TextButton(
                        onClick = {
                            runCatching {
                                    context.startActivity(
                                        Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse(
                                                    "paperforge://document/$doc?page=${page.page}"
                                                ),
                                            )
                                            .setPackage("cn.paperforge.app")
                                    )
                                }
                                .onFailure { vm.message.value = "来源应用或文档暂不可用，原文快照仍保留" }
                        }
                    ) {
                        Text("查看当前原页")
                    }
            },
        )
    }
}

@Composable
private fun LanguageChoice(
    label: String,
    selected: String,
    enabled: Boolean,
    change: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text("$label：${LocalTranslation.languages[selected]}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LocalTranslation.languages.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        change(id)
                        open = false
                    },
                )
            }
        }
    }
}
