// Frozen PaperForge 4d56d55 baseline, test-only. Do not tune this copy.
package cn.paperforge.app.quality

import android.content.Context
import cn.paperforge.app.model.OcrLayout
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.*

object BaselinePdfTextLayer {
    fun font(context: Context,doc: PDDocument)=context.assets.open("fonts/PaperForgeCJK-Regular.ttf").use { PDType0Font.load(doc,it,true) }
    fun safe(font: PDType0Font,text: String): String = buildString {
        java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC).codePoints().toArray().forEach { code ->
            val s=String(Character.toChars(code))
            if(!Character.isISOControl(code)) append(runCatching { font.encode(s); s }.getOrDefault("?"))
        }
    }
    fun append(doc: PDDocument,page: PDPage,font: PDType0Font,layout: OcrLayout,x: Float,y: Float,w: Float,h: Float) {
        PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true).use { stream ->
            layout.lines.forEach { line ->
                if(line.corners.size!=4) return@forEach
                val p=line.corners
                val dx=(p[1].x-p[0].x)*w; val dy=-(p[1].y-p[0].y)*h
                val lineWidth=hypot(dx,dy).coerceAtLeast(1f)
                val lineHeight=hypot((p[3].x-p[0].x)*w,(p[3].y-p[0].y)*h).coerceAtLeast(1f)
                val text=safe(font,line.text); if(text.isBlank()) return@forEach
                val size=(lineHeight*.9f).coerceAtLeast(1f)
                val natural=(font.getStringWidth(text)/1000*size).coerceAtLeast(1f)
                val angle=atan2(dy,dx)
                stream.beginText(); stream.setFont(font,size); stream.setRenderingMode(RenderingMode.NEITHER)
                stream.setHorizontalScaling(lineWidth/natural*100)
                stream.setTextMatrix(Matrix(cos(angle),sin(angle),-sin(angle),cos(angle),x+p[3].x*w,y+(1-p[3].y)*h+lineHeight*.10f))
                stream.showText(text); stream.endText()
            }
        }
    }
}
