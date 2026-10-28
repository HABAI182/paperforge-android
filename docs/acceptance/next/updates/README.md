# 可选更新工具

SET04。独立 `cn.paperforge.network` 安装包，主应用保留无网络权限。关于页“检查更新”在同签名工具已安装时打开工具，否则打开项目发布页。默认关闭，只在手动检查和已提交的下载中联网；不自动安装。已核对开启/关闭在立即结束进程后仍能正确保留，凭据写入加密存储并持久提交。

## 行为

- 正式版 / 含预发布 / 含开发版三个渠道；逐页读取 GitHub Releases，不把仅有 tag 的提交当作版本。按照发布清单的包名、versionCode、ABI筛选；旧版无清单的附件使用版本比较以及资产 digest 或 SHA256SUMS。旧版规则的最终版本和包名仍在下载后核对。
- 检查错误、没有发布、缺少可核对附件和没有更高适配版本分别显示。未配置私有仓库令牌得到明确错误，不显示“已经最新”。
- Room/WorkManager自身负责队列，下载意图在应用私有JSON中保留。网络约束等待、暂停保存 `.part`、HTTP Range续传；服务器不支持范围时重新完整下载。每个SHA工作文件只有一个活动写入者。
- 下载后核对完整字节数与SHA256，使用 AOSP apksig 真正核验 APK 签名，再比对纸匠已安装证书、包名、版本及实际native ABI。不能只读取未校验的证书字段。通过后交给Android系统安装界面，仍需系统安装来源授权及用户动作。
- GitHub token 使用 AndroidKeyStore AES-GCM 加密，仅在 `api.github.com` 的HTTPS请求中发送，不随附件重定向发往其他主机。私有库只需指定仓库的 Contents 只读权限。没有凭据、文档内容或账号被写入验收材料。

按 [GitHub Releases API](https://docs.github.com/en/rest/releases/releases#list-releases) 与 [Release assets API](https://docs.github.com/en/rest/releases/assets#get-a-release-asset) 接口实现私有附件下载，包括直接返回和重定向两种响应。AOSP apksig固定依赖8.13.2；来源、JAR哈希和完整Apache许可在network-engine/UPSTREAM.json及assets/licenses/。

## 实际证据

API35 x86_64的两个集中检查共2.174秒通过。验证了预发布高于开发安装版时不会漏选、错误ABI排除、HTTP404不会变成没有更新。2,105,759字节的自制签名APK先中断，再从311,296字节续传；实际完整签名验证通过，同一个SHA外层合法但签名后改动的APK被拒绝。测试APK没有安装，证书是现有机主开发证书，未生成新的测试私钥。

主应用关于页实际打开工具；继承深色与125%字号，仓库/凭据收起，检查入口在首屏。未配置令牌的私有仓库实际返回权限提示，截图见check-failure.png；关闭后强制停止再读取仍为关闭。只用本地可控服务检查下载和范围恢复，没有把模拟器网络性能当作实体手机结论。

源码发布状态仍保持暂停，当前没有新tag/Release。后续局域网配对与翻译/AI属于EXT02/EXT03，不以更新工具代替这两项。
