# #26 · 票据/收据/发票字段抽取、核对与 CSV/XLSX 导出（MVP）

本页记录本轮最小可用范围：只读取**已缓存**的离线 OCR 文本，用可解释的本地规则抽取日期、商家、金额、税额、发票号与分类，在可编辑的核对表里让用户修正，**只有用户点「导出 CSV / 导出 XLSX」时**才向应用导出目录追加一条记录。没有模型、没有联网、不自动触发 OCR；真实票据样本未提供，样本全部是测试内构造的合成文本。

## 范围与入口

### 入口

文档库首页 → 文档卡片右上「更多」（⋮）→「**票据信息**」（在「重命名」和「导出记录」之间）。

1. 点开后才读取该文档已缓存的 OCR，不预加载；扫描文档按页拼接（`ocrLayout.text` 优先，回退 `ocrText`），PDF 文档没有 `pages` 行，当前直接提示「没有可用的离线 OCR 文字」。
2. 读取失败或没有文字时弹出说明，不硬凑字段。
3. 有文字时弹出核对表：日期 / 商家 / 金额 / 税额 / 发票号五个可编辑输入框 + 分类 chip（餐饮 / 交通 / 购物 / 住宿 / 办公 / 其他），显示识别置信度与最多两条依据。
4. 底部「导出 CSV」「导出 XLSX」。导出按钮在全部字段为空时禁用。
5. 导出后关闭核对表，走既有「文件已生成」弹窗：可「另存为」（系统 SAF `ACTION_CREATE_DOCUMENT`，可存到系统 Documents）、分享或关闭。

### 代码位置

- 新增 `app/src/main/java/cn/paperforge/app/model/ReceiptFields.kt`：纯函数规则（无 Android 依赖，JVM 可直接测）。
- 新增 `app/src/main/java/cn/paperforge/app/data/ReceiptLedger.kt`：读取缓存 OCR、追加 CSV、由 CSV 重建 XLSX。
- 新增 `app/src/main/java/cn/paperforge/app/ui/ReceiptReviewDialog.kt`：可编辑核对表（`Dialog` + 滚动列）。
- `app/src/main/java/cn/paperforge/app/MainViewModel.kt`：新增 `receiptDraft(doc)`（IO 线程读取缓存文本并解析）、`exportReceipt(record, source, xlsx)`（写文件、`publishOutput`、提示）。
- `app/src/main/java/cn/paperforge/app/ui/HomeScreen.kt`：更多菜单项、核对表状态与弹窗接线。
- 新增 `app/src/test/java/cn/paperforge/app/model/ReceiptFieldsTest.kt`：15 个 JVM 用例。
- 新增 `app/src/androidTest/java/cn/paperforge/app/ReceiptExportFlowTest.kt`：抽取 → 编辑 → 追加 CSV → 重复导出 → XLSX 回读。
- 新增 `app/src/androidTest/java/cn/paperforge/app/ReceiptReviewDialogTest.kt`：核对表字段编辑、分类 chip、两个导出按钮的回调。
- 没有新增依赖；没有改 `#21/#23/#24/#25/#27` 的代码或测试断言，也没有改既有测试断言。

### 复用与未复用

| 需要 | 做法 |
|---|---|
| 日期写法 | 与 `SmartNaming` 相同的三种正则（`2026-09-29` / `2026年9月29日` / `20260929`）。`SmartNaming` 把这几个正则设为 private，且 #25 已提交，按规矩不改它，所以本文件按同一规则独立实现，并用单测钉住。 |
| 位置权重 | 参考 `SmartNaming` 的「越靠上权重越高」思路，但票据合计常在底部，因此把底部的下限抬到 0.70（不是 0.25）。 |
| 导出目录 | 复用 `Repository.exports`（应用私有 `files/exports`），新增子目录 `PaperForge/`。 |
| 发布链路 | 复用 `MainViewModel.publishOutput` → `Repository.recordOutput` → 既有「文件已生成」弹窗（另存为 / 分享）。 |
| 文件名清洗 | 生成记录时不再另造文件名；账本使用固定文件名 `票据记录.csv` / `票据记录.xlsx`（追加语义需要稳定文件）。`OutputNames` 仍用于导出弹窗里显示名。 |
| CSV/XLSX 工具 | 仓库**没有** Apache POI/XSSFWriter 依赖，Android 侧也没有 CSV 库。表格功能里 `PDF/图片 → CSV 表格包` 实际产出的是「每表一个 CSV 的 ZIP」；`PhoneTables.write` 的 XLSX 是纯 Kotlin 拼 OOXML + `java.util.zip`。本轮记账本需要**真正单文件、可追加**的 CSV 与 XLSX，因此只沿用同一套「手写 OOXML」思路，在 `ReceiptLedger` 内自带一个小写出器（sheet 名 `票据记录`，8 列，金额/税额/置信度为数值单元格）。没有复用 `PhoneTables.write` 是因为它固定 sheet 名 `P{page}-T{n}` 且会在包里附带结构 JSON，不适合用户直接打开的账本。 |

## 规则与阈值

### 输入

```kotlin
ReceiptFields.parse(ocrText = 页 OCR 文本, lineLimit = 80)
```

只读前 80 个非空行（可在 1–200 之间调整），去除控制字符、按 `\n` 分行；空文本返回空 `Record`（全字段空、`confidence = 0`、`Category.OTHER`、无依据）。

输出：

```kotlin
ReceiptFields.Record(
    date, merchant, amount, tax, invoiceNo,   // 归一的字符串，缺失为 ""
    category,                                  // FOOD/TRANSPORT/SHOPPING/LODGING/OFFICE/OTHER
    confidence,                                // 0..0.99
    evidence,                                  // 最多 8 条，含行号与命中片段
)
```

### 日期

按行顺序、每行依次尝试三种写法，取第一条**合法**日期（`LocalDate.of` 校验，年份限 1900–2100，与 `SmartNaming` 一致）：

- `2026-09-29` / `2026/9/29` / `2026.9.29`
- `2026年9月29日`
- `20260929`（8 位紧凑日期；同一个 8 位串不会被当成发票号）

输出统一为 `yyyy-MM-dd`；时分秒忽略。证据：`第 3 行日期：2026-09-29`。

### 金额

标签按优先级从高到低（同一行只取最高优先级的一个候选）：

| 优先级 | 标签 | 计入置信度的质量系数 |
|---:|---|---:|
| 6 | 价税合计 | 1.00 |
| 5 | 合计 | 1.00 |
| 4 | 总计 | 0.95 |
| 3 | 应付 / 实付 | 0.95 |
| 2 | 金额 | 0.85 |
| 1 | 无标签，只有 `￥/¥/$`、`元` 或小数 | 0.70 |

- 标签后取第一个「像金额」的数字：支持 `￥/¥/$`、千分位（`1,280.50`）、1–2 位小数、`元` 后缀；统一归一为两位小数字符串（`9.9` → `9.90`，`1,280.5` → `1280.50`）。
- 过滤：后接 `-`/`/`/`.`/`年/月/日/时/分/秒` 的（日期）、前 6 个字符含「电话/手机/传真/号码/代码/编号/身份/No/#」的、整数位超过 8 位的（手机号、发票代码长度）、非正数的，都不算金额。
- 多候选时取标签优先级最高者，同优先级取更靠上的行；证据会写「另有 N 个金额候选，按标签优先级取此值」。
- 全文没有合计类标签时，取**最靠下**的带货币符号/小数点的金额作为兜底，并在证据里标明「货币符号/末行金额」；这是启发式，不是保证。
- 税额行（含税额/税金/税款/税费）不参与兜底金额。

### 税额

`税额 / 税金 / 税款 / 税费` 标签后第一个合法金额；「价税合计」本身不算税额（单测覆盖）。同一行既有「价税合计」又有「税额」时能分别抽出。

### 发票号

1. 优先带标签：`发票代码 / 发票号码 / 发票号 / 票据号码 / 发票编号 / 收据号码 / 单据号码 / 号码 / No. / NO / NO`，后接 8–20 位数字（允许数字之间单个空格，OCR 常见）。
2. 标签前 6 个字符含「电话 / 手机 / 传真」时跳过该行，避免把手机号当发票号。
3. 没有标签时回退到 8–20 位连续数字，但排除：含货币符号/合计/金额/税额的行、含电话/手机/身份的行、能解析成合法日期的 8 位串。
4. 证据写明是「发票号」还是「长数字串」，后者置信度打折。

### 商家

- 先去掉前缀 `销售方 / 商户 / 商家 / 店名 / 收款方 / 开票方 / 单位名称 / 名称`（及冒号、括号）。
- 跳过：空行、纯日期行、含 `￥/¥/$` 的行、含合计/金额/税额的行、整行只是「发票/收据/票据/小票/单据/欢迎光临/谢谢惠顾」等泛化词的行。
- 命中后缀关键词（超市、便利店、商场、商城、百货、餐厅、饭店、餐馆、咖啡、酒店、宾馆、旅馆、民宿、药房、药店、公司、中心、加油站、书店、市场、门诊、医院、店）的候选得 1.0，行号 0 加 0.3、行号 1–2 加 0.15；
- 没有后缀时，只接受前 3 个非空行，首行 0.6、其他 0.45。
- 取分数最高者；清洗时去掉尾部泛化词（如「星河超市发票」→「星河超市」），压缩空白、截断 30 个码点。证据：`第 1 行商家「星河超市」（含「超市」）` 或 `（首行位置）`。

### 分类

分类只用于核对表的 chip 与导出列，**不写文档库**。关键词 × 位置权重取最高分，无命中为「其他」：

| 分类 | 关键词（权重） |
|---|---|
| 餐饮 | 餐厅/饭店/餐馆(1.0)、美食/咖啡/奶茶/火锅/烧烤(0.9)、食堂/酒楼(0.8)、餐(0.6) |
| 交通 | 地铁/公交/出租车/高铁/机票(1.0)、打车/火车/加油(0.9)、机场/停车/过路/ETC(0.8) |
| 购物 | 超市/便利店/商场(1.0)、商城/百货(0.9)、购物/旗舰店/专卖店(0.8) |
| 住宿 | 酒店/宾馆/旅馆(1.0)、住宿/民宿(0.9)、客房(0.8) |
| 办公 | 文具/办公(1.0)、打印/复印/耗材(0.9)、纸张/图书/书店(0.8) |

### 置信度

按命中字段加权，再乘来源位置系数：

| 字段 | 基础权重 |
|---|---:|
| 日期 | 0.20 |
| 商家 | 0.18 |
| 金额 | 0.30 |
| 税额 | 0.12 |
| 发票号 | 0.16 |

- 位置系数（非空行 0 基序号）：0–3 → 1.00，4–8 → 0.92，9–20 → 0.82，21+ → 0.70。
- 质量系数：金额按上表 1.00/0.95/0.85/0.70；发票号带标签 1.00、长数字串 0.80；商家含后缀 1.00、仅首行 0.85。
- 相加后夹取到 `0..0.99`。字段越多、来源越靠前，置信度越高；缺失字段不加分也不编造。
- 这不是标定概率，只是本次规则的相对强弱。

## 导出路径与格式

| 项 | 实际实现 |
|---|---|
| CSV | `files/exports/PaperForge/票据记录.csv`，UTF-8 **带 BOM**（`EF BB BF`），CRLF 换行，首次导出写表头；之后每次导出**追加一行**，不覆盖。 |
| CSV 列 | `日期,商家,金额,税额,发票号,分类,置信度,来源`（来源=文档标题）。含逗号/引号/换行的值会加引号并双写引号。 |
| XLSX | `files/exports/PaperForge/票据记录.xlsx`。每次导出 XLSX 时：先按 CSV 账本追加同一行，再由**整份 CSV** 重建工作簿（所以 CSV 与 XLSX 行数一致，切换格式不会丢行）。 |
| XLSX 结构 | 手写最小 OOXML（`[Content_Types].xml`、`_rels/.rels`、`xl/workbook.xml`、`xl/_rels/workbook.xml.rels`、`xl/styles.xml`、`xl/worksheets/sheet1.xml`），工作表名 `票据记录`，8 列列宽 18；金额/税额/置信度写成数值单元格（格式 `0.00`），其余为内联字符串；发票号保留为文本以保住前导 0。 |
| 发布 | 写完后 `publishOutput` → `Repository.recordOutput` → 既有「文件已生成」弹窗：另存为（SAF）/ 分享 / 关闭。 |
| 与任务描述的差异 | 任务写「写入 `Documents/PaperForge/`」。本应用**没有** `MANAGE_EXTERNAL_STORAGE`，也没有直写公共 `Documents` 的代码；`docs/acceptance/next/auto-export-summary.json` 里记录的 `Documents/PaperForge-Exports` 是用户授权的 SAF 目录。因此本轮沿用既有导出目录（应用私有 `files/exports`）下的 `PaperForge/`，再用既有「另存为」让用户落到系统 Documents；没有新增存储权限，也没有假装直写公共目录。 |

## 设备实测（不是估算）

- 设备：`paperforge_api35(AVD)`，Android 15 / SDK 35，`emulator-5554`，x86_64；沿用已有模拟器，未新开。
- 本功能**不调用 OCR 引擎**：测试把合成票据文本写到真实 `PageRow.ocrText`（与引擎缓存落点一致），再走生产的 `ReceiptLedger.sourceText` → `ReceiptFields.parse`。
- 证据文件：设备 `files/receipts/device-receipt.json`、`票据记录.csv`、`票据记录.xlsx`，已拉回仓库外 `work/receipt/`，副本见本目录（`device-receipt.json`、`ledger-sample.csv`、`ledger-sample.xlsx`、`xlsx-openpyxl.json`、`device-tests.xml`）。

### 合成票据（synthetic=true，测试内构造，不是真实照片）

输入文本（8 行）：

```
星河超市
发票
2026-09-29
苹果 10.00
合计 ￥128.00
发票号码：0123456789
税额 ￥12.80
价税合计 ￥140.80
```

抽取结果（设备返回的 JSON）：

| 字段 | 值 |
|---|---|
| 日期 | `2026-09-29` |
| 商家 | `星河超市` |
| 金额 | `140.80`（价税合计优先于合计 128.00） |
| 税额 | `12.80` |
| 发票号 | `0123456789` |
| 分类 | `SHOPPING`（购物） |
| confidence | **0.9136** |
| evidence | `第 3 行日期：2026-09-29`、`第 1 行商家「星河超市」（含「超市」）`、`第 8 行「价税合计」金额：￥140.80`、`另有 1 个金额候选…`、`第 7 行税额：￥12.80`、`第 6 行发票号：0123456789`、`第 1 行分类词「超市」` |

编辑后导出（模拟用户在核对表里改两个字段 + 换分类）：商家 `星河超市（已改）`、金额 `256.00`、分类 `餐饮`（FOOD）。

CSV 实测：

| 检查 | 结果 |
|---|---|
| 文件存在 | 是，`/data/user/0/cn.paperforge.app/files/exports/PaperForge/票据记录.csv` |
| 体积 | **250 字节** |
| BOM | `efbbbf` |
| 首次导出 | 表头 + 1 行 = **2 行** |
| 重复导出 | 追加为 **3 行**，两行数据完全相同 |
| 行内容 | `2026-09-29, 星河超市（已改）, 256.00, 12.80, 0123456789, 餐饮, 0.91, 扫描 10-31 12.43` |

XLSX 实测：

| 检查 | 结果 |
|---|---|
| 文件存在 / 体积 | 是 / **2250 字节** |
| Android 侧回读 | `ZipFile` 打开成功，含 `[Content_Types].xml`、`xl/workbook.xml`、`xl/worksheets/sheet1.xml`；`DocumentBuilderFactory` 解析成功；`row` 元素 **3 个**（表头 + 2 行） |
| 工作表名 | `票据记录` |
| 宿主侧第三方回读 | 用 `openpyxl 3.1.5` 打开：`sheet=票据记录`，`dimensions=A1:H3`，3 行 8 列全部可读 |
| 单元格类型 | 金额 `256.0`(n)、税额 `12.8`(n)、置信度 `0.91`(n)、发票号 `0123456789`(s，前导 0 保留)、商家/分类/日期/来源为字符串 |
| 证据文件 | 本目录 `device-receipt.json`、`xlsx-openpyxl.json`、`ledger-sample.csv`、`ledger-sample.xlsx` |

## 测试结果

### JVM 单测

`ReceiptFieldsTest`：**15 tests / 0 failures**，0.111 s（`app/build/test-results/testDebugUnitTest`）。

| 用例 | 断言 |
|---|---|
| `isoDateCurrencyAndTotalAreExtracted` | ISO 日期、￥、首行商家、合计金额、购物分类、置信度 >0.6、证据含日期与合计 |
| `chineseDateLabeledInvoiceAndTaxUsePriceTaxTotal` | 中文日期、销售方前缀清洗、带标签发票号、价税合计优先、税额、办公分类、置信度 >0.85 |
| `slashAndDotDatesAreAccepted` | `2026/9/29`、`2026.9.29` 都归一为 `2026-09-29`；不存在的 `2026-02-31` 不编造 |
| `compactDateIsNotMistakenForInvoiceNumber` | `20260929` → 日期，且不成为发票号 |
| `thousandSeparatorsAndDollarSignAreNormalised` | `$1,280.50` → `1280.50`，英文首行商家 |
| `chineseTotalBeatsSmallerLateCandidates` | 合计 > 实付，证据写「另有候选」 |
| `taxIsReadOnThePriceTaxTotalLineButPriceTaxTotalIsNotTax` | 同行价税合计+税额可拆分；只有价税合计时税额为空 |
| `merchantFallsBackToTheFirstMeaningfulLine` | 无后缀首行商家与「首行位置」证据 |
| `missingFieldsStayEmptyAndLowerConfidence` | 只有日期时其余为空、置信度 <0.3 |
| `blankTextYieldsAnEmptyRecord` | 空/空白/换行 → 全空、0、无依据 |
| `outOfOrderLinesAreStillExtracted` | 乱序（合计在最前）仍能抽全 4 个字段 |
| `moreFieldsRaiseConfidenceMonotonically` | 日期 < 日期+商家 < 日期+商家+金额 |
| `theSameEvidenceLowerInTheDocumentScoresLess` | 同一依据靠后时置信度更低 |
| `overlongTextIsBoundedAndLineLimitIsHonoured` | 5000 行不崩、evidence ≤8；超出行限的合计不参与 |
| `identifiersAreNotMistakenForAmountsOrInvoiceNumbers` | 手机号/订单号不进金额 |

### 设备测试（`emulator-5554`）

| 测试 | 结果 |
|---|---|
| `ReceiptExportFlowTest` | **1/1 通过**（2.251 s）——抽取、编辑、追加、重复追加、XLSX 回读 |
| `ReceiptReviewDialogTest` | **1/1 通过**（4.307 s）——字段编辑、分类 chip、两个导出按钮回调；未点导出时无回调 |
| 合计 | **2 tests / 0 failures**（suite 7.008 s） |

### 既有相关测试（导出/命名）

| 测试 | 结果 |
|---|---|
| `DefaultExportNamesFlowTest` | **通过**（13.109 s） |
| `LegacyOutputNamesFlowTest` | **通过**（0.314 s） |
| `ExportPresetsFlowTest` | **失败**（`ExportPresetsFlowTest.kt:102`，`expected:<2> but was:<3>`），**既有问题，非本轮引入**：该测试断言 PNG 导出 ZIP 只有 2 个条目，但 `66011b6`（page-stages）给 `PdfTools` 的图片 ZIP 增加了 `images.json`（有 `stages` 时写入），提交未同步更新该断言。实测该 ZIP 条目为 `page-0001.png`、`page-0002.png`、`images.json`。按规矩不改既有断言，也不在 #26 顺手修。 |
| `LibraryNamingTest` | **失败**（`BackupStore.kt:274` 读 `cache/accessibility-f7682c6c-….png` 时 `ENOENT`），与 #24/#25 验收页记录的同一设备残留 `edit_history` 孤儿行一致，**既有环境问题**。 |

## 已知限制

- **没有真实票据样本**：用户未提供票据/收据/发票照片。所有设备数字来自测试内构造的合成文本；规则对真实 OCR 的命中率、误判率、字段级准确率**没有测**。
- **规则式，无模型**：只认上表里的中文标签、关键词、日期与金额格式；不同票种（增值税专用发票、电子发票、出租车票、定额发票、多栏小票）的版式差异、表格、手写体、印章遮挡都没有专门规则。
- **不触发 OCR**：只读已缓存文本；未识别过的文档、PDF 文档（无 `pages` 行）都拿不到字段。
- **只读前 80 个非空行**：超长小票/多页票据靠后的合计会被忽略；多页扫描会直接拼接，不按页切分，跨页票据可能串字段。
- **金额兜底是启发式**：没有合计类标签时取最靠下的货币/小数金额，可能取到税额或最后一件商品；「小计/优惠/找零/折扣」没有专门排除规则（只排除了税额标签行）。
- **发票号回退可能误取**：8–20 位数字串可能是订单号、流水号；带「号码」标签但实际是手机号的，仅靠前 6 字符的「电话/手机/传真」判断跳过，不能保证。
- **商家是启发式**：依赖后缀/首行，店名不含后缀且不在首行时可能抽不到或抽到抬头；"XX公司发票" 这类混合行只做尾部泛化词清洗。
- **金额为字符串**：归一为两位小数字符串，没有做多币种换算；`HK$`、`RMB`、负数/退款（`-128.00`）没有专门支持，`￥` 被 OCR 识别成 `Y` 时无法识别。
- **CSV 账本非事务**：纯追加，进程被杀可能留下半行；并发导出没有加锁；重复导出会产生重复行，没有去重/撤回/按月份分表。
- **XLSX 是手写的最小 OOXML**：不含样式、筛选、冻结窗格、列类型校验；只有 `openpyxl` 与 Android zip/XML 两种回读证据，**没有用 Excel / WPS / Google Sheets 真机打开验证**；金额写成数值单元格，超过 8 位的整数不会写成金额（会被过滤）。
- **`Documents/PaperForge/` 未直写**：应用没有公共存储权限，实际写在应用私有 `files/exports/PaperForge/`，再通过既有「另存为」落到系统目录。任务描述里的公共路径需要用户每一步授权，本轮没有做「一键写入系统 Documents」。
- **分类只是导出字段**：不写 `folder/tags`，也不产生文档分类。
- **UI 只做了 Compose 断言**：没有在模拟器上截图走查深色/AMOLED、超大字体、窄屏下的表单滚动与 chip 换行；核对表是 `Dialog` + 垂直滚动 + 横向滚动 chip 行。
- **未测真机**：只在 x86_64 API35 模拟器验证；厂商 ROM、Android 版本差异未验证。

## 未验证项

- 真实票据照片/扫描件的字段准确率（precision/recall）、不同票种的版式适配；没有标注语料。
- 增值税发票的「发票代码 + 发票号码」两行、密码区、大写金额、`价税合计（大写）` 等版式的解析。
- OCR 把 `￥` 识别成 `Y`/`¥` 混用、千分位被识别成空格、小数点丢失时的表现；只覆盖了单测里的干净文本。
- 多页票据/长小票超过 80 非空行时，底部合计的抽取；以及按页拆分票据页。
- 导出失败（磁盘满、目录不可写）时的用户提示与恢复；CSV 半行损坏后的修复。
- 在 Excel / WPS / Google Sheets / 手机 Office 里打开 `票据记录.xlsx` 的实际效果（字体、列宽、数字格式、中文 sheet 名）。
- 反复导出几百行后的 CSV/XLSX 体积与打开速度；本轮只验证了 2 行数据。
- 系统「另存为」把 `票据记录.csv` 保存到 `Documents/PaperForge/` 的完整链路（需要人工在系统文件选择器里操作，本轮未做设备点按）。
- 真实点按「文档更多 → 票据信息 → 导出 CSV」的完整 UI 链路：本轮验证的是 Compose 对话框组件（编辑/chip/导出回调）和仓库层真实写文件；`MainViewModel.receiptDraft` / `exportReceipt` 与「文件已生成」弹窗只保证编译通过，没有在模拟器上逐击。
- 与 #25 智能命名同时打开、文档被删除/重命名后的核对表状态；本轮没有做并发/切换场景。
