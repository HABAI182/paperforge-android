# #4 · HTTPS 局域网互传

使用 AndroidKeyStore 的不可导出 ECDSA P-256 身份与平台 TLS 1.2/1.3。首次配对按手机及浏览器证书 SHA-256 指纹确认身份；二维码、八位配对码、队列、SHA 校验、暂停/续传与下载 Range 仍可用。原有 HTTP 配对首次升级撤销，副本文件保留。

会话 Cookie 为 Secure / HttpOnly / SameSite=Strict；服务端检查 30 天有效期。认证 API 绑定当前服务 epoch、一次性随机请求标识和时间窗；拒绝跨来源请求、过期及重复请求。连接关闭/握手拒绝均释放连接记录。

界面明确提示 HTTPS、首次信任步骤与配对有效期。浏览器必须查看真实证书详情再与手机核对，不能只相信页面显示。没有公共 CA 的局域网证书不能自动获得浏览器信任；这项人工身份确认是协议的一部分，详见 [协议与信任边界](../../../development/LAN-SECURITY.md)。

## 验证

- API35 x86_64 完整 release 构建及 lintVital 通过。
- 三项集中设备检查 **OK (3 tests)，4.986 秒**，见 [test.log](test.log)。复用原传输及随包浏览器测试源码，同时在完整包身份运行。
- [tls-security.json](tls-security.json)：实际 TLSv1.3；精确证书绑定拒绝错误身份；错误配对摘要、已用票据、API 重放、旧服务 epoch、过期请求、跨来源请求、过期与撤销会话均拒绝。
- TCP 代理记录实际 TLS 字节，确认文件名、Cookie、JSON 请求头与文件正文没有以明文出现；翻转加密记录导致连接失败；原 HTTP 请求不能取得 HTTP 响应。
- [flow.json](flow.json)：上传在 311296 字节暂停，服务重开后从断点继续；SHA-256 一致，下载 Range 拼接一致，原文件不变；撤销立即失效。
- [browser.json](browser.json) / [browser.png](browser.png)：实际 Android WebView 执行随包 HTTPS HTML/JS，精确核对证书后确认配对，发送 400003 字节二进制样本并验证 SHA。

Android 平台的 TLS 引擎证书回调与 KeyStore 签名限制已通过实际连接验证；实现采用 ECDSA，避免 RSA 原始私钥运算在部分 KeyStore/Conscrypt 组合上的不兼容。

测试只使用生成样本，不公开配对码、会话凭据或私钥。这里验证传输链路，不宣称受控终端安全，也不替代首次证书核对。
