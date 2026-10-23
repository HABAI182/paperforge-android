package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.model.PointN
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlinx.coroutines.ensureActive
import org.json.*
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.*

data class PdfMark(val page: Int,val kind: String,val points: List<PointN>,val color: Int=Color.RED,val width: Float=.004f,val text: String="") {
    fun json()=JSONObject().put("page",page).put("kind",kind).put("color",color).put("width",width).put("text",text).put("points",JSONArray(points.map { JSONArray(listOf(it.x,it.y)) }))
    companion object {
        fun decode(array: JSONArray)=List(array.length()) { n -> array.getJSONObject(n).let { o ->
            val p=o.getJSONArray("points")
            PdfMark(o.getInt("page"),o.getString("kind"),List(p.length()) { i -> PointN(p.getJSONArray(i).getDouble(0).toFloat(),p.getJSONArray(i).getDouble(1).toFloat()) },o.optInt("color",Color.RED),o.optDouble("width",.004).toFloat(),o.optString("text"))
        } }
    }
}

object PdfMarks {
    /** Ordinary marks are flattened into a copy. A redaction rebuilds every page from pixels;
     * no original content streams, attachments, hidden text or metadata enter that output. */
    suspend fun save(context: Context,input: File,output: File,marks: List<PdfMark>,progress: suspend (Int)->Unit={}) {
        require(marks.isNotEmpty()) { "请先添加批注" }
        if(marks.any { it.kind=="redact" }) { raster(input,output,marks,progress); return }
        PdfTools.write(input,output) { doc ->
            val font=if(marks.any { it.kind=="text" }) PdfTextLayer.font(context,doc) else null
            marks.groupBy { it.page }.forEach { (index,items) ->
                require(index in 0 until doc.numberOfPages)
                val page=doc.getPage(index); val box=page.cropBox
                PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true).use { c ->
                    items.forEach { mark ->
                        if(mark.points.isEmpty()) return@forEach
                        val points=mark.points.map { p -> PdfCoordinates.unrotate(p,page.rotation).let { PointF(box.lowerLeftX+it.x*box.width,box.lowerLeftY+(1-it.y)*box.height) } }
                        c.saveGraphicsState(); c.setStrokingColor(Color.red(mark.color),Color.green(mark.color),Color.blue(mark.color)); c.setNonStrokingColor(Color.red(mark.color),Color.green(mark.color),Color.blue(mark.color))
                        c.setLineWidth(mark.width*min(box.width,box.height)); c.setLineCapStyle(1); c.setLineJoinStyle(1)
                        when(mark.kind) {
                            "rect","highlight" -> {
                                val a=points.first(); val b=points.last()
                                if(mark.kind=="highlight") c.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant=.3f })
                                c.addRect(min(a.x,b.x),min(a.y,b.y),abs(b.x-a.x),abs(b.y-a.y))
                                if(mark.kind=="highlight") c.fill() else c.stroke()
                            }
                            "text" -> {
                                val p=points.first(); c.beginText(); c.setFont(font!!,max(10f,mark.width*box.width*5))
                                val radians=Math.toRadians(-page.rotation.toDouble())
                                c.setTextMatrix(com.tom_roush.pdfbox.util.Matrix.getRotateInstance(radians,p.x,p.y)); c.showText(PdfTextLayer.safe(font,mark.text.replace('\n',' '))); c.endText()
                            }
                            else -> { c.moveTo(points.first().x,points.first().y); points.drop(1).forEach { c.lineTo(it.x,it.y) }; if(points.size==1) c.lineTo(points.first().x+.1f,points.first().y); c.stroke() }
                        }; c.restoreGraphicsState()
                    }
                }
            }
        }
    }
    private suspend fun raster(input: File,output: File,marks: List<PdfMark>,progress: suspend (Int)->Unit) {
        val part=File(output.path+".part")
        try {
            ParcelFileDescriptor.open(input,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
                PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                    for(index in 0 until renderer.pageCount) {
                        coroutineContext.ensureActive()
                        renderer.openPage(index).use { source ->
                            val scale=2400f/max(source.width,source.height)
                            val bitmap=Bitmap.createBitmap((source.width*scale).roundToInt(),(source.height*scale).roundToInt(),Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE); source.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val canvas=Canvas(bitmap)
                                marks.filter { it.page==index }.forEach { mark -> draw(canvas,bitmap.width.toFloat(),bitmap.height.toFloat(),mark) }
                                val page=PDPage(PDRectangle(source.width.toFloat(),source.height.toFloat())); doc.addPage(page)
                                PDPageContentStream(doc,page).use { it.drawImage(JPEGFactory.createFromImage(doc,bitmap,.98f),0f,0f,page.mediaBox.width,page.mediaBox.height) }
                            } finally { bitmap.recycle() }
                        }; progress((index+1)*100/renderer.pageCount)
                    }; doc.save(part)
                }
            } }
            PDDocument.load(part).use { require(it.numberOfPages>0) }; check(part.renameTo(output))
        } finally { part.delete() }
    }
    private fun draw(canvas: Canvas,w: Float,h: Float,mark: PdfMark) {
        if(mark.points.isEmpty()) return
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=mark.color; strokeWidth=mark.width*min(w,h); strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
        val a=mark.points.first(); val b=mark.points.last()
        when(mark.kind) {
            "rect","highlight","redact" -> {
                paint.style=if(mark.kind=="rect") Paint.Style.STROKE else Paint.Style.FILL
                if(mark.kind=="highlight") paint.alpha=78
                if(mark.kind=="redact") { paint.color=Color.BLACK; paint.alpha=255 }
                canvas.drawRect(min(a.x,b.x)*w,min(a.y,b.y)*h,max(a.x,b.x)*w,max(a.y,b.y)*h,paint)
            }
            "text" -> { paint.textSize=max(10f,mark.width*w*5); canvas.drawText(mark.text,a.x*w,a.y*h,paint) }
            else -> { paint.style=Paint.Style.STROKE; val path=Path(); mark.points.forEachIndexed { i,p -> if(i==0) path.moveTo(p.x*w,p.y*h) else path.lineTo(p.x*w,p.y*h) }; canvas.drawPath(path,paint) }
        }
    }
}
