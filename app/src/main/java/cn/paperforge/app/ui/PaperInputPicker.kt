package cn.paperforge.app.ui

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.OutputNames
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaperFileLauncher internal constructor(private val open: (Array<String>) -> Unit) {
    fun launch(types: Array<String>) = open(types)
}

@Composable
fun rememberPaperDocumentPicker(onResult: (Uri?) -> Unit): PaperFileLauncher =
    rememberPaperInputPicker(false) { onResult(it.firstOrNull()) }

@Composable
fun rememberPaperDocumentsPicker(onResult: (List<Uri>) -> Unit): PaperFileLauncher =
    rememberPaperInputPicker(true, onResult)

internal data class LibraryInput(
    val id: String,
    val title: String,
    val detail: String,
    val mime: String,
    val documentId: String? = null,
    val file: File? = null,
)

internal fun inputMimeMatches(mime: String, types: Array<String>) =
    types.any {
        it == "*/*" ||
            it.equals(mime, true) ||
            it.endsWith("/*") && mime.startsWith(it.substringBefore('/') + "/")
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberPaperInputPicker(
    multiple: Boolean,
    onResult: (List<Uri>) -> Unit,
): PaperFileLauncher {
    val context = LocalContext.current
    val repo = (context.applicationContext as PaperForgeApp).repository
    val callback by rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    var types by remember { mutableStateOf<Array<String>?>(null) }
    var library by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<LibraryInput>?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val single =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            callback(listOfNotNull(it))
        }
    val many =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            callback(it)
        }
    fun phone() {
        val value = types ?: return
        types = null
        if (multiple) many.launch(value) else single.launch(value)
    }
    val requested = types
    if (requested != null && !library)
        AlertDialog(
            onDismissRequest = {
                types = null
                callback(emptyList())
            },
            title = { Text("从哪里导入") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = { library = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.LibraryBooks, null)
                        Spacer(Modifier.width(8.dp))
                        Text("PaperForge 文档库")
                    }
                    OutlinedButton(onClick = { phone() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text("手机文件")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = {
                        types = null
                        callback(emptyList())
                    }
                ) {
                    Text("取消")
                }
            },
        )
    LaunchedEffect(requested?.toList(), library) {
        if (requested == null || !library) return@LaunchedEffect
        rows = null
        selected = emptySet()
        notice = null
        query = ""
        try {
            rows =
                withContext(Dispatchers.IO) {
                    val result = mutableListOf<LibraryInput>()
                    val image =
                        requested.any { it == "image/*" || it.startsWith("image/") } &&
                            !inputMimeMatches("application/pdf", requested)
                    repo.dao
                        .allDocuments()
                        .filter { it.listed && it.trashedAt == null }
                        .forEach { doc ->
                            if (
                                (doc.kind == "scan" || doc.pdfPath != null) &&
                                    (image || inputMimeMatches("application/pdf", requested))
                            ) {
                                if (doc.kind != "scan" || repo.dao.pages(doc.id).isNotEmpty())
                                    result +=
                                        LibraryInput(
                                            "doc:${doc.id}",
                                            doc.title,
                                            doc.folder.ifBlank { "未分类" } +
                                                if (image) " · 按页导入图片"
                                                else if (doc.kind == "scan") " · 扫描草稿转 PDF"
                                                else " · PDF",
                                            if (image) "image/png" else "application/pdf",
                                            documentId = doc.id,
                                        )
                            }
                        }
                    val documentPaths =
                        repo.dao
                            .allDocuments()
                            .filter { it.listed && it.trashedAt == null }
                            .mapNotNull { it.pdfPath }
                            .toSet()
                    repo.dao
                        .allOutputs()
                        .filter { it.path !in documentPaths }
                        .forEach { output ->
                            val file = File(output.path)
                            val mime =
                                MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(file.extension.lowercase())
                                    ?: when (file.extension.lowercase()) {
                                        "md" -> "text/markdown"
                                        "csv" -> "text/csv"
                                        else -> "application/octet-stream"
                                    }
                            if (file.isFile && inputMimeMatches(mime, requested))
                                result +=
                                    LibraryInput(
                                        "output:${output.path}",
                                        OutputNames.display(repo, file),
                                        "导出结果 · ${output.format.uppercase()} · ${android.text.format.Formatter.formatShortFileSize(context,file.length())}",
                                        mime,
                                        file = file,
                                    )
                        }
                    result
                }
        } catch (e: Exception) {
            notice = e.message ?: "无法读取文档库"
        }
    }
    if (requested != null && library)
        ModalBottomSheet(
            onDismissRequest = {
                if (!preparing) {
                    types = null
                    callback(emptyList())
                }
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("从 PaperForge 导入", style = MaterialTheme.typography.titleLarge)
                Text("按当前功能筛选格式，扫描草稿可直接作为 PDF 或逐页图片使用", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("搜索名称或分类") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (rows == null || preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                notice?.let { Text(it) }
                LazyColumn(Modifier.weight(1f)) {
                    val visible =
                        rows.orEmpty().filter {
                            query.isBlank() || (it.title + it.detail).contains(query, true)
                        }
                    if (rows != null && visible.isEmpty())
                        item { Text("暂无符合格式的文档，可从手机文件导入", Modifier.padding(vertical = 20.dp)) }
                    items(visible, key = { it.id }) { item ->
                        val choose = {
                            selected =
                                if (multiple) {
                                    if (item.id in selected) selected - item.id
                                    else selected + item.id
                                } else setOf(item.id)
                        }
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !preparing, onClick = choose)
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(item.id in selected, { choose() }, enabled = !preparing)
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                Text(item.detail, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { phone() }, enabled = !preparing) { Text("手机文件") }
                    Button(
                        onClick = {
                            preparing = true
                            scope.launch {
                                try {
                                    val chosen = rows.orEmpty().filter { it.id in selected }
                                    val uris =
                                        withContext(Dispatchers.IO) {
                                            chosen.flatMap { item ->
                                                if (item.documentId != null) {
                                                    val root =
                                                        Uri.parse(
                                                            "content://${context.packageName}.library/documents/${item.documentId}"
                                                        )
                                                    if (item.mime.startsWith("image/")) {
                                                        val doc =
                                                            repo.dao.document(item.documentId)
                                                                ?: error("文档已移除")
                                                        val count =
                                                            if (doc.kind == "scan")
                                                                repo.dao.pages(doc.id).size
                                                            else
                                                                android.os.ParcelFileDescriptor
                                                                    .open(
                                                                        File(doc.pdfPath!!),
                                                                        android.os
                                                                            .ParcelFileDescriptor
                                                                            .MODE_READ_ONLY,
                                                                    )
                                                                    .use { fd ->
                                                                        android.graphics.pdf
                                                                            .PdfRenderer(fd)
                                                                            .use { it.pageCount }
                                                                    }
                                                        (0 until
                                                                if (multiple) count
                                                                else minOf(count, 1))
                                                            .map {
                                                                root
                                                                    .buildUpon()
                                                                    .appendPath("image")
                                                                    .appendPath(it.toString())
                                                                    .build()
                                                            }
                                                    } else
                                                        listOf(
                                                            root
                                                                .buildUpon()
                                                                .appendPath("pdf")
                                                                .build()
                                                        )
                                                } else {
                                                    val file = requireNotNull(item.file)
                                                    listOf(
                                                        runCatching {
                                                                FileProvider.getUriForFile(
                                                                    context,
                                                                    "${context.packageName}.files",
                                                                    file,
                                                                    item.title,
                                                                )
                                                            }
                                                            .getOrElse {
                                                                val copy =
                                                                    File(
                                                                        File(
                                                                                context.cacheDir,
                                                                                "share/library-input/${java.util.UUID.randomUUID()}",
                                                                            )
                                                                            .apply { mkdirs() },
                                                                        cn.paperforge.app.model
                                                                            .safeFileName(
                                                                                item.title
                                                                            ),
                                                                    )
                                                                file.copyTo(copy)
                                                                FileProvider.getUriForFile(
                                                                    context,
                                                                    "${context.packageName}.files",
                                                                    copy,
                                                                    item.title,
                                                                )
                                                            }
                                                    )
                                                }
                                            }
                                        }
                                    types = null
                                    callback(uris)
                                } catch (e: Exception) {
                                    notice = e.message ?: "文档未能导入"
                                } finally {
                                    preparing = false
                                }
                            }
                        },
                        enabled = selected.isNotEmpty() && !preparing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (preparing) "正在准备" else "导入所选 ${selected.size}")
                    }
                }
            }
        }
    return remember {
        PaperFileLauncher {
            types = it
            library = false
        }
    }
}
