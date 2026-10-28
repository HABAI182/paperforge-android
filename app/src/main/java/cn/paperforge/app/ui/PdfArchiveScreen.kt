package cn.paperforge.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.DocumentResources
import cn.paperforge.app.pdf.PdfArchive
import cn.paperforge.app.pdf.PdfAssembly
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PdfArchiveScreen(vm: MainViewModel) {
    val source by vm.archiveSource.collectAsState()
    val report by vm.archiveReport.collectAsState()
    val reportFile by vm.archiveReportFile.collectAsState()
    val busy by vm.busy.collectAsState()
    val doc = source
    val input = doc?.pdfPath?.let(::File)
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var installed by remember { mutableStateOf(PdfArchive.available(context)) }
    var target by rememberSaveable(doc?.id) { mutableStateOf("2b") }
    var sourceHash by remember(input?.path) { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var moreTargets by remember { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var failures by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) installed = PdfArchive.available(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(doc?.id, input?.path) {
        if (doc != null && input != null) {
            try {
                val loaded =
                    withContext(Dispatchers.IO) {
                        val sha = PdfAssembly.sha256(input)
                        val cached =
                            DocumentResources.entries(vm.repo, doc.id)
                                .filter { it.role == "archive-report" }
                                .asReversed()
                                .firstNotNullOfOrNull { resource ->
                                    val file = File(resource.path)
                                    runCatching { JSONObject(file.readText()) }
                                        .getOrNull()
                                        ?.takeIf { it.optString("sourceSha256") == sha }
                                        ?.let { file to it }
                                }
                        sha to cached
                    }
                sourceHash = loaded.first
                loaded.second?.let { (file, data) ->
                    vm.archiveReport.value = data
                    vm.archiveReportFile.value = file
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "无法读取归档资料"
            }
        }
    }
    val matching = input == null || report?.optString("sourceSha256") == sourceHash
    val result = report?.takeIf { matching }
    val ready = input != null && sourceHash.isNotEmpty() && installed && !busy
    fun back() {
        vm.selected.value = source
        vm.screen.value = if (input == null) vm.archiveReportReturn.value else "reader"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF/A 归档") },
                navigationIcon = {
                    IconButton(onClick = ::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回阅读")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    doc?.title ?: result?.optString("fileName") ?: "归档校验报告",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text("完整规则校验 · 字体、颜色、元数据与文档结构", style = MaterialTheme.typography.bodySmall)
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
            }
            if (input != null)
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("PDF/A 目标等级", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("1b", "2b", "2u", "3b").forEach { choice ->
                                    FilterChip(
                                        target == choice,
                                        { target = choice },
                                        label = { Text(choice) },
                                    )
                                }
                                Box {
                                    TextButton(onClick = { moreTargets = true }) {
                                        Text(
                                            if (target !in listOf("1b", "2b", "2u", "3b"))
                                                "PDF/A-$target"
                                            else "其他等级"
                                        )
                                    }
                                    DropdownMenu(moreTargets, { moreTargets = false }) {
                                        PdfArchive.targets.forEach { choice ->
                                            DropdownMenuItem(
                                                text = { Text("PDF/A-$choice") },
                                                onClick = {
                                                    target = choice
                                                    moreTargets = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            if (!installed) {
                                Text("需要新版手机格式引擎。安装后在本机离线校验，无需电脑。")
                                TextButton(
                                    onClick = {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse(
                                                    "https://github.com/HABAI182/paperforge-android"
                                                ),
                                            )
                                        )
                                    }
                                ) {
                                    Text("打开项目与引擎说明")
                                }
                            }
                            Button(enabled = ready, onClick = { vm.queueArchive(target) }) {
                                Text("完整校验 PDF/A-$target")
                            }
                        }
                    }
                }
            item {
                val passed =
                    result?.optBoolean("compliant") == true &&
                        result.optString("jobEndStatus") == "NORMAL"
                val background =
                    if (result == null) MaterialTheme.colorScheme.surfaceContainer
                    else if (passed) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = background),
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            when {
                                result == null -> "尚未完成校验"
                                passed -> "通过 PDF/A-${result.optString("target")}"
                                else -> "不符合 PDF/A-${result.optString("target")}"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        if (result == null) Text("写入 PDF/A 标记不会自动成为归档文件，校验结果会保存在文档中。")
                        else {
                            Text(
                                "${result.optInt("profileRuleCount")} 条完整规则 · ${result.optInt("assertions")} 次检查"
                            )
                            Text(
                                "veraPDF 1.30.3 · Android 适配",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (input != null && result.optString("target") != target)
                                Text(
                                    "这是 PDF/A-${result.optString("target")} 的结果；当前目标已切换为 PDF/A-$target。"
                                )
                            reportFile?.let { file ->
                                TextButton(
                                    onClick = {
                                        vm.outputLocked.value = false
                                        vm.output.value = file
                                    }
                                ) {
                                    Text("保存 / 分享校验报告")
                                }
                            }
                        }
                    }
                }
            }
            val rules = result?.optJSONArray("failedRules")
            if ((rules?.length() ?: 0) > 0) {
                item { Text("需要处理的要求", style = MaterialTheme.typography.titleMedium) }
                items(rules!!.length()) { index ->
                    val rule = rules.getJSONObject(index)
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "规则 ${rule.optString("clause")} · ${rule.optInt("count")} 处",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                rule.optString("description"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                item {
                    TextButton(onClick = { failures = !failures }) {
                        Text(if (failures) "收起位置详情" else "展开失败位置")
                    }
                }
                if (failures) {
                    val assertions = result?.optJSONArray("failures")
                    items(assertions?.length() ?: 0) { index ->
                        val assertion = assertions!!.getJSONObject(index)
                        ListItem(
                            headlineContent = { Text(assertion.optString("message")) },
                            supportingContent = { Text(assertion.optString("context")) },
                            trailingContent = {
                                if (doc != null && assertion.optInt("page", 0) > 0)
                                    TextButton(
                                        onClick = {
                                            vm.selected.value = doc
                                            vm.readerTarget.value =
                                                doc.id to (assertion.getInt("page") - 1)
                                            vm.outputReturn.value = "pdf-archive"
                                            vm.screen.value = "reader"
                                        }
                                    ) {
                                        Text("第 ${assertion.optInt("page")} 页")
                                    }
                            },
                        )
                    }
                }
            }
            if (result != null)
                item {
                    OutlinedButton(onClick = { details = !details }) {
                        Text(if (details) "收起字体与元数据" else "字体、元数据与报告来源")
                    }
                    if (details) {
                        val facts = result.optJSONObject("diagnostics")
                        val fonts = facts?.optJSONArray("fonts")
                        Text(
                            "字体资源：${fonts?.length() ?: 0}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        for (i in 0 until (fonts?.length() ?: 0)) {
                            val font = fonts!!.getJSONObject(i)
                            Text(
                                "${font.optString("name")} · ${font.optString("type")} · ${if (font.optBoolean("embedded")) "已嵌入" else "未嵌入"}"
                            )
                        }
                        facts?.optJSONObject("metadata")?.let { metadata ->
                            metadata.keys().forEach { key ->
                                Text(
                                    "$key：${metadata.optString(key)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (result.optBoolean("appearanceOnly")) Text("此副本使用静态页面图像。")
                        Text(
                            "文件 SHA-256 ${result.optString("sourceSha256")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "规则 SHA-256 ${result.optString("profileSha256")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("证书签名有效性请使用独立的证书签署与核验工具。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            if (input != null)
                item {
                    HorizontalDivider()
                    Text(
                        "生成归档兼容副本",
                        Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "转为静态页面图像，保留纸面外观。可编辑文字、链接、表单和数字签名不会保留；原件保留。生成后自动完整校验 PDF/A-2b。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(enabled = ready, onClick = { vm.queueArchive("2b", true) }) {
                        Text("生成并校验 PDF/A-2b 副本")
                    }
                }
        }
    }
}
