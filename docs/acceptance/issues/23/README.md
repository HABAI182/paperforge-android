# #23 · 双页书脊自动检测与左右拆分（MVP）

本页记录最小可用范围：对一张“整书页扫描图”自动找到中间书脊，复用既有页面拆分机制生成左右两页；只做左右各页的四边形内切，**不做曲面 dewarp，也不做透视/曲面重建**。真实书页照片与真机都未测，实测全部是测试内合成图。

> **2026-10-31 收尾（内边收边）**：自动拆分从“一条分割线、左右各留半条缝”改成“左页按缝左缘、右页按缝右缘”各收一次，并在存储四边形里补偿渲染期的 1% `CropSafety` 安全环。同一台模拟器、同一张合成跨页、同一列投影口径实测：每页内边暗带 **左 17 px / 右 17 px → 左 0 px / 右 0 px**（旧文档记的“约 12 px”用的是另一套未附脚本的列均值口径，未能在同一口径下复算；本轮同口径实测的改动前数字是 17/17 px）。做法与前后数字见“内侧收边”。

## 范围与入口

- **入口**：编辑器 → 底部工具栏「更多」（扫描页面工具）→「拆分双页 · 自动书脊」。
  - 同一菜单里的「书籍双页与书脊」保留原手动落点对话框（本轮未改）。
- **行为**：对当前页执行；检测成功才拆分。检测同时返回中缝带的两缘（`SpineDetection.seamLeft` / `seamRight`），拆分走扩展后的 `Repository.splitBook(page, leftTop, leftBottom, "both", rightTop, rightBottom)`（即 `CropGeometry.split` 的 5 参数重载）：左页 = [原四边形左边, 缝左缘]，右页 = [缝右缘, 原四边形右边]，整条中缝不落在任何一页里。
  - 手动「书籍双页与书脊」仍调用原来的 3 参数版本（一条线、两页各留半条缝），既有语义与默认值不变；`CropGeometry.split(quad, top, bottom)` 等价于把同一条线传给两条边界。
  - 左右两页写入同一文档，顺序固定为左页在前、右页在后（`position` 0/1）；
  - 两页都指向**同一张原图文件**，原图不复制、不重写、不删除；
  - 撤销/重做沿用既有 `edit_history`（`Repository.splitBook → replacePages → addHistory`），与手动拆分同一条路径；
  - `splitBook` 原有行为不变：两半的 `ocrText`/`ocrLayout` 清空，`processingMetadata`/资源表保留。
- **内边收边的补偿**：渲染路径 `PageDecoder.crop` 会先按 #21 的 `CropSafety` 把每页四边形向外扩 1% 短边再透视裁剪（1400×1000 样张上单边约 10 px）。若存储的内边正好落在缝缘，渲染后又会多带回一条中缝；因此自动拆分把存储内边再向页内收进一个安全环宽度（`MainViewModel.innerEdgeSafetyInset`，用原图长边把 `0.01 × min(宽, 高)` 换算成 x 比例），渲染时安全环再把它补回缝缘，最终成片的内边 = 缝缘。手动拆分不补偿。
- **检测失败**：只提示「未检测到书脊：<原因>。本页未拆分。」，**不写任何页面**，没有“硬切中间”的回退。
- **预处理**：检测专用渲染固定为 `filter=ORIGINAL`、亮度 0、对比度 1、长边 1600 px，使用当前页自己的裁切四边形；因此检测空间与 `CropGeometry.split` 的 top/bottom 比例直接对应。仅支持 `rotation == 0` 且 `deskewDegrees == 0`；其它旋转/去斜值只提示不支持（去斜会旋转输出图，x 比例不再对应四边形上/下边）。

### 代码位置

- 新增 `app/src/main/java/cn/paperforge/app/imaging/BookSpineDetector.kt`：纯 luma 数组核心 + `Bitmap` 包装，返回 `SpineDetection`（含中缝两缘 `SpineEdge`）。
- `app/src/main/java/cn/paperforge/app/MainViewModel.kt`：`autoSplitSpread(page)`、`lastSpineDetection`、`innerEdgeSafetyInset(page)`。
- `app/src/main/java/cn/paperforge/app/model/CropGeometry.kt`：`split` 增加 5 参数重载（左页内边 + 右页内边），3 参数版本委托给它，行为不变。
- `app/src/main/java/cn/paperforge/app/data/Repository.kt`：`splitBook` 增加可选的 `rightTopFraction`/`rightBottomFraction`，默认等于左页内边。
- `app/src/main/java/cn/paperforge/app/imaging/PageDecoder.kt`：新增 `uprightSize(file)`（只读文件头 + EXIF），供安全环换算使用。
- `app/src/main/java/cn/paperforge/app/ui/EditorScreen.kt`：「扫描页面工具」菜单项。
- `app/src/main/java/cn/paperforge/app/ui/ScanEditorLayout.kt`：分页 pager 的越界防护（见“设备实测/撤销崩溃”）。
- 新增 `app/src/test/java/cn/paperforge/app/imaging/BookSpineDetectorTest.kt`、`app/src/androidTest/java/cn/paperforge/app/SpreadSplitSampleTest.kt`；本轮在 `app/src/test/java/cn/paperforge/app/CropGeometryTest.kt` 补两条边界用例、在 `BookSpineDetectorTest` 补“中缝两缘”用例、在 `SpreadSplitSampleTest` 补内边暗带测量。
- 复用而非另造：`CropGeometry.split`（四边形分割）、`Repository.splitBook`（同文档换页 + 历史）。本轮显式读取 `CropSafety` 的安全环宽度（`cropSafetyMargin`）用于反向补偿，但没有改 `CropSafety` 本身的实现，也没有改手动拆分。

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

返回：`x`（归一化书脊位置）、`top`/`bottom`（上下两个端点的页宽比例）、`seamLeft`/`seamRight`（中缝带的左右两缘，拆分时各自作为左/右页内边）、`confidence`、`method`（`dark-band` / `white-seam`）、`evidence`（对比度、覆盖、段宽、左右墨迹、倾斜）。没检出时 `x=null`、`method="none"`、`reason` 为具体原因，两缘也为 null。

### 展平/校正

- 左右页就是原裁切四边形的两个子四边形（`CropGeometry.split` 5 参数重载），每页保留完整页高；渲染时沿用既有 `ScanProcessor.render`（透视裁剪到矩形）。
- 没有曲面 dewarp、没有透视网格；自动拆分做“逐页内侧收边”（见下）：一条中缝不再被两页各留一半。
- 原图始终保留；撤销后回到 1 页且原图 SHA-256 不变（见下）。

### 内侧收边（2026-10-31 收尾）

- **两条边界**：`CropGeometry.split` 新增 `split(quad, leftTop, leftBottom, rightTop, rightBottom)`，左页右边 = 缝左缘、右页左边 = 缝右缘；3 参数版本把同一条线传给两条边界，所以手动拆分、既有调用与默认值都不变。两条边界先各自夹到 `0.15..0.85`（与原单线版本一致），再要求“右边界 ≥ 左边界”，交叉时抛「书脊左右边界交叉」，不会生成两张重叠的页面。
- **检测给出两缘**：`BookSpineDetector` 原来只返回异常段中点，现在额外返回该段在上下采样带里的左右端点（`seamLeft` / `seamRight`）。1400×1000 合成图上：中点 0.459943，左缘 0.452074、右缘 0.467811（检出段宽 1.644% 页宽，比 18 px 实际暗带略宽，因为列均值做了半径 2 px 平滑、边缘阈值 6 级）。
- **渲染安全环补偿**：`ScanProcessor.render → PageDecoder.crop` 在透视裁剪前会按 #21 的 `CropSafety` 把每页四边形向外扩 1% 短边 = 10 px（1400×1000 样张）。如果只把内边放在缝缘，渲染后又会把 10 px 中缝带回页内；所以自动拆分在存储四边形里把内边向页内收进这一个安全环宽度（`MainViewModel.innerEdgeSafetyInset`，`0.01 × min(原图宽, 高) / (原图宽 - 1)`），渲染时再补回缝缘。**成片的内边 = 检测到的缝缘**（比实际暗带再外扩约 2–3 px，因为列投影做了平滑，属于有意留的纸边）；裁切编辑器里显示的四角则比该缝缘再向页内约 1% 短边（只影响编辑器显示，渲染/导出已补偿）。
- **改动前后数字**（同一设备、同一张合成跨页、同一测量口径）：

| | 改动前（`4ab6f09`） | 改动后（本轮） |
|---|---|---|
| 左页内边暗带 | **17 px**（距内边 0 px） | **0 px**（内 10% 内无暗带） |
| 右页内边暗带 | **17 px**（距内边 1 px） | **0 px**（内 10% 内无暗带） |
| 存储半边页面积（左/右） | 0.4599 / 0.5401 | 0.4449 / 0.5250（中间让出 3.0% 页宽 ≈ 42 px：中缝段约 1.6% + 两侧安全环各约 0.7%） |
| 渲染尺寸（左/右） | 579×900 / 681×900 | 560×900 / 662×900 |

  改动前两页各自跨过整条中缝（左边页含 635–653，右边页含 634–653），所以按“比纸面低 40 级”的列投影口径数是 17/17 px；这和旧文档记的 12 px 不是同一口径（旧数字未附脚本，无法复算）。测量方法写在 `SpreadSplitSampleTest`：渲染页中间 70% 行做列投影，纸面取列均值 90 分位，低于纸面 40 级记为暗列，只统计内边 10% 区域内离内边最近的连续暗带；改动前/后的现场在仓库外 `work/spread-issue23-inner/`（`baseline-result.json` / `final-result.json`），仓库内留存 [spread-split-before.json](spread-split-before.json) / [spread-split.json](spread-split.json)。

## 设备实测（synthetic=true，不是真实照片）

- 设备：`paperforge_api35(AVD)`，Android 15 / SDK 35，`emulator-5554`，x86_64；沿用已有模拟器，未新开。
- 入口：Compose 测试真实点按「更多」→「拆分双页 · 自动书脊」，不是直接调用 ViewModel。
- 样张：测试内用 `Canvas` 画的 1400×1000 跨页图：46% 处 18 px 暗带中缝、左右两页 18 行文字、从左到右纸面 246→196 的光照渐变；保存为 JPEG 后走完整编辑入口。不是真实照片，也不含用户样本。
- 结果 JSON：[spread-split.json](spread-split.json)（收边后，含 `seamLeft`/`seamRight` 与 `innerEdgeDarkBand`；同一份在仓库外 `work/spread-issue23-inner/final-result.json`）；改动前同口径测量：[spread-split-before.json](spread-split-before.json)（仓库外 `work/spread-issue23-inner/baseline-result.json`）。
- 生成图：[spread-source.jpg](spread-source.jpg)、[left.png](left.png)、[right.png](right.png)（本轮收边后的渲染页）。

| 指标 | 实测值 |
|---|---|
| 真实书脊位置（合成图设定） | 46% 页宽（像素中心 644/1399 ≈ 0.4603） |
| 检测 x / 方法 / 置信度 | **0.459943** / `dark-band` / **0.9415** |
| 位置偏差 | **0.04% 页宽**（1400 px 图约 0.5 px，含像素离散） |
| top / bottom / 倾斜 | 0.459943 / 0.459943 / 0 |
| 中缝左缘 / 右缘（top=bottom） | **0.452074 / 0.467811**（段宽 1.644% 页宽） |
| 暗带对比度、行覆盖、全带覆盖 | 162.4 级 / 1.000 / 1.000 |
| 左右墨迹占比 | 5.398% / 3.108% |
| 页数 | **1 → 2** |
| 左页 / 右页 | position 0 / 1，中心 x 0.2225 / 0.7375，面积 **0.4449 / 0.5250**（两半之间留出 0.0300 页宽的中缝+安全环） |
| 两页 `original` | 同一路径，原图 SHA-256 拆分前后一致（`17f4a783…e78d0`） |
| 左右页渲染尺寸 | **560×900 / 662×900**（左页占比 0.4583） |
| 每页内边暗带（渲染图列投影实测） | 改动前 **左 17 px / 右 17 px**（各跨过整条 18 px 中缝，含 1% 安全外扩；旧文档记的 12 px 属另一口径）；改动后 **左 0 px / 右 0 px**（内边 10% 内无暗列） |
| 撤销 | 2 → 1 页，回到同一原图，SHA-256 不变 |
| 设备测试 | `SpreadSplitSampleTest` **1 test / 0 failures**；同一次运行里 `ResolutionTest` 1/1 通过、`EditorExperienceTest` 失败（既有 touch-injection 问题，见下）。现场：`work/spread-issue23-inner/device-final.xml` |

视觉核对：把设备渲染出的 [left.png](left.png) / [right.png](right.png) 与源图拉回后逐张看图确认——左页文字顺序正确、右页起点正确、没有整页空白、内边不再有中缝暗带。这是对生成图片的检查，不是设备屏幕截图走查。

### 撤销崩溃与修复（#23 首轮附带）

首次设备运行在撤销把页数 2→1 时崩溃：`IndexOutOfBoundsException: Index 1 out of bounds for length 1`，位于 `ScanEditorLayout.kt` 的 pager `key = { pages[it].id }`——分页器还持有上一轮的索引范围，而页面列表已经缩短。这是“拆分后撤销”必然会走到的路径，因此做了最小防护：`key` 与页面内容都改为 `getOrNull`，不再越界。修复后同一测试连续两次 1/0 通过。这不是重构，也不改变正常页数下的行为。

## 合成单测数字

`BookSpineDetectorTest` **9 tests / 0 failures**（JVM，`app/build/test-results/testDebugUnitTest`；原 8 例未改，新增 1 例）。合成图 1400×1000，带 ±3 级确定性噪声；数值来自测试写入 JUnit XML 的实测输出。

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
| 中缝两缘（缝宽 14 px，新增） | left=0.453896 / right=0.466047（检出段 17.0 px，含 ±2 px 平滑外扩）；两缘夹住中点 x=0.459614，上下采样带一致（差 <0.002） |

`CropGeometryTest` **6 tests / 0 failures**（原 2 例未改 + 新增 4 例）：

| 新增用例 | 断言 |
|---|---|
| 两条边界各收一次 | 左页右边 = 0.4523、右页左边 = 0.4676；两页面积和 = 1 − 0.0153（只丢中缝），右页左边 > 左页右边 |
| 单线兼容 | `split(quad, .35, .55)` 与 `split(quad, .35, .55, .35, .55)` 结果完全相等 |
| 越界夹取 | 负数/大于 1 的边界夹到 0.15 / 0.85，与原单线版本相同，两页仍 valid |
| 交叉边界 | 左边 0.60 > 右边 0.40 时抛 `IllegalArgumentException`「书脊左右边界交叉」，不生成重叠页 |

阈值行为也因此固定：暗带/白缝的 x 偏差在合成图上都 ≤0.04% 页宽；小于 0.05% 页宽的异常段、宽于 6%（暗带）/3%（白缝）的异常段、覆盖不足的候选都会被拒。

## 既有测试（本轮 2026-10-31 复跑）

| 测试 | 结果 |
|---|---|
| `BookSpineDetectorTest`（JVM） | **9/9 通过**（原 8 例未改 + 新增“中缝两缘”1 例） |
| `CropGeometryTest`（JVM） | **6/6 通过**（原 2 例未改 + 新增两条边界/顺序/越界/单线兼容 4 例） |
| `PageSpreadsTest`（JVM） | **1/1 通过** |
| `ResolutionTest`（`emulator-5554`） | **1/1 通过** |
| `SpreadSplitSampleTest`（`emulator-5554`） | **1/1 通过**（含新增内边暗带断言） |
| `EditorExperienceTest`（`emulator-5554`） | **失败**：`Failed to inject touch input.`（`TestTag = 'crop-canvas'`，既有环境问题，见下） |
| 全量 JVM 单测 | 本轮未重跑；上一轮记录 **82 tests / 1 failure**，失败为 `GeometryTest.fileNamesCannotEscapeDestination`（既有，见下） |

- 现场：`work/spread-issue23-inner/unit-final.log`、`work/spread-issue23-inner/device-final.xml`（一次运行包含 `SpreadSplitSampleTest` / `ResolutionTest` / `EditorExperienceTest` 三个类，`SpreadSplitSampleTest` 与 `ResolutionTest` 通过、`EditorExperienceTest` 失败）。
- `EditorExperienceTest` 在本机 AVD 的裁切画布手势处报 `Failed to inject touch input`（找不到 `crop-canvas` 节点）。该失败已在 `docs/acceptance/issues/21/README.md` 记录为 `main` 上同样复现的已知问题；本轮未改该测试、未改触摸/裁切逻辑，不算 #23 收边回归。
- `GeometryTest` 期望 `safeFileName("...") == "纸匠文档"`，实际为 `PaperForge文档`；由提交 `1d9af77 Adopt supplied layered-page logo and PaperForge display name` 改默认名时留下，与 #23 无关。
- 断言变更：只改了 `SpreadSplitSampleTest` 里两条按旧“左右页共享缝中心线”写的断言（`leftQuad.points[1] == rightQuad.points[0]` 等），改为“左页内侧在缝左缘、右页内侧在缝右缘附近”；这是本次行为变更本身要求的，不是放宽。`BookSpineDetectorTest` 原有用例、`CropGeometryTest` 原有用例、`PageSpreadsTest`、`ResolutionTest` 均未改。

## MVP 限制

- **只做整页直线中缝**：书脊位置限制在页宽 30%–70%，上下端点差 ≤4% 页宽；曲线书脊、极宽/极淡阴影、超出中心带的中缝都不支持。
- **没有曲面/透视重建**：只做原四边形的左右内切；不 dewarp、不做曲面网格。自动拆分现在做了逐页内侧收边（见“内侧收边”），但只是把页边对齐到检测到的**直线**缝缘，曲面书页的弯折仍会残留。
- **安全环补偿只作用于自动拆分**：存储内边 = 缝缘 − 1% 短边（左）/ + 1% 短边（右），渲染时补回。因此裁切编辑器里看到的四角比缝缘再向页内约 1% 短边；手动「书籍双页与书脊」仍是单线、两页各留半条缝（本轮未接两条边界）。
- **只支持未旋转、未去斜页面**：`rotation != 0` 或 `deskewDegrees != 0` 直接拒绝并提示；旋转/去斜过的扫描件未接。
- **一侧空白的真实跨页会被拒**：内容分布要求两侧墨迹 ≥0.2% 且比值 ≤4，夹在章节间的空白页不能拆。
- **可能误判**：页面中央若有贯穿全高的深色竖线/表格边框（比文字列更连续），现阶段无法与装订线区分；两栏纯文字页有单测覆盖（未误判），但带中央框线的表格/表单未测。误判可撤销。
- **只测了合成图**：没有真实书页照片、没有 DocUNet 之类真实拍摄语料；检测阈值与收边数字只在这合成图与设备 Canvas 图上验证。
- **检测结果只用于本次拆分**：不写回页面元数据；`lastSpineDetection` 仅供诊断/测试。

## 未验证项

- 真实手机、其它 Android 版本/厂商 ROM；真实摊开书页（不同装订、阴影宽度、纸张颜色、折痕）与真实相机透视——收边后是否会把真实纸边裁掉一点、曲面处是否还留暗带，都未测。
- 旋转 90°/180°/270°、去斜（`deskewDegrees`）、已经 dewarp 过的页面。
- 白缝（分离页）在设备上的收边：单测只覆盖了 `seamLeft`/`seamRight` 的存在与顺序，设备样张只有暗带缝。
- 非全幅/偏心裁切四边形上的安全环补偿：按原图宽高换算，四边形实际裁剪比例不同时理论上有偏差，未用真实样本量化。
- 超大页/内存受限设备上 1600 px 渲染 + 检测的耗时；本轮未记录耗时指标（只有功能通过）。
- 撤销/重做多次、拆分后再对某一半再次拆分、拆分后立即 OCR 的组合场景。
- 手动「书籍双页与书脊」未收边，也未在设备上验证两条边界接口（只走了自动入口）。
- 导出 PDF/图片后的内边：本轮只检查 `ScanProcessor.render` 的 PNG，未逐页检查 PDF/图片导出。
- 深色主题、超大字体、横屏与无障碍下菜单项和结果提示的实际观感；设备测试是 Compose 点击，不是人工走查截图。
- `splitBook` 对两半清空 OCR 文本/布局的既有行为未在 #23 重新验证（沿用既有实现）。
- 设备上只走了“检出并拆分成功”的路径；“未检测到书脊时不写页面”目前只有单测和代码路径，未做设备断言。
- 非全幅裁切四边形上的偏心书脊：检测在渲染图上做（该渲染带 #21 的 1% `CropSafety` 外扩），比例随后直接套到原四边形；全幅样张没有误差，偏心书脊理论上存在约 1% 量级的换算近似，未用真实样本量化。
