package cn.paperforge.app.ui

import androidx.compose.material.icons.filled.Description
import cn.paperforge.app.model.ToolCapability
import cn.paperforge.app.model.ToolCatalog

/** Description is available for every entry, including navigation/actions outside conversions. */
internal fun toolDescription(tool: HomeTool): ToolCapability {
    ToolCatalog.conversion(tool.id.removePrefix("tool:"))?.let {
        return it
    }
    val id = tool.id.removePrefix("tool:").removePrefix("action:")
    val formats =
        when {
            id in setOf("scan", "capture", "images", "ocr-images", "image-pdf") ->
                "相机照片、JPG / PNG / WebP 等系统可解码图片" to "应用内扫描文档，可导出 PDF 或图片；识字结果另行查看"
            id == "backup" -> "文档库中的原图、PDF、OCR、分类与编辑记录" to "PaperForge 备份 ZIP"
            id == "restore" -> "PaperForge 导出的备份 ZIP" to "恢复到应用文档库；保留原有文档"
            id in setOf("folders", "rename-batch", "library", "storage") ->
                "文档库中的扫描文档和 PDF" to "更新本地文档管理信息；不转换文件格式"
            id == "notes" -> "摘录与笔记" to "笔记工作台，具体导出格式在导出时选择"
            id == "read:share-process" -> "当前 PDF" to "按设置清理或压缩后的副本，可保存或分享；原件保留"
            id.startsWith("read:") -> "当前 PDF" to "阅读视图或导航；不改写源文件"
            id == "encrypt" -> "未受限制或已解锁的 PDF" to "设置打开密码和权限的 PDF 副本"
            id in setOf("document-watermarks", "watermark-clean") ->
                "PDF、DOCX/XLSX/PPTX/ODF、DOC/XLS/PPT/RTF、JPG/PNG 等图片" to
                    "Office 新格式保留原格式；旧格式转 PDF；图片输出 PNG。PDF 支持文字对象或框选页图修复。"
            id == "watermark-clean" ->
                "带文字层、图像对象或可定位覆盖对象的 PDF" to "移除所选对象后的 PDF 副本；与正文融为一张图的水印需图像修复"
            id in setOf("annotate", "native-edit", "forms", "watermark") ->
                "当前 PDF；表单填写需已有 AcroForm 字段" to "编辑或标注后的 PDF 副本"
            id in
                setOf("range", "split-each", "merge", "crop", "blank", "nup2", "nup4", "number") ->
                "当前 PDF 或选中的多份 PDF" to "按页面操作生成的 PDF 副本"
            id in setOf("redact", "certificates", "archive") ->
                "当前 PDF；证书签署另需自己的证书文件" to "脱敏、签署或归档结果；执行前可核对所选参数"
            id in setOf("diagnostics", "metadata", "optimize", "flatten") ->
                "当前 PDF" to "属性或诊断报告，或优化后的 PDF 副本；扁平化会将对象转为页图"
            else -> "当前文档，或在功能页选择的文件；以文件选择器提供的格式为准" to tool.description.ifBlank { "在功能页查看结果与保存选项" }
        }
    return ToolCapability(
        tool.title,
        tool.description,
        tool.group,
        tool.id,
        input = formats.first,
        output = formats.second,
        parameters = "进入功能页后选择页面、范围或处理选项；执行前核对预览。",
        boundary =
            if (tool.id.startsWith("read:")) "阅读操作不修改源文件。" else "具体适用范围见功能页；图像中无法辨认的内容需要原件核对。",
        engine =
            if (tool.id.contains("network") || tool.id.contains("intelligence")) "根据所选服务配置"
            else "手机本地；联网操作会在功能页说明",
    )
}

internal fun scanToolDescription(label: String): ToolCapability =
    toolDescription(
            HomeTool(
                "scan:$label",
                label,
                when {
                    "识" in label || "文字" in label || "校对" in label -> "扫描页面离线识别与人工校对，保留原图；公式需对照核验。"
                    "导出" in label -> "将扫描页面导出为 PDF 或标明格式的图片包；保留原件。"
                    "票据" in label -> "从识别文字提取字段，人工核对后导出 CSV / XLSX。"
                    "高清" in label || "修复" in label -> "可调强度、自动预览、原图对照，另存处理副本。"
                    "裁" in label || "曲面" in label -> "调整页面边界或曲面几何，先预览再应用；可撤销。"
                    else -> "操作当前扫描文档；在功能页选择处理范围和保存方式。"
                },
                androidx.compose.material.icons.Icons.Default.Description,
                "扫描",
                {},
            )
        )
        .copy(
            input = "应用内扫描页面及其原图",
            output = if ("导出" in label) "标明格式的 PDF / 图片包" else "扫描文档、预览或处理记录",
        )
