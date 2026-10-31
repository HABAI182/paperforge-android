# #21 裁切安全外扩 + 票据面积门槛解耦

日期：2026-10-31。范围：两处小改动，检测四角、候选打分、refine 与既有测试断言均未改。

结论：

- **A 裁切安全外扩**：真正用于裁切的四边形在读取像素前向外扩 **图像短边的 1.0%**（3024 px 原图约 30 px），并保证不越界、不自交、`Quad.valid()` 仍成立、且包含原来的检测四边形；无法安全外扩时原样回退。4_2 右边被切掉的字符列不再丢字；7_2 左边纸面内切的最差处（p90）从 25 px 收窄到 13 px。
- **B 票据面积门槛解耦**：`SettingKeys.CaptureArea`（默认 0.08）仍只约束普通单页；四角长宽比 ≥ **1.8** 的长票据允许 **0.04** 的面积下限。4_2（面积 7.39%、长宽比 2.62）现在会 arm 自动快门，普通 6% 面积的 A4 文档仍不 arm。
- **合成几何零回归**：synthetic-v1 平均 IoU 仍为 **0.9906321972441199**（与 `geometry-after-frame-guard.json` 逐位相同），误裁率 0%，无文档误检率 0%。

## 1. A · 裁切安全外扩

### 1.1 为什么放在裁切读取处，而不是检测出口

检测四边形是基准（synthetic-v1 的 IoU 直接量它）与相机取景框、arm 判断的输入；如果把它整体外扩，synthetic 的 IoU 会掉约 0.02（按面积增量粗略估算），远超本任务的 0.005 预算，而且 4_2 的面积会从 7.39% 变成 8.5%，B 的验证就失去意义。

因此外扩只发生在**实际裁切时**：`PageDecoder.crop()` 在计算解码 ROI 之前把四边形扩一圈，`ScanProcessor.transform()` 拿到并 warp 的正是这个扩大后的四边形。ROI 也按扩大后的框解码，所以多出来的一圈是**真实像素**，不是边缘复制。检测结果、页面里保存的 `corners`、取景框与 arm 判断都不变。

> 位置说明：任务建议放在 `DocumentDetector.refine` 之后或 `ScanProcessor.transform` 读取 quad 附近。这里选在 `PageDecoder.crop()`——它是 `transform` 的直接输入，也是唯一决定解码 ROI 的地方；若放到 `transform` 内部，ROI 外的像素并不在解码位图里，只能得到 `BORDER_REPLICATE` 的假像素。

### 1.2 算法与公式

`app/src/main/java/cn/paperforge/app/model/CropSafety.kt`（新增，纯数学，无 Bitmap）：

1. 归一化四角换算到像素坐标；对每条边求单位方向 `(dx, dy)`（`Quad.valid()` 保证四角同向排列，外侧法线为 `(dy, -dx)`）。
2. 每条边沿外法线平移 `d = margin × min(width, height)`。
3. 相邻两条平移后的直线求交，得到四个新角点。
4. 角点逐轴 clamp 到 `[0, width-1] × [0, height-1]`（封顶到画幅内）。
5. 校验：`Quad.valid()`（含凸性 / 不自交 / 面积 > 0.015 / 在 [0,1] 内）、面积不小于原四边形、且原四边形的四个角都在新四边形内。任一条不满足则**原样返回检测四边形**。

外扩量：

| 项 | 值 |
|---|---|
| `cropSafetyMargin` | **0.01**（`app/src/main/assets/scan_params.json`，代码兜底 `CropSafety.Margin`） |
| 单位 | 每边 = 图像短边 × 1.0% |
| 3024×4032 原图 | 约 30 px/边 |
| 1350×1800 解码（1800 上限） | 约 13.5 px/边 |
| 满幅四边形（`Quad.Full`） | clamp 后与原四边形相同，成片行为不变 |

### 1.3 验证（JVM 单测）

新增 `app/src/test/java/cn/paperforge/app/model/CropSafetyTest.kt`，7 条全过：

- 满幅四边形外扩后不变；
- 4_2 类票据外扩后：`valid()`、四角在 [0,1]、面积变大、包含原四角、每角位移 10–70 px（3024 短边）；
- 斜拍（透视）四边形保持凸性与顺序；
- 贴边四边形外扩后仍包含原区域、不越界（clamp 失败时回退原四边形）；
- 最小合法面积（0.015）四边形外扩后仍有效；
- `margin=0`、非法尺寸（0/1 px）原样返回；
- 400 个随机合法凸四边形：外扩结果全部 `valid()`、包含原四边形、在画幅内、面积不缩小、相邻角叉积仍为正（不自交）。

## 2. B · 票据面积门槛解耦

### 2.1 改动

`AutoCaptureGate`（`app/src/main/java/cn/paperforge/app/model/Geometry.kt`）新增 `areaFloor()`：

```text
长边/短边 = max(pixelSides) / min(pixelSides)     // 用真实帧像素尺寸还原
floor = 长宽比 ≥ 1.8 ? min(captureArea, 0.04) : captureArea
```

`update()` 的面积判断从 `quad.area < minimumArea` 改为 `quad.area < areaFloor(quad, frameWidth, frameHeight)`；`CameraScreen` 把分析帧的 `frameWidth/frameHeight` 传进去。

- `SettingKeys.CaptureArea` 的默认值与取值范围（0.08，0.05–0.4）**未改**，普通单页行为不变。
- 新增构造参数 `receiptAspect = 1.8`、`receiptMinimumArea = 0.04`；调用方不传帧尺寸时退回旧行为（只按 `captureArea`），保证既有调用和测试语义不变。
- 只有长宽比 ≥ 1.8 才降门槛，且降到的下限是 4%，所以“小到 3% 的票据”仍不会误触发。

### 2.2 单测（`CaptureAreaGateTest`，6 条全过）

| 用例 | 输入 | 期望 | 实测 |
|---|---|---|---|
| 4_2 实拍四角（DocUNet 探针记录，1350×1800） | 面积 7.39%、长宽比 2.62 | arm | **arm** |
| 普通 A4 页面 | 面积 6.0%、物理长宽比 1.41 | 不 arm | **不 arm** |
| 旧调用（不传帧尺寸） | 4_2 四角 | 不 arm（= 旧固定 8% 门槛） | **不 arm** |
| 3% 面积长票据 | 长宽比 3.0 | 不 arm | **不 arm** |
| 12% 大面积页面 | 长宽比 1.56 | arm | **arm** |

### 2.3 设备侧（DocUNet 20 张，真实检测四角）

探针用设备上的 `captureArea=0.08`、`stableMs=600`、`cooldownMs=1500` 逐帧模拟 `AutoCaptureGate`：

| 文件 | 面积 | 长宽比 | 面积下限 | arm |
|---|---:|---:|---:|:---:|
| 4_2.jpg | 7.39% | 2.62 | 4% | **是** |
| 7_2.jpg | 8.02% | 2.30 | 4% | **是** |
| 其余 18 张 | 8.28%–17.19% | — | 8%（或 4%） | 是（此前也是） |

改动前 4_2 是 20 张里唯一面积低于 8% 的（7.39%），旧代码下永远不会 arm；本次探针 20/20 全部 arm。

## 3. 合成几何指标（synthetic-v1，74 检测 + 8 无文档）

命令：`QualityBenchmarkTest`（`benchMode=current`、`benchStage=detect`）+ `scripts/bench/scan_quality_metrics.py`。

| 指标 | 本次改动前（已提交基线） | 本次改动后 |
|---|---:|---:|
| 平均 IoU | 0.9906321972441199 | **0.9906321972441199** |
| 中位 IoU | 0.9955662001932334 | **0.9955662001932334** |
| 平均角误差 | 0.252865% | **0.252865%** |
| 误裁率（IoU<0.7） | 0% | **0%** |
| 检测率 | 77.03%（17 例满幅回退） | **77.03%** |
| 无文档误检率（置信度≥0.8） | 0% | **0%** |
| 检测耗时 p50 / p95 | 290.5 / 439.4 ms（模拟器） | 367.7 / 465.3 ms（模拟器波动，不作快慢结论） |

数值见 [crop-safety.json](crop-safety.json) 的 `syntheticGeometry`；检测路径未改，指标逐位相同。

## 4. DocUNet 4_2 / 7_2 边缘对照

说明：语料无真值四角，以下只有**主观视觉判断 + 粗测边界偏移**，不是精度结论。放大图均来自全分辨率原图；蓝线是本次实际使用的裁切边界。

### 4_2.jpg 右边（问题最严重的一张）

- 改前：[edge-4_2-side1-before.jpg](crop-safety/edge-4_2-side1-before.jpg) —— 红框压住 `00651`、`$21.56` 最后一列与 `Chip Read` / `Issuer` 的末字母。
- 改后：[edge-4_2-side1-after.jpg](crop-safety/edge-4_2-side1-after.jpg) —— 蓝线（裁切边界）落在纸面之外，字符列完整。
- 对照：[compare-edge-4_2-side1.jpg](crop-safety/compare-edge-4_2-side1.jpg)。
- 实际成片（App 内 `PageDecoder.crop` + `ScanProcessor.transform` 渲染）：[crop-4_2-right-edge-after.jpg](crop-safety/crop-4_2-right-edge-after.jpg) —— 右列 `00651I`、`387266638351326`、`Chip Read`、`Issuer`、`$21.56` 均完整，右侧只多出纸边与一条背景。

### 7_2.jpg 左边

- 改前：[edge-7_2-side3-before.jpg](crop-safety/edge-7_2-side3-before.jpg)
- 改后：[edge-7_2-side3-after.jpg](crop-safety/edge-7_2-side3-after.jpg)
- 对照：[compare-edge-7_2-side3.jpg](crop-safety/compare-edge-7_2-side3.jpg)
- 实际成片：[crop-7_2-left-edge-after.jpg](crop-safety/crop-7_2-left-edge-after.jpg) —— 左侧粉色页眉与全部正文完整；弯曲处仍有极窄白边被切（见下）。

带外扩框的 contact sheet：[4_2-sheet.jpg](crop-safety/4_2-sheet.jpg)、[7_2-sheet.jpg](crop-safety/7_2-sheet.jpg)（红=检测框，蓝虚线=裁切安全框，METRICS 栏含 aspect / floor / arm / crop 尺寸）。

粗测边界偏移（全分辨率 3024 px，正数=纸面在该边之外；Otsu 边界扫描，扫描起点 -6 px 偏差，仅供参考）：

| 图/边 | 检测边 | 裁切边（+1.0%） |
|---|---:|---:|
| 4_2 右边 | 中位 40 px、p90 51 px | 中位 10 px、p90 25 px |
| 7_2 左边 | 中位 1 px、p90 25 px | 中位 -6 px、p90 13 px |

**诚实结论**：字符列已被完整保留（放大图目测 4_2 的字符最外一列约在检测边内 10–15 px，30 px 外扩后仍有约 15–20 px 余量；7_2 的字符本来就不贴边）。但**卷曲处的一小条白边仍会被切**（4_2 中位仍有 10 px、7_2 最差处 13 px 纸面在裁切边外）——直边四边形无法贴合弯曲，彻底解决需要曲面展开，不在本次范围。

## 5. 测试

| 测试 | 结果 |
|---|---|
| `testDebugUnitTest`（全量 57 条） | **56 通过、1 失败**：`GeometryTest.fileNamesCannotEscapeDestination`（`纸匠文档` vs `PaperForge文档`，HEAD 上就存在，`confidence-calibration.md` 已记录，非本次改动） |
| 新增 `CropSafetyTest` | **7/7 通过** |
| 新增 `CaptureAreaGateTest` | **6/6 通过** |
| `connectedDebugAndroidTest`：`QualityBenchmarkTest`(detect)、`DocUnetProbeTest`、`CameraQualityTest`、`LearnedDetectorTest` | **4/4 通过，0 失败** |
| 追加回归：`ResolutionTest`、`CaptureGeometryFlowTest`、`OcrGeometryTest` | **3/3 通过** |

设备：`emulator-5554`（AVD paperforge_api35，API 35，x86_64，无头，`-gpu swiftshader_indirect`），未新开模拟器；`detector=HYBRID`，`preview=false`，解码上限 1800。

### 5.1 与本改动无关的三条既有失败（实测记录，未修）

在同一次追加回归中：

- `UpgradeSmokeTest.historyAndBackupRestoreRetainOriginals`：期望 `AUTO`，实得 `TEXT` —— 设备 `preferences.xml` 里 `filter=TEXT`（此前会话/手动验证留下的设置，不是代码改动）。恢复默认 `AUTO` 后该测试通过。
- `LibraryComparisonTest`、`HistoryBackupTest`：在 `BackupStore.backup` 读历史库时 `FileNotFoundException`。该模拟器的库有 129 个文档 / 188 条输出，其中 **47/260 个被引用的文件已被清理**（含 `/files/exports/...` 与 `/cache/accessibility-*.png`），备份会在第一个缺失文件处失败。改动不触碰数据库、备份或缓存，失败发生在任何渲染之前。
- 这类失败需要清理 App 数据（或修复历史库）才能消失；本轮没有清库，以免丢掉已推的 `files/bench`、`files/docunet` 语料。

## 6. 未验证项 / 限制

- **只在模拟器**（x86_64 + swiftshader）；真机相机预览、真实 `ImageAnalysis` 帧、不同传感器比例未测。
- **外扩是固定比例**：1.0% 对 3024 px 照片是 30 px；更小的解码尺寸（如相机预览 720 px）不裁切，不涉及；更高像素图会让同样比例对应更多像素。极端卷曲（内切 > 1% 短边）仍会切到白边甚至字符。
- **外扩方向是四边等量**：不区分卷曲边与平直边，平直边会多带约 30 px 背景（4_2 成片左右两侧可见）。若后续按边自适应，可再降。
- **没有用真实文字检测验证“无压字”**：结论来自人工放大看图与边界偏移粗测，未做 OCR 字符框级比对。
- **票据门槛只按长宽比**：1.8 与 4% 是按 DocUNet 20 张与合成语料定的初值，真实票据（超长小票、名片、标签）分布未统计；极斜拍导致的长宽比失真可能让普通页面误降到 4%。
- **DocUNet 只有 20 张、无真值四角**：不能说“几何更准”，只能说 4_2/7_2 的字符不再被切、20/20 仍返回四角。
- 未验证：`DewarpTrace`/曲面展开路径、手动裁切（CropDialog）后的安全外扩观感、PDF/OCR 输出因多出 1% 背景产生的版面差异。

## 7. 改动文件

- 新增 `app/src/main/java/cn/paperforge/app/model/CropSafety.kt`：外扩几何 + 合法性/包含性校验。
- `app/src/main/java/cn/paperforge/app/imaging/PageDecoder.kt`：裁切 ROI 与映射四边形改用外扩后的四边形；`Decoded.aspect` 仍用原四边形。
- `app/src/main/java/cn/paperforge/app/model/Geometry.kt`：`AutoCaptureGate.areaFloor()`、`update()` 增加 `frameWidth/frameHeight`（默认 0 = 旧行为）。
- `app/src/main/java/cn/paperforge/app/ui/CameraScreen.kt`：把分析帧宽高传给 gate。
- `app/src/main/assets/scan_params.json`：新增 `"cropSafetyMargin": 0.01`。
- 新增 `app/src/test/java/cn/paperforge/app/model/CropSafetyTest.kt`、`CaptureAreaGateTest.kt`。
- `app/src/androidTest/java/cn/paperforge/app/quality/DocUnetProbeTest.kt`：新增 `aspect/areaFloor/autoArm/cropQuad/cropArea/cropWidth/cropHeight` 字段、蓝色裁切安全框、实际裁切图 `<name>-crop.jpg`；既有断言未改。

## 8. 复现

```powershell
# 合成几何（current / detect）
./gradlew :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=cn.paperforge.app.quality.QualityBenchmarkTest" `
  "-Pandroid.testInstrumentationRunnerArguments.benchMode=current" `
  "-Pandroid.testInstrumentationRunnerArguments.benchStage=detect"
adb exec-out run-as cn.paperforge.app cat files/quality-current/results.json > results.json
python scripts/bench/scan_quality_metrics.py results.json bench-private/synthetic-v1/manifest.json out/

# DocUNet 20 张（含裁切图与安全框）
./gradlew :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=cn.paperforge.app.quality.DocUnetProbeTest" `
  "-Pandroid.testInstrumentationRunnerArguments.probeRun=cropsafety"

# 新单测
./gradlew :app:testDebugUnitTest "--tests" "cn.paperforge.app.model.*"
```

证据文件：[crop-safety.json](crop-safety.json)（阈值、合成指标、逐图 arm/裁切、边界偏移）、[crop-safety-docunet.json](crop-safety-docunet.json)（20 张探针原始结果）、[crop-safety/](crop-safety/)（边缘对照与成片放大图）。
