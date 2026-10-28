package cn.paperforge.app.model

import cn.paperforge.app.UserSettings
import cn.paperforge.app.data.SettingsProfiles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

data class ScanExportOptions(
    val paper: String = "Auto",
    val resolution: String = "STANDARD",
    val dpi: Int = 300,
    val quality: Int = 90,
    val searchable: Boolean = false,
    val format: String = "PDF",
    val naming: String = "{title}",
    val destination: String = "",
) {
    val effectiveResolution
        get() = if (resolution == "CUSTOM") "DPI:$dpi" else resolution

    fun fileName(title: String, pages: Int, now: Long = System.currentTimeMillis()): String {
        val date = Date(now)
        return safeFileName(
            naming
                .ifBlank { "{title}" }
                .replace("{title}", title)
                .replace("{date}", SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(date))
                .replace("{time}", SimpleDateFormat("HHmmss", Locale.ROOT).format(date))
                .replace("{pages}", pages.toString())
        )
    }

    fun values() =
        JSONObject()
            .put("paper", paper)
            .put("resolution", resolution)
            .put("customDpi", dpi)
            .put("quality", quality)
            .put("searchable", searchable)
            .put("exportFormat", format)
            .put("exportNaming", naming)
            .put("exportDestination", destination)

    fun remember() {
        UserSettings.importJson(
            JSONObject().put("format", 1).put("preferences", values()).toString()
        )
    }

    companion object {
        fun current() = decode(UserSettings.values(SettingsProfiles.keys(setOf("export"))))

        fun decode(j: JSONObject) =
            ScanExportOptions(
                j.optString("paper", "Auto"),
                j.optString("resolution", "STANDARD"),
                j.optInt("customDpi", 300).coerceIn(72, 600),
                j.optInt("quality", 90).coerceIn(40, 100),
                j.optBoolean("searchable", false),
                j.optString("exportFormat", "PDF"),
                j.optString("exportNaming", "{title}"),
                j.optString("exportDestination"),
            )
    }
}
