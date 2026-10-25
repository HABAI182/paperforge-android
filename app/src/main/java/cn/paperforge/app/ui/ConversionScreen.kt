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
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.pdf.ImageConversionOptions
import cn.paperforge.app.pdf.LongImageOptions

private data class Conversion(
    val title: String,
    val detail: String,
    val group: String,
    val mode: String,
)

private val conversions =
    listOf(
        Conversion("电脑助手结果导入", "接收 Office、表格和版面转换的结果包", "电脑引擎", "desktop-import"),
        Conversion(
            "Office → PDF · 保留排版",
            "Word、Excel、PowerPoint、ODF；手机本机排版引擎",
            "手机引擎",
            "office-pdf",
        ),
        Conversion(
            "Office 格式互转",
            "DOCX / ODT / RTF、XLSX / ODS / CSV、PPTX / ODP",
            "手机引擎",
            "office-options",
        ),
        Conversion("PDF → Word · 结构重建", "手机本地重建文字、表格与嵌入图片；扫描页离线 OCR", "文字", "docx-layout"),
        Conversion("PDF / 图片 → Excel 表格", "有线 / 无线表、合并单元格、离线 OCR，导出 XLSX", "手机表格", "tables-xlsx"),
        Conversion("PDF / 图片 → CSV 表格包", "每张表独立 CSV，附结构与来源信息", "手机表格", "tables-csv"),
        Conversion("PDF → PowerPoint · 整页外观", "手机本地生成，一页一张图片幻灯片", "文字", "pdf-pptx"),
        Conversion("文字 / DOCX → PDF", "TXT、Markdown、HTML、DOCX 正文，中文分页排版", "生成 PDF", "text-pdf"),
        Conversion("图片 → PDF", "多图排序、裁边、滤镜和纸张尺寸", "生成 PDF", "image-pdf"),
        Conversion("图片格式互转", "JPG、PNG、WebP；支持批量打包", "图片", "image-format"),
        Conversion("PDF → JPG", "整页高清导出，按页码输出图片包", "图片", "images-jpg"),
        Conversion("PDF → WebP", "逐页导出 WebP 图片包", "图片", "images-webp"),
        Conversion("图片拼成长图", "按选择顺序拼接，宽度与间距可调，过长自动分段", "图片", "images-long"),
        Conversion("PDF → 长图", "按页序拼接，超过长度自动连续分段", "图片", "long-image"),
        Conversion("PDF → PNG", "无损图片编码，适合文字与线条", "图片", "images-png"),
        Conversion("PDF → Word 段落", "提取文字为可编辑 DOCX，重新排版", "文字", "docx"),
        Conversion("PDF → 图文 Word", "每页参考图加可编辑文字段落", "文字", "docx-image"),
        Conversion("PDF → TXT", "导出已有文字层", "文字", "txt"),
        Conversion("PDF → Markdown", "按段落导出文本，不重建复杂版式", "文字", "md"),
        Conversion("PDF → HTML", "生成可在浏览器打开的文字文档", "文字", "html"),
        Conversion("扫描 PDF → 可搜索 PDF", "离线 OCR，保留原页并添加透明文字层", "生成 PDF", "searchable"),
        Conversion("提取 PDF 内图片", "提取嵌入图像，输出 PNG 图片包", "图片", "extract-images"),
        Conversion("PDF 每页独立拆分", "一页一份 PDF，打包保存", "整理", "split-each"),
        Conversion("合并 PDF", "选择多份 PDF，调整顺序后合并", "整理", "merge"),
        Conversion("压缩 PDF", "优化嵌入图片并保留文字和链接", "整理", "compress"),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversionScreen(
    vm: MainViewModel,
    onPdf: (String) -> Unit,
    onImages: () -> Unit,
    onMerge: () -> Unit,
    onText: (Float) -> Unit,
    onImageFormat: (ImageConversionOptions) -> Unit,
    onLongImages: (LongImageOptions) -> Unit,
    onDesktop: (String, org.json.JSONObject) -> Unit,
    onResult: () -> Unit,
) {
    val busy by vm.busy.collectAsState()
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
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("格式转换", style = MaterialTheme.typography.titleLarge)
                Text(
                    "选择转换方式，再选择文件。全程本机处理。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (drafts.isNotEmpty())
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("继续编辑正文", style = MaterialTheme.typography.titleMedium)
                            drafts.take(3).forEach { row ->
                                TextButton(
                                    onClick = { vm.loadTextConversion(row) },
                                    enabled = !busy,
                                ) {
                                    Text(
                                        org.json
                                            .JSONObject(row.payload)
                                            .getJSONObject("input")
                                            .optString("name", row.title)
                                    )
                                }
                            }
                            if (drafts.size > 3)
                                TextButton(
                                    onClick = {
                                        vm.taskFilter.value = "ATTENTION"
                                        vm.screen.value = "tasks"
                                    }
                                ) {
                                    Text("查看其他待处理任务")
                                }
                        }
                    }
                }
            item {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("搜索格式或功能") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("全部", "手机引擎", "手机表格", "生成 PDF", "图片", "文字", "整理", "电脑引擎").forEach { name
                        ->
                        FilterChip(group == name, { group = name }, label = { Text(name) })
                    }
                }
            }
            val matches =
                conversions.filter {
                    ((group == "全部" && it.group != "电脑引擎") || it.group == group) &&
                        (it.title.contains(query, true) ||
                            it.detail.contains(query, true) ||
                            it.mode.contains(query, true))
                }
            if (matches.isEmpty()) item { Text("没有匹配的转换方式", Modifier.padding(24.dp)) }
            items(matches, key = { it.mode }) { entry ->
                OutlinedCard(
                    onClick = {
                        when (entry.mode) {
                            "text-pdf",
                            "image-format",
                            "office-options",
                            "images-long" -> options = entry.mode
                            "desktop-import" -> onResult()
                            "image-pdf" -> onImages()
                            "merge" -> onMerge()
                            else ->
                                if (entry.group in setOf("电脑引擎", "手机引擎", "手机表格"))
                                    desktopJob = entry.mode
                                else onPdf(entry.mode)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { Text(entry.detail) },
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
                    "DOCX 导入正文与表格文字；HTML 提取文字；Markdown 保留源码。快速文字入口会重新排版；Office 排版入口使用手机引擎保留页面内容与布局。",
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
                        else -> "图片格式互转"
                    }
                )
            },
            text = {
                Column(
                    Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (options == "office-options") {
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
                        Text("按选择顺序拼接，过长自动连续分段。")
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
                        if (mode == "office-options") desktopJob = "office-$officeTarget"
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
