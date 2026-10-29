package cn.paperforge.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Local preferences are read by camera, import, export and Compose from one shared source. */
object UserSettings {
    private val defaults
        get() = SettingKeys.defaults

    lateinit var prefs: SharedPreferences
        private set

    val revision = MutableStateFlow(0)

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences("preferences", 0)
        if (!prefs.getBoolean("scanExportOcrOptInV1", false))
            prefs
                .edit()
                .putBoolean("searchable", false)
                .putBoolean("scanExportOcrOptInV1", true)
                .apply()
        if (!prefs.contains("autoShutter"))
            prefs
                .edit()
                .putBoolean(
                    "autoShutter",
                    context.getSharedPreferences("camera", 0).getBoolean("auto", true),
                )
                .apply()
    }

    operator fun <T : Any> get(key: SettingKey<T>): T =
        key.read(if (::prefs.isInitialized) prefs else null)

    fun <T : Any> set(key: SettingKey<T>, value: T) {
        key.write(prefs.edit(), value).apply()
        changed()
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
        require(root.optInt("format") == 1) { "不是支持的PaperForge设置文件" }
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
            SettingKeys.all.filterIsInstance<SettingKey.Number>().associate { it.name to it.range }
        val options =
            SettingKeys.all
                .filterIsInstance<SettingKey.Text>()
                .filter { it.choices.isNotEmpty() }
                .associate { it.name to it.choices }
        val booleans = SettingKeys.all.filterIsInstance<SettingKey.Bool>().map { it.name }.toSet()
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
