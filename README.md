# 纸匠 · PaperForge

原生 Android 离线扫描与 PDF 工作台。按个人、非商业用途开发。无登录、无广告、无水印；不声明网络权限。

源码仓库：[HABAI182/paperforge-android](https://github.com/HABAI182/paperforge-android)（私有）。

```powershell
git clone https://github.com/HABAI182/paperforge-android.git
cd paperforge-android
```

当前版本 **0.2.0**。安装包与源码在 [GitHub Releases](https://github.com/HABAI182/paperforge-android/releases)。版本 0.1.0 的 tag 与发布文件保留。

扫描/OCR 修复：弱对比纸边使用边缘与亮度分割组合识别；默认自动拍摄，照片与导图自动裁切，可一键重检。OCR 使用裁切旋转后的原色图，避免显示滤镜抹掉细字，识别过少时尝试去阴影，空结果明确提示。中文 PDF 字体映射已修正，搜索与复制使用统一汉字。

实际运行截图：[自动裁切](docs/acceptance/screenshots/v020-auto-crop.png) · [离线 OCR 结果](docs/acceptance/screenshots/v020-ocr-result.png)。验证环境为无 GMS、飞行模式 Android 15 / x86_64 模拟器；没有真实手机的相机质量结论。具体证据见 [TEST_MATRIX](docs/TEST_MATRIX.md)。

## 已接入的使用流程

- **扫描**：实时纸边、自动/手动连拍、点击对焦、闪光灯、变焦/曝光（按硬件支持）、低光提示；重拍替换，四角放大镜和透视校正。
- **增强与页面**：原图、自动增强、去阴影、灰度、黑白、彩色、白板；亮度/对比度、长按原图对比、批量套用、拖拽网格、复制/旋转/删除、双页中缝分割。
- **导出与 OCR**：A4/A5/Letter/原比例、证件正反面 A4 合版；内置中英文 OCR、单页/全部识别、文字校正/复制/TXT、嵌入中文透明文字层、JPG/PNG 图片包。
- **PDF 阅读与编排**：连续懒加载、缩放、跳页、缩略图、文字搜索高亮；合并顺序调整、拖拽页序、批量删除/旋转、抽页、逐页拆分、裁边、空白页、2/4 合 1。
- **PDF 工具**：中文水印、页码、元数据、JPEG/PNG 导出、嵌入图片提取、图像压缩、结构重写与实际体积反馈、扫描 PDF 离线 OCR。
- **批注与保护**：画笔、高亮框、矩形、中文文字、可复用手写签名；草稿自动保存，批注固定到副本；安全脱敏生成全新图片 PDF；AES-256 密码、密码导入解锁副本、基础 AcroForm 填写（含中文）。
- **文档库**：文件夹、标签、收藏、名称/标签/OCR 内容搜索、回收站与恢复；离线 ZIP 备份/追加恢复，保留原图、OCR、编辑历史和批注草稿。
- **文字转换**：TXT/Markdown/HTML、文字段落 DOCX。DOCX 为文字导出，不重建原排版、图片和表格。

## 本机与构建

- Android 8.0+（API 26）；目标 API 36。
- APK 包含 arm64-v8a 手机及 x86_64 模拟器，暂不支持仅 32 位 ARM 的设备。
- JDK 17、Android SDK Platform 36、Build Tools 35.0.0。
- Gradle 8.13（带官方 wrapper 与 SHA-256 校验）；AGP 8.13.2；Kotlin 2.1.20。

Windows / Android Studio：打开本目录，选择 JDK 17，让 Studio 安装 SDK，完成 Gradle Sync。终端：

```powershell
# 设置你自己的 SDK 路径，或用 Android Studio 自动生成 local.properties。
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME = 'C:\Users\你的用户名\AppData\Local\Android\Sdk'
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\gradlew.bat connectedDebugAndroidTest
```

macOS / Linux 使用 `./gradlew`。首次**构建**需要下载依赖；安装后的应用核心流程无需网络。

调试 APK 默认位于 `app/build/outputs/apk/debug/app-debug.apk`。交付版 APK、源码 ZIP、校验值及验收样本放在上一级交付目录。调试包使用开发签名；后续正式签名应由项目所有者保存私钥，不将密钥提交到源码。

## 当前边界

真实手机的相机、国内 ROM、弱光/反光纸张、100 页内存性能仍需独立实测。模拟器合成素材测试不能替代这些验收。

后续研发：曲面书页展平、去手指/去手写、超分与模糊恢复、表格结构/高保真 Office、原 PDF 文本对象原位编辑、证书签名、XFA、英文 UI。双页分割只分纸面，去阴影为本地图像算法；不宣称达到竞品 AI 修复效果。文档库搜索当前使用 SQLite 内容匹配，未建 FTS 索引。

外部 PDF 为工作副本。普通合并/抽页/编排保留原生 PDF 内容；图像压缩与安全脱敏会栅格化，另存新文件。手写签名是笔迹图层；密码权限的执行程度由外部阅读器决定。基础表单支持可编辑 AcroForm 字段，不处理证书签名或 XFA。

原图和草稿位于应用私有目录，卸载会丢失；请导出重要内容。系统提供程序写入不保证原子提交，完整 PDF 会先在本地生成并校验，保存失败可重试。

## 项目索引

- [架构与数据生命周期](docs/ARCHITECTURE.md)
- [技术选型与范围调整](docs/architecture/0001-architecture.md)
- [第三方审计](docs/research/OSS_AUDIT.md)、[许可声明](LICENSE_NOTICES.md)
- [隐私说明](docs/PRIVACY.md)
- [测试与设备矩阵](docs/TEST_MATRIX.md)、[开发日志与接续任务](docs/DEVELOPMENT_LOG.md)
- [发布检查](docs/RELEASE_CHECKLIST.md)
