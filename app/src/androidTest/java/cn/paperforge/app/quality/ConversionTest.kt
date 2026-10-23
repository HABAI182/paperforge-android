package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class ConversionTest {
    @Test fun paragraphAndReferenceImageDocxAndNestedImageExtraction()=runBlocking {
        val root=File(ApplicationProvider.getApplicationContext<PaperForgeApp>().filesDir,"quality-conversion").apply { mkdirs() };val input=File(root,"source.pdf")
        PDDocument().use { doc ->
            val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            val image=try { PdfImageEncoder.jpeg(doc,bitmap,false,90,150) } finally { bitmap.recycle() }
            val form=PDFormXObject(doc).apply { resources=PDResources();bBox=PDRectangle(100f,100f) }
            form.cosObject.createOutputStream(COSName.FLATE_DECODE).use { output -> PDPageContentStream(doc,form,output).use { it.drawImage(image,0f,0f,100f,100f) } }
            repeat(2) { n ->
                val page=PDPage(PDRectangle.A4);doc.addPage(page)
                PDPageContentStream(doc,page).use { stream ->
                    stream.beginText();stream.setFont(PDType1Font.HELVETICA,14f);stream.newLineAtOffset(30f,780f);stream.showText("First line page ${n+1}");stream.newLineAtOffset(0f,-18f);stream.showText("continues the paragraph.");stream.newLineAtOffset(0f,-50f);stream.showText("Second paragraph & <XML>.");stream.endText();stream.drawForm(form)
                }
            };doc.save(input)
        }
        for(kind in listOf("txt","html","docx","docx-image")) {
            val output=File(root,"result-$kind."+if(kind.startsWith("docx")) "docx" else kind)
            PdfTextExport.export(input,output,kind)
            assertTrue(output.length()>0)
            if(kind.startsWith("docx")) ZipFile(output).use { zip ->
                val content=zip.getInputStream(zip.getEntry("word/document.xml")).readBytes()
                assertTrue(content.toString(Charsets.UTF_8).contains("continues the paragraph."));assertTrue(content.toString(Charsets.UTF_8).contains("&amp; &lt;XML&gt;"))
                val parser=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }.newDocumentBuilder();parser.parse(content.inputStream())
                assertEquals(if(kind=="docx-image") 2 else 0,zip.entries().asSequence().count { it.name.startsWith("word/media/") })
            }
        }
        val images=File(root,"nested-images.zip");PdfTools.extractImages(input,images)
        ZipFile(images).use { assertEquals(1,it.size()) }
        assertFalse(File(images.path+".part").exists())
    }
}
