package cn.paperforge.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.parsePageRange
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

object PdfEngine {
    suspend fun scan(pages: List<PageRow>,output: File,paper: String="A4",quality: Int=90,progress: suspend (Int)->Unit={}) {
        require(pages.isNotEmpty()) { "请先添加页面" }
        atomic(output) { temp ->
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                pages.forEachIndexed { index,page ->
                    coroutineContext.ensureActive()
                    val bitmap=ScanProcessor.render(page,if(quality<70) 1600 else 2800)
                    try {
                        val size=when(paper) { "A5" -> PDRectangle.A5; "Letter" -> PDRectangle.LETTER; "Original" -> PDRectangle(bitmap.width*.24f,bitmap.height*.24f); else -> PDRectangle.A4 }
                        val pdfPage=PDPage(size); doc.addPage(pdfPage)
                        val image=JPEGFactory.createFromImage(doc,bitmap,quality/100f)
                        val margin=if(paper=="Original") 0f else 12f
                        val scale=minOf((size.width-2*margin)/bitmap.width,(size.height-2*margin)/bitmap.height)
                        val w=bitmap.width*scale; val h=bitmap.height*scale
                        PDPageContentStream(doc,pdfPage).use { it.drawImage(image,(size.width-w)/2,(size.height-h)/2,w,h) }
                    } finally { bitmap.recycle() }
                    progress((index+1)*100/pages.size)
                }
                doc.documentInformation.title=output.nameWithoutExtension
                doc.documentInformation.creator="PaperForge 纸匠"
                doc.save(temp)
            }
        }
    }
    fun merge(inputs: List<File>,output: File) {
        require(inputs.size>=2) { "请选择至少两份 PDF" }
        atomicSync(output) { tmp ->
            val merger=PDFMergerUtility(); inputs.forEach { merger.addSource(it) }; merger.destinationFileName=tmp.path
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        }
    }
    fun extract(input: File,range: String,output: File,rotation: Int=0) {
        atomicSync(output) { tmp ->
            PDDocument.load(input,MemoryUsageSetting.setupTempFileOnly()).use { src ->
                PDDocument().use { dst ->
                    parsePageRange(range,src.numberOfPages).forEach { n ->
                        val original=src.getPage(n)
                        dst.importPage(original).also { it.resources=original.resources; it.rotation=(it.rotation+rotation)%360 }
                    }
                    dst.save(tmp)
                }
            }
        }
    }
    fun text(input: File): String=PDDocument.load(input,MemoryUsageSetting.setupTempFileOnly()).use { PDFTextStripper().apply { sortByPosition=true }.getText(it) }
    suspend fun compress(input: File,output: File,quality: Int,progress: suspend (Int)->Unit={}) {
        // Explicit image PDF conversion. Used only when the UI explains loss of text/annotations.
        atomic(output) { tmp ->
            ParcelFileDescriptor.open(input,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                        for(n in 0 until renderer.pageCount) {
                            coroutineContext.ensureActive()
                            renderer.openPage(n).use { page ->
                                val scale=(if(quality<60) 1100f else 1800f)/maxOf(page.width,page.height)
                                val b=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                                try {
                                    b.eraseColor(Color.WHITE); page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    val p=PDPage(PDRectangle(page.width.toFloat(),page.height.toFloat())); doc.addPage(p)
                                    PDPageContentStream(doc,p).use { it.drawImage(JPEGFactory.createFromImage(doc,b,quality/100f),0f,0f,p.mediaBox.width,p.mediaBox.height) }
                                } finally { b.recycle() }
                            }
                            progress((n+1)*100/renderer.pageCount)
                        }
                        doc.save(tmp)
                    }
                }
            }
        }
    }
    private fun verify(file: File) { PDDocument.load(file,MemoryUsageSetting.setupTempFileOnly()).use { require(it.numberOfPages>0) }; FileOutputStream(file,true).use { it.fd.sync() } }
    private suspend fun atomic(output: File,write: suspend (File)->Unit) {
        val tmp=File(output.path+".part")
        try { write(tmp); verify(tmp); check(tmp.renameTo(output)) { "保存 PDF 失败" } } finally { tmp.delete() }
    }
    private fun atomicSync(output: File,write: (File)->Unit) {
        val tmp=File(output.path+".part")
        try { write(tmp); verify(tmp); check(tmp.renameTo(output)) { "保存 PDF 失败" } } finally { tmp.delete() }
    }
}
