package cn.paperforge.app.ui

import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.reader.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyNotesScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<StudyNote?>(null) }
    var deleting by remember { mutableStateOf<StudyNote?>(null) }
    val notes by
        produceState(emptyList<StudyNote>(), revision) {
            runCatching { withContext(Dispatchers.IO) { StudyNotes.load(context) } }
                .onSuccess { value = it }
                .onFailure { error = it.message }
        }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("学习摘录") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "home" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                query,
                { query = it },
                placeholder = { Text("搜索摘录、笔记与来源") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("md" to "Markdown", "docx" to "Word", "tsv" to "Anki TSV").forEach {
                    (format, label) ->
                    TextButton(
                        onClick = { vm.exportStudyNotes(format) },
                        enabled = notes.isNotEmpty(),
                    ) {
                        Text("导出 $label")
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (notes.isEmpty())
                    item {
                        Text(
                            "在 PDF 阅读设置中打开“文字与朗读”，选择段落后保存摘录。也可以长按 PDF 页面进入文字选择。",
                            Modifier.padding(vertical = 24.dp),
                        )
                    }
                items(
                    notes
                        .filter {
                            listOf(it.quote, it.note, it.title).any { text ->
                                text.contains(query, true)
                            }
                        }
                        .sortedByDescending { it.created },
                    key = { it.id },
                ) { note ->
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "${note.title} · 第 ${note.page + 1} 页",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            SelectionContainer {
                                Text(note.quote, style = MaterialTheme.typography.bodyLarge)
                            }
                            if (note.note.isNotBlank())
                                Text(note.note, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row {
                                TextButton(
                                    onClick = {
                                        vm.openDocumentLink(
                                            Uri.parse(
                                                "paperforge://document/${note.documentId}?page=${note.page + 1}"
                                            )
                                        )
                                    }
                                ) {
                                    Text("回到来源")
                                }
                                TextButton(onClick = { editing = note }) { Text("编辑") }
                                TextButton(onClick = { deleting = note }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { selected ->
        var quote by remember(selected.id) { mutableStateOf(selected.quote) }
        var note by remember(selected.id) { mutableStateOf(selected.note) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑摘录") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(quote, { quote = it }, label = { Text("摘录") })
                    OutlinedTextField(note, { note = it }, label = { Text("笔记") })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                    withContext(Dispatchers.IO) {
                                        StudyNotes.update(context, selected.id, quote, note)
                                    }
                                }
                                .onSuccess {
                                    editing = null
                                    revision++
                                }
                                .onFailure { error = it.message }
                        }
                    },
                    enabled = quote.isNotBlank(),
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
    deleting?.let { selected ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条摘录？") },
            text = { Text("来源文档不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                    withContext(Dispatchers.IO) {
                                        StudyNotes.remove(context, selected.id)
                                    }
                                }
                                .onSuccess {
                                    deleting = null
                                    revision++
                                }
                                .onFailure { error = it.message }
                        }
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } },
        )
    }
}
