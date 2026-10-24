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

data class OcrOptions(val mode: OcrMode = OcrMode.BALANCED, val pageAgreement: Boolean = true) {
    companion object {
        fun current() =
            OcrOptions(
                OcrMode.parse(UserSettings.string("ocrMode", "BALANCED")),
                UserSettings.bool("ocrVoting", true),
            )
    }
}
