package cn.paperforge.app.model

import cn.paperforge.app.UserSettings
import cn.paperforge.app.data.SettingsProfiles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * Export choices. [resolution], [quality] and [lossless] are the three independent page-image settings described in
 * PdfImageSettings; [dpi] belongs to a CUSTOM resolution, which is kept as chosen and shown only where it is in use.
 */
data class ScanExportOptions(
    val paper: String = "Auto",
    val resolution: String = "ORIGINAL",
    val dpi: Int = 300,
    val quality: Int = 85,
    val searchable: Boolean = false,
    val format: String = "PDF",
    val naming: String = "{title}",
    val destination: String = "",
    val lossless: Boolean = false,
) {
    val effectiveResolution
        get() = if (resolution == "CUSTOM") "DPI:$dpi" else resolution

    val imageSettings
        get() = cn.paperforge.app.pdf.PdfImageSettings(quality, effectiveResolution, lossless)

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
            .put("losslessImages", lossless)
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
                // Presets saved before the redesign carry the old names; they keep their pixel size.
                cn.paperforge.app.pdf.PdfImageSettings.normalize(j.optString("resolution", "ORIGINAL")),
                j.optInt("customDpi", 300).coerceIn(72, 600),
                j.optInt("quality", 85).coerceIn(30, 100),
                j.optBoolean("searchable", false),
                j.optString("exportFormat", "PDF"),
                j.optString("exportNaming", "{title}"),
                j.optString("exportDestination"),
                // Old presets had no switch: quality 100 was 无损存档.
                if (j.has("losslessImages")) j.optBoolean("losslessImages") else j.optInt("quality", 85) >= 100,
            )
    }
}
