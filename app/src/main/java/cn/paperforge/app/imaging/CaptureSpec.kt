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
) {
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

    companion object {
        fun current(existing: PageRow? = null): CapturePreset {
            val p = JSONObject(UserSettings.exportJson()).getJSONObject("preferences")
            return CapturePreset(
                existing?.filter ?: p.getString("filter"),
                existing?.brightness ?: p.getDouble("brightness").toFloat(),
                existing?.contrast ?: p.getDouble("contrast").toFloat(),
                existing?.enhanceStrength ?: p.getDouble("enhanceStrength").toFloat(),
                existing?.outputAspect ?: 0f,
                p.getString("detector"),
                p.getBoolean("lockCrop"),
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
                            .put("motion", r.motion),
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
