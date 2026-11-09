package cn.paperforge.network.lan

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun LanPreviewDialog(
    loadInfo: () -> JSONObject,
    loadPage: (Int) -> Bitmap?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf(false) }
    var zoom by remember(page) { mutableFloatStateOf(1f) }
    var offset by remember(page) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    LaunchedEffect(Unit) {
        try {
            info = withContext(Dispatchers.IO) { loadInfo() }
        } catch (e: Exception) {
            error = e.message
            loading = false
        }
    }
    LaunchedEffect(info, page) {
        if (info == null) return@LaunchedEffect
        loading = true
        error = null
        var image: Bitmap? = null
        try {
            withContext(Dispatchers.IO) { image = loadPage(page) }
            bitmap = image
            image = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "无法预览"
        } finally {
            image?.recycle()
            loading = false
        }
    }
    DisposableEffect(bitmap) {
        val image = bitmap
        onDispose { image?.recycle() }
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        info?.optString("title") ?: "文档预览",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                    )
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Box(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                        .transformable(
                            rememberTransformableState { scale, pan, _ ->
                                zoom = (zoom * scale).coerceIn(1f, 5f)
                                offset =
                                    if (zoom == 1f) androidx.compose.ui.geometry.Offset.Zero
                                    else offset + pan
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let {
                        Image(
                            it.asImageBitmap(),
                            "文档页面预览",
                            Modifier.fillMaxSize().graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = offset.x
                                translationY = offset.y
                            },
                            contentScale = ContentScale.Fit,
                        )
                    }
                    if (loading) CircularProgressIndicator()
                    else if (bitmap == null) Text(error ?: "此格式可下载后查看")
                }
                val count = info?.optInt("pages", 1)?.coerceAtLeast(1) ?: 1
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { page-- }, enabled = page > 0 && !loading) { Text("上一页") }
                    Text("${page+1} / $count")
                    TextButton(onClick = { page++ }, enabled = page + 1 < count && !loading) {
                        Text("下一页")
                    }
                }
                info?.let { data ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp)
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                (if (data.optString("kind") == "scan") "扫描草稿 · 原图合计 "
                                else "${data.optString("mime","PDF")} · ") +
                                    Formatter.formatShortFileSize(context, data.optLong("bytes"))
                            )
                            TextButton(onClick = { details = !details }) {
                                Text(if (details) "收起详情" else "文件详细信息")
                            }
                            if (details) {
                                Text("分类  ${data.optString("folder").ifBlank { "未分类" }}")
                                if (data.optString("tags").isNotBlank())
                                    Text("标签  ${data.optString("tags")}")
                                Text(
                                    "修改时间  ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(data.optLong("modified")))}"
                                )
                                Text("共 $count 页 · 双指缩放检查内容")
                            }
                        }
                    }
                }
            }
        }
    }
}
