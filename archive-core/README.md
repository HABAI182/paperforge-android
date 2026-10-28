# 手机 PDF/A 校验内核

可选 `PaperForge 格式引擎` 使用此库；主包不增加网络权限或桌面 Java 运行时依赖。固定 veraPDF 1.30.3，选择 MPL-2.0。原始来源、移植类和公开 JAR 指纹见 `UPSTREAM.json`，随包许可见 `src/main/assets/archive/`。

保留上游全部规则。`AndroidProfiles` 用 DOM 读取未修改的内置规则 XML，通过公开模型构造器创建配置；11个目标等级与官方 JAXB 对象逐值相等。两个颜色类通过 Android 的 sRGB / D50 XYZ 转换去掉 AWT；规则求值使用 Rhino 解释器；SecureXML 显式使用 Xerces 2.12.2，保留上游解析要求。只补充 Android 启动类路径缺少的 XML API 接口，不替换系统已有类。

Gradle 从公开 Maven 版本生成本地兼容 JAR，排除四个由移植源码提供的类，并消除 JAXB 的重复类；构建产物不入库。`UPSTREAM.json` 的兼容 JAR 指纹是本次 Windows 构建观察值，原始 Maven 版本和源码 JAR 指纹单独记录。

完整证据与限制见 `docs/acceptance/next/pdf-archive-summary.json`。PDF/A 结果不等于证书签名有效性；App 提供独立证书核验入口。静态兼容副本明确使用页面图像，只有校验通过才标记归档成功。
