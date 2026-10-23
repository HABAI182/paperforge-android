package cn.paperforge.app.model

import org.json.JSONArray
import org.json.JSONObject

data class OcrLine(val text: String,val corners: List<PointN>)
data class OcrLayout(val width: Int,val height: Int,val lines: List<OcrLine>) {
    val text get()=lines.joinToString("\n") { it.text }
    fun encode()=JSONObject().put("width",width).put("height",height).put("lines",JSONArray().apply {
        lines.forEach { line -> put(JSONObject().put("text",line.text).put("corners",JSONArray().apply { line.corners.forEach { put(JSONArray(listOf(it.x,it.y))) } })) }
    }).toString()
    companion object {
        fun decode(json: String): OcrLayout {
            val o=JSONObject(json); val a=o.getJSONArray("lines")
            return OcrLayout(o.getInt("width"),o.getInt("height"),(0 until a.length()).map { i ->
                val line=a.getJSONObject(i); val p=line.getJSONArray("corners")
                OcrLine(line.getString("text"),(0 until p.length()).map { n -> p.getJSONArray(n).let { PointN(it.getDouble(0).toFloat(),it.getDouble(1).toFloat()) } })
            })
        }
    }
}
