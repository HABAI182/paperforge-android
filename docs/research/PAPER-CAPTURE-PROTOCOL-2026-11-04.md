# 真实采集协议：同一张纸的正/反/逆光/无透印参考 + 多帧序列

2026-11-04。给机主用。目的是**拍完就能直接跑评估**，不用再补拍、不用猜条件。

背景：`PAPER-PAIRED-2026-11-04.md` 已确认手上没有真实正/反面配对，`PAPER-MULTIFRAME-2026-11-04.md` 已确认多帧只能压噪声项、且真实两帧只降 18–24% 墨迹标准差。本协议要一次把这两件事需要的照片都拍齐，并且带像素级真值的来源。

## 0. 判定口径（为什么是这些条件）

评估门槛（现有产品标准，不因本协议改变）：

| 指标 | 要求 | 需要的照片 |
|---|---|---|
| 淡墨正面保留率 | ≥ 99.99% | 正面 + 淡字真值 |
| 合成透印排除率 | ≥ 90% | 正面 + 无透印参考（黑背） |
| 真实透印排除（双面路径） | 待定 | 正面 + 反面（同一张纸） |
| 多帧收益 | 待定 | 同一页 ≥4 帧（目标 8） |
| 照明残差 | 待定 | 黑背参考 + 可计算平场的空白区 |

**真值优先级**：

1. **打印页（首选）**：用自己排的 PDF 打印，保留源 PDF。评估时把 PDF 渲染页用 SIFT 配准到照片，就能得到逐像素的“有字/空白/淡字”掩膜，不需要人手描。淡字用两级浓度打印（正文 100%、淡字 15–30%），并在 PDF 里就用不同图层颜色标记，便于生成掩膜。
2. **手写/铅笔页（次选）**：必须配合第 7 节的人工标注（overlay + JSON 坐标），否则只能当定性检查。

## 1. 纸张与页数

- **≥5 页，建议 8–10 页**；每页是**同一张纸**，正反面都要拍。
- 至少覆盖：
  - 2 页印刷正文（含标点、公式）+ 1 页只有淡字（15–30% 浓度）
  - 1 页手写铅笔淡字（真实笔迹，不是打印）
  - 1 页表格/横线（测线条保留）
  - 1 页“空白为主”（测空白区噪声与 shade）
- 纸张类型至少两种（打印纸 70–80g、笔记本纸 60–70g）；**留出页与训练页不同纸**。
- 每页在拍摄前先记录：纸张克重/品牌、书写工具（HB/2B 铅笔、0.38 中性笔）、打印浓度、是否双面书写。

## 2. 每页必须拍的条件（一个都不能少）

| 条件代码 | 拍什么 | 张数 | 用途 |
|---|---|---:|---|
| `front_normal` | 正面平放，正常照明 | **8 帧**（最低 4） | 多帧堆栈、单帧基线 |
| `front_blackback` | 正面，纸下垫**黑色卡纸/黑绒**（完全挡光） | 2 帧 | 无透印参考（透印≈0 的真实样张） |
| `front_transmitted` | 正面，**背面打强光**（透射），正面朝相机 | 1–2 帧 | 透印最强的样子；验证背面内容与正面的几何对应 |
| `back_normal` | 同一张纸翻面拍背面（正常照明） | 2 帧 | 双面减法的真实输入 |
| `back_transmitted`（可选） | 背面 + 背光 | 1 帧 | 背面墨迹几何参考 |

> 黑背参考是关键：它给出“同一张纸、同样照明、但没有透印”的对照，是目前唯一能把透印与照明残差分开的真实手段。

## 3. 拍摄设置（照抄即可）

- **三脚架/固定支架俯拍**，手机或相机均可；镜头正对纸面中心。
- 距离：整页入画且长边 ≥ 2000px（手机主摄 20–30cm 即可；不要用超广角边缘）。
- 光线：两侧 45° 漫射光（两盏台灯 + 白纸/柔光布），**关闭闪光灯**，避免单侧强光。
- 相机设置（手动优先）：
  - ISO ≤ 400（尽量 100）；固定曝光（不要自动）；固定白平衡（不要自动）；手动对焦并锁定。
  - **关闭**：HDR、夜景/多帧合成、美颜、降噪、锐化、数码变焦、二维码/文档模式的重度处理。
  - 格式：JPEG 最高质量或 PNG；**保留 EXIF**（时间、曝光、ISO、焦距有用）。
- **连拍 8 帧时纸张、灯光、相机一律不动**，只按快门；手不要入画。
- 每帧之间不要移动纸张；拍摄过程不要让手机自动切镜头（会改变焦距/畸变）。

## 4. 构图要求

- 整页入画，**四个纸角与纸边都可见**（配准与边界模型都需要）。
- 页边到画面边缘留 ≥5% 空白。
- 纸面尽量平行于传感器；允许小角度倾斜（配准能处理），不允许大透视（>15°）。
- 避免反光斑、手影、笔影；避免手指压纸（压纸用透明压板或胶带固定四角）。

## 5. 配准与曝光容差（本仓库实测约束，不是猜的）

| 项目 | 目标 | 硬约束 | 依据 |
|---|---|---|---|
| 帧间配准 RMS | ≤ 0.5 px | ≤ 1.5 px | 1px 误差已让完美双面排除从 0.787 掉到 0.743 |
| 帧间曝光差 | ≤ 2% | ≤ 5% | 私有四张裁切图实测有一对差 10.1%、另一对 5.4%（`multiframe/results/real-stacks.json`） |
| SIFT 内点 | ≥ 200 | ≥ 40 | 同页配对实测 422 / 118 内点 |
| 长边像素 | 2000–3000 | ≥ 1200 | `check_capture_set.py` 会拦 |

## 6. 命名与目录（严格）

```text
capture/
  p01/
    p01_front_normal_f01.jpg
    p01_front_normal_f02.jpg
    ...
    p01_front_blackback_f01.jpg
    p01_front_transmitted_f01.jpg
    p01_back_normal_f01.jpg
  p02/
    ...
  manifest.json
```

文件名契约（`check_capture_set.py` 按此解析，不匹配只报警告但会归入 unmatched）：

```text
<pageId>_<side>_<condition>_f<NN>.<jpg|jpeg|png>
side       ∈ front | back
condition  ∈ normal | blackback | transmitted | multiframe
```

> 注意：`front_normal` 的 8 帧在命名上属于 `normal` 条件；只有当你想把“连拍序列”与“单张基线”分开统计时才用 `multiframe`。默认按上表用 `normal` + `f01..f08`。

`manifest.json` 最小字段：

```json
{
  "collectedAt": "2026-11-05",
  "device": "phone model / camera + lens",
  "distanceCm": 25,
  "lighting": "two 45-degree diffused desk lamps, no flash",
  "settings": {"iso": 100, "exposure": "manual", "whiteBalance": "manual", "digitalZoom": false},
  "pages": [
    {
      "id": "p01",
      "paper": "80gsm office",
      "tool": "2B pencil + 0.38 gel pen",
      "sourceDocument": "docs/generated/page-01.pdf",
      "printNote": "body 100%, faint layer 20%",
      "sides": {"front": "printed text + faint math", "back": "bleed source only"},
      "annotations": "annotations/p01.json",
      "privacy": "private"
    }
  ]
}
```

## 7. 标注（真值）

**打印页**：不需要人工描。交付 `sourceDocument`（PDF），评估时渲染后配准即可得到掩膜；请在 PDF 里把淡字单独放在一层并用固定颜色（例如 30% 灰 `#B3B3B3`）绘制，正文纯黑。

**手写页**：交付 `annotations/<pageId>.json`：

```json
{
  "page": "p05",
  "side": "front",
  "coordinateSystem": "normalised 0-1, origin top-left, as seen in the photo before any rotation",
  "regions": [
    {"kind": "faint_glyph", "box": [0.12, 0.34, 0.18, 0.41], "note": "single faint character"},
    {"kind": "punctuation", "box": [0.31, 0.35, 0.33, 0.37], "note": "comma"},
    {"kind": "formula", "box": [0.10, 0.55, 0.60, 0.72], "note": "integral line"},
    {"kind": "blank_region", "box": [0.65, 0.10, 0.95, 0.30], "note": "no front ink, no bleed"},
    {"kind": "bleed_region", "box": [0.05, 0.80, 0.50, 0.95], "note": "back-side content visible here"}
  ]
}
```

`kind` 允许：`faint_glyph`、`punctuation`、`formula`、`blank_region`、`bleed_region`、`table_line`。框只要“大致包住”，评估只用它做区域统计，不做逐像素精度要求；**逐像素真值仍然来自 PDF 渲染**。

## 8. 采集后自检（必做）

```text
python -X utf8 work/model-lab-20261103/multiframe/scripts/check_capture_set.py \
  --dir <capture>/ --manifest <capture>/manifest.json --out <capture>/check/
```

- `usable: true`（0 个 error）才算交付完成。
- warning 逐条看：`too-few-frames`、`registration-loose`、`exposure-drift`、`missing-side`、`missing-no-bleed-reference` 都是要补拍或重拍的理由。
- 自检脚本只读照片，不改名不移动。

## 9. 交付与隐私

- 交付目录放在 `work/` 下（**不进仓库**，不复制进 `outputs/PaperForge`）。
- 私有笔迹默认不对外；manifest 里 `privacy: private` 的页不得用于截图、公开文档或示例图。
- 交付时附带：`manifest.json`、`annotations/*.json`（手写页）、源 PDF（打印页）、`check/capture-check.json`。

## 10. 拿到之后怎么跑（脚本映射）

| 想回答的问题 | 用哪批照片 | 现有脚本 |
|---|---|---|
| 多帧堆栈能降多少真实噪声 | `front_normal` 8 帧 | `multiframe/scripts/real_stacks.py`（当前读两帧，改帧列表即可） |
| 多帧能否过门槛 | `front_normal` + `front_blackback`（当真值/无透印参考） | 需新增：以黑背图为负样本参考，替换合成夹具 |
| 双面减法可行性 | `front_normal` + `back_normal` | `paired-frontback/scripts/duplex_evidence.py` 的误差网格给出容差；真实评估需新脚本 |
| 照明残差地板 | `front_blackback` 的空白区 | 现有 `evaluate_paper_context.py` 的 ink 定义可直接复用 |
| 参考截图代理 | 源 PDF 渲染 + `front_normal` | `bookScreenshotProxy` 口径（SIFT 配准 + 参考墨迹膨胀 25px 的空白区） |

**最小可行集**（时间不够时优先拍这个）：3 页 × （`front_normal` 8 帧 + `front_blackback` 1 帧 + `back_normal` 1 帧）+ 源 PDF。这套就能同时支撑“多帧”“照明残差”“双面几何对应”三项的第一轮评估；但**不能**单独支撑 5 页以上的留出验证。

## 11. 已知不可解决的部分（先说明，避免误期待）

- 真实纸面的透印是**非线性、空间变化**的，正面墨会遮挡背面信号；本协议能测出这个偏差，不能假设它是常数。
- 纸张弯曲/波浪导致局部离焦与阴影；`front_blackback` 与 `front_normal` 之间必须**保持纸张位置不变**（只换垫纸），否则两张不能直接相减。
- 手机自带的文档增强/多帧合成会污染数据：必须关闭（第 3 节）。
