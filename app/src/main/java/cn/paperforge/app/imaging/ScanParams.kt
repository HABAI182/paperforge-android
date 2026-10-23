package cn.paperforge.app.imaging

import android.content.res.AssetManager
import org.json.JSONObject

/** One versioned JSON is read by Android and the desktop benchmark. */
object ScanConfig {
    private var values = JSONObject()

    fun load(assets: AssetManager) {
        values = JSONObject(assets.open("scan_params.json").bufferedReader().use { it.readText() })
        require(values.getInt("schema") == 1)
    }

    fun int(name: String, fallback: Int) = values.optInt(name, fallback)

    fun float(name: String, fallback: Float) = values.optDouble(name, fallback.toDouble()).toFloat()

    fun dpi(preset: String): Int {
        if (preset.startsWith("DPI:"))
            return preset.removePrefix("DPI:").toIntOrNull()?.coerceIn(72, 600) ?: 220
        return when (preset) {
            "CUSTOM" ->
                cn.paperforge.app.UserSettings.number("customDpi", 300f).toInt().coerceIn(72, 600)
            "DRAFT" -> int("draftDpi", 150)
            "HIGH" -> int("highDpi", 300)
            else -> int("standardDpi", 220)
        }
    }

    fun edge(preset: String) = (dpi(preset) * 297f / 25.4f).toInt()
}
