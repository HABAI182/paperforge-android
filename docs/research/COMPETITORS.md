# 研究与设计取舍

沿用用户提供文档中的跨平台需求，不把商店宣传当成测试证据。首轮重新读取了以下上游资料（2026-10-23）：

- ClearPDF：https://github.com/Chethan616/ClearPDF 。研究 Kotlin/Compose 工具箱结构，未整库引入、未复制界面资源。
- CameraX/OpenCV SDK：https://github.com/zynkware/Document-Scanning-Android-SDK 。研究取景分析和四角流程，使用自己的实现及官方 OpenCV Maven 制品。
- Compose Document Scanner：https://github.com/shaunxiao-github/Document-Scanner 。验证原生扫描路线参考，不将小型参考应用等同成熟算法。
- PdfBox-Android：https://github.com/TomRoush/PdfBox-Android 。实际使用其 Apache 2.0 移植引擎。
- CameraX 版本说明：https://developer.android.com/jetpack/androidx/releases/camera 。已有更新版本，本轮锁定 1.4.2，不宣称使用最新版本。

吸收的交互：首屏明确扫描/导图/打开 PDF；拍照时不打断连拍；原图可回改；有真实导出、真实体积和系统分享；工具入口少而可用。没有访问或复制竞品专有图标、商标、截图、模型、付费算法。

尚未对 CamScanner/夸克/vFlat 等作同素材真机对拍，也未对其付费范围重新进行账户内核验。扫描质量、OCR 识别率与竞品的优劣没有结论。
