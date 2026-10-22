# 依赖审计与决定

核对日期：2026-10-23。构建只从 Google Maven、Maven Central、Gradle 官方下载域取依赖/工具；SDK 包根据 Google 官方仓库 XML SHA-1 校验，Gradle distribution 根据官方 SHA-256 校验。

| 实际引入 | 版本 / 来源 | 许可 | 运行时下载 / 网络 | 维护与纳入决定 |
|---|---|---|---|---|
| AGP | 8.13.2 / Google Maven | Apache-2.0 | 仅开发构建下载 | 官方兼容 JDK17、Gradle8.13、API36.1，应用编译 API36 |
| Kotlin / Compose compiler | 2.1.20 / Maven Central | Apache-2.0 | 仅开发构建 | 固定成熟版本，编译插件同版本 |
| KSP | 2.1.20-2.0.1 / Maven Central | Apache-2.0 | 仅开发构建 | 与 Kotlin 对齐，生成 Room 实现 |
| Compose / Material 3 / Icons | BOM 2025.05.01 / Google Maven | Apache-2.0 | 无模型下载 | 原生中文 UI、深浅色、大字体基线 |
| Activity / Lifecycle | 1.10.1 / 2.9.0 / Google Maven | Apache-2.0 | 无 | 系统选择器、生命周期与 MVVM |
| CameraX | 1.4.2 / Google Maven | Apache-2.0 | 不依赖 Play 扫描模块 | 官方有更新版本，先固定完成真实设备回归 |
| ExifInterface | 1.4.1 / Google Maven | Apache-2.0 | 无 | 纠正旋转和镜像 |
| Room | 2.7.1 / Google Maven | Apache-2.0 | 无 | schema 导出，后续必须迁移 |
| WorkManager | 2.10.1 / Google Maven | Apache-2.0 | 默认本地任务 | 非无限后台承诺，长任务前台路径需后续 |
| OpenCV | 4.12.0 / Maven Central | Apache-2.0，另有第三方许可 | native 库随 APK | 官方预编译制品，arm64/x86_64，跟进漏洞/16KB 兼容 |
| PdfBox-Android | 2.0.27.0 / Maven Central | Apache-2.0 + 上游字体/数据声明 | 无 | 旧 PDFBox 移植版本，复杂/恶意 PDF 兼容与资源压力持续评估；不开网络，不伪造全格式支持 |
| ML Kit Chinese + Latin | 各 16.0.1 / Google Maven | ML Kit / Google API SDK 条款（非开源模型） | 模型已打包；SDK 可含指标代码，本应用移除网络权限 | 实际首次离线、无GMS测试通过后才标记可用 |
| Coroutines / Play task adapter | 1.10.2 / Maven Central | Apache-2.0 | Task 适配层不等于依赖 Play services 安装 | suspend await，处理取消 |
| AndroidX Test / JUnit | runner1.6.2、core1.6.1、ext1.2.1 / JUnit4.13.2 | Apache-2.0 / EPL-1.0 | 仅测试 | 不随主 APK 引入 JUnit |

参考但不引入代码：ClearPDF、zynkware scanner、shaunxiao scanner。未加入 GPL/AGPL 引擎。用户非商用指示不被扩张成免除第三方版权声明，也不触发额外购买商业 SDK 的流程。

传递组件的版本见 `docs/acceptance/dependencies.txt`（构建解析生成）；Google Play 标识的基础 Task/API 工件可被 ML Kit 传递引入，并不说明设备必须安装 GMS。只以无 GMS 实际运行结果判定本版离线能力。
