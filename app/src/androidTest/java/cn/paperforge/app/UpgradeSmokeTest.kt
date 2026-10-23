package cn.paperforge.app

import android.graphics.*
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.BundledOcrEngine
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpgradeSmokeTest {
    private val app=ApplicationProvider.getApplicationContext<PaperForgeApp>()
    @Test fun lowContrastAutoCropOcrAndChineseSearchablePdf()=runBlocking {
        val raw=Bitmap.createBitmap(1800,2400,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(raw); canvas.drawColor(Color.rgb(180,180,180))
        val paper=Bitmap.createBitmap(1400,2000,Bitmap.Config.ARGB_8888)
        val sheet=Canvas(paper); sheet.drawColor(Color.rgb(211,211,211))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(35,35,35); textSize=76f; typeface=Typeface.createFromAsset(app.assets,"fonts/PaperForgeCJK-Regular.ttf") }
        sheet.drawText("纸匠自动扫描与离线识别",90f,220f,paint)
        sheet.drawText("中文识别测试 2026",90f,360f,paint)
        paint.textSize=54f; sheet.drawText("PAPERFORGE OCR 123456",90f,520f,paint)
        for(i in 0..12) sheet.drawText("清晰讲义 · 第 ${i+1} 行",90f,700f+i*80,paint)
        val corners=floatArrayOf(150f,200f,1620f,120f,1720f,2160f,210f,2290f)
        val matrix=Matrix().apply { setPolyToPoly(floatArrayOf(0f,0f,1400f,0f,1400f,2000f,0f,2000f),0,corners,0,4) }
        canvas.drawBitmap(paper,matrix,null); paper.recycle()
        val file=File(app.repository.exports,"acceptance-020-low-contrast.jpg"); file.outputStream().use { raw.compress(Bitmap.CompressFormat.JPEG,96,it) }
        val q=try {
            val detected=ScanProcessor.detect(raw) ?: error("Low contrast paper not automatically detected")
            val enhanced=ScanProcessor.transform(raw,detected,"AUTO")
            try {
                val background=Color.red(enhanced.getPixel((enhanced.width*.07f).toInt(),(enhanced.height*.06f).toInt()))
                assertTrue("Paper background acquired a grey halo: $background",background>248)
                File(app.repository.exports,"acceptance-020-enhanced.png").outputStream().use { enhanced.compress(Bitmap.CompressFormat.PNG,100,it) }
            } finally { enhanced.recycle() }; detected
        } finally { raw.recycle() }
        q.points.forEachIndexed { i,p -> assertEquals(corners[i*2]/1800,p.x,.035f); assertEquals(corners[i*2+1]/2400,p.y,.035f) }
        val page=PageRow("ocr-new","new",0,file.path,corners=q.encode(),filter="BW",brightness=-.45f)
        val result=BundledOcrEngine().layout(page)
        assertTrue("OCR result: ${result.text}",result.text.contains("中文识别")); assertTrue(result.text.contains("PAPERFORGE"))
        File(app.repository.exports,"acceptance-020-ocr.txt").writeText(result.text)
        val pdf=File(app.repository.exports,"acceptance-020-searchable.pdf")
        PdfEngine.scan(listOf(page.copy(filter="AUTO",brightness=0f,ocrLayout=result.encode(),ocrText=result.text)),pdf,context=app,searchable=true)
        val text=PdfEngine.text(pdf); assertTrue("Extracted: $text",text.contains("中文识别"))
        val hits=PdfSearch.find(pdf,"中文识别"); assertTrue(hits.isNotEmpty()); assertTrue(hits.first().bottom>hits.first().top)
        val first=hits.first(); val center=PointN((first.left+first.right)/2,(first.top+first.bottom)/2)
        for(angle in listOf(90,180,270)) {
            val rotated=File(app.cacheDir,"search-$angle.pdf"); PdfEngine.extract(pdf,"",rotated,angle)
            val hit=PdfSearch.find(rotated,"中文识别").single()
            val expected=PdfCoordinates.unrotate(center,360-angle)
            assertEquals("Search x rotation $angle",expected.x,(hit.left+hit.right)/2,.025f); assertEquals("Search y rotation $angle",expected.y,(hit.top+hit.bottom)/2,.025f)
        }
        Unit
    }
    @Test fun pdfToolsSignatureRedactionAndEncryption()=runBlocking {
        val source=File(app.repository.exports,"acceptance-020-native.pdf")
        PDDocument().use { doc -> val page=PDPage(PDRectangle.A4); doc.addPage(page); PDPageContentStream(doc,page).use { c -> c.beginText(); c.setFont(PDType1Font.HELVETICA,24f); c.newLineAtOffset(50f,700f); c.showText("SECRET 1234 PUBLIC"); c.endText() }; doc.documentInformation.author="PRIVATE AUTHOR"; doc.save(source) }
        val marked=File(app.repository.exports,"acceptance-020-marked.pdf")
        PdfMarks.save(app,source,marked,listOf(PdfMark(0,"text",listOf(PointN(.1f,.3f)),text="中文批注"),PdfMark(0,"signature",listOf(PointN(.1f,.4f),PointN(.2f,.45f),PointN(.3f,.4f)))))
        assertTrue(PdfEngine.text(marked).contains("中文批注")); assertTrue(PdfEngine.text(marked).contains("SECRET"))
        val redacted=File(app.repository.exports,"acceptance-020-redacted.pdf")
        PdfMarks.save(app,source,redacted,listOf(PdfMark(0,"redact",listOf(PointN(.06f,.12f),PointN(.83f,.20f)))))
        assertTrue(PdfEngine.text(redacted).isBlank()); PDDocument.load(redacted).use { assertNull(it.documentInformation.author); assertNull(it.documentCatalog.acroForm) }
        val encrypted=File(app.repository.exports,"acceptance-020-encrypted.pdf"); PdfTools.encrypt(marked,encrypted,"local-pass",false,false)
        assertTrue(runCatching { PDDocument.load(encrypted).close() }.isFailure)
        PDDocument.load(encrypted,"local-pass").use { assertTrue(it.isEncrypted); assertFalse(it.currentAccessPermission.canPrint()) }
        val nup=File(app.cacheDir,"nup.pdf"); PdfTools.nup(source,nup,2); assertTrue(PdfEngine.text(nup).contains("PUBLIC"))
        val water=File(app.cacheDir,"watermark.pdf"); PdfTools.watermark(app,source,water,"内部资料"); val waterText=PdfEngine.text(water); assertTrue("Watermark text: $waterText","内部资料".all { it in waterText })
        val images=File(app.cacheDir,"pages.zip"); PdfTools.images(source,images,"","png"); java.util.zip.ZipFile(images).use { assertEquals(1,it.size()) }
        val formSource=File(app.cacheDir,"form.pdf")
        PDDocument().use { doc ->
            val page=PDPage(PDRectangle.A4); doc.addPage(page)
            val form=com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(doc); doc.documentCatalog.acroForm=form
            form.defaultResources=PDResources().also { it.put(com.tom_roush.pdfbox.cos.COSName.getPDFName("Helv"),PDType1Font.HELVETICA) }; form.defaultAppearance="/Helv 12 Tf 0 g"
            val field=com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField(form); field.partialName="name"; field.widgets.first().rectangle=PDRectangle(50f,600f,300f,40f); field.widgets.first().page=page; page.annotations.add(field.widgets.first()); form.fields.add(field); doc.save(formSource)
        }
        val filled=File(app.cacheDir,"filled.pdf"); PdfTools.fillForms(formSource,filled,mapOf("name" to "纸匠中文"),app)
        assertEquals("纸匠中文",PdfTools.forms(filled).single().second)
        val arranged=File(app.cacheDir,"arranged.pdf"); PdfEngine.organize(source,arranged,listOf(0),mapOf(0 to 90)); PDDocument.load(arranged).use { assertEquals(90,it.getPage(0).rotation) }; assertTrue(PdfEngine.text(arranged).contains("SECRET"))
        Unit
    }
    @Test fun historyAndBackupRestoreRetainOriginals()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(app,PaperDatabase::class.java).build()
        try {
            val repo=Repository(app,db); val doc=repo.newScan(); val image=repo.captureFile(doc.id)
            Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(Color.WHITE); image.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG,90,it) }; b.recycle() }
            val page=repo.addCapture(doc.id,image); repo.savePage(page.copy(filter="CLEAN")); repo.undo(doc.id); assertEquals("AUTO",repo.dao.pages(doc.id).single().filter); repo.undo(doc.id,true); assertEquals("CLEAN",repo.dao.pages(doc.id).single().filter)
            repo.duplicate(repo.dao.pages(doc.id).single()); repo.deletePage(repo.dao.pages(doc.id).first()); assertTrue(image.exists())
            val backup=File(app.cacheDir,"backup-smoke.zip"); BackupStore.backup(repo,backup); assertEquals(1,BackupStore.restore(repo,Uri.fromFile(backup)))
            val all=repo.dao.allDocuments(); assertEquals(2,all.size); val restored=repo.dao.pages(all.first { it.id!=doc.id }.id).single(); assertNotEquals(image.path,restored.original); assertTrue(File(restored.original).exists())
            all.forEach { repo.deleteDocument(it) }
        } finally { db.close() }
    }
}
