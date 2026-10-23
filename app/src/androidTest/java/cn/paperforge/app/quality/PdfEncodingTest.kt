package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.pdf.PdfImageEncoder
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfEncodingTest {
    @Test fun actualGrayAndOneBitObjectsHaveNoAlphaAndRenderCorrectly() {
        val app=ApplicationProvider.getApplicationContext<PaperForgeApp>();val out=File(app.filesDir,"quality-encoding").apply { mkdirs() }
        val bitmap=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(600*800) { i -> if(i%600 in 100..499 && i/600 in 200..599) Color.BLACK else Color.WHITE }
        bitmap.setPixels(pixels,0,600,0,0,600,800)
        try {
            for(mode in listOf("BW","GRAY","AUTO")) {
                val file=File(out,"$mode.pdf")
                PDDocument().use { doc ->
                    val image=PdfImageEncoder.scan(doc,bitmap,mode,90,300)
                    assertNull(image.cosObject.getDictionaryObject(COSName.SMASK))
                    assertEquals(if(mode=="AUTO") "DeviceRGB" else "DeviceGray",image.colorSpace.name)
                    assertEquals(if(mode=="BW") 1 else 8,image.bitsPerComponent)
                    val page=PDPage(PDRectangle(144f,192f));doc.addPage(page)
                    PDPageContentStream(doc,page).use { it.drawImage(image,0f,0f,144f,192f) };doc.save(file)
                }
                ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer -> renderer.openPage(0).use { page ->
                    val rendered=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888)
                    try {
                        page.render(rendered,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        assertTrue(Color.red(rendered.getPixel(300,400))<10);assertTrue(Color.red(rendered.getPixel(10,10))>245)
                        File(out,"$mode-android.png").outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG,100,it) }
                    } finally { rendered.recycle() }
                } } }
            }
        } finally { bitmap.recycle() }
    }
}
