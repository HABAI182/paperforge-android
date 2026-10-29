package cn.paperforge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val revision by UserSettings.revision.collectAsState()
    val snapshot = remember(revision) { UserSettings.prefs.all }
    var query by remember { mutableStateOf("") }
    var reset by remember { mutableStateOf(false) }
    var exportPresets by remember { mutableStateOf(false) }
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
            if (visible("桌面 系统 快捷 扫描 识字 阅读 小组件")) SystemEntrypointsSettings()
            if (visible("图像 内存 任务 后台 预算 大图")) {
                Text("图像内存预算", style = MaterialTheme.typography.titleMedium)
                val configured = UserSettings[SettingKeys.TaskMemoryMB]
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(64f, 128f, 192f, 256f, 384f).forEach { value ->
                        FilterChip(
                            selected = configured == value,
                            onClick = { UserSettings.set(SettingKeys.TaskMemoryMB, value) },
                            label = { Text("${value.toInt()} MB") },
                        )
                    }
                }
                Text(
                    "实际按设备内存调整。低预算可能降低超大页面的导出清晰度，原件保留；新任务和手动重试使用当前预算。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (visible("OCR 识字 识别 模型 语言包 Paddle"))
                OutlinedButton(onClick = vm::openOcrModels, modifier = Modifier.fillMaxWidth()) {
                    Text("离线 OCR 与模型包")
                }
            if (visible("档案 配置 预设 扫描 默认"))
                OutlinedButton(
                    onClick = { vm.openProfiles("settings") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("配置档案与扫描模式")
                }
            if (visible("导出 预设 目录 命名 DPI 质量"))
                OutlinedButton(
                    onClick = { exportPresets = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("导出预设与保存目录")
                }
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
                SettingToggle("相似页翻动后重新自动拍摄", "turnRearm", true)
                SettingSlider("翻页动作灵敏阈值", "turnThreshold", .06f, .025f..0.2f, "%", 100f)
                SettingSlider("手动快门倒计时", "captureTimer", 0f, 0f..10f, "秒")
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
            if (visible("导出 PDF OCR 文字 识别 DPI 竖排 多栏 排序 脚本 vertical order")) {
                Text("导出与识别", style = MaterialTheme.typography.titleLarge)
                OcrOptionsControl()
                if (UserSettings[SettingKeys.OcrBackend] == "PADDLE")
                    Text(
                        "识别语言按当前模型包决定，可在“离线 OCR 与模型包”中切换。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                else {
                    SettingToggle("结合多次识别结果校对", "ocrVoting", true)
                    SettingChoices(
                        "OCR 语言",
                        "ocrLanguage",
                        "CHINESE",
                        listOf("CHINESE" to "中文 + 英文", "LATIN" to "英文 / 拉丁字母"),
                    )
                }
                SettingToggle("扫描导出默认附带文字层", "searchable", false)
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
            if (
                visible(
                    "外观 主题 颜色 字体 布局 密度 列表 封面 网格 列数 动效 动画 触感 振动 宽屏 侧栏 平板 theme font motion haptic sidebar"
                )
            ) {
                Text("外观", style = MaterialTheme.typography.titleLarge)
                SettingChoices(
                    "主题",
                    "theme",
                    "SYSTEM",
                    listOf("SYSTEM" to "跟随系统", "LIGHT" to "浅色", "DARK" to "深色", "AMOLED" to "纯黑"),
                )
                SettingToggle("使用系统动态配色", "dynamicColor", false)
                SettingToggle("减少动效", "reduceMotion", false)
                SettingToggle("文档选择触感", "haptics", true)
                SettingToggle("宽屏文档侧栏", "documentSidebar", true)
                SettingChoices(
                    "主题色",
                    "accent",
                    "GREEN",
                    listOf("GREEN" to "墨绿", "BLUE" to "蓝色", "PURPLE" to "紫色", "ORANGE" to "橙色"),
                )
                SettingSlider("应用字体比例", "fontScale", 1f, 0.85f..1.3f, "%", 100f)
                Text("系统大字号优先；达到 200% 后，应用比例不再叠加放大。", style = MaterialTheme.typography.bodySmall)
                SettingToggle("紧凑文档卡片", "compactRows", false)
                SettingChoices(
                    "文档布局",
                    "documentLayout",
                    "LIST",
                    listOf("LIST" to "列表", "COVERS" to "封面"),
                )
                SettingChoices(
                    "封面网格列数",
                    "gridColumns",
                    "AUTO",
                    listOf("AUTO" to "自动", "2" to "2列", "3" to "3列", "4" to "4列"),
                )
                Text("窄屏和大字号时自动减少列数，便于阅读和点选。", style = MaterialTheme.typography.bodySmall)
                SettingSlider("首页最近文档数量", "recentCount", 6f, 4f..20f, "份")
            }
            if (visible("首页 筛选 收藏 收件箱 未识字 未导出")) {
                SettingChoices(
                    "首页默认集合",
                    "homeCollection",
                    "RECENT",
                    listOf(
                        "RECENT" to "最近",
                        "FAVORITES" to "收藏",
                        "INBOX" to "未整理",
                        "NO_OCR" to "未识字",
                        "NEEDS_EXPORT" to "待导出",
                    ),
                )
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
                                    SettingKeys.HomeTools,
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
                                UserSettings.set(SettingKeys.HomeTools, next.joinToString(","))
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
                                UserSettings.set(SettingKeys.HomeTools, next.joinToString(","))
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
    if (exportPresets)
        ScanExportDialog(vm, "导出示例", 1, { exportPresets = false }, settingsOnly = true)
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
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value, role = Role.Switch, onValueChange = { UserSettings.set(key, it) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f))
        Switch(value, null)
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
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}
