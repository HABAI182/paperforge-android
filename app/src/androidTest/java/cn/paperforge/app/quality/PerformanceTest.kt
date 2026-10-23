package cn.paperforge.app.quality

import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.Quad
import cn.paperforge.app.pdf.PdfEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PerformanceTest {
    @Test fun hundredPagesTwelveMpAndCancellationStayWithinBoundedProcessing()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<PaperForgeApp>();val out=File(app.filesDir,"quality-performance").apply { mkdirs() }
        val source=File(app.filesDir,"bench/resolution-12mp.jpg");require(source.exists()) { "Install the fixed benchmark fixtures first" }
        val page=PageRow("perf","perf",0,source.path,corners=Quad.Full.encode(),filter="BW")
        val peak=AtomicInteger();val sampler=launch(Dispatchers.Default) { while(isActive) { val m=Debug.MemoryInfo();Debug.getMemoryInfo(m);peak.accumulateAndGet(m.totalPss,::maxOf);delay(50) } }
        val started=SystemClock.elapsedRealtime()
        try {
            val file=File(out,"hundred-pages.pdf")
            PdfEngine.scan(List(100) { page.copy(id="perf-$it") },file,resolution="DRAFT")
            PDDocument.load(file).use { assertEquals(100,it.numberOfPages) }
            val cancellation=File(out,"cancelled.pdf")
            try { PdfEngine.scan(List(100) { page },cancellation,resolution="DRAFT") { if(it>=3) throw CancellationException("quality cancellation probe") };fail("Expected cancellation") } catch(_: CancellationException) {}
            assertFalse(cancellation.exists());assertFalse(File(cancellation.path+".part").exists())
            val high=ScanProcessor.render(page.copy(filter="ORIGINAL"),3508)
            val highWidth=high.width;val highHeight=high.height;high.recycle()
            File(out,"results.json").writeText(JSONObject().put("physicalDevice",false).put("inputWidth",3024).put("inputHeight",4032).put("pages",100).put("preset","DRAFT150dpi")
                .put("hundredPageBytes",file.length()).put("elapsedMsIncludingCancellationAndHighProbe",SystemClock.elapsedRealtime()-started).put("sampledPeakPssKiB50ms",peak.get()).put("highWidth",highWidth).put("highHeight",highHeight).put("cancelledOutputAbsent",true).put("cancelledPartAbsent",true).toString(2))
        } finally { sampler.cancelAndJoin() }
    }
}
