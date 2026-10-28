# PaperForge 可选联网工具

当前提供手动检查更新、渠道/ABI选择、私有GitHub仓库访问、可取消与恢复的下载、完整APK签名/哈希核验及系统安装入口。默认关闭；不获取扫描文档。它与离线主应用分别安装。

主应用：关于与隐私 → 检查更新。工具自身也有启动图标。配置仓库 `HABAI182/paperforge-android`，若是私有库，在“仓库与访问凭据”填入只对该库 Contents 只读的GitHub fine-grained token并保存。令牌在手机AndroidKeyStore下加密，不随文档备份，也不传入WorkManager参数。

开发：`:network-engine:assembleDebug`；`-PpaperforgeAbis=arm64-v8a`限制构建ABI。使用与主应用相同的外部签名身份。`scripts/build_distribution.py --include-engines`同时收录此工具。源码与完整许可见UPSTREAM.json、src/main/assets/licenses/。基础依赖与主项目使用同一组固定版本。

集中证据见 `docs/acceptance/next/updates-summary.json`。局域网配对和翻译/AI仍独立推进，不显示空入口。
