# 翻译与智能工具

`flow.json`覆盖本地原句查找、自有HTTP协议样本、凭据加密、合法/不存在的页码及独立历史快照；没有调用付费模型。`models.json`是SDK下载后的语言可用性，`model-files.json`记录当次下载文件哈希，权重不提交。

下载完成后关闭Wi-Fi并恢复飞行模式，再单独运行`translation-offline.log`，其真实输出在`translation.json`。

实际UI从既有三页PDF第2页进入，默认关闭；手动开启后本机翻译“ROTATED 00456”，核对原文后回到2/3。强停/覆盖安装后历史仍可读取，最后再次关闭智能工具。原PDF的SHA256与早先`native-slides/independent-readback.json`一致。未把协议样本当作真实外部模型质量验证，也未重复旧功能套件。
