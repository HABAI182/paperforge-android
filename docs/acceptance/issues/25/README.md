# #25 · 基于离线 OCR 的智能命名与文档分类建议（MVP）

本页记录最小可用范围：只读首页已缓存 OCR 文本，用可解释的本地规则给出 1–3 个候选名称和一个分类建议；用户点选只填入重命名输入框，**不自动改名，也不自动写分类**。没有模型、没有联网、没有 OCR 自动触发；真实样本无关键词时返回空候选，另用测试内构造文本证明票据/合同/练习册/笔记能命中。

## 范围与入口

### 入口

- **文档库**：首页文档卡片「更多」→「重命名」→「重命名文档」弹窗 → 输入框下方「建议」按钮。
- **编辑器**：点击顶部文档名称 → 「文档名称」弹窗 → 输入框下方「建议」按钮。

两处共用同一个 `SmartNamingAssist` 组件，行为一致：

1. 用户点「建议」后才读取数据，不预加载、不后台识别。
2. 读取该文档**首页已缓存**的 OCR：优先 `PageRow.ocrLayout` 解码出的 `OcrLayout.text`，回退 `PageRow.ocrText`；没有识别结果时显示「没有足够的离线文字依据，暂不给出建议。」，不硬凑。
3. 有依据时显示分类 chip（如「票据 · 84%」）和 1–3 个候选名称 chip；点候选名称只把文字填进输入框，**仍需用户按「保存」**才会走既有 `vm.rename`。
4. 分类 chip 只展示，不写 `folder`/`tags`；页面里没有任何分类枚举或新表。
5. 分类有依据但首页缺少日期/标题时，只显示分类，不生成名称；当前名称已等于所有候选时，候选为空并提示。

### 数据来源与复用

- **OCR**：`BundledOcrEngine.layout` 的 `OcrLayout.lines` / `OcrLayout.text`（`app/src/main/java/cn/paperforge/app/model/OcrLayout.kt`）。不新增 OCR 数据结构。
- **命名**：复用 `safeFileName`（`model/Geometry.kt`）做文件名清洗与截断上限；复用 `OutputNames` 的「显示名/公开文件名」语义，不另造文档模型。
- **重命名**：沿用 `MainViewModel.rename` / `Repository.rename` / `OutputNames.remember`，候选只填输入框，不绕过确认。
- **分类/标签**：现有 `DocumentRow` 只有 `title/folder/tags`，没有分类字段。本轮分类只是界面建议，不写库。

### 代码位置

- 新增 `app/src/main/java/cn/paperforge/app/model/SmartNaming.kt`：纯函数规则、日期/金额/关键词/版式评分、名称生成。无 Android 依赖，可在 JVM 直接测。
- 新增 `app/src/main/java/cn/paperforge/app/ui/SmartNamingAssist.kt`：建议按钮、分类 chip、候选名称 chip、依据展示。
- `app/src/main/java/cn/paperforge/app/MainViewModel.kt`：新增 `suspend fun namingSuggestion(doc): SmartNaming.Suggestion?`，读取首页缓存 OCR（IO 线程）。
- `app/src/main/java/cn/paperforge/app/ui/HomeScreen.kt`：文档库重命名弹窗接入建议组件。
- `app/src/main/java/cn/paperforge/app/ui/EditorScreen.kt`、`ui/ScanEditorLayout.kt`：编辑器重命名弹窗接入同一组件（可选参数 `suggestName`，不传则保持原弹窗）。
- 新增 `app/src/test/java/cn/paperforge/app/model/SmartNamingTest.kt`：12 个 JVM 用例。
- 新增 `app/src/androidTest/java/cn/paperforge/app/quality/SmartNamingSampleTest.kt`：真实样本 OCR + 合成文本 + 数据库回读。

本轮没有改 `#21/#23/#24/#27` 的业务代码与测试断言，也没有新增依赖。

## 规则与权重

### 输入

```kotlin
SmartNaming.Input(
    ocrText = 首页 OCR 文本,
    pageCount = 文档页数,
    currentName = 现有文件名,
    date = 可选日期,          // 仅当 OCR 里没有日期时作为回退
    lineLimit = 24,           // 只读前 N 个非空行
)
```

输出：

```kotlin
SmartNaming.Suggestion(
    names = List<String>,     // 0–3 个，去重、保序
    category = Category,      // 票据/合同/练习册/笔记/其他
    confidence = Float,       // 0..0.99
    evidence = List<String>,  // 触发的词、日期、版式与评分
)
```

`Category.OTHER` + `names=[]` + `confidence=0` + `evidence=[]` 是明确的「没有依据」，不是兜底分类。

### 位置权重

只统计前 24 个非空行；命中行越靠上权重越高：

| 非空行序号（0 基） | 权重 |
|---|---:|
| 0–2 | 1.00 |
| 3–7 | 0.70 |
| 8–15 | 0.45 |
| 16+ | 0.25 |

每个关键词最多计 3 行命中（避免页眉/页脚/重复行刷分）；金额格式最多计 2 行。

### 关键词与权重

| 分类 | 关键词/规则 | 权重 |
|---|---|---:|
| 票据 | 发票、收据 | 1.0 |
| 票据 | 价税合计 | 0.9 |
| 票据 | 合计、总计、金额 | 0.8 |
| 票据 | 应付、实付、税额 | 0.7 |
| 票据 | ￥、¥、$ | 0.6 |
| 票据 | 金额格式：`¥128.00` / `128.00元` / `$9.9` | 0.9 |
| 合同 | 合同、甲方、乙方 | 1.0 |
| 合同 | 条款 | 0.9 |
| 合同 | 签订、签署 | 0.8 |
| 合同 | 协议、双方 | 0.7 |
| 合同 | 违约、履行 | 0.6 |
| 练习册 | 练习册、姓名、班级 | 1.0 |
| 练习册 | 练习 | 0.8 |
| 练习册 | 学号、年级 | 0.7 |
| 练习册 | `第X页`（`第\s*\d+\s*页`） | 0.7 |
| 笔记 | 笔记 | 1.0 |
| 笔记 | 会议记录 | 0.9 |
| 笔记 | 待办、备忘 | 0.8 |
| 笔记 | 会议 | 0.7 |
| 笔记 | 版式规则（见下） | 1.0–1.3 |

### 日期

按顺序尝试三种写法，取第一条合法日期（`LocalDate.of` 校验，年份限 1900–2100）：

- `2026-09-29` / `2026/9/29` / `2026.9.29`
- `2026年9月29日`
- `20260929`

证据示例：`第 3 行日期：2026-09-29`。OCR 没有日期时才用 `Input.date`，证据写 `文档日期：…`。

### 笔记版式

笔记没有固定关键词，使用结构条件，**全部满足**才计分：

- 至少 6 个非空行；
- 短行（≤16 字）占比 ≥60%；
- 含 ≥3 行「至少 4 个汉字」，且这类行 ≥总行数 25%（防止纯拉丁/公式噪声误判）；
- 前 3 个非空行里有一行 2–24 字、≥2 个汉字，作为标题行。

基础分 1.0；标题行在第 1 行再加 0.3。证据：`版式：N 行中 M 行为短行（≤16 字）`、`标题行：「…」`。

真实样本 `camscanner-sample-3.pdf` 只有 31 行公式/手写噪声，末行虽是「扫播全能王创建」水印（1 行 ≥4 汉字），但达不到「≥3 行且 ≥25%」，因此不会误判成笔记。

### 分类判定与置信度

1. 四个分类各自累加 `关键词权重 × 位置权重`；票据额外加金额格式分。
2. 取最高分 `top` 与次高分 `second`。
3. `top < 1.0` 或 `top <= second`（并列）→ `OTHER`，空候选。并列时不按枚举顺序硬选。
4. `confidence = top / (top + 0.6 × second + 0.5)`，夹到 `0..0.99`。
   - 同分类继续命中会增大 `top`，置信度单调不降；次高分类同时变大则会降低置信度，这是「另一个分类也像」的歧义表达。
5. 证据按「日期 → 命中行（按行号排序）→ 评分」输出，最多 8 条；评分格式如 `评分：票据 2.61`，有次高分时附 `，次高 合同 0.00` 不输出 0 分项。

### 名称生成

- 前缀固定为分类中文名：`票据` / `合同` / `练习册` / `笔记`。
- 标题取第一个「有意义」的行：至少 2 个汉字或 3 个拉丁字母、不是纯日期、不是「发票/收据/合同/姓名/合计」等泛化词；截断 20 个码点（不切 surrogate）。
- 组合顺序（去重、保序、最多 3 个）：
  1. `前缀-日期-标题`（日期和标题都有）
  2. `前缀-日期`
  3. `前缀-标题`
- 每个候选都经过 `safeFileName`（替换 `\/:*?"<>|` 与控制字符、去首尾点、100 字符上限）；与现有文件名完全相同的候选被剔除。
- 没有日期也没有标题时返回空候选，不生成只有「票据」这类无信息名称。

## 设备实测（不是估算）

- 设备：`paperforge_api35(AVD)`，Android 15 / SDK 35，`emulator-5554`，x86_64；沿用已有模拟器，未新开。
- 引擎：内置 ML Kit `mlkit-bundled-v031`（`Chinese16.0.1/Latin16.0.1`），中文，`BALANCED`，测试显式 `snapshot(BundledOcrEngine.ID)`，不受设备 OCR 偏好影响。
- 样本：设备私有目录 `files/samples/camscanner-sample-3.pdf`（用户样本，**不进仓库**），1 页，按 2000 px 长边渲染后整页 OCR。
- 设备输出：`files/smart-naming/sample-smart-naming.json`，已拉回仓库外 `work/smart-naming/sample-smart-naming.json`。

### 真实样本结果

| 指标 | 实测 |
|---|---:|
| PDF 页数 / OCR 页数 | 1 / 1 |
| OCR 行数 | **31** |
| 分类结果 | **其他（OTHER）** |
| 候选名称 | **空** |
| confidence | **0** |
| evidence | **空** |
| 数据库回读文本一致 | 是 |
| 数据库回读建议一致 | 是 |

真实样本是公式/手写体扫描页，OCR 首行 `3X, +`，没有票据/合同/练习册/笔记关键词；末行「扫播全能王创建」只是扫描水印的一行，不构成版式证据。因此按设计返回空候选，没有硬凑名称。这与 #24 对该样本「31/31 行低置信、OCR 接近乱码」的记录一致。

### 合成文本（synthetic=true，测试内构造，不是真实照片）

`SmartNamingSampleTest` 在测试内直接构造文本，证明规则能命中，并断言分类、候选数量（1–3）、分类前缀和 evidence 非空：

| 构造文本 | 分类 | confidence | 候选名称 |
|---|---|---:|---|
| 星河超市 / 发票 / 2026-09-29 / 苹果 10.00 / 合计 ￥128.00 | **票据** | 0.8392 | `票据-2026-09-29-星河超市`、`票据-2026-09-29`、`票据-星河超市` |
| 采购合同 / 甲方：… / 乙方：… / 签订日期：2026年9月29日 / 第一条 服务条款 | **合同** | 0.8934 | `合同-2026-09-29-采购合同`、`合同-2026-09-29`、`合同-采购合同` |
| 数学练习册 / 姓名：… / 班级：… / 学号：… / 第12页 口算练习 | **练习册** | 0.9144 | `练习册-数学练习册` |
| 周末计划 / 买菜 / 读书 / 跑步 / 写周报 / 整理照片 / 给妈妈打电话 | **笔记** | 0.7222 | `笔记-周末计划` |

票据证据示例（JSON 原文）：`第 3 行日期：2026-09-29`、`第 2 行命中「发票」`、`第 5 行命中「合计」`、`第 5 行命中「￥」`、`第 5 行金额：￥128.00`、`评分：票据 2.61`。

## 测试结果

### JVM 单测

`SmartNamingTest`：**12 tests / 0 failures**（`app/build/test-results/testDebugUnitTest`，报告副本 `work/smart-naming/unit-TEST-SmartNamingTest.xml`，耗时 0.099 s）。

| 用例 | 断言 |
|---|---|
| `receiptWithIsoDateProducesOrderedNamesAndEvidence` | 票据、3 个候选顺序、日期/发票/合计证据、置信度 >0.7 |
| `contractWithChineseDateProducesContractName` | 合同、`合同-2026-09-29-采购合同`、甲方/签订证据 |
| `workbookCatchesNameClassAndPageNumber` | 练习册、姓名、`第12页` |
| `noteUsesShortLineLayoutAndTitle` | 笔记、短行版式、标题行 |
| `blankTextReturnsNoSuggestion` | 空白 → OTHER/空候选/0/空证据 |
| `formulaNoiseWithoutKeywordsReturnsNoSuggestion` | 公式噪声 → OTHER/空候选 |
| `overlongTitleIsTruncatedAndSanitized` | 20 字截断、控制字符与非法字符被清洗、总长 ≤34 |
| `moreWinningHitsRaiseConfidenceMonotonically` | 同分类更多命中 → 置信度更高 |
| `currentNameIsRemovedWithoutReorderingOrDuplicates` | 剔除当前名、顺序不变、无重复 |
| `metadataDateIsUsedWhenOcrHasNone` | OCR 无日期时用元数据日期并写证据 |
| `onlyFirstConfiguredLinesAreRead` | 第 24 行后的关键词不参与，放大行数后才命中 |
| `pageCountAndAmbiguousCategoriesDoNotForceAName` | 合同/练习册并列 → OTHER 空候选 |

### 设备测试（`emulator-5554`）

| 测试 | 结果 |
|---|---|
| `SmartNamingSampleTest` | **1/1 通过**（产出上面的 JSON） |
| `DefaultExportNamesFlowTest` | **1/1 通过**（11.504 s，既有测试） |
| `LibraryNamingTest` | **失败**，环境残留，见下 |

- 现场：`work/smart-naming/device-tests-smart-naming.xml`（最终版新测试 1/0）、`work/smart-naming/device-tests-final.xml`（一次运行 3 个用例：新测试通过、`DefaultExportNamesFlowTest` 通过、`LibraryNamingTest` 失败）。
- `LibraryNamingTest` 失败点：`BackupStore.backup` 读取 `cache/accessibility-f7682c6c-5ca8-49dd-a4a0-dc8b82058c33.png` 时 `FileNotFoundException`（`BackupStore.kt:274`）。该路径来自更早的 Accessibility PDF fixture 留下的 `edit_history` 快照，cache 文件已被清理；#24 的 `OcrRecordsTest` 已记录同一设备残留。本轮用设备数据库副本复核：`edit_history` id 154 仍引用该路径，`pages.original` 中该路径引用数为 0，对应 document id 也已不存在，即孤儿历史行。本轮未清设备数据、未改 `BackupStore`、未改该测试断言，因此如实记为既有环境失败，不计入 #25 回归。

## 已知限制

- **只读首页且只读缓存**：不会自动触发 OCR；未识别过的页面没有建议，用户需先识别。PDF 文档（`kind=pdf`）没有 `pages` 行，当前也不会从 PDF 文字层取文本。
- **纯规则、无模型**：只认中文/英文关键词、日期、金额与中文短行版式；同义词、语义分类、表格字段理解都没有。真实 OCR 乱码时通常返回空建议。
- **只有 1 个真实样本**：真实样本是公式页且无关键词，只能证明「无依据不硬凑」；真实票据/合同/练习册的准确率未测。
- **分类不写库**：分类 chip 只是展示，不会自动改 `folder/tags`；也没有新增分类字段。
- **页数参数暂不影响结果**：`Input.pageCount` 已接入并透传，但当前规则不据此改名或加分；保留给后续多页/票据页数区分。
- **元数据日期未在 UI 使用**：`Input.date` 支持回退日期并有单测；应用侧没有可靠的文档签发/创建日期，`doc.modified` 不当作单据日期，因此 UI 传 `null`。
- **标题取首行**：标题就是首个有意义行，不识别「公司名/抬头/第二行标题」等版式；首行若是店名，候选会带店名。
- **关键词是子串匹配**：没有分词，可能出现「合作同时」之类边界误命中；本轮未做词边界。
- **置信度不是标定概率**：分数只用于本次规则的相对强弱，不能解释为准确率；阈值 1.0 与权重是手工设定。
- **UI 未做设备点按/截图走查**：只保证编译通过，未在模拟器上真实点击「建议」按钮、未走查深色主题/大字体/窄屏弹窗布局。
- **真机未测**：只在 x86_64 API35 模拟器上验证；Android 版本、厂商 ROM、ML Kit 实际输出差异未验证。
- **重命名入口仍是两处**：文档库与编辑器各自打开弹窗；没有统一到一个全局重命名面板。

## 未验证项

- 真实票据、合同、练习册、手写笔记照片上的命中率、误判率与 1–3 个候选的可用性；没有标注语料，未做 precision/recall。
- 多页文档的首页不是标题页时（封面、空白页、目录页）的效果；页数参数未参与。
- PDF 文字层/PDF OCR 结果的命名建议（当前只走 `PageRow`）。
- 旋转、去斜、双页拆分后的页面，以及 `orientationCorrection != 0` 时的 OCR 文本首行顺序。
- 设备上「建议」按钮的真实点按、候选 chip 点选填入、取消后重开、编辑器中文档切换后的状态保持；本轮只有 JVM 纯函数和 OCR→JSON 设备测试。
- 中文输入法、超长文件名（接近 100 字符）在输入框与 chip 中的显示；超长只做了纯函数长度断言。
- 深色/AMOLED 主题、超大字体、横屏、无障碍 TalkBack 下 chip 与 evidence 的可读性。
- 分类建议是否应映射成标签/文件夹、以及用户撤销重命名后的建议状态；按范围决定本轮不写库。
- `LibraryNamingTest` 的既有缓存残留是否会在干净设备/清数据后消失；本轮未清数据复验。
