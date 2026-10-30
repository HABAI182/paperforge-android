package cn.paperforge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.ExportDestinations
import cn.paperforge.app.data.SettingsProfiles
import cn.paperforge.app.model.ScanExportOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ScanExportDialog(
    vm: MainViewModel,
    title: String,
    pages: Int,
    onDismiss: () -> Unit,
    settingsOnly: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles = remember { SettingsProfiles(context) }
    val destinations = remember { ExportDestinations(context) }
    val optionsSaver = remember {
        Saver<ScanExportOptions, String>(
            save = { it.values().toString() },
            restore = { ScanExportOptions.decode(org.json.JSONObject(it)) },
        )
    }
    var options by
        rememberSaveable(stateSaver = optionsSaver) { mutableStateOf(ScanExportOptions.current()) }
    var dpiText by rememberSaveable(options.dpi) { mutableStateOf(options.dpi.toString()) }
    val dpiValid = dpiText.toIntOrNull()?.let { it in 72..600 } == true
    var presetName by rememberSaveable { mutableStateOf("") }
    var presetMenu by remember { mutableStateOf(false) }
    var selectedPreset by rememberSaveable { mutableStateOf("当前设置") }
    var presets by remember { mutableStateOf(profiles.saved().filter { it.values.has("paper") }) }
    var notice by remember { mutableStateOf("") }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null)
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { destinations.remember(uri) } }
                        .onSuccess {
                            options = options.copy(destination = it.id)
                            notice = ""
                        }
                        .onFailure { notice = it.message ?: "无法授权此目录，请重新选择" }
                }
        }
    SpaciousToolDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (settingsOnly) "导出预设与目录" else "导出 ${pages} 页") },
        text = {
            Column(
                Modifier.heightIn(max = 490.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box {
                    OutlinedButton(
                        onClick = { presetMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("预设 · $selectedPreset")
                    }
                    DropdownMenu(presetMenu, { presetMenu = false }) {
                        listOf(
                                "日常 PDF" to ScanExportOptions(),
                                "高清存档 PDF" to ScanExportOptions(resolution = "HIGH", quality = 97),
                                "轻量分享 PDF" to
                                    ScanExportOptions(
                                        resolution = "DRAFT",
                                        quality = 72,
                                        searchable = false,
                                    ),
                                "无损页面 PNG" to
                                    ScanExportOptions(
                                        resolution = "HIGH",
                                        searchable = false,
                                        format = "PNG",
                                    ),
                            )
                            .forEach { (name, value) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        options = value.copy(destination = options.destination)
                                        selectedPreset = name
                                        presetMenu = false
                                    },
                                )
                            }
                        presets.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.name) },
                                onClick = {
                                    options = ScanExportOptions.decode(preset.values)
                                    selectedPreset = preset.name
                                    presetMenu = false
                                },
                                trailingIcon = {
                                    TextButton(
                                        onClick = {
                                            profiles.remove(preset)
                                            presets =
                                                profiles.saved().filter { it.values.has("paper") }
                                        }
                                    ) {
                                        Text("删除")
                                    }
                                },
                            )
                        }
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("PDF", "JPG", "PNG").forEach { value ->
                        FilterChip(
                            options.format == value,
                            { options = options.copy(format = value) },
                            label = { Text(if (value == "PDF") value else "$value 图片包") },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        options.searchable && options.format == "PDF",
                        { options = options.copy(searchable = it) },
                        enabled = options.format == "PDF",
                    )
                    Text(if (options.format == "PDF") "离线 OCR · 可搜索文字层" else "图片包不含可搜索文字层")
                }
                Text("纸张", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                            "Auto" to "匹配原图",
                            "A4" to "A4",
                            "A5" to "A5",
                            "Letter" to "Letter",
                            "ID" to "证件双面合版",
                        )
                        .forEach { (value, name) ->
                            FilterChip(
                                options.paper == value,
                                { options = options.copy(paper = value) },
                                label = { Text(name) },
                            )
                        }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                            "DRAFT" to "150 dpi",
                            "STANDARD" to "220 dpi",
                            "HIGH" to "300 dpi",
                            "CUSTOM" to "自定 DPI",
                        )
                        .forEach { (value, name) ->
                            FilterChip(
                                options.resolution == value,
                                { options = options.copy(resolution = value) },
                                label = { Text(name) },
                            )
                        }
                }
                if (options.resolution == "CUSTOM")
                    OutlinedTextField(
                        dpiText,
                        {
                            dpiText = it
                            it.toIntOrNull()
                                ?.takeIf { value -> value in 72..600 }
                                ?.let { dpi -> options = options.copy(dpi = dpi) }
                        },
                        label = { Text("DPI 72–600") },
                        isError = !dpiValid,
                        singleLine = true,
                    )
                if (options.format != "PNG") {
                    Text("图像质量 ${options.quality}")
                    Slider(
                        options.quality.toFloat(),
                        { options = options.copy(quality = it.toInt()) },
                        valueRange = 40f..100f,
                    )
                }
                OutlinedTextField(
                    options.naming,
                    { options = options.copy(naming = it) },
                    label = { Text("文件名模板") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    "{title} 标题 · {date} 日期 · {time} 时间 · {pages} 扫描页数",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "预览：${options.fileName(title, pages)}.${if(options.format == "PDF") "pdf" else "zip"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                val destination = destinations.get(options.destination)
                Text(
                    if (options.destination.isBlank()) "生成后选择保存位置"
                    else if (destination == null || !destinations.available(destination))
                        "保存目录需要重新授权"
                    else "保存目录：${destination.name}"
                )
                Row {
                    TextButton(onClick = { pick.launch(destination?.uri) }) { Text("选择保存目录") }
                    if (options.destination.isNotBlank())
                        TextButton(onClick = { options = options.copy(destination = "") }) {
                            Text("清除")
                        }
                }
                Text("成品先保存在应用内，再点保存到目录；目录内同名文件自动编号。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    presetName,
                    { presetName = it },
                    label = { Text("新预设名称") },
                    singleLine = true,
                )
                TextButton(
                    enabled =
                        presetName.isNotBlank() && (options.resolution != "CUSTOM" || dpiValid),
                    onClick = {
                        options.remember()
                        val row = profiles.save(presetName, setOf("export"))
                        selectedPreset = row.name
                        presets = profiles.saved().filter { it.values.has("paper") }
                        presetName = ""
                        notice = "预设已保存"
                    },
                ) {
                    Text("将当前参数保存为预设")
                }
                if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
                Text(
                    "DPI 决定页面输出尺寸，清晰度仍受源照片限制。PNG 使用无损图像编码。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = options.resolution != "CUSTOM" || dpiValid,
                onClick = {
                    options.remember()
                    onDismiss()
                    if (!settingsOnly) vm.exportWithOptions(title, pages, options)
                },
            ) {
                Text(
                    if (settingsOnly) "应用并记住"
                    else "生成 ${if(options.format == "PDF") "PDF" else options.format + " 图片包"}"
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
