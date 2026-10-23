# 发布检查

0.5.0使用固定官方MNN3.6.1源码与两份恢复模型，随包来源不等于模型开源许可。只验证本轮扫描链路，详细结果见acceptance/v050。发布流程继续生成同证书通用/ARM64/x86_64包、源码ZIP、报告及SHA256；逐附件回读后公开新预发布。新v0.5.0 tag保留旧版本不变，最终以上传目录release-0.5.0-verification.json为准。

0.4.0新增定位模型与ONNX Runtime：完整许可/固定来源/模型及AAR哈希保存在assets。通用、ARM64、x86_64分别检查签名、权限和16KB结构对齐，source.zip按原始Git blob验证；新tag和预发布不覆盖历史。最终Release上传结果以交付目录release-0.4.0-verification.json为准。

## 0.3.0 当前发布

使用公开仓库，保留v0.1.0/v0.2.0；新增v0.3.0预发布。用户已授权GitHub推送、tag和release。通用/ARM64/x86_64开发签名APK、原始Git blob源码ZIP、质量报告和SHA256作为独立附件；发布脚本先创建草稿、逐附件回读哈希，再公开预发布。最终上传结果以交付目录release-0.3.0-verification.json为准。

构建/必要检查、无网络权限、同原开发证书、ZIP/ELF16KB对齐均要求完成。APK结构不代表ARM64手机实际验收。源码ZIP排除SDK、构建目录、本机配置与密钥，每个文件按Git blob验证。开发签名作为个人预发布用途。

以下为历史发布记录。

本次交付为 0.2.0 调试安装包，按用户授权推送到私有 GitHub 仓库并发布 tag/预发布。0.1.0 tag 和附件保留。安装包、源码 ZIP 与校验清单均在发布后回读验证 SHA-256；发布确认报告放在交付目录。

0.2.0：构建通过，8 项本地单测 + 3 项集中设备检查通过，Lint 0 errors / 19 warnings；实际 UI 和独立 PDF 验证见 TEST_MATRIX。沿用 0.1.0 调试签名，可直接更新安装。

以下为 0.1.0 历史发布检查：

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
