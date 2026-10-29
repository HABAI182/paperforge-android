package cn.paperforge.app.ocr

import android.graphics.Bitmap
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings
import cn.paperforge.app.data.PageRow
import org.json.JSONObject

data class OcrRequest(val engineId: String, val chinese: Boolean, val options: OcrOptions) {
    val signature
        get() = signature(engineId, chinese, options)

    fun json() =
        JSONObject()
            .put("engine", engineId)
            .put("chinese", chinese)
            .put("mode", options.mode.name)
            .put("voting", options.pageAgreement)
            .put("order", options.order.name)

    fun bind(router: OcrEngine): OcrEngine {
        val base = router.snapshot(engineId)
        return object : OcrEngine {
            override val id = base.id
            override val fontFiles
                get() = base.fontFiles

            override fun signature(chinese: Boolean, options: OcrOptions) =
                this@OcrRequest.signature

            override suspend fun layout(page: PageRow, chinese: Boolean, options: OcrOptions) =
                base.layout(page, this@OcrRequest.chinese, this@OcrRequest.options)

            override suspend fun recognizeBitmap(
                source: Bitmap,
                chinese: Boolean,
                options: OcrOptions,
            ) = base.recognizeBitmap(source, this@OcrRequest.chinese, this@OcrRequest.options)
        }
    }

    companion object {
        fun signature(engineId: String, chinese: Boolean, options: OcrOptions) =
            "$engineId|$chinese|${options.mode.name}|${options.pageAgreement}|${options.order.name}|${OcrReadingOrder.VERSION}"

        fun current(
            engineId: String,
            chinese: Boolean = UserSettings[SettingKeys.OcrLanguage] != "LATIN",
        ) = OcrRequest(engineId, chinese, OcrOptions.current())

        fun decode(
            json: JSONObject?,
            engineId: String,
            chinese: Boolean = UserSettings[SettingKeys.OcrLanguage] != "LATIN",
        ) =
            if (json == null) current(engineId, chinese)
            else
                OcrRequest(
                    json.optString("engine", engineId),
                    json.optBoolean("chinese", chinese),
                    OcrOptions(
                        OcrMode.parse(json.optString("mode", "BALANCED")),
                        json.optBoolean("voting", true),
                        OcrOrder.parse(json.optString("order", "AUTO")),
                    ),
                )
    }
}
