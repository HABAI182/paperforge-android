# #24 · OCR 低置信度高亮、候选纠错与快捷校对（MVP）

本页记录本轮的最小可用范围：把引擎已有的置信度用于高亮与跳转、局部文字写回，并如实记录候选纠错到底有没有可用来源。词典/语言模型重排、逐词候选生成没有做。

## 置信度来源：引擎真实字段，没有编造

| 引擎 | 行级 | 词级 | 代码 |
|---|---|---|---|
| 内置 ML Kit（`mlkit-bundled-v031`） | `Text.Line.confidence` | `Text.Element.confidence` | `app/src/main/java/cn/paperforge/app/ocr/OcrEngine.kt` |
| Paddle（模型包） | `result.confidence`（每行一个文本框） | 无（SDK 单路行级输出，`elements` 为空） | `app/src/main/java/cn/paperforge/app/ocr/RoutingOcrEngine.kt` |

- `OcrLine.confidence` / `OcrElement.confidence` 本来就是 `Float?`，schema 4 的 `encode()`/`decode()` 已经写读该字段，**本轮没有新增数据结构字段**。
- 引擎层只接受有限且 `0..1` 的分数；`0`、非有限值、未返回都转成 `null`。`null` 表示「未知」，不是低置信度，UI 显示「未知」，不参与高亮。
- Paddle 实现里 `item.confidence.takeIf { it.isFinite() && it in 0f..1f }`，没有为词级补造分数。

## 改动点

### 1. 阈值走既有设置体系

- `SettingKeys.OcrProofreadThreshold = Number("ocrProofreadThreshold", 0.6f, 0.3f..0.9f)`，加入 `SettingKeys.all`，因此会被设置导出/导入、配置档案与 `normalizedValues` 一并处理（导入超范围会被夹回 0.3–0.9）。
- 入口：设置 → OCR 分组「文字校对」滑块「低置信度高亮阈值」，也出现在设置搜索（关键词：OCR / 校对 / 置信度 / 高亮 / 阈值）。
- `OcrConfidence.threshold()` 再做一次夹取，导入异常值不会让高亮失效。

### 2. 判定规则

- 一行被标记 = 该行 `confidence < 阈值`，**或**该行任一 `element.confidence < 阈值`。
  - 原因：ML Kit 可能只给出词级分数或行、词分数不一致，单纯看行分会漏掉「行分高、某个词很低」的片段。
  - 行分严格低于阈值的数量单独记录为 `strict`，与 `flagged` 区分。
- `confidence == null` 永不标记（包括人工校正后清空的分数）。
- 阈值是严格小于：`confidence == 0.6` 在默认阈值 0.6 下不算低。

### 3. 校对界面（`OcrProofreadScreen.kt`）

- 画布：低置信度行用主题 error 色描边；行内低置信度词额外用 error 色描边；当前选中项仍用主色粗边。
- 控制区：显示「低置信度 X / Y 行 · 高亮阈值 60%」，并提供「上一个低置信度 / 下一个低置信度」，到首尾循环。
- 跳转时若目标行有低置信度词，自动选中该行第一个低置信度词；否则选中整行。
- 当前片段显示置信度数值（`0.00` 两位）或「未知」，低于阈值时标注「低于阈值」。
- 点选行/词后沿用既有编辑框与「保存这一处」：`MainViewModel.correctProofLine` → `Repository.correctOcrLine` → 新增的纯函数 `OcrLineCorrection.apply`。
  - 只替换目标片段文本；行、词的四角坐标全部保留，其他行/词按原样保留。
  - 被编辑片段 `confidence` 置为 `null`、`corrected = true`；行级编辑会清空该行 `elements`（与原有行为一致），避免旧词位置与新文本不一致。
  - 编码结构不变：schema 4、`width/height`、`ir`、`lines[].corners` 都保留（设备测试回读断言）。
  - 整行写回清空 `elements` 后，若之前选中的词已不存在，界面会自动回到「整行」，避免下一次保存报「该词无法单独替换」。

### 4. 候选纠错只在真有候选时出现

- 候选来自 `OcrLine.hypotheses`：只有多路识别（BALANCED/DETAILED 的 `merge`）在同一行产生多个真实候选文本时才有值。
- UI 只在「整行」编辑且候选文本非空、且不同于当前行文本时显示候选 chip，点选只填入输入框，仍需「保存这一处」确认。
- 没有候选且当前片段低置信度时显示一行说明：「当前引擎未给出这一处的候选文本，只能手动修改。」不生成任何本地猜测词。
- **Paddle 路径不提供候选**（单路行级结果，`hypotheses` 永远为空），因此 Paddle 下只有手动输入框。

### 改动文件

- 新增 `app/src/main/java/cn/paperforge/app/model/OcrConfidence.kt`：阈值夹取、行/词低置信度筛选、上一个/下一个循环。
- 新增 `app/src/main/java/cn/paperforge/app/model/OcrLineCorrection.kt`：把原来写在事务里的片段写回逻辑抽成纯函数，行为逐行保持一致。
- `app/src/main/java/cn/paperforge/app/data/OcrProofreading.kt`：`correctOcrLine` 改为调用 `OcrLineCorrection.apply`。
- `app/src/main/java/cn/paperforge/app/SettingKeys.kt`：新增阈值项并注册。
- `app/src/main/java/cn/paperforge/app/ui/SettingsScreen.kt`：OCR 分组的「文字校对」滑块与说明。
- `app/src/main/java/cn/paperforge/app/ui/OcrProofreadScreen.kt`：高亮、计数、跳转、置信度显示、候选 chip。
- 新增 `app/src/test/java/cn/paperforge/app/model/OcrConfidenceTest.kt`、`OcrLineCorrectionTest.kt`。
- 新增 `app/src/androidTest/java/cn/paperforge/app/quality/OcrConfidenceSampleTest.kt`。

## 设备实测（不是估算）

- 设备：`paperforge_api35(AVD)`，Android 15 / SDK 35，`emulator-5554`，x86_64，沿用已有模拟器，未新开。
- 引擎：内置 ML Kit `mlkit-bundled-v031`（`Chinese16.0.1/Latin16.0.1`），中文，测试显式传入 `BALANCED`（代码默认值，与设备偏好无关），阈值 0.60。
- 样本：设备私有目录 `files/samples/camscanner-sample-3.pdf`（用户样本，**不进仓库**），PDF 共 1 页，按 2000 px 长边渲染后整页 OCR。
- 结果 JSON：设备 `files/ocr-confidence/sample-confidence.json`，已拉回 `work/ocr-conf/sample-confidence.json`（仓库外）。

### 真实样本计数

| 指标 | 数值 |
|---|---:|
| OCR 行数 / 带行级置信度的行 | **31 / 31** |
| 行分严格低于 0.6 | **31** |
| 被标记行（含词级触发） | **31 / 31** |
| 低置信度词片段 | **48** |
| 行级置信度均值 / 最小 / 最大 | 0.300 / 0.102 / 0.590 |
| 词级置信度：有分数的词 / 总词数 | 51 / 51 |
| 带候选（`hypotheses` 至少一个不同文本）的行 | 3 |
| 识别尝试 | `original`、`uncertain-lines` |

- 这是公式/手写体扫描页，ML Kit 输出本身接近乱码，行分全部落在 0.10–0.59，所以 31 行全被标记。阈值再降低时：0.5 → 28 行，0.4 → 25 行，0.3 → 22 行（同一页）。
- 同一页上确有低置信度项被识别和计数，不是空结果；但「全页都低」也说明该样本不能用来展示高置信度被过滤的效果，因此补了一张**合成对照**（见下）。

### 合成对照（synthetic=true，不是真实照片）

测试内渲染的清晰印刷拉丁文 8 行，`FAST` 单路识别：

| 指标 | 数值 |
|---|---:|
| 行数 / 带行级置信度的行 | 8 / 8 |
| 行分严格低于 0.6 | **0** |
| 被标记行（含词级触发） | **1**（某一行内单个词 <0.6） |
| 行级均值 / 最小 / 最大 | 0.833 / 0.814 / 0.849 |
| 词级置信度：有分数的词 / 总词数 | 32 / 32 |

说明：阈值能把 7/8 的高置信行放过；剩下 1 行是词级规则命中（行分高、单词低），与规则设计一致。

### 写回实测

`OcrConfidenceSampleTest` 对真实样本第 1 页走 `repo.cacheOcr` → `repo.correctOcrLine`（与「保存这一处」按钮同一条入口）：

| 检查 | 结果 |
|---|---|
| 首个低置信度行/词的选择 | 行 0、词 0（原文 `3X, +`） |
| 词级写回 | `校对字 +`，其余词保留 |
| 所有行/词的 corners 与写回前一致 | 是 |
| 其他行文本不变 | 是 |
| 写回后 `schema` / `width` / `height` | 4 / 1413 / 2000，与写回前一致 |
| 被编辑行/词 `confidence` | `null`，`corrected = true` |
| 整行写回 | elements 清空、corners 保留、其他行不变 |

## 测试结果

### JVM 单测（`app/build/test-results/testDebugUnitTest`）

- `OcrConfidenceTest`：6 个用例，0 失败 —— 阈值夹取、严格小于、`null` 不参与、行+词联合标记、上一个/下一个正反循环与空列表、单元素循环。
- `OcrLineCorrectionTest`：11 个用例，0 失败 —— 首词/末词/中间词写回、空文本（删词/清空整行）、超长文本（4001 字符）、换行拒绝、越界行/词拒绝、重复校正、几何保留。合计 **17 tests / 0 failures**。

### 设备测试（`emulator-5554`）

| 测试 | 结果 |
|---|---|
| `OcrConfidenceSampleTest` | **1/1 通过**（产出上面的 JSON） |
| `OcrGeometryTest` | **1/1 通过** |
| `OcrOptionsTest` | **1/1 通过**（见下注） |
| `OcrRegionFlowTest` | **1/1 通过**（既有词级写回 + 选区合并回归） |
| `OcrRecordsTest` | **失败**，环境残留，见「已知失败」 |

注：跑设备回归时模拟器设置里 `ocrBackend=PADDLE`（早前工作留下的选择），`OcrOptionsTest`、`OcrRegionFlowTest` 会按 Paddle 走并断言内置引擎，因而失败。临时把偏好改成 `MLKIT` 后两者通过，测试后已恢复原文件；恢复后的 `shared_prefs/preferences.xml` SHA-256 与测试前一致（`6B54B714…FFB35`）。`OcrConfidenceSampleTest` 显式 `snapshot(BundledOcrEngine.ID)`，不受该设置影响。

### 证据文件（仓库外 `work/ocr-conf/`）

- `sample-confidence.json`：设备 `files/ocr-confidence/sample-confidence.json` 原样拉回。
- `device-tests-pass.xml`：同一构建下 `OcrConfidenceSampleTest` + `OcrGeometryTest` + `OcrOptionsTest` + `OcrRegionFlowTest` 共 4 个用例、0 失败。
- `device-tests-sample-final.xml`：最终版 `OcrConfidenceSampleTest`（增加精确候选行计数）1 个用例、0 失败。
- `device-tests-ocrrecords-fail.xml`：`OcrRecordsTest` 失败现场（下表说明原因）。
- `unit-TEST-*.xml`：两个 JVM 单测报告。
- `prefs-before.xml` / `prefs-restored.xml`：设备 OCR 后端偏好的改动前/恢复后副本，SHA-256 相同。

### 已知失败：`OcrRecordsTest`（与 #24 无关，未修）

在本机 AVD 上的失败点是 `BackupStore.backup` 读取
`cache/accessibility-f7682c6c-….png` 时 `FileNotFoundException`。用 `sqlite3` 查设备数据库确认：该路径只出现在更早的「Accessibility PDF fixture」测试留下的 `edit_history` 快照里（`edit_history` id 154/157），对应的 cache 文件已被清理；`pages.original` 里没有该引用。即旧测试残留数据让备份阶段找不到文件，不是本轮改动引入，也不在 #24 范围，本轮未删除设备数据、未改 `BackupStore`。

## 已知限制

- **真机未测**：只在 x86_64 API35 模拟器上验证；Android 版本、厂商 ROM 未验证。
- **ML Kit 在模拟器上的置信度表现**：本次 AVD 上 ML Kit **同时提供**行级与词级分数（31/31 行、51/51 词都有值）；不同设备/版本可能返回 `null` 或 `0`，此时不标记、显示「未知」，不会倒填分数。
- **样本本身全低**：`camscanner-sample-3.pdf` 是公式/手写扫描页，31/31 行低于 0.6，高亮会覆盖整页；ML Kit 的分数不是标定概率，不能当准确率。
- **候选纠错有限**：只有多路识别合并出的整行 `hypotheses`，没有词典/语言模型/逐词候选；真实样本上仅 3 行有候选，且候选文本同样是低质量 OCR 变体，不保证能纠对。Paddle 完全没有候选，只能手改。
- **逐词清空后再填**：OCR element 不包含词间空格，把词清空后原位重填可能丢失分隔空格（如 `Outside Header` → `OutsideHeader `）。单测已固定该边界；此时建议改用整行编辑。验收页不把该情况当作可自动修复。
- **快速校对是手动的**：保存后不自动跳到下一处；没有「全部低置信度逐条过一遍」的批处理或进度记忆。
- **UI 视觉未走查**：本轮没有做 Compose 画布的点按/截图验收，浅色/深色主题下的高亮对比度未实测；颜色取自 `colorScheme.error`。
- **阈值全局**：一个阈值同时作用于行与词，没有按引擎/语言分别标定；旧页面（无 `confidence` 的 legacy 布局）不会高亮。

## 未验证项

- 真实手机、其他 Android 版本、Paddle 模型包下的实际置信度与候选为空的表现（本轮只读代码确认 `hypotheses` 为空）。
- 多栏、竖排、旋转/去斜校正过的页面；`orientationCorrection != 0` 时的低置信度坐标与跳转。
- 超过 2 页/大文档的耗时与内存；本轮真实样本只有 1 页，未做多页计数。
- 中文输入法、超长单行文本在输入框与芯片区的实际交互；超长文本只做了纯函数级测试。
- 与「框选重识别」合并到已校正行的组合场景（只跑了既有 `OcrRegionFlowTest` 回归）。
- 低置信度高亮与深色/AMOLED 主题、色盲无障碍的对比度。
