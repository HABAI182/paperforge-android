# #11 · 完整包体积优化

ARM64 完整 APK 从 **177,193,373 → 155,992,001 字节**，减少 **21,201,372 字节（11.97%，约 21.20 MB）**。保持单一 ARM64 架构和全部原生引擎，没有改为下载模型/Office 运行时。

对照源码为合并 #8 后的 `6052457b3b09a97b692d07833b35e5f28026cf98`。另记录公开 v1.0.1 的 177,132,921 字节旧包，避免把前五个 Issue 增加的代码误算成优化损失。所有 MB 均为十进制。

## 实际组成

以下为 ZIP 中压缩后的有效载荷，不含 ZIP 目录、签名与对齐开销；完整明细、原始字节、最大条目和重复资源见 [优化前](before-sizes.json) / [优化后](after-sizes.json)。

| ARM64 模块 | 优化前字节 | 优化后字节 |
|---|---:|---:|
| Office native | 73,764,438 | 73,764,438 |
| DEX | 25,662,099 | 12,304,127 |
| 离线模型 | 18,506,727 | 17,241,547 |
| 字体 | 18,153,388 | 11,836,648 |
| 扫描 / ONNX / OpenCV native | 14,129,892 | 14,129,892 |
| ML Kit native | 11,185,172 | 11,185,172 |
| MuPDF native | 5,496,846 | 5,496,846 |
| Java 资源 / 元数据 | 3,426,028 | 3,437,495 |
| Office assets | 2,336,407 | 2,336,407 |
| 其他 assets | 2,303,343 | 2,303,931 |
| Android 资源 | 1,744,866 | 1,469,991 |
| 其他 native | 202,439 | 202,439 |

DEX 原始大小从 79,798,616 降至 31,932,188 字节。x86_64 验证包从 187,140,050 降至 165,938,754 字节。

## 实施文件

- `full-app/build.gradle.kts`：release 默认启用 R8 与资源收缩；移除模型 noCompress。保留 `-PpaperforgeOptimizeFull=false` 供定位比较；与历史源码比较时仍须使用上述对照提交。
- `full-app/proguard-rules.pro`：保留 LibreOffice/MuPDF JNI、veraPDF/JAXB/Xerces 与 Rhino 反射边界；保留跨模块和独立测试 APK 所需的公共运行时 ABI。Rhino 的 Swing 桌面工具不保留。未使用的图标工厂及依赖实现继续裁剪。
- `full-app/test-proguard-rules.pro`：独立配置测试方法/规则和可选 Window 扩展类型，避免把测试专属规则混入主包。
- `office-engine/bundled.gradle.kts`：用生成的 Sync 资产目录过滤重复 CJK 字体；直接对 Android assets 设置 exclude 未生效，因此改在合并前过滤，APK 内实际确认仅一份。
- `office-engine/.../OfficeRuntime.kt`：首次 Office 排版从主包的 canonical 字体准备用户字体目录；已有字体继续可用。
- `tools/fetch_office_runtime.py`：独立 Office 包也采用一个 canonical 字体位置，移除旧生成位置。
- `scripts/build_distribution.py`：完整 release 候选同时保存 R8 mapping/configuration ZIP、大小和 SHA-256，方便定位以后发生的崩溃；这不是另一个安装包。
- `tools/apk_size_report.py`：按运行时归类实际 ZIP 字节、原始字节、ABI、最大条目、重复资源和 SHA-256。
- `FullPackageFlowTest.kt` / `ShrunkRuntimeTest.kt`：验证首次共享字体安装、压缩资产读取、两种 MNN、ORT、双 OCR 与完整转换链路。

扫描模型均通过流读取或复制到缓存文件再加载，不依赖 APK 中的未压缩文件描述符。模型原始字节未改变，压缩减少约 1.27 MB；删除第二份 CJK 字体减少约 6.32 MB。

剩余 LiberationSans/CMap 和 XML catalog 的重复资源分别由资产加载器、classpath 或不同 URI 路径读取，未直接删掉。Office native 占比最高，当前完整 Writer/Calc/Impress 能力需要它；本轮保留，不增加首次下载依赖。更进一步减少它需要另行重编译 LibreOffice 的模块组合。

## 验证结果

- [release 构建与 lintVital](release-build.log) 通过；[ARM64 正式打包脚本](arm64-package-build.log) 通过，同时生成 mapping ZIP 和校验清单。Python 工具语法检查通过，体积工具已用于真实 APK。
- 基线 DOCX 中文/数字排版、SVG 转换、PDF/A 校验：[1 项通过，4.136s](baseline-conversion-test.log)。
- 裁剪后 [8 项回归记录](regression-before-rhino.log)：其中 7 项通过，包含压缩模型首次读取、两种 MNN、ORT、中文/拉丁离线 OCR、扫描增强及 PDF 导出、PDF 图像增强与队列恢复、阅读工具预览、TLS 及真实 WebView 上传。
- 综合转换用例发现 Rhino 的 VMBridge 反射实现被裁剪；补齐核心反射规则后仅复验该失败用例：[1 项通过，1.485s](archive-fixed-test.log)。DOCX 2 页中文/数字、共享字体 SHA、SVG 1 页、veraPDF `NORMAL` 和共享文件提供器均通过：[conversion.json](conversion.json)。其余 7 项未因这一局部保留规则重复运行。
- [runtime.json](runtime.json) 记录实际 `docquad-CORNERS`、两种 MNN 与两种 OCR 结果；不只是确认模型文件存在。
- [payload-audit.json](payload-audit.json)：ARM64/x86_64 各原生库的条目、字节 SHA 与优化前完全一致；ELF machine 与 ABI 一致；离线模型/元数据和 canonical 字体字节一致。ARM64 仅包含 ARM64；签名沿用原身份，ZIP 对齐检查通过。
- 实际运行环境为 API35 x86_64 模拟器，完成覆盖更新、启动及功能运行；ARM64 做了打包、签名、ABI、ELF 和载荷核对，未连接 ARM64 真机。

## 安装占用与启动

[device-metrics.json](device-metrics.json) 使用同一模拟器、保留现有用户数据，`cmd package compile -m verify -f` 后统计 APK + 已提取 native + oat 的安装代码目录，排除用户文档和缓存：**556,004 → 487,712 KiB**，减少 **69,931,008 字节，约 69.93 MB**。运行时字体与用户文档不计入该代码目录比较。

相同保留数据的三次冷启动，优化前 954/951/955ms，优化后 928/918/952ms，中位数 954→928ms。样本少且来自模拟器，只报告原始记录，不宣称有统计上可靠的启动加速。

产物是验证用候选，未覆盖或发布现有 v1.0.1 Release；后续发行需要独立版本号。配置依据 [Android 官方 R8 优化说明](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization) 和 [keep 规则说明](https://developer.android.com/topic/performance/app-optimization/keep-rules-overview)。
