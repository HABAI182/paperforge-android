# 架构与文件生命周期

0.4.0：CameraX共享UseCaseGroup/ViewPort，遵循cropRect与OutputTransform。LearnedDocumentDetector用单例串行CPU ORT会话和带来源的MakeACopy后处理，与DocumentDetector混合/回退。UserSettings独立保存本地偏好、StateFlow驱动UI，DPI排队时冻结；不改变Room6。文档多选按明确ID批量操作，照片原始文件不变。

## 0.3.0 管线增量

PageDecoder负责预算内区域解码和EXIF；ImageGeometry在完整源画面估算物理比例。DocumentDetector返回评分/方法/诊断；EnhancePipeline处理颜色和缩放自适应二值化。ScanConfig从scan_params.json读参数。OcrEngine注入到应用/视图模型，OcrLayout记录候选/四角/引擎/方向；缓存写入核对revision，原图与派生历史保留。

PdfImageEncoder处理不透明RGB/真灰度/1bit；PdfImageOptimizer在递归资源和实际CTM上下文中更换合适图片，其余对象与内容流保留，输出报告。flatten独立。Room6加入CJK FTS4及去阴影强度，迁移回填旧OCR；文档删除同步索引与历史。直接删除与回收站为两个明确入口。pdfEntry只在所选PDF成功打开时消费，用于直接到达工具参数；取消选择清空入口。

以下为早期架构说明；若与本段冲突，以本段和当前代码为准。

单 Gradle app 模块，按 model / data / imaging / pdf / ocr / ui 分包。选择一条可运行主链路，避免第一轮拆十多个尚无独立边界的模块。后续可以提取引擎为 library module。

```
CameraX / SAF / 分享 Intent
          ↓
Repository → 私有 documents/<uuid>/原图或 PDF 副本
          ↓
Room: DocumentRow + PageRow + EditHistory（分类、回收站、OCR 行四角、撤销）
          ↓
OpenCV: EXIF → 原图 → 归一化角点 → 透视 → 滤镜 → 旋转
          ↓                       ↓
Compose 预览 / 缩略图          内置 ML Kit OCR → 可校正文本 / 透明文字层
          ↓
WorkManager → PdfBox 逐页嵌入 JPEG → 临时文件 → 回读校验 → exports/
          ↓
PdfRenderer 阅读 / SAF 另存 / FileProvider Share Sheet
```

扫描原图永不被渲染覆盖。`PageRow` 的四角坐标采用经过 EXIF 纠正后的原图坐标，0–1 归一化、顺时针 TL/TR/BR/BL。滤镜与旋转是派生操作；几何/滤镜修改递增 revision 并清空旧 OCR。OCR 行坐标相对于最终裁切旋转后的图像，嵌入 PDF 时按实际图像位置和页面旋转映射。校正文字保持行数时可直接更新文字层缓存。

DAO 页面排序变更通过 Room 事务完成。Room schema 2 提供 1→2 迁移；EditHistory 存储编辑前后页面 JSON，支持跨进程撤销/重做。重拍保留此前原图供撤销；删除页面会清除该文档历史。复制/双页分割共用原图，最后一页引用消失才删除文件。

相机分析在单独线程运行，KEEP_ONLY_LATEST，每约 180ms 分析一次；读取 Y 平面、旋正、最长边 900 像素。多阈值轮廓拟合、Otsu 正反亮度掩膜组合评分；界面平滑角点，稳定门控使用未平滑检测值。拍摄后以更高分辨率重检，失败时用同一 4:3 取景的检测框回退。真实设备 FOV/旋转仍需回归；失败不阻止手拍。

扫描导出逐页解码、透视和嵌入 JPEG，及时释放中间 Bitmap/Mat；PdfBox 使用磁盘临时缓存。PDF 阅读是 LazyColumn + 24MiB LRU；每页关闭 PdfRenderer.Page，对 renderer 加锁以避免并发开页。显示中的 Bitmap 由 UI/GC 管理，避免回收后绘制造成崩溃。

普通 PDF 合并/抽取保留原生内容流；压缩单列为显式栅格化副本。签名与交互对象的跨文档保留能力不做未验证承诺。所有处理创建新文件。应用内部先生成 .part、关闭/fsync、解析验证后 rename；SAF 提供程序输出为后续复制，其跨提供程序原子性无法保证。

WorkManager 负责扫描导出及 PDF 工具任务，记录 UUID 用于结果恢复。密码仅用于短暂内存中的加密操作，不放入 WorkManager Data。批注长笔迹另存 JSON 任务输入，避免 Data 的 10KB 上限。单页识别、文档备份等在 ViewModel IO 协程执行。Android 超长任务前台服务和大文件压力验证仍待补。

批注固定到副本内容流，草稿 JSON 留在文档私有目录。若含安全脱敏，所有页面重新渲染到新 PDDocument，源内容流、文字、附件、表单和元数据不复制。备份 ZIP 保存文档引用文件、历史、OCR、批注和签名；恢复分配新 UUID，事务追加，不覆盖现有文档。
