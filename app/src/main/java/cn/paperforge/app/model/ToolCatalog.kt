package cn.paperforge.app.model

/** Executable conversion routes and their user-visible capabilities share one catalog. */
data class ToolCapability(
    val title: String,
    val detail: String,
    val group: String,
    val mode: String,
    val input: String = "",
    val output: String = "",
    val parameters: String = "",
    val boundary: String = "",
    val engine: String = "手机离线",
    val aliases: String = "",
) {
    val id
        get() = "tool:$mode"

    fun matches(query: String) =
        ToolCatalog.matches(query, title, detail, mode, input, output, aliases)
}

object ToolCatalog {
    val conversions =
        listOf(
                ToolCapability("电脑助手结果导入", "接收 Office、表格和版面转换的结果包", "电脑引擎", "desktop-import"),
                ToolCapability(
                    "Office → PDF · 保留排版",
                    "Word、Excel、PowerPoint、ODF；手机本机排版引擎",
                    "手机引擎",
                    "office-pdf",
                ),
                ToolCapability(
                    "Office 格式互转",
                    "DOCX / ODT / RTF、XLSX / ODS / CSV、PPTX / ODP",
                    "手机引擎",
                    "office-options",
                ),
                ToolCapability(
                    "PowerPoint / Office → 图片包",
                    "按实际排版逐页输出 PNG、JPG 或 WebP，保留原件",
                    "手机引擎",
                    "office-images",
                ),
                ToolCapability(
                    "PDF → Word · 结构重建",
                    "手机本地重建文字、表格与嵌入图片；扫描页离线 OCR",
                    "文字",
                    "docx-layout",
                ),
                ToolCapability(
                    "PDF / 图片 → Excel 表格",
                    "有线 / 无线表、合并单元格、离线 OCR，导出 XLSX",
                    "手机表格",
                    "tables-xlsx",
                ),
                ToolCapability("PDF / 图片 → CSV 表格包", "每张表独立 CSV，附结构与来源信息", "手机表格", "tables-csv"),
                ToolCapability("PDF → PowerPoint · 整页外观", "手机本地生成，一页一张图片幻灯片", "文字", "pdf-pptx"),
                ToolCapability(
                    "PDF → PowerPoint · 原生对象",
                    "可见文字、图片和基础路径；复杂页可保留外观或优先编辑",
                    "文字",
                    "pdf-pptx-native",
                ),
                ToolCapability("Markdown → HTML 资源包", "标题、列表、链接、表格、脚注与图片；附打印样式", "文字", "md-html"),
                ToolCapability("HTML → Markdown 资源包", "有限语法转换；合并表格和复杂样式保留 HTML", "文字", "html-md"),
                ToolCapability(
                    "EPUB → HTML / PDF",
                    "按章节顺序合并，目录、封面、脚注；预览后系统另存 PDF",
                    "电子书",
                    "epub-html",
                ),
                ToolCapability("EPUB → Markdown", "目录与脚注内链、图片资源包", "电子书", "epub-md"),
                ToolCapability("EPUB → TXT", "按章节顺序导出纯文字资源包", "电子书", "epub-txt"),
                ToolCapability(
                    "文档 → EPUB 电子书",
                    "TXT / MD / HTML / 资源包 / PDF；标题分章、目录与原页对照",
                    "电子书",
                    "epub-create",
                ),
                ToolCapability("OFD → PDF", "嵌入字体、页序、图片与印章外观；原件保留", "版式文档", "ofd-pdf"),
                ToolCapability("DjVu → PDF", "多页与旋转方向，保留可用的原始文字坐标", "版式文档", "djvu-pdf"),
                ToolCapability(
                    "CAJ / KDH → PDF",
                    "PDF 容器、保留原件；不支持的 HN / C8 会明确说明",
                    "版式文档",
                    "caj-pdf",
                ),
                ToolCapability("CAD DXF → PDF", "手机 Draw 引擎解析二维线条、图形与文字", "版式文档", "dxf-pdf"),
                ToolCapability(
                    "LaTeX 正文与公式 → PDF",
                    "常用标题、段落、表格与数学；支持带图片的 TeX 项目 ZIP",
                    "版式文档",
                    "latex-pdf",
                ),
                ToolCapability("邮件 EML → PDF", "中文主题、正文、内嵌图片与邮件头；附件保留在原件", "版式文档", "email-pdf"),
                ToolCapability(
                    "XPS / SVG / 漫画 / FB2 → PDF",
                    "XPS、OXPS、SVG、CBZ、FB2；文字与基础矢量保留",
                    "版式文档",
                    "native-format-pdf",
                    "email-pdf",
                ),
                ToolCapability("特殊文档 → PNG 页包", "逐页无损图片、纸张尺寸和版面报告", "版式文档", "layout-images"),
                ToolCapability(
                    "文字 / DOCX → PDF",
                    "TXT、Markdown、HTML、DOCX 正文，中文分页排版",
                    "生成 PDF",
                    "text-pdf",
                ),
                ToolCapability("图片 → PDF", "多图排序、裁边、滤镜和纸张尺寸", "生成 PDF", "image-pdf"),
                ToolCapability("图片格式互转", "JPG、PNG、WebP；支持批量打包", "图片", "image-format"),
                ToolCapability("PDF → JPG", "整页高清导出，按页码输出图片包", "图片", "images-jpg"),
                ToolCapability("PDF → WebP", "逐页导出 WebP 图片包", "图片", "images-webp"),
                ToolCapability("图片拼成长图", "顺序或重叠拼接，接缝预览与手调，过长自动分段", "图片", "images-long"),
                ToolCapability("PDF → 长图", "按页序拼接，超过长度自动连续分段", "图片", "long-image"),
                ToolCapability("PDF → PNG", "无损图片编码，适合文字与线条", "图片", "images-png"),
                ToolCapability("PDF → Word 段落", "提取文字为可编辑 DOCX，重新排版", "文字", "docx"),
                ToolCapability("PDF → 图文 Word", "每页参考图加可编辑文字段落", "文字", "docx-image"),
                ToolCapability("PDF → TXT", "导出已有文字层", "文字", "txt"),
                ToolCapability("PDF → Markdown", "按段落导出文本，不重建复杂版式", "文字", "md"),
                ToolCapability("PDF → HTML", "生成可在浏览器打开的文字文档", "文字", "html"),
                ToolCapability("扫描 PDF → 可搜索 PDF", "离线 OCR，保留原页并添加透明文字层", "生成 PDF", "searchable"),
                ToolCapability("提取 PDF 内图片", "提取嵌入图像，输出 PNG 图片包", "图片", "extract-images"),
                ToolCapability("PDF 每页独立拆分", "一页一份 PDF，打包保存", "整理", "split-each"),
                ToolCapability("合并 PDF", "选择多份 PDF，调整顺序后合并", "整理", "merge"),
                ToolCapability("压缩 PDF", "优化嵌入图片并保留文字和链接", "整理", "compress"),
                ToolCapability("PDF 差异比较", "原生文字与外观、并排同步查看、区域定位与报告", "整理", "pdf-diff"),
                ToolCapability("工作流配方", "按顺序处理，保存步骤；调整目录复用 OCR 与排版", "整理", "workflows"),
                ToolCapability("批量转换", "共用预设、逐项参数、单独结果与失败重试", "整理", "conversion-batch"),
                ToolCapability("自动导出", "按文件夹和标签触发，结果与失败记录可继续", "整理", "auto-export"),
            )
            .map { entry ->
                when (entry.mode) {
                    "dxf-pdf" ->
                        entry.copy(
                            input = "DXF 二维图纸",
                            output = "PDF；可用实际手机 Office 排版对照",
                            parameters = "保留原件、任务重试；也可通过 Office 图片包导出逐页图像",
                            boundary =
                                "按手机 LibreOffice Draw 的导入能力；不读取 DWG/DGN，不保证外部字体、三维实体或全部 CAD 专有对象。",
                            engine = "可选手机 Office 引擎 · 离线",
                            aliases = "CAD DXF 图纸 工程图 二维 绘图 Draw",
                        )
                    "caj-pdf" ->
                        entry.copy(
                            input = "内含 PDF 的 CAJ、KDH，或改名为 CAJ 的 PDF",
                            output = "PDF / PNG 页包与读取报告",
                            parameters = "分辨率、原件保留、逐页缓存、实际结果对照",
                            boundary = "仅 PDF 容器；HN / C8 / TEB 图像型 CAJ 尚不支持。目录、证书和交互内容保留在原件，未迁移。",
                            engine = "可选手机格式引擎 · 离线",
                            aliases = "CAJ KDH HN C8 知网 论文",
                        )
                    "latex-pdf" ->
                        entry.copy(
                            input = "UTF-8 .tex，或包含 main.tex / 单一主文件与图片的项目 ZIP",
                            output = "PDF / PNG 页包、公式原文与不支持内容报告",
                            parameters = "纸张、字号、DPI、公式附原文、原件保留与结果对照",
                            boundary =
                                "常用正文结构加 JLaTeXMath 数学，公式为 300 DPI 图像；不是完整 TeX 编译器。宏包、TikZ、BibTeX 和未知命令保留原文并提示。",
                            engine = "可选手机格式引擎 · MuPDF / JLaTeXMath · 离线",
                            aliases = "tex latex 数学 公式 矩阵 分数 根式 论文 排版 项目 zip",
                        )
                    "ofd-pdf",
                    "djvu-pdf",
                    "native-format-pdf",
                    "layout-images" ->
                        entry.copy(
                            input =
                                when (entry.mode) {
                                    "ofd-pdf" -> "OFD 版式文档"
                                    "djvu-pdf" -> "DjVu / DJV 多页文档"
                                    "native-format-pdf" -> "XPS / OXPS、基础 SVG、CBZ 漫画、FB2 电子书"
                                    "email-pdf" -> "EML 邮件，MIME 正文与内嵌图片"
                                    else -> "格式引擎支持的特殊文档"
                                },
                            output =
                                if (entry.mode in setOf("ofd-pdf", "djvu-pdf")) "PDF；页面图像加可用的原始文字层"
                                else "PDF 或 PNG 页包；引擎可映射的文字与基础矢量保留",
                            parameters =
                                "150 / 200 / 300 DPI、输出类型、原件保留、实际结果对照、转换报告" +
                                    if (entry.mode in setOf("native-format-pdf", "email-pdf"))
                                        "；重排纸张与字号"
                                    else "",
                            boundary =
                                if (entry.mode == "email-pdf")
                                    "静态邮件正文，CID 图片离线读取；外部图片不联网加载，附件数据留在原件。"
                                else "需要独立手机格式引擎，支持范围依对应解析器。复杂效果请核对实际版面；源签名与证书未迁移或验证。",
                            engine = "可选手机格式引擎 · 离线",
                            aliases =
                                when (entry.mode) {
                                    "ofd-pdf" -> "ofd 版式 电子发票 票据 印章"
                                    "djvu-pdf" -> "djvu djv 书刊"
                                    "native-format-pdf" -> "xps oxps svg cbz fb2 漫画 电子书"
                                    "email-pdf" -> "eml email mime 邮件"
                                    else -> "ofd djvu xps svg cbz fb2 eml caj kdh tex PNG 图片 页包"
                                },
                        )
                    "auto-export" ->
                        entry.copy(
                            input = "文档库中的扫描文档和 PDF；按标签、文件夹或子文件夹匹配",
                            output = "按所选配方生成本地成品，可保存到授权目录",
                            parameters = "规则启停、等待稳定时间、命名配方副本、目录、扫描导出参数、手动运行及重试",
                            boundary = "默认关闭；失败后保留结果并等待手动处理，不循环重试。已有 PDF 保留原页，自动结果只进入导出记录。",
                            aliases = "自动 归档 条件 规则 标签 文件夹 自动化 automation export",
                        )
                    "office-images" ->
                        entry.copy(
                            input = "PPT / PPTX / ODP，以及手机引擎可读取的其他 Office 文档",
                            output = "每页静态图片 ZIP，附页序、尺寸和来源报告；可实际预览",
                            parameters = "PNG / JPG / WebP、最长边像素和图像质量；逐页检查点",
                            boundary = "按手机 Office 引擎排版；图片保留可见外观，动画、音频、备注和可编辑对象不随图片保留。",
                            engine = "可选手机 Office 引擎 · 离线",
                            aliases = "ppt pptx odp slides 幻灯片 演示文稿 图片 相册 PNG JPG WebP",
                        )
                    "conversion-batch" ->
                        entry.copy(
                            input = "多份 PDF、文字、图片、Office、OFD / DjVu 或结构文档",
                            output = "各文件独立结果；表格识别后继续校对并导出",
                            parameters = "共用预设、逐项输出与排版设置、失败项重试、原件重选",
                            boundary =
                                "只重试选定的失败项；Office / OFD / DjVu 使用对应可选手机引擎。合并、拼接等多对一操作仍使用各自工作台。",
                            aliases = "batch 批处理 批量 失败 重试 多文件 convert",
                        )
                    "workflows" ->
                        entry.copy(
                            input = "单份 PDF、Office、TXT 或图片；每步检查格式匹配",
                            output = "PDF、TXT、DOCX 或 PNG 页包；可保存到授权目录",
                            parameters = "步骤增删/排序、压缩质量、纸张、命名模板、目的地；内置和命名配方",
                            boundary = "仅提供列出的本机处理路线；Office 排版需要可选手机引擎。已有任务可调整重跑，参数相同的已完成阶段会复用。",
                            aliases = "workflow recipe 自动 流水线 OCR 压缩 归档 批处理",
                        )
                    "pdf-diff" ->
                        entry.copy(
                            input = "两份 PDF；原生文字层可比较文字，扫描件可比较外观",
                            output = "双栏差异工作台；原件 / 标注图片 / HTML / JSON 报告包",
                            parameters = "两侧起始页、页眉页脚忽略范围、空白 / 大小写 / 灰度、外观容差、逐项忽略",
                            boundary = "按所选起始页逐页配对；文字与外观记录可能重复。扫描件缺文字层时不声称语义差异，复杂插页可调整起始页核对。",
                            aliases = "compare diff 对比 修订 审阅 版本 文档比较",
                        )
                    "desktop-import" ->
                        entry.copy(
                            input = "电脑助手结果 ZIP",
                            output = "导入 PDF / DOCX / XLSX 等结果",
                            parameters = "选择结果包",
                            boundary = "需要电脑助手先生成结果；手机引擎入口可直接在本机转换。",
                            engine = "可选电脑助手",
                            aliases = "桌面 desktop libreoffice",
                        )
                    "office-pdf" ->
                        entry.copy(
                            input = "DOC / DOCX / XLS / XLSX / PPT / PPTX / ODF / RTF",
                            output = "保留排版的 PDF",
                            parameters = "工作表、打印范围与排版沿用源文档",
                            boundary = "首次需要独立安装 Office 手机引擎；缺失字体、宏、外链与复杂对象可能影响排版。",
                            engine = "独立 Office 手机引擎",
                            aliases = "word excel powerpoint 演示 表格 libreoffice",
                        )
                    "office-options" ->
                        entry.copy(
                            input = "Word / Excel / PowerPoint / ODF / RTF",
                            output = "DOCX / ODT / RTF / XLSX / ODS / CSV / PPTX / ODP",
                            parameters = "选择与源文档类型对应的目标格式",
                            boundary = "CSV 仅保留当前或首个工作表的值；宏与部分 Office 专有对象不会完整保留。",
                            engine = "独立 Office 手机引擎",
                            aliases = "word excel powerpoint 格式 转换 libreoffice",
                        )
                    "docx-layout" ->
                        entry.copy(
                            input = "原生 / 扫描 PDF",
                            output = "可编辑 DOCX 结构文档 / 整页外观 DOCX",
                            parameters = "编辑优先 / 外观优先、离线 OCR、表格识别",
                            boundary =
                                "编辑模式按阅读顺序展开多栏，脚注保留为小号正文，图片为实际合成裁片；字体近似。外观模式为整页图片，不能独立编辑文字或表格。",
                            engine = "手机本地结构重建 + 可选 OCR",
                            aliases = "word office 文档 编辑 识字 ocr",
                        )
                    "tables-xlsx" ->
                        entry.copy(
                            input = "PDF / 图片",
                            output = "XLSX 工作簿",
                            parameters = "区域、行列、合并、单元格类型与跨页表",
                            boundary = "进入表格工作台对照校正；复杂无框表和低清扫描可能需要调整。",
                            engine = "手机本地表格分析 + 可选 OCR",
                            aliases = "excel 电子表格 spreadsheet ocr 识字",
                        )
                    "tables-csv" ->
                        entry.copy(
                            input = "PDF / 图片",
                            output = "逐表 CSV + 结构 JSON 资源包",
                            parameters = "区域、单元格类型、编码、分隔符",
                            boundary = "CSV 不保存合并和样式；合并关系在结构报告中保留。",
                            engine = "手机本地表格分析 + 可选 OCR",
                            aliases = "excel tsv csv 电子表格 ocr",
                        )
                    "pdf-pptx" ->
                        entry.copy(
                            input = "PDF",
                            output = "PPTX 幻灯片",
                            parameters = "逐页生成",
                            boundary = "一页一张参考图片；文字和图表不能作为独立对象编辑。",
                            engine = "手机本地页面渲染",
                            aliases = "powerpoint ppt 演示 幻灯片 图片",
                        )
                    "md-html" ->
                        entry.copy(
                            input = "Markdown / 含相对资源的 ZIP",
                            output = "HTML + 图片 / 样式资源 ZIP；可预览后打印 PDF",
                            parameters = "转换后查看原件与结果，系统打印时选纸张",
                            boundary = "转换静态结构；远程图片不自动下载。",
                            engine = "手机离线 CommonMark + HTML",
                            aliases = "markdown md 网页 html 打印",
                        )
                    "pdf-pptx-native" ->
                        entry.copy(
                            input = "带原生内容的 PDF；扫描图仍作为图片",
                            output = "文字框、图片和基础矢量形状组成的 PPTX；附逐页转换记录",
                            parameters = "默认复杂页保留整页外观；可选择优先编辑并简化复杂效果",
                            boundary =
                                "字体使用本机字体近似；混合纸张等比居中到首页尺寸。不重建图表数据、动画与语义表格。竖排、坏文字映射或未完整解析的页保留外观。",
                            aliases = "powerpoint ppt pptx 编辑 editable 文字 对象 图形 vector",
                        )
                    "html-md" ->
                        entry.copy(
                            input = "HTML / 含相对资源的 ZIP",
                            output = "Markdown + 图片资源 ZIP",
                            parameters = "原件 / 结果预览、源代码对照",
                            boundary = "合并表格和复杂样式以 HTML 片段保留；脚本交互转为静态内容。",
                            engine = "手机离线 HTML 结构转换",
                            aliases = "网页 html markdown md",
                        )
                    "epub-html" ->
                        entry.copy(
                            input = "EPUB 2 / 3",
                            output = "章节合并 HTML 资源包；系统另存 PDF",
                            parameters = "原件 / 结果、目录、系统打印",
                            boundary = "固定版式按静态内容重排；不解密受保护电子书；系统打印 PDF 不保留内部可点击目录。",
                            engine = "手机离线 EPUB 解析",
                            aliases = "电子书 ebook 阅读 打印",
                        )
                    "epub-md" ->
                        entry.copy(
                            input = "EPUB 2 / 3",
                            output = "Markdown + 图片资源 ZIP",
                            parameters = "原件 / 结果预览",
                            boundary = "按 spine 章节顺序；复杂样式静态回退，不解密受保护内容。",
                            engine = "手机离线 EPUB 解析",
                            aliases = "电子书 ebook markdown",
                        )
                    "epub-txt" ->
                        entry.copy(
                            input = "EPUB 2 / 3",
                            output = "TXT 纯文字资源包",
                            parameters = "原件 / 结果预览",
                            boundary = "纯文字不保留图片与页面版式；章节和语义换行保留。",
                            engine = "手机离线 EPUB 解析",
                            aliases = "电子书 ebook 文本 text",
                        )
                    "epub-create" ->
                        entry.copy(
                            input = "TXT / MD / HTML / 资源 ZIP / PDF",
                            output = "标准 EPUB 3 电子书",
                            parameters = "书名、作者、语言、封面、PDF 单 / 双栏顺序",
                            boundary = "PDF 使用原生文字或离线 OCR 重排；复杂表格、公式和图形可展开原页对照。",
                            engine = "手机离线排版 + 可选 OCR",
                            aliases = "电子书 ebook 生成 word 阅读 ocr",
                        )
                    "text-pdf" ->
                        entry.copy(
                            input = "TXT / Markdown / HTML / DOCX 正文",
                            output = "重新排版的 PDF",
                            parameters = "草稿、纸张、方向、字号、边距、行距",
                            boundary = "此入口提取正文重新排版；需要源 Office 外观时选择 Office → PDF。",
                            engine = "手机离线文字排版",
                            aliases = "word text 文本 打印",
                        )
                    "image-pdf" ->
                        entry.copy(
                            input = "多张图片",
                            output = "扫描 PDF",
                            parameters = "排序、裁边、滤镜、纸张、画质与 OCR",
                            boundary = "进入扫描编辑后导出；原始图片保留。",
                            engine = "手机离线扫描 / 导出",
                            aliases = "照片 相册 image jpg png ocr",
                        )
                    "image-format" ->
                        entry.copy(
                            input = "JPG / PNG / WebP 等 Android 可解码图片",
                            output = "JPG / PNG / WebP 图片 ZIP",
                            parameters = "质量、长边、透明、底色、无损 WebP",
                            boundary = "有损格式不能保留透明度；失败项保留报告并可重试。",
                            engine = "手机离线图片编解码",
                            aliases = "照片 image jpeg jpg png webp 批量",
                        )
                    "images-jpg" ->
                        entry.copy(
                            input = "PDF",
                            output = "逐页 JPG 图片 ZIP",
                            parameters = "页码范围",
                            boundary = "整页栅格图像，不能作为可编辑 PDF 对象。",
                            engine = "手机离线页面渲染",
                            aliases = "图片 照片 image jpeg jpg",
                        )
                    "images-webp" ->
                        entry.copy(
                            input = "PDF",
                            output = "逐页 WebP 图片 ZIP",
                            parameters = "页码范围",
                            boundary = "整页栅格图像。",
                            engine = "手机离线页面渲染",
                            aliases = "图片 image webp",
                        )
                    "images-long" ->
                        entry.copy(
                            input = "多张图片",
                            output = "分段 JPG / PNG / WebP 长图及来源报告",
                            parameters = "顺序 / 重叠、接缝、宽度、间距、分段、底色、质量",
                            boundary = "自动重叠匹配针对平移；强透视和曲面建议先裁切。",
                            engine = "手机离线长图工作台",
                            aliases = "照片 image 拼接 长截图 票据",
                        )
                    "long-image" ->
                        entry.copy(
                            input = "PDF",
                            output = "连续分段长图及源行映射",
                            parameters = "页码、宽度、间距、分段、格式、底色、质量",
                            boundary = "自动分段避免超长单图；图像输出不保留可选文字。",
                            engine = "手机离线页面渲染与拼接",
                            aliases = "图片 image 长截图 拼接",
                        )
                    "images-png" ->
                        entry.copy(
                            input = "PDF",
                            output = "逐页 PNG 图片 ZIP",
                            parameters = "页码范围",
                            boundary = "无损图像编码；整页仍按像素渲染。",
                            engine = "手机离线页面渲染",
                            aliases = "图片 image png 无损",
                        )
                    "docx" ->
                        entry.copy(
                            input = "带文字层的 PDF",
                            output = "可编辑段落 DOCX",
                            parameters = "逐页提取正文",
                            boundary = "不重建图片、复杂表格或原版式；扫描件可选结构重建或先添加 OCR。",
                            engine = "手机离线文字层提取",
                            aliases = "word office 文本 编辑",
                        )
                    "docx-image" ->
                        entry.copy(
                            input = "带文字层的 PDF",
                            output = "原页参考图 + 可编辑段落 DOCX",
                            parameters = "逐页输出",
                            boundary = "原页外观在图片中；不重建原排版或可编辑表格。",
                            engine = "手机离线渲染与文字提取",
                            aliases = "word office 图片 图文",
                        )
                    "txt" ->
                        entry.copy(
                            input = "带文字层的 PDF",
                            output = "TXT",
                            parameters = "按页面顺序提取",
                            boundary = "没有文字层的扫描件需要先 OCR。",
                            engine = "手机离线文字层提取",
                            aliases = "文本 text 文字",
                        )
                    "md" ->
                        entry.copy(
                            input = "带文字层的 PDF",
                            output = "Markdown",
                            parameters = "按段落导出",
                            boundary = "不重建复杂版式、表格与图片。",
                            engine = "手机离线文字层提取",
                            aliases = "markdown 文本 文字",
                        )
                    "html" ->
                        entry.copy(
                            input = "带文字层的 PDF",
                            output = "HTML",
                            parameters = "按段落导出",
                            boundary = "生成文字文档，不恢复原网页或复杂 PDF 版式。",
                            engine = "手机离线文字层提取",
                            aliases = "网页 文本 文字",
                        )
                    "searchable" ->
                        entry.copy(
                            input = "原生 / 扫描 PDF",
                            output = "原页 + 透明文字层 PDF",
                            parameters = "沿用 OCR 语言、引擎和识别精度",
                            boundary = "按已有文字覆盖范围跳过；识别质量取决于源图，原页外观保留。",
                            engine = "手机离线 OCR",
                            aliases = "识字 文字识别 ocr 搜索 扫描",
                        )
                    "extract-images" ->
                        entry.copy(
                            input = "PDF",
                            output = "嵌入图像 PNG ZIP",
                            parameters = "提取图像对象",
                            boundary = "提取嵌入图片；内联图像和矢量图形不等于独立图片对象。",
                            engine = "手机离线 PDF 资源读取",
                            aliases = "图片 image 提取 抽取",
                        )
                    "split-each" ->
                        entry.copy(
                            input = "PDF",
                            output = "每页独立 PDF 的 ZIP",
                            parameters = "逐页拆分",
                            boundary = "生成新副本；跨页跳转仅在目标页仍存在时保留。",
                            engine = "手机离线 PDF 页面处理",
                            aliases = "拆分 分割 页面 split",
                        )
                    "merge" ->
                        entry.copy(
                            input = "多份 PDF",
                            output = "合并 PDF",
                            parameters = "文件顺序与页面组合",
                            boundary = "普通表单、目录和附件按保留选项处理；XFA 与证书签名单独处理。",
                            engine = "手机离线 PDF 页面组合",
                            aliases = "合并 拼接 merge",
                        )
                    "compress" ->
                        entry.copy(
                            input = "PDF",
                            output = "压缩 PDF + 实际体积报告",
                            parameters = "目标大小、选页与压缩质量",
                            boundary = "保留原生文字和链接；无法达标会保留最小可用副本并说明。",
                            engine = "手机离线 PDF 优化",
                            aliases = "瘦身 size optimize 体积 缩小",
                        )
                    else -> entry
                }
            }
    val pdfTitles =
        linkedMapOf(
            "compress" to "压缩 PDF · 目标体积",
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
            "archive" to "PDF/A 归档校验与副本",
            "native-edit" to "编辑原生文字与字体",
            "redact" to "文字与区域脱敏",
            "encrypt" to "密码加密 / 权限",
            "forms" to "填写 PDF 表单",
            "certificates" to "证书签署与核验",
            "txt" to "导出纯文本 TXT",
            "md" to "导出 Markdown 文本",
            "html" to "导出 HTML 文本",
            "docx" to "导出可编辑段落 DOCX",
            "docx-layout" to "手机结构重建 DOCX",
            "pdf-pptx" to "PDF 转 PPTX 整页图片",
            "pdf-pptx-native" to "PDF 转 PPTX 原生对象",
            "docx-image" to "原页参考图 + 文字 DOCX",
            "optimize" to "图片优化与资源去重",
            "flatten" to "扁平化为图像 PDF（有损）",
        )
    private val pdfAliases =
        mapOf(
            "watermark" to "水印 watermark 印章",
            "number" to "页码 page 页数",
            "crop" to "裁边 crop 边距",
            "blank" to "空白 插页 blank",
            "nup2" to "拼版 二合一 2合1 nup",
            "nup4" to "拼版 四合一 4合1 nup",
            "metadata" to "属性 metadata 标题 作者",
            "diagnostics" to "诊断 属性 字体 资源 diagnostics",
            "archive" to "归档 PDF/A PDFA 校验 验证 字体 XMP archive veraPDF",
            "native-edit" to "原生编辑 文字 修改 替换字体 字号 native edit font",
            "redact" to "脱敏 删除敏感文字 区域遮盖 涂黑 redaction 隐私",
            "encrypt" to "加密 密码 password 权限",
            "forms" to "表单 acroform 填写 复选框 单选 下拉 XFA",
            "certificates" to "数字签名 证书 签署 验签 核验 P12 PFX PKCS12 certificate signature",
            "optimize" to "优化 体积 去重 optimize 图片",
            "flatten" to "扁平化 图像 图片 flatten",
        )

    fun conversion(mode: String) = conversions.firstOrNull { it.mode == mode }

    fun pdfMatches(mode: String, title: String, query: String) =
        conversion(mode)?.matches(query) ?: matches(query, title, mode, pdfAliases[mode].orEmpty())

    fun matches(query: String, vararg values: String): Boolean {
        val haystack = values.joinToString(" ").lowercase(java.util.Locale.ROOT)
        return query.trim().lowercase(java.util.Locale.ROOT).split(Regex("\\s+")).all {
            it in haystack
        }
    }
}
