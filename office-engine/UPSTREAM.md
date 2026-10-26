# Office 手机引擎来源

手机模块使用 LibreOfficeKit 26.2.6.3。JNI 包装源码保留原始 MPL-2.0 文件头，来自 LibreOffice 同名 tag 的 `android/Bootstrap/src/org/libreoffice/kit/`。`Office.java` 与 `Document.java` 原样使用；`LibreOfficeKit.java` 的 Java 初始化入口由 Activity 改为 Context，使独立后台服务也能初始化，同一 JNI 方法与原生库保持不变。

运行时取自 F-Droid 构建的 LibreOffice Viewer，各 ABI 的 APK URL、SHA256 与对应源码包写在 `tools/fetch_office_runtime.py` 中。运行时目录不进入 Git；执行脚本即可按固定校验值重建。JNI 库及运行时资源继续遵循其上游许可；相关许可证、字体声明与源码入口随运行时资源保留。

- [LibreOffice 对应源码](https://github.com/LibreOffice/core/tree/libreoffice-26.2.6.3)
- [F-Droid 构建、二进制及对应源码](https://f-droid.org/packages/org.documentfoundation.libreoffice/)
- [MPL-2.0](https://www.mozilla.org/MPL/2.0/)

额外放入纸匠已有的中文字体，保留其现有来源与许可证声明。`OfficeActivity.kt` 是本项目编写的转换入口，负责复制源件、调用原生排版引擎、返回结果 URI。

本项目的 `OfficeRuntime.kt` 统一线程与运行时安装；`OfficeJobService.kt` 为纸匠任务中心提供同签名、独立进程的文件描述符接口，协议见 [后台 Office 任务](../docs/architecture/office-job-protocol.md)。更新主应用后也应安装同一渠道的新引擎 APK。
