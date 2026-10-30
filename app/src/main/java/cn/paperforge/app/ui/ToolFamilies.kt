package cn.paperforge.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

internal val pdfFamilies =
    listOf(
        WorkspaceSection("pages", "页面编排", "拆分、裁边、页码与拼版", Icons.Default.ViewModule),
        WorkspaceSection("edit", "内容与标注", "文字、表单、水印与批注", Icons.Default.Draw),
        WorkspaceSection("export", "导出与转换", "图片、文字、Word 与幻灯片", Icons.Default.SwapHoriz),
        WorkspaceSection("protect", "安全与签署", "密码、脱敏、证书与归档", Icons.Default.Lock),
        WorkspaceSection("optimize", "优化与属性", "压缩、资源、属性与诊断", Icons.Default.Tune),
    )

internal fun pdfFamily(mode: String): String =
    when (mode.removePrefix("tool:")) {
        "range",
        "merge",
        "split-each",
        "crop",
        "blank",
        "nup2",
        "nup4",
        "number",
        "action:assembly",
        "action:open-pdf" -> "pages"
        "annotate",
        "native-edit",
        "forms",
        "watermark" -> "edit"
        "encrypt",
        "redact",
        "certificates",
        "archive" -> "protect"
        "compress",
        "enhance-images",
        "optimize",
        "metadata",
        "diagnostics",
        "pdf-diff" -> "optimize"
        else -> "export"
    }

internal val conversionFamilies =
    listOf(
        WorkspaceSection(
            "手机引擎",
            "Office 文档",
            "Word、Excel、PowerPoint 与 ODF",
            Icons.Default.Description,
        ),
        WorkspaceSection("手机表格", "表格与数据", "识别表格，导出 Excel 或 CSV", Icons.Default.TableChart),
        WorkspaceSection("文字", "文字与文档", "PDF 转 Word、文本与幻灯片", Icons.Default.Article),
        WorkspaceSection("图片", "图片与长图", "图片互转、页图导出与拼接", Icons.Default.Image),
        WorkspaceSection("电子书", "电子书", "EPUB、章节、封面与文本", Icons.Default.AutoStories),
        WorkspaceSection("版式文档", "特殊格式", "OFD、DjVu、CAD 与其他文档", Icons.Default.FolderOpen),
        WorkspaceSection("生成 PDF", "生成 PDF", "文字、图片与文档生成 PDF", Icons.Default.PictureAsPdf),
    )

internal fun toolFamily(tool: HomeTool, root: String) =
    if (root == "pdf") pdfFamily(tool.id) else tool.group
