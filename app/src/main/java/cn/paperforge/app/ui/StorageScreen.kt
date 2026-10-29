package cn.paperforge.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val storage = remember {
        LibraryStorage((context.applicationContext as PaperForgeApp).repository)
    }
    var report by remember { mutableStateOf<StorageReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf("document-covers", "reader-text")) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refresh) {
        runCatching { storage.report() }
            .onSuccess {
                report = it
                error = null
            }
            .onFailure { error = it.message }
    }
    fun size(bytes: Long) = Formatter.formatFileSize(context, bytes)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("存储空间") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = vm.storageReturn.value }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val value = report
            if (value == null && error == null) CircularProgressIndicator()
            error?.let {
                Text(it)
                TextButton(onClick = { refresh++ }) { Text("重新读取") }
            }
            if (value != null) {
                Text(size(value.total), style = MaterialTheme.typography.headlineLarge)
                Text("文档、导出与运行缓存", style = MaterialTheme.typography.bodyMedium)
                value.buckets.forEach { bucket ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (bucket.cache)
                            Checkbox(
                                bucket.id in selected,
                                { checked ->
                                    selected =
                                        if (checked) selected + bucket.id else selected - bucket.id
                                },
                                enabled = !busy,
                            )
                        Column(Modifier.weight(1f)) {
                            Text(bucket.title)
                            Text("${bucket.files} 个文件", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(size(bucket.bytes), style = MaterialTheme.typography.labelLarge)
                    }
                }
                val reclaim =
                    value.buckets.filter { it.cache && it.id in selected }.sumOf { it.bytes }
                FilledTonalButton(
                    onClick = { confirm = true },
                    enabled = !busy && reclaim > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "正在清理…" else "清理所选缓存 · ${size(reclaim)}")
                }
                Text(
                    "只清理可重新生成的缩略图和阅读补识别结果。原件、历史、批注、已入库 PDF 与导出文件均保留。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("清理所选缓存？") },
            text = { Text("重新打开文档时会按需重建这些缓存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        busy = true
                        scope.launch {
                            runCatching { storage.clearCaches(selected) }
                                .onFailure { error = it.message }
                            busy = false
                            refresh++
                        }
                    }
                ) {
                    Text("清理")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
}
