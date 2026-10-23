package cn.paperforge.app.quality

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.Quad
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ResolutionTest {
    @Test fun croppedTwelveMegapixelPhotoKeepsHighResolution() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val input = File(app.filesDir, "bench/resolution-12mp.jpg")
        val page = PageRow("high", "high", 0, input.path, filter="ORIGINAL")
        val high = ScanProcessor.render(page, 3508)
        try {
            assertTrue("${high.width}x${high.height}", maxOf(high.width, high.height) >= 3500)
            assertTrue(maxOf(high.width, high.height) <= 3508)
        } finally { high.recycle() }
        val preview = ScanProcessor.render(page, 1200)
        try { assertEquals(1200, maxOf(preview.width, preview.height)) } finally { preview.recycle() }
    }
}
