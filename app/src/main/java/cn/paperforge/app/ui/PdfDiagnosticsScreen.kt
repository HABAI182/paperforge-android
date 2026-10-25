package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.pdf.*
import java.io.File
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfDiagnosticsScreen(vm: MainViewModel) {
    val doc by vm.selected.collectAsState()
    val file = doc?.pdfPath?.let(::File)
    var percent by remember(file) { mutableIntStateOf(0) }
    val result by
        produceState<Result<PdfDiagnosticsReport>?>(null, file) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { PdfDiagnostics.inspect(requireNotNull(file)) { percent = it } }
                        .onFailure { if (it is CancellationException) throw it }
                }
        }
    val report = result?.getOrNull()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("属性与诊断") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "reader" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回阅读")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { report?.let { vm.exportPdfDiagnostics(it) } },
                        enabled = report != null,
                    ) {
                        Text("导出报告")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(doc?.title ?: "PDF", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "仅检查当前文件，不影响阅读与原件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result == null)
                item {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("正在检查 · $percent%")
                }
            result?.exceptionOrNull()?.let { failure ->
                item { Text("检查未完成：${failure.message}", color = MaterialTheme.colorScheme.error) }
            }
            if (report != null) {
                item {
                    DiagnosticCard("概况") {
                        Text(
                            "${report.pages.size} 页 · PDF ${report.version} · ${"%.2f".format(report.bytes/1024.0/1024)} MB",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("${report.textPages} 页有文字 · ${report.imagePages} 页含图像资源")
                        Text("${report.fonts.size} 种字体 · ${report.uniqueImages} 个图像资源")
                        Text(
                            "图像资源统计不含内联图像；文字数量表示可提取字符。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        report.metadata.forEach { (key, value) -> Text("$key：$value") }
                    }
                }
                item {
                    DiagnosticCard("适用工具") {
                        if (report.ocrCandidates.isNotEmpty()) {
                            Text("${report.ocrCandidates.size} 页包含图像但文字较少，可按需要添加文字层。")
                            TextButton(onClick = { vm.queueTool("searchable") }) { Text("生成可搜索副本") }
                    } else Text(if(report.textPages>0) "已有可提取文字；可直接搜索、复制或转换。" else "未发现可提取文字；空白、矢量或内联图像页面可先查看原页，再决定是否识别。")
                        if (report.fieldTypes.isNotEmpty())
                            TextButton(onClick = vm::forms) { Text("填写普通表单") }
                        TextButton(onClick = { vm.queueTool("optimize") }) { Text("优化图片副本") }
                        if (report.fonts.any { !it.embedded })
                            Text(
                                "部分字体未嵌入，其他设备显示可能采用替代字体。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                }
                item {
                    DiagnosticCard("表单、批注与附件") {
                        Text(
                            "表单字段：${report.fieldTypes.values.sum()} · ${if(report.xfa) "含 XFA" else "无 XFA"}"
                        )
                        report.fieldTypes.forEach { (type, count) -> Text("$type：$count") }
                        Text("批注对象：${report.annotationTypes.values.sum()}")
                        report.annotationTypes.forEach { (type, count) -> Text("$type：$count") }
                        Text("附件：${report.attachments.size}")
                        report.attachments.forEach { Text(it) }
                    }
                }
                item {
                    DiagnosticCard("权限与签名记录") {
                        Text(if (report.encrypted) "当前文件带加密信息" else "当前文件未加密")
                        Text(
                            report.permissions.entries.joinToString(" · ") {
                                "${it.key}：${if(it.value) "允许" else "受限"}"
                            }
                        )
                        Text("签名记录：${report.signatures.size}")
                        report.signatures.forEach { signature ->
                            Text(
                                "${signature.optString("recordedName").ifBlank { "未记录姓名" }} · ${if(signature.optBoolean("reachesFileEnd")) "记录范围到文件末尾" else "记录范围未到当前文件末尾"}"
                            )
                        }
                        Text(
                            "此页不核验证书、签名有效性或 PDF/A 合规性。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item { Text("字体", style = MaterialTheme.typography.titleLarge) }
                items(report.fonts) { font ->
                    ListItem(
                        headlineContent = { Text(font.name) },
                        supportingContent = {
                            Text(
                                "${font.type} · ${if(font.embedded) "已嵌入" else "未嵌入"} · ${font.pages.size} 页引用"
                            )
                        },
                    )
                }
                item { Text("逐页信息", style = MaterialTheme.typography.titleLarge) }
                items(report.pages, key = { it.page }) { page ->
                    OutlinedCard(
                        onClick = {
                            doc?.let { vm.readerTarget.value = it.id to page.page - 1 }
                            vm.screen.value = "reader"
                        }
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("第 ${page.page} 页", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${page.width.toInt()} × ${page.height.toInt()} pt · 旋转 ${page.rotation}°"
                            )
                            Text(
                                "${page.characters} 字符 · ${page.images} 图像资源 · ${page.annotations} 批注"
                            )
                        }
                    }
                }
                if (report.warnings.isNotEmpty())
                    item { DiagnosticCard("读取提示") { report.warnings.forEach { Text(it) } } }
                item {
                    DiagnosticCard("文件指纹") {
                        Text(report.fileName)
                        Text(report.sha256, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
