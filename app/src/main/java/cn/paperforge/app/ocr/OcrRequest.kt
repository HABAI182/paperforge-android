package cn.paperforge.app.ocr

import android.graphics.Bitmap
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

    fun bind(router: OcrEngine): OcrEngine {
        val base = router.snapshot(engineId)
        return object : OcrEngine {
            override val id = base.id

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
            "$engineId|$chinese|${options.mode.name}|${options.pageAgreement}"

        fun current(
            engineId: String,
            chinese: Boolean = UserSettings.string("ocrLanguage", "CHINESE") != "LATIN",
        ) = OcrRequest(engineId, chinese, OcrOptions.current())

        fun decode(
            json: JSONObject?,
            engineId: String,
            chinese: Boolean = UserSettings.string("ocrLanguage", "CHINESE") != "LATIN",
        ) =
            if (json == null) current(engineId, chinese)
            else
                OcrRequest(
                    json.optString("engine", engineId),
                    json.optBoolean("chinese", chinese),
                    OcrOptions(
                        OcrMode.parse(json.optString("mode", "BALANCED")),
                        json.optBoolean("voting", true),
                    ),
                )
    }
}
