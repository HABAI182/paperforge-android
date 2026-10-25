package cn.paperforge.app.pdf

import android.graphics.Color
import org.json.JSONObject

data class LongImageOptions(
    val width: Int = 960,
    val gap: Int = 16,
    val segmentHeight: Int = 8192,
    val background: Int = Color.WHITE,
    val format: String = "png",
    val quality: Int = 92,
) {
    fun json() =
        JSONObject()
            .put("width", width)
            .put("gap", gap)
            .put("segmentHeight", segmentHeight)
            .put("background", background)
            .put("format", format)
            .put("quality", quality)

    fun validate() {
        require(
            width in 128..4096 &&
                gap in 0..1024 &&
                segmentHeight in 256..30000 &&
                format in setOf("png", "jpg") &&
                quality in 1..100
        ) {
            "长图宽度、间距、分段高度或格式设置不合适"
        }
    }

    companion object {
        fun decode(j: JSONObject) =
            LongImageOptions(
                j.optInt("width", 960),
                j.optInt("gap", 16),
                j.optInt("segmentHeight", 8192),
                j.optInt("background", Color.WHITE),
                j.optString("format", "png"),
                j.optInt("quality", 92),
            )
    }
}
