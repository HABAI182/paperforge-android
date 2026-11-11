package cn.paperforge.app.imaging

import cn.paperforge.app.data.PageRow
import org.json.JSONObject

/** A persisted accepted crop must not grow implicitly while rendering or preparing a flatten job. */
internal fun PageRow.hasExactCropFrame(): Boolean {
    if (detectionMethod?.contains(RuleFrame.VERSION) == true) return true
    val metadata = runCatching { JSONObject(processingMetadata ?: "{}") }.getOrNull() ?: return false
    return metadata.optBoolean("exactFrame", false) ||
        metadata.optJSONObject("capture")?.optBoolean("exactFrame", false) == true ||
        metadata.optJSONObject("flatDocument")?.optBoolean("exactFrame", false) == true
}
