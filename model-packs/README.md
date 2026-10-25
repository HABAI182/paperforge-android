# 可选离线 OCR 模型包

在手机的 **设置 → 离线 OCR 与模型包 → 导入模型包** 中选择 `.pfocr.zip`，再点“使用此模型”。无需电脑运行服务。基础 APK 的默认识别仍为内置 ML Kit；切换后新建 OCR/可搜索 PDF 任务使用所选模型，已经排队的任务保留原配置。失败任务的“按当前识别设置重试”会重新绑定当前配置。

本目录在私有仓库中，可用已登录的浏览器下载。模型包不随基础 APK 安装，也不需要应用联网；导入后完全本地运行。容量管理将它记为“离线 OCR 模型”，清理缓存不会移除它。完整文档备份保留文字、版本及设置；模型权重需单独保留或重新导入。同一版本可重复导入，文件 SHA256 在导入时核对。

| 包 | 识别语言 | ZIP | 安装文件 |
| --- | --- | --- | --- |
| [PaddleOCR v5 Mobile](paddle-v5-mobile.pfocr.zip) | 中文、英文 | 18,640,263 bytes | 约 20.5 MiB |

ZIP SHA256：`9ae49c20abd9b0678932a62789ae73f44191d1b7af6a6855b8b0540665bbe6f9`。模型、字典、预处理和来源绑定；安装 ID 后缀来自内容指纹。同一型号的不同权重可并存。移除选中模型会切回内置识别，等待正在使用的识别释放资源，不删除文档原件或历史文字。

## 来源和适配

- [PaddleOCR 官方 Android SDK，固定提交 dab3fe3](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk)，源码及 Apache-2.0 在 `ocr-paddle/`；模型和配置从私有文件读取。
- [官方 v5 Mobile 检测 ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/tree/e6f4fa85f00e168c862bc462aebca69eef9b3d3d)。
- [官方 v5 Mobile 识别 ONNX 与字典](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/tree/ed152b8b495f84de93cda5709d768548a9127622)。
- 包内 `manifest.json`/`provenance.json` 包含原始链接、固定版本、大小和哈希，附完整 Apache-2.0 许可。构建工具：`scripts/build_ocr_model_pack.py`。

v5 识别 YAML 指定 BGR 通道，适配按配置执行；原生检测和逐行识别共用已有 OCR 资源门，允许交互请求在批次间优先。取消在原生调用边界生效。

## 已验证的范围

同一组中英/编号合成页和一张公开实拍路牌裁区比较见 [验收记录](../docs/acceptance/next/paddle-ocr-summary.json)。这里只证明一个实拍样本有收益，不代表所有扫描/手写/竖排更好。Paddle 当前为单路行级识别，不能提供的词框不伪造；语言由包内字典决定。
