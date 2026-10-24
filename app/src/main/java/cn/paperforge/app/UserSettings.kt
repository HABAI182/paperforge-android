package cn.paperforge.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Local preferences are read by camera, import, export and Compose from one shared source. */
object UserSettings {
    private val defaults: Map<String, Any> =
        linkedMapOf(
            "detector" to "NEURAL",
            "outlineWeight" to 3f,
            "outlineSwitchFrames" to 2f,
            "dewarpStrength" to 1f,
            "autoShutter" to true,
            "reviewAfterCapture" to false,
            "lockCrop" to true,
            "showOutline" to true,
            "strictQuality" to false,
            "captureArea" to .08f,
            "captureConfidence" to .60f,
            "stableMs" to 600f,
            "cooldownMs" to 1500f,
            "analysisMs" to 160f,
            "cameraJpeg" to 97f,
            "filter" to "AUTO",
            "enhanceStrength" to 1f,
            "brightness" to 0f,
            "contrast" to 1f,
            "ocrLanguage" to "CHINESE",
            "searchable" to true,
            "paper" to "Auto",
            "resolution" to "STANDARD",
            "customDpi" to 300f,
            "quality" to 90f,
            "theme" to "SYSTEM",
            "accent" to "GREEN",
            "dynamicColor" to false,
            "fontScale" to 1f,
            "compactRows" to false,
            "recentCount" to 6f,
            "homeTools" to "images,pdf,ocr,merge,compress,tools",
        )
    lateinit var prefs: SharedPreferences
        private set

    val revision = MutableStateFlow(0)

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences("preferences", 0)
        if (!prefs.contains("autoShutter"))
            prefs
                .edit()
                .putBoolean(
                    "autoShutter",
                    context.getSharedPreferences("camera", 0).getBoolean("auto", true),
                )
                .apply()
    }

    fun string(key: String, default: String) =
        if (::prefs.isInitialized) prefs.getString(key, default) ?: default else default

    fun bool(key: String, default: Boolean) =
        if (::prefs.isInitialized) prefs.getBoolean(key, default) else default

    fun number(key: String, default: Float) =
        if (::prefs.isInitialized) prefs.getFloat(key, default) else default

    fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        changed()
    }

    fun set(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        changed()
    }

    fun set(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
        changed()
    }

    fun reset() {
        prefs.edit().clear().putBoolean("autoShutter", true).apply()
        changed()
    }

    fun exportJson() =
        JSONObject()
            .put("format", 1)
            .put("preferences", JSONObject(defaults + prefs.all.filterKeys { it in defaults }))
            .toString(2)

    fun importJson(text: String): Int {
        val root = JSONObject(text)
        require(root.optInt("format") == 1) { "不是支持的纸匠设置文件" }
        val values = root.getJSONObject("preferences")
        val ranges =
            mapOf(
                "stableMs" to 300f..2000f,
                "captureArea" to 0.05f..0.4f,
                "captureConfidence" to 0.45f..0.95f,
                "cooldownMs" to 800f..5000f,
                "analysisMs" to 100f..500f,
                "cameraJpeg" to 75f..100f,
                "enhanceStrength" to 0f..1f,
                "brightness" to -0.5f..0.5f,
                "contrast" to 0.5f..1.8f,
                "customDpi" to 72f..600f,
                "quality" to 40f..100f,
                "fontScale" to 0.85f..1.3f,
                "recentCount" to 4f..20f,
                "outlineWeight" to 1f..8f,
                "outlineSwitchFrames" to 1f..6f,
                "dewarpStrength" to 0f..1f,
            )
        val options =
            mapOf(
                "detector" to setOf("NEURAL", "HYBRID", "STANDARD"),
                "filter" to
                    setOf("ORIGINAL", "AUTO", "TEXT", "CLEAN", "GRAY", "BW", "COLOR", "WHITEBOARD"),
                "ocrLanguage" to setOf("CHINESE", "LATIN"),
                "paper" to setOf("Auto", "A4", "A5", "Letter"),
                "resolution" to setOf("DRAFT", "STANDARD", "HIGH", "CUSTOM"),
                "theme" to setOf("SYSTEM", "LIGHT", "DARK", "AMOLED"),
                "accent" to setOf("GREEN", "BLUE", "PURPLE", "ORANGE"),
            )
        val booleans =
            setOf(
                "autoShutter",
                "reviewAfterCapture",
                "lockCrop",
                "showOutline",
                "strictQuality",
                "searchable",
                "compactRows",
                "dynamicColor",
            )
        val edit = prefs.edit()
        var count = 0
        values.keys().forEach { key ->
            val value = values.get(key)
            when {
                key in ranges && value is Number && value.toFloat().isFinite() ->
                    edit.putFloat(key, value.toFloat().coerceIn(ranges.getValue(key)))
                key in options && value is String && value in options.getValue(key) ->
                    edit.putString(key, value)
                key in booleans && value is Boolean -> edit.putBoolean(key, value)
                key == "homeTools" && value is String ->
                    edit.putString(
                        key,
                        value
                            .split(',')
                            .filter {
                                it in setOf("images", "pdf", "ocr", "merge", "compress", "tools")
                            }
                            .distinct()
                            .joinToString(","),
                    )
                else -> return@forEach
            }
            count++
        }
        edit.apply()
        changed()
        return count
    }

    private fun changed() {
        revision.value += 1
    }
}
