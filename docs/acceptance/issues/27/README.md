# #27 · 对象级 PDF 水印的应用内入口

本页记录把已有 `PdfWatermarks` 对象级引擎接到应用内 PDF 工具的结果，以及栅格水印（已栅格化图片上的 logo / 平铺）本轮未做、只记录计划的范围说明。分享入口的既有实现见 [#28](../../issues/28/README.md)。

## 已接入：对象级水印处理入口

- **入口**：打开 PDF 进入阅读器 → 底部工具栏「工具」→「PDF 工具箱」→「内容与标注」→「水印处理 · 移除对象级水印」。在工具箱搜索「水印 / 去水印 / 移除水印」也能直接命中。原有「添加中文水印」保持不变，两个条目分开。
- **导入路径**：首页或转换页的 PDF 工具列表里选同一入口，会先打开系统文件选择器，导入后直接进入阅读器并弹出该模式的工具对话框（沿用现有 `pdfEntry` 跳转）。
- **范围**：对当前打开的 PDF 整篇处理（`selected` 文档的 `pdfPath`），不覆盖原件；结果写入应用导出目录（沿用 `OutputNames` 的文档标题命名），完成后弹出「PDF 已生成」结果页，可另存或分享。
- **调用顺序**：`MainViewModel.removeObjectWatermarks()` → `PdfWatermarks.scan` 先检查 → 未发现候选时提示「未检测到可识别的对象级水印」并结束，不创建任何输出文件 → 有候选才调用 `PdfWatermarks.remove` → 结果提示「已移除 N 个图片水印，删除/遮盖 M 处文字水印」。
- **失败与取消清理**：`PdfWatermarks.remove` 自身写 `.part` 后原子改名；入口在结果未发布成功时删除输出文件和同名 `.part`，覆盖扫描/删除抛错以及发布阶段取消。原件只读，始终不参与写入。
- **进度**：沿用 `MainViewModel.perform` + 顶部进度卡，取消走现有 `cancel()`；没有引入新的任务队列或新的进度界面。

### 代码位置

- `app/src/main/java/cn/paperforge/app/MainViewModel.kt`：`removeObjectWatermarks()`（扫描检查、副本生成、结果提示、失败清理）
- `app/src/main/java/cn/paperforge/app/ui/PdfToolsDialog.kt`：工具箱条目说明与「执行」分发
- `app/src/main/java/cn/paperforge/app/model/ToolCatalog.kt`：标题与搜索别名
- `app/src/main/java/cn/paperforge/app/ui/ToolFamilies.kt`：「内容与标注」分类
- `app/src/androidTest/java/cn/paperforge/app/quality/WatermarkEntrySampleTest.kt`：入口路径仪器测试

## 设备验证

`WatermarkEntrySampleTest` 在 API35 x86_64 模拟器（`emulator-5554`）上调用 `MainViewModel.removeObjectWatermarks()`，即工具菜单点击「执行」后走的同一入口，对设备私有样本 `files/samples/local-sample-3.pdf` 处理。样本含用户私人文档，**不进仓库**；测试输出写在设备 `files/watermark-proof/in-app-entry.json`。

| 指标 | 结果 |
|---|---|
| 输出为独立副本、原件 SHA-256 不变 | 是 |
| 输出页数 / 每页图片对象数 | 1 页 / `[1]` |
| 输出文字层字符数 | **0** |
| 引擎报告（结果提示原文） | 「已移除 1 个图片水印，删除/遮盖 1 处文字水印」 |
| 输出体积 | 509,226 → 482,737 字节 |
| `.part` 残留 | 无 |

同一测试类的 `noCandidatesDoesNotCreateCopy` 用一份无角落文字、无重复小图的合成 PDF 走同一入口，断言没有生成输出（`output == null`）且收到「未检测到可识别的对象级水印」提示。仪器结果：`WatermarkEntrySampleTest` **2 tests / 0 failures / 0 errors**（`app/build/outputs/androidTest-results/connected`）。

说明：该测试直接驱动 ViewModel 入口，不在仪器测试里点击 Compose 菜单；菜单到 ViewModel 的绑定是代码路径，未做真实点按的 UI 自动化。

## 栅格水印选区修复：本轮只记录计划

对象级路径只处理 PDF 对象：重复小图、可解码的文字 token。若第三方的 logo / 平铺水印已经和扫描页一起被栅格化成一张图，PDF 里没有独立对象可删，本轮的入口也不会假装能处理，UI 说明中已写明「已栅格化的 logo / 平铺水印不在本次范围」。

计划路径（**需 UI 选择区域，暂未实现**）：

1. 按目标页渲染工作位图（约 200–300 DPI），保留页面尺寸与旋转信息；
2. 复用现有区域交互让用户框选或涂抹水印区域（可参考扫描修复的涂抹选区、脱敏/裁切页的区域选择），允许一页多个选区；
3. 对选区做修复：先用纸面底色/邻域填充，纹理复杂时再评估接入现有 MI-GAN 区域填补，选区外像素保持原样；
4. 重新编码该页并回写到 PDF 副本（替换页图或新增图像对象），保留未处理页的对象结构，不覆盖原件；
5. 提供处理前后对照与撤销，必要时明确提示「整份栅格化」的代价（文字层/链接/表单会丢失）。

未做的原因：选区 UI、页图回写、修补质量评估都是独立工作量；在未验证修补效果前不能以「自动去栅格水印」名义上线。

## 未验证 / 限制

- 只验证了本地样本应用样本 `local-sample-3.pdf`；#28 的另外两份样本未在本轮重复跑应用内入口。
- 只跑 API35 x86_64 模拟器，未在真实手机和不同 Android 版本上验证；未验证微信等外部接收端。
- 文字层字符数与图片对象数由应用同版本 PDFBox 回读，不是独立渲染器/MuPDF 复核；像素级独立核对（非水印区像素差 0）见 [#28](../28/README.md)，本轮未重复。
- 未做菜单点按的 UI 自动化或截图走查；本次以 ViewModel 入口仪器测试为准。
- 加密 PDF、超大页数/大文件、表单/批注/证书签名与去水印同存的场景未专项验证。
- 失败/取消清理只走了代码路径，未注入磁盘满、导出目录不可写或处理中途取消做故障验证；成功路径已断言无 `.part` 残留。
- 识别仍是启发式：重复小图（≤640 px）、下/上 6% 且右侧的角落短文本；平铺、非角落、矢量图形和已栅格化水印不支持。
- 文字水印优先删除内容流里的 `Tj` token；字体解码失败时退回白色遮盖，被遮盖文字仍可被文本提取（与 #28 一致）。
