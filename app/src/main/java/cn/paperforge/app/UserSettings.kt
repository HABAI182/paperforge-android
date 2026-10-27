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
            "turnRearm" to true,
            "turnThreshold" to .06f,
            "captureTimer" to 0f,
            "scanAspect" to 0f,
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
            "ocrBackend" to "MLKIT",
            "ocrPackId" to "",
            "ocrMode" to "BALANCED",
            "ocrVoting" to true,
            "ocrOrder" to "AUTO",
            "searchable" to true,
            "paper" to "Auto",
            "resolution" to "STANDARD",
            "customDpi" to 300f,
            "quality" to 90f,
            "theme" to "SYSTEM",
            "accent" to "GREEN",
            "dynamicColor" to false,
            "reduceMotion" to false,
            "haptics" to true,
            "documentSidebar" to true,
            "fontScale" to 1f,
            "compactRows" to false,
            "documentLayout" to "LIST",
            "gridColumns" to "AUTO",
            "homeCollection" to "RECENT",
            "edgeTurn" to true,
            "readerSelectionOrder" to "0",
            "speechVoice" to "",
            "speechSpeed" to 1f,
            "recentCount" to 6f,
            "homeTools" to "images,pdf,ocr,merge,compress,tools",
            "pinnedTools" to "",
            "exportFormat" to "PDF",
            "exportNaming" to "{title}",
            "exportDestination" to "",
            "workflowRecipes" to "[]",
            "autoExportRules" to "[]",
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

    fun pinnedTools() = string("pinnedTools", "").split(',').filter { it.isNotBlank() }.toSet()

    fun toggleTool(id: String) {
        val pins = pinnedTools().toMutableSet()
        if (!pins.remove(id)) pins.add(id)
        set("pinnedTools", pins.joinToString(","))
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
        val values = normalizedValues(root.getJSONObject("preferences"))
        val edit = prefs.edit()
        values.keys().forEach { key ->
            when (val value = values.get(key)) {
                is Boolean -> edit.putBoolean(key, value)
                is Number -> edit.putFloat(key, value.toFloat())
                is String -> edit.putString(key, value)
            }
        }
        edit.apply()
        changed()
        return values.length()
    }

    fun normalizedValues(values: JSONObject): JSONObject {
        val ranges =
            mapOf(
                "stableMs" to 300f..2000f,
                "turnThreshold" to .025f..0.2f,
                "captureTimer" to 0f..10f,
                "scanAspect" to 0f..2.5f,
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
                "speechSpeed" to .5f..2f,
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
                "ocrBackend" to setOf("MLKIT", "PADDLE"),
                "ocrMode" to setOf("FAST", "BALANCED", "DETAILED"),
                "paper" to setOf("Auto", "A4", "A5", "Letter", "ID"),
                "exportFormat" to setOf("PDF", "JPG", "PNG"),
                "resolution" to setOf("DRAFT", "STANDARD", "HIGH", "CUSTOM"),
                "theme" to setOf("SYSTEM", "LIGHT", "DARK", "AMOLED"),
                "accent" to setOf("GREEN", "BLUE", "PURPLE", "ORANGE"),
                "documentLayout" to setOf("LIST", "COVERS"),
                "gridColumns" to setOf("AUTO", "2", "3", "4"),
                "readerSelectionOrder" to setOf("0", "1", "2"),
                "homeCollection" to setOf("RECENT", "FAVORITES", "INBOX", "NO_OCR", "NEEDS_EXPORT"),
            )
        val booleans =
            setOf(
                "autoShutter",
                "ocrVoting",
                "turnRearm",
                "reviewAfterCapture",
                "lockCrop",
                "showOutline",
                "strictQuality",
                "searchable",
                "compactRows",
                "edgeTurn",
                "dynamicColor",
            )
        val result = JSONObject()
        values.keys().forEach { key ->
            val value = values.get(key)
            when {
                key in ranges && value is Number && value.toFloat().isFinite() ->
                    result.put(key, value.toFloat().coerceIn(ranges.getValue(key)))
                key in options && value is String && value in options.getValue(key) ->
                    result.put(key, value)
                key in booleans && value is Boolean -> result.put(key, value)
                key == "ocrPackId" &&
                    value is String &&
                    (value.isEmpty() || value.matches(Regex("[a-z0-9-]{1,64}"))) ->
                    result.put(key, value)
                key == "exportNaming" && value is String -> result.put(key, value)
                key == "speechVoice" && value is String -> result.put(key, value.take(200))
                key in setOf("workflowRecipes", "autoExportRules") && value is String ->
                    runCatching { org.json.JSONArray(value) }
                        .getOrNull()
                        ?.let { result.put(key, it.toString()) }
                key == "exportDestination" &&
                    value is String &&
                    (value.isEmpty() || value.matches(Regex("[a-f0-9]{24}"))) ->
                    result.put(key, value)
                key == "pinnedTools" && value is String ->
                    result.put(
                        key,
                        value
                            .split(',')
                            .filter { it.matches(Regex("[a-z0-9:-]+")) }
                            .distinct()
                            .joinToString(","),
                    )
                key == "homeTools" && value is String ->
                    result.put(
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
        }
        return result
    }

    fun values(keys: Set<String>, factory: Boolean = false) =
        JSONObject().apply {
            val snapshot =
                if (factory) defaults else defaults + prefs.all.filterKeys { it in defaults }
            keys.forEach { key -> snapshot[key]?.let { put(key, it) } }
        }

    private fun changed() {
        revision.value += 1
    }
}
