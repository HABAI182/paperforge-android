package cn.paperforge.app.reader

import cn.paperforge.app.model.PageViewport
import org.json.JSONArray
import org.json.JSONObject

data class PageInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    fun viewport() = PageViewport(left, top, 1 - right, 1 - bottom)
}

data class ReaderViewOptions(
    val fitWidth: Boolean = false,
    val trims: Map<String, PageInsets> = emptyMap(),
    val preserveImageColor: Boolean = true,
    val facingPages: Boolean = false,
    val coverAlone: Boolean = true,
    val rightToLeft: Boolean = false,
) {
    fun insets(page: Int) =
        trims["page:$page"]
            ?: trims[if (page % 2 == 0) "odd" else "even"]
            ?: trims["all"]
            ?: PageInsets()

    fun viewport(page: Int) = insets(page).viewport()

    fun encode() =
        JSONObject()
            .put("fitWidth", fitWidth)
            .put("preserveImageColor", preserveImageColor)
            .put("facingPages", facingPages)
            .put("coverAlone", coverAlone)
            .put("rightToLeft", rightToLeft)
            .put(
                "trims",
                JSONObject().apply {
                    trims.forEach { (key, value) ->
                        put(
                            key,
                            JSONArray(listOf(value.left, value.top, value.right, value.bottom)),
                        )
                    }
                },
            )

    companion object {
        fun decode(json: JSONObject): ReaderViewOptions {
            val values = mutableMapOf<String, PageInsets>()
            json.optJSONObject("trims")?.let { items ->
                items.keys().forEach { key ->
                    val array = items.optJSONArray(key)
                    if (
                        array != null &&
                            array.length() == 4 &&
                            (key in setOf("all", "odd", "even") || key.startsWith("page:"))
                    ) {
                        fun number(index: Int) =
                            array
                                .optDouble(index, 0.0)
                                .toFloat()
                                .takeIf { it.isFinite() }
                                ?.coerceIn(0f, .4f) ?: 0f
                        values[key] = PageInsets(number(0), number(1), number(2), number(3))
                    }
                }
            }
            return ReaderViewOptions(
                json.optBoolean("fitWidth", false),
                values,
                json.optBoolean("preserveImageColor", true),
                json.optBoolean("facingPages", false),
                json.optBoolean("coverAlone", true),
                json.optBoolean("rightToLeft", false),
            )
        }
    }
}
