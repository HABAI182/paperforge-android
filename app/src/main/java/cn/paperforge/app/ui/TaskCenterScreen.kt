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
import cn.paperforge.app.pdf.BatchConversions
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
    val filter by vm.taskFilter.collectAsState()
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
                        onClick = { vm.taskFilter.value = id },
                        label = { Text(name) },
                    )
                }
            }
            Text(
                "扫描、OCR、PDF 导出和外部保存集中管理。已完成的结果和失败检查点会保留。",
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
                        val textDraft =
                            row.type == "CONVERT" &&
                                org.json
                                    .JSONObject(row.payload)
                                    .optJSONObject("input")
                                    ?.optBoolean("draft") == true
                        val available =
                            BatchConversions.member(row) || textDraft ||
                                row.documentId != null ||
                                row.resourceEntries().any {
                                    it.id in setOf("result", "save-source") && File(it.path).isFile
                                }
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
                                        if (textDraft) "待编辑"
                                        else if (
                                            row.type == "CONVERT" &&
                                                row.state == LocalTaskState.PARTIAL
                                        )
                                            "部分完成"
                                        else taskStateName(row.state),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                Text(
                                    (if (row.type == "CAPTURE") "第 ${row.position+1} 页 · "
                                    else "") + row.detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                row.error?.let {
                                    Text(
                                        it,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (
                                    !active &&
                                        (row.type == "AUTO_EXPORT" ||
                                            row.type == "CONVERT" &&
                                                org.json
                                                    .JSONObject(row.payload)
                                                    .optJSONObject("input")
                                                    ?.optString("kind") == "RECIPE")
                                )
                                    TextButton(onClick = { vm.editWorkflowRun(row) }) {
                                        Text("调整配方并继续")
                                    }
                                if (row.state == LocalTaskState.RUNNING)
                                    LinearProgressIndicator(
                                        progress = { row.progress / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (row.type == "CAPTURE")
                                        TextButton(
                                            onClick = { preview = row },
                                            enabled =
                                                available && row.resourceEntries().isNotEmpty(),
                                        ) {
                                            Text("原图")
                                        }
                                    TextButton(
                                        onClick = { vm.openTask(row) },
                                        enabled = available,
                                    ) {
                                        Text(
                                            if (textDraft) "编辑草稿"
                                            else if (row.type == "CAPTURE") "查看文档"
                                            else if (row.type == "SAVE") "本地结果 / 另存" else "查看结果"
                                        )
                                    }
                                    if (
                                        !active &&
                                            !textDraft &&
                                            row.type == "CONVERT" &&
                                            org.json
                                                .JSONObject(row.payload)
                                                .getJSONObject("input")
                                                .optString("kind") == "LONG_IMAGES"
                                    )
                                        TextButton(onClick = { vm.editLongImageTask(row) }) {
                                            Text("调整接缝副本")
                                        }
                                    if (active)
                                        TextButton(onClick = { vm.cancelLocalTask(row) }) {
                                            Text("停止")
                                        }
                                    else if (
                                        row.state != LocalTaskState.SUCCEEDED &&
                                            (available || row.type in DocumentJobs.types)
                                    ) {
                                        if (!textDraft)
                                            TextButton(onClick = { vm.retryTask(row) }) {
                                                Text(
                                                    if (
                                                        org.json
                                                            .JSONObject(row.payload)
                                                            .optJSONObject("input")
                                                            ?.has("ocrRequest") == true
                                                    )
                                                        "按当前识别设置重试"
                                                    else "重试"
                                                )
                                            }
                                        if (row.type == "CAPTURE")
                                            TextButton(onClick = { vm.openTask(row, true) }) {
                                                Text("重拍")
                                            }
                                    }
                                }
                                if (!active && row.type in DocumentJobs.types)
                                    TextButton(onClick = { vm.forgetTask(row) }) {
                                        Text(if (textDraft) "删除转换草稿" else "移除记录")
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
