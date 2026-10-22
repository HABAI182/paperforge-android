# 发布检查

本次交付为 0.1.0 首轮调试安装包，不上传商店、不创建远程仓库、不发布到任何公开渠道。

- [x] `assembleDebug` 真实生成可安装 APK。
- [x] `testDebugUnitTest` 通过几何、稳定捕获、页码与路径测试（8 项）。
- [x] `lintDebug` 完成，0 errors、11 warnings；原报告保留。
- [x] `connectedDebugAndroidTest` 在 API35 AOSP x86_64 模拟器执行（7 项）。
- [x] 安装、首开、飞行模式与无 GMS 环境验证。
- [x] 实际操作相机/权限、编辑、PDF 预览与系统另存/分享路径。
- [x] 拉取生成 PDF，用 pypdf/Poppler 验证页数与显示。
- [x] 确认最终 Manifest 不含 INTERNET 或全盘存储权限。
- [x] 版本号、min/target SDK、ABI、签名验证、APK SHA-256 在 acceptance/summary.json；源码 commit 在交付目录发布说明。
- [x] 源码打包脚本排除 SDK、构建缓存、local.properties 和签名密钥；交付时检查 ZIP 条目。
- [x] 记录未验证真机、ROM、光照和性能，而不把模拟器结果等同真机。

正式发布后续：使用所有者自己的签名密钥、对目标 ROM/ARM64 实机回归、检查依赖升级与 native 库页大小兼容、长任务/低内存、备份恢复、无障碍大字体、外部阅读器组合。测试不通过的能力明确限制。
