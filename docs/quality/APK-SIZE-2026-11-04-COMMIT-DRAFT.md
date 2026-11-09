# 提交说明草稿（不提交）

> 用途：本轮“包体测量 + 遗留代码清理”的提交说明候选。**未执行任何 git 写操作**，本文件也不提交；由机主/收尾人决定是否采用、如何拆分提交。
> 关联报告：[APK-SIZE-2026-11-04.md](APK-SIZE-2026-11-04.md)。

---

## 建议的提交信息（可直接粘贴）

```text
chore(app): drop unreferenced declarations and record the 169 MB payload boundary

Remove 15 declarations (103 lines) across 11 files that no source, resource,
manifest, or test names any more. Every symbol was checked with a
word-boundary reference count over 663 product source files (6,397 top-level
declarations); the deleted names were confirmed absent from the rebuilt
release dex. No behaviour is intentionally changed.

Deleted (file: symbol — evidence):
- ui/EditorMoreSheet.kt: private MoreGroup() — name occurs only at its declaration
- ui/HomeScreen.kt: private QuickCard() — same; the workbench shortcut row uses WorkspaceComponents
- ui/ToolCapabilityDialog.kt: ToolPinButton() — pinning is implemented inline at WorkspaceComponents.kt:365
- ui/PaperDesign.kt: PaperMotion.screenMs/exitMs/enterMs — never read; transitions use stateMs/panelMs
- annotation/PageAnnotations.kt: HIGHLIGHT const — never referenced (highlight is kind == "highlight" + mark.color)
- data/Database.kt: DocumentDao.originalReferences() — unused Room @Query; schema untouched
- data/LibraryStorage.kt: StorageReport.reclaimable — never read
- model/PageAnnotation.kt: isBox, annotationFromDrag() — never referenced
- pdf/NativeTextCoverage.kt: substantial() — never referenced
- pdf/PdfAssembly.kt: private targetIndex() — never referenced
- pdf/PdfWatermarks.kt: CORNER_BAND const, unused local pagesWithText set

Deliberately kept (needed for reflection, serialization, or pending wiring):
- MainViewModel.openOutputInLibrary/extractText/officeResult, PdfTools.decrypt:
  zero call sites today, but they back documented features; note that
  OutputResultDialog actually opens via vm.open(doc) in MainActivity.kt:857,
  so the docs/acceptance v200 record and the code disagree.
- Data-class properties (DecodePlan.sourceLong/requestedLong, PageCopyResult.firstPosition,
  OCRResult.wordBoxes): removing them renumbers componentN and would silently
  change destructuring/serialization.
- ocr-paddle vendored utilities: UPSTREAM.md pins the upstream commit and
  requires original headers; <200 B saved.
- Every asset under share/unpack/program/layout/org/com/mlkit models, all model
  weights, the CJK font, all licence texts.

Size result (arm64-v8a full release, same build_distribution.py path):
- before (clean 19ab940): 169,491,680 B
- after (5e156d2 + worktree): 169,489,672 B  (delta -2,008 B, -0.0012%)
- lib 104,793,414 B unchanged; res/resources.arsc/root Java resources unchanged;
  dex -1,436 B zipped (-5,660 B unzipped); assets -572 B, of which -40 B came
  from a parallel edit to scan_params.json and -565 B from the regenerated
  dexopt baseline profile - neither is part of this cleanup.
- R8 full mode and resource shrinking were already enabled; no keep rule was
  relaxed because no device/AVD is available to re-run instrumentation.

Verification:
- :full-app:compileReleaseKotlin + :app:compileReleaseKotlin: BUILD SUCCESSFUL
- :app:testDebugUnitTest: 137/137 passed
- arm64 release built through scripts/build_distribution.py (optimized=true,
  resourceShrinking=true, owner certificate unchanged)
- NOT run: V200FeedbackTest / EdgeInk*Test / page-by-page capture-export check.
  This host has no AVD (emulator -list-avds is empty), no connected device, and
  no system image; creating a new AVD is out of scope. Device regression is
  still required before delivery.
```

---

## 删除清单（明细，供 review 勾对）

| # | 文件 | 符号 | 行数 | 复核方式 |
|---:|---|---|---:|---|
| 1 | `app/src/main/java/cn/paperforge/app/ui/EditorMoreSheet.kt` | `MoreGroup` | 12 | 词边界引用=1（仅声明）+ after dex 无该符号 |
| 2 | `app/src/main/java/cn/paperforge/app/ui/HomeScreen.kt` | `QuickCard` | 27 | 同上（before dex 4 处 → after 0） |
| 3 | `app/src/main/java/cn/paperforge/app/ui/ToolCapabilityDialog.kt` | `ToolPinButton` | 13 | 同上（before dex 4 处 → after 0） |
| 4 | `app/src/main/java/cn/paperforge/app/ui/PaperDesign.kt` | `screenMs/exitMs/enterMs` | 9 | 引用=0 |
| 5 | `app/src/main/java/cn/paperforge/app/annotation/PageAnnotations.kt` | `HIGHLIGHT` | 3 | 引用=0（大小写敏感） |
| 6 | `app/src/main/java/cn/paperforge/app/data/Database.kt` | `originalReferences` | 3 | 引用=0 + Room schema 未变 |
| 7 | `app/src/main/java/cn/paperforge/app/data/LibraryStorage.kt` | `reclaimable` | 3 | 引用=0 |
| 8 | `app/src/main/java/cn/paperforge/app/model/PageAnnotation.kt` | `isBox`、`annotationFromDrag` | 15 | 引用=0 |
| 9 | `app/src/main/java/cn/paperforge/app/pdf/NativeTextCoverage.kt` | `substantial` | 4 | 引用=0 |
| 10 | `app/src/main/java/cn/paperforge/app/pdf/PdfAssembly.kt` | `targetIndex` | 12 | 引用=0 |
| 11 | `app/src/main/java/cn/paperforge/app/pdf/PdfWatermarks.kt` | `CORNER_BAND`、`pagesWithText` | 2 | 引用=0 |
| 合计 | 11 个文件 | 15 个声明 | 103 行 | 0 行新增 |

## 保留项（不删，供 review 确认）

| 项 | 原因 |
|---|---|
| `assets/templates/LICENSE-python-pptx.txt` | 唯一零引用 assets，但属许可证 |
| `assets/retouch/models.json` | 998 B 来源登记表，无代码读取但有记录价值 |
| `MainViewModel.openOutputInLibrary / extractText / officeResult` | 零调用但对应已记录功能，且文档与 UI 实现不一致，需产品确认 |
| `PdfTools.decrypt` | 零调用但为“去密码另存”能力实现 |
| data class 属性（`sourceLong/requestedLong`、`firstPosition`、`wordBoxes`） | 删改会移动 `componentN`，破坏解构/序列化 |
| `ocr-paddle` 的未用工具函数 | vendored 上游，保持可同步 |
| 25 个 `.so`、全部模型/字体、LO/PDFBox/JLaTeXMath 运行期资产 | 运行期必需或按名加载 |
| 全部 `licenses/` 与 notice 文本 | 规矩禁止删除 |

## 与并行工作的关系

本提交草稿只覆盖上表的 103 行删除。工作区里 `app/src/main/java/cn/paperforge/app/imaging/WorkbookPaperBoundary.kt` 与 `app/src/main/assets/scan_params.json` 的改动来自另一个并行工作（提交 `5e156d2` 及其后续未提交修改），**不在本提交范围内**，体积对比中已单独标注。
