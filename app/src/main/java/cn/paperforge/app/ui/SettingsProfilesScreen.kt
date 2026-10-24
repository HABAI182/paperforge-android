package cn.paperforge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsProfilesScreen(vm: MainViewModel) {
    val profiles = remember { SettingsProfiles(vm.repo.context) }
    var revision by remember { mutableIntStateOf(0) }
    val saved = remember(revision) { profiles.saved() }
    var groups by remember { mutableStateOf(setOf("scan")) }
    var saving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val importing =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                scope.launch {
                    runCatching {
                            withContext(Dispatchers.IO) {
                                val text =
                                    vm.repo.context.contentResolver
                                        .openInputStream(uri)
                                        ?.bufferedReader()
                                        ?.use { it.readText() } ?: error("无法读取档案")
                                profiles.importJson(text)
                            }
                        }
                        .onSuccess {
                            message = "已导入 $it 份档案，选择后即可应用"
                            revision++
                        }
                        .onFailure { message = it.message ?: "导入失败" }
                }
        }
    fun apply(row: SettingsProfile) {
        profiles.apply(row)
        vm.screen.value = vm.profileReturn.value
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置档案") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = vm.profileReturn.value }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "套用档案只修改它包含的参数。当前页和已拍照片保留各自拍摄时的设置。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item { Text("快捷扫描", style = MaterialTheme.typography.titleLarge) }
            items(SettingsProfiles.modes, key = { it.id }) { row ->
                OutlinedCard(onClick = { apply(row) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(row.name, style = MaterialTheme.typography.titleMedium)
                        Text(row.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("保存当前配置", style = MaterialTheme.typography.titleLarge) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsProfiles.groups.forEach { (id, label) ->
                        FilterChip(
                            selected = id in groups,
                            onClick = { groups = if (id in groups) groups - id else groups + id },
                            label = { Text(label) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { saving = true }, enabled = groups.isNotEmpty()) {
                        Text("保存为新档案")
                    }
                    OutlinedButton(
                        onClick = {
                            profiles.defaults(groups)
                            message = "已恢复所选分组的默认值"
                        },
                        enabled = groups.isNotEmpty(),
                    ) {
                        Text("恢复分组默认")
                    }
                }
            }
            item { Text("我的档案", style = MaterialTheme.typography.titleLarge) }
            if (saved.isEmpty())
                item { Text("尚未保存自定义档案", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(saved, key = { it.id }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(row.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${row.values.length()} 项参数 · 只覆盖已保存分组",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { apply(row) }) { Text("应用") }
                            TextButton(
                                onClick = {
                                    profiles.copy(row)
                                    revision++
                                }
                            ) {
                                Text("复制")
                            }
                            TextButton(
                                onClick = {
                                    profiles.remove(row)
                                    revision++
                                }
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::exportProfiles) { Text("导出档案") }
                    OutlinedButton(
                        onClick = {
                            importing.launch(
                                arrayOf("application/json", "application/octet-stream")
                            )
                        }
                    ) {
                        Text("导入档案")
                    }
                }
                message?.let {
                    Text(
                        it,
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    if (saving)
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("为档案命名") },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("名称") })
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        profiles.save(name, groups)
                        name = ""
                        saving = false
                        revision++
                    },
                    enabled = name.isNotBlank(),
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("取消") } },
        )
}
