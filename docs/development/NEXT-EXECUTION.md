# 下一代完整计划执行

用户要求：先补齐完整计划，再统一发布大版本。开发提交继续保存到私有仓库；完成前不创建新 tag 或 Release，不关机。当前发布仍是 0.6.0，内部 `0.6.1-dev` 只用于开发装机。

原始 98 项逐项保留在 `docs/roadmap/backlog.json`，实际实现说明见 `implementation-status.json`。两份状态同步，不因文档撰写或不支持的占位入口而标为完成。Claude 评审中的国际化、设置类型、代码拆分、CI、真照片集和诊断等补充建议也属于执行范围；它们与原计划有重叠，逐组记录，不混作 98 项之外已经自动完成。

## 已落实的功能组

1. 扫描后大画布、横向翻页、缩放、全屏、固定回收站、真实文档缩略图、视觉字阶和表面，以及清晰/去阴影增强改进。
2. 页面操作的完整快照撤销；裁切角点/整边直接拖动、放大镜、比例；可移动书脊的非对称双页分割；格式2完整性备份及阅读/书签/设置/摘录恢复。
3. 原生大纲/页码标签/链接；原页长按文字选择、补识别扫描区域的入口；可回源页的摘录、Word/MD/Anki TSV；离线系统朗读接口与声音缺失提示。
4. 阅读可见分块、焦点缩放、页边二次翻页；按文档保存的显示裁边/适宽适页、图片原色；宽屏面对页/封面与RTL，横竖切换保留物理页。验证见 `docs/acceptance/next/reader-display-summary.json`。
5. 曲面输入/逆映射/模型来源持久保存与联动对照；格式3备份资源重映射，恢复失败完整回滚；存储分类与引用保护的缓存清理。验证见 `docs/acceptance/next/resources-summary.json`。
6. 文档库独立打开/修改排序、全文命中片段与页定位、智能集合与保存筛选；持久输出记录、外部保存位置、派生结果单独收纳。验证见 `docs/acceptance/next/library-summary.json`。
7. 文档原始密文和额外附件也有持久引用清单，随完整备份重映射恢复；包含密码重新打开原件的验证，见 `docs/acceptance/next/document-resources-summary.json`。
8. 50张公开真实照片定位并修复自动模式跳过MNN、错选背景的路径，见 `docs/acceptance/next/real-photo/README.md`。这不是标注精度或机主实拍验收。
9. 快门前持久意图、原图草稿、串行识边、页序预留、失败/取消重试、手动编辑保护和重拍冲突保全；任务中心及备份任务恢复。集中验证见 `docs/acceptance/next/capture-tasks-summary.json`，另实际走通模拟器CameraX手动拍摄回调。

实际验证与限制见 `docs/acceptance/next/core-reader-summary.json`。实体 ARM64、真实纸张标注集、实际朗读声音和竞品同图比较尚未完成。当前只验证模拟器可证明的内容。

10. 分组命名配置档案、五种扫描模式、倒计时和相机独立高级面板；全画面翻动后稳定的相似页重触发。配置与状态时序检查见 `docs/acceptance/next/profiles-summary.json`，真实手持翻页仍保留待验收。

11. OCR快速/均衡/精细分路，局部疑难补识别、可关闭投票及真实尝试记录；编辑页识别遵循语言设置。清晰20行样张快速/均衡各仅一次原图调用，见 `docs/acceptance/next/ocr-options-summary.json`。

12. 原始/自动/用户校正独立持久记录；记录回查、选用旧文字、校正与重识别撤销，资源图/备份保留。修复任务ID重映射覆盖还原资源路径的问题，验证见 `docs/acceptance/next/ocr-records-summary.json`。

13. 扫描页区域OCR和同屏逐行/逐词校对、缩放定位、选区外文本保留与审计撤销；触摸起点和选区布局稳定性修复。集中原生检查与真实UI操作见 `docs/acceptance/next/ocr-region-summary.json`。

14. 文档多选命名模板、字段和补零序号、重名自动编号及最终预览；应用后可撤销，后续手工名称保留，备份后的撤销只作用于副本。中文/Emoji长文件名验证见 `docs/acceptance/next/library-naming-summary.json`。

15. 多选页跨扫描文档复制/移动、原图与OCR审计记录独立保留、来源定位、双文档联动撤销与备份重映射；新增后来拍摄页不被撤销删除。目录分级浏览、面包屑和子目录筛选，批量可分别清空/保留分类与标签。修复资源发布成功后取消导致误清理、关联重做分支和已删除来源的历史资源清理；验证见 `docs/acceptance/next/page-transfer-summary.json`。

16. 主动内容比较：扫描原件/处理/文字及PDF原件哈希分组、相似页并排切换、回源后返回、忽略候选；多页匹配折叠到文档对。版本组有首选、重命名、合并、移出和解散，跟随备份恢复为独立副本。发现结果不会自动删页/删文档。集中检查及实际UI见 `docs/acceptance/next/library-comparison-summary.json`。

17. 跨PDF页面组合工作台、来源选页/拖动与精准移页、正反面反序交错、空白/复制/旋转/局部反序、封面和分页可点击目录；保留原生页内容、资源、裁切和旋转，内部选中页目标重绑、URI保留，未选页目标移除。草稿有撤销重做/持久保存/备份重映射；文件清单驱动真实WorkManager任务、输出来源记录与预览返回。该阶段外观模式的初始验证见 `docs/acceptance/next/assembly-summary.json`。

18. 页面组合、合并、抽页和重排统一保留普通可编辑表单；同名字段和重复页独立填写/外观、原目录/精确目标、源页标签及嵌入/关联附件可配置。原件不变，集中检查与独立MuPDF复读见 `docs/acceptance/next/preservation-summary.json`。XFA与证书签名继续归PDF10。

19. 标准高亮/下划线/删除线、文字、便签、图章、手绘和矩形，支持可编辑/固定导出；阅读文字逐行标记，批注列表编辑/删除/撤销重做和原子草稿。旋转中文字及便签外观在独立渲染时发现并修正，验证见 `docs/acceptance/next/native-annotations-summary.json`。

20. 持久任务中心扩展扫描导出、PDF工具/转换、整本文字OCR与外部保存；后台继续、逐项取消/重试/结果、OCR检查点和备份重映射。外部写入后核对SHA256/长度，失败保留本地成品；排队组合引用的已删除来源也能随备份恢复继续导出。详见 `docs/acceptance/next/document-jobs-summary.json`。Phone Office/表格及中间PDF/ZIP的页级恢复仍在JOB01后续范围。

21. 独立只读PDF属性与诊断页、逐页定位、文字/资源/字体/表单/附件/签名记录概况及JSON报告；不阻塞普通阅读。集中检查与原件哈希见 `docs/acceptance/next/pdf-diagnostics-summary.json`。资源图像不含内联图像；证书/PDF-A仍由PDF10/PDF06处理。

22. 后台PDF/扫描导出/OCR重流水线限一条，交互OCR可在原生pass之间优先排队；阅读继续使用独立有界分块缓存。排队取消与重试不泄漏槽位，验证见 `docs/acceptance/next/runtime-budget-summary.json`。这不是单次操作分配上限或真机流畅度验收，JOB03仍部分完成。

23. 基础文字转PDF的原件/可编辑分块草稿/续编/TXT另存及纸张/方向/字号/边距/行距；图片互转的质量/尺寸/透明/底色/无损WebP与逐项缓存、部分结果报告、失败重试。修正启动恢复覆写完成状态和中文字体户→部首的复制映射错误；原生检查、独立MuPDF/Pillow和实际重启续编见 `docs/acceptance/next/basic-conversion-summary.json`。Office/表格和复杂转换的剩余任务保持未完成。

24. PDF按选页顺序与多张图片直接拼长图；自定义宽度/间距/分段/底色/格式与质量，连续分段和源行映射。8种EXIF方向、12000px长图末行和独立Pillow复读见 `docs/acceptance/next/long-images-summary.json`。这里完成CVT05顺序长图；SCN05重叠接缝另见下文第29组。

25. 目标体积压缩支持自定大小、最多7次质量搜索和真实达标提示，保留原生内容；不能达标时保留最小可用副本，含签名记录按字节保留。报告随结果保存，独立复读和实际UI见 `docs/acceptance/next/pdf-target-summary.json`。

26. 阅读链接与目录保留精确目标，连续滚动与多次跳转支持返回原页缩放/平移/滚动，历史跨界面重建恢复；顶栏直接目录入口。真实画布两次跳转和连续返回检查见 `docs/acceptance/next/reader-anchors-summary.json`。

27. 桌面直接扫描/图片识字/继续阅读、快捷设置扫描按钮和继续阅读小组件，设置可请求固定入口；小组件跟随上次页码并遵守应用锁显示。实际Android组件、图像选择器和系统Tile检查见 `docs/acceptance/next/system-entrypoints-summary.json`。

28. 首页可保存集合，列表/封面网格、列数与密度支持自定义和档案；缩小快捷入口、固定导入、保留多选及全部文档操作。重启保留/全选切换/200%首页检查见 `docs/acceptance/next/personal-layout-summary.json`。平板折叠及全应用可访问性保留在UI08。

29. 长票据支持顺序/重叠、自动平移匹配与手动接缝、叠加预览、持久顺序及分段源行映射；编辑扫描和多图导入均可进入。水平偏移扩展画布保边，完成任务可另开设置副本。集中检查与真实UI见 `docs/acceptance/next/receipt-stitch-summary.json`；强透视/曲面先裁切，不宣称任意照片自动配准。

30. 官方 PaddleOCR v5 Mobile 以可导入独立包接入，保留内置 ML Kit 默认；手机可切换/查看来源/移除，任务固定识别配置，缺包后明确提示和当前设置重试。集中检查、实拍路牌小样本对比和实际界面见 `docs/acceptance/next/paddle-ocr-summary.json`；模型包来源和手机使用见 `model-packs/README.md`。不据一个样本宣称普遍识别领先。

31. OCR schema4 统一页/块/段落/行/词/单元格与顺序，保存来源图像SHA和处理几何版本；共享变换同步文字及表格，复制/历史恢复重绑修订，校对页可导出结构JSON。集中变换/过期缓存/校正/撤销与旧格式检查见 `docs/acceptance/next/ocr-ir-summary.json`；表格实际检测仍单独归OCR07。

32. 表格分析/导出接入持久队列和逐页检查点；原件/校正草稿保留，真实区域选择、原图点格和放大对照，单元格/列类型、CSV/TSV编码分隔及Excel文本兼容复制。修复圈选漏格与原生页眉使扫描表跳过的问题；横纵合并、Paddle逐格、独立openpyxl/csv与实际导入/续编/导出见 `docs/acceptance/next/table-workspace-summary.json`。

33. Markdown/HTML结构互转、资源ZIP、原件/实际结果预览和系统打印PDF；保留图片字节、脚注、代码及表格编号，合并表格/样式明确回退。修复Android路径别名与打印文件名，集中检查和真实保存后的独立PDF复读见 `docs/acceptance/next/markup-conversion-summary.json`。转换搜索置顶，草稿摘要折叠。

34. EPUB按spine读取章节，EPUB3目录/EPUB2 NCX、封面/脚注内链与静态结构文本互转；标题分章生成标准EPUB3。PDF原生文字/扫描OCR重排、原页和原PDF回源，实际5页系统PDF、原页2/2跳转与独立EPUBCheck见 `docs/acceptance/next/epub-conversion-summary.json`。复杂版式可看原页；系统打印的内部点击目录限制已记录。

35. 首页/转换/PDF工具箱统一搜索别名、分类和星标固定项；首页可直接启动具体转换，31条转换能力说明保留真实输入/输出/参数/引擎及模式限制。Word/OCR/图片搜索、固定恢复/界面重建和实际EPUB分发见 `docs/acceptance/next/tool-discovery-summary.json`。

36. 纸张/DPI/质量/OCR/格式/文件名/目的地组合成导出预设，支持命名保存与一键套用；PDF和JPG/PNG包按真实纸张几何输出，系统目录授权仅留本机。目录保存/同名编号/哈希复读及重启记忆见 `docs/acceptance/next/export-presets-summary.json`。自动导出规则仍归JOB05。

37. 两份PDF的原生文字/外观/增删页比较进入持久任务，双栏同步缩放和差异源区域回跳；忽略设置和逐项核对可保存，导出含原件/图片/HTML/JSON的报告包。真实界面选择/回跳/第3组返回及独立复读见 `docs/acceptance/next/pdf-comparison-summary.json`。复杂插页可用起始页偏移分段核对，不宣称自动语义配对。

38. Phone Office转为保留原件的后台CONVERT任务，同签名独立服务通过文件描述符接收输入/输出；取消及空闲释放引擎进程，原生转换段后的结果检查点可复用，另存不重新排版。真实DOCX两页/中文编号金额、实际任务中心结果和独立复读见 `docs/acceptance/next/office-job-summary.json`。JOB01其余复杂阶段及JOB03峰值/实机预算仍未完成。

39. 工作流配方支持10种真实步骤、类型匹配/排序/参数/命名保存复制，按输入SHA和参数版本保留阶段；失败位置与已有结果可回到编辑器，重选保存目录仅执行保存。旧发布结果按版本保留，补文字层固定OCR配置。真实扫描件OCR/改目录复用/系统目录完成及独立复读见 `docs/acceptance/next/workflow-recipe-summary.json`。条件触发自动执行仍归JOB05。

40. 自动导出默认关闭，支持标签/文件夹条件、配方副本、独立目录与扫描导出参数；源变化/规则周期去重，失败留结果等待手动续跑，输出不回流触发。真实条件触发/排除/失败去重/复用与界面启用后的实际文件保存见 `docs/acceptance/next/auto-export-summary.json`。不将云盘断网、实机后台及完整故障矩阵作为已验证结论。

41. 原页长按后可拖动字符/词簇手柄，复制、多栏顺序、整行/全页选择和逐行高亮；区域离线OCR映射回原页，无词框则按实际行框选择。真实拖动/剪贴板/圈选、旋转裁切坐标和独立PDF高亮复读见 `docs/acceptance/next/reader-selection-summary.json`。

42. 阅读器独立全屏重排，段落按字号/行距换行，原嵌图和有线表格合并格保留区域锚点及原样对照；源页回跳接入原阅读位置返回，按文档保存进度并随备份映射。真实界面、合并表格/像素和独立复读见 `docs/acceptance/next/reader-reflow-summary.json`。复杂自由版式仍可直接回原页。

43. 系统离线朗读支持搜索声音、保存语速、选段与段落导航；按真实词回调或实际播放句段高亮，支持暂停续读与退出停止。修复中文系统的英文分句，实际 eSpeak 服务、中英文 PCM/WAV 及界面选声/跟读见 `docs/acceptance/next/offline-speech-summary.json`；引擎只作为模拟器独立软件安装。

44. 独立手机格式引擎支持 OFD / DjVu 到 PDF / PNG 页包，嵌入字体、物理纸张、旋转、页序和可用的原始文字坐标；任务保留原件、逐页缓存、版本化结果及备份重映射。修复上游预览缩放上限、中文复制映射和预览返回位置，合成样本及真实五页签章 OFD 的有限实证见 `docs/acceptance/next/layout-conversion-summary.json`。印章外观与证书验证明确区分，不发布外部票据原文或图像。

45. 相机三路显式统一方向，显示方向/取景尺寸变化时重绑同一视口、清空旧候选和对焦状态；横屏按钮移到侧栏，恢复大取景区，档案长名称不再挤压倒计时。实际 CameraX 四方向 JPEG、EXIF 1/3/6/8、独立纸边及四角颜色保全见 `docs/acceptance/next/capture-geometry-summary.json`。此组证明模拟器相机链路，实体手机运动/重触发与光学质量仍单独保留。

46. OCR 导出仅修正本次新增字体的 ToUnicode 映射，保留全角编号/圈号与 ActualText；文字基线按字体度量贴合水平/旋转框。原页选择、搜索及原生覆盖统一用裁切后的文字渲染矩阵，修正方向混用与框高偏小；四组坐标和独立 MuPDF 结果一致，原生字体/原件/可见像素保留，见 `docs/acceptance/next/ocr-pdf-geometry-summary.json`。阅读与流程缓存包含新版本。系统栏同步应用明暗设置，相机深色表面也请求清晰图标；这不单独宣称全应用适配完成。

## 后续执行顺序

页序历史补充：重排只记录页面ID，删页/分割仅记录内容差异；新旧可读快照的撤销保留后来新增的扫描页和与操作无关的新OCR。备份对独立页序ID重映射，集中检查见 `docs/acceptance/next/sparse-history-summary.json`。不将此修复重复计为新完成的计划卡。

- 阅读：原页选择、带源锚点的单栏段落/图片/有线表格重排及系统离线朗读已经落实，继续随剩余界面适配改进；实体手机音质与复杂自由版式质量保持单独验收。已完成的分块/页边翻页/原色/裁边/面对页不再重复实现。
- 扫描：捕获几何与相似页重触发、相机反馈精调；去手指/手写/反光等按实验卡真实比较。取景候选和成片核对、持久草稿队列已接入，仍保留跨旋转实拍验收。
- 文档库：备份的配方与恢复重复策略。跨扫描文档复制/移动、分级分类、内容比较/版本组、命中片段/智能集合/容量/回滚/原件引用已经落实。
- OCR：竖排/脚本、外部复制几何；有线表格及区域工作台已接入，结构IR已统一，第二引擎和模型包已完成本地可选链路，继续扩充实拍质量样本。
- 转换：参数、批量逐项重试、真实结果对照、结构文本与EPUB、Office/特殊格式按实际引擎补齐；表格区域及类型导出已经提供。
- PDF：语义脱敏、归档验证、限定对象编辑、证书签名和高级表单；页面组合基础工作台已经落实。
- 工作流：继续补齐中间PDF/ZIP页级恢复（Phone Office、表格已接入持久任务）、资源优先级/内存预算和可选独立网络扩展；配方、条件自动导出、配置档案与导出目录记忆已落实。
- 工程：国际化、类型化设置、功能拆分、私有CI、包体与签名渠道；最后核对全部能力和证据，再统一发布大版本。

研究能力必须有实际原型与结果；未验证的实验不开伪装成功的入口。个人手机的实拍质量与模拟器/公开样张始终分开记录。
