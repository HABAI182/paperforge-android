package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import org.json.JSONObject

@Composable
fun PdfToolsDialog(vm: MainViewModel, initialMode: String = "", onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(initialMode) }
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("全部") }
    var range by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("5") }
    var printAllowed by remember { mutableStateOf(true) }
    var copyAllowed by remember { mutableStateOf(true) }
    val titles =
        linkedMapOf(
            "searchable" to "扫描 PDF → 可搜索 PDF",
            "images-jpg" to "PDF 转 JPG 图片包",
            "images-png" to "PDF 转 PNG 图片包",
            "images-webp" to "PDF 转 WebP 图片包",
            "long-image" to "PDF 转连续长图（自动分段）",
            "split-each" to "每页拆成独立 PDF",
            "extract-images" to "提取嵌入图片（PNG）",
            "watermark" to "添加中文水印",
            "number" to "添加页码",
            "crop" to "裁切页面边缘",
            "blank" to "插入 A4 空白页",
            "nup2" to "2 合 1 拼版",
            "nup4" to "4 合 1 拼版",
            "metadata" to "标题与作者",
            "diagnostics" to "PDF 属性与诊断",
            "encrypt" to "密码加密 / 权限",
            "forms" to "填写 AcroForm 表单",
            "txt" to "导出纯文本 TXT",
            "md" to "导出 Markdown 文本",
            "html" to "导出 HTML 文本",
            "docx" to "导出可编辑段落 DOCX",
            "docx-layout" to "手机结构重建 DOCX",
            "pdf-pptx" to "PDF 转 PPTX 整页图片",
            "docx-image" to "原页参考图 + 文字 DOCX",
            "optimize" to "图片优化与资源去重",
            "flatten" to "扁平化为图像 PDF（有损）",
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode.isEmpty()) "PDF 工具箱" else titles[mode] ?: "PDF 工具") },
        text = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (mode.isEmpty()) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text("搜索工具，如水印、Word、页码") },
                        singleLine = true,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("全部", "转换", "页面", "其他").forEach { name ->
                            FilterChip(group == name, { group = name }, label = { Text(name) })
                        }
                    }
                    val conversion =
                        setOf(
                            "searchable",
                            "images-jpg",
                            "images-png",
                            "images-webp",
                            "long-image",
                            "extract-images",
                            "txt",
                            "md",
                            "html",
                            "docx",
                            "docx-layout",
                            "pdf-pptx",
                            "docx-image",
                        )
                    val pageTools = setOf("split-each", "crop", "blank", "nup2", "nup4", "number")
                    titles
                        .filter { (key, title) ->
                            val category =
                                if (key in conversion) "转换"
                                else if (key in pageTools) "页面" else "其他"
                            (group == "全部" || category == group) &&
                                (query.isBlank() ||
                                    title.contains(query, true) ||
                                    key.contains(query, true) ||
                                    (query.equals("word", true) && key.startsWith("docx")))
                        }
                        .forEach { (key, title) ->
                            OutlinedButton(
                                onClick = {
                                    mode = key
                                    number = if (key == "blank") "1" else "5"
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(title)
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
                    if (mode == "docx-layout")
                        Text("本机重建文字、表格与嵌入图片；扫描件会离线 OCR。复杂跨栏、裁剪图形和浮动对象可能重新排版，请对照原件。")
                    if (mode == "pdf-pptx") Text("每页生成一张图片幻灯片，保留页面外观；文字与图表不是独立可编辑对象。")
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
                                    JSONObject()
                                        .put("range", range)
                                        .put("text", text)
                                        .put("title", text)
                                        .put("author", second)
                                if (mode == "crop") p.put("percent", number.toDoubleOrNull() ?: -1)
                                if (mode == "blank") p.put("after", number.toIntOrNull() ?: -1)
                                vm.queueTool(mode, p)
                                onDismiss()
                            }
                        }
                    }
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
