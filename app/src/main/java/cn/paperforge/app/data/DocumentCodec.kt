package cn.paperforge.app.data

import org.json.JSONObject

object DocumentCodec {
    fun page(p: PageRow)=JSONObject().apply {
        put("id",p.id); put("documentId",p.documentId); put("position",p.position); put("original",p.original)
        put("corners",p.corners); put("filter",p.filter); put("rotation",p.rotation); put("brightness",p.brightness); put("contrast",p.contrast); put("revision",p.revision)
        put("ocrText",p.ocrText ?: JSONObject.NULL); put("ocrLayout",p.ocrLayout ?: JSONObject.NULL)
        put("detectionConfidence",p.detectionConfidence ?: JSONObject.NULL); put("detectionMethod",p.detectionMethod ?: JSONObject.NULL)
        put("deskewDegrees",p.deskewDegrees)
    }
    fun page(o: JSONObject)=PageRow(o.getString("id"),o.getString("documentId"),o.getInt("position"),o.getString("original"),o.getString("corners"),o.getString("filter"),o.getInt("rotation"),o.getDouble("brightness").toFloat(),o.getDouble("contrast").toFloat(),o.getInt("revision"),nullable(o,"ocrText"),nullable(o,"ocrLayout"),if(o.isNull("detectionConfidence")) null else o.getDouble("detectionConfidence").toFloat(),nullable(o,"detectionMethod"),o.optDouble("deskewDegrees",0.0).toFloat())
    fun document(d: DocumentRow)=JSONObject().apply { put("id",d.id); put("title",d.title); put("modified",d.modified); put("kind",d.kind); put("pdfPath",d.pdfPath ?: JSONObject.NULL); put("folder",d.folder); put("tags",d.tags); put("favorite",d.favorite); put("trashedAt",d.trashedAt ?: JSONObject.NULL) }
    fun document(o: JSONObject)=DocumentRow(o.getString("id"),o.getString("title"),o.getLong("modified"),o.getString("kind"),nullable(o,"pdfPath"),o.optString("folder"),o.optString("tags"),o.optBoolean("favorite"),if(o.isNull("trashedAt")) null else o.getLong("trashedAt"))
    private fun nullable(o: JSONObject,key: String)=if(o.isNull(key)) null else o.getString(key)
}
