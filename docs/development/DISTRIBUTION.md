# 构建、签名与手机交付

当前只生成开发候选包。`distribution-policy.json` 的 `releaseHold` 保持开启；构建脚本和 CI 都没有创建 tag / Release 的步骤。现有发布继续保留，版本号仍为内部 `0.6.1-dev` / code 7，等完整计划完成后再统一确定大版本。

## 手机自行获取候选包

在已登录 GitHub 的手机浏览器打开私有仓库，进入 **Actions → Owner signed phone candidate → Run workflow**，分支选 `main`。普通现代手机选 `arm64-v8a`，模拟器选 `x86_64`，需要兼容两者时选 `universal`。一般使用 `development` 通道；可选同时构建 Office / 格式引擎。完成后在该次运行的 Artifacts 下载 ZIP，解压并安装需要的 APK。

主应用与可选引擎必须使用同一证书，签名权限才能正常通信。候选包按已有所有者证书核验，沿用同一应用 ID，可覆盖安装；独立模型包仍从 `model-packs/` 下载后在手机内导入。只安装主应用也可离线扫描、OCR、阅读和普通 PDF 处理。

自动提交 CI 名称是 **Android compile and isolated candidate**。它编译 `cn.paperforge.app.ci` 隔离包及格式引擎源码，使用临时开发证书；这个产物用于编译核对，不是主应用的覆盖更新包。主应用更新使用上面的 owner signed 工作流。

## 现有身份

历史开发包及当前候选使用的证书 SHA256：

`d5232e43c2cea64793d92c0b57abea1716577caac320523c30bc27f2c8f8f34e`

本地私钥在仓库外，`.jks` / `.keystore` 排除在 Git 与源码包外。Gradle 读取 `PAPERFORGE_SIGNING_STORE`、`PAPERFORGE_STORE_PASSWORD`、`PAPERFORGE_KEY_ALIAS`、`PAPERFORGE_KEY_PASSWORD`；不把值写进项目配置、命令行参数或报告。根构建配置把同一外部身份应用于三个 APK。

签名工作流只允许 `main` 的手动运行，仓库 Secrets 分别保存 `PAPERFORGE_SIGNING_STORE_BASE64` 和上述三个口令/别名。密钥在临时 runner 目录还原，不进入检出目录、日志或上传产物。CI token 只有 `contents: read`；PR 编译工作流不读取这些 Secrets。

为当前单人私有产品，继续现有证书是默认交付路线。“稳定版”表示功能通道，不需要为名称更换证书。若未来所有者决定更换身份：先将完整备份保存到应用外并核对可读性，再安排明确的卸载/重新安装和恢复；旧 Android 版本不能假定支持签名轮换。主包与两个引擎必须一起迁移。该破坏性迁移没有自动执行，本轮只验证同证书覆盖更新。

## 本地构建

设置 Java17、Android SDK、外部签名环境后运行：

```text
python scripts/build_distribution.py --abi arm64-v8a --channel development --include-engines
python scripts/build_distribution.py --abi x86_64 --channel development
python scripts/build_distribution.py --abi universal --channel development
```

`--include-engines` 先按固定下载地址及 SHA256 准备所需 ABI 的手机 Office 运行时，再编译两个可选引擎；未选中时不下载。输入 APK/模型均在手机上运行，不依赖电脑服务。`--isolated-ci` 只编译隔离调试包，不使用所有者私钥。

默认产物在 `build/distributions/<channel>/<abi>/`。脚本读取真实 APK，核验包名、版本、架构、签名和 ZIP 16 KiB 对齐；另外解析每个 ELF 的 PT_LOAD 对齐并单独记录，不能用 ZIP 对齐替代 ELF 结论。主包没有 INTERNET 权限。输出：

- 对应架构的主 APK，以及明确选中的可选引擎 APK。
- `release-manifest.json`：提交、工作区是否含未提交修改、通道、版本、真实文件哈希/大小、证书、ABI、权限、native ELF 和最大体积项。
- `SHA256SUMS.txt`：每个 APK 的实际 SHA256。

通道为 development / prerelease / stable。未发布候选保持 `candidateOnly: true`；稳定通道不能把含预发布后缀的版本名伪装成正式版本。正式发布时版本 code 要递增，附件、manifest、标签和源码提交需统一，仍以完整计划验收后的发布工作为准。

## 包体边界

按 ABI 交付去掉另一架构的全部 native 库；R8 和资源收缩用于可安装的 Release 候选。CPU 扫描保留当前实际算子，MNN 的 GPU、转换器、示例和工具未加入应用；没有为体积盲删识别所需算子。离线中文/拉丁 ML Kit、扫描模型和基础 CJK 字体继续随主包安装，保证无网络首开可用；Paddle 权重、韩文/天城文/Latin 导出字体、Office 与格式引擎独立按需安装。

OpenCV 4.12.0 改为固定源码与 SHA256 构建，仅保留实际使用的 core / imgproc / imgcodecs / photo / Java；JPEG、PNG、WebP、去阴影修复和基线 NEON 保留。官方 Java API JAR 未修改，来源与模块边界见 `opencv-mobile/UPSTREAM.json` 和 README。关闭可选 KleidiCV/ADE 下载，避免网络成功与否改变构建。ORT 继续保留现有 CPU 算子；更细裁剪可作为后续改进。

同为 ARM64 Release 的主包由 99,720,804 降到 84,434,456 字节，减少 15.33%；这是原生模块精简的单独对比。此前通用 Release 的 203,088,347 字节与 ARM64 的 99,720,804 字节用于说明 ABI 去重，分别记录。七种增强独立逐像素一致、两路四角与实际 Paddle 输出相同，证据见 `docs/acceptance/next/distribution-summary.json`。ARM64 APK 的结构核验与 ARM64 实体手机体验分别记录。

CI 固定的 Actions 提交来自各自官方仓库，版本核对日期 2026-10-27；检查 APK 固定使用 build-tools 35.0.0，并保留公开证书报告。工作流默认只编译，不重复执行整套设备测试。必要的功能验证由改动本身决定。
