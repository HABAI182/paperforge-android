package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.UserSettings
import cn.paperforge.app.model.ToolCatalog
import org.json.JSONObject

@Composable
internal fun PdfToolsDialog(
    vm: MainViewModel,
    initialMode: String = "",
    readingActions: List<HomeTool> = emptyList(),
    currentPage: Int = 0,
    onDismiss: () -> Unit,
) {
    var longOptions by remember { mutableStateOf(cn.paperforge.app.pdf.LongImageOptions()) }
    var longValid by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(initialMode) }
    var capability by remember { mutableStateOf<cn.paperforge.app.model.ToolCapability?>(null) }
    capability?.let { ToolCapabilityDialog(it) { capability = null } }
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var range by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("5") }
    var printAllowed by remember { mutableStateOf(true) }
    var copyAllowed by remember { mutableStateOf(true) }
    var preferEditable by remember { mutableStateOf(false) }
    var layoutMode by remember { mutableStateOf("editable") }
    if (mode in listOf("redact", "native-edit", "certificates", "forms", "archive")) {
        LaunchedEffect(Unit) {
            vm.queueTool(mode)
            onDismiss()
        }
        return
    }
    if (mode == "compress") {
        val doc by vm.selected.collectAsState()
        PdfCompressionDialog(vm, doc?.pdfPath?.let({ java.io.File(it) }), onDismiss)
        return
    }
    if (mode == "enhance-images") {
        val doc by vm.selected.collectAsState()
        PdfEnhancementDialog(vm, doc?.pdfPath?.let { java.io.File(it) }, currentPage, onDismiss)
        return
    }
    val settingsVersion by UserSettings.revision.collectAsState()
    val pinned = remember(settingsVersion) { UserSettings.pinnedTools() }
    val titles = ToolCatalog.pdfTitles
    SpaciousToolDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode.isEmpty()) "PDF 工具箱" else titles[mode] ?: "PDF 工具") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (mode.isEmpty()) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text("搜索工具，如水印、Word、页码") },
                        singleLine = true,
                    )
                    if (query.isBlank() && group.isBlank()) {
                        Text("常用", style = MaterialTheme.typography.titleMedium)
                        titles
                            .filterKeys { "tool:$it" in pinned }
                            .entries
                            .chunked(2)
                            .forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { (key, title) ->
                                        FilledTonalButton(
                                            onClick = { mode = key },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(
                                                mapOf(
                                                    "compress" to "压缩 PDF",
                                                    "searchable" to "识别文字",
                                                    "images-png" to "转为图片",
                                                    "docx-layout" to "转为 Word",
                                                )[key] ?: title,
                                                maxLines = 2,
                                            )
                                        }
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        if (titles.keys.none { "tool:$it" in pinned })
                            Text("从工具菜单固定常用功能。", style = MaterialTheme.typography.bodySmall)
                        Text("按用途选择", style = MaterialTheme.typography.titleMedium)
                        val choices =
                            (if (readingActions.isNotEmpty())
                                listOf(
                                    WorkspaceSection(
                                        "reading",
                                        "阅读与导航",
                                        "目录、书签、朗读与翻译",
                                        androidx.compose.material.icons.Icons.Default.AutoStories,
                                    )
                                )
                            else emptyList()) + pdfFamilies
                        SectionCardGrid(choices) { group = it }
                    } else {
                        TextButton(
                            onClick = {
                                group = ""
                                query = ""
                            }
                        ) {
                            Text("返回工具分类")
                        }
                        if (group == "reading" && query.isBlank()) {
                            readingActions
                                .filterNot { it.id in setOf("read:range", "read:assembly") }
                                .forEach { tool -> WorkspaceToolRow(tool, false, true, {}) }
                        } else {
                            if (group == "pages" && query.isBlank())
                                readingActions
                                    .filter { it.id in setOf("read:range", "read:assembly") }
                                    .forEach { tool -> WorkspaceToolRow(tool, false, true, {}) }
                            val found =
                                titles.filter { (key, title) ->
                                    (query.isNotBlank() ||
                                        group.isBlank() ||
                                        pdfFamily(key) == group) &&
                                        ToolCatalog.pdfMatches(key, title, query)
                                }
                            if (found.isEmpty()) Text("没有找到匹配的工具")
                            found.forEach { (key, title) ->
                                WorkspaceToolRow(
                                    HomeTool(
                                        "tool:$key",
                                        title,
                                        ToolCatalog.conversion(key)?.detail
                                            ?: pdfFamilies
                                                .find { it.id == pdfFamily(key) }
                                                ?.detail
                                                .orEmpty(),
                                        androidx.compose.material.icons.Icons.Default.PictureAsPdf,
                                    ) {
                                        mode = key
                                        number = if (key == "blank") "1" else "5"
                                    },
                                    "tool:$key" in pinned,
                                    true,
                                    { capability = it },
                                )
                            }
                        }
                    }
                } else {
                    if (
                        mode in
                            listOf(
                                "images-jpg",
                                "images-png",
                                "images-webp",
                                "long-image",
                                "watermark",
                                "number",
                                "crop",
                            )
                    )
                        OutlinedTextField(
                            value = range,
                            onValueChange = { range = it },
                            label = { Text("页码 1-3,5；留空为全部") },
                        )
                    if (mode == "watermark")
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("水印文字") },
                        )
                    if (mode == "long-image")
                        LongImageControls(longOptions, { longOptions = it }, { longValid = it })
                    if (mode == "metadata") {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("标题") },
                        )
                        OutlinedTextField(
                            value = second,
                            onValueChange = { second = it },
                            label = { Text("作者") },
                        )
                    }
                    if (mode in listOf("crop", "blank"))
                        OutlinedTextField(
                            value = number,
                            onValueChange = { number = it },
                            label = {
                                Text(if (mode == "crop") "四周裁切百分比 0–40" else "在第几页后插入；0 为最前")
                            },
                        )
                    if (mode == "crop") Text("调整 CropBox，可见范围外的内容仍保留。")
                    if (mode == "encrypt") {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("打开密码") },
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Row {
                            Checkbox(printAllowed, { printAllowed = it })
                            Text("允许打印")
                        }
                        Row {
                            Checkbox(copyAllowed, { copyAllowed = it })
                            Text("允许复制文字")
                        }
                        Text("AES-256 打开密码；阅读器对打印/复制权限的遵守程度可能不同。密码不保存。")
                    }
                    if (mode == "searchable")
                        Text("逐页离线 OCR，保留原 PDF 内容，添加嵌入字体与透明文字层；按已有文字覆盖范围跳过，避免重复添加页眉。")
                    if (mode == "flatten")
                        Text("将页面重绘为图片。原有可选文字、链接、表单、批注对象和数字签名会丢失。生成新副本，源 PDF 保留。")
                    if (mode == "docx-layout") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                layoutMode == "editable",
                                { layoutMode = "editable" },
                                { Text("编辑优先") },
                            )
                            FilterChip(
                                layoutMode == "appearance",
                                { layoutMode = "appearance" },
                                { Text("外观优先") },
                            )
                        }
                        Text(
                            if (layoutMode == "appearance") "保留实际整页外观、纸张和方向；每页为图片，文字和表格不能独立编辑。"
                            else
                                "本机重建标题、段落、表格与可见图片裁片；多栏展开为单栏阅读流，扫描件离线 OCR。脚注保留为小号正文；字体近似、复杂浮动对象请对照原件。"
                        )
                    }
                    if (mode == "pdf-pptx") Text("每页生成一张图片幻灯片，保留页面外观；文字与图表不是独立可编辑对象。")
                    if (mode == "pdf-pptx-native") {
                        Text(
                            "可见文字、图片和基础路径作为可编辑对象；复杂页默认保留整页外观。扫描图仍是图片，字体以本机字体近似；混合纸张等比居中到首页尺寸，附逐页记录。"
                        )
                        Row {
                            Checkbox(preferEditable, { preferEditable = it })
                            Text("优先编辑，简化复杂剪裁与遮罩", Modifier.padding(top = 10.dp))
                        }
                    }
                    if (mode == "docx-image") Text("每页参考图加可编辑文字段落，便于对照。图片保留页面外观；不重建原排版或表格。")
                    if (mode == "docx") Text("导出可编辑的文字段落，保留文字阅读顺序；图片、表格与原排版不属于此模式。")
                    if (mode in listOf("optimize", "nup2", "nup4"))
                        Text(
                            if (mode == "optimize")
                                "以较高画质压缩可安全处理的图片并去除重复图像资源，保留文字、矢量与链接。无法减小时保留原件副本。"
                            else "生成拼版副本，保持页面矢量内容。"
                        )
                    if (mode == "forms") Text("读取 PDF 中实际存在的 AcroForm 字段。")
                }
            }
        },
        confirmButton = {
            if (mode.isNotEmpty())
                TextButton(
                    enabled = mode != "long-image" || longValid,
                    onClick = {
                        when (mode) {
                            "encrypt" ->
                                if (text.isNotEmpty()) {
                                    vm.encrypt(text, printAllowed, copyAllowed)
                                    onDismiss()
                                }
                            "forms" -> {
                                vm.forms()
                                onDismiss()
                            }
                            "diagnostics" -> {
                                vm.screen.value = "pdf-diagnostics"
                                onDismiss()
                            }
                            else -> {
                                val p =
                                    (if (mode == "long-image") longOptions.json() else JSONObject())
                                        .put("range", range)
                                        .put("text", text)
                                        .put("title", text)
                                        .put("author", second)
                                        .put("preferEditable", preferEditable)
                                        .put("layoutMode", layoutMode)
                                if (mode == "crop") p.put("percent", number.toDoubleOrNull() ?: -1)
                                if (mode == "blank") p.put("after", number.toIntOrNull() ?: -1)
                                vm.queueTool(mode, p)
                                onDismiss()
                            }
                        }
                    },
                ) {
                    Text("执行")
                }
        },
        dismissButton = {
            TextButton(onClick = { if (mode.isEmpty()) onDismiss() else mode = "" }) {
                Text(if (mode.isEmpty()) "关闭" else "返回工具")
            }
        },
    )
}
