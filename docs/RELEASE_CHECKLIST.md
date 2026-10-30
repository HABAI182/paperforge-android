# 发布检查

## 1.1.0 完整功能版

所有者要求直接以 1.1.0 上传并要求高效完成，本轮不重复运行功能测试。`releaseHold=false`，正式交付为`PaperForge-1.1.0-full-arm64-v8a.apk`，沿用原应用ID与证书，版本码10103，单ARM64。只核对版本、签名、ABI、ZIP/ELF 16KiB对齐与四个附件digest，记录干净源提交、SHA256与文件字节数。发布正文归档见[1.1.0说明](releases/1.1.0.md)，实证见[发布实证](acceptance/next/v110-release-summary.json)，能力与剩余设备验收见[1.1.0状态](quality/V1.1.0-STATUS.md)，安装见[INSTALL](INSTALL.md)。

## 1.0.1 完整功能版

所有者已接受当前范围并授权发布单APK，`releaseHold=false`。正式交付为`PaperForge-1.0.0-full-arm64-v8a.apk`，沿用原应用ID与证书；Office、特殊格式与联网工具均内置，桌面只保留一个图标。当前完整包版本码10004。私有/公开是仓库可见性，稳定/预发布是发行渠道，两者分别管理。

发布前检查真实版本、单ABI、所有者证书、ZIP/ELF16KiB对齐；记录干净源提交、SHA256、文件字节数。UI仅针对本次变化检查分类、返回、搜索、设置与分享入口；既有原生转换证据保留版本标记。发布后核对tag/source、三个附件及服务器digest，更新README、CHANGELOG、安装说明与状态文档。能力和剩余设备验收见[1.0.1状态](quality/V1.0.1-STATUS.md)，安装见[INSTALL](INSTALL.md)。构建命令见[DISTRIBUTION](development/DISTRIBUTION.md)。

## 历史开发约定（已被1.0.0授权取代）

统一大版本前曾暂停新增tag/Release。以下内容为历史交付记录，不覆盖上面的当前发行约定。

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
