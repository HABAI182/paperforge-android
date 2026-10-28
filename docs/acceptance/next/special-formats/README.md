# 特殊格式样例与结果

所有内容由本项目生成，包含已有许可的应用字体，不含真实邮件或外部论文。

- `android-native-proof.json`：XPS / OXPS / SVG / CBZ / FB2、任务与图片包复用、原件 / 实际结果对照。
- `email-dxf-proof.json`：UTF-8 HTML + CID 图片 + 附件、GB18030 纯文字邮件、实际手机 Draw 图纸转换。
- `caj-latex-proof.json`：完整 / 缺页树 CAJ、KDH、PDF 改名、HN 明确不支持、TeX 图片项目与真实分页。
- `host-native-proof.json`、`host-extra-proof.json`：独立桌面 MuPDF 复读；五种基础格式所有页面与手机页面图像逐像素一致，四类 PDF 容器与原 PDF 逐像素一致；文字 / 矢量 / 图像数量与尺寸同时记录。
- `actual-ui-proof.json`：真实系统文件选择器 → XPS → PDF → 应用原生大页面阅读，用户可见文件名不附随机尾缀。此 UI 记录是 adapter5；最终 adapter6 的追加 CFF 包装适配由集中检查覆盖，未假称该 UI 截图来自另一次运行。
- `instrumentation.log`：最终 adapter6 的三个集中检查，25.801 秒通过。未重复运行旧 OCR、扫描、表单或证书套件。

`large-output-files.json` 保存本地四份大型 CFF 中文 PDF 的真实 SHA256 和长度；它们保留在此本地目录，但不重复提交整套字体到 Git。源文件、公式、正文、输出帧和测试代码均可重现这些结果。较大 CFF 字体仍可能随 PDF 保留，未据 TrueType 样例的缩小宣称全部字体都能同样缩小。

实际运行环境为 API35 x86_64 模拟器。结构检查的 ARM64 签名候选与实体 ARM64 运行性能分开记录，不作为真机兼容性或全部真实文件的证明。
