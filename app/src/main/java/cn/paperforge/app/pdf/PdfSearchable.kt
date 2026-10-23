package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.ocr.BundledOcrEngine
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File

object PdfSearchable {
    suspend fun convert(context: Context,input: File,output: File,progress: suspend (Int)->Unit={}) {
        val temporary=File(output.path+".part")
        try {
            PDDocument.load(input,MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val font=PdfTextLayer.font(context,doc)
                ParcelFileDescriptor.open(input,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
                    for(i in 0 until renderer.pageCount) {
                        coroutineContext.ensureActive()
                        val text= com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage=i+1; endPage=i+1 }.getText(doc)
                        if(text.isBlank()) renderer.openPage(i).use { view ->
                            val scale=2400f/maxOf(view.width,view.height)
                            val bitmap=Bitmap.createBitmap((view.width*scale).toInt().coerceAtLeast(1),(view.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE); view.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val layout=BundledOcrEngine().recognizeBitmap(bitmap)
                                val page=doc.getPage(i); val box=page.cropBox
                                // PdfRenderer returns displayed coordinates. Undo page rotation before mapping.
                                val normalized=PdfCoordinates.unrotate(layout,page.rotation)
                                PdfTextLayer.append(doc,page,font,normalized,box.lowerLeftX,box.lowerLeftY,box.width,box.height)
                            } finally { bitmap.recycle() }
                        }
                        progress((i+1)*100/renderer.pageCount)
                    }
                } }
                doc.save(temporary)
            }
            PDDocument.load(temporary).use { require(it.numberOfPages>0) }; check(temporary.renameTo(output))
        } finally { temporary.delete() }
    }
}
