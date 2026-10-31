# 扫描质量基准（#7）

这是 #21/#22 改动前后的统一量尺。语料是 `bench-private/synthetic-v1`：82 个确定性合成样例，其中 74 个带真实四角（9 类场景：深色桌面、白色桌面、木桌、杂物、阴影、反光、满幅、相近亮度彩色、OCR 回归等），8 个无文档场景。合成场景只能证明算法方向，真实拍照质量必须用真实样张另行验收。

## 怎么跑

1. 把语料推到设备（图片与 manifest 必须同批）：
   `adb shell run-as cn.paperforge.app mkdir -p files/bench`，再按需写入 `manifest.json` 与图片。
2. 设备侧执行 `QualityBenchmarkTest`：
   `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.paperforge.app.quality.QualityBenchmarkTest -Pandroid.testInstrumentationRunnerArguments.benchMode=current -Pandroid.testInstrumentationRunnerArguments.benchStage=detect`
3. 拉回结果：`adb exec-out run-as cn.paperforge.app cat files/quality-current/results.json > results-current.json`
4. 计算指标：`python scripts/bench/scan_quality_metrics.py results-current.json bench-private/synthetic-v1/manifest.json out/`

## 指标定义

| 指标 | 定义 |
|---|---|
| IoU | 预测四角与真值四角的栅格化交并比（512×512），对应关系取旋转/镜像中的最优匹配 |
| 角误差 | 最优匹配下 4 个角点到真值的平均距离 ÷ 图像对角线 |
| 检测率 | 返回四角的比例。未定位时应用按整幅裁切，`full_frame_fallbacks` 记录该回退数 |
| 误裁率 | **有效裁切**（返回四角，否则整幅）与真值的 IoU < 0.7 的比例 |
| 无文档误检率 | 无文档场景返回四角且置信度 ≥ 0.8 的比例（会自动拍摄门槛） |
| 延迟 | 设备侧 `detect_ms` 的 p50 / p95，模拟器数据，不代表真机 |

## 基线（1.1.0 引擎，`feat/21-capture-geometry` 之前）

数据见 [geometry-before-frame-guard.json](geometry-before-frame-guard.json)。

| 指标 | 值 |
|---|---:|
| 平均 IoU（74 例） | 0.9950 |
| 平均角误差 | 0.132% |
| 检测率 | 100% |
| 误裁率（IoU<0.7） | 0% |
| 无文档误检率 | **62.5%（5/8）** |
| 延迟 p50 / p95 | 284 ms / 437 ms |

无文档场景的 5 次误检置信度均为 0.99、方法为 `auto-neural-edge+refined`，预测框覆盖约 96–99% 画面——是把传感器/letterbox 边框当成纸张轮廓的退化结果。

## 本次（feat/21）改动与结果

改动：新增 `Quad.hugsFrame()` 并在 `LearnedDocumentDetector` 的出参处统一拦截（`frameOnly`）。四角都贴边的满幅框不携带裁切信息，按「未定位」返回：成片仍按整幅裁切，行为等价，但不再冒充已检测文档，也不会触发自动拍摄门槛。

| 指标 | 改动前 | 改动后 |
|---|---:|---:|
| 无文档误检率 | 62.5% | **0%** |
| 有效裁切 IoU | 0.9950 | 0.9906 |
| 角误差 | 0.132% | 0.253% |
| 检测率 | 100% | 77%（17 例满幅回退） |
| 误裁率（IoU<0.7） | 0% | 0% |
| 延迟 p50 / p95 | 284 / 437 ms | 291 / 439 ms |

改动后数据见 [geometry-after-frame-guard.json](geometry-after-frame-guard.json) 与 [rows-after-frame-guard.csv](rows-after-frame-guard.csv)。

代价是满幅类（纸张几乎铺满画面）的样例不再报告四角，回退为整幅裁切，IoU 由 0.99 降到 0.95。这是有意的取舍：整幅裁切只多留一点背景，而伪检测会让空桌面被当成文档、并触发自动拍摄。

## 未验证

- 只有合成语料 + x86_64 模拟器；真实手机拍照、真实光线与纸张纹理未测。
- DocUNet 真实照片只有原图没有真值四角，无法算真实几何误差，只能人工视觉核对。
- #7 要求的淡字、铅笔、反光、透印真实样张尚未提供：这些场景目前用 #22 的合成薄纸测试与既有合成反光/阴影场景覆盖，**真实样张到位后必须补测并更新本页**。
