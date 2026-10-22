# 架构与文件生命周期

单 Gradle app 模块，按 model / data / imaging / pdf / ocr / ui 分包。选择一条可运行主链路，避免第一轮拆十多个尚无独立边界的模块。后续可以提取引擎为 library module。

```
CameraX / SAF / 分享 Intent
          ↓
Repository → 私有 documents/<uuid>/原图或 PDF 副本
          ↓
Room: DocumentRow + PageRow (参数、页序、修订、OCR 文本)
          ↓
OpenCV: EXIF → 原图 → 归一化角点 → 透视 → 滤镜 → 旋转
          ↓                       ↓
Compose 预览 / 缩略图          内置 ML Kit OCR → 可校正文本 / TXT
          ↓
WorkManager → PdfBox 逐页嵌入 JPEG → 临时文件 → 回读校验 → exports/
          ↓
PdfRenderer 阅读 / SAF 另存 / FileProvider Share Sheet
```

扫描原图永不被渲染覆盖。`PageRow` 的四角坐标采用经过 EXIF 纠正后的原图坐标，0–1 归一化、顺时针 TL/TR/BR/BL。滤镜与旋转是派生操作；几何/滤镜修改递增 revision 并清空旧 OCR。OCR 当前只有文本缓存，不伪造缺失的布局坐标。

DAO 页面排序变更通过 Room 事务完成。工作区草稿跨进程保存；当前撤销栈是会话内的，进程恢复后可继续编辑但不能撤销此前会话。Room schema 导出至 app/schemas，后续版本必须增加迁移。

相机分析在单独线程运行，KEEP_ONLY_LATEST，每约 180ms 分析一次；只读取 Y 平面并考虑行跨度，旋正后缩至 720 像素检测。FIT_CENTER 取景与归一化覆盖框匹配；真实设备宽高/FOV 和旋转仍须回归。低置信度不阻止手拍，拍摄后独立重新检测原图。自动捕获要求角点稳定，拍后同一纸张被锁定，离开视野或明显变动才重新武装。

扫描导出逐页解码、透视和嵌入 JPEG，及时释放中间 Bitmap/Mat；PdfBox 使用磁盘临时缓存。PDF 阅读是 LazyColumn + 24MiB LRU；每页关闭 PdfRenderer.Page，对 renderer 加锁以避免并发开页。显示中的 Bitmap 由 UI/GC 管理，避免回收后绘制造成崩溃。

普通 PDF 合并/抽取保留原生内容流；压缩单列为显式栅格化副本。签名与交互对象的跨文档保留能力不做未验证承诺。所有处理创建新文件。应用内部先生成 .part、关闭/fsync、解析验证后 rename；SAF 提供程序输出为后续复制，其跨提供程序原子性无法保证。

WorkManager 负责扫描导出与进度，记录 job UUID 到偏好用于进程重建后的结果恢复。第一版其它工具在 ViewModel 的 IO 任务运行，超长合并的持久调度、及时中止及 Android 长任务前台服务仍为后续项。
