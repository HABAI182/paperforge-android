# 纸匠 · PaperForge

原生 Android 离线扫描与 PDF 工作台。按个人、非商业用途开发。无登录、无广告、无水印；不声明网络权限。

源码仓库：[HABAI182/paperforge-android](https://github.com/HABAI182/paperforge-android)（私有）。

```powershell
git clone https://github.com/HABAI182/paperforge-android.git
cd paperforge-android
```

这是第一轮可执行切片，优先实现需求中的 M0 → M1，并加入部分 M2 / M3。完整矩阵与真实测试结果分别见 [PRODUCT_SPEC](docs/PRODUCT_SPEC.md) 和 [TEST_MATRIX](docs/TEST_MATRIX.md)。未完成能力不会以空按钮充数。

验收结果：**8 项单元测试 + 7 项 Android 模拟器测试通过，Lint 0 errors（11 warnings）**。模拟器为无 GMS、飞行模式的 Android 15 / x86_64；没有真实手机测试结论。已在界面实际完成拍摄、四角调整、导出、系统另存和分享面板。

实际运行截图：[首页](docs/acceptance/screenshots/01-home.png) · [相机](docs/acceptance/screenshots/03-camera-preview.png) · [裁切](docs/acceptance/screenshots/05-crop-adjusted.png) · [PDF 预览](docs/acceptance/screenshots/08-reader.png)。相机图像是 Android 模拟器测试画面，不用作纸张扫描质量证明。

## 已接入的使用流程

- **扫描**：首页「扫描文档」→ CameraX 取景、纸边覆盖、手动或稳定后自动拍摄 → 连拍 → 完成。
- **导图**：「相册导入」或系统图片分享 → 自动检测纸边 → 拖动四角、透视校正、旋转、五种滤镜与亮度/对比度。
- **草稿**：Room 保存原图路径、页序和参数；离开界面后可从文档库继续。原图不被滤镜覆盖。
- **导出**：调整页序、命名 → 导出 PDF → A4/A5/Letter/原比例、JPEG 质量 → 后台生成 → 预览、系统另存或分享。
- **PDF**：导入私有副本，懒加载连续阅读、双指缩放、跳页、合并、按 `3,1-2` 顺序抽页、旋转、提取原有文字。
- **图像压缩**：明确确认后栅格化为图像 PDF；显示输入与输出的实际大小，源文件保留。
- **OCR**：扫描编辑页「识文字」使用内置中英文模型；可校正、复制、另存 UTF-8 TXT。当前导出的扫描 PDF 仍为图像 PDF。

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

## 第一版边界

真实手机的相机、国内 ROM、弱光/反光纸张、100 页内存性能仍需独立实测。模拟器合成素材测试不能替代这些验收。

尚未实现：带中文透明文字层的可搜索 PDF、拖拽网格调序（当前使用前后移动）、书本展平、去手指/去手写、签名批注、密码工具、表单、Office 转换、全局 OCR 索引、回收站、备份、英文界面切换。自动增强目前为灰度局部对比度，不宣称 AI 去阴影或彩色保真增强。

外部 PDF 为导入的工作副本，文档库会明确标识。合并按文件选择器返回的顺序执行；可用后续抽页操作调整页序。压缩是明确标识的有损图像模式；普通抽页/合并保持 PDF 对象，不通过整页截图替代。

原图和草稿位于应用私有目录，卸载会丢失；请导出重要内容。系统提供程序写入不保证原子提交，完整 PDF 会先在本地生成并校验，保存失败可重试。

## 项目索引

- [架构与数据生命周期](docs/ARCHITECTURE.md)
- [技术选型与范围调整](docs/architecture/0001-architecture.md)
- [第三方审计](docs/research/OSS_AUDIT.md)、[许可声明](LICENSE_NOTICES.md)
- [隐私说明](docs/PRIVACY.md)
- [测试与设备矩阵](docs/TEST_MATRIX.md)、[开发日志与接续任务](docs/DEVELOPMENT_LOG.md)
- [发布检查](docs/RELEASE_CHECKLIST.md)
