package cn.paperforge.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.UserSettings
import cn.paperforge.app.model.ToolCapability
import cn.paperforge.app.model.ToolCatalog
import cn.paperforge.app.pdf.ImageConversionOptions
import cn.paperforge.app.pdf.LongImageOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversionScreen(
    vm: MainViewModel,
    onPdf: (String) -> Unit,
    onImages: () -> Unit,
    onMerge: () -> Unit,
    onText: (Float) -> Unit,
    onMarkup: (String, org.json.JSONObject) -> Unit,
    onImageFormat: (ImageConversionOptions) -> Unit,
    onLongImages: (LongImageOptions) -> Unit,
    onDesktop: (String, org.json.JSONObject) -> Unit,
    onResult: () -> Unit,
) {
    val busy by vm.busy.collectAsState()
    val settingsVersion by UserSettings.revision.collectAsState()
    val pinned = remember(settingsVersion) { UserSettings.pinnedTools() }
    val requested by vm.conversionEntry.collectAsState()
    var capability by remember { mutableStateOf<ToolCapability?>(null) }
    val tasks by vm.localTasks.collectAsState()
    val drafts =
        tasks.filter {
            it.type == "CONVERT" &&
                org.json.JSONObject(it.payload).optJSONObject("input")?.optBoolean("draft") == true
        }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var help by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("全部") }
    var options by remember { mutableStateOf("") }
    var longOptions by remember { mutableStateOf(LongImageOptions()) }
    var longValid by remember { mutableStateOf(true) }
    var format by rememberSaveable { mutableStateOf("jpg") }
    var imageQuality by rememberSaveable { mutableIntStateOf(92) }
    var imageEdge by rememberSaveable { mutableStateOf("4096") }
    var keepAlpha by rememberSaveable { mutableStateOf(true) }
    var backgroundHex by rememberSaveable { mutableStateOf("FFFFFF") }
    var lossless by rememberSaveable { mutableStateOf(false) }
    var desktopJob by remember { mutableStateOf("") }
    var desktopOcr by rememberSaveable { mutableStateOf(true) }
    var joinTables by rememberSaveable { mutableStateOf(false) }
    var tableStrategy by rememberSaveable { mutableStateOf("auto") }
    var officeTarget by rememberSaveable { mutableStateOf("docx") }
    var fontSize by rememberSaveable { mutableFloatStateOf(12f) }
    var bookTitle by rememberSaveable { mutableStateOf("") }
    var bookAuthor by rememberSaveable { mutableStateOf("") }
    var bookLanguage by rememberSaveable { mutableStateOf("zh") }
    var bookCover by rememberSaveable { mutableStateOf(true) }
    var bookColumns by rememberSaveable { mutableIntStateOf(0) }
    fun launch(entry: ToolCapability) {
        when (entry.mode) {
            "pdf-diff" -> vm.screen.value = "pdf-diff"
            "md-html",
            "epub-html" -> onMarkup("HTML", org.json.JSONObject())
            "html-md",
            "epub-md" -> onMarkup("MD", org.json.JSONObject())
            "epub-txt" -> onMarkup("TXT", org.json.JSONObject())
            "epub-create" -> options = entry.mode
            "text-pdf",
            "image-format",
            "office-options",
            "images-long" -> options = entry.mode
            "desktop-import" -> onResult()
            "image-pdf" -> onImages()
            "merge" -> onMerge()
            else ->
                if (entry.group in setOf("电脑引擎", "手机引擎", "手机表格")) desktopJob = entry.mode
                else onPdf(entry.mode)
        }
    }
    LaunchedEffect(requested) {
        if (requested.isNotEmpty()) {
            vm.conversionEntry.value = ""
            ToolCatalog.conversion(requested)?.let { launch(it) }
        }
    }
    capability?.let { ToolCapabilityDialog(it) { capability = null } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("转换中心") },
                actions = {
                    IconButton(onClick = { help = true }) {
                        Icon(Icons.Default.HelpOutline, "转换帮助")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "home" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("conversion-list"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("搜索格式或功能") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth().testTag("conversion-search"),
                    trailingIcon = {
                        if (query.isNotEmpty())
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, "清空搜索")
                            }
                    },
                )
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("全部", "常用", "手机引擎", "手机表格", "电子书", "生成 PDF", "图片", "文字", "整理", "电脑引擎")
                        .forEach { name ->
                            FilterChip(group == name, { group = name }, label = { Text(name) })
                        }
                }
            }
            if (drafts.isNotEmpty() && query.isBlank())
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${drafts.size} 份转换草稿",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                TextButton(
                                    onClick = {
                                        vm.taskFilter.value = "ATTENTION"
                                        vm.screen.value = "tasks"
                                    }
                                ) {
                                    Text("全部待办")
                                }
                            }
                            TextButton(onClick = { vm.openTask(drafts.first()) }, enabled = !busy) {
                                Text(
                                    org.json
                                        .JSONObject(drafts.first().payload)
                                        .getJSONObject("input")
                                        .optString("name", drafts.first().title),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            val matches =
                ToolCatalog.conversions.filter {
                    ((group == "全部" && it.group != "电脑引擎") ||
                        it.group == group ||
                        (group == "常用" && it.id in pinned)) && it.matches(query)
                }
            if (matches.isEmpty())
                item {
                    Text(
                        if (group == "常用") "点击工具旁的星标，固定常用转换。" else "没有匹配的转换方式",
                        Modifier.padding(24.dp),
                    )
                }
            items(matches, key = { it.mode }) { entry ->
                OutlinedCard(
                    onClick = { launch(entry) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { Text(entry.detail) },
                        trailingContent = {
                            Column {
                                ToolPinButton(entry.id, entry.title, entry.id in pinned)
                                IconButton(onClick = { capability = entry }) {
                                    Icon(Icons.Default.Info, "${entry.title}的能力说明")
                                }
                            }
                        },
                        leadingContent = {
                            Icon(
                                if (entry.group == "图片") Icons.Default.Image
                                else Icons.Default.SwapHoriz,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
            }
            item {
                Text(
                    "快速正文入口会重新排版文字；Markdown / HTML 使用独立结构转换。Office 排版入口使用手机引擎。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (options.isNotEmpty())
        AlertDialog(
            onDismissRequest = { options = "" },
            title = {
                Text(
                    when (options) {
                        "text-pdf" -> "生成文字 PDF"
                        "office-options" -> "Office 目标格式"
                        "images-long" -> "图片拼成长图"
                        "epub-create" -> "生成 EPUB 电子书"
                        else -> "图片格式互转"
                    }
                )
            },
            text = {
                Column(
                    Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (options == "epub-create") {
                        OutlinedTextField(
                            bookTitle,
                            { bookTitle = it },
                            label = { Text("书名（留空沿用文档标题）") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            bookAuthor,
                            { bookAuthor = it },
                            label = { Text("作者（可留空）") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            bookLanguage,
                            { bookLanguage = it },
                            label = { Text("语言，例如 zh / en") },
                            singleLine = true,
                        )
                        Row {
                            Checkbox(bookCover, { bookCover = it })
                            Text("首张图片用作封面")
                        }
                        Text("PDF 重排顺序；文字可编辑，复杂表格/公式保留原页对照：")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("单栏", "双栏左起", "双栏右起").forEachIndexed { i, label ->
                                FilterChip(
                                    bookColumns == i,
                                    { bookColumns = i },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Text(
                            "MD / HTML 按主标题分章；资源可打成 ZIP 后导入。扫描 PDF 使用离线 OCR。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (options == "office-options") {
                        Text("手机本机转换。首次需要安装 Office 手机引擎。请选择与源文件对应的目标类型。")
                        if (officeTarget == "csv")
                            Text(
                                "CSV 仅导出当前/首个工作表的值，不保留合并单元格与样式；多表和长编号优先用 XLSX。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        listOf(
                                listOf("docx", "odt", "rtf"),
                                listOf("xlsx", "ods", "csv"),
                                listOf("pptx", "odp"),
                            )
                            .forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { target ->
                                        FilterChip(
                                            officeTarget == target,
                                            { officeTarget = target },
                                            label = { Text(target.uppercase()) },
                                        )
                                    }
                                }
                            }
                    } else if (options == "text-pdf") {
                        Text("A4 纸张 · 自动换行与分页 · 嵌入中文字体")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(10f, 12f, 16f).forEach { size ->
                                FilterChip(
                                    fontSize == size,
                                    { fontSize = size },
                                    label = { Text("${size.toInt()} pt") },
                                )
                            }
                        }
                    } else if (options == "images-long") {
                        Text("选择图片后可调整顺序、自动识别重叠并手动调整接缝。")
                        LongImageControls(longOptions, { longOptions = it }, { longValid = it })
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("jpg", "png", "webp").forEach { name ->
                                FilterChip(
                                    format == name,
                                    { format = name },
                                    label = { Text(name.uppercase()) },
                                )
                            }
                        }
                        Text("多张图片输出 ZIP，并保留逐项结果；失败后只需重试未完成的图片。")
                        OutlinedTextField(
                            imageEdge,
                            { imageEdge = it },
                            label = { Text("长边上限 · 128–8192 像素") },
                            singleLine = true,
                            isError = imageEdge.toIntOrNull()?.let { it !in 128..8192 } ?: true,
                        )
                        if (format != "png" && !(format == "webp" && lossless)) {
                            Text("编码质量 $imageQuality")
                            Slider(
                                imageQuality.toFloat(),
                                { imageQuality = it.toInt() },
                                valueRange = 1f..100f,
                            )
                        }
                        if (format != "jpg")
                            Row {
                                Checkbox(keepAlpha, { keepAlpha = it })
                                Text("保留透明背景")
                            }
                        if (format == "jpg" || !keepAlpha)
                            OutlinedTextField(
                                backgroundHex,
                                { backgroundHex = it },
                                label = { Text("背景颜色 · RGB 或 ARGB 十六进制") },
                                singleLine = true,
                            )
                        if (format == "webp" && android.os.Build.VERSION.SDK_INT >= 30)
                            Row {
                                Checkbox(lossless, { lossless = it })
                                Text("无损 WebP")
                            }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled =
                        options in setOf("text-pdf", "office-options") ||
                            options == "images-long" && longValid ||
                            (options != "images-long" &&
                                imageEdge.toIntOrNull()?.let { it in 128..8192 } == true &&
                                (format != "jpg" && keepAlpha ||
                                    runCatching {
                                            android.graphics.Color.parseColor("#$backgroundHex")
                                        }
                                        .isSuccess)),
                    onClick = {
                        val mode = options
                        options = ""
                        if (mode == "epub-create")
                            onMarkup(
                                "EPUB",
                                org.json
                                    .JSONObject()
                                    .put("title", bookTitle)
                                    .put("author", bookAuthor)
                                    .put("language", bookLanguage.ifBlank { "und" })
                                    .put("cover", bookCover)
                                    .put("columns", bookColumns),
                            )
                        else if (mode == "office-options") desktopJob = "office-$officeTarget"
                        else if (mode == "text-pdf") onText(fontSize)
                        else if (mode == "images-long") onLongImages(longOptions)
                        else
                            onImageFormat(
                                ImageConversionOptions(
                                    format,
                                    imageQuality,
                                    imageEdge.toIntOrNull() ?: 4096,
                                    keepAlpha,
                                    runCatching {
                                            android.graphics.Color.parseColor("#$backgroundHex")
                                        }
                                        .getOrDefault(android.graphics.Color.WHITE),
                                    lossless,
                                )
                            )
                    },
                ) {
                    Text("选择文件")
                }
            },
            dismissButton = { TextButton(onClick = { options = "" }) { Text("取消") } },
        )
    if (desktopJob.isNotEmpty())
        AlertDialog(
            onDismissRequest = { desktopJob = "" },
            title = {
                Text(
                    if (desktopJob.startsWith("office-")) "手机 Office 排版"
                    else if (desktopJob.startsWith("tables-")) "手机表格识别" else "可选电脑扩展"
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (desktopJob.startsWith("office-")) {
                        Text("在手机本机使用 LibreOfficeKit 排版。首次安装手机引擎后，无需电脑或网络。")
                        TextButton(
                            onClick = {
                                uriHandler.openUri(
                                    "https://github.com/HABAI182/paperforge-android/releases"
                                )
                            }
                        ) {
                            Text("安装 / 更新手机 Office 引擎")
                        }
                    } else if (desktopJob.startsWith("tables-"))
                        Text("手机本机提取表格，完成后可逐格校正并导出 Excel 或 CSV。")
                    else Text("可选电脑扩展，通过任务 ZIP 交接；手机基础功能可以独立使用。")
                    if (desktopJob.startsWith("tables") || desktopJob == "pdf-docx") {
                        Row {
                            Checkbox(desktopOcr, { desktopOcr = it })
                            Text("识别扫描页中的文字")
                        }
                    }
                    if (desktopJob.startsWith("tables")) {
                        Text("表格结构")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("auto" to "自动", "lines_strict" to "有线表", "text" to "无线表")
                                .forEach { (key, title) ->
                                    FilterChip(
                                        tableStrategy == key,
                                        { tableStrategy = key },
                                        label = { Text(title) },
                                    )
                                }
                        }
                        Row {
                            Checkbox(joinTables, { joinTables = it })
                            Text("连接相邻页的同表头表格")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val mode = desktopJob
                        desktopJob = ""
                        onDesktop(
                            mode,
                            org.json
                                .JSONObject()
                                .put("ocr", desktopOcr)
                                .put("tableStrategy", tableStrategy)
                                .put("joinTables", joinTables),
                        )
                    }
                ) {
                    Text(
                        if (desktopJob.startsWith("office-") || desktopJob.startsWith("tables-"))
                            "选择文件"
                        else "选择文件并打包"
                    )
                }
            },
            dismissButton = { TextButton(onClick = { desktopJob = "" }) { Text("取消") } },
        )

    if (help)
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text("手机本地转换") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("手机转换：选格式、选文件，完成后预览、分享或另存。")
                    Text("Office：先安装一次手机 Office 引擎，再回到转换中心选择文件。表格识别直接在纸匠内部完成，可预览校正。")
                    Text("Office 排版使用 LibreOffice；结构 Word 和表格会重建可编辑内容。实际字体和复杂版式可能影响结果，原件始终保留。")
                    TextButton(
                        onClick = {
                            uriHandler.openUri(
                                "https://github.com/HABAI182/paperforge-android/releases"
                            )
                        }
                    ) {
                        Text("下载手机引擎与查看更新")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } },
        )
}
