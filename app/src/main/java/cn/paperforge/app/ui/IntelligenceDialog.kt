package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.UserSettings
import cn.paperforge.app.data.DocumentRow
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.parsePageRange
import cn.paperforge.app.pdf.PageTextSource
import cn.paperforge.app.pdf.ReaderDocument
import cn.paperforge.app.system.NetworkTools
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.*

/** Explicitly freezes selected text; it never writes OCR or document records. */
@Composable
internal fun IntelligenceDialog(
    doc: DocumentRow,
    pageCount: Int,
    currentPage: Int,
    pdf: File? = null,
    pages: List<PageRow> = emptyList(),
    excerpt: Pair<Int, String>? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf("${currentPage+1}") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    AlertDialog(
        onDismissRequest = {
            job?.cancel()
            onDismiss()
        },
        title = { Text("翻译与智能工具") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (excerpt == null) "选择页码生成原文快照，再选择本机翻译、本地摘录或自定义服务。" else "将所选文字与原页码交给智能工具。")
                if (excerpt == null)
                    OutlinedTextField(
                        value = range,
                        onValueChange = { range = it },
                        enabled = !busy,
                        label = { Text("页码，如 1-3,5；留空为全部") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                Text(
                    "原始 OCR 保留。图片页需要时在本机读取文字；自定义服务仅在你点击运行后接收所选文字。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progress)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    job =
                        scope.launch {
                            try {
                                val selected =
                                    if (excerpt != null) listOf(excerpt.first)
                                    else parsePageRange(range, pageCount)
                                val app = context.applicationContext as PaperForgeApp
                                val engine = app.ocrEngine.snapshot()
                                val texts = mutableListOf<JSONObject>()
                                for ((done, index) in selected.withIndex()) {
                                    ensureActive()
                                    progress = "读取第 ${index+1} 页 · ${done+1}/${selected.size}"
                                    val text =
                                        if (excerpt != null) excerpt.second
                                        else if (pdf != null)
                                            ReaderDocument.order(
                                                    PageTextSource.load(
                                                        context,
                                                        pdf,
                                                        index,
                                                        engine,
                                                    ),
                                                    UserSettings.string("readerSelectionOrder", "0")
                                                        .toIntOrNull() ?: 0,
                                                )
                                                .joinToString("\n") { it.text }
                                        else
                                            pages
                                                .getOrNull(index)
                                                ?.let { page ->
                                                    page.ocrText
                                                        ?: engine.recognize(
                                                            page,
                                                            UserSettings.string(
                                                                "ocrLanguage",
                                                                "CHINESE",
                                                            ) != "LATIN",
                                                        )
                                                }
                                                .orEmpty()
                                    texts += JSONObject().put("page", index + 1).put("text", text)
                                    require(
                                        texts.sumOf { it.getString("text").length } <= 2_000_000
                                    ) {
                                        "本次文字超过200万字，请分批选择页码"
                                    }
                                }
                                require(texts.any { it.getString("text").isNotBlank() }) {
                                    "所选页面暂无可读文字，请先识字或圈选区域"
                                }
                                val file =
                                    withContext(Dispatchers.IO) {
                                        File(
                                                context.cacheDir,
                                                "share/intelligence-${UUID.randomUUID()}/source.json",
                                            )
                                            .apply {
                                                parentFile!!.mkdirs()
                                                writeText(
                                                    JSONObject()
                                                        .put("format", 1)
                                                        .put("documentId", doc.id)
                                                        .put("title", doc.title)
                                                        .put("pages", JSONArray(texts))
                                                        .toString(),
                                                    Charsets.UTF_8,
                                                )
                                            }
                                    }
                                NetworkTools.intelligence(context, file)
                                onDismiss()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "原文读取未完成"
                            } finally {
                                busy = false
                            }
                        }
                },
            ) {
                Text("选择运行方式")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    job?.cancel()
                    onDismiss()
                }
            ) {
                Text(if (busy) "取消读取" else "取消")
            }
        },
    )
}
