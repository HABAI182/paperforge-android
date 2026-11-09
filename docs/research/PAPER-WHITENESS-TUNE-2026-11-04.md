# 纸面白度温和档：跳过条件改成纸面分位、目标 251、增益上限 1.12

2026-11-04。承接 `PAPER-WHITENING-2026-11-04.md`（B0 纯除法、参数入档、"交付图已达标"的更正）与
`docs/acceptance/next/v200-feedback-2026-11-04/README.md`（第 2 条：该页判定 `paper-already-white` 直接跳过）。
本轮只动 `PaperWhiteFinal` 与 `scan_params.json`：**不改 UI / 导出 / 几何，不碰 annotation，没有 git 写操作**，
没有新开 AVD（复用 `paperforge_api35`），既有测试断言一条没改，也没有新增测试。

## 结论先行

1. **"243.4 均值的页被跳过"复现成立，但 243.4 不是纸面。** 那个数是仓库外
   `work/repro/page-after.jpg`（20011 批次构建导出的 DocUNet `10_1` 页）的**整页均值**；同一张图上按纸面场
   `ink<0.02` 取出的纸面像素占 82.3%、**均值 254.37、p50 255、低于 250 的只占 0.038%**。整页均值被
   18% 的墨/抗锯齿像素和底部阴影拉低（240–250 占 7.66%、150–235 占 4.78%、<96 占 2.05%）。
   **v1 的 `paper-already-white` 判定在这张图上是对的**，不是漏提。
2. **v1 的判定确实用错了统计量**（这条成立）：它读的是闭运算纸面场的 p10，而闭运算是**上包络**——
   会把比核窄的灰/影填成周围的亮纸。所以它既可能在纸面已经 254 时仍"看起来可以提"，也可能在纸面偏灰
   时被包络的高值骗过。本轮把判定换成**纸面像素（相对纸面场 ink < `paperWhiteInkFloor`）的分位**，
   与除法用的是同一个纸面场，语义自洽。
3. **现有语料里没有"纸面真的偏灰"的交付页。** 有冻结掩膜的真实页里（page-3 因伪配准弃用、不计），
   3 张交付页（notes-1/notes-2/book）纸面 252.7–254.9；真正偏灰的是 `legacy CLEAN` 两页（222.7 / 232.0）与 `legacy crop` 几页（185–241）；
   DocUNet 公开相机照片纸面 125–173（不是本产品渲染结果）。**所以"再白一点点"在这批语料上不存在可提的纸面。**
4. **温和档的收益只在偏灰页上**：`notes-1 legacy CLEAN` 空白 222.68 → **235.66**（近白 0.3% → 14.5%）、
   `notes-2 legacy CLEAN` 231.98 → **243.98**（5.7% → 18.2%）；同期淡笔画 1px 保留 **0.9411 → 0.9412**、
   内容 1px **0.7264 → 0.7300**、灰痕可见 **17.85% → 17.87%**（不动）、空白高频噪声 2.04 → 2.10。
5. **代价如实说：交付页上温和档比 v1 少 0–2.2 个灰阶。** 因为 v1 会把 252.7–253.4 的纸硬推到 255，
   而新判据"纸面低于目标才动作"在 251 上直接跳过它们（notes-1 254.93 → 252.69、notes-2 254.45 → 252.75）。
   想保留推力就把 `paperWhiteTarget` 调到 254（notes-1 → 253.93，仍不到 255），第 4 节的靶值扫描给了全表。
6. **合成边界：温和档 9/9 通过，v1 在"纸面 243"这一档败给它自己的目标**（243 → 255，97% 像素 >252，没有留余量；
   温和档 243 → 251，>252 为 0）。低对比铅笔（约 5% 残差）在温和档下 **ink 比 1.054**（对比度反而升高），
   极端暗页（纸面 70）**逐字节返回原图**。
7. **DocUNet 20 页回归 0.00pp。** 温和档保留了 v1 的"暗拍摄联锁"（纸面场 p10 < 120 直接返回原图），
   20 张相机照片全部落进该联锁，因此与基线逐项相同（淡笔画 1px 0.7095、灰痕清理 1px 0.1324）。
   作为对照，**去掉联锁**的 `v2-open` 会在 19/20 页生效：淡笔画 +0.56pp、灰痕清理 **−0.64pp**，
   超过"不得下降 0.5pp"的验收线，因此**没有采用**。这条同时说明联锁在防什么。
8. **设备路径：同一页、同一裁切、同一导出设置，改前改后交付页图逐字节相同**（sha256 `b5c944cf…`，
   1226×948，纸面 254.45，近白 81.76%）。因为该构建的练习册/强力去背景两条路径都把纸面送到 254.4–254.5，
   白度步在这台设备上没有可提的纸面——生效分支只能在离线的真实灰页上证明。这是本轮最硬的"不回归"证据，
   也是最诚实的"没收益"记录。

## 环境与仪器

- Python：`work/office-env/Scripts/python.exe`（3.12.14 / opencv 5.0.0 / numpy 2.5.4）。
- 掩膜：**沿用**`whitening/out/pages/*-masks.npz` 的冻结掩膜（与 `PAPER-WHITENING-2026-11-04` §三、§五同一套：
  content / faint / ghost / blank / ring），候选跑之前冻结，候选不能移动评分区。指标定义来自
  `whitening/scripts/measure.py`（`CONTENT_KEEP=0.03`、`GHOST_VISIBLE=0.05`、`NEAR_WHITE=250`）。
- DocUNet 回归：直接 import `paired-frontback/scripts/real_document_eval.py` 的
  `ink_map / fit / content_crop / register / tolerant`，与上一轮同一口径。
- Kotlin 侧不改判据以外的任何东西：`PaperWhiteFinal.apply()` 仍是"灰度页原地修改、每通道同一 gain"。
- 设备：`paperforge_api35`（Android 15 / SDK 35，`emulator-5554`），无头 `-no-window -no-audio
  -gpu swiftshader_indirect`。**没有新建 AVD**。构建 `:full-app:assembleDebug`（离线 Gradle 8.13 +
  JDK 17，`GRADLE_USER_HOME=work/toolchain/gradle-home`），版本号仍是 20011，签名与仓库既有 debug key
  一致（SHA-256 `d5232e43…f8f4e`），`install -r -d` 覆盖安装，数据保留。

## 一、复现：243.4 是整页均值，不是纸面

`scripts/inspect_gate.py`（把 `PaperWhiteFinal.kt` 的纸面场、分位、跳过判据逐行移植到 Python）：

| 页 | v1 判定 | 纸面场 p10 / p50 / p90 | 整页均值 | 纸面像素占比 | 纸面均值 | 纸面 p10 | 纸面 <250 |
|---|---|---:|---:|---:|---:|---:|---:|
| `repro/page-after.jpg`（20011，DocUNet 10_1） | **paper-already-white** | 255 / 255 / 255 | 243.45 | 82.3% | **254.37** | 252 | 0.038% |
| `repro/page-before.jpg`（20011 原包） | paper-already-white | 255 / 255 / 255 | 243.38 | 82.0% | 254.37 | 252 | 0.038% |
| 设备练习册导出（本批，1226×948） | paper-already-white | 255 / 255 / 255 | 246.15 | 81.8% | **254.45** | 253 | 0.0% |
| 设备强力去背景导出（本批） | paper-already-white | 255 / 255 / 255 | 246.17 | 81.8% | **254.46** | 253 | 0.0% |
| `notes-1 workbook`（交付） | ok（会动作） | 252 / 253 / 254 | 245.27 | 90.9% | 252.86 | 252 | 0.7% |
| `notes-2 workbook`（交付） | ok（会动作） | 253 / 253 / 255 | 244.51 | 90.0% | 253.35 | 253 | 0.5% |
| `book automatic`（交付） | paper-already-white | 255 / 255 / 255 | 248.22 | 88.8% | 254.74 | 254 | 1.0% |
| `notes-1 legacy CLEAN`（灰） | ok | 226 / 229 / 245 | 213.39 | 38.0% | 230.14 | 226 | 83.2% |

读法：

- 那三张"纸面场 p10 = 255"的页，纸面本身就在 254.4–254.5，**跳过是对的**；整页均值 243.4–246.2 的差
  来自 7.7% 的 240–250 层（小字抗锯齿边 + 页边阴影）与 4.8–7.3% 的中间调。
- 反过来，**同一个判据在交付练习册页上"会动作"，但纸面 252.86 已经在 251 以上**——它动作只是把纸推到 255，
  这正是第 5 条代价的来源。
- 偏灰的真实页（`legacy CLEAN` 222.7 / `legacy crop` 185–241）才是有纸面可提的地方；DocUNet 照片（125–173）
  是压力测试，不是本产品的输入分布。

`scripts/paper_census.py` 的完整普查（31 张页，含 10 张 DocUNet 原片）在 `results/paper-census.json`：
交付页纸面 252.4–254.9，没有任何一张落在 240–250 之间。

## 二、改了什么

`app/src/main/java/cn/paperforge/app/imaging/PaperWhiteFinal.kt`（`paper-white-final-1` → `-2`）：

| 项 | 旧 | 新 |
|---|---|---|
| 跳过判据 | 纸面**场**的 p10 ≥ 254 → 整页跳过 | **纸面像素**（相对纸面场 ink < `paperWhiteInkFloor`）在 `paperWhitePercentile` 上的分位 ≥ `paperWhiteTarget` → 跳过 |
| 目标白 | 常数 255 | `paperWhiteTarget`，默认 **251** |
| 增益上限 | 1.15 | `paperWhiteMaximumGain`，默认 **1.12** |
| 不裁剪 | 无（255 目标会把纸面顶到 255，淡笔画边沿被压平） | **逐像素 `gain ≤ target / value`**：任何像素都不会被推过目标白，因此 `target ≤ 252` 时**不可能裁剪**，也不存在变暗（gain 夹在 ≥1） |
| 极暗联锁 | 场 p10 < 120 返回原图 | **不变**（同一统计量、同一门限），另加 `paperWhiteInkFloor` 只用于"哪些像素算纸" |
| 色相 | 每通道同一 gain | 不变 |

新增 `scan_params.json` 键：

| 键 | 默认 | 含义 |
|---|---:|---|
| `paperWhiteFinal` | `1` | 总开关；`0` 完全关闭（与旧版同义） |
| `paperWhiteTarget` | `251` | 目标纸白（本轮验收区间 250–252；255 会吃掉全部余量） |
| `paperWhitePercentile` | `0.5` | 判据读纸面像素的哪个分位；0.5 = 纸面中位（纸面整体偏灰才动作），0.9 会被亮边骗过（本轮实测：`notes-1 legacy CLEAN` 的纸面 p90 ≥251，用它就整页跳过） |
| `paperWhiteInkFloor` | `0.02` | "算纸"的墨量上限；同时是 `ink 对比度下限`——低于它的像素才当作纸面抬起 |
| `paperWhiteMaximumGain` | `1.12` | 温和档上限（验收区间 1.10–1.15） |
| `paperWhiteKernel` / `paperWhiteEdge` / `paperWhiteMinimumPaper` | `61` / `800` / `120` | 与 v1 相同，未改 |

算子（与 `scripts/kotlin_port.py::white_apply` 逐行对应）：

```text
B   = 纸面场（长边 800 副本：椭圆闭运算 61×长边/1400 + 高斯 σ=kernel/6，放大回原尺寸）
ink = 1 - g / B
纸面像素 = ink < paperWhiteInkFloor
paperLevel = 纸面像素在 paperWhitePercentile 上的分位（最近秩）
paperLevel < paperWhiteMinimumPaper           → 返回原图（paper-below-minimum）
B 的 p10 < paperWhiteMinimumPaper             → 返回原图（field-below-minimum，继承 v1 的暗拍摄联锁）
paperLevel >= paperWhiteTarget                → 返回原图（paper-at-or-above-target）
否则 gain = clip(target / B, 1, maxGain)
       g >= target → gain = 1                        （不把已经更亮的像素压暗）
       1 <= g < target → gain = min(gain, target/g)  （不越过目标白，因此不裁剪）
     out = round(g * gain)，每通道同一 gain
```

## 三、真实页 before/after（冻结掩膜，1px 容差）

`scripts/evaluate_tune.py`，同一页、同一掩膜，只换算子：

| 页 | 算子 | 空白均值 | 近白≥250 | 灰痕可见 | 淡字 1px | 内容 1px | 空白噪声 | 光晕 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| notes-1 **legacy CLEAN**（纸面 230.1） | 基线 | 222.68 | 0.003 | 17.85% | 0.9411 | 0.7264 | 2.04 | −0.34 |
| | v1（255） | 239.48 | 0.619 | 18.11% | 0.9414 | 0.7330 | 2.15 | +0.56 |
| | **v2（251）** | **235.66** | **0.145** | **17.87%** | **0.9412** | **0.7300** | **2.10** | **+0.47** |
| notes-2 **legacy CLEAN**（纸面 241.0） | 基线 | 231.98 | 0.057 | 14.83% | 0.8785 | 0.6837 | 4.47 | +5.53 |
| | v1（255） | 247.68 | 0.671 | 15.07% | 0.8848 | 0.6889 | 4.49 | +6.38 |
| | **v2（251）** | **243.98** | **0.182** | **15.15%** | **0.8804** | **0.6867** | **4.47** | **+6.38** |
| notes-1 **WORKBOOK**（交付，252.9） | 基线 | 252.69 | 1.000 | 2.98% | 0.8320 | 0.5837 | 2.31 | +10.06 |
| | v1（255） | 254.93 | 1.000 | 3.01% | 0.8322 | 0.5839 | 2.33 | +10.31 |
| | **v2（251）** | **252.69** | **1.000** | **2.98%** | **0.8320** | **0.5837** | **2.31** | **+10.06** |
| notes-2 **WORKBOOK**（交付，253.4） | 基线 | 252.75 | 0.990 | 2.67% | 0.7515 | 0.6513 | 4.80 | +4.62 |
| | v1 | 254.45 | 0.991 | 2.66% | 0.7516 | 0.6513 | 4.81 | +4.56 |
| | **v2** | **252.75** | **0.990** | **2.67%** | **0.7515** | **0.6513** | **4.80** | **+4.62** |
| book **AUTOMATIC**（交付，254.7） | 基线 / v1 / v2 | 254.82 | 0.996 | 4.56% | 0.9340 | 0.9114 | 0.58 | +2.07 |

- 交付页上 v2 与基线**逐项相同**（`paper-at-or-above-target`），即"不动就是不动"；v1 的 +2.2 / +1.7 灰阶
  正是被新判据拒掉的"硬推 255"。
- 偏灰页上 v2 用 **+13.0 / +12.0** 灰阶换到近白 14.5% / 18.2%，淡字与内容 1px **不降反微升**，
  灰痕可见 **+0.02 / +0.32pp**（相对 17.85%/14.83% 的基数在噪声内），噪声 +3%，光晕与 v1 同量级。
- `page-3` 不进表：那一页的配准是横线重复纹理造成的伪单应（`PAPER-WHITENING` §2.3），掩膜不可信。
- **v2 提不到 251**：`notes-1 legacy CLEAN` 只到 235.66 而不是 251，因为闭运算纸面场在有大量亮边的页面上
  会把灰区的纸面估到纸白附近，除法 gain 在那些像素上接近 1。这是纸面场估计的限制，不是目标没设对
  （第 6 节的合成纯灰页 243 → 251 是准的）。

图（仓库外 `work/model-lab-20261103/whiteness-tune/results/figures/grey-page-before-after.png`；
左：交付 / 中：v1 / 右：v2，同一裁切。**不把带手写的页面裁切复制进仓库**，与上一轮"私有照片只读"的做法一致）。

## 四、目标白扫描（交付页能"再白多少"）

`scripts/target_sweep.py`，只改 `paperWhiteTarget`：

| 页 | 基线 | 250 | 251（默认） | 252 | 253 | 254 |
|---|---:|---:|---:|---:|---:|---:|
| notes-1 WORKBOOK | 252.69 | 252.69 | 252.69 | 252.69 | 252.69 | **253.93** |
| notes-2 WORKBOOK | 252.75 | 252.75 | 252.75 | 252.75 | 252.75 | **253.52** |
| book AUTOMATIC | 254.82 | 254.82 | 254.82 | 254.82 | 254.82 | 254.82 |
| notes-1 legacy CLEAN | 222.68 | 234.75 | 235.66 | 236.56 | 237.46 | **238.35** |
| notes-2 legacy CLEAN | 231.98 | 243.09 | 243.98 | 244.88 | 245.79 | **246.69** |

交付页纸面中位是 253 / 253 / 255，所以 250–253 全部"已在目标之上"，只有 254 还能推动 +1.2 / +0.8；
灰页则是单调的：每 +1 目标约 +0.9 灰阶，淡字/内容/噪声在所有档位都不降。
**要"再白一点点"就把 target 调到 254；要"不硬推 255、只在纸面偏灰时动作"就留 251。** 本轮按任务要求默认 251。

## 五、DocUNet 20 页防回归

`scripts/docunet_tune.py`（20/25 页通过配准，5 页弃用，与上一轮一致）：

| 算子 | 前保留 1px | 淡笔画 1px | 灰痕清理 1px | 空白均值 | 空白近白 | 生效/跳过 |
|---|---:|---:|---:|---:|---:|---:|
| 基线（不处理） | 0.8498 | 0.7095 | 0.1324 | 163.74 | 0.000 | 0/20 |
| v1（交付版） | 0.8498 | 0.7095 | 0.1324 | 163.74 | 0.000 | **0/20** |
| **v2（本批）** | **0.8498** | **0.7095** | **0.1324** | **163.74** | **0.000** | **0/20** |
| v2-open（去掉暗拍摄联锁，仅测量） | 0.8527 | 0.7151 | **0.1260** | 182.04 | 0.029 | 19/1 |

- **v2 与基线逐项相同**：20 张公开相机照片的纸面场 p10 全部 < 120，落进 v1 的暗拍摄联锁。验收线
  "淡笔画保留与灰痕清理不得下降 0.5pp" → **0.00pp，通过**。这条同时也是"该联锁让白度步不碰真实照片"的证据。
- **v2-open 是反例，不采用**：它让 19/20 页生效，淡笔画 +0.56pp、灰痕清理 **−0.64pp**，越线。逐页看，
  灰痕这一项在单页上从 −13.9pp 摆到 +12.8pp（第 20/17/9/19/25 页最差），是"纸面被抬起 → 局部中值场抬高 →
  测出来的灰痕墨量变化"的仪器效应叠加真实影响，方向性结论是：**在这些照片上动作，灰痕没有变好。**
- 口径提醒（沿用上一轮）：这里的"分数"是图像自身的 ink，任何把纸推白的算子都会机械抬高测量 ink，
  所以这组数只用于**方向性否决**，不能当"提升"读。

## 六、合成边界

`scripts/synthetic_tune.py`（夹具复用 `whitening/scripts/synthetic_sanity.py`，另加 243 灰纸、噪声页）：

| 用例 | 判据 | v1 | v2 |
|---|---|---|---|
| 纯白 255（微噪） | 最暗 ≥250 | PASS（逐字节不变） | PASS（逐字节不变） |
| **灰纸 243 + 文字**（机主报的档位，此处当纸面） | 纸面 ≥248、笔画 ≥0.85、>252 像素 ≤0.1% | **FAIL**（243 → 255，97.5% 像素 >252） | **PASS（243 → 251，>252 = 0）** |
| 灰纸 205 + 文字 | 纸面 ≥224、笔画 ≥0.90 | PASS（205 → 236，cap 1.15） | PASS（205 → 230，cap 1.12） |
| 阴影梯度 120→250 | 笔画 ≥0.80 | PASS（230.1 → 253.2，ink ×0.994） | PASS（230.1 → 248.4，ink ×0.998） |
| **低对比铅笔（约 5% 残差）** | 笔画 ≥0.90、纸面 ≥235 | PASS（214 → 246，ink ×1.001） | **PASS（214 → 240，ink ×1.054）** |
| 彩色印章 | 红通道对比 ≥0.90、纸面 ≥248 | PASS（246 → 255） | PASS（246 → 251） |
| 浓黑文字 | 笔画 ≥0.95、最暗 ≤20 | PASS | PASS |
| 噪声页（纸面 249，σ=2） | 噪声比 ≤1.15、纸面 ≥249 | PASS（噪声比 0.996） | PASS（逐字节不变，gain≈1） |
| **极暗拍摄（纸面 70）** | 逐字节返回原图 | PASS | **PASS** |

- 低对比铅笔这一档是**硬否决项**的回归：v1/v2 都是纯除法，`ink` 只被放大不被压掉（×1.054），
  而上一轮任何 `floor ≥ 0.03` 的 knee 都会把它整条擦掉。本轮的 `paperWhiteInkFloor = 0.02` **不是 knee**，
  只用于"哪些像素算纸"，不乘任何斜坡。
- v2 在"灰纸 243"这一档才是任务要的行为：落在 251、不留 255 的平台、不裁剪。
- 噪声页 v2 判 `paper-below-target`（纸面中位 250）但 `meanGain = 1.0`、`liftedShare = 0`：因为该页纸面场
  被噪声顶到 251+，`target/B` 夹在 1 以下。也就是说判据动作了但一个像素没动，诊断里能看出来。

## 七、设备路径（同一页导出对照）

流程（`scripts/device_export.py` / `device_ui.py` / `pull_export.py` / `measure_pdf.py`）：
用应用自己的「导入文件 → 相册导入」导入仓库外的真实练习册照片 `notes-1.jpg`（20011 那两页的来源），
练习册滤镜（默认），导出对话框保持默认（匹配原图 / 150 dpi / 图像质量 72），生成 PDF，
再从应用私有目录 `files/exports/<jobId>/` 抽出内嵌 JPEG 测量。**同一台模拟器、同一张原图、同一套设置，
只换构建。**

| | 20011 构建 | 本批构建 |
|---|---|---|
| 内嵌页图 sha256 | `b5c944cf9ab9f9dc…` | `b5c944cf9ab9f9dc…`（**相同**） |
| 尺寸 | 1226×948 | 1226×948 |
| 整页均值 | 246.152 | 246.152 |
| 纸面均值 / p10 | 254.45 / 253 | 254.45 / 253 |
| 近白 ≥250 | 81.76% | 81.76% |
| 240–250 层 | 7.64% | 7.64% |
| 中间调 150–235 | 7.27% | 7.27% |
| 墨 <96 | 1.05% | 1.05% |
| 空白高频 std | 6.53 | 6.53 |

- 结论：**白度步在这页上没有动作**（`paper-at-or-above-target`），交付页图逐字节相同 → 零回归。
  这也解释了为什么这台设备上"背景白度"不会因为本批改变：练习册与强力去背景两条路径都把纸面送到
  254.4–254.5（强力去背景导出：254.46 / 近白 81.80%），已经没有纸面可提。
- 同一页的 20011 参考图（DocUNet `10_1` 交付页，`work/repro/page-after.jpg`）也是纸面 254.37 / 近白 82.25%，
  与本表同一量级。
- 局限：**设备上无法产生"纸面 < 251"的输入**（渲染链路总会推满），所以生效分支没有设备证据；
  生效分支的证据是第 3 节的真实灰页（同一套测量口径）与第 6 节的合成页。模拟器不是真机，
  导出走的是 150 dpi / q72 的 JPEG，不能当印刷级纸白结论。
- **构建树是共享的：** 本批 `:full-app:assembleDebug` 时，工作树里还有另一条尚在进行的 annotation 改动
  （`EditorMoreSheet.kt` / `EditorScreen.kt` / `CaptureReview.kt` / `CameraScreen.kt` / `MainViewModel.kt`、
  `full-app/build.gradle.kts`），不是本批产生的，本批一行都没动它们。`imaging/` 目录下只有
  `PaperWhiteFinal.kt` 一处改动（`git status` 可查）。因为交付页图逐字节相同，这条并行改动没有影响本轮的
  纸面结论；但严格说"改后构建"并不是"只有我的改动"的构建。

## 失败与被否决的记录

- **"243.4 均值 = 纸面 243.4"是错的。** 复现出来的纸面是 254.37；v1 在那张图上的判定没错。
  这一条改变了整轮的方向：本轮不是"把 243 提到 251"，而是"让判据看纸面、并把 251 当目标"。
- **判据用 p90 会失效。** 第一版 v2 用纸面 p90（0.90）判定，`notes-1 legacy CLEAN` 因为亮边把 p90 顶到 ≥251
  而整页跳过——真实偏灰页一张都提不动。改 0.5 后才按预期动作。这是本轮自己踩到并修掉的坑。
- **`v2-open`（去掉暗拍摄联锁）不采用**：DocUNet 灰痕清理 −0.64pp，越线。
- **交付页比 v1 少 0–2.2 灰阶。** 不是 bug，是"不硬推 255"的直接后果；如果机主要的是"更白"，
  `paperWhiteTarget=254` 是代价最小的档位（第 4 节），但它仍然到不了 v1 的 255。
- **`notes-1 legacy CLEAN` 只提到 235.66，没到 251：** 纸面场在亮边附近会高估纸面，gain 被夹在 1。
  要修这个得改纸面估计（更小的核或分位场），本轮没做，理由是它同时会放大噪声并增加光晕（上一轮 C 的教训）。
- **光晕指标在 notes-1 交付页上是 +10.06**（基线就如此，不是本步引入）；v2 不动所以不变。

## 未验证 / 未做

1. **机主 20015 那张实拍页不在仓库里。** `work/` 下没有 20015 的拍摄件（只有
   `work/test-2.0.0-20015` 的 APK 与校验文件）。本轮所有"真实页"结论都来自
   `work/repro`、`work/feedback-20011`、`work/behavioral-v1` 与 `work/model-lab-20261103/whitening` 的产物；
   那张页的纸面水平**未测**。
2. **设备上没有生效分支的证据。** 渲染链路把纸面送到 254.4+，设备无法产生纸面 <251 的输入；
   生效分支只有离线灰页与合成页证据。
3. **没有新增 instrumented 测试。** 既有 `V200FeedbackTest.paperWhiteStepWhitensPaperWithoutErasingFaintStrokes`
   断言未改（它在该夹具上仍是"不倒退"），新判据没有对应的 androidTest。
4. **真机、真实淡字/透印逐像素真值、OCR 文本行保护**：仍没有；与上一轮一致。
5. **彩色页 / PDF 语义**：只做了合成印章 sanity，没有真实彩色文档与 PDF 输出回归（设备导出的是一页灰度练习册）。
6. **闭运算纸面场对亮边附近灰区的系统性高估**没有解决（第 3 节末尾）。
7. **构建树里有并行改动**：annotation 相关文件（`ui/EditorMoreSheet.kt` 等）在本批构建时处于修改状态，
   不是本批改的；`imaging/` 下只有 `PaperWhiteFinal.kt` 一处。交付页图逐字节相同，纸面结论不受影响，
   但那不是"纯净"的单改构建。

## 产出与复现

仓库外 `work/model-lab-20261103/whiteness-tune/`：

```text
scripts/
  kotlin_port.py         PaperWhiteFinal v1 的逐行移植 + v2 算子（与 Kotlin 一一对应）
  inspect_gate.py        v1 判定复现：纸面场分位、纸面像素分位、整页统计
  paper_census.py        31 张页的"还有没有纸面可提"普查
  evaluate_tune.py       真实页 before/after（冻结掩膜）+ 设备页自参考
  target_sweep.py        目标白 250–254 扫描
  docunet_tune.py        DocUNet 20 页回归（baseline / v1 / v2 / v2-open）
  synthetic_tune.py      9 个合成边界
  device_ui.py           adb + uiautomator 驱动（UTF-8 dump，带重试）
  device_export.py       导入→导出 PDF 的无头流程
  pull_export.py         从应用私有目录二进制安全地取 PDF
  measure_pdf.py         抽内嵌页图 + 纸面/近白/灰层/墨/外圈指标
  device_report.py       设备 before/after 汇总
results/
  gate-inspection.json  paper-census.json  page-tune.json  target-sweep.json
  docunet-tune.json     synthetic-tune.json  device-export.json
  figures/grey-page-before-after.png  figures/white-page-before-after.png
out/
  before/notes-1-workbook-before.pdf|.jpg    20011 构建导出（同一页）
  after/notes-1-workbook-after.pdf|.jpg      本批构建导出（逐字节相同）
  after/notes-1-clean-after.pdf|.jpg         本批构建、强力去背景导出
  before/page-after.jpg                     20011 批次交付页（整页均值 243.45）
```

复现（工作目录 `scripts/`，Python 为 `work/office-env/Scripts/python.exe`）：

```text
python -X utf8 inspect_gate.py
python -X utf8 paper_census.py
python -X utf8 evaluate_tune.py
python -X utf8 target_sweep.py
python -X utf8 docunet_tune.py
python -X utf8 synthetic_tune.py
python -X utf8 device_ui.py dump          # 设备在跑时
python -X utf8 device_export.py --label before
python -X utf8 pull_export.py "files/exports/<job>/扫描 11-04 15.14.pdf" ../out/before/x.pdf
python -X utf8 measure_pdf.py ../out/before/x.pdf ../out/before/x.jpg
python -X utf8 device_report.py
```

设备构建：

```text
GRADLE_USER_HOME=work/toolchain/gradle-home \
  work/toolchain/gradle-8.13/bin/gradle.bat :full-app:assembleDebug --offline
adb install -r -d full-app/build/outputs/apk/debug/full-app-debug.apk
```
