package cn.paperforge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.UserSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val revision by UserSettings.revision.collectAsState()
    val snapshot = remember(revision) { UserSettings.prefs.all }
    var query by remember { mutableStateOf("") }
    var reset by remember { mutableStateOf(false) }
    val importSettings =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(vm::importPreferences)
        }
    fun visible(label: String) = query.isBlank() || label.contains(query, true)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个性化设置") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "home" }) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                query,
                { query = it },
                label = { Text("查找设置") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("修改自动保存，拍摄参数在下一次进入相机时生效。", style = MaterialTheme.typography.bodySmall)
            if (visible("任务 后台 进度 失败 重试"))
                OutlinedButton(
                    onClick = { vm.screen.value = "tasks" },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("任务中心")
                }
            if (visible("存储 空间 缓存 原件 历史 清理"))
                OutlinedButton(
                    onClick = { vm.screen.value = "storage" },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("存储空间与缓存")
                }
            if (visible("扫描 取景 相机 自动 裁边 纸张 定位 AI 照片 纸框 显示 模糊 亮度 稳定 等待 分析 间隔 JPEG 画质")) {
                Text("扫描与取景", style = MaterialTheme.typography.titleLarge)
                SettingChoices(
                    "纸张定位",
                    "detector",
                    "NEURAL",
                    listOf("HYBRID" to "自动识边", "STANDARD" to "传统识边"),
                )
                SettingSlider("纸框平滑程度", "outlineWeight", 3f, 1f..8f, "")
                SettingSlider("切换纸框确认帧数", "outlineSwitchFrames", 2f, 1f..6f, "帧")
                SettingToggle("默认自动拍摄", "autoShutter", true)
                SettingToggle("拍一页即进入编辑", "reviewAfterCapture", false)
                SettingToggle("照片沿用取景纸框", "lockCrop", true)
                SettingToggle("显示纸边框", "showOutline", true)
                SettingToggle("自动拍摄检查模糊与亮度", "strictQuality", false)
                SettingSlider("自动拍摄最小纸张比例", "captureArea", 0.08f, 0.05f..0.4f, "%", 100f)
                SettingSlider("自动拍摄识边阈值", "captureConfidence", .60f, 0.45f..0.95f, "%", 100f)
                SettingSlider("稳定后拍摄等待", "stableMs", 600f, 300f..2000f, "ms")
                SettingSlider("两次自动拍摄最短间隔", "cooldownMs", 1500f, 800f..5000f, "ms")
                SettingSlider("取景分析间隔", "analysisMs", 160f, 100f..500f, "ms")
                SettingSlider("拍照 JPEG 画质", "cameraJpeg", 97f, 75f..100f, "")
            }
            if (visible("增强 图片 滤镜 扫描")) {
                Text("默认增强", style = MaterialTheme.typography.titleLarge)
                SettingChoices(
                    "导入与拍摄滤镜",
                    "filter",
                    "AUTO",
                    listOf(
                        "ORIGINAL" to "原图",
                        "AUTO" to "自动",
                        "TEXT" to "清晰文字",
                        "CLEAN" to "去阴影",
                        "GRAY" to "灰度",
                        "BW" to "黑白",
                        "COLOR" to "彩色",
                        "WHITEBOARD" to "白板",
                    ),
                )
                SettingSlider("去阴影强度", "enhanceStrength", 1f, 0f..1f, "", 100f)
                SettingSlider("默认亮度", "brightness", 0f, -0.5f..0.5f, "", 100f)
                SettingSlider("默认对比度", "contrast", 1f, 0.5f..1.8f, "", 100f)
                SettingSlider("默认曲面校正强度", "dewarpStrength", 1f, 0f..1f, "%", 100f)
            }
            if (visible("导出 PDF OCR 文字 识别 DPI")) {
                Text("导出与识别", style = MaterialTheme.typography.titleLarge)
                SettingChoices(
                    "OCR 语言",
                    "ocrLanguage",
                    "CHINESE",
                    listOf("CHINESE" to "中文 + 英文", "LATIN" to "英文 / 拉丁字母"),
                )
                SettingToggle("扫描导出默认附带文字层", "searchable", true)
                SettingChoices(
                    "默认纸张",
                    "paper",
                    "Auto",
                    listOf("Auto" to "原比例", "A4" to "A4", "A5" to "A5", "Letter" to "Letter"),
                )
                SettingChoices(
                    "默认分辨率",
                    "resolution",
                    "STANDARD",
                    listOf("DRAFT" to "草稿", "STANDARD" to "标准", "HIGH" to "高清", "CUSTOM" to "自定义"),
                )
                SettingSlider("自定义分辨率", "customDpi", 300f, 72f..600f, "DPI")
                SettingSlider("PDF 图片画质", "quality", 90f, 40f..100f, "")
            }
            if (visible("外观 主题 颜色 字体")) {
                Text("外观", style = MaterialTheme.typography.titleLarge)
                SettingChoices(
                    "主题",
                    "theme",
                    "SYSTEM",
                    listOf("SYSTEM" to "跟随系统", "LIGHT" to "浅色", "DARK" to "深色", "AMOLED" to "纯黑"),
                )
                SettingToggle("使用系统动态配色", "dynamicColor", false)
                SettingChoices(
                    "主题色",
                    "accent",
                    "GREEN",
                    listOf("GREEN" to "墨绿", "BLUE" to "蓝色", "PURPLE" to "紫色", "ORANGE" to "橙色"),
                )
                SettingSlider("应用字体比例", "fontScale", 1f, 0.85f..1.3f, "%", 100f)
                SettingToggle("紧凑文档列表", "compactRows", false)
                SettingSlider("首页最近文档数量", "recentCount", 6f, 4f..20f, "份")
            }
            if (visible("首页 快捷 工具 入口 排序")) {
                Text("首页快捷入口", style = MaterialTheme.typography.titleLarge)
                Text("用上下按钮调整顺序；关闭后仍可从工具页进入。", style = MaterialTheme.typography.bodySmall)
                val names =
                    linkedMapOf(
                        "images" to "相册导入",
                        "pdf" to "打开 PDF",
                        "ocr" to "图片识字",
                        "merge" to "合并 PDF",
                        "compress" to "压缩 PDF",
                        "tools" to "全部工具",
                    )
                val order =
                    ((snapshot["homeTools"] as? String) ?: names.keys.joinToString(","))
                        .split(',')
                        .filter { it in names }
                        .distinct()
                (order + names.keys.filter { it !in order }).forEach { key ->
                    val index = order.indexOf(key)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            index >= 0,
                            { checked ->
                                UserSettings.set(
                                    "homeTools",
                                    (if (checked) order + key else order - key).joinToString(","),
                                )
                            },
                            modifier = Modifier.semantics { contentDescription = "显示${names[key]}" },
                        )
                        Text(names.getValue(key), Modifier.weight(1f))
                        IconButton(
                            enabled = index > 0,
                            onClick = {
                                val next = order.toMutableList()
                                next[index] = next[index - 1]
                                next[index - 1] = key
                                UserSettings.set("homeTools", next.joinToString(","))
                            },
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, "上移 ${names[key]}")
                        }
                        IconButton(
                            enabled = index >= 0 && index < order.lastIndex,
                            onClick = {
                                val next = order.toMutableList()
                                next[index] = next[index + 1]
                                next[index + 1] = key
                                UserSettings.set("homeTools", next.joinToString(","))
                            },
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, "下移 ${names[key]}")
                        }
                    }
                }
            }
            if (visible("隐私 安全 应用锁 截图")) {
                Text("隐私", style = MaterialTheme.typography.titleLarge)
                SecuritySettings()
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::exportPreferences, modifier = Modifier.weight(1f)) {
                    Text("导出设置")
                }
                OutlinedButton(
                    onClick = {
                        importSettings.launch(
                            arrayOf("application/json", "application/octet-stream")
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("导入并应用")
                }
            }
            OutlinedButton(onClick = { reset = true }, modifier = Modifier.fillMaxWidth()) {
                Text("恢复个性化默认设置")
            }
        }
    }
    if (reset)
        AlertDialog(
            onDismissRequest = { reset = false },
            title = { Text("恢复默认设置？") },
            text = { Text("重置扫描、导出、外观和快捷入口。文档、原图和隐私开关保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        UserSettings.reset()
                        reset = false
                    }
                ) {
                    Text("恢复默认")
                }
            },
            dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } },
        )
}

@Composable
private fun SettingToggle(title: String, key: String, default: Boolean) {
    val revision by UserSettings.revision.collectAsState()
    val value = remember(revision) { UserSettings.bool(key, default) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(value, { UserSettings.set(key, it) })
    }
}

@Composable
private fun SettingChoices(
    title: String,
    key: String,
    default: String,
    options: List<Pair<String, String>>,
) {
    val revision by UserSettings.revision.collectAsState()
    val current =
        remember(revision) {
            UserSettings.string(key, default).let {
                if (key == "detector" && it == "NEURAL") "HYBRID" else it
            }
        }
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (value, label) ->
                FilterChip(
                    current == value,
                    { UserSettings.set(key, value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun SettingSlider(
    title: String,
    key: String,
    default: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    multiplier: Float = 1f,
) {
    val revision by UserSettings.revision.collectAsState()
    var pending by
        remember(key, revision) { mutableFloatStateOf(UserSettings.number(key, default)) }
    Column {
        Text(
            "$title · ${(pending*multiplier).toInt()} $unit",
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            pending.coerceIn(range),
            { pending = it },
            onValueChangeFinished = { UserSettings.set(key, pending) },
            valueRange = range,
        )
    }
}
