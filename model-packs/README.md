# 可选离线 OCR 模型包

在手机的 **设置 → 离线 OCR 与模型包 → 导入模型包** 中选择 `.pfocr.zip`，再点“使用此模型”。手机自行下载和导入即可，无需电脑服务。基础 APK 默认仍是内置 ML Kit；新建 OCR / 可搜索 PDF 使用当前模型，已排队任务固定模型指纹、识别模式与阅读顺序。失败任务可按当前配置重试。

私有仓库需在已登录浏览器下载；应用本身不联网。模型及导出字体独立安装，清理缓存不会移除。完整备份保留文字及配置，权重需另行保留或重导入。相同指纹重导入可刷新名称和语言说明，不修改已有模型字节；不同权重/字体版本可并存。移除当前模型会切回内置识别，并等待运行中的识别释放资源。

| 包 | 语言说明 | ZIP | 安装文件约 |
| --- | --- | --- | --- |
| [PaddleOCR v5 Mobile](paddle-v5-mobile.pfocr.zip) | 中英、繁体、日文 | 18,640,958 bytes | 20.5 MiB |
| [PaddleOCR v5 Korean](paddle-v5-korean.pfocr.zip) | 韩文、英文 | 15,914,303 bytes | 23.4 MiB |
| [PaddleOCR v5 Devanagari](paddle-v5-devanagari.pfocr.zip) | 印地语、马拉地语、尼泊尔语、梵语、英文 | 11,883,557 bytes | 12.4 MiB |
| [PaddleOCR v5 Latin](paddle-v5-latin.pfocr.zip) | 英语、法德西葡意荷土波捷瑞典语 | 12,168,412 bytes | 12.9 MiB |

ZIP SHA256：

- `paddle-v5-mobile.pfocr.zip`：`c04c20edf5a2dc478eb6a2319a22ce71b7a19938705947bdf18394c370dd37bc`
- `paddle-v5-korean.pfocr.zip`：`435085d2ed604694701400e7e5ce689ac5ba19651e720b781ab269fd312d2ee2`
- `paddle-v5-devanagari.pfocr.zip`：`ba959f39f1f9c8516312f1dc85ebc4b43ab9a2d7af5623a89505bc761e957af3`
- `paddle-v5-latin.pfocr.zip`：`14adf2d1cbf21b6e2651f0a1b661491f6aeb687f5ca3320dbabe69011e944ba0`

## 阅读顺序与字体

设置 → 查找 `排序` / `order`，可选自动、逐行、多栏左至右/右至左、竖排右至左/左至右。阅读器的文字选择和重排也可调整顺序。自动竖排以 CJK 文字及实际行框判断，中文/日文通常从右至左；栏间阅读流按实际几何分组，通栏标题/页脚保留在对应位置。自动判断仍可用手动顺序覆盖。

韩文、天城文和 Latin 包携带对应的静态 TrueType 字体；可搜索 PDF 使用固定模型包的字体补缺，保留 Unicode 复制与原页外观。字体与模型一起计入指纹，不增加基础 APK。当前范围是 OCR 隐形文字层；通用 TXT → PDF 的复杂脚本排印及任意混合字体排版属于另一个能力边界。

语言列表描述固定包字典的能力，不表示各语言都已经过实拍质量评估。此 Latin ONNX 字典缺少越南语的部分字符，未列为支持；不能用上游整个 OCR 产品线的语言列表代替这份固定字典。

## 来源和构建

- [官方 Android SDK，固定 dab3fe3](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk)，源码和 Apache-2.0 在 `ocr-paddle/`。
- [官方 v5 Mobile 检测 ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/tree/e6f4fa85f00e168c862bc462aebca69eef9b3d3d)。
- [中英日识别](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/tree/ed152b8b495f84de93cda5709d768548a9127622)、[韩文](https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/tree/5c6f574b8e2230adf4287b33e736d71b9fabd28e)、[天城文](https://huggingface.co/PaddlePaddle/devanagari_PP-OCRv5_mobile_rec_onnx/tree/251aec19e36739540d35e2cc943f6aa7503b98e5)、[Latin](https://huggingface.co/PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx/tree/89d3a50e2c27e2e7cceeab0e944c25c807d5db4f)。
- 韩文、天城文、Latin 导出字体分别来自 Google Fonts 的 Noto Sans KR / Noto Sans Devanagari / Noto Sans，固定提交和原文件/衍生文件 SHA 在包内 `provenance.json`；保留 SIL OFL，静态化并重命名为 PaperForge 字体。
- 包含 `manifest.json` / `provenance.json`、完整 Apache-2.0，以及适用的 `font-LICENSE`。各文件核对 SHA256 和长度后导入。
- `scripts/prepare_ocr_pack_font.py` 从指定上游 TTF 生成静态字体；`scripts/build_ocr_model_pack.py` 接收 `--id` / `--name` / `--version` / `--languages`，对相同输入生成固定时间戳、固定顺序 ZIP。

YAML 指定 BGR 与字典，适配按配置执行；检测和逐行识别共用 OCR 资源门，取消在原生调用边界生效。Paddle 当前采用单路行级识别，不提供的词框不伪造，ML Kit 的多次采样控件只在内置引擎下显示。

## 实际验证

先前中英及一张实拍路牌的有限对比见 [第二引擎记录](../docs/acceptance/next/paddle-ocr-summary.json)。竖排、三栏顺序、日/韩/印地/法/捷样本、固定任务配置、PDF 搜索/复制和独立 MuPDF 外观复读见 [脚本与顺序记录](../docs/acceptance/next/ocr-writing-summary.json)。清晰排印样本不能代表手写、任意实拍或全部字典字符准确率；竖排样本中的「紙→纸」等真实识别差异保留在记录中。
