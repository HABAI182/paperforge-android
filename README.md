# 纸匠 · PaperForge

原生 Android 离线扫描与 PDF 工作台，供个人非商业使用。无登录、广告或水印，应用不声明 INTERNET 权限。

当前版本 **0.6.0**，公开源码：[HABAI182/paperforge-android](https://github.com/HABAI182/paperforge-android)。安装包与源码见 [Releases](https://github.com/HABAI182/paperforge-android/releases)。旧 tag 和发布附件保留。

## 0.6.0 新增：全程只用手机

- 阅读器支持左右翻页、连续滚动、全屏、进度/缩放记忆、书签、暖纸与夜读。
- 转换中心统一格式入口；新增文字 PDF、图片互转、PDF WebP、分段长图、手机结构 Word 和整页图片 PPTX。
- 手机原生/扫描表格识别，有线/无线表、合并单元格、可选跨页连接；逐格校正后导出真正可编辑的 XLSX 或 CSV。
- Office 排版使用 **Office 手机引擎**，首次在手机安装对应 APK，之后转换在手机本机运行并自动返回纸匠，用户不需要电脑或网络。
- 首页更紧凑、工具可搜索，最近打开与内容修改分开；保留多选、直接删除和现有扫描/PDF功能。

普通手机优先安装发布页的 `arm64` 主 APK；需要 Office 排版时再安装同页的 `Office-Engine-arm64` APK。x86_64 用于相应设备/模拟器，通用主 APK 包含两种 ABI。引擎源码/运行时来源见 [UPSTREAM](office-engine/UPSTREAM.md)。

[详细计划](docs/roadmap/README.md) · [手机使用与实际范围](docs/roadmap/11-手机优先实施记录.md) · [本轮验证](docs/quality/READER-CONVERSION-0.6.0.md)

## 0.5.0 新增

默认改用神经边缘模型识别纸张，无候选时回退到现有定位方式。取景纸框使用双候选缓冲和平滑，设置可调平滑程度、切换确认帧数、自动拍摄阈值。默认阈值降至60%，模糊与亮度拦截仍默认关闭。

编辑页直接进入「曲面校正」，先看预览再调强度，保存后支持撤销/重做并保留原图。只针对新扫描链路做必要验证，未重复整套OCR或压力测试。见[本轮记录](docs/quality/SCAN-0.5.0.md)及[模型来源](docs/research/MODEL-INTEGRATION-0.5.0.md)。

## 0.4.0 已有功能

首页右上角齿轮进入个性化设置，可调扫描/拍摄/增强/OCR/导出/主题字体及首页快捷入口，支持设置JSON导入导出。最近文档和文档库点“多选”或长按，批量分享、收藏、分类、回收和直接删除；回收站可批量恢复。已有PDF可直接进入合并排序。

相机统一取景/分析/照片坐标，修复反复重绑和拍摄中断，沿用纸框后原图精修；加入MakeACopy离线定位模型与传统识边回退。质量与边界见[本轮扫描记录](docs/quality/SCAN-0.4.0.md)，APK研究见[静态分析](docs/research/CAMSCANNER-APK-ANALYSIS.md)。

## 从这里开始

- **扫描**：首页开始扫描，连续拍摄后自动裁边；可随时手动拍摄、点击对焦、重拍、调四角。编辑页上方直接提供自动裁边、OCR 和调节入口。
- **导图 / 图片识字**：首页分别提供入口；图片识字导入后直接识别。支持中英文离线 OCR、编辑校正、复制、TXT、可搜索 PDF。
- **PDF**：首页直接打开、合并或压缩。阅读器直接提供 OCR、转 Word、转图片和页面管理，工具箱可搜索、按类别筛选。
- **最近文档**：搜索名称与 OCR 全文；操作菜单包含收藏、分类、重命名、移入回收站、直接删除。直接删除只需一次明确确认；回收站支持恢复。

## 主要能力

- 多通道纸边评分、静态照片边缘精修、显示轮廓平滑；原图、裁切、滤镜和撤销历史保存在本地。
- 彩色去阴影、灰度、缩放自适应黑白、彩色/白板增强；去阴影强度、亮度、对比度和批量应用。
- 草稿 150 / 标准 220 / 高清 300 DPI，图像画质独立设置；A4/A5/Letter/原比例，证件双面 A4 合版。
- 真灰度 JPEG 与 1-bit Flate 黑白 PDF；普通压缩重编码合适的嵌入图片，保留原生文字、链接、表单和内容流。图像扁平化是单独命名的功能。
- 连续阅读、缩放、搜索高亮、跳页、缩略图；合并、抽页、拆分、页序、旋转、裁边、拼版、空白页。
- 中文水印、页码、图片包与嵌入图片提取；TXT/Markdown/HTML、可编辑段落 DOCX、原页参考图加段落 DOCX。
- 批注、画笔、手写签名、手动脱敏、AES-256 密码、基础 AcroForm；文件夹、标签、收藏、中文 FTS4 全文搜索、ZIP 备份/追加恢复。
- 可选防截图与系统设备凭据应用锁，默认关闭。它们不等同于文档存储加密。

## 质量证据与边界

[质量报告](docs/quality/QUALITY-REPORT.md)记录相同合成输入上的 v0.2/v0.3 对比、取舍和未完成项。[OCR 报告](docs/quality/OCR-BENCHMARK.md)区分固定标注四角与实际自动裁边流程。开发集结果不能当作真实照片或竞品准确率。

API35 无 GMS 模拟器已在飞行模式下运行内置中英 OCR；100 页草稿导出及取消已测。没有 ARM64 真机相机、国内 ROM、真实纸张弱光/反光、50MP 或电池/温升结论。

DOCX 有段落、原页对照和手机结构重建模式；结构模式可输出文字、表格和嵌入图片，复杂跨栏/脚注/裁剪对象不保证还原。手机表格和 Office 排版已接入。自动曲面判断、去手指/手写、超分、完整原生PDF编辑和证书签名等研究目标仍保留，状态见本次实施清单。

原图和草稿位于应用私有目录，卸载会丢失，请导出重要内容。普通 PDF 工具生成副本；手动脱敏和明确标记的扁平化生成图像 PDF。系统另存先生成完整本地文件；外部提供程序写入失败可重试。

## 构建

Android 8.0+（API26），target/compile36；JDK17、SDK Platform36、Build Tools35.0.0、Gradle8.13、AGP8.13.2、Kotlin2.1.20。新增 NDK28.2.13676358、CMake3.22.1；首次原生构建下载固定SHA256的官方MNN3.6.1源码。Android Studio 打开本目录并选择 JDK17，或：

```powershell
git clone https://github.com/HABAI182/paperforge-android.git
cd paperforge-android
# 设置本机 JAVA_HOME、ANDROID_HOME，或由 Android Studio 创建 local.properties。
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

macOS/Linux 使用 `./gradlew`。首次构建需下载依赖，已安装应用核心流程离线运行。默认 APK 包含 arm64-v8a 和 x86_64；可用 `-PpaperforgeAbis=arm64-v8a` 单独构建手机包。不支持仅32位 ARM。发布文件为开发签名的预发布包，可覆盖原开发签名版本；密钥不进入仓库。

基准脚本与固定种子见 `scripts/bench/`；私有照片放入被忽略的 `bench-private/`，不得提交个人资料。格式化使用 `scripts/format.ps1` 和 ktfmt0.54，历史格式整理位于独立提交。

## 项目索引

- [架构](docs/ARCHITECTURE.md)、[技术选型](docs/architecture/0001-architecture.md)
- [上游研究矩阵](docs/research/UPSTREAM_REFERENCE_MATRIX.md)、[审计](docs/research/OSS_AUDIT.md)、[许可](LICENSE_NOTICES.md)
- [测试矩阵](docs/TEST_MATRIX.md)、[隐私](docs/PRIVACY.md)、[发布检查](docs/RELEASE_CHECKLIST.md)
- [0.5.0 改动](docs/CHANGELOG-0.5.0.md)、[接续记录](docs/DEVELOPMENT_LOG.md)


构建可选 Office 手机引擎：先运行 `python tools/fetch_office_runtime.py`（从固定来源下载并校验运行时），再执行 `gradlew.bat :office-engine:assembleDebug -PpaperforgeAbis=arm64-v8a`。主应用独立构建不需要该运行时。运行时目录不进入源码包。
