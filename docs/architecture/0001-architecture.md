# ADR 0001 — 本地原生扫描切片

日期：2026-10-23。状态：已采用。

1. **项目定位**：遵循用户非商用补充，不建设商业支付、登录、云端或许可购买流程。仍保留依赖声明和原有版权文本。
2. **版本**：使用已核对兼容的 JDK 17 / Gradle 8.13 / AGP 8.13.2 / Kotlin 2.1.20 / API 36。锁定版本以实现可重复构建，不把每个库更新到最新作为首轮目标。AGP 官方兼容表：https://developer.android.com/build/releases/agp-8-13-0-release-notes
3. **扫描**：自有 CameraX 界面与 OpenCV 4.12.0 Maven 组件；不依赖 Play services 扫描模块。参考项目用于了解设计，不复制其完整壳或旧版 OpenCV。OpenCV 官方 Maven 用法：https://docs.opencv.org/4.12.0/d5/df8/tutorial_dev_with_OCV_on_Android.html
4. **PDF**：PdfBox-Android 2.0.27.0 执行内容级页面操作；Android PdfRenderer 显示。不引入 AndroidX PDF Beta，提高 API 26 基线路径的一致性。移植库维护速度、旧 PDFBox 上游版本、复杂表单/签名兼容性需持续评估。https://github.com/TomRoush/PdfBox-Android
5. **OCR**：ML Kit bundled Chinese + Latin，不使用 `play-services-mlkit-*` 动态模型。没有 INTERNET 权限，即使 SDK 包含遥测代码也不能联网。ML Kit 是 Google SDK 条款下的闭源模型，不写成 Apache 开源模型。https://developers.google.com/ml-kit/vision/text-recognition/v2/android 、https://developers.google.com/ml-kit/terms
6. **存储**：Room 2.7.1 + 私有文件 + SAF；不用全盘权限。暂不引入 DataStore，只有单个 export job 偏好，后续设置规模增加再迁移。
7. **范围**：提供完整扫描→导出流程并附部分实用 PDF/OCR；较难验证的可搜索中文文字层、书页 AI、批注/表单、Office 转换留作后续。不会用普通 OCR 文本冒充可搜索 PDF。
8. **打包**：arm64-v8a + x86_64，兼顾手机与模拟器。当前是调试签名安装包；长期发布签名由项目所有者掌握。
