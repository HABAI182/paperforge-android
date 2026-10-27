package cn.paperforge.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.UserSettings
import cn.paperforge.app.ocr.OcrModelPack
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OcrModelsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as PaperForgeApp
    val packs by app.ocrModels.installed.collectAsState()
    val revision by UserSettings.revision.collectAsState()
    val backend = remember(revision) { UserSettings.string("ocrBackend", "MLKIT") }
    val selected = remember(revision) { UserSettings.string("ocrPackId", "") }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<OcrModelPack?>(null) }
    val scope = rememberCoroutineScope()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                scope.launch {
                    working = true
                    notice = "正在导入模型包"
                    try {
                        val model = app.ocrModels.install(uri)
                        notice = "已导入 ${model.name}，可选择使用"
                    } catch (error: Exception) {
                        notice = error.message ?: "模型包未能导入"
                    } finally {
                        working = false
                    }
                }
        }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("离线 OCR 与模型包") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = vm.ocrModelsReturn.value }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("内置识别可直接离线使用。独立模型包可在手机上导入、切换和移除。", style = MaterialTheme.typography.bodyMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("内置 ML Kit", style = MaterialTheme.typography.titleMedium)
                    Text("中英 / 拉丁字母 · 快速、均衡、精细", style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        onClick = {
                            scope.launch {
                                UserSettings.set("ocrBackend", "MLKIT")
                                app.ocrEngine.trimUnused()
                            }
                        },
                        enabled = !working && backend != "MLKIT",
                    ) {
                        Text(if (backend == "MLKIT") "正在使用" else "使用内置识别")
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        picker.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    enabled = !working,
                ) {
                    Text("导入模型包")
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    "https://github.com/HABAI182/paperforge-android/tree/main/model-packs"
                                ),
                            )
                        )
                    }
                ) {
                    Text("获取模型包")
                }
            }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
            if (backend == "PADDLE" && packs.none { it.id == selected })
                Text("所选模型包尚未安装，当前使用内置识别。", style = MaterialTheme.typography.bodySmall)
            packs.forEach { pack ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(pack.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${pack.languageSummary} · ${"%.1f".format(pack.bytes/1048576.0)} MB · ${pack.version}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Paddle 单路行级识别；模型包自带语言字典。", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        UserSettings.set("ocrPackId", pack.id)
                                        UserSettings.set("ocrBackend", "PADDLE")
                                        app.ocrEngine.trimUnused()
                                    }
                                },
                                enabled = !working && (backend != "PADDLE" || selected != pack.id),
                            ) {
                                Text(
                                    if (backend == "PADDLE" && selected == pack.id) "正在使用"
                                    else "使用此模型"
                                )
                            }
                            TextButton(onClick = { info = pack }) { Text("版本与来源") }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        working = true
                                        notice = "等待当前识别完成并移除模型"
                                        try {
                                            app.ocrEngine.remove(pack)
                                            notice = "模型已移除"
                                        } catch (error: Exception) {
                                            notice = error.message ?: "模型未能移除"
                                        } finally {
                                            working = false
                                        }
                                    }
                                },
                                enabled = !working,
                            ) {
                                Text("移除")
                            }
                        }
                    }
                }
            }
        }
    }
    info?.let { pack ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(pack.name) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text("版本：${pack.version}")
                    Text("语言：${pack.languageSummary}")
                    Text("安装大小：${"%.1f".format(pack.bytes/1048576.0)} MB")
                    Text(
                        "模型指纹：${pack.fingerprint.take(16)}…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "SDK：${pack.manifest.optString("sdkCommit").take(12)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(
                                        pack.manifest.optString(
                                            "source",
                                            "https://github.com/PaddlePaddle/PaddleOCR",
                                        )
                                    ),
                                )
                            )
                        }
                    ) {
                        Text("查看模型来源")
                    }
                    Text(
                        "模型和字典按版本一起安装。移除后，可重新导入同一包；文档原件与已识别文字仍保留。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { info = null }) { Text("完成") } },
        )
    }
}
