# 测试与真实验收状态

日期：2026-10-23（Asia/Shanghai）。本轮验证将构建、本地单测、模拟器、独立 PDF 引擎和真实手机分开记录。

## 0.2.0 增量验证

- `assembleDebug` 已构建；`testDebugUnitTest` 8 项通过，定向 `UpgradeSmokeTest` 3 项通过，Lint 0 errors / 19 warnings。原始 XML、日志及 APK 哈希见 `acceptance/v020/`。
- 弱对比透视纸张自动识边，中英混排 OCR，PDF 嵌入字体后中文提取，0/90/180/270° 页面搜索位置回归通过。
- 中文批注、笔迹、脱敏后无原文字/作者/表单、AES-256 无密码拒绝/正确密码开启、权限标记、中文水印、拼版、图片 ZIP、中文 AcroForm、PDF 编排、持久撤销与备份追加恢复通过。
- 实际 UI 通过系统选择器导入弱对比图片，未调整四角即自动裁切，点击 OCR 显示中英文结果；见 `screenshots/v020-*.png`。识别仍有同形字错误，不宣称准确率。
- 0.1.0 模拟器数据升级回读与独立 PDF 检查另见同目录 JSON；真机/国内 ROM/真实纸张照片质量仍未验证。

以下为 0.1.0 保留的基线记录，7 项设备测试不计入本次 3 项增量结果。

## 环境

- Windows 主机，JDK 17.0.20.1，官方 Gradle wrapper 8.13 / AGP 8.13.2。
- Android SDK compile/target 36，Build Tools 35.0.0；安装包 minSdk 26。
- Android Emulator `paperforge_api35`，Android 15 / API35 / x86_64，AOSP default 镜像，不带 GMS；WHPX 加速。
- 进入应用首次使用 OCR 前启用飞行模式，关闭 Wi-Fi 和移动数据。Manifest 不含 INTERNET / ACCESS_NETWORK_STATE / 全盘存储权限。

## 已执行

| 层级 | 检查 | 结论 / 证据 |
|---|---|---|
| 构建 | `gradlew.bat assembleDebug` | 已生成签名调试 APK；见 acceptance/apk-manifest.txt、apk-signature.txt |
| 本地单测 | `testDebugUnitTest` | 8 项：角点排序/非法几何/序列化/捕获稳定与防重复/页码/文件名；见 acceptance/unit-tests.xml |
| Android 测试 | `connectedDebugAndroidTest` | 7 项：主页工具隐私、PDF 渲染滚动跳页、十页草稿与排序旋转导出、五滤镜、无 GMS 中英文 OCR、旋转后原生文本、OCR 取消重试；见 acceptance/device-tests.xml |
| PDF 登记回归 | 十页测试中重复登记同一路径 | 返回同一文档 id，引用数不额外增长 |
| Lint | `lintDebug` | 0 errors；警告保留在 acceptance/lint-results-debug.txt，不以 baseline 隐藏 |
| 人工操作模拟器 | 拒绝相机权限 → 再次授权 → 预览 → 快门 → 完成 | 成功；相机输入为模拟器测试画面，不是真实纸张 |
| 人工操作模拟器 | 拖动四角 → 应用 → WorkManager 导出 → 预览 | 成功；见 screenshots/04–08 |
| 系统文件访问 | ACTION_CREATE_DOCUMENT 保存到 Downloads | 成功；独立读回与私有输出逐字节一致，见 saf-export-check.json |
| 系统分享 | FileProvider → Android Share Sheet | 成功显示 PDF 名称及可选目标；未向任何联系人发送 |
| 独立 PDF 验证 | pypdf strict 解析 10/3/13 页与旋转文本 PDF | 页数、页尺寸、旋转、文本与顺序正确，见 pdf-independent-check.json |
| 独立渲染 | Poppler pdfinfo / pdftoppm + 目视 | 检查十页文档首尾与原生旋转样本；所查页面文字可读、中文无方块、比例保持 |

最终命令、计数、APK 校验值和收集时间见 acceptance/summary.json。构建期间发现并修复了初始 Kotlin 类型/图标引用、AndroidTest BOM、JUnit 返回类型、跳页文字点击区、旋转文字提取顺序及 Windows SDK 路径转义问题。早期失败不作为通过记录。

## Lint 警告解释

锁定版本的 Gradle/OpenCV/Coroutines 有更新可用；若干 KTX 风格建议未影响运行。PdfBox 传递的 Bouncy Castle PKI 工件有 TrustAllX509TrustManager 静态警告；应用没有网络权限、也不调用其 TLS 客户端。保留原始报告供后续依赖维护，不通过关闭全局检查隐藏它。

## 未验证或待补齐

- **没有连接真实 Android 手机**；ARM64、国内 ROM、Android 8/其他 API 的运行验收仍待做。
- 真实 A4 十页、阴影/低光/反光/铅笔、多机型拍摄、角点识别质量和相机 FOV/旋转对齐，不用模拟器结果替代。
- 自动捕获逻辑有单测；用真实纸张验证抖动、换页、防重复和自动曝光仍待做。
- 100 页压力、低内存、强杀中途导出、设备重启、DocumentProvider 写入失败、权限撤回、大字体与全部无障碍路线仍未完成完整矩阵。
- OCR 测试使用自制高对比中文/英文样本，未计算真实资料 CER/WER，不宣称准确率或优于竞品。
- 压缩的多文件质量/体积回归、复杂表单/签名/加密文件兼容、外部 Adobe/Chrome/系统阅读器组合待扩展；没有宣称全部 PDF 对象无损保留。
- 外部查看使用独立桌面解析/渲染引擎；尚未在真实手机的第三方 PDF 阅读器中验收。
