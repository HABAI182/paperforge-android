package cn.paperforge.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputHistoryScreen(vm: MainViewModel) {
    val all by vm.repo.dao.outputs().collectAsState(initial = emptyList())
    val source by vm.outputSource.collectAsState()
    val rows = all.filter { source == null || it.sourceDocumentId == source }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导出记录") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "home" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (rows.isEmpty()) item { Text("这里会保留每次生成的结果、来源和外部保存位置。") }
            items(rows, key = { it.path }) { row ->
                val file = File(row.path)
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${row.format.uppercase()} · ${Formatter.formatFileSize(context,file.length())} · ${SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(Date(row.created))}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text("来源：${row.sourceTitle}", style = MaterialTheme.typography.bodySmall)
                        row.externalUri?.let {
                            Text(
                                "已保存到：${android.net.Uri.decode(it)}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                            )
                        }
                        Row {
                            TextButton(onClick = { vm.reopenOutput(row) }) { Text("分享 / 另存") }
                            if (row.outputDocumentId != null)
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            vm.repo.dao
                                                .document(row.outputDocumentId)
                                                ?.let(vm::open)
                                        }
                                    }
                                ) {
                                    Text("打开文档")
                                }
                            if (row.outputDocumentId != null)
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            vm.repo.dao.document(row.outputDocumentId)?.let {
                                                vm.repo.dao.updateDocument(it.copy(listed = true))
                                                vm.messages.send("已加入文档库")
                                            }
                                        }
                                    }
                                ) {
                                    Text("加入文档库")
                                }
                        }
                    }
                }
            }
        }
    }
}
