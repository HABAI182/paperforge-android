# #23 · 双页书脊自动检测与左右拆分（MVP）

本页记录最小可用范围：对一张“整书页扫描图”自动找到中间书脊，复用既有页面拆分机制生成左右两页；只做左右各页的四边形内切，**不做曲面 dewarp，也不做透视/曲面重建**。真实书页照片与真机都未测，实测全部是测试内合成图。

## 范围与入口

- **入口**：编辑器 → 底部工具栏「更多」（扫描页面工具）→「拆分双页 · 自动书脊」。
  - 同一菜单里的「书籍双页与书脊」保留原手动落点对话框（本轮未改）。
- **行为**：对当前页执行；检测成功才拆分。拆分走既有的 `Repository.splitBook(page, top, bottom, "both")`：
  - 左右两页写入同一文档，顺序固定为左页在前、右页在后（`position` 0/1）；
  - 两页都指向**同一张原图文件**，原图不复制、不重写、不删除；
  - 撤销/重做沿用既有 `edit_history`（`Repository.splitBook → replacePages → addHistory`），与手动拆分同一条路径；
  - `splitBook` 原有行为不变：两半的 `ocrText`/`ocrLayout` 清空，`processingMetadata`/资源表保留。
- **检测失败**：只提示「未检测到书脊：<原因>。本页未拆分。」，**不写任何页面**，没有“硬切中间”的回退。
- **预处理**：检测专用渲染固定为 `filter=ORIGINAL`、亮度 0、对比度 1、长边 1600 px，使用当前页自己的裁切四边形；因此检测空间与 `CropGeometry.split` 的 top/bottom 比例直接对应。仅支持 `rotation == 0` 且 `deskewDegrees == 0`；其它旋转/去斜值只提示不支持（去斜会旋转输出图，x 比例不再对应四边形上/下边）。

### 代码位置

- 新增 `app/src/main/java/cn/paperforge/app/imaging/BookSpineDetector.kt`：纯 luma 数组核心 + `Bitmap` 包装，返回 `SpineDetection`。
- `app/src/main/java/cn/paperforge/app/MainViewModel.kt`：`autoSplitSpread(page)`、`lastSpineDetection`。
- `app/src/main/java/cn/paperforge/app/ui/EditorScreen.kt`：「扫描页面工具」菜单项。
- `app/src/main/java/cn/paperforge/app/ui/ScanEditorLayout.kt`：分页 pager 的越界防护（见“设备实测/撤销崩溃”）。
- 新增 `app/src/test/java/cn/paperforge/app/imaging/BookSpineDetectorTest.kt`、`app/src/androidTest/java/cn/paperforge/app/SpreadSplitSampleTest.kt`。
- 复用而非另造：`CropGeometry.split`（四边形分割）、`Repository.splitBook`（同文档换页 + 历史）。本轮没有新调用 `CropSafety`，但 `ScanProcessor.render → PageDecoder.crop` 既有 #21 的 1% 短边安全外扩会作用于拆分后的半页（见“展平/校正”与限制）。

## 检测算法与阈值

输入是一张已经按页面四边形渲染好的灰度图（宽高均 ≥240 px）。

1. **列投影**：在三个纵向带内按行采样（行步长 `height / 480`，1000 px 图约 2 px）：上带 8%–45%、下带 55%–92%、全带 4%–96%。列均值做半径 `max(1, width/600)` 的盒式平滑。
2. **局部基线**：`delta[x] = 平滑列均值 - max(左侧 5% 处, 右侧 5% 处的平滑列均值)`。
   - 用**更亮一侧**而不是两侧平均：平均基线会让暗书脊把旁边留白的基线拉低，留白反过来像一条“白缝”。合成图上实测过这个错误：46% 处的暗书脊曾被报成 50.9% 的白缝（修复前设备 x=0.464/单测 0.509），改成更亮侧基线 + 取异常段中点后为 0.4599。
3. **暗带 / 白缝两路候选**（只在页宽 30%–70% 搜索）：
   - 暗带：`delta >= 11` 灰度级；行级覆盖 ≥65%；全带覆盖 ≥55%；异常段宽 0.05%–6% 页宽；行判据是同行的左右 5% 邻列平均比本列亮 ≥8 级。
   - 白缝：`delta >= 6` 灰度级；行级覆盖 ≥82%；全带覆盖 ≥90%；异常段宽 0.05%–3% 页宽；行判据是本列比同行左右邻列平均亮 ≥5 级。
   - 候选位置取**连续异常段的中点**（暗带内部 delta 相同，取极值会把分割线推到段的一侧；实测偏 6 px）。
4. **上下半区校验**：上、下两个带各取自己的最优候选；两半的方法必须相同；上下端点差 ≤4% 页宽（本 MVP 只接直线中缝，允许轻微倾斜，不做曲线）。
5. **内容分布校验**：按候选位置把页分成左右两半（排除中线两侧 1.5% 与页面 2% 外缘），每半单独取纸面亮度（90 分位），`像素 < 纸面-30` 记为墨迹；两侧墨迹占比都必须 ≥0.2%，且大/小比 ≤4。单页、空白页、一侧空白的页面在这一步被拒。
6. **置信度**：`0.58 * min(上,下半区分) + 0.30 * 平均分 + 0.12 * 内容平衡分`，再乘倾斜惩罚 `(1 - 0.08 * 倾斜/上限)`；低于 **0.50** 判为未检测到。分数由对比度、行覆盖、中心先验构成（中心先验 σ=16% 页宽）。

返回：`x`（归一化书脊位置）、`top`/`bottom`（上下两个端点的页宽比例，直接交给 `CropGeometry.split`）、`confidence`、`method`（`dark-band` / `white-seam`）、`evidence`（对比度、覆盖、段宽、左右墨迹、倾斜）。没检出时 `x=null`、`method="none"`、`reason` 为具体原因。

### 展平/校正

- 左右页就是原裁切四边形的两个子四边形（`CropGeometry.split`），每页保留完整页高；渲染时沿用既有 `ScanProcessor.render`（透视裁剪到矩形）。
- 没有曲面 dewarp、没有透视网格、没有逐页内侧收边。一条分割线让每页各留下半条中缝暗带；另外 `ScanProcessor.render → PageDecoder.crop` 会按 #21 的既有 `CropSafety` 把每页外扩 1% 短边（页面外缘被画幅夹住，内边则向中缝多覆盖一点）。设备测试的渲染图里每页内边都有约 12 px 暗带（左页 579 px 宽的约 2.1%，右页 681 px 宽的约 1.8%）。存储的四角仍是精确半页；要完全去掉内边残留需要“左页按中缝左缘、右页按右缘”的两次裁切并跳过内边外扩，本轮未做。
- 原图始终保留；撤销后回到 1 页且原图 SHA-256 不变（见下）。

## 设备实测（synthetic=true，不是真实照片）

- 设备：`paperforge_api35(AVD)`，Android 15 / SDK 35，`emulator-5554`，x86_64；沿用已有模拟器，未新开。
- 入口：Compose 测试真实点按「更多」→「拆分双页 · 自动书脊」，不是直接调用 ViewModel。
- 样张：测试内用 `Canvas` 画的 1400×1000 跨页图：46% 处 18 px 暗带中缝、左右两页 18 行文字、从左到右纸面 246→196 的光照渐变；保存为 JPEG 后走完整编辑入口。不是真实照片，也不含用户样本。
- 结果 JSON：[spread-split.json](spread-split.json)（同一份也拉回仓库外 `work/spread-issue23/result.json`）。
- 生成图：[spread-source.jpg](spread-source.jpg)、[left.png](left.png)、[right.png](right.png)。

| 指标 | 实测值 |
|---|---|
| 真实书脊位置（合成图设定） | 46% 页宽（像素中心 644/1399 ≈ 0.4603） |
| 检测 x / 方法 / 置信度 | **0.459943** / `dark-band` / **0.9415** |
| 位置偏差 | **0.04% 页宽**（1400 px 图约 0.5 px，含像素离散） |
| top / bottom / 倾斜 | 0.459943 / 0.459943 / 0 |
| 暗带对比度、行覆盖、全带覆盖 | 162.4 级 / 1.000 / 1.000 |
| 异常段宽 | 1.644% 页宽 |
| 左右墨迹占比 | 5.398% / 3.108% |
| 页数 | **1 → 2** |
| 左页 / 右页 | position 0 / 1，中心 x 0.2300 / 0.7300，面积 0.4599 / 0.5401 |
| 两页 `original` | 同一路径，原图 SHA-256 拆分前后一致（`17f4a783…e78d0`） |
| 左右页渲染尺寸 | 579×900 / 681×900（左页占比 0.4595） |
| 每页内边暗带（渲染图逐列均值实测） | 左 12 px / 右 12 px（约 2.1% / 1.8% 页宽，含半条中缝与既有 1% 安全外扩） |
| 撤销 | 2 → 1 页，回到同一原图，SHA-256 不变 |
| 设备测试 | `SpreadSplitSampleTest` **1 test / 0 failures**（`work/spread-issue23/device-test-SpreadSplitSampleTest.xml`） |

视觉核对：把设备渲染出的 [left.png](left.png) / [right.png](right.png) 与源图拉回后逐张看图确认——左页文字顺序正确、右页起点正确、没有整页空白；中缝暗带按 MVP 预期各留半条。这是对生成图片的检查，不是设备屏幕截图走查。

### 撤销崩溃与修复（本轮附带）

首次设备运行在撤销把页数 2→1 时崩溃：`IndexOutOfBoundsException: Index 1 out of bounds for length 1`，位于 `ScanEditorLayout.kt` 的 pager `key = { pages[it].id }`——分页器还持有上一轮的索引范围，而页面列表已经缩短。这是“拆分后撤销”必然会走到的路径，因此做了最小防护：`key` 与页面内容都改为 `getOrNull`，不再越界。修复后同一测试连续两次 1/0 通过。这不是重构，也不改变正常页数下的行为。

## 合成单测数字

`BookSpineDetectorTest` **8 tests / 0 failures**（JVM，`app/build/test-results/testDebugUnitTest`）。合成图 1400×1000，带 ±3 级确定性噪声；数值来自测试写入 JUnit XML 的实测输出。

| 用例 | 实测 |
|---|---|
| 中缝暗带（目标 0.5000） | x=0.499643（偏差 0.036% 页宽），置信度 0.9997 |
| 白缝（纸面 232、缝 253） | x=0.499643，方法 `white-seam`，置信度 0.9997 |
| 光照不均（纸面 242→189，缝在 0.4800） | x=0.479628（偏差 0.037%），置信度 0.9895 |
| 倾斜 2% 页宽 | top=0.495354 < bottom=0.504646（端点差 0.93%），方向正确 |
| 单页无中缝 | `found=false`，`method=none`，原因“未找到贯穿上下的暗带或白缝” |
| 空白页 | `found=false`，原因同上 |
| 两栏文字 + 中央空白栏 | `found=false`（不把版心空白当白缝） |
| 非对称中缝 0.4600 | x=0.459614（偏差 0.039%）；`CropGeometry.split` 后左页面积小于右页、左页中心在右页左侧、两半面积和 1.0 |

阈值行为也因此固定：暗带/白缝的 x 偏差在合成图上都 ≤0.04% 页宽；小于 0.05% 页宽的异常段、宽于 6%（暗带）/3%（白缝）的异常段、覆盖不足的候选都会被拒。

## 既有测试

| 测试 | 结果 |
|---|---|
| `PageSpreadsTest`（JVM） | **1/1 通过** |
| `ResolutionTest`（`emulator-5554`） | **1/1 通过**（`work/spread-issue23/device-test-ResolutionTest.xml`） |
| `EditorExperienceTest`（`emulator-5554`） | **失败**：`Failed to inject touch input.`（既有环境问题，见下） |
| 全量 JVM 单测 | **82 tests / 1 failure**，失败为 `GeometryTest.fileNamesCannotEscapeDestination`（既有，见下） |

- `EditorExperienceTest` 在本机 AVD 的裁切画布手势处报 `Failed to inject touch input`。该失败已在 `docs/acceptance/issues/21/README.md` 记录为 `main` 上同样复现的已知问题；本轮未改该测试、未改触摸/裁切逻辑。失败现场：`work/spread-issue23/device-test-EditorExperienceTest-fail.xml`。
- `GeometryTest` 期望 `safeFileName("...") == "纸匠文档"`，实际为 `PaperForge文档`；由提交 `1d9af77 Adopt supplied layered-page logo and PaperForge display name` 改默认名时留下，与 #23 无关。本轮未改断言。现场：`work/spread-issue23/unit-TEST-GeometryTest-fail.xml`。
- 新增/修改代码没有改任何既有测试断言。

## MVP 限制

- **只做整页直线中缝**：书脊位置限制在页宽 30%–70%，上下端点差 ≤4% 页宽；曲线书脊、极宽/极淡阴影、超出中心带的中缝都不支持。
- **没有曲面/透视重建**：只做原四边形的左右内切；不 dewarp、不做曲面网格，也没有逐页内侧收边，因此每页会留半条中缝暗带；渲染时既有的 `CropSafety` 1% 短边外扩还会让内边再多覆盖一点（见 [left.png](left.png) / [right.png](right.png)，实测内边约 12 px）。
- **只支持未旋转、未去斜页面**：`rotation != 0` 或 `deskewDegrees != 0` 直接拒绝并提示；旋转/去斜过的扫描件未接。
- **一侧空白的真实跨页会被拒**：内容分布要求两侧墨迹 ≥0.2% 且比值 ≤4，夹在章节间的空白页不能拆。
- **可能误判**：页面中央若有贯穿全高的深色竖线/表格边框（比文字列更连续），现阶段无法与装订线区分；两栏纯文字页有单测覆盖（未误判），但带中央框线的表格/表单未测。误判可撤销。
- **只测了合成图**：没有真实书页照片、没有 DocUNet 之类真实拍摄语料；检测阈值只在这组合成图与设备 Canvas 图上验证。
- **检测结果只用于本次拆分**：不写回页面元数据；`lastSpineDetection` 仅供诊断/测试。

## 未验证项

- 真实手机、其它 Android 版本/厂商 ROM；真实摊开书页（不同装订、阴影宽度、纸张颜色、折痕）与真实相机透视。
- 旋转 90°/180°/270°、去斜（`deskewDegrees`）、已经 dewarp 过的页面。
- 超大页/内存受限设备上 1600 px 渲染 + 检测的耗时；本轮未记录耗时指标（只有功能通过）。
- 撤销/重做多次、拆分后再对某一半再次拆分、拆分后立即 OCR 的组合场景。
- 深色主题、超大字体、横屏与无障碍下菜单项和结果提示的实际观感；设备测试是 Compose 点击，不是人工走查截图。
- `splitBook` 对两半清空 OCR 文本/布局的既有行为未在 #23 重新验证（沿用既有实现）。
- 设备上只走了“检出并拆分成功”的路径；“未检测到书脊时不写页面”目前只有单测和代码路径，未做设备断言。
- 非全幅裁切四边形上的偏心书脊：检测在渲染图上做（该渲染带 #21 的 1% `CropSafety` 外扩），比例随后直接套到原四边形；全幅样张没有误差，偏心书脊理论上存在约 1% 量级的换算近似，未用真实样本量化。
