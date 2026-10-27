package cn.paperforge.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextConversionScreen(vm: MainViewModel) {
    val draft by vm.textConversion.collectAsState()
    val busy by vm.busy.collectAsState()
    var index by remember(draft?.taskId) { mutableIntStateOf(0) }
    var options by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("正文转 PDF") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = vm.conversionReturn.value }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回转换中心")
                    }
                },
                actions = {
                    TextButton(onClick = vm::exportEditedText, enabled = draft != null && !busy) {
                        Text("保存 TXT")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = vm::generateTextConversion,
                    enabled = !busy && draft?.chunks?.any { it.isNotBlank() } == true,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) {
                    Text("生成 PDF")
                }
            }
        },
    ) { padding ->
        val active = draft
        if (active != null)
            Column(
                Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(active.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "原件单独保留，修改只写入正文草稿。长文本分块编辑，导出时按原顺序合并。",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { options = !options }) {
                    Text(
                        "排版设置 · ${active.options.paper} · ${if(active.options.landscape) "横向" else "纵向"} · ${active.options.fontSize.toInt()} pt"
                    )
                }
                if (options)
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf("A4", "A5", "Letter").forEach { paper ->
                                FilterChip(
                                    active.options.paper == paper,
                                    {
                                        vm.updateTextConversion(
                                            options = active.options.copy(paper = paper)
                                        )
                                    },
                                    label = { Text(paper) },
                                )
                            }
                            FilterChip(
                                active.options.landscape,
                                {
                                    vm.updateTextConversion(
                                        options =
                                            active.options.copy(
                                                landscape = !active.options.landscape
                                            )
                                    )
                                },
                                label = { Text("横向") },
                            )
                        }
                        Text("字号 ${active.options.fontSize.toInt()} pt")
                        Slider(
                            active.options.fontSize,
                            {
                                vm.updateTextConversion(
                                    options = active.options.copy(fontSize = it)
                                )
                            },
                            valueRange = 6f..72f,
                            steps = 65,
                        )
                        Text("页边距 ${active.options.margin.toInt()} pt")
                        Slider(
                            active.options.margin,
                            { vm.updateTextConversion(options = active.options.copy(margin = it)) },
                            valueRange = 0f..96f,
                        )
                        Text("行距 ${"%.2f".format(active.options.lineSpacing)} 倍")
                        Slider(
                            active.options.lineSpacing,
                            {
                                vm.updateTextConversion(
                                    options = active.options.copy(lineSpacing = it)
                                )
                            },
                            valueRange = 1f..3f,
                        )
                    }
                if (active.chunks.size > 1)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { index-- }, enabled = index > 0) { Text("上一块") }
                        TextButton(onClick = { jump = true }) {
                            Text("${index+1} / ${active.chunks.size}")
                        }
                        TextButton(
                            onClick = { index++ },
                            enabled = index + 1 < active.chunks.size,
                        ) {
                            Text("下一块")
                        }
                    }
                OutlinedTextField(
                    active.chunks.getOrElse(index) { "" },
                    { vm.updateTextConversion(index, it) },
                    Modifier.fillMaxWidth().weight(1f),
                    label = { Text("可编辑正文") },
                )
                Spacer(Modifier.height(4.dp))
            }
    }
    if (jump) {
        var number by remember { mutableStateOf("${index+1}") }
        AlertDialog(
            onDismissRequest = { jump = false },
            title = { Text("跳到正文块") },
            text = {
                OutlinedTextField(
                    number,
                    { number = it },
                    label = { Text("1–${draft?.chunks?.size ?: 1}") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        number
                            .toIntOrNull()
                            ?.takeIf { it in 1..(draft?.chunks?.size ?: 1) }
                            ?.let { index = it - 1 }
                        jump = false
                    }
                ) {
                    Text("跳转")
                }
            },
        )
    }
}
