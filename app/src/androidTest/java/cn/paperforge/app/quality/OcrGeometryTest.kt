package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrGeometryTest {
    @Test fun cachedCorrectionTransformsPixelsAndTextTogetherAndUndoKeepsOriginal()=runBlocking {
        val repo=ApplicationProvider.getApplicationContext<PaperForgeApp>().repository
        val doc=repo.newScan();val file=repo.captureFile(doc.id)
        val bitmap=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        val originalHash=java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        val page=PageRow("geometry-${doc.id}",doc.id,0,file.path,filter="ORIGINAL")
        try {
            repo.dao.putPage(page)
            val initial=ScanProcessor.render(page,3200)
            val layout=OcrLayout(initial.width,initial.height,listOf(OcrLine("sample",listOf(PointN(.1f,.2f),PointN(.5f,.2f),PointN(.5f,.25f),PointN(.1f,.25f)))),engineId="test",sourceRevision=page.revision,orientationCorrection=90,deskewDegrees=-2f)
            initial.recycle()
            val (updated,cached)=repo.cacheOcr(page,layout)
            val rendered=ScanProcessor.render(updated,3200)
            try { assertEquals(rendered.width,cached.width);assertEquals(rendered.height,cached.height) } finally { rendered.recycle() }
            assertEquals(90,updated.rotation);assertEquals(-2f,updated.deskewDegrees,0f)
            assertEquals(updated.revision,cached.sourceRevision)
            assertEquals(cached,OcrLayout.decode(cached.encode()))
            repo.undo(doc.id)
            val restored=repo.dao.pages(doc.id).single()
            assertEquals(0,restored.rotation);assertEquals(0f,restored.deskewDegrees,0f)
            assertArrayEquals(originalHash,java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } finally { repo.deleteDocument(doc) }
    }
}
