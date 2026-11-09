package cn.paperforge.network.lan

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanLibraryPicker(onDismiss: () -> Unit, onSelect: (List<String>) -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<JSONObject>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val library = remember { LanDocumentLibrary(context) }
    var preview by remember { mutableStateOf<String?>(null) }
    preview?.let { id ->
        LanPreviewDialog(
            { library.info(id) },
            { page ->
                context.contentResolver.openInputStream(library.previewUri(id, page))?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            },
            { preview = null },
        )
    }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) {
        try {
            rows =
                withContext(Dispatchers.IO) {
                    val list = LanDocumentLibrary(context).list()
                    (0 until list.length()).map { list.getJSONObject(it) }
                }
        } catch (e: Exception) {
            failure = e.message ?: "无法读取文档库"
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("从文档库选择", style = MaterialTheme.typography.titleLarge)
            Text("按应用内分类选取，扫描草稿自动导出为 PDF", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                query,
                { query = it },
                label = { Text("搜索名称或分类") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (rows == null && failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            failure?.let { Text(it) }
            LazyColumn(Modifier.weight(1f)) {
                val visible =
                    rows.orEmpty().filter {
                        query.isBlank() ||
                            (it.optString("title") + " " + it.optString("folder")).contains(
                                query,
                                true,
                            )
                    }
                if (rows != null && visible.isEmpty())
                    item { Text("暂无匹配文档", Modifier.padding(vertical = 20.dp)) }
                items(visible, key = { it.getString("id") }) { doc ->
                    val id = doc.getString("id")
                    val toggle = { selected = if (id in selected) selected - id else selected + id }
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(onClick = toggle)
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(id in selected, { toggle() })
                        val cover by
                            produceState<android.graphics.Bitmap?>(null, id) {
                                value =
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                                context.contentResolver
                                                    .openInputStream(library.uri(id, true))
                                                    ?.use {
                                                        android.graphics.BitmapFactory.decodeStream(
                                                            it
                                                        )
                                                    }
                                            }
                                            .getOrNull()
                                    }
                            }
                        DisposableEffect(cover) {
                            val image = cover
                            onDispose { image?.recycle() }
                        }
                        cover?.let {
                            Image(
                                it.asImageBitmap(),
                                null,
                                Modifier.size(54.dp).padding(end = 8.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                doc.getString("title"),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                doc.optString("folder").ifBlank { "未分类" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { preview = id }) { Text("预览") }
                    }
                }
            }
            Button(
                onClick = { onSelect(selected.toList()) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            ) {
                Text("加入发送队列 ${selected.size}")
            }
        }
    }
}
