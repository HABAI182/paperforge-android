package cn.paperforge.app.model

data class GuideTopic(
    val id: String,
    val title: String,
    val purpose: String,
    val steps: List<String>,
    val note: String,
    val destination: String,
    val destinationLabel: String,
    val capabilityMode: String? = null,
) {
    fun matches(query: String) =
        ToolCatalog.matches(
            query,
            title,
            purpose,
            steps.joinToString(" "),
            note,
            capabilityMode.orEmpty(),
        )

    val capability
        get() = capabilityMode?.let(ToolCatalog::conversion)
}

/** Bundled help, aligned to executable routes; no website or network dependency. */
object UsageGuide {
    val topics =
        listOf(
            GuideTopic(
                "capture",
                "扫描与裁边",
                "把纸张拍成整齐的多页文档。",
                listOf(
                    "打开工作台 → 扫描与识字 → 扫描纸张，或选择图片导入。",
                    "拍摄后左右滑动检查各页；在「裁切」中自动识边或拖动四角。",
                    "使用「继续拍」追加页面，完成后点「导出」。",
                ),
                "拍摄时让纸张四周留一点背景，避免反光；草稿自动保留，可稍后继续。",
                "capture",
                "前往扫描与识字",
            ),
            GuideTopic(
                "filters",
                "增强预设与原图对比",
                "让文字更清晰，去掉灰底或阴影。",
                listOf("在扫描编辑页点「增强」。", "普通文档选自然；浅字试柔和黑白或灰度；阴影选强力去背景；薄纸练习册选练习册。", "点「对比原图」检查笔画，满意后可「应用全部」。"),
                "柔和黑白卡片保留柔和预设；彩色印章或照片优先用自然/彩色。强度可在原有调节入口更改。",
                "capture",
                "前往扫描与识字",
            ),
            GuideTopic(
                "ocr",
                "OCR 与可搜索 PDF",
                "提取扫描文字，或为 PDF 添加可搜索的文字层。",
                listOf(
                    "扫描编辑页点「识字」，检查识别结果及语言。",
                    "已有扫描 PDF 从工作台 → 扫描与识字选择「扫描 PDF → 可搜索 PDF」。",
                    "按需要复制文字、校正结果或导出。",
                ),
                "扫描导出默认不运行 OCR；需要搜索文字时再启用。低清、手写或复杂版面建议对照原页核对。",
                "capture",
                "前往扫描与识字",
                "searchable",
            ),
            GuideTopic(
                "enhance-pdf",
                "PDF 二次增强",
                "已经保存或导入的扫描 PDF，也能重新增强底图。",
                listOf(
                    "打开 PDF → 工具 → 优化与属性 → 图像增强，也可搜索「增强」。",
                    "选预设与当前页、选页或整篇，预览后切换「原图 / 增强后」。",
                    "点「生成增强副本」，结果会保留原件并单独保存。",
                ),
                "保留原生/OCR 文字、矢量、页尺寸、批注和表单。数字签名文件不能直接改写；小图、内联和透明图像保留，超大底图可能按内存预算采样。",
                "pdf/optimize",
                "前往 PDF 优化工具",
            ),
            GuideTopic(
                "pages",
                "合并与拆分页面",
                "把多份 PDF 组合起来，或保留所需页。",
                listOf(
                    "在工作台 → PDF 工具 → 页面编排选择合并、页面组合或逐页拆分。",
                    "选择文件并调整顺序；选页可写 1-3,5。",
                    "生成后检查页序，再保存或分享副本。",
                ),
                "页码从 1 开始。原生文字与页面对象通常会保留；整页重绘等有损工具会单独说明。",
                "pdf/pages",
                "前往页面编排",
            ),
            GuideTopic(
                "compress",
                "压缩与目标体积",
                "减少 PDF 大小，便于发送或归档。",
                listOf("打开 PDF → 工具 → 优化与属性 → 压缩 PDF。", "选择画质，或输入目标大小。", "检查实际大小与画面，再保存生成的副本。"),
                "主要压缩嵌入图像。已经很小的文件可能无法进一步缩小；未达到目标时会显示实际结果。",
                "pdf/optimize",
                "前往 PDF 优化工具",
                "compress",
            ),
            GuideTopic(
                "convert",
                "格式转换与表格",
                "在 PDF、Office、图片、表格和电子书之间转换。",
                listOf(
                    "打开工作台 → 格式转换，按文件类型选择分类。",
                    "选择文件，按实际需要设置输出格式、画质或版面模式。",
                    "完成后预览结果；表格可在工作台中校正行列和单元格。",
                ),
                "复杂字体、宏、外链及特殊排版可能影响结果；保留原稿，关键文档先核对。CSV 不保留样式和合并单元格。",
                "convert",
                "前往格式转换",
                "docx-layout",
            ),
            GuideTopic(
                "reader",
                "阅读、全屏与翻页",
                "放大检查扫描效果，按习惯阅读文档。",
                listOf(
                    "打开 PDF，双指缩放或双击放大页面。",
                    "点底栏「视图」，选择左右翻页、连续滚动、页面适配或全屏。",
                    "全屏轻触页面显示工具；系统返回先退出全屏。",
                ),
                "放大后单指移动页面；需要翻页时可先缩回。书签、目录和文字朗读在「工具 → 阅读与导航」。",
                "documents",
                "前往文档",
            ),
            GuideTopic(
                "save-backup",
                "保存、备份与恢复",
                "把结果保存到手机目录，并保留可恢复的资料。",
                listOf(
                    "任务完成后在结果页选择保存或分享；默认目录可在设置 → 导出与文件名调整。",
                    "工作台 → 文档整理 → 离线备份，生成包含原图、PDF、OCR 和编辑历史的 ZIP。",
                    "把 ZIP 另存到可靠位置；需要时用同分类的「恢复备份」导入。",
                ),
                "应用内文件与另存副本是不同位置。回收站仍可恢复，永久删除不可撤销；卸载前先把备份保存到应用外。",
                "organize",
                "前往文档整理",
            ),
        )

    fun search(query: String) = topics.filter { it.matches(query) }
}
