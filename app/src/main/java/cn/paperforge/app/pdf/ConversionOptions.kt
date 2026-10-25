package cn.paperforge.app.pdf

import android.graphics.Color
import org.json.JSONObject

data class TextPdfOptions(
    val fontSize: Float = 12f,
    val paper: String = "A4",
    val landscape: Boolean = false,
    val margin: Float = 44f,
    val lineSpacing: Float = 1.65f,
) {
    fun json() =
        JSONObject()
            .put("fontSize", fontSize)
            .put("paper", paper)
            .put("landscape", landscape)
            .put("margin", margin)
            .put("lineSpacing", lineSpacing)

    companion object {
        fun decode(j: JSONObject) =
            TextPdfOptions(
                j.optDouble("fontSize", 12.0).toFloat(),
                j.optString("paper", "A4"),
                j.optBoolean("landscape"),
                j.optDouble("margin", 44.0).toFloat(),
                j.optDouble("lineSpacing", 1.65).toFloat(),
            )
    }
}

data class ImageConversionOptions(
    val format: String = "jpg",
    val quality: Int = 92,
    val edge: Int = 4096,
    val keepAlpha: Boolean = true,
    val background: Int = Color.WHITE,
    val losslessWebp: Boolean = false,
) {
    fun json() =
        JSONObject()
            .put("format", format)
            .put("quality", quality)
            .put("edge", edge)
            .put("keepAlpha", keepAlpha)
            .put("background", background)
            .put("losslessWebp", losslessWebp)

    companion object {
        fun decode(j: JSONObject) =
            ImageConversionOptions(
                j.optString("format", "jpg"),
                j.optInt("quality", 92),
                j.optInt("edge", 4096),
                j.optBoolean("keepAlpha", true),
                j.optInt("background", Color.WHITE),
                j.optBoolean("losslessWebp"),
            )
    }
}
