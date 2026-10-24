package cn.paperforge.app.imaging

import cn.paperforge.app.model.CaptureReference
import org.json.JSONArray
import org.json.JSONObject

object CaptureMetadata {
    fun encode(reference: CaptureReference?, result: DetectionResult): String {
        if (reference == null) return "{}"
        return JSONObject()
            .put("format", 1)
            .put(
                "capture",
                JSONObject()
                    .put("clock", "analysis-arrival-elapsedRealtime")
                    .put("analysisMs", reference.analyzedAt)
                    .put("shutterMs", reference.requestedAt)
                    .put("ageMs", reference.age)
                    .put("sourceTimestampNs", reference.sourceTimestampNs)
                    .put("viewport", reference.viewport)
                    .put("analysisWidth", reference.width)
                    .put("analysisHeight", reference.height)
                    .put("rawQuad", reference.quad.encode())
                    .put("score", reference.confidence)
                    .put("motion", reference.motion)
                    .put("scene", JSONArray(reference.scene))
                    .put("paper", JSONArray(reference.paper))
                    .put("decision", result.method)
                    .put("diagnostics", JSONObject(result.diagnostics)),
            )
            .toString()
    }
}
