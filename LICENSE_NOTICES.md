# 第三方组件声明

本项目按个人非商用用途开发。项目自身的对外源码发行许可证尚未由所有者指定；以下组件继续受其原许可证/条款约束。

| 组件 / 固定版本 | 许可 / 上游 |
|---|---|
| AndroidX Compose BOM 2025.05.01、Material 3、Material Icons、Activity 1.10.1、Lifecycle 2.9.0、CameraX 1.4.2、Room 2.7.1、WorkManager 2.10.1、ExifInterface 1.4.1 | Apache-2.0；https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin 2.1.20、Coroutines 1.10.2 | Apache-2.0；https://github.com/JetBrains/kotlin 、https://github.com/Kotlin/kotlinx.coroutines |
| OpenCV 4.12.0 | Apache-2.0 及捆绑第三方声明；https://github.com/opencv/opencv/tree/4.12.0 |
| PdfBox-Android 2.0.27.0 | Apache-2.0；https://github.com/TomRoush/PdfBox-Android ，包括 Apache PDFBox / FontBox、Adobe glyph lists、Unicode data、SIL Open Font License 字体等上游许可，见完整 LICENSE 与 NOTICE |
| ML Kit Chinese / Latin Text Recognition 16.0.1 | Google ML Kit 与 Google APIs 服务条款下的 SDK/模型，并非开源模型；https://developers.google.com/ml-kit/terms |
| PaperForge CJK Regular（由 Noto Sans SC 静态化并重命名的衍生字体） | SIL Open Font License 1.1；https://github.com/google/fonts/tree/main/ofl/notosanssc ；完整许可见 assets/licenses/NotoSansSC-OFL.txt |
| Bouncy Castle bcprov / bcpkix / bcutil 1.72（PdfBox 传递依赖） | Bouncy Castle MIT-style license；https://www.bouncycastle.org/licence.html |
| JUnit 4.13.2（仅测试） | EPL-1.0；https://github.com/junit-team/junit4 |

完整已获取许可证保存在 `app/src/main/assets/licenses/` 并随 APK 打包。应用“关于与隐私”展示概要，源码提供完整文本。依赖传递清单由 Gradle 生成并保存在验收目录；未使用 MuPDF/iText 或第三方竞品视觉资产。

v0.3 adapts `OutlineTracker.kt` and `CaptureQualityPolicy.kt` from [phillip9933/open-android-doc-scanner](https://github.com/phillip9933/open-android-doc-scanner/tree/3ba5c50a64f9be8eaeec47d263b360488e58835b), commit `3ba5c50a64f9be8eaeec47d263b360488e58835b`, Apache-2.0. Changed files: PaperForge `model/OutlineTracker.kt`, `model/CaptureQualityPolicy.kt`; native Quad interfaces, explicit AF readiness, bounded scene references and first-frame blur handling. License: assets/licenses/Apache-2.0.txt; original notice: OpenAndroidDocScanner-NOTICE.txt. Imaging/OCR/PDF algorithms otherwise independently implemented, with technique references in docs/research/UPSTREAM_REFERENCE_MATRIX.md. No upstream runtime or model was imported.

Apache PDFBox
Copyright 2014 The Apache Software Foundation

This product includes software developed at The Apache Software Foundation (http://www.apache.org/).
Based on source code originally developed in the PDFBox and FontBox projects.
Copyright (c) 2002-2007, www.pdfbox.org
Based on source code originally developed in the PaDaF project.
Copyright (c) 2010 Atos Worldline SAS
Includes the Adobe Glyph List. Copyright 1997, 1998, 2002, 2007, 2010 Adobe Systems Incorporated.
Includes the Zapf Dingbats Glyph List. Copyright 2002, 2010 Adobe Systems Incorporated.
Includes the Script Property (Scripts-10.0.0.txt). Copyright 2017 Unicode, Inc.
