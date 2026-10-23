# 用户提供 CamScanner APK 的本地静态分析

输入：CamScanner.apk，171,237,365bytes，SHA256 `344eb2b67ada9ef436ad6b40cba58232b24ed099c8b849171f94063737d2891e`。包名com.intsig.camscanner，versionName7.27.5.2610150000 / versionCode72751。输入来自用户，不据此证明官方发行来源。

使用Android Build Tools35.0.0 aapt、官方jadx1.5.6（下载digest校验）及ELF/ZIP静态符号检查。可读DEX只有13,908bytes，反编译结果主要为s.h.e.l.l加载壳；大量功能在ARM64原生库。本轮可读取加载壳与原生接口符号；没有运行该APK，完整算法实现尚未还原。原APK、解包内容、反编译产物只保留在工作区研究目录，不随PaperForge源码或Release发布。

| 本地观察 | 能支持的结论 | PaperForge采用 |
|---|---|---|
| libscannercs.so导出detectBorder/detectYuvImage、adjustBound、trimImageS等JNI接口 | 边框检测、边框微调与裁切是分别暴露的模块；不证明内部调用顺序或参数 | 分开实时识边、原照片精修和非破坏性裁切；统一坐标和取景纸框 |
| libpagescanner.so导出CropDewarp/CurveCropDewarp/ContourMask等 | 包内存在曲面/掩膜相关接口；仅名字不能恢复算法或证明任何样例效果 | 曲面展平仍列未完成，不用按钮或声明冒充实现 |
| libMNN/libncnn/libmagicenhancer等库存在 | 可确认含神经推理/增强组件，不能由库名推断模型权重或质量 | 采用有明确公开授权的MakeACopy模型和后处理，保留独立验证 |
| 首页资源JSON用mode/description/deeplink等字段组织功能入口 | 功能入口是数据驱动的配置 | PaperForge本地快捷入口注册、显隐、排序，集中设置；不引入其URL、图片、服务或资产 |

这轮交付的直接代码复用来自MakeACopy的Apache2源码及其明确授权推理模型；商业APK提供了可核实的结构与功能设计参考。扫描、PDF、库管理仍在PaperForge原生Kotlin/Compose/CameraX/OpenCV架构中实现，运行无INTERNET权限。
