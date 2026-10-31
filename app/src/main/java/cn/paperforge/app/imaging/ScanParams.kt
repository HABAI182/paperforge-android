package cn.paperforge.app.imaging

import android.content.res.AssetManager
import cn.paperforge.app.SettingKeys
import org.json.JSONObject

/** One versioned JSON is read by Android and the desktop benchmark. */
object ScanConfig {
    private var values = JSONObject()

    fun load(assets: AssetManager) {
        values = JSONObject(assets.open("scan_params.json").bufferedReader().use { it.readText() })
        require(values.getInt("schema") == 1)
    }

    /** Benchmark-only overlay; callers restore the returned snapshot after a sweep. */
    @Synchronized
    fun replaceForBenchmark(overrides: JSONObject): JSONObject {
        val previous = JSONObject(values.toString())
        val next = JSONObject(values.toString())
        overrides.keys().forEach { next.put(it, overrides.get(it)) }
        values = next
        return previous
    }

    @Synchronized
    fun restoreAfterBenchmark(snapshot: JSONObject) {
        values = JSONObject(snapshot.toString())
    }

    fun int(name: String, fallback: Int) = values.optInt(name, fallback)

    fun float(name: String, fallback: Float) = values.optDouble(name, fallback.toDouble()).toFloat()

    fun dpi(preset: String): Int {
        if (preset.startsWith("DPI:"))
            return preset.removePrefix("DPI:").toIntOrNull()?.coerceIn(72, 600) ?: 220
        return when (preset) {
            "CUSTOM" ->
                cn.paperforge.app.UserSettings[SettingKeys.CustomDpi].toInt().coerceIn(72, 600)
            "DRAFT" -> int("draftDpi", 150)
            "HIGH" -> int("highDpi", 300)
            else -> int("standardDpi", 220)
        }
    }

    fun edge(preset: String) = (dpi(preset) * 297f / 25.4f).toInt()
}
