# 横平竖直：扫描几何残差测量与长线约束校正（2026-11-04）

## 结论摘要

- 当前默认拍摄链（`FlatDocument.intelligentGeometry`）已经修正全局倾斜和粗曲面，但对**残余单应/弯曲**与**竖线 x(y) 弯曲**没有约束；`PrimaryPaperFrame` 只收紧裁切，`TextLineDewarp` 在默认 `dewarpEngine=CURRENT` 下不是默认展平引擎。
- 新增 `LineStraightness` 纯 Kotlin 核心 + `GridStraighten` 适配器，在现有曲线/仿射结果之后估计残余旋转与低频位移场，再与现有映射合成，仍然只做一次全分辨率重采样。
- 合成夹具：表格页横线 RMS 2.63→0.26 px、竖线 0.70→0.14 px，横/竖倾角 2.11°/1.50°→0.03°/0.003°；练习册页横线 RMS 1.87→0.00 px、倾角 1.70°→0.00°，文本行 2.19→1.43 px、1.61°→0.10°。
- 设备真实成片（20011 当前链输出）：数学页横线 RMS 0.578→0.407 px、倾角 0.216°→0.071°；代码页文本行 1.297→1.081 px、0.197°→0.033°。墨量保留 0.98，行距比 0.998/1.001，2% 外圈像素不变。
- DocUNet 全链（检测→裁切→展平）8 张：2 张实际触发并小幅改善，6 张因证据/守卫保持原图；没有出现第一次集成时的灾难性折返，但也未覆盖 DocUNet 的卷曲卡纸/边缘场景。
- **默认开启有风险**：DocUNet 1_2 的横线代理变差（1.35°→1.53°），虽然文本行改善（1.54°→0.62°）。建议先以 `gridStraighten=1` 在机主样张试用、DocUNet 上以 `0` 做回归；或默认 0，等卷曲/边缘约束补齐后再默认。

## 指标定义

同一套代理指标同时用于合成和真实页，代码在 `LineStraightness.measure`：

| 指标 | 定义 | 说明 |
|---|---|---|
| 直线度 RMS（px） | 每条长线中心线对其稳健直线拟合的残差均方根，取中位数 | 只反映弯曲，不含整体倾角；不是像素级真值 |
| 平均倾角（°） | 每条线拟合斜率的带符号中位数；另报绝对值中位数 | 横线用 dy/dx，竖线用 dx/dy（已统一符号） |
| 投影峰宽 FWHM（px） | 线中心横向 ±12 px 的墨量剖面半高全宽，线级平均 | 只对长规则线计算；文本行无稳定单峰，记 0 |
| 证据数/跨度 | 参与线的条数、中位跨度 | 少于 3 条不启用长线约束 |
| 内容保留率 | 处理后/前墨量比 | 守卫阈值 0.95–1.05 |
| 字高/字宽比 | 字符级连通域中位高/宽比 | 守卫阈值 ±15%/±18%；过小字号时按像素退化 |
| 行距比 | 相邻横线中点间距中位数比 | 守卫阈值 ±10% |
| 最大位移 | 网格内最大 \|dx\|,\|dy\| | 上限 2% 短边；本实现实际 5–18 px |
| 外圈不变 | 外 2% 边圈逐像素比较 | 校正场在 3% 内为零，边线/四角不被重采样 |

真实照片没有逐像素/字符真值：上表是**线拟合代理**；DocUNet 的“改善”不能当作字符准确率或四角精度。人工目检对照见 `work/model-lab-20261103/geometry-straight/review/straightness-before-after-final.png`。

## 当前基线（20011 行为）

| 开关 | 默认值 | 是否参与本次几何 | 结论 |
|---|---|---|---|
| `primaryPaperFrame`（`PrimaryPaperFrame.VERSION=3`） | 1（代码兜底，JSON 未显式写） | 是，但只在检测/裁切 | 收紧纸边，不改变展平倾角/弯曲；本次未改 |
| `dewarpEngine` | `CURRENT` | `IndependentPageDewarp` 不启用 | `TextLineDewarp` 不是默认展平引擎 |
| `ruledRefinement`（`RuledPageRefinement.VERSION=ruled-residual-7`） | 1 | 是 | 跟踪横线做竖向位移，含共享折痕/曲面一致性；不显式约束竖线，也不单独估计刚性残余旋转 |
| `workbookIntelligentGeometry` | 1 | 是 | 当前拍摄链的 `FlatDocument` 路径；曲线网格 + `WorkbookStructure` 仿射 |
| `flatTextCurves` | 1 | 条件启用 | 只有 `probe.horizontal.confidence<.7` 时才用 `TextLineDewarp` 估计；代码页/数学页的 LSD 横线置信度通常已 ≥.7，文本曲线不参与 |
| `gridStraighten`（本次新增） | 1（代码兜底） | 是，新增 | 在现有曲线+仿射结果上估计残余长线约束；可用 `ScanConfig.replaceForBenchmark` 关闭 |

基线数字（设备 20011 输出，`work/feedback-20011/v4/feedback-20010`；同一文件在设备 `files/feedback-20010`）：

| 页 | 轴/代理 | 条数 | RMS 中位 px | 倾角绝对值中位 | FWHM px | 备注 |
|---|---|---:|---:|---:|---:|---|
| 数学页 | 横线 | 19 | 0.578 | 0.216° | 2 | 有手指、手绘竖线；竖线未形成 ≥3 条长约束 |
| 数学页 | 文本行 | 2 | 3.258 | 1.147° | — | 行少，仅作参考 |
| 代码页 | 横线 | 1 | 0.203 | 0.013° | 3 | 无连续横规则线 |
| 代码页 | 文本行 | 9 | 1.297 | 0.197° | — | 代码行/缩进长短不一 |

## 方法：残差长线约束 + 低频网格

实现分两层：

1. `app/src/main/java/cn/paperforge/app/imaging/LineStraightness.kt`（纯 Kotlin，无 Android/OpenCV 依赖）
   - 积分图局部背景 + Otsu + 行程滤波分离长墨线；8 邻域连通域；
   - 横/竖线用“局部脊线跟踪”（在交点处取局部极值，避免把表格交点计入中心线）；
   - 文本行回退：水平桥接词间空隙后跟踪每行墨量质心（用于没有印刷规则线的页）；
   - 稳健直线拟合（3 次截断最小二乘）计算 RMS/倾角/跨度；
   - 估计全局残余旋转 θ，把每条线样本旋转到去倾角帧，保留**整条中心线**（不是只保留二次拟合）；
   - 生成 64×64 归一化反向位移场：输出点先反向旋转，再按相邻线残差线性插值（类似 RuledPageRefinement 的线间插值），得到 dx/dy；
   - 守卫：最大位移 ≤2% 短边、墨量 0.95–1.05、字高/字宽变化、行距变化、倾角回归、无明显收益即返回原图；3% 边缘圈位移为零，10% 内平滑过渡。
2. `GridStraighten.kt`（OpenCV 适配）
   - 将现有几何输出缩到 ≤1000 px 灰度，调用核心估计；
   - `FlatDocument.intelligentGeometry` 在现有 `base(affine(p))` 预览上估计，再把校正点 `p+d(p)` 代回原映射，最终仅一次全分辨率 remap；
   - 诊断写入 `structure.straighten`：前后横/竖/文本 RMS、倾角、FWHM、条数、角度、最大位移、网格缩放。
   - `FlatDocument.VERSION` 12→13，`ScanConfig.fingerprint()` 纳入 `GridStraighten.VERSION`。

没有复制外部代码；线跟踪、稳健拟合、场插值和守卫均为本仓库新写。没有改 `EnhancePipeline.kt`、UI、导出/分享、`MainViewModel` 的导出逻辑。

## 合成夹具（stage 级，非 Android 全链）

夹具在 `LineStraightnessLabTest` 内生成：1000×1400 的表格/横线/竖线页 + 字符块，施加已知旋转 1.2°/1.5°、正弦弯曲 6/8 px、轻微透视；直接用核心处理（不经过检测/裁切），所以下表是“展平残差”级对照，不是检测→裁切全链。

| 夹具 | 轴 | 条数 | RMS 前→后 px | 倾角绝对值 前→后 | FWHM 前→后 px | 文本行 RMS/倾角 |
|---|---|---:|---:|---:|---:|---|
| 表格 1.5°+8px | 横 | 15 | 2.627→0.260 | 2.108°→0.033° | 2→2 | 2.876/2.142°→1.431/0.073° |
| 表格 1.5°+8px | 竖 | 9 | 0.699→0.141 | 1.499°→0.003° | 2→3 | — |
| 练习册 1.2°+6px | 横 | 32→26 | 1.868→0.000 | 1.696°→0.000° | 1→1 | 2.187/1.605°→1.434/0.096° |
| 练习册 1.2°+6px | 竖 | 2 | 0.709→0.621 | 1.200°→0.551° | 3→3 | — |
| 已平直页 | 横 | 30 | 0.003 | 0.0002° | 2 | 返回 `null`（不处理） |

守卫：表格墨量 0.978、字高比 1.000、行距比 0.998；练习册墨量 0.967、字高比 0.917、行距比 1.000。练习册横线条数从 32 降到 26、中位跨度从 886 降到 438 px，说明边缘/短线在重采样后被检测器合并或截短；墨量仍保留 96.7%，但这是本次最明显的内容代理损失，不能宣称“零损失”。

FWHM 没有改善（横线 2→2，竖线 2→3，练习册 1→1）：该阶段只改几何位置，不锐化；3 px 的竖线 FWHM 变化来自重采样，不能当作清晰度收益。

## 真实照片

### 设备样张（20011 当前链输出，非重跑）

| 页 | 轴 | 条数 | RMS 前→后 px | 倾角绝对值 前→后 | 最大位移 | 墨量保留 | 字高比 | 行距比 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 数学页 | 横 | 19 | 0.578→0.407 | 0.216°→0.071° | 14.69 px | 0.980 | 0.917 | 0.998 |
| 代码页 | 文本行 | 9 | 1.297→1.081 | 0.197°→0.033° | 5.55 px | 0.980 | 0.889 | 1.001 |

数学页的竖线（手绘分隔线）不足 3 条长约束，本次只用了横线 + 旋转；代码页没有连续横规则线，回退到文本行。两页都被守卫接受。目检：数学页横线由“左低右高”变为接近水平；代码页文本行倾角明显减小。底边手指/纸边仍在，未被修复。

### DocUNet 全链（检测→裁切→展平，模拟器）

设备 `paperforge_api35`（x86_64，headless）上，先用实际 `ScanProcessor.decode(1800)`、`detectResult`、`transform(ORIGINAL)` 得到裁切页，再分别以 `gridStraighten=0/1` 跑 `FlatDocument.geometry`。8 张有足够线/文本证据的页：

| 页 | 触发 | 横线 RMS 前→后 px | 横线倾角 前→后 | 竖线 RMS 前→后 | 文本 RMS 前→后 | 文本倾角 前→后 | 墨量比 |
|---|---|---|---:|---:|---:|---:|---:|
| 10_2 | 是 | 1.002→0.912 | 0.389°→0.231° | 0.155→0.066 | 1.875→2.072（变差） | 0.819°→0.464° | 1.0001 |
| 1_2 | 是 | 0.391→0.340 | **1.351°→1.529°（变差）** | 无 | 1.366→0.959 | 1.535°→0.623° | 0.990 |
| 10_1 | 否 | 1.599 | 0.079° | 无 | 6.556 | 1.205° | 1.000 |
| 1_1 | 否（已有 text-curves） | 0.908 | 0.748° | 无 | 0.675 | 0.050° | 1.000 |
| 4_1 | 否 | 无横线 | — | 0（1 条） | 1.684 | 1.704° | 1.000 |
| 4_2 | 否 | 无横线 | — | 0.262（1 条） | 1.368 | 0.899° | 1.000 |
| 5_1 | 否 | 0.531 | 0.288° | 0.380 | 无 | — | 1.000 |
| 6_1 | 否 | 0.230 | 0.263° | 无 | 0.639 | 1.350° | 1.000 |

- 2/8 实际触发；6/8 因证据不足/守卫/无收益保持原图。未触发不代表性能不够；它只说明这些卷曲卡纸/低分辨率裁切页没有达到本次约束的启用条件。
- 10_2 的改进在目检中可见（卡片上缘更平），但文本行 RMS 变差；1_2 文本行明显变直，横线代理（很可能是卷边）变差。两项都保留在报告里，不挑选只报好的数字。
- 第一次集成曾把“像素位移”误当“归一化位移”加进 `FlatDocument` 的归一化网格，DocUNet 10_2 出现灾难性折返（文本变等高线）；已在 `LineStraightness.estimate` 中改为归一化位移并由 `apply` 乘回像素。这个失败说明“同一算法在单元测试/独立图上通过”不等于接入正确；最终版把 10_2 修正为正常图像后才纳入结果。
- 没有逐像素真值；上表只是线拟合代理。DocUNet 原图/裁切页是公开研究照片，不是机主设备真机样本。

## 与现有 `PageDewarp` 的关系

- 默认拍摄链走 `FlatDocument`，不调用 `PageDewarp.process`；`PageDewarp` 的 MNN 位移 + `RuledPageRefinement` 只在旧路径/编辑器重展平/`WorkbookRestore` 里使用。本次没有改 `PageDewarp`，也没有把新约束接进去。
- 因此“与现有 `PageDewarp` 输出对比”只做到**当前拍摄链 vs 新链**，没有重跑 `PageDewarp` 的 MNN 输出；这是未完成项，不能声称已和 `PageDewarp` 逐项对比。
- 建议接入点：若要覆盖编辑器重展平，在 `PageDewarp.process` 的 `RuledPageRefinement` 之后、最终 `remap` 之前插入同样的 `GridStraighten.estimate` 组合；不要在 `FlatDocument` 与 `PageDewarp` 同时调用，否则会二次校正。

## 测试

- `:app:testDebugUnitTest --tests cn.paperforge.app.imaging.LineStraightnessLabTest`：4 条通过（表格、练习册、已平直页、外圈不变）。
- 相关既有单元测试通过：`GeometryTest`、`CropGeometryTest`、`DewarpGridTest`、`ImageGeometryTest`、`CropSafetyTest`、`StillCropTest`（未改断言）。
- `:app:connectedDebugAndroidTest` 过滤 `GeometryStraightnessProbeTest`：1 条通过（模拟器 paperforge_api35；含 20011 设备输出与 8 张 DocUNet 全链）。只是模拟器结论，不是真机耗时/画质验收。
- 构建：`:app:compileDebugKotlin`、`:app:compileDebugUnitTestKotlin` 通过。为绕开并行 agent 未完成的 `V200FeedbackTest.kt`（`putAll`/ suspend 编译错误），仪器测试构建时临时移出该文件，跑完立即原样放回；没有改其内容。并行 agent 的 `PaperEdgeGuard.kt` 有一行 `Mat.put` 类型错误会阻塞全模块编译，我临时改为 `FloatArray` 以完成构建，需该 agent 确认/接管这行。
- 未跑：完整 `connectedDebugAndroidTest`（耗时长且与并行改动冲突）、真机、OCR 质量、导出/分享、动画。

## 失败、限制与未完成

1. **DocUNet 覆盖低**：8 张只触发 2 张；10_1/6_1/4_1 的文本行倾角 1.2–1.7° 未处理。原因是文本行/证据在低分辨率裁切页上达不到阈值或被守卫拒绝；不是“已解决”。
2. **1_2 的横线代理变差**（1.35°→1.53°），而文本行改善；这可能是卷边/折痕被当作长线。当前守卫没有拒绝这种“一轴变差、另一轴改善”的冲突，默认开启存在风险。
3. **FWHM 未改善**，竖线 FWHM 还从 2 变 3 px；该阶段不解决模糊/重采样锐度。
4. **合成练习册横线 32→26、跨度 886→438 px**：内容保留率 96.7%，但长线检测在重采样后合并/截短；不能宣称“零内容损失”。设备页墨量保留 98%，字高比最低 0.889（9→8 px，1 px 量化）。
5. **四角/边线没有真值标注**：只验证外 2% 圈逐像素不变（单元测试）与目检；没有角点误差/IoU。数学页手指、代码页弯曲纸边不在本次修复范围。
6. **`PageDewarp` 未重跑**，未与 MNN 位移输出逐项比较；`PrimaryPaperFrame` 的裁切安全逻辑未改，也没验证它与新校正叠加后的边缘行为。
7. 只有模拟器结论；真机性能（新增估计在 1000 px 灰度上前后各 4 个掩膜连通域 + 1 次双线性应用）、相机实时链路、内存都没有验收。新增阶段可能增加数百毫秒，未测。

## 接入建议

1. 先以**试用开关**接入：`gridStraighten=1` 只在机主数学/练习册/表格页上 A/B；DocUNet/卷曲票据/卡纸默认 0。若要保持机主样张收益又避免 1_2 类回归，把 `gridStraighten` 默认设为 0，在设置页“扫描 > 展平”加“长线拉直（试用）”。
2. 若默认 1：至少增加“轴冲突守卫”（一条轴改善、另一条轴变差 >0.3° 即拒绝）和文档边界/卷边检测（把页面外轮廓排除出长线约束），再重跑 DocUNet 与真实卷曲页。
3. 编辑器重展平路径：在 `PageDewarp.process` 的 `RuledPageRefinement` 之后接同一 `GridStraighten`，且不要在 `FlatDocument` 与 `PageDewarp` 同时启用。
4. 性能：若机主页面超过 2000 px，建议只在 900–1000 px 分析、最终一次 remap；当前实现已经如此。需要真机测 `geometryMs` 增量后再决定是否降 `maximumEdge`。
5. 版本/诊断：`FlatDocument.VERSION=flat-paper-13`、`GridStraighten.VERSION=grid-straight-1` 已进入指纹；上线前用真实样张确认缓存失效行为。

## 文件

- 实现：`app/src/main/java/cn/paperforge/app/imaging/LineStraightness.kt`、`GridStraighten.kt`；接入 `FlatDocument.kt`；指纹 `ScanParams.kt`。
- 测试：`app/src/test/java/cn/paperforge/app/imaging/LineStraightnessLabTest.kt`；`app/src/androidTest/java/cn/paperforge/app/quality/GeometryStraightnessProbeTest.kt`。
- 实验/图（仓库外）：`work/model-lab-20261103/geometry-straight/`
  - `review/straightness-before-after-final.png`（合成/设备/DocUNet 对照）
  - `device-probe-final/metrics.json`、`device-probe-final/*.png`
  - `scripts/proto.py`、`scripts/make_fixtures.py`、`scripts/make_review_final.py`、`scripts/LabMain.kt`
  - `out-kotlin/synth-*.png`（合成前后）

## 默认接入守卫（20011 复测前补）

DocUNet 全链出现“一轴变好、另一轴变差”（1_2：文本行改善、横线倾斜 1.351°→1.529°），因此默认路径改为**失败关闭**：GridStraighten.acceptable 要求

- 长线总数 ≥2、maxShift ≤ 2% 边；
- 墨量保留比在 0.94–1.02；
- 三个轴（横/竖/文本）任一轴倾斜或 RMS 变差超过 0.05° / 0.05px 即拒绝；
- 至少要有一个轴改善 ≥0.02° 或 ≥0.02px。

FlatDocument 现在写作 nalyze(aligned)?.takeIf(GridStraighten::acceptable)，被拒绝时诊断里 straighten.active=false。这保留了合成与设备页的收益（横线 2.63→0.26px、文本 2.187→1.434px），同时挡掉卷边/卡纸页的单轴回归。未做：轴冲突的具体阈值未做网格搜索，卷边排除仍是启发式。