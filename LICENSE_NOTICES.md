# 第三方组件声明

第三方模型用于个人私有构建；来源和许可状态见下文。

本项目按个人非商用用途开发。项目自身的对外源码发行许可证尚未由所有者指定；以下组件继续受其原许可证/条款约束。

| 组件 / 固定版本 | 许可 / 上游 |
|---|---|
| AndroidX Compose BOM 2025.05.01、Material 3、Material Icons、Activity 1.10.1、Lifecycle 2.9.0、CameraX 1.4.2、Room 2.7.1、WorkManager 2.10.1、ExifInterface 1.4.1 | Apache-2.0；https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin 2.1.20、Coroutines 1.10.2 | Apache-2.0；https://github.com/JetBrains/kotlin 、https://github.com/Kotlin/kotlinx.coroutines |
| OpenCV 4.12.0 | Apache-2.0 及捆绑第三方声明；https://github.com/opencv/opencv/tree/4.12.0 |
| PdfBox-Android 2.0.27.0 | Apache-2.0；https://github.com/TomRoush/PdfBox-Android ，包括 Apache PDFBox / FontBox、Adobe glyph lists、Unicode data、SIL Open Font License 字体等上游许可，见完整 LICENSE 与 NOTICE |
| ML Kit Chinese / Latin Text Recognition 16.0.1 | Google ML Kit 与 Google APIs 服务条款下的 SDK/模型，并非开源模型；https://developers.google.com/ml-kit/terms |
| PaperForge CJK Regular（由 Noto Sans SC 静态化并重命名的衍生字体） | SIL Open Font License 1.1；https://github.com/google/fonts/tree/main/ofl/notosanssc ；完整许可见 assets/licenses/NotoSansSC-OFL.txt |
| Bouncy Castle bcprov / bcpkix / bcutil 1.86（显式统一版本） | Bouncy Castle MIT-style license；https://www.bouncycastle.org/licence.html |
| JUnit 4.13.2（仅测试） | EPL-1.0；https://github.com/junit-team/junit4 |

完整已获取许可证保存在 `app/src/main/assets/licenses/` 并随 APK 打包。应用“关于与隐私”展示概要，源码提供完整文本。依赖传递清单由 Gradle 生成并保存在验收目录；主包未链接 MuPDF/iText，未使用第三方竞品视觉资产。可选格式引擎从本次开发版起使用 MuPDF Fitz 1.28.5（AGPL-3.0），完整许可、字体与第三方声明在 `format-engine/src/main/assets/native/`；固定源代码及 AAR 哈希见 notices.txt。

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


v0.4 directly adapts DocQuadLetterbox.java, DocQuadScore.java and DocQuadPostprocessor.java from MakeACopy (Christian Kierdorf), fixed revision 01bebd394b9dd6f3a692f28aea7c0638085eb4da, Apache-2.0. Package names changed, Lombok and runner-specific overloads removed; original copyright headers retained. The unmodified DocQuadNet-256 ORT inference artifact is bundled under the upstream NOTICE's explicit Apache-2.0 model grant. Full LICENSE/NOTICE, training-provenance caveats and hashes travel in assets/licenses/MakeACopy-* and assets/docquad/provenance.json. Model training overlap and inherited dataset terms are not independently certified.

ONNX Runtime Android 1.24.1 is the official Microsoft Maven artifact, CPU only, MIT plus third-party notices. Exact upstream revision 470ae16099a74fe05e31f2530489332c0525edb5 and AAR/notice hashes are recorded in assets/licenses/ONNXRuntime-provenance.json. Full texts are bundled. In v0.4, CamScanner's provided APK and extracted binaries/source/assets were local research inputs, not shipped dependencies.

v0.5 builds Alibaba MNN 3.6.1 from official source, fixed commit
d407447ed56c4121a11ccbd266dc184ca1ead0c2 (Apache-2.0), CPU only.
Full MNN, FlatBuffers and half licenses are bundled in assets/licenses/MNN-*.
Two recovered third-party model buffers (border.mnn, dewarp.mnn) come from
the user-provided CamScanner 7.27.5 APK. They are not PaperForge-authored or
open-source-licensed model weights. APK/model hashes, binary addresses and
runtime source pin are in assets/scan/provenance.json. No original competitor
.so or APK shell is bundled or executed. Edge contour fitting is PaperForge's
implementation; display tracking and dense dewarp core adapt recovered logic.


## 0.6.0 Office 与转换

Office 手机引擎使用 LibreOfficeKit 26.2.6.3，JNI 包装原文件保留 MPL-2.0 文件头。F-Droid 两个 ABI 构建、对应源码、固定 SHA256 与重建脚本见 [office-engine/UPSTREAM.md](office-engine/UPSTREAM.md)。主 APK 不链接该引擎，需按需安装独立手机 APK；均无 INTERNET 权限。

PPTX 空模板由 python-pptx 1.0.2 生成，模板许可文本保留在 `app/src/main/assets/templates/LICENSE-python-pptx.txt`。Android 表格、结构 DOCX、PPTX 打包与调用逻辑为本轮实现，复用既有 PDFBox/OpenCV/内置 ML Kit。

`desktop/` 是可选源码适配器，Python 依赖版本固定在其 requirements 中；pdf2docx 上游已停止活跃维护，当前 MIT；PyMuPDF 等依赖继续适用各自许可。手机默认流程不依赖这些 Python 包，本次不发布 Windows 第三方运行时二进制。

可选 OCR 包新增官方 PaddleOCR v5 Korean / Devanagari / Latin ONNX 权重与字典，继续使用 Apache-2.0；中英 v5 Mobile 包补充已验证的日文能力说明，权重不变。韩文、天城文和拉丁文包分别包含 Google Fonts 的 Noto Sans KR / Noto Sans Devanagari / Noto Sans 静态化衍生字体，重命名为 PaperForge Korean / Devanagari / Latin；SIL OFL 1.1 全文、固定来源提交、原始与派生哈希均在包内 `font-LICENSE` / `provenance.json`。字体不加入基础 APK。具体版本和使用方式见 [model-packs/README.md](model-packs/README.md)。

JPEG-2000 / PDF JPX 解码使用 [ThalesGroup/JP2ForAndroid 1.1.0](https://github.com/ThalesGroup/JP2ForAndroid/tree/8ad43abb5d8e2caf2147162d3bfe5569581b5d1f) 及其 OpenJPEG 2.5.4，BSD-2-Clause。Java 绑定保留原样，原生代码按固定归档哈希构建；为现有 API36 / NDK28.2 做独立构建模块适配，保留上游 16 KiB 链接选项。完整文本在 `assets/licenses/JP2ForAndroid-LICENSE.txt` / `OpenJPEG-LICENSE.txt`；文件哈希和适配说明在 `jpeg2000/UPSTREAM.json` / `README.md`。

## 手机 PDF/A 校验引擎

独立格式引擎新增 veraPDF 1.30.3（选择 MPL-2.0）、Rhino 1.7.15.1、Xerces 2.12.2 / XML APIs 1.4.01 及上游固定的 XML / JAXB 依赖。完整随包声明在 archive-core/src/main/assets/archive/，来源与四个移植源码在 archive-core/UPSTREAM.json 和 src/main/java/org/verapdf/。主包不包含这些校验内核依赖。sRGB ICC 由 Pillow / LittleCMS 生成，未复制外部 ICC 文件。


扫描清理与修复使用 Picsart AI Research 的 MI-GAN pipeline v2（MIT，模型由用户单独导入），以及 ONNX Model Zoo 的 Sub-Pixel CNN 3× 超分模型（Apache-2.0，240,078 字节随包）。固定来源、版本、输入输出与 SHA256 位于 app/src/main/assets/retouch/models.json；完整许可随包保存在 retouch/LICENSE-MIGAN、retouch/LICENSE-SR。两者均在本机推理。


## 可选联网工具

`network-engine`使用AOSP apksig 8.13.2（Apache-2.0）验证完整APK签名。工具独立拥有网络与系统安装请求权限，基础应用没有新增这些权限。依赖来源与哈希见 network-engine/UPSTREAM.json，许可随工具在assets/licenses/中提供。

局域网二维码使用ZXing core 3.5.3（Apache-2.0）；随包浏览器增量哈希使用js-sha256 0.11.1（MIT），npm完整性与脚本SHA已核对。完整许可位于network-engine/src/main/assets/licenses，来源见其UPSTREAM.json。

可选联网工具新增Google ML Kit Translate 17.0.3，适用[Google ML Kit SDK条款](https://developers.google.com/ml-kit/terms)，不是开源模型。SDK AAR固定哈希在network-engine/UPSTREAM.json；语言包由用户手动下载/移除，文字翻译在设备本地处理，权重未提交或随APK再打包。
