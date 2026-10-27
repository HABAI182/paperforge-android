package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import cn.paperforge.app.pdf.PdfSearch
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Preview stays interactive; the caller submits creation to the persistent PDF queue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfRedactionScreen(input: File, onBack: () -> Unit, onSubmit: (String, JSONObject) -> Unit) {
    val scope = rememberCoroutineScope()
    var page by remember(input) { mutableIntStateOf(0) }
    var count by remember(input) { mutableIntStateOf(0) }
    var bitmap by remember(input, page) { mutableStateOf<Bitmap?>(null) }
    var text by remember(input) { mutableStateOf("") }
    var hits by remember(input, page, text) { mutableStateOf<List<PdfSearch.Hit>>(emptyList()) }
    var a by remember(input, page) { mutableStateOf<Offset?>(null) }
    var b by remember(input, page) { mutableStateOf<Offset?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(input, page) {
        var pending: Bitmap? = null
        try {
            val (total, image) =
                withContext(Dispatchers.IO) {
                    PdfSession(input).use { session ->
                        val total = session.count
                        val image = session.render(page, 1200).copy(Bitmap.Config.ARGB_8888, false)
                        pending = image
                        total to image
                    }
                }
            count = total
            bitmap = image
            pending = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            message = failure.message ?: "无法打开文件"
        } finally {
            pending?.recycle()
        }
    }
    val renderedBitmap = bitmap
    DisposableEffect(renderedBitmap) { onDispose { renderedBitmap?.recycle() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文字 / 区域脱敏") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("先搜索文字或拖框选择区域，预览后生成新副本。原文件不会改动。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { page-- }, enabled = !busy && page > 0) { Text("上一页") }
                Text(
                    if (count > 0) "${page + 1} / $count" else "载入页面…",
                    Modifier.padding(top = 12.dp),
                )
                OutlinedButton(onClick = { page++ }, enabled = !busy && page + 1 < count) {
                    Text("下一页")
                }
            }
            bitmap?.let { image ->
                Box(Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height)) {
                    Image(image.asImageBitmap(), "待脱敏页面", Modifier.fillMaxSize())
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(page, busy) {
                            fun normalized(p: Offset) =
                                Offset(
                                    (p.x / size.width).coerceIn(0f, 1f),
                                    (p.y / size.height).coerceIn(0f, 1f),
                                )
                            detectDragGestures(
                                onDragStart = {
                                    if (!busy) {
                                        a = normalized(it)
                                        b = a
                                    }
                                }
                            ) { change, _ ->
                                if (!busy) {
                                    change.consume()
                                    b = normalized(change.position)
                                }
                            }
                        }
                    ) {
                        hits
                            .filter { it.page == page }
                            .forEach { h ->
                                drawRect(
                                    Color.Yellow.copy(alpha = .45f),
                                    Offset(h.left * size.width, h.top * size.height),
                                    Size(
                                        (h.right - h.left) * size.width,
                                        (h.bottom - h.top) * size.height,
                                    ),
                                )
                            }
                        val x = a
                        val y = b
                        if (x != null && y != null)
                            drawRect(
                                Color.Black.copy(alpha = .65f),
                                Offset(min(x.x, y.x) * size.width, min(x.y, y.y) * size.height),
                                Size(abs(x.x - y.x) * size.width, abs(x.y - y.y) * size.height),
                            )
                    }
                }
            }
            OutlinedTextField(
                text,
                { text = it },
                label = { Text("要删除的精确文字") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            OutlinedButton(
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    busy = true
                    message = ""
                    scope.launch {
                        val found =
                            withContext(Dispatchers.IO) {
                                runCatching { PdfSearch.find(input, text) }
                            }
                        found
                            .onSuccess {
                                hits = it.filter { hit -> hit.page == page }
                                message = "本页找到 ${hits.size} 处，黄色区域为搜索预览。语义删除要求本页目标唯一且位于单个文字片段。"
                            }
                            .onFailure { message = it.message ?: "搜索失败" }
                        busy = false
                    }
                },
            ) {
                Text("搜索并预览文字")
            }
            Text("语义文字删除仅处理当前页，其他页可能仍含相同文字；仅支持单个文字片段内可解码、唯一的目标。当前页含图像、Form XObject、复杂资源或跨片段文字时不支持。")
            Button(
                enabled = !busy && text.isNotBlank() && hits.size == 1,
                onClick = {
                    onSubmit("redact-semantic", JSONObject().put("page", page).put("target", text))
                },
            ) {
                Text("生成语义文字脱敏副本")
            }
            Text("整份栅格脱敏：拖框选择敏感区域，所有页面将变成图片，原生文字不可复制。")
            OutlinedButton(
                enabled =
                    !busy &&
                        a != null &&
                        b != null &&
                        abs(a!!.x - b!!.x) > .005f &&
                        abs(a!!.y - b!!.y) > .005f,
                onClick = {
                    val x = a!!
                    val y = b!!
                    onSubmit(
                        "redact-raster",
                        JSONObject()
                            .put("page", page)
                            .put("left", min(x.x, y.x))
                            .put("top", min(x.y, y.y))
                            .put("right", max(x.x, y.x))
                            .put("bottom", max(x.y, y.y)),
                    )
                },
            ) {
                Text("生成整份栅格脱敏副本")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message)
        }
    }
}
