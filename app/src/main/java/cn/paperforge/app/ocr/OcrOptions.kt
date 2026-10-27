package cn.paperforge.app.ocr

import cn.paperforge.app.UserSettings

enum class OcrMode(val label: String, val edge: Int) {
    FAST("快速", 2200),
    BALANCED("均衡", 3200),
    DETAILED("精细", 4000);

    companion object {
        fun parse(value: String) = entries.firstOrNull { it.name == value } ?: BALANCED
    }
}

enum class OcrOrder(val label: String) {
    AUTO("自动顺序"),
    ROWS("按行顺序"),
    COLUMNS_LTR("多栏从左至右"),
    COLUMNS_RTL("多栏从右至左"),
    VERTICAL_RTL("竖排从右至左"),
    VERTICAL_LTR("竖排从左至右");

    companion object {
        fun parse(value: String) = entries.firstOrNull { it.name == value } ?: AUTO
    }
}

data class OcrOptions(
    val mode: OcrMode = OcrMode.BALANCED,
    val pageAgreement: Boolean = true,
    val order: OcrOrder = OcrOrder.AUTO,
) {
    companion object {
        fun current() =
            OcrOptions(
                OcrMode.parse(UserSettings.string("ocrMode", "BALANCED")),
                UserSettings.bool("ocrVoting", true),
                OcrOrder.parse(UserSettings.string("ocrOrder", "AUTO")),
            )
    }
}
