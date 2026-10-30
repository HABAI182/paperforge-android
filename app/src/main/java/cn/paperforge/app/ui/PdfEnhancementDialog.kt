package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.model.parsePageRange
import cn.paperforge.app.pdf.PdfImageEnhancement
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
internal fun PdfEnhancementDialog(
    vm: MainViewModel,
    file: File?,
    currentPage: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var preset by rememberSaveable { mutableStateOf("BW") }
    var selection by rememberSaveable { mutableStateOf("current") }
    var range by rememberSaveable { mutableStateOf("") }
    var inspection by remember { mutableStateOf<PdfImageEnhancement.Inspection?>(null) }
    var error by remember { mutableStateOf("") }
    var request by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<Bitmap, Bitmap>?>(null) }
    var before by rememberSaveable { mutableStateOf(false) }
    val count = inspection?.pages ?: 0
    val requested =
        when (selection) {
            "current" -> (currentPage + 1).toString()
            "selected" -> range
            else -> ""
        }
    val selected =
        if (count > 0 && (selection != "selected" || range.isNotBlank()))
            runCatching { parsePageRange(requested, count) }.getOrNull()
        else null
    val previewPage = selected?.let { if (currentPage in it) currentPage else it.first() } ?: 0
    LaunchedEffect(file) {
        try {
            inspection =
                withContext(Dispatchers.IO) { PdfImageEnhancement.inspect(requireNotNull(file)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "无法读取 PDF"
        }
    }
    DisposableEffect(preview) {
        val owned = preview
        onDispose {
            owned?.first?.recycle()
            owned?.second?.recycle()
        }
    }
    LaunchedEffect(preset, requested, request) {
        preview = null
        before = false
        if (request == 0 || selected == null || inspection?.blocked != null || file == null)
            return@LaunchedEffect
        loading = true
        error = ""
        var generated: Pair<Bitmap, Bitmap>? = null
        val temp = File.createTempFile("enhance-preview-", ".pdf", context.cacheDir)
        try {
            withContext(Dispatchers.IO) {
                PdfImageEnhancement.enhance(file, temp, preset, (previewPage + 1).toString())
                val original = PdfSession(file).use { it.render(previewPage, 1000) }
                try {
                    generated = original to PdfSession(temp).use { it.render(previewPage, 1000) }
                } catch (e: Throwable) {
                    original.recycle()
                    throw e
                }
            }
            preview = generated
            generated = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "预览失败，可重试"
        } finally {
            generated?.first?.recycle()
            generated?.second?.recycle()
            temp.delete()
            loading = false
        }
    }
    SpaciousToolDialog(
        onDismissRequest = onDismiss,
        title = { Text("图像增强") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("只增强扫描底图，保留文字层、矢量和页尺寸，另存独立副本。")
                PreferenceBlock("增强预设") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PdfImageEnhancement.presets.forEach { (key, title) ->
                            FilterChip(preset == key, { preset = key }, label = { Text(title) })
                        }
                    }
                }
                PreferenceBlock("处理页面") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("current" to "当前页", "selected" to "选页", "all" to "整篇").forEach {
                            (key, title) ->
                            FilterChip(
                                selection == key,
                                { selection = key },
                                label = { Text(title) },
                            )
                        }
                    }
                    if (selection == "selected")
                        OutlinedTextField(
                            range,
                            { range = it },
                            label = { Text("页码，如 1-3,5") },
                            isError = selected == null,
                            singleLine = true,
                        )
                    Text(
                        if (count > 0) "共 $count 页 · 选择 ${selected?.size ?: 0} 页" else "正在读取文档…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if ((inspection?.annotations ?: 0) > 0 || inspection?.hasForms == true)
                    Text(
                        "批注、链接及表单保留且不参与增强。底图中的手写标记会随图像一起变化。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                Text(
                    "小图标、透明图像和内联图像保留；大尺寸底图可能按任务内存预算降采样。无扫描底图的页面会跳过。",
                    style = MaterialTheme.typography.bodySmall,
                )
                inspection?.blocked?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                OutlinedButton(
                    onClick = { request++ },
                    enabled = selected != null && !loading && inspection?.blocked == null,
                ) {
                    Text("预览第 ${previewPage+1} 页对比")
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                preview?.let { pair ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(before, { before = true }, label = { Text("原图") })
                        FilterChip(!before, { before = false }, label = { Text("增强后") })
                    }
                    Image(
                        (if (before) pair.first else pair.second).asImageBitmap(),
                        if (before) "原图预览" else "增强后预览",
                        Modifier.fillMaxWidth().height(360.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.queueTool(
                        "enhance-images",
                        JSONObject().put("preset", preset).put("range", requested),
                    )
                    onDismiss()
                },
                enabled = selected != null && inspection?.blocked == null && !loading,
            ) {
                Text("生成增强副本")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
