package cn.paperforge.app.pdf

import org.json.JSONObject

data class StitchSeam(
    val overlap: Float = 0f,
    val shift: Float = 0f,
    val mode: String = "SEQUENTIAL",
    val confidence: Float = 0f,
    val separation: Float = 0f,
) {
    fun json() =
        JSONObject()
            .put("overlap", overlap)
            .put("shift", shift)
            .put("mode", mode)
            .put("confidence", confidence)
            .put("separation", separation)

    companion object {
        fun decode(j: JSONObject?) =
            if (j == null) StitchSeam()
            else
                StitchSeam(
                    j.optDouble("overlap", 0.0)
                        .toFloat()
                        .takeIf { it.isFinite() }
                        ?.coerceIn(0f, .95f) ?: 0f,
                    j.optDouble("shift", 0.0)
                        .toFloat()
                        .takeIf { it.isFinite() }
                        ?.coerceIn(-.2f, .2f) ?: 0f,
                    j.optString("mode", "SEQUENTIAL"),
                    j.optDouble("confidence", 0.0).toFloat(),
                    j.optDouble("separation", 0.0).toFloat(),
                )
    }
}
