# PaperForge 折纸字标

原创矢量标记，以连续纸面组成 P：上缘为薄荷绿折角，内侧用暖金色区分纸背。保留纸匠的绿色识别，减少通用文件图标的横线与装饰。

- 主色：深青绿 `#102F2C` 到 `#226C5B` 的轻渐变。
- 纸面：暖白 `#F5F5EC`；折面 `#80D8BC`、`#D9B878`。
- Android 前景使用 108 × 108 画布；图形落在中心直径 66 的安全区域内。
- 圆形、自适应圆角和单色图标保持同一轮廓；Android 13+ 提供真正的 monochrome 图层。
- 关于对话框复用同一标记，未引入图像依赖或字体资产。

`paperforge-app-icon.svg` 为静态完整图标，`paperforge-mark.svg` 为透明字标，`icon-mask-preview.svg` 为大小、明暗与形状预览。SVG 与 Android XML 共用路径；PNG 仅用于评审，不作为应用中的位图资源。
