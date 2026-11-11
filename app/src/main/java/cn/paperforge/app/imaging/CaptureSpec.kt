package cn.paperforge.app.imaging

import cn.paperforge.app.UserSettings
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.*
import org.json.JSONArray
import org.json.JSONObject

data class CapturePreset(
    val filter: String,
    val brightness: Float,
    val contrast: Float,
    val strength: Float,
    val aspect: Float,
    val detector: String,
    val lockCrop: Boolean,
    val aiFinish: Boolean = true,
    val flatAspect: Float = .70710678f,
) {
    /**
     * The camera's mode rail stores its recipe in the shared settings. A gallery photo was not taken in
     * that mode, so it must not inherit it: a stale rail silently turned off flattening for every import.
     * A filter the rail cannot set was chosen in Settings, and stands.
     */
    fun forGalleryImport(): CapturePreset {
        val railFilter =
            mapOf("color" to "AUTO", "clean" to "CLEAN", "card" to "ORIGINAL")[
                UserSettings.string("captureMode", "document")]
        return if (filter == railFilter) copy(filter = "WORKBOOK", aspect = 0f) else this
    }

    fun page(id: String, document: String, position: Int, path: String) =
        PageRow(
            id,
            document,
            position,
            path,
            filter = filter,
            brightness = brightness,
            contrast = contrast,
            enhanceStrength = strength,
            outputAspect = aspect,
        )

    fun encode() =
        JSONObject()
            .put("filter", filter)
            .put("brightness", brightness)
            .put("contrast", contrast)
            .put("strength", strength)
            .put("aspect", aspect)
            .put("detector", detector)
            .put("lockCrop", lockCrop)
            .put("aiFinish", aiFinish)
            .put("flatAspect", flatAspect)

    companion object {
        fun current(existing: PageRow? = null): CapturePreset {
            val p = JSONObject(UserSettings.exportJson()).getJSONObject("preferences")
            return CapturePreset(
                if (
                    existing != null &&
                        JSONObject(existing.processingMetadata ?: "{}").has("flatDocument")
                )
                    "WORKBOOK"
                else existing?.filter ?: p.getString("filter"),
                existing?.brightness ?: p.getDouble("brightness").toFloat(),
                existing?.contrast ?: p.getDouble("contrast").toFloat(),
                existing?.enhanceStrength ?: p.getDouble("enhanceStrength").toFloat(),
                existing?.outputAspect ?: p.optDouble("scanAspect", 0.0).toFloat(),
                p.getString("detector"),
                p.getBoolean("lockCrop"),
                p.optBoolean("scanAiFinish", false),
                if (p.optString("flatPaper", "ISO") == "SOURCE") 0f else .70710678f,
            )
        }

        fun decode(j: JSONObject) =
            CapturePreset(
                j.getString("filter"),
                j.getDouble("brightness").toFloat(),
                j.getDouble("contrast").toFloat(),
                j.getDouble("strength").toFloat(),
                j.getDouble("aspect").toFloat(),
                j.getString("detector"),
                j.getBoolean("lockCrop"),
                j.optBoolean("aiFinish", false),
                j.optDouble("flatAspect", .70710678).toFloat(),
            )
    }
}

data class CaptureSpec(
    val preset: CapturePreset,
    val reference: CaptureReference?,
    val expectedTargetRevision: Int = -1,
    val expectedDraftRevision: Int = 0,
) {
    fun encode() =
        JSONObject()
            .put("format", 1)
            .put("preset", preset.encode())
            .put("expectedTargetRevision", expectedTargetRevision)
            .put("expectedDraftRevision", expectedDraftRevision)
            .apply {
                reference?.let { r ->
                    put(
                        "reference",
                        JSONObject()
                            .put("quad", r.quad.encode())
                            .put("analyzedAt", r.analyzedAt)
                            .put("requestedAt", r.requestedAt)
                            .put("width", r.width)
                            .put("height", r.height)
                            .put("viewport", r.viewport)
                            .put("sourceTimestampNs", r.sourceTimestampNs)
                            .put("confidence", r.confidence)
                            .put("scene", JSONArray(r.scene))
                            .put("paper", JSONArray(r.paper))
                            .put("motion", r.motion)
                            .put("canonicalFrame", r.canonicalFrame),
                    )
                }
            }

    companion object {
        fun decode(j: JSONObject): CaptureSpec {
            val reference =
                j.optJSONObject("reference")?.let { r ->
                    fun values(key: String) =
                        r.getJSONArray(key).let { a ->
                            (0 until a.length()).map { a.getDouble(it).toFloat() }
                        }
                    CaptureReference(
                        Quad.decode(r.getString("quad")),
                        r.getLong("analyzedAt"),
                        r.getLong("requestedAt"),
                        r.getInt("width"),
                        r.getInt("height"),
                        r.getString("viewport"),
                        r.getLong("sourceTimestampNs"),
                        r.getDouble("confidence").toFloat(),
                        values("scene"),
                        values("paper"),
                        r.optDouble("motion", 0.0).toFloat(),
                        r.optBoolean("canonicalFrame", false),
                    )
                }
            return CaptureSpec(
                CapturePreset.decode(j.getJSONObject("preset")),
                reference,
                j.optInt("expectedTargetRevision", -1),
                j.optInt("expectedDraftRevision", 0),
            )
        }
    }
}
