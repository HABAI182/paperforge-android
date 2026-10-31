package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.share.ShareEnhance
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * #28: the share pipeline as the activity would run it — read a shared PDF, strip watermarks,
 * compress, and hand back a real file. The sample lives on the device only.
 */
@RunWith(AndroidJUnit4::class)
class SharePipelineSampleTest {
    private fun cornerDarkFraction(file: File): Double {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap =
                        Bitmap.createBitmap(
                            (page.width * 2).toInt(),
                            (page.height * 2).toInt(),
                            Bitmap.Config.ARGB_8888,
                        )
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    var dark = 0
                    var total = 0
                    for (y in (bitmap.height * .94).toInt() until bitmap.height) {
                        for (x in (bitmap.width * .7).toInt() until bitmap.width) {
                            val p = bitmap.getPixel(x, y)
                            val luma =
                                .299 * Color.red(p) + .587 * Color.green(p) + .114 * Color.blue(p)
                            if (luma < 200) dark++
                            total++
                        }
                    }
                    bitmap.recycle()
                    return if (total == 0) 0.0 else dark.toDouble() / total
                }
            }
        }
    }

    @Test
    fun processesSharedPdfLikeTheActivity() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val sample = File(app.filesDir, "samples/camscanner-sample-3.pdf")
        assertTrue("缺少设备端样本", sample.exists())
        val result =
            ShareEnhance.process(
                app,
                Uri.fromFile(sample),
                removeWatermark = true,
                compress = true,
                quality = 75,
            )
        assertTrue("成品不存在", result.file.exists() && result.file.length() > 0)
        assertTrue("未执行压缩", result.compressed)
        val report = result.watermarks ?: error("缺少水印处理报告")
        assertTrue("图片水印未移除", report.imagesRemoved >= 1)
        assertTrue("文字水印未处理", report.textCovers >= 1)
        val residual = cornerDarkFraction(result.file)
        assertTrue("成品仍有水印残留 $residual", residual < .005)
        val json =
            JSONObject()
                .put("synthetic", false)
                .put("ownerSample", "camscanner-sample-3.pdf")
                .put("compressed", result.compressed)
                .put("imagesRemoved", report.imagesRemoved)
                .put("textCovers", report.textCovers)
                .put("sourceBytes", sample.length())
                .put("outputBytes", result.file.length())
                .put("cornerDarkAfter", residual)
        File(app.filesDir, "watermark-proof/share-pipeline.json").writeText(json.toString(2))
    }
}
