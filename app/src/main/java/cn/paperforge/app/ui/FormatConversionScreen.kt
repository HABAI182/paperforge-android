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
            uri?.let { vm.convertLayout(it, JSONObject().put("target", target).put("dpi", dpi)) }
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
                title = { Text("OFD / DjVu 转换") },
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
            item { Text("保留纸张尺寸、页序、图像与可用的原始文字坐标。OFD 嵌入字体优先，DjVu 按原始方向解码；转换后可直接阅读或导出逐页图片。") }
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
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("选择 OFD / DjVu 文件")
                }
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
                                "有 ${checkpoint!!.optInt("warningCount")} 项字体提示，请核对版面",
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        Row {
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
    if (warnings == null || warnings.length() == 0) appendLine("未发现字体提示。")
    else {
        appendLine("字体提示：请核对对应的页面")
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
