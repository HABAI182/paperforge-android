package cn.paperforge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.pdf.FormatJobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatConversionScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val busy by vm.busy.collectAsState()
    val tasks by vm.localTasks.collectAsState()
    val target by vm.formatTarget.collectAsState()
    val dpi by vm.formatDpi.collectAsState()
    var bookFont by rememberSaveable { mutableIntStateOf(12) }
    var bookPaper by rememberSaveable { mutableStateOf("A4") }
    var showBookOptions by rememberSaveable { mutableStateOf(false) }
    var includeHeaders by rememberSaveable { mutableStateOf(true) }
    var mathSource by rememberSaveable { mutableStateOf(false) }
    var installed by remember { mutableStateOf(FormatJobs.installed(context)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME)
                    installed = FormatJobs.installed(context)
            }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                vm.convertLayout(
                    it,
                    JSONObject()
                        .put("target", target)
                        .put("dpi", dpi)
                        .put("fontSize", bookFont)
                        .put("includeHeaders", includeHeaders)
                        .put("mathSource", mathSource)
                        .put("widthPt", if (bookPaper == "A5") 420 else 595)
                        .put("heightPt", if (bookPaper == "A5") 595 else 842),
                )
            }
        }
    val history =
        tasks.filter {
            it.type == "CONVERT" &&
                JSONObject(it.payload).optJSONObject("input")?.optString("kind") == "LAYOUT"
        }
    var report by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("特殊文档转换") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "convert" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回转换中心")
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
            item { Text("手机本机转换版面", style = MaterialTheme.typography.headlineSmall) }
            item {
                Text(
                    "OFD · DjVu · XPS / OXPS · SVG · CBZ · FB2 · EML · CAJ / KDH · TeX",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                Text(
                    "保留纸张尺寸、页序与可见版面。XPS、SVG、FB2 使用原生 PDF 设备，尽量保留文字与基础矢量；OFD / DjVu 保留原始文字坐标。转换后可阅读、对照或导出逐页图片。"
                )
            }
            item {
                Card {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (installed != null) "格式引擎已就绪 · $installed" else "首次需要安装手机格式引擎",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (installed == null)
                            Text("请安装本次版本附带的「PaperForge 格式引擎」APK，再返回纸匠。之后转换无需电脑或网络。")
                        TextButton(
                            onClick = {
                                uriHandler.openUri("https://github.com/HABAI182/paperforge-android")
                            }
                        ) {
                            Text("项目与引擎下载")
                        }
                    }
                }
            }
            item {
                Text("输出", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        target == "PDF",
                        { vm.formatTarget.value = "PDF" },
                        label = { Text("PDF") },
                    )
                    FilterChip(
                        target == "IMAGES",
                        { vm.formatTarget.value = "IMAGES" },
                        label = { Text("PNG 图片包") },
                    )
                }
            }
            item {
                Text("版面分辨率", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(150, 200, 300).forEach { value ->
                        FilterChip(
                            dpi == value,
                            { vm.formatDpi.value = value },
                            label = { Text("$value DPI") },
                        )
                    }
                }
            }
            item {
                TextButton(onClick = { showBookOptions = !showBookOptions }) {
                    Text(if (showBookOptions) "收起文字排版" else "电子书、邮件与 TeX 排版")
                }
                if (showBookOptions)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "FB2、EML、TeX 使用所选纸张与字号；固定版式文档保持原始纸张。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("包含邮件头", Modifier.weight(1f))
                            Switch(includeHeaders, { includeHeaders = it })
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("LaTeX 公式附原文", Modifier.weight(1f))
                            Switch(mathSource, { mathSource = it })
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("A4", "A5").forEach { value ->
                                FilterChip(
                                    bookPaper == value,
                                    { bookPaper = value },
                                    label = { Text(value) },
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(10, 12, 16, 20).forEach { value ->
                                FilterChip(
                                    bookFont == value,
                                    { bookFont = value },
                                    label = { Text("$value pt") },
                                )
                            }
                        }
                    }
            }
            item {
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("选择文件或 TeX 项目 ZIP")
                }
            }
            item {
                Text(
                    "CAJ 仅支持内含 PDF 的容器；HN / C8 暂不支持。TeX 为常用正文与数学排版，未支持的宏包与内容会保留原文并列入报告。二维 DXF 请在转换中心选择 CAD 入口。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Text(
                    "输出以可见版面为准，复杂字形和字体回退可查看报告。印章外观会随版面转换；原文件中的签名与证书保留在原件，本工具不迁移或验签。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (history.isNotEmpty())
                item { Text("最近转换", style = MaterialTheme.typography.titleMedium) }
            items(history.take(12), key = { it.id }) { task ->
                val payload = JSONObject(task.payload)
                val checkpoint = payload.optJSONObject("checkpoint")?.optJSONObject("layout-report")
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            payload.optJSONObject("input")?.optString("name").orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "${mapOf("SUCCEEDED" to "已完成","FAILED" to "未完成","RUNNING" to "处理中","QUEUED" to "等待中","CANCELLED" to "已停止","PARTIAL" to "部分完成")[task.state.name] ?: task.state.name} · ${task.detail}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if ((checkpoint?.optInt("warningCount") ?: 0) > 0)
                            Text(
                                "有 ${checkpoint!!.optInt("warningCount")} 项转换提示，请核对报告",
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        FlowRow {
                            if (task.state == cn.paperforge.app.data.LocalTaskState.SUCCEEDED)
                                TextButton(onClick = { vm.openTask(task) }) { Text("打开结果") }
                            TextButton(onClick = { vm.screen.value = "jobs" }) { Text("任务与结果") }
                            checkpoint
                                ?.optString("path")
                                ?.takeIf { it.isNotBlank() }
                                ?.let { path ->
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                report =
                                                    withContext(Dispatchers.IO) {
                                                        runCatching {
                                                                layoutSummary(
                                                                    JSONObject(
                                                                        java.io
                                                                            .File(path)
                                                                            .readText()
                                                                    )
                                                                )
                                                            }
                                                            .getOrDefault("报告暂不可用")
                                                    }
                                            }
                                        }
                                    ) {
                                        Text("版面报告")
                                    }
                                }
                        }
                    }
                }
            }
        }
    }
    report?.let { value ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("版面报告") },
            text = {
                LazyColumn {
                    item {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(value, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { report = null }) { Text("关闭") } },
        )
    }
}

private fun layoutSummary(report: JSONObject) = buildString {
    val pages = report.getJSONArray("pages")
    appendLine("${report.optString("name")} · ${pages.length()} 页 · ${report.getInt("dpi")} DPI")
    appendLine("保留原件，转换只复制可见版面；签名与证书未迁移或验签。")
    val warnings = report.optJSONArray("warnings")
    appendLine("引擎：${report.optString("adapter")}")
    appendLine(if (report.has("nativePdf")) "输出保留引擎可映射的文字、图像与基础矢量。" else "输出保留可见版面与可用的原始文字坐标。")
    if (warnings == null || warnings.length() == 0) appendLine("未记录转换提示，请在对照中检查实际结果。")
    else {
        appendLine("转换提示：请核对对应的页面")
        for (i in 0 until warnings.length()) appendLine(
            warnings
                .getString(i)
                .replace("Font not embedded; system matching: ", "原件未嵌入字体，使用系统匹配：")
                .replace("Embedded font fallback: ", "嵌入字体无法载入，使用替代：")
                .replace("Missing embedded font: ", "找不到嵌入字体：")
        )
    }
    appendLine()
    for (i in 0 until pages.length()) {
        val page = pages.getJSONObject(i)
        appendLine(
            "第 ${i+1} 页：${page.getInt("width")} × ${page.getInt("height")} 像素；原始文字区域 ${page.optJSONArray("zones")?.length() ?: 0} 组"
        )
    }
    appendLine("原件 SHA256：${report.getString("sourceSha256")}")
}
