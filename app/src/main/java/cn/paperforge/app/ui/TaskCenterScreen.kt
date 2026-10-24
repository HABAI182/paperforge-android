package cn.paperforge.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun taskStateName(state: LocalTaskState) =
    when (state) {
        LocalTaskState.CAPTURING -> "保存照片"
        LocalTaskState.QUEUED -> "等待处理"
        LocalTaskState.RUNNING -> "处理中"
        LocalTaskState.SUCCEEDED -> "已完成"
        LocalTaskState.PARTIAL -> "待检查"
        LocalTaskState.FAILED -> "处理失败"
        LocalTaskState.CANCELLED -> "已停止"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCenterScreen(vm: MainViewModel) {
    val tasks by vm.localTasks.collectAsState()
    var filter by remember { mutableStateOf("ACTIVE") }
    var preview by remember { mutableStateOf<LocalTaskRow?>(null) }
    val selected =
        tasks.filter {
            when (filter) {
                "ACTIVE" -> it.state in LocalTaskRow.active
                "ATTENTION" ->
                    it.state in
                        setOf(
                            LocalTaskState.FAILED,
                            LocalTaskState.PARTIAL,
                            LocalTaskState.CANCELLED,
                        )
                else -> true
            }
        }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("任务中心") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "home" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("ACTIVE" to "进行中", "ATTENTION" to "待处理", "ALL" to "全部").forEach { (id, name)
                    ->
                    FilterChip(
                        selected = filter == id,
                        onClick = { filter = id },
                        label = { Text(name) },
                    )
                }
            }
            Text(
                "扫描照片先保留原图，再在后台识边。离开页面后继续处理。",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            if (selected.isEmpty())
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这里暂时没有任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            else
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(selected, key = { it.id }) { row ->
                        val active = row.state in LocalTaskRow.active
                        val available = row.documentId != null
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (active) Icons.Default.PendingActions
                                        else if (row.state == LocalTaskState.SUCCEEDED)
                                            Icons.Default.CheckCircle
                                        else Icons.Default.Info,
                                        null,
                                    )
                                    Text(
                                        row.title,
                                        Modifier.weight(1f).padding(horizontal = 10.dp),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        taskStateName(row.state),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                Text(
                                    "第 ${row.position+1} 页 · ${row.detail}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                row.error?.let {
                                    Text(
                                        it,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (row.state == LocalTaskState.RUNNING)
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(
                                        onClick = { preview = row },
                                        enabled = available && row.resourceEntries().isNotEmpty(),
                                    ) {
                                        Text("原图")
                                    }
                                    TextButton(
                                        onClick = { vm.openTask(row) },
                                        enabled = available,
                                    ) {
                                        Text("查看文档")
                                    }
                                    if (active)
                                        TextButton(onClick = { vm.cancelLocalTask(row) }) {
                                            Text("停止")
                                        }
                                    else if (row.state != LocalTaskState.SUCCEEDED && available) {
                                        TextButton(onClick = { vm.retryTask(row) }) { Text("重试") }
                                        TextButton(onClick = { vm.openTask(row, true) }) {
                                            Text("重拍")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }
    preview?.let { row ->
        val result by
            produceState<Result<android.graphics.Bitmap>?>(null, row.id) {
                value =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            cn.paperforge.app.imaging.ScanProcessor.decode(
                                File(row.resourceEntries().first().path),
                                1800,
                            )
                        }
                    }
            }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("拍摄原图") },
            text = {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 500.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    result?.fold(
                        onSuccess = {
                            Image(it.asImageBitmap(), "未经处理的照片", Modifier.fillMaxWidth())
                        },
                        onFailure = { Text("照片未完整保存，请重拍") },
                    ) ?: CircularProgressIndicator()
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } },
        )
    }
}
