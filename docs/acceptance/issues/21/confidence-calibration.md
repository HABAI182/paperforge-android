# #21 检测置信度标定（去饱和）

日期：2026-10-31。范围：只改 `NeuralEdgeDetector.fit()` 的**打分**，不改候选生成、四角几何、refine 与任何调用方阈值。

结论：旧公式在合成语料 57/57、DocUNet 真实照片 20/20 上固定为 0.99；新公式把合成语料 57 个定位检测拉到 0.795–0.919（中位 0.900，σ=0.019），DocUNet 拉到 0.702–0.910（中位 0.866，σ=0.049），且同一张图逐级模糊时置信度单调下降（0.904 → 0.682）。几何指标与 [geometry-after-frame-guard.json](../../scan-quality/geometry-after-frame-guard.json) **逐项相同**。

## 1. 旧公式与问题

`app/src/main/java/cn/paperforge/app/imaging/NeuralEdgeDetector.kt`：

```kotlin
confidence = (.45f + .35f * mean + .2f * minimum).coerceIn(0f, .99f)
```

`mean` / `minimum` 是四条边 support（每条边 64 个采样点中，5×5 邻域峰值 ≥ 0.2 的比例）的均值与最小值。干净页面上两者都等于 1，公式必然触顶 0.99。

实测（同一模拟器，旧代码）：

| 语料 | 返回神经四角的样本 | 置信度 |
|---|---:|---|
| synthetic-v1 | 57 | **57/57 = 0.99**（min/median/max 全为 0.99，σ=0） |
| DocUNet 前 20 张 | 20 | **20/20 = 0.9900000095367432** |

后果：`SettingKeys.CaptureConfidence`（默认 0.60）、`OutlineTracker`（<0.55 丢弃）、`LearnedDocumentDetector`（<0.55 回退 / ≥0.72 选 refined）在真实照片上全部失去区分度。

## 2. 新公式

候选选择仍使用原来的 `score = .65*mean + .2*minimum + .15*sqrt(area)` 与 `mean ≥ .5 / minimum ≥ .25 / area ≥ .04` 过滤；新特征只在**选中候选之后**计算，因此不改变任何四角。

### 2.1 特征

| 特征 | 定义 | 作用 |
|---|---|---|
| `edgeSupportMean` / `Min` / `Spread` | 四条边 support 比例的均值、最小值、极差 | 边缘整体覆盖与最弱边 |
| `edgeStrengthMean` | 64×4 个采样点 5×5 邻域峰值的平均（连续值） | support 阈值会饱和，强度保留渐变 |
| `edgeResidualMean` | 沿边法线 ±3 px 搜索脊线峰值，抛物线插值后的平均 \|偏移\|（px） | 拟合直线是否压住神经脊线 |
| `contourResidualMean` / `P90` | 生成该四边形的轮廓点到四条直线边的距离，均值与 90 分位（240 px 空间） | 直边四边形能否解释原轮廓；卷曲/内切票据的弦切误差 |
| `cornerPeakMin` / `Mean` | 四角处 5×5 热图峰值的最小值/均值 | 圆角、遮挡、角点缺失 |
| `quadArea` | 归一化四边形面积 | 面积先验 |
| `quadAspect` | 源图宽高修正后的长边/短边（用 `source.cols()/rows()` 还原方形像素） | 长宽比先验 |

### 2.2 打分

```kotlin
edge      = clamp(.45*supportMean + .35*supportMin + .20*strength - .30*spread, 0, 1)
alignment = clamp(1 - edgeResidual/3, 0, 1)
structure = clamp(1 - (contourP90 - 1)/3, 0, 1)   // 240px 空间，1px 是干净轮廓的基线
corner    = .55*cornerMean + .45*cornerMin
prior     = areaPrior(area) * aspectPrior(aspect)
quality   = .15*edge + .20*alignment + .30*structure + .15*corner + .20*prior
confidence = clamp(.10 + .88*quality, 0, .999)
```

先验刻意宽松，避免惩罚合法的小票据或长票据：

- `areaPrior`：<0.04→0.5；<0.06→0.85；0.06–0.90→1.0；≥0.97→0.6；0.90–0.97 线性从 1.0 降到 0.8。
- `aspectPrior`：≤3→1.0；≥7→0.7；3–7 线性。

自然上界是 `quality=1` 时的 **0.98**，`.999` 只是数学兜底，不再有 0.99 硬顶。权重由 synthetic-v1（57 个带真值检测）与 DocUNet 20 张的特征实测标定：目标为清晰页面集中在 0.80–0.92、卷曲票据仍 ≥0.60（保留自动拍摄）、分数不落回同一档位。

### 2.3 阈值含义（未改调用方）

| 阈值 | 使用位置 | 含义 |
|---|---|---|
| ≥0.72 | `LearnedDocumentDetector`（语义模型与标准检测四角位移 ≥0.055 时） | 优先采用 docquad 精修结果；神经边已返回四角的常规路径不过这个门 |
| ≥0.60 | `SettingKeys.CaptureConfidence` 默认 0.60 | 自动拍摄的置信度下限（还需清晰度/稳定/面积等条件） |
| ≥0.55 | `OutlineTracker`；`LearnedDocumentDetector` 回退判断 | 显示跟踪框 / 不让低分语义结果替换标准检测 |
| <0.55 | 同上 | 丢弃跟踪框 / 回退 |
| ≥0.80 | 仅基准脚本的无文档误检规则 | 无文档场景返回四角且 ≥0.8 记为误检；不是应用阈值 |

## 3. 几何不变性

同一命令、同一模拟器，旧代码与新代码各跑一次 `QualityBenchmarkTest`（detect），并用 `scripts/bench/scan_quality_metrics.py` 复算：

| 指标（synthetic-v1，74 检测 + 8 无文档） | 已提交基线 after-frame-guard | 本次旧公式实测 | 本次新公式实测 |
|---|---:|---:|---:|
| 平均 IoU | 0.990632 | 0.990632 | **0.990632** |
| 中位 IoU | 0.995566 | 0.995566 | **0.995566** |
| 平均角误差 | 0.252865% | 0.252865% | **0.252865%** |
| 误裁率（IoU<0.7） | 0% | 0% | **0%** |
| 检测率 | 77.03%（17 例满幅回退） | 77.03% | **77.03%** |
| 无文档误检率（置信度≥0.8） | 0% | 0% | **0%** |
| 检测耗时 p50 / p95 | 290.5 / 439.4 ms | 313.3 / 456.3 ms | 294.8 / 452.0 ms |

三项结论：IoU、失败率、误检率逐位相同；检测率与 17 例满幅回退不变；耗时在模拟器上波动明显（同一旧代码的两次运行 p50 可差 30 ms 以上，一次中间运行 p95 达 541 ms），本轮不能据此声称变快或变慢。新增特征只在选中候选后计算约 256×7 次双线性采样，未进入候选循环。

## 4. 合成语料置信度

57 个“神经边返回四角”的检测（17 个满幅样例被 `frameOnly` 拦截为 0.2，不计入）：

| 统计（57） | 旧公式 | 新公式 |
|---|---:|---:|
| min | 0.990 | **0.795** |
| median | 0.990 | **0.900** |
| mean | 0.990 | **0.895** |
| max | 0.990 | **0.919** |
| std | 0.000 | **0.019** |

全部 82 行（含无文档与标准检测回退）的分桶：

| 区间 | 旧公式 | 新公式 |
|---|---:|---:|
| 0.00–0.55 | 24 | 24 |
| 0.55–0.60 | 1 | 1 |
| 0.60–0.72 | 0 | 0 |
| 0.72–0.80 | 0 | 1 |
| 0.80–0.90 | 0 | 30 |
| 0.90–1.00 | 57 | 26 |

24 个低分是 22 个 `frameOnly`（17 检测 + 5 无文档，固定 0.2）与 2 个标准检测低分；0.594 那一行是 `negative-6.jpg` 的 STANDARD 回退，不走新公式，未变。

按场景（57 个神经四角）：

| 场景 | n | min | mean | max |
|---|---:|---:|---:|---:|
| clutter | 8 | 0.795 | 0.878 | 0.902 |
| dark-desk | 8 | 0.903 | 0.906 | 0.912 |
| glare | 8 | 0.880 | 0.894 | 0.902 |
| ocr-regression | 1 | 0.899 | 0.899 | 0.899 |
| shadow | 8 | 0.857 | 0.887 | 0.919 |
| similar-luminance-color | 8 | 0.886 | 0.899 | 0.912 |
| white-desk | 8 | 0.877 | 0.893 | 0.904 |
| wood | 8 | 0.900 | 0.908 | 0.917 |

合成语料本身接近满分（IoU≈0.99），场景间差异只有 0.02–0.04；它的价值是证明**不再触顶**且清晰页面多数在 0.85 以上（最低 0.795），而不是区分合成场景的“质量”。

## 5. DocUNet 真实照片

环境：`emulator-5554`（API35 x86_64，软件 GPU），`detector=HYBRID → neural-edge`，preview=false，解码上限 1800。语料无真值四角，本节只比较置信度分布，不构成精度结论。

| 统计（20） | 旧基线 | 新公式 |
|---|---:|---:|
| min | 0.990 | **0.702** |
| median | 0.990 | **0.866** |
| mean | 0.990 | **0.849** |
| max | 0.990 | **0.910** |
| std | 0.000 | **0.049** |

| 区间 | 旧基线 | 新公式 |
|---|---:|---:|
| 0.60–0.72 | 0 | 1 |
| 0.72–0.80 | 0 | 1 |
| 0.80–0.90 | 0 | 15 |
| 0.90–1.00 | 20 | 3 |

`preview=true` 分支（仍是同一张 1800 解码图，只改 `ScanProcessor.detectResult` 的预览标志）20/20 返回四角，分布与全分辨率**逐点相同**（min 0.702 / median 0.866 / max 0.910）。这只证明预览分支使用同一打分，不是真实相机 `ImageAnalysis` 帧的验证。

逐张（按新置信度升序；前两行为基线报告人工复核点名的两张卷曲票据）：

| 文件 | 面积 | 置信度 | edgeResidual | contourP90 |
|---|---:|---:|---:|---:|
| 7_2.jpg | 8.02% | 0.702 | 1.16 | 3.39 |
| 4_2.jpg | 7.39% | 0.773 | 1.04 | 2.66 |
| 9_2.jpg | 12.84% | 0.807 | 1.45 | 2.00 |
| 6_1.jpg | 9.44% | 0.815 | 0.87 | 2.29 |
| 7_1.jpg | 9.11% | 0.821 | 1.40 | 1.88 |
| 2_1.jpg | 16.03% | 0.826 | 1.38 | 1.83 |
| 5_2.jpg | 13.62% | 0.826 | 1.03 | 2.06 |
| 8_2.jpg | 11.78% | 0.853 | 1.18 | 1.65 |
| 1_2.jpg | 13.05% | 0.856 | 1.20 | 1.61 |
| 3_2.jpg | 9.10% | 0.862 | 1.37 | 1.43 |
| 2_2.jpg | 15.57% | 0.869 | 1.28 | 1.40 |
| 10_2.jpg | 9.95% | 0.870 | 1.11 | 1.51 |
| 5_1.jpg | 10.30% | 0.871 | 1.13 | 1.49 |
| 6_2.jpg | 12.53% | 0.872 | 1.19 | 1.44 |
| 8_1.jpg | 10.77% | 0.874 | 1.17 | 1.42 |
| 10_1.jpg | 14.22% | 0.879 | 1.31 | 1.28 |
| 1_1.jpg | 11.88% | 0.890 | 1.32 | 1.14 |
| 3_1.jpg | 11.07% | 0.902 | 1.33 | 0.94 |
| 9_1.jpg | 17.19% | 0.908 | 1.08 | 1.10 |
| 4_1.jpg | 8.28% | 0.910 | 1.08 | 1.08 |

最低的两张正好是基线报告里被人工点名的卷曲票据（4_2、7_2）——方向一致，但这只是合理性的旁证，不是精度证明。所有 20 张仍 ≥0.60，自动拍摄门槛未被破坏。

## 6. 同图降质响应

`ConfidenceDegradationTest` 对同一张合成页（`bench/dark-desk-0.jpg`）逐级高斯模糊，检测路径不变：

| 模糊 σ | 置信度 | 返回四角 | 面积 |
|---:|---:|:---:|---:|
| 0 | 0.904 | 是 | 50.9% |
| 2 | 0.902 | 是 | 50.8% |
| 4 | 0.873 | 是 | 51.0% |
| 8 | 0.837 | 是 | 51.8% |
| 12 | 0.682 | 是 | 54.0% |

同一画面、同一四角大致位置，置信度随模糊单调下降；σ=12 时仍高于 0.60，说明它仍会拍摄，但分数已明显低于清晰页。这是“同一张图不同质量有区分度”的直接证据。更重的模糊、离焦与运动模糊未扫。

## 7. 测试

| 测试 | 结果 |
|---|---|
| `testDebugUnitTest --tests cn.paperforge.app.imaging.EdgeConfidenceTest` | **6/6 通过**（单调性、区间、先验、弱证据落到 0.55 以下） |
| `connectedDebugAndroidTest`：`QualityBenchmarkTest` + `DocUnetProbeTest` + `ConfidenceDegradationTest` + `CameraQualityTest` + `LearnedDetectorTest` | **5/5 通过**，0 失败（69.6 s） |
| `CameraQualityTest` 序列输出 | 清晰 1 次拍摄、模糊 0、未对焦 0；与改动前一致 |
| `LearnedDetectorTest` | docquad 模型、refined 提示逻辑通过，未受打分改动影响 |

既有测试断言未修改；`QualityBenchmarkTest` 只新增一列 `diagnostics`（便于以后复算分量），不参与任何判定。

全量 `testDebugUnitTest`（44 条）**43 通过、1 失败**：失败项是 `GeometryTest.fileNamesCannotEscapeDestination`（期望 `纸匠文档`，实现返回 `PaperForge文档`）。`Geometry.kt` 与 `GeometryTest.kt` 在 HEAD 上就是这个状态，不在本次改动内，属于既有不一致，本任务未修。

## 改动文件

- `app/src/main/java/cn/paperforge/app/imaging/NeuralEdgeDetector.kt`：新增置信度特征与打分，候选过滤/选择、refine 与几何输出不变。
- `app/src/androidTest/java/cn/paperforge/app/quality/QualityBenchmarkTest.kt`：`results.json` 增加 `diagnostics` 字段（只记录，不参与判定）。
- 新增 `app/src/test/java/cn/paperforge/app/imaging/EdgeConfidenceTest.kt`：6 条打分单测。
- 新增 `app/src/androidTest/java/cn/paperforge/app/quality/ConfidenceDegradationTest.kt`：同图模糊响应探针。
- DocUNet 探针沿用既有 `DocUnetProbeTest.kt`（本目录改动前已存在于工作区，本次未改）。

## 8. 风险与未验证项

- **只在模拟器**：x86_64 + 软件 GPU；真机 CPU/NPU 耗时、相机预览帧率未测。耗时在模拟器上波动大（p50 可达 ±30 ms），本次不给出快慢结论，真机仍需复测。
- **DocUNet 无真值四角**：20/20 都返回四角且视觉上落在纸上，但没有 IoU/角点误差；置信度低不等于“错”，高也不等于“准”。新分数是模型证据强弱，不是几何正确率。
- **卷曲票据仍会拍摄**：7_2 = 0.702、4_2 = 0.773，都高于自动拍摄 0.60；内切几何本身没有修（本任务只改打分）。基线报告里 4_2 裁掉字符列的问题仍在。
- **自动拍摄行为会随分数变化**：旧公式下任何返回四角的画面都 ≥0.6、必过置信度门；现在低质量画面可能落到 0.6 以下而不再自动拍摄。这正是本次修复要恢复的区分度，但“真实相机里哪些场景会被拦”没有真机数据，可能误伤（例如弱光、潮湿反光纸张）。
- **真实相机预览未验证**：`preview=true` 只在同一张 1800 解码图上跑过（分布与全分辨率逐点相同）；真实 `ImageAnalysis` 的 720 px 帧、曝光与运动抖动未测。
- **docquad 置信度未改**：`LearnedDocumentDetector` 自己的 `cornerConfidence()` 仍是原值；`.55/.72` 门在神经边失败、走语义模型时生效，本轮没有用它做真机/真实照片标定。
- **面积/长宽比先验刻意宽松**：aperture 很小或极斜拍的合法纸张可能被轻度折扣；≥3 的长宽比才开始扣分，≥7 才到 0.7。真实票据的分布未统计，参数可能还要按真机样本调。
- **结构项依赖轮廓阈值**：`contourP90` 来自 40/80/128/176 四档二值化中被选中候选的轮廓；不同模糊/噪声会移动基线 1 px 附近，真机样本到位后应重标定（尤其 σ 与压缩噪声）。
- 只测了 DocUNet 前 20 张（共 50 张），未覆盖全部。

## 9. 复现

```powershell
# 合成语料（current / detect）
./gradlew :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=cn.paperforge.app.quality.QualityBenchmarkTest" `
  "-Pandroid.testInstrumentationRunnerArguments.benchMode=current" `
  "-Pandroid.testInstrumentationRunnerArguments.benchStage=detect"
adb exec-out run-as cn.paperforge.app cat files/quality-current/results.json > results.json
python scripts/bench/scan_quality_metrics.py results.json bench-private/synthetic-v1/manifest.json out/

# DocUNet 20 张 + 同图降质
./gradlew :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=cn.paperforge.app.quality.DocUnetProbeTest,cn.paperforge.app.quality.ConfidenceDegradationTest" `
  "-Pandroid.testInstrumentationRunnerArguments.probeRun=confidence"
adb exec-out run-as cn.paperforge.app cat files/docunet-probe-confidence/results.json > docunet.json
adb exec-out run-as cn.paperforge.app cat files/confidence-degradation/results.json > degradation.json
```

证据文件：

- [confidence-synthetic.json](confidence-synthetic.json)：82 行逐条置信度/特征 + 几何汇总 + 前后分布
- [confidence-docunet.json](confidence-docunet.json)：20 张逐条置信度/特征 + 旧基线
- [confidence-degradation.json](confidence-degradation.json)：同图模糊序列
- 几何对照：[geometry-after-frame-guard.json](../../scan-quality/geometry-after-frame-guard.json)
