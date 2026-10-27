package cn.paperforge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.BackupStore

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupRestoreDialog(vm: MainViewModel) {
    val pending by vm.backupPreview.collectAsState()
    val preview = pending?.second ?: return
    var applyWorkspace by remember(pending?.first) { mutableStateOf(true) }
    var showDetails by remember(pending?.first) { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = vm::cancelBackupRestore,
        title = { Text("恢复备份") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "${preview.documents} 份文档 · ${"%.1f".format(preview.bytes / 1048576.0)} MB",
                    style = MaterialTheme.typography.titleMedium,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("已存在 ${preview.sameId} 份", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "仅名称相同 ${preview.sameName} 份",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "按备份来源识别，不按名称判重。",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                }
                Text("跳过会保留本机版本；全部追加会创建副本。")
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .toggleable(
                                value = applyWorkspace,
                                role = Role.Checkbox,
                                onValueChange = { applyWorkspace = it },
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = applyWorkspace, onCheckedChange = null)
                    Column(Modifier.weight(1f)) {
                        Text("恢复应用设置和工作区", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "替换外观等偏好、导入档案并恢复组合草稿；不勾选则保留本机。",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                }
                Text(
                    "新文档恢复阅读位置、书签和摘录；配方与规则合并，新规则停用。全部跳过时仍会合并，并按勾选应用设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                Column {
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "收起说明" else "更多说明")
                    }
                    if (showDetails) {
                        Text(
                            "本机已有自动规则保持原状，恢复的文档后续更改可触发；已排队或运行的任务继续处理。不勾选时，本机签名、库视图和配置档案也会保留。",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                }
            }
        },
        confirmButton = {
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = vm::cancelBackupRestore) { Text("取消") }
                TextButton(
                    onClick = {
                        vm.confirmBackupRestore(
                            BackupStore.DuplicatePolicy.SKIP_SAME_ID,
                            applyWorkspace,
                        )
                    }
                ) {
                    Text("跳过已存在")
                }
                TextButton(
                    onClick = {
                        vm.confirmBackupRestore(
                            BackupStore.DuplicatePolicy.APPEND_ALL,
                            applyWorkspace,
                        )
                    }
                ) {
                    Text("全部追加")
                }
            }
        },
    )
}
