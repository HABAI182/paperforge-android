# #30 · 成片二次精修：取景框与成片裁切对齐

日期：2026-10-31。范围：只做「拍摄落盘后，在全分辨率静态图上再检测一次，与取景框比较并按规则采用成片框 + 诊断」。不做曲面展开、不做真机相机栈改造，也不调用 vivo 私有能力。

结论先说：

- 复用既有 `CaptureReference` / `previewQuad` 通道，在 `ScanProcessor.detectFile` 内新增一次独立成片检测；归一化最大角点位移 **> 0.01** 且两帧置信度都 **≥ 0.60** 时采用成片框，否则保留原有的全分辨率 `refine(预览框)` 结果。
- 诊断写入 `DetectionResult.diagnostics/notes → page.processingMetadata → ScanDiagnostics`，字段含 `stillMaxCornerShift / stillShiftThreshold / stillAdopted / stillConfidence / stillReasonCode` 与原因字符串。
- DocUNet 20 张「720px 预览 → 1800px 成片」两段实测：位移 **p50 0.787% / p90 1.156% / max 1.223%**；阈值 0.01 下 **5/20（25%）** 采用成片框，0.015 及以上 **0/20**。采用后边缘贴合（Otsu 偏移）相对**原始预览框**明显改善（中位 20.0→4.0 px，p90 33.0→16.0 px），但相对**已 refine 的预览框**只有小幅尾部改善（p90 23.0→16.0 px），其中两张反而略差——见第 4 节的诚实结论。
- 新增单测 8/8；设备上 `DocUnetProbeTest`、`QualityBenchmarkTest(detect)`、`CameraQualityTest`、`LearnedDetectorTest`、`CaptureGeometryFlowTest` 与 `StillCropProbeTest` 全部通过；全量 JVM 130 条仅既有 `GeometryTest.fileNamesCannotEscapeDestination` 失败。

## 1. 能力边界：vivo「超级文档」不可调用

- vivo「超级文档」是厂商相机/系统侧的**私有能力**（多帧融合、文档增强、曲面展平与厂商后处理都在闭源相机服务里）。它没有公开 SDK、Intent、ContentProvider 或文档化的第三方接口；第三方应用无法调用，也不能把它当作本 App 的一个步骤。
- 本任务只使用**公开 API + 自研**：CameraX（预览 / `ImageAnalysis` / `ImageCapture`）、Android Bitmap/Exif、OpenCV、自带的 DocQuad 边模型与几何代码。
- 因此不能宣称「达到超级文档效果」：本次没有多帧去反光、没有曲面展开，也没有厂商级的文档增强。若未来要接厂商能力，需要厂商授权与公开接口，届时另行评估。
- 复核记录里不写竞品包名、不复制其代码/模型/资源；本页只描述能力边界。

## 2. 问题、衔接点与本次做法

### 2.1 既有链路（衔接点）

1. `CameraScreen` 在 `ImageAnalysis` 回调里把分析帧转正、`LearnedDocumentDetector.detect(..., preview = true)`，得到预览框与置信度，组装 `CaptureReference`（`quad / analyzedAt / requestedAt / width / height / viewport / confidence / scene / paper / motion`）。
2. 按快门时 `captureNow` 取「700 ms 内的 `rawQuad`」与 `atShutter = reference.copy(requestedAt = now)`，`vm.prepareCapture(doc.id, atShutter, target)` → `CaptureTasks.prepare` 把 `CaptureReference` 写进 `CaptureSpec` 的 payload（持久化标记），随后 `ImageCapture.takePicture` 落盘原始文件。
3. `CaptureProcessWorker` 调 `ScanProcessor.detectFile(file, spec.reference?.quad, spec.reference, detector, lockCrop)`。
4. 改前：reference 新鲜且匹配时只做 `DocumentDetector.refine(mat, previewQuad)`（在成片分辨率上把预览框的每条边重新拟合一次）；reference 过期/场景变化时改为整幅重新检测。**没有第二次独立检测，也没有记录两者差异。**

### 2.2 改动

`ScanProcessor.detectFile` 在 reference 匹配且 `lockReference` 时：

1. 仍先算 `refined = DocumentDetector.refine(mat, previewQuad)`，作为回退候选（保持既有行为）。
2. 在同一张全分辨率解码图上**再跑一次独立检测** `LearnedDocumentDetector.detect(mat, preview = false)`，得到成片框与置信度。
3. 用纯几何模块 `StillCrop.decide` 比较「预览框 vs 成片框」的归一化最大角点位移，按下面规则选框：

| 情况 | 采用 | 原因码 |
|---|---|---:|
| 成片检测失败 / 返回无效四边形 | `refined`（预览框 refine） | 0 `still-detection-failed` |
| 成片置信度 < 0.60 | `refined` | 1 `still-low-confidence` |
| 预览置信度 < 0.60 | `refined` | 4 `preview-low-confidence` |
| 位移 ≤ 阈值（默认 0.01） | `refined` | 2 `still-within-threshold` |
| 位移 > 阈值且两个置信度都 ≥ 0.60 | 成片框 | 3 `still-adopted` |

- 位移定义：四角逐点比较 `hypot(dx, dy)`（x、y 各自除以对应图像尺寸），取最大值；不是单轴阈值，也不是 IoU。
- 低置信度门槛对**两帧都生效**：成片检测或预览检测任一 < 0.60 都不覆盖预览框（原因分别记 `still-low-confidence` / `preview-low-confidence`），避免多拍之间裁切跳变。预览置信度只参与这个门槛，其余情况仍按位移决定。
- 没有新增任何状态：预览框、置信度、时间戳都来自既有 `CaptureReference`；决策只活在本次 `DetectionResult` 与页面元数据里，不落库新列。
- `reference == null` 的调用（相册导入、部分手动重试）**完全保持原路径**：只 refine、method 仍是 `viewfinder+refined`，既有 `LearnedDetectorTest` 断言未改也通过。
- reference 过期/不匹配的路径仍是整幅新检测，但补记了与预览框的位移、`stillAdopted` 与 `stillReason=reference-<reason>`。

### 2.3 阈值与诊断字段

`app/src/main/assets/scan_params.json`：

```json
"stillRefineShift": 0.01,
"stillRefineMinConfidence": 0.6
```

代码兜底 `StillCrop.DefaultShiftThreshold = 0.01f`、`DefaultMinConfidence = 0.6f`。

`DetectionResult.diagnostics` 新增（数值，进页面元数据/诊断包）：

| 字段 | 含义 |
|---|---|
| `stillMaxCornerShift` | 预览框 vs 成片框的最大归一化角点位移；无成片框时 -1 |
| `stillShiftThreshold` | 本次使用的位移阈值 |
| `stillAdopted` | 是否采用成片框（1/0） |
| `stillPreviewConfidence` | 预览框置信度（来自 `CaptureReference`） |
| `stillConfidence` | 成片检测置信度 |
| `stillAreaRatio` | 成片框面积 / 预览框面积（仅诊断，不参与决策） |
| `stillReasonCode` | 0 失败 / 1 成片低置信 / 2 阈值内 / 3 采用 / 4 预览低置信 |
| `stillDetectMs` | 成片检测耗时（模拟器参考值） |
| `referenceAgeMs` / `referenceReused` | 既有字段，保持不变 |

`DetectionResult.notes` 新增 `stillReason=<字符串原因>`；`CaptureMetadata` 把它写进 `processingMetadata.capture.notes`（诊断字段在 `.capture.diagnostics`）。`ScanDiagnostics.export` 新增 `storedProcessingMetadata`（含拍后决策）与 `newDiagnostics/newNotes`，因此 App 内导出的诊断包能看到「预览框 vs 成片框」的决策，而不需要重新猜。

`detectionMethod` 会记录结果来源：采用成片框时为 `still-verified+<模型 method>;still-adopted`；保留预览框时为 `preview-verified+refined;still-within-threshold`（或 `;still-low-confidence` / `;preview-low-confidence` / `;still-detection-failed`）；过期路径仍是 `...;fresh-after-<reason>`。

### 2.4 阈值怎么定的

先在设备上用 20 张 DocUNet 跑 720px 预览 / 1800px 成片两段，得到位移分布（第 4 节）。候选阈值下的采用数为：

| 阈值 | 采用 / 20 | 保留预览（阈值内） | 低置信保留 | 检测失败保留 |
|---:|---:|---:|---:|---:|
| **0.01** | **5** | 15 | 0 | 0 |
| 0.015 | 0 | 20 | 0 | 0 |
| 0.02 | 0 | 20 | 0 | 0 |
| 0.03 | 0 | 20 | 0 | 0 |

分布 p90 = 1.156%，所以 0.015 以上在本语料上等于永不触发。选 **0.01**（1% 画幅，1800px 解码约 18px、3024px 原图约 30px）以覆盖差异最大的尾部，同时不把中位差异（0.787%）当成误检。该值仍需真机语料复核。

## 3. 验证环境

- 设备：`emulator-5554`，AVD `paperforge_api35`（Android 15 / SDK 35，x86_64），沿用既有模拟器，未新开。
- 检测设置：`detector=HYBRID`，`preview` 检测上限 720，成片 1800（探针）/ 4000（产品路径实际解码上限）。
- 语料：设备 `files/docunet` 的 DocUNet 公开相机照片 20 张（真实拍摄照片，**无真值四角**）。
- 探针：`StillCropProbeTest`（新增）逐张记录预览框、成片框、refine 后的预览框、采用决策，并用真实 `ScanProcessor.detectFile` 走一遍产品路径，断言诊断字段与原因存在。

## 4. 实测数字（DocUNet 20 张，模拟器）

### 4.1 两段检测的差异

「预览」= 720px 解码 + `preview=true`（不 refine）；「成片」= 1800px 解码 + `preview=false`（与拍摄路径相同）。位移为归一化最大角点距离。

| 指标 | 值 |
|---|---:|
| 20 张都返回四边形 | 20/20（无失败） |
| 位移 p50 | **0.00787（0.787%）** |
| 位移 p90 | **0.01156（1.156%）** |
| 位移 max | **0.01223（1.223%）** |
| 位移 mean | 0.00817 |
| refine 预览框 vs 原始预览框（p50 / p90 / max） | 0.00747 / 0.01041 / 0.01162 |
| 成片框 vs refine 预览框（p50 / p90 / max） | 0.00186 / 0.00549 / 0.00918 |
| 成片置信度最低 / 预览置信度最低 | 0.702（7_2）/ 0.777（7_2） |
| 阈值 0.01 判定「需采用成片框」 | **5/20 = 25%**（2_1、3_1、5_2、7_2、8_1） |
| 因低置信度（<0.6，任一帧）拒绝采用 | 0/20（本语料没有低置信样本） |
| 因检测失败回退 | 0/20 |

解读：原始预览框与成片框的差异最多 1.22%，其中很大一部分来自「在成片上对预览框做局部 refine」这一既有步骤（refine 后 p50 仍有 0.747%）；成片独立检测相对 refine 预览框只剩 p90 0.55% 的差异。也就是说，两段检测在这 20 张上没有出现「预览框离谱、成片框才对」的大错位。

### 4.2 产品路径（真实 `detectFile`，4000 解码）

用预览框与 720px 分析签名构造匹配的 `CaptureReference`，逐张走 `ScanProcessor.detectFile(file, previewQuad, reference, null, lockReference = true)`：

| 项 | 值 |
|---|---:|
| reference 被判定「匹配」（未走 fresh-after） | 20/20 |
| 采用成片框 | 5/20（3_1、5_2、7_2、8_1、10_2） |
| 保留 refine 预览框 | 15/20，原因均为 `still-within-threshold` |
| 诊断字段齐全（探针断言） | 20/20 |
| 整次 `detectFile` 耗时（含 4000 解码） | 2116–3371 ms（模拟器，仅参考） |
| 其中成片检测 `stillDetectMs` | 514–656 ms（模拟器，仅参考） |

产品路径的采用集合与 1800px 两段探针不完全相同（2_1 换成 10_2），因为产品路径在 4000px 解码上重跑检测；两次都是 5/20。没有做真机耗时对比，不能据此下「快/慢」结论。

### 4.3 采用后的边缘贴合度（Otsu 偏移）

方法：对全分辨率原图（EXIF 转正），沿四边形每条边取 8%–92% 共 25 个采样点，在 OUTWARD 法线 ±1.2% 短边（约 ±36 px）取灰度剖面，3 点平滑后做 Otsu 二分类，找离边最近的一次「纸面→背景」跨越；偏移正数 = 纸面在该边之外（即检测边切进纸里）。统计每图 `median|offset|` 与 `p90|offset|`，再对 20 张取中位。脚本与逐图数据见 [still-crop-analysis.json](still-crop-analysis.json)。

全 20 张：

| 候选框 | 中位 \|偏移\|（各图中位的中位） | p90 \|偏移\|（各图 p90 的中位） | 偏移 >12 px 样本占比（均值） |
|---|---:|---:|---:|
| 原始预览框（720） | 13.5 px | 29.9 px | 11.6% |
| refine 预览框（1800 refine） | 5.25 px | 24.45 px | 11.1% |
| 成片框（1800 独立检测） | 5.0 px | 22.25 px | 11.7% |

阈值 0.01 下被采用的 5 张：

| 候选框 | 中位 \|偏移\| | p90 \|偏移\| | 偏移 >12 px 样本占比 |
|---|---:|---:|---:|
| 原始预览框 | 20.0 px | 33.0 px | 12.7% |
| refine 预览框 | 4.0 px | 23.0 px | 13.1% |
| 成片框（采用） | **4.0 px** | **16.0 px** | 11.7% |

**诚实结论**：

- 相对于**原始预览框**，成片框把被采用样本的中位偏移从 20.0 px 降到 4.0 px、p90 从 33.0 px 降到 16.0 px；但同样的改善大部分来自既有的「refine 预览框」，成片独立检测相对 refine 预览框只把 p90 从 23.0 px 压到 16.0 px。
- 逐图看并非全是改善：8_1 的 p90 由 25.9 px 降到 13.0 px（[zoom-8_1-side1.jpg](still-crop/zoom-8_1-side1.jpg)），3_1（23→22 px）与 5_2 基本持平；2_1 的中位由 6→7 px、7_2 由 4→5 px 反而略差。不可宣称「采用成片框一律更准」。
- 该偏移测量本身是代理指标：Otsu 剖面在卷曲/低对比/花纹边缘上会失败（跳过样本），且没有真值四角；数值用于同图相对比较，不是绝对精度。
- 覆盖图（红=预览框，橙虚线=refine 预览框，绿=成片框）：[8_1](still-crop/overlay-8_1.jpg)、[7_2](still-crop/overlay-7_2.jpg)、[2_1](still-crop/overlay-2_1.jpg)、[3_1](still-crop/overlay-3_1.jpg)、[5_2](still-crop/overlay-5_2.jpg)。

## 5. 测试

### 5.1 新增单测（JVM）

`app/src/test/java/cn/paperforge/app/model/StillCropTest.kt`，**8/8 通过**：

| 用例 | 断言 |
|---|---|
| 位移恰在阈值上 | 保留 refine 预览框，原因 `still-within-threshold` |
| 位移刚超阈值 | 采用成片框，`stillAdopted=1` |
| 对角位移 | 用欧氏距离而不是单轴：0.005/0.005 保留，0.008/0.008 采用 |
| 成片低置信（0.599） | 即使位移 0.06 也不采用，原因 `still-low-confidence`、原因码 1 |
| 预览低置信（0.59） | 即使成片置信 0.9 也不采用，原因 `preview-low-confidence`、原因码 4 |
| 置信度恰为 0.60 | 两帧都 ≥0.60 时可改采用（门槛是「低于」） |
| 成片检测失败 / 无效四边形 | 回退 refine 预览框，位移 -1，原因码 0 |
| 诊断字段 | 位移、阈值、是否采用、两个置信度、面积比、原因码逐项核对 |

### 5.2 设备测试（`emulator-5554`）

| 测试 | 结果 |
|---|---|
| `StillCropProbeTest`（新增，20 张两段 + 产品路径） | **1/1 通过**（含 20 行诊断断言） |
| `DocUnetProbeTest` | **1/1 通过** |
| `QualityBenchmarkTest`（`benchMode=current`、`benchStage=detect`） | **1/1 通过**（64.7 s，本次运行） |
| `CameraQualityTest` | **1/1 通过** |
| `LearnedDetectorTest`（`viewfinder+refined` 旧断言） | **1/1 通过** |
| `CaptureGeometryFlowTest`（旋转/EXIF 相机 JPEG） | **1/1 通过** |

同一次运行 6 个测试类 0 失败；`StillCropProbeTest` 单独运行也是 1/0 失败。

### 5.3 全量 JVM

`testDebugUnitTest`：**130 条，129 通过、1 失败**。

- 失败：`GeometryTest.fileNamesCannotEscapeDestination`（期望「纸匠文档」，实得「PaperForge文档」），交接文档已记录、与本次无关，未改断言。
- 本次未重跑交接文档里另外几条设备侧既有失败（`EditorExperienceTest` touch-injection、`LibraryNamingTest` 孤儿缓存等），因此不把它们算成本次回归，也不声称它们通过。

## 6. 未验证项 / 限制

- **没有真机**：全部为 API35 x86_64 模拟器 + `swiftshader_indirect`；真机相机预览、真实 `ImageAnalysis` 帧、不同传感器比例、厂商降噪/锐化都未测。
- **「预览」是下采样静态图，不是真实预览帧**：探针把同一张 DocUNet 照片解码到 720px 当作预览检测输入；真实预览帧还有曝光/降噪/运动模糊差异，以及「预览帧 → 快门」的时间差（`CaptureReference.age` 通常 0–450 ms）。因此真实场景的两段位移可能比 1.22% 更大，本次数字只能说明「同一图像不同分辨率」的差异下界性质。
- **DocUNet 无真值四角**：不能说「几何更准」；只能说不返回空、诊断可读、采用后相对原始预览框的代理偏移改善。Otsu 偏移是粗测，遇到卷曲、低对比、纸张花纹会跳过或判错。
- **曲面展开仍缺**：卷曲边的直边内切问题没有解决；`CropSafety` 的 1% 安全外扩仍是独立环节（本次未改），极端卷曲仍会切到白边。
- **低置信 / 检测失败分支只有单测**：20 张成片置信度都 ≥0.70、预览置信度都 ≥0.777，设备上没有触发 `still-low-confidence`、`preview-low-confidence` 或 `still-detection-failed`；这些路径的行为只由 JVM 单测保证。
- **阈值只按 20 张校准**：0.01 是这篇语料的 p90 附近值；真实票据、白纸、深色纸张、低光、运动模糊下的分布未统计，0.01 / 0.6 都可能需要按真机重新校准。
- **没有用户提示**：采用成片框时页面照常保存，不弹提示；诊断只进页面元数据与 App 内诊断包，用户不可见。
- **耗时只在模拟器量过**：产品路径 2.1–3.4 s、成片检测 0.5–0.6 s 都是模拟器 x86_64 数字，不能当手机结论；未测内存峰值与电量。
- **没有多帧/时序融合**：只比较「一帧预览 + 一帧成片」，没有多帧平均、没有防抖后的帧对齐；真机上预览与成片可能不是同一场景（手抖、翻页），本次只由 `CaptureReference.reason` 的过期判断兜底。

## 7. 改动文件

- 新增 `app/src/main/java/cn/paperforge/app/model/StillCrop.kt`：纯几何决策、位移计算、原因码、诊断字段。
- `app/src/main/java/cn/paperforge/app/imaging/ScanProcessor.kt`：`detectFile` 在 reference 匹配时增加成片独立检测与 `StillCrop.decide`；reference 为空时保持原 `viewfinder+refined`；过期路径补记位移/采用字段。
- `app/src/main/java/cn/paperforge/app/imaging/DocumentDetector.kt`：`DetectionResult` 增加 `notes: Map<String, String>`（默认空，所有既有构造调用不变）。
- `app/src/main/java/cn/paperforge/app/imaging/CaptureMetadata.kt`：`processingMetadata.capture.notes`。
- `app/src/main/java/cn/paperforge/app/imaging/ScanDiagnostics.kt`：诊断包增加 `storedProcessingMetadata`、`newDiagnostics`、`newNotes`。
- `app/src/main/assets/scan_params.json`：`stillRefineShift=0.01`、`stillRefineMinConfidence=0.6`。
- 新增 `app/src/test/java/cn/paperforge/app/model/StillCropTest.kt`（8 条）。
- 新增 `app/src/androidTest/java/cn/paperforge/app/quality/StillCropProbeTest.kt`：20 张两段探针 + 产品路径断言。
- 新增验收页 `docs/acceptance/issues/30/README.md`（本页）、[still-crop.json](still-crop.json)、[still-crop-analysis.json](still-crop-analysis.json)、[still-crop-two-stage.json](still-crop-two-stage.json)、[still-crop/](still-crop/) 覆盖图。

未改：`CropSafety.kt`、`NeuralEdgeDetector.kt` / 置信度逻辑、`CameraScreen`、`CaptureTasks`、数据库 schema、任何既有测试断言。

## 8. 复现

```powershell
# 构建并安装（沿用 emulator-5554）
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME='E:\CodexWorkspaces\2026-10-23\new-chat\work\toolchain\android-sdk'
$env:GRADLE_USER_HOME='E:\CodexWorkspaces\2026-10-23\new-chat\work\toolchain\gradle-home'
.\gradlew.bat :app:installDebug "-PpaperforgeAbis=x86_64"

# 20 张两段探针 + 产品路径
.\gradlew.bat :app:connectedDebugAndroidTest "-PpaperforgeAbis=x86_64" `
  "-Pandroid.testInstrumentationRunnerArguments.class=cn.paperforge.app.quality.StillCropProbeTest"

# 拉取结果（用 cmd 重定向以保留 UTF-8 字节）
adb -s emulator-5554 exec-out run-as cn.paperforge.app cat files/still-crop-probe/results.json > results-two-stage.json

# 逐图 Otsu 偏移与统计（宿主 Python + numpy/Pillow）
python work/issue30-still-crop/analyze.py

# 新增单测 / 既有相关测试
.\gradlew.bat :app:testDebugUnitTest "--tests" "cn.paperforge.app.model.StillCropTest"
.\gradlew.bat :app:connectedDebugAndroidTest "-PpaperforgeAbis=x86_64" `
  "-Pandroid.testInstrumentationRunnerArguments.class=cn.paperforge.app.quality.DocUnetProbeTest,cn.paperforge.app.quality.QualityBenchmarkTest,cn.paperforge.app.quality.CameraQualityTest,cn.paperforge.app.quality.LearnedDetectorTest,cn.paperforge.app.CaptureGeometryFlowTest" `
  "-Pandroid.testInstrumentationRunnerArguments.benchMode=current" `
  "-Pandroid.testInstrumentationRunnerArguments.benchStage=detect"
```

证据：[still-crop.json](still-crop.json)（阈值、分布、采用集合、偏移汇总）、[still-crop-analysis.json](still-crop-analysis.json)（逐图偏移、产品路径诊断）、[still-crop-two-stage.json](still-crop-two-stage.json)（探针原始四角/决策）。
